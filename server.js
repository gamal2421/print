    const express = require("express");
    const axios = require("axios");
    const cors = require("cors");
    const fs = require("fs");
    const path = require("path");
    const {
        print,
        getPrinters,
        getDefaultPrinter
    } = require("pdf-to-printer");

    const app = express();
    app.use(cors());
    app.use(express.json());

    const PORT = 9999;

    const REPORT_PRINTERS = {
        "2": "Microsoft Print to PDF"
    };


    // ================================
    // TEMP FOLDER
    // ================================

    const TEMP_FOLDER = path.join(process.cwd(), "temp");

    if (!fs.existsSync(TEMP_FOLDER)) {
        fs.mkdirSync(TEMP_FOLDER, { recursive: true });
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
