const express = require("express");
    const axios = require("axios");
    const cors = require("cors");
    const fs = require("fs");
    const path = require("path");
    const { execFile } = require("child_process");
    const { promisify } = require("util");
    const { print } = require("pdf-to-printer");

    const app = express();
    app.use(cors());
    app.use(express.json());

    const PORT = 9999;
    var SCAN_UPLOAD_BASE_URL ;
    const execFileAsync = promisify(execFile);

    const REPORT_PRINTERS = {
        "2": "Microsoft Print to PDF"
    };

    let scanInProgress = false;
    const printQueue = [];
    let printQueueRunning = false;
    const PRINTER_SELECTION_CANCELLED = "Printer selection was canceled.";
    const PRINTER_NOT_FOUND = "Printer not found.";

    function enqueuePrintJob(jobName, printOperation) {
        return new Promise((resolve, reject) => {
            printQueue.push({ jobName, printOperation, resolve, reject });
            console.log(`[print-queue] queued: ${jobName}; waiting: ${printQueue.length}`);
            processPrintQueue();
        });
    }

    async function processPrintQueue() {
        if (printQueueRunning) {
            return;
        }

        printQueueRunning = true;

        try {
            while (printQueue.length > 0) {
                const job = printQueue.shift();
                console.log(`[print-queue] started: ${job.jobName}`);

                try {
                    const result = await job.printOperation();
                    console.log(`[print-queue] finished: ${job.jobName}`);
                    job.resolve(result);
                }
                catch (error) {
                    console.log(`[print-queue] failed: ${job.jobName}`, error.message || error);
                    job.reject(error);
                }
            }
        }
        finally {
            printQueueRunning = false;

            if (printQueue.length > 0) {
                processPrintQueue();
            }
        }
    }

    function normalizePrinterName(printerName) {
        return String(printerName || "").trim().toLowerCase();
    }

    async function getWindowsPrinters() {
        const script = "@(Get-Printer | Select-Object Name, DriverName) | ConvertTo-Json -Compress";
        const { stdout } = await execFileAsync(
            "powershell.exe",
            ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
            { maxBuffer: 1024 * 1024 }
        );

        if (!stdout.trim()) {
            return [];
        }

        const printers = JSON.parse(stdout);
        return Array.isArray(printers) ? printers : [printers];
    }

    async function validatePrinterExists(printerName) {
        if (!printerName) {
            return;
        }

        const printers = await getWindowsPrinters();
        const printerNames = printers
            .map((printer) => printer && printer.Name)
            .filter((name) => typeof name === "string");

        const normalizedRequested = normalizePrinterName(printerName);
        const found = printerNames.some(
            (name) => normalizePrinterName(name) === normalizedRequested
        );

        if (!found) {
            throw new Error(`Printer "${printerName}" not found.`);
        }
    }


    // ================================
    // TEMP FOLDER
    // ================================

    const TEMP_FOLDER = path.join(process.cwd(), "temp");
    const PRINT_IMAGE_CLEANUP_DELAY_MS = 120000;

    if (!fs.existsSync(TEMP_FOLDER)) {
        fs.mkdirSync(TEMP_FOLDER, { recursive: true });
    }


    function getImageExtensionFromMime(contentType) {

        const mime =
            typeof contentType === "string"
                ? contentType.toLowerCase()
                : "";

        if (mime.includes("png")) {
            return ".png";
        }

        if (mime.includes("jpg") || mime.includes("jpeg")) {
            return ".jpg";
        }

        if (mime.includes("bmp")) {
            return ".bmp";
        }

        if (mime.includes("gif")) {
            return ".gif";
        }

        if (mime.includes("tif") || mime.includes("tiff")) {
            return ".tif";
        }

        if (mime.includes("webp")) {
            return ".webp";
        }

        return ".png";

    }


    function getMspaintPath() {
        const systemRoot = process.env.SystemRoot || "C:\\Windows";
        const candidates = [
            path.join(systemRoot, "System32", "mspaint.exe"),
            path.join(systemRoot, "Sysnative", "mspaint.exe"),
            path.join(systemRoot, "SysWOW64", "mspaint.exe")
        ];

        return candidates.find((candidate) => fs.existsSync(candidate));
    }

    // Show the same native Windows-style choice experience used by the scanner.
    // A supplied printer name is still honored so existing integrations can print
    // without showing a dialog.
    async function choosePrinter(printerName) {
        if (printerName) {
            return printerName;
        }

        const script = `
Add-Type -AssemblyName System.Windows.Forms
$dialog = New-Object System.Windows.Forms.PrintDialog
$dialog.UseEXDialog = $true
if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
    exit 2
}
[Console]::Out.Write($dialog.PrinterSettings.PrinterName)
        `;

        try {
            const { stdout } = await execFileAsync(
                "powershell.exe",
                ["-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-Command", script],
                { maxBuffer: 1024 * 1024 }
            );

            const selectedPrinter = stdout.trim();
            if (!selectedPrinter) {
                throw new Error(PRINTER_SELECTION_CANCELLED);
            }

            return selectedPrinter;
        }
        catch (error) {
            if (error && error.code === 2) {
                throw new Error(PRINTER_SELECTION_CANCELLED);
            }

            throw error;
        }
    }

    async function printImageWithPaint(filePath, printerName) {
        const paintPath = getMspaintPath();

        if (!paintPath) {
            throw new Error("mspaint.exe not found on this machine.");
        }

        const args = ["/pt", filePath];

        if (printerName) {
            args.push(printerName);
        }

        await execFileAsync(
            paintPath,
            args,
            {
                maxBuffer: 1024 * 1024
            }
        );
    }

    async function printImageWithPowerShell(filePath, printerName) {
        const safeFilePath = filePath.replace(/'/g, "''");
        const safePrinter = (printerName || "").replace(/'/g, "''");

        const script = `
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies "System.Drawing.dll" @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Printing;

public sealed class ImagePrinter : IDisposable {
    private readonly Image image;
    private readonly PrintDocument document;

    private ImagePrinter(string filePath, string printerName) {
        image = Image.FromFile(filePath);
        document = new PrintDocument();
        document.DocumentName = "ZAHA Image Print";
        document.OriginAtMargins = false;

        if (!String.IsNullOrWhiteSpace(printerName)) {
            document.PrinterSettings.PrinterName = printerName;
        }

        document.DefaultPageSettings.Margins = new Margins(0, 0, 0, 0);

        document.PrintPage += PrintPage;
    }

    private void PrintPage(object sender, PrintPageEventArgs eventArgs) {
        eventArgs.Graphics.PageUnit = GraphicsUnit.Pixel;

        RectangleF printableArea = eventArgs.Graphics.VisibleClipBounds;
        float scale = Math.Min(
            printableArea.Width / image.Width,
            printableArea.Height / image.Height
        );

        float printWidth = image.Width * scale;
        float printHeight = image.Height * scale;
        float printX = printableArea.X + (printableArea.Width - printWidth) / 2;
        float printY = printableArea.Y + (printableArea.Height - printHeight) / 2;

        RectangleF destRect = new RectangleF(
            printX,
            printY,
            printWidth,
            printHeight
        );

        eventArgs.Graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
        eventArgs.Graphics.DrawImage(image, destRect);

        eventArgs.HasMorePages = false;
    }

    public static void Print(string filePath, string printerName) {
        using (ImagePrinter printer = new ImagePrinter(filePath, printerName)) {
            printer.document.Print();
        }
    }

    public void Dispose() {
        document.Dispose();
        image.Dispose();
    }
}
'@
[ImagePrinter]::Print('${safeFilePath}', '${safePrinter}')
    `;

    await execFileAsync(
        "powershell.exe",
        ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
        {
            maxBuffer: 1024 * 1024
        }
    );
}

async function printImageFile(filePath, printerName) {
    try {
        await printImageWithPowerShell(filePath, printerName);
    }
    catch (error) {
        console.log(
            "PowerShell image print failed; retrying with Paint :",
            error && error.message ? error.message : error
        );

        try {
            return await printImageWithPaint(filePath, printerName);
        }
        catch (paintError) {
            const powerShellError = error && error.message ? error.message : error;
            const paintMessage =
                paintError && paintError.message ? paintError.message : paintError;

            throw new Error(
                `PowerShell renderer failed: ${powerShellError}. ` +
                `Paint fallback failed: ${paintMessage}`
            );
        }
    }
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
        payloadFields
    ) {

        const imageBuffer =
            await fs.promises.readFile(filePath);

        const payload = {
            image:
                "data:image/png;base64," +
                imageBuffer.toString("base64"),
            ...payloadFields
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

    async function getImageFromBody(body) {
        const imageUrl = body.image_url;
        const imageBase64 = body.image_base64 || body.image;
        const imagePath = body.image_path;
        let buffer;
        let contentType;
        let extension = ".png";

        if (imageUrl) {
            const response = await axios.get(imageUrl, {
                responseType: "arraybuffer",
                timeout: 60000,
                validateStatus: () => true
            });

            if (response.status < 200 || response.status >= 300) {
                throw new Error("Unable to download image from the provided URL.");
            }

            buffer = Buffer.from(response.data);
            contentType = response.headers["content-type"];
            extension = getImageExtensionFromMime(contentType);
        }
        else if (imageBase64) {
            const base64Value = String(imageBase64);
            const dataUrlMatch = base64Value.match(/^data:(image\/[^;]+);base64,(.+)$/i);
            const rawBase64 = dataUrlMatch ? dataUrlMatch[2] : base64Value;
            const normalizedBase64 = rawBase64.replace(/\s/g, "");

            buffer = Buffer.from(normalizedBase64, "base64");

            if (buffer.length === 0) {
                throw new Error("image_base64 is empty or invalid.");
            }

            contentType = dataUrlMatch ? dataUrlMatch[1] : "image/png";
            extension = getImageExtensionFromMime(contentType);
        }
        else if (imagePath) {
            const resolvedPath = path.isAbsolute(imagePath)
                ? imagePath
                : path.resolve(process.cwd(), imagePath);

            if (!fs.existsSync(resolvedPath)) {
                throw new Error("image_path does not exist.");
            }

            buffer = await fs.promises.readFile(resolvedPath);
            extension = path.extname(resolvedPath) || extension;
        }

        return { buffer, extension };
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


            const requestedPrinter =
                REPORT_PRINTERS[reportType] ||
                reportType ||
                queryParams.printer ||
                queryParams.printer_name;

            const printerName = await choosePrinter(requestedPrinter);
            await validatePrinterExists(printerName);

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
            console.log(
                "Printing options :",
                printOptions
            );

            const pdfBase64 =
                Buffer
                    .from(response.data)
                    .toString("base64");

          

 
            try {
                await enqueuePrintJob(
                    `report-${filename}`,
                    () => print(filePath, printOptions)
                );
                console.log("Printed successfully.");
            }
            finally {
                setTimeout(() => {
                    if (filePath && fs.existsSync(filePath)) {
                        fs.unlink(filePath, (err) => {
                            if (err) {
                                console.log(
                                    "Delete Error :",
                                    err.message
                                );
                            } else {
                                console.log(
                                    "Temp file deleted."
                                );
                            }
                        });
                    }
                }, 60000);
            }

            return;


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
                .status(
                    error && error.message === PRINTER_SELECTION_CANCELLED ? 409 : 500
                )
                .send(
                    error && error.message === PRINTER_SELECTION_CANCELLED
                        ? PRINTER_SELECTION_CANCELLED
                        : "Printing failed."
                );

        }

    });


    // ================================
    // PRINT IMAGE
    // ================================

    const handlePrintImage = async (req, res) => {

        let filePath;
        const requestId = `image-print-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
        const startedAt = Date.now();
        let imageSource = "unknown";
        let printJobSent = false;

        console.log(`[${requestId}] Print image request started`, {
            method: req.method,
            requestedPrinter: (req.body && (req.body.printer || req.body.printer_name)) ||
                (req.query && (req.query.printer || req.query.printer_name)) ||
                "(default)"
        });

        try {

            const payload =
                req.body && typeof req.body === "object" && !Array.isArray(req.body)
                    ? req.body
                    : {};

            const query = req.query || {};

            const imageUrl =
                payload.image_url || query.image_url;

            const imageBase64 =
                payload.image_base64 || payload.image || query.image_base64;

            const imagePath =
                payload.image_path || query.image_path;

            const requestedPrinter =
                payload.printer || payload.printer_name || query.printer || query.printer_name;

            const printerName = await choosePrinter(requestedPrinter);
            await validatePrinterExists(printerName);

            let buffer;
            let contentType;
            let extension = ".png";

            if (imageUrl) {

                imageSource = "image_url";
                const parsedImageUrl = new URL(imageUrl);
                console.log(`[${requestId}] Downloading image`, {
                    url: `${parsedImageUrl.origin}${parsedImageUrl.pathname}`
                });

                const response = await axios.get(imageUrl, {
                    responseType: "arraybuffer",
                    timeout: 60000,
                    validateStatus: () => true
                });

                if (response.status < 200 || response.status >= 300) {
                    console.log(`[${requestId}] Image download failed`, {
                        status: response.status
                    });
                    return res.status(400).json({
                        error: "Unable to download image from the provided URL.",
                        status: response.status
                    });
                }

                buffer = Buffer.from(response.data);
                contentType = response.headers["content-type"];
                extension = getImageExtensionFromMime(contentType);

            }
            else if (imageBase64) {

                imageSource = "image_base64";

                const base64Value = String(imageBase64);
                const dataUrlMatch = base64Value.match(/^data:(image\/[^;]+);base64,(.+)$/i);

                const rawBase64 = dataUrlMatch ? dataUrlMatch[2] : base64Value;
                const normalizedBase64 = rawBase64.replace(/\s/g, "");

                buffer = Buffer.from(normalizedBase64, "base64");

                if (buffer.length === 0) {
                    console.log(`[${requestId}] Base64 image was empty or invalid`);
                    return res.status(400).json({
                        error: "image_base64 is empty or invalid."
                    });
                }

                contentType = dataUrlMatch ? dataUrlMatch[1] : "image/png";
                extension = getImageExtensionFromMime(contentType);

            }
            else if (imagePath) {

                imageSource = "image_path";

                const resolvedPath = path.isAbsolute(imagePath)
                    ? imagePath
                    : path.resolve(process.cwd(), imagePath);

                if (!fs.existsSync(resolvedPath)) {
                    console.log(`[${requestId}] Image path does not exist`, { imagePath: resolvedPath });
                    return res.status(400).json({
                        error: "image_path does not exist."
                    });
                }

                buffer = await fs.promises.readFile(resolvedPath);
                extension = path.extname(resolvedPath) || extension;

            }
            else {
                console.log(`[${requestId}] Print image request rejected: no image source`);
                return res.status(400).json({
                    error: "Provide image_url, image_base64/image, or image_path."
                });
            }

            if (!buffer || buffer.length === 0) {
                console.log(`[${requestId}] Print image request rejected: no image data`);
                return res.status(400).json({
                    error: "No image data was received."
                });
            }

            const filename = `image_${Date.now()}${extension}`;
            filePath = path.join(TEMP_FOLDER, filename);
            await fs.promises.writeFile(filePath, buffer);

            console.log(`[${requestId}] Image prepared for printing`, {
                source: imageSource,
                bytes: buffer.length,
                contentType: contentType || "unknown",
                file: filename,
                printer: printerName || "(default)"
            });

            await enqueuePrintJob(
                requestId,
                () => printImageFile(filePath, printerName)
            );
            printJobSent = true;

            console.log(`[${requestId}] Print job sent successfully`, {
                file: filename,
                printer: printerName || "(default)",
                durationMs: Date.now() - startedAt
            });

            res.json({
                success: true,
                message: "Image print job sent successfully.",
                file: filename,
                printer: printerName || "(default)"
            });

        }
        catch (error) {

            console.log(
                `[${requestId}] Image print failed after ${Date.now() - startedAt}ms :`,
                error && error.stack
                    ? error.stack
                    : error
            );

            const message =
                error && error.message
                    ? error.message
                    : "Unknown printing error.";

            const notFound = message.includes("not found");
            const status =
                message === PRINTER_SELECTION_CANCELLED ? 409 : notFound ? 404 : 500;

            res.status(status).json({
                error: notFound ? message : "Image printing failed.",
                details: message
            });

        }
        finally {

            if (filePath && fs.existsSync(filePath)) {
                const deleteTempImage = () => fs.unlink(filePath, (error) => {
                    if (error) {
                        console.log(
                            `[${requestId}] Image temp file delete error :`,
                            error.message
                        );
                    }
                    else {
                        console.log(`[${requestId}] Temporary image deleted`);
                    }
                });

                if (printJobSent) {
                    console.log(`[${requestId}] Temporary image cleanup scheduled`, {
                        delayMs: PRINT_IMAGE_CLEANUP_DELAY_MS
                    });
                    setTimeout(deleteTempImage, PRINT_IMAGE_CLEANUP_DELAY_MS);
                }
                else {
                    deleteTempImage();
                }
            }

        }

    };


    app.get("/print-image", handlePrintImage);
    app.post("/print-image", handlePrintImage);


    // ================================
    // SCAN AND INSERT
    // ================================

    app.post("/scan_and_insert", async (req, res) => {

        let filePath;
        const body =
            req.body && typeof req.body === "object" && !Array.isArray(req.body)
                ? req.body
                : {};

        const identifierFields = Object.keys(body).filter(
            (field) =>
                /^[a-z][a-z0-9_]*_id$/i.test(field) &&
                body[field] !== undefined &&
                body[field] !== null
        );

        if (identifierFields.length === 0) {
            return res
                .status(400)
                .json({
                    error:
                        "Provide at least one positive <type>_id field."
                });
        }

        const identifierKey = identifierFields[0];
        const identifierValue = Number(body[identifierKey]);
        const scanServer =
            body.server === undefined
                ? SCAN_UPLOAD_BASE_URL
                : body.server;
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

            const hasImagePayload =
                body.image || body.image_base64 || body.image_url || body.image_path;

            if (hasImagePayload) {
                const imageResult = await getImageFromBody(body);

                filePath = path.join(TEMP_FOLDER, `ID_${Date.now()}${imageResult.extension}`);
                await fs.promises.writeFile(filePath, imageResult.buffer);
            }
            else {
                console.log("==================================");
                console.log("Starting scanner");
                console.log(`${identifierKey} :`, identifierValue);
                console.log("Upload URL :", targetUrl);
                console.log("==================================");

                await scanDocumentToPng(filePath);
            }

            const payloadFields = { ...body };
            delete payloadFields.server;
            delete payloadFields.image;
            delete payloadFields.image_base64;
            delete payloadFields.image_url;
            delete payloadFields.image_path;

            const uploadResponse =
                await uploadScannedImage(
                    filePath,
                    targetUrl,
                    payloadFields
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
                error.message || error
            );

            res
                .status(500)
                .json({
                    error: "Scanning or image upload failed.",
                    details: error.message || error
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
    // health
    // ================================

    app.get("/health", (req, res) => { res.status(200).send("OK"); });

    // ================================
    // SHOW PRINTERS
    // ================================
app.get(
        "/printers",
        async (req, res) => {

            const printers =
                await getWindowsPrinters();

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