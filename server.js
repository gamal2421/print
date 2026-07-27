    const express = require("express");
    const axios = require("axios");
    const cors = require("cors");
    const fs = require("fs");
    const path = require("path");
    const { execFile } = require("child_process");
    const { promisify } = require("util");
    const {
        print,
        getPrinters,
        getDefaultPrinter
    } = require("pdf-to-printer");

    const app = express();
    app.use(cors());
    app.use(express.json());

    const PORT = 9999;
    const SCAN_UPLOAD_BASE_URL =
        "http://192.168.155.57:8080/ords/himu/scanner/";
    const execFileAsync = promisify(execFile);

    const REPORT_PRINTERS = {
        "2": "Microsoft Print to PDF"
    };

    let scanInProgress = false;


    // ================================
    // TEMP FOLDER
    // ================================

    const TEMP_FOLDER = path.join(process.cwd(), "temp");

    if (!fs.existsSync(TEMP_FOLDER)) {
        fs.mkdirSync(TEMP_FOLDER, { recursive: true });
    }


    // ================================
    // SCAN TO PNG
    // ================================

    const WIA_SCAN_SCRIPT = `
        $ErrorActionPreference = "Stop"
        $outputPath = [Environment]::GetEnvironmentVariable("SCAN_OUTPUT_PATH")

        if ([string]::IsNullOrWhiteSpace($outputPath)) {
            throw "SCAN_OUTPUT_PATH is required."
        }

        $pngFormat = "{B96B3CAF-0728-11D3-9D7B-0000F81EF32E}"
        $dialog = New-Object -ComObject WIA.CommonDialog
        $image = $dialog.ShowAcquireImage(1, 1, 0, $pngFormat, $true, $true, $true)

        if ($null -eq $image) {
            throw "Scan canceled."
        }

        $image.SaveFile($outputPath)
    `;


    async function scanDocumentToPng(filePath) {

        await execFileAsync(
            "powershell.exe",
            [
                "-NoProfile",
                "-STA",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                WIA_SCAN_SCRIPT
            ],
            {
                env: {
                    ...process.env,
                    SCAN_OUTPUT_PATH: filePath
                },
                maxBuffer: 1024 * 1024
            }
        );

        if (!fs.existsSync(filePath)) {
            throw new Error("Scanner did not create an image file.");
        }

    }


    async function uploadScannedImage(
        filePath,
        targetUrl,
        identifierKey,
        identifierValue
    ) {

        const imageBuffer =
            await fs.promises.readFile(filePath);

        const payload = {
            image:
                "data:image/png;base64," +
                imageBuffer.toString("base64"),
            [identifierKey]: identifierValue
        };

        return axios.post(targetUrl, payload, {
            headers: {
                "Content-Type": "application/json"
            },
            maxBodyLength: Infinity,
            maxContentLength: Infinity,
            timeout: 60000,
            validateStatus: () => true
        });

    }


    function buildScanInsertUrl(server, identifierKey) {

        if (typeof server !== "string" || !server.trim()) {
            throw new Error("server must be a non-empty URL.");
        }

        const serverUrl = new URL(server);

        if (
            serverUrl.protocol !== "http:" &&
            serverUrl.protocol !== "https:"
        ) {
            throw new Error("server must use http or https.");
        }

        serverUrl.search = "";
        serverUrl.hash = "";

        if (!serverUrl.pathname.endsWith("/")) {
            serverUrl.pathname += "/";
        }

        const scannerType =
            identifierKey
                .replace(/_id$/i, "")
                .replace(/_/g, "");

        return new URL(
            scannerType,
            serverUrl
        ).toString();

    }


    // ================================
    // BI PUBLISHER REPORT URL
    // ================================

    function buildReportURL(server, reportPath, params = {}) {

        const encodedPath =
            reportPath.replace(/\//g, "%2F");


        let url =
            `${server}/xmlpserver/${reportPath}.xdo?` +
            `_xpf=&_xpt=1` +
            `&_xdo=%2F${encodedPath}.xdo` +
            `&_xmode=3`;


        // Add report parameters

        for (const [key, value] of Object.entries(params)) {

            url +=
                `&_params${key}=${encodeURIComponent(value)}`;

        }


        // Get report name only

        const reportName =
            reportPath.split("/").pop();


        // Fixed parameters

        url += `&_xt=${reportName}`;
        url += `&_xf=pdf`;
        url += `&_xautorun=true`;


        return url;

    }



    // ================================
    // PRINT REPORT
    // ================================

    app.get("/print-report", async (req, res) => {

        let filePath;

        try {

            // ================================
            // GET PARAMETERS
            // ================================

            const server =
                req.query.server;

            const reportPath =
                req.query.report_path;


            if (!server) {

                return res
                    .status(400)
                    .send("server is required.");

            }


            if (!reportPath) {

                return res
                    .status(400)
                    .send("report_path is required.");

            }


            // ================================
            // GET REPORT PARAMETERS
            // ================================

            const queryParams = {
                ...req.query
            };


            const reportType =
                typeof queryParams.report_type === "string"
                    ? queryParams.report_type
                    : undefined;


            const printerName =
                REPORT_PRINTERS[reportType] ||
                queryParams.printer ||
                queryParams.printer_name;


            delete queryParams.server;
            delete queryParams.report_path;
            delete queryParams.report_type;
            delete queryParams.printer;
            delete queryParams.printer_name;


            const params = queryParams;


            console.log("==================================");
            console.log("Generating Report");
            console.log("Server :", server);
            console.log("Report Path :", reportPath);
            console.log("Report Type :", reportType || "(none)");
            console.log("Parameters :", params);
            console.log(
                "Selected printer :",
                printerName || "(default)"
            );
            console.log("==================================");


            // ================================
            // BUILD BI PUBLISHER URL
            // ================================

            const url =
                buildReportURL(
                    server,
                    reportPath,
                    params
                );


            console.log("BI Publisher URL:");
            console.log(url);
            console.log("==================================");


            // ================================
            // DOWNLOAD PDF
            // ================================

            const response =
                await axios.get(url, {

                    responseType: "arraybuffer",

                    headers: {
                        "Accept": "application/pdf"
                    }

                });


            const type =
                response.headers["content-type"];


            console.log(
                "Response Type :",
                type
            );


            if (
                !type ||
                !type.includes("application/pdf")
            ) {

                return res
                    .status(500)
                    .send("Report is not PDF.");

            }


            // ================================
            // SAVE TEMP PDF
            // ================================

            const filename =
                `report_${Date.now()}.pdf`;


            filePath = path.join(
                TEMP_FOLDER,
                filename
            );


            fs.writeFileSync(
                filePath,
                response.data
            );


            console.log(
                "Saved :",
                filePath
            );


            // ================================
            // PRINT PDF
            // ================================

            let sumatraPath;

            try {

                if (process && process.pkg) {

                    sumatraPath =
                        path.join(
                            path.dirname(process.execPath),
                            "SumatraPDF.exe"
                        );

                }

            }
            catch (e) {

                sumatraPath = undefined;

            }


            const printOptions = {};


            if (sumatraPath) {

                printOptions.sumatraPdfPath =
                    sumatraPath;

            }


            if (printerName) {

                printOptions.printer =
                    printerName;

                console.log(
                    "Printer override :",
                    printerName
                );

            }
            else {

                const defaultPrinter =
                    await getDefaultPrinter();


                console.log(
                    "Default printer :",
                    defaultPrinter || "none"
                );


                if (!defaultPrinter) {

                    const availablePrinters =
                        await getPrinters();


                    console.log(
                        "Available printers :",
                        availablePrinters
                    );


                    return res
                        .status(500)
                        .send(
                            "No default printer available. Use printer=<name>."
                        );

                }


                printOptions.printer =
                    defaultPrinter.name;

            }


            console.log(
                "Printing options :",
                printOptions
            );

    // START PRINTING IN BACKGROUND

    print(filePath, printOptions)

    .then(() => {

        console.log(
            "Printed successfully."
        );


 
        setTimeout(() => {

            fs.unlink(
                filePath,
                (err) => {

                    if (err) {

                        console.log(
                            "Delete Error :",
                            err.message
                        );

                    }
                    else {

                        console.log(
                            "Temp file deleted."
                        );

                    }

                }
            );

        }, 60000);

    })

    .catch((err) => {

        console.log(
            "Printing Error :",
            err && err.message
                ? err.message
                : err
        );


        fs.unlink(
            filePath,
            (deleteError) => {

                if (deleteError) {

                    console.log(
                        "Delete Error :",
                        deleteError.message
                    );

                }
                else {

                    console.log(
                        "Temp file deleted after print failure."
                    );

                }

            }
        );

    });


            // ================================
            // CREATE HTML VIEW
            // ================================

            const pdfBase64 =
                Buffer
                    .from(response.data)
                    .toString("base64");


            res.send(`

    <!DOCTYPE html>

    <html>

    <head>

    <title>Report View</title>

    <style>

    html,body{

    margin:0;
    height:100%;
    overflow:hidden;

    }

    iframe{

    width:100%;
    height:100%;
    border:none;

    }

    </style>

    </head>

    <body>

    <iframe
    src="data:application/pdf;base64,${pdfBase64}">
    </iframe>

    </body>

    </html>

            `);


            // ================================
            // DELETE TEMP FILE AFTER 60 SEC
            // ================================
 
        }
        catch (error) {

            console.log(
                "ERROR :",
                error.message
            );


            res
                .status(500)
                .send("Printing failed.");

        }

    });


    // ================================
    // SCAN AND INSERT
    // ================================

    app.post("/scan_and_insert", async (req, res) => {

        let filePath;
        const identifierFields =
            req.body &&
            typeof req.body === "object" &&
            !Array.isArray(req.body)
                ? Object.keys(req.body).filter(
                    (field) =>
                        /^[a-z][a-z0-9_]*_id$/i.test(field) &&
                        req.body[field] !== undefined &&
                        req.body[field] !== null
                )
                : [];
        if (identifierFields.length !== 1) {
            return res
                .status(400)
                .json({
                    error:
                        "Provide exactly one positive <type>_id field."
                });
        }

        const identifierKey = identifierFields[0];
        const identifierValue = Number(
            req.body[identifierKey]
        );
        const scanServer =
            req.body.server === undefined
                ? SCAN_UPLOAD_BASE_URL
                : req.body.server;
        let targetUrl;

        try {

            targetUrl = buildScanInsertUrl(
                scanServer,
                identifierKey
            );

        }
        catch (error) {

            return res
                .status(400)
                .json({ error: error.message });

        }

        if (
            !Number.isInteger(identifierValue) ||
            identifierValue <= 0
        ) {
            return res
                .status(400)
                .json({
                    error: `${identifierKey} must be a positive integer.`
                });
        }

        if (scanInProgress) {
            return res
                .status(409)
                .json({ error: "A scan is already in progress." });
        }

        scanInProgress = true;

        try {

            const filename = `ID_${Date.now()}.png`;

            filePath = path.join(
                TEMP_FOLDER,
                filename
            );

            console.log("==================================");
            console.log("Starting scanner");
            console.log(`${identifierKey} :`, identifierValue);
            console.log("Upload URL :", targetUrl);
            console.log("==================================");

            await scanDocumentToPng(filePath);

            const uploadResponse =
                await uploadScannedImage(
                    filePath,
                    targetUrl,
                    identifierKey,
                    identifierValue
                );

            if (
                uploadResponse.status < 200 ||
                uploadResponse.status >= 300 ||
                !uploadResponse.data ||
                uploadResponse.data.status !== "SUCCESS"
            ) {
                console.log(
                    "Insert endpoint rejected scanned image :",
                    uploadResponse.status,
                    uploadResponse.data
                );

                return res
                    .status(502)
                    .json({
                        error: "Scanned image was rejected by the insert endpoint.",
                        insert_status: uploadResponse.status,
                        insert_message:
                            uploadResponse.data &&
                            uploadResponse.data.message,
                        insert_response: uploadResponse.data
                    });
            }

            res.status(201).json({
                message: "Scanned image sent successfully.",
                filename,
                insert_status: uploadResponse.status,
                insert_file_name:
                    uploadResponse.data.file_name
            });

        }
        catch (error) {

            console.log(
                "Scan and insert error :",
                error.message
            );

            res
                .status(500)
                .json({
                    error: "Scanning or image upload failed."
                });

        }
        finally {

            scanInProgress = false;

            if (filePath && fs.existsSync(filePath)) {
                fs.unlink(filePath, (error) => {
                    if (error) {
                        console.log(
                            "Scan temp file delete error :",
                            error.message
                        );
                    }
                });
            }

        }

    });


    // ================================
    // SHOW PRINTERS
    // ================================

    app.get(
        "/printers",
        async (req, res) => {

            const printers =
                await getPrinters();

            res.json(printers);

        }
    );


    // ================================
    // START SERVER
    // ================================

    app.listen(PORT, () => {

        console.log(
            `Print server running on port ${PORT}`
        );

    });
