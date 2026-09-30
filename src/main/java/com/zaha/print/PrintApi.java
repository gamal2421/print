package com.zaha.print;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.printing.PDFPageable;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.print.attribute.standard.MediaSizeName;
import javax.print.attribute.standard.OrientationRequested;
import javax.print.attribute.standard.PageRanges;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.Paper;
import java.awt.print.PrinterJob;

import javax.imageio.ImageIO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.time.Duration;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * ================================================================
 * ZAHA BI PUBLISHER JAVA PRINT API
 * ================================================================
 *
 * ENDPOINTS
 *
 * GET  /health          -> OK
 * GET  /printers        -> [{"Name":"..."}]
 * GET/POST /print-image  prints an image (image_url | image_base64/image | image_path)
 * GET  /print-report    full A4 printing (independent of print-card)
 * GET  /print-card      (supports repeated parameters, e.g.
 *                        W_SUB_MEMBER_ID=1&W_SUB_MEMBER_ID=2)
 * POST /scan_and_insert
 *
 * Requires: Java 11+, PDFBox 2.0.x, Gson
 *
 * scan_and_insert example body:
 *
 * {
 *   "member_id": 128296,
 *   "server": "http://192.168.155.49:8080/api"
 * }
 *
 * -> scans with WIA (or uses image / image_base64 / image_url /
 *    image_path if supplied) and POSTs the PNG as base64 JSON to
 *    <server>/member   (member_id -> "member")
 * ================================================================
 */
public class PrintApi {

    // ============================================================
    // CONFIG
    // ============================================================

    private static final int API_PORT = 9999;
    private static final Object PRINT_LOCK = new Object();
    /** Default upload server for /scan_and_insert when body.server is absent. */
    private static String SCAN_UPLOAD_BASE_URL = null;

    private static final int MAX_BODY_BYTES = 25 * 1024 * 1024;

    /**
     * Parameter that is "split": every value of it opens the report
     * separately and prints separately. Can be overridden per request
     * with &split_param=NAME.
     */
    private static final String SPLIT_PARAM = "W_SUB_MEMBER_ID";

    private static final Path TEMP_FOLDER =
            Paths.get(System.getProperty("user.dir"), "temp");

    private static final Gson GSON =
            new GsonBuilder().serializeNulls().create();

    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    private static final AtomicBoolean scanInProgress =
            new AtomicBoolean(false);

    private static final String WIA_SCAN_SCRIPT =
            "$ErrorActionPreference = \"Stop\"\n"
                    + "$outputPath = [Environment]::GetEnvironmentVariable(\"SCAN_OUTPUT_PATH\")\n"
                    + "if ([string]::IsNullOrWhiteSpace($outputPath)) { throw \"SCAN_OUTPUT_PATH is required.\" }\n"
                    + "$pngFormat = \"{B96B3CAF-0728-11D3-9D7B-0000F81EF32E}\"\n"
                    + "$dialog = New-Object -ComObject WIA.CommonDialog\n"
                    + "$image = $dialog.ShowAcquireImage(1, 1, 0, $pngFormat, $true, $true, $true)\n"
                    + "if ($null -eq $image) { throw \"Scan canceled.\" }\n"
                    + "$image.SaveFile($outputPath)\n";

    // ============================================================
    // MAIN
    // ============================================================

    public static void main(String[] args) {

        try {

            System.out.println();
            System.out.println("=========================================");
            System.out.println(" ZAHA BI PUBLISHER JAVA PRINT API");
            System.out.println("=========================================");
            System.out.println();

            Files.createDirectories(TEMP_FOLDER);

            HttpServer server =
                    HttpServer.create(
                            new InetSocketAddress(API_PORT),
                            0
                    );

            server.createContext("/health", PrintApi::handleHealth);
            server.createContext("/printers", PrintApi::handlePrinters);
            server.createContext("/print-card", PrintApi::handlePrintCard);
            server.createContext("/print-report", PrintApi::handlePrintReport);
            server.createContext("/print-image", PrintApi::handlePrintImage);
            server.createContext("/scan_and_insert", PrintApi::handleScanAndInsert);

            server.setExecutor(Executors.newFixedThreadPool(5));

            server.start();

            System.out.println("Print API running on port " + API_PORT);
            System.out.println();
            System.out.println("Health:");
            System.out.println("http://localhost:" + API_PORT + "/health");
            System.out.println();
            System.out.println("Printers:");
            System.out.println("http://localhost:" + API_PORT + "/printers");
            System.out.println();
            System.out.println("Print:");
            System.out.println("GET http://localhost:" + API_PORT + "/print-card");
            System.out.println();
            System.out.println("Print image:");
            System.out.println("GET/POST http://localhost:" + API_PORT + "/print-image");
            System.out.println();
            System.out.println("Print full A4:");
            System.out.println("GET http://localhost:" + API_PORT + "/print-report");
            System.out.println();
            System.out.println("Scan:");
            System.out.println("POST http://localhost:" + API_PORT + "/scan_and_insert");
            System.out.println();

        } catch (Exception e) {

            e.printStackTrace();
        }
    }


    // ============================================================
    // GET /health   (plain "OK")
    // ============================================================

    private static void handleHealth(
            HttpExchange exchange) {

        try {

            if (!exchange.getRequestMethod()
                    .equalsIgnoreCase("GET")) {

                sendJson(
                        exchange,
                        405,
                        jsonError("Only GET is allowed.")
                );

                return;
            }

            sendText(
                    exchange,
                    200,
                    "OK"
            );

        } catch (Exception e) {

            e.printStackTrace();
        }
    }


    // ============================================================
    // GET /printers   ([{"Name":"..."}])
    // ============================================================

    private static void handlePrinters(
            HttpExchange exchange) {

        try {

            if (!exchange.getRequestMethod()
                    .equalsIgnoreCase("GET")) {

                sendJson(
                        exchange,
                        405,
                        jsonError("Only GET is allowed.")
                );

                return;
            }

            PrintService[] printers =
                    PrintServiceLookup
                            .lookupPrintServices(
                                    null,
                                    null
                            );

            StringBuilder json =
                    new StringBuilder();

            json.append("[");

            for (int i = 0;
                 i < printers.length;
                 i++) {

                if (i > 0) {
                    json.append(",");
                }

                json.append("{\"Name\":\"")
                        .append(escapeJson(printers[i].getName()))
                        .append("\"}");
            }

            json.append("]");

            sendJson(
                    exchange,
                    200,
                    json.toString()
            );

        } catch (Exception e) {

            e.printStackTrace();

            try {

                sendJson(
                        exchange,
                        500,
                        jsonError(e.getMessage())
                );

            } catch (Exception ignored) {
            }
        }
    }


    // ============================================================
    // POST /scan_and_insert
    // ============================================================

    private static void handleScanAndInsert(
            HttpExchange exchange) {

        Path filePath = null;
        boolean lockAcquired = false;

        try {

            // ----------------------------------------------------
            // CORS preflight (browser JSON POST)
            // ----------------------------------------------------

            if (exchange.getRequestMethod()
                    .equalsIgnoreCase("OPTIONS")) {

                exchange.getResponseHeaders()
                        .set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders()
                        .set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders()
                        .set("Access-Control-Allow-Headers", "Content-Type");

                exchange.sendResponseHeaders(204, -1);
                exchange.close();

                return;
            }

            if (!exchange.getRequestMethod()
                    .equalsIgnoreCase("POST")) {

                sendJson(
                        exchange,
                        405,
                        jsonError("Only POST is allowed.")
                );

                return;
            }


            // ----------------------------------------------------
            // Read JSON body
            // ----------------------------------------------------

            byte[] rawBody =
                    exchange.getRequestBody()
                            .readNBytes(MAX_BODY_BYTES + 1);

            if (rawBody.length > MAX_BODY_BYTES) {

                sendJson(
                        exchange,
                        413,
                        toJson("error", "Request body too large.")
                );

                return;
            }

            JsonObject body = new JsonObject();

            if (rawBody.length > 0) {

                try {

                    JsonElement parsed =
                            JsonParser.parseString(
                                    new String(
                                            rawBody,
                                            StandardCharsets.UTF_8
                                    )
                            );

                    if (parsed.isJsonObject()) {
                        body = parsed.getAsJsonObject();
                    }

                } catch (JsonSyntaxException e) {

                    sendJson(
                            exchange,
                            400,
                            toJson("error", "Invalid JSON body.")
                    );

                    return;
                }
            }


            // ----------------------------------------------------
            // Find the <type>_id field
            // ----------------------------------------------------

            Pattern idPattern =
                    Pattern.compile(
                            "^[a-z][a-z0-9_]*_id$",
                            Pattern.CASE_INSENSITIVE
                    );

            String identifierKey = null;

            for (String field : body.keySet()) {

                if (idPattern.matcher(field).matches()
                        && !body.get(field).isJsonNull()) {

                    identifierKey = field;
                    break;
                }
            }

            if (identifierKey == null) {

                sendJson(
                        exchange,
                        400,
                        toJson(
                                "error",
                                "Provide at least one positive <type>_id field."
                        )
                );

                return;
            }


            // ----------------------------------------------------
            // Target URL
            // ----------------------------------------------------

            String scanServer =
                    body.has("server")
                            ? str(body, "server")
                            : SCAN_UPLOAD_BASE_URL;

            String targetUrl;

            try {

                targetUrl =
                        buildScanInsertUrl(
                                scanServer,
                                identifierKey
                        );

            } catch (Exception e) {

                sendJson(
                        exchange,
                        400,
                        toJson("error", e.getMessage())
                );

                return;
            }


            // ----------------------------------------------------
            // Validate id value
            // ----------------------------------------------------

            double identifierValue;

            try {

                identifierValue =
                        Double.parseDouble(
                                str(body, identifierKey).trim()
                        );

            } catch (Exception e) {

                identifierValue = Double.NaN;
            }

            if (Double.isNaN(identifierValue)
                    || identifierValue <= 0
                    || identifierValue != Math.floor(identifierValue)) {

                sendJson(
                        exchange,
                        400,
                        toJson(
                                "error",
                                identifierKey
                                        + " must be a positive integer."
                        )
                );

                return;
            }


            // ----------------------------------------------------
            // One scan at a time
            // ----------------------------------------------------

            if (!scanInProgress.compareAndSet(false, true)) {

                sendJson(
                        exchange,
                        409,
                        toJson(
                                "error",
                                "A scan is already in progress."
                        )
                );

                return;
            }

            lockAcquired = true;


            // ----------------------------------------------------
            // Get image bytes (payload image OR scanner)
            // ----------------------------------------------------

            String filename =
                    "ID_" + System.currentTimeMillis() + ".png";

            filePath =
                    TEMP_FOLDER.resolve(filename);

            byte[] imageBytes;

            boolean hasImagePayload =
                    !isEmpty(str(body, "image"))
                            || !isEmpty(str(body, "image_base64"))
                            || !isEmpty(str(body, "image_url"))
                            || !isEmpty(str(body, "image_path"));

            if (hasImagePayload) {

                imageBytes =
                        loadImageBytes(
                                str(body, "image_url"),
                                firstNonEmpty(
                                        str(body, "image_base64"),
                                        str(body, "image")
                                ),
                                str(body, "image_path")
                        );

            } else {

                System.out.println("==================================");
                System.out.println("Starting scanner");
                System.out.println(identifierKey + " : " + (long) identifierValue);
                System.out.println("Upload URL : " + targetUrl);
                System.out.println("==================================");

                scanDocumentToPng(filePath);

                imageBytes = Files.readAllBytes(filePath);
            }


            // ----------------------------------------------------
            // Build payload:  { image: dataUrl, ...otherFields }
            // ----------------------------------------------------

            JsonObject payload = new JsonObject();

            payload.addProperty(
                    "image",
                    "data:image/png;base64,"
                            + Base64.getEncoder()
                            .encodeToString(imageBytes)
            );

            for (Map.Entry<String, JsonElement> entry :
                    body.entrySet()) {

                String key = entry.getKey();

                if (key.equals("server")
                        || key.equals("image")
                        || key.equals("image_base64")
                        || key.equals("image_url")
                        || key.equals("image_path")) {

                    continue;
                }

                payload.add(key, entry.getValue());
            }


            // ----------------------------------------------------
            // Upload
            // ----------------------------------------------------

            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(targetUrl))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            payload.toString(),
                                            StandardCharsets.UTF_8
                                    )
                            )
                            .build();

            HttpResponse<String> upload =
                    HTTP.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );

            JsonObject data = null;

            try {

                JsonElement parsed =
                        JsonParser.parseString(upload.body());

                if (parsed.isJsonObject()) {
                    data = parsed.getAsJsonObject();
                }

            } catch (Exception ignored) {
            }

            boolean ok =
                    upload.statusCode() >= 200
                            && upload.statusCode() < 300
                            && data != null
                            && "SUCCESS".equals(str(data, "status"));

            if (!ok) {

                System.out.println(
                        "Insert endpoint rejected scanned image : "
                                + upload.statusCode()
                                + " "
                                + upload.body()
                );

                Map<String, Object> res =
                        new LinkedHashMap<>();

                res.put("error", "Scanned image was rejected by the insert endpoint.");
                res.put("insert_status", upload.statusCode());
                res.put("insert_message", data == null ? null : str(data, "message"));
                res.put("insert_response", data);

                sendJson(exchange, 502, GSON.toJson(res));

                return;
            }


            // ----------------------------------------------------
            // Success (201)
            // ----------------------------------------------------

            Map<String, Object> res =
                    new LinkedHashMap<>();

            res.put("message", "Scanned image sent successfully.");
            res.put("filename", filename);
            res.put("insert_status", upload.statusCode());
            res.put("insert_file_name", str(data, "file_name"));

            sendJson(exchange, 201, GSON.toJson(res));

        } catch (Exception e) {

            System.out.println(
                    "Scan and insert error : "
                            + (e.getMessage() != null
                            ? e.getMessage()
                            : e.toString())
            );

            e.printStackTrace();

            try {

                Map<String, Object> res =
                        new LinkedHashMap<>();

                res.put("error", "Scanning or image upload failed.");
                res.put("details", e.getMessage() != null ? e.getMessage() : e.toString());

                sendJson(exchange, 500, GSON.toJson(res));

            } catch (Exception ignored) {
            }

        } finally {

            if (lockAcquired) {
                scanInProgress.set(false);
            }

            if (filePath != null) {

                try {

                    Files.deleteIfExists(filePath);

                } catch (IOException e) {

                    System.out.println(
                            "Scan temp file delete error : "
                                    + e.getMessage()
                    );
                }
            }
        }
    }


    // ============================================================
    // SCANNER (WIA via PowerShell)
    // ============================================================

    private static void scanDocumentToPng(
            Path outputPath)
            throws Exception {

        // Write the script to a temp .ps1 (UTF-8 with BOM) to avoid
        // command-line quoting problems on Windows.

        Path script =
                Files.createTempFile(
                        TEMP_FOLDER,
                        "scan_",
                        ".ps1"
                );

        try {

            byte[] bom = {
                    (byte) 0xEF, (byte) 0xBB, (byte) 0xBF
            };

            byte[] body =
                    WIA_SCAN_SCRIPT.getBytes(StandardCharsets.UTF_8);

            byte[] all =
                    new byte[bom.length + body.length];

            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(body, 0, all, bom.length, body.length);

            Files.write(script, all);

            ProcessBuilder pb =
                    new ProcessBuilder(
                            "powershell.exe",
                            "-NoProfile",
                            "-STA",
                            "-ExecutionPolicy",
                            "Bypass",
                            "-File",
                            script.toString()
                    );

            pb.environment()
                    .put("SCAN_OUTPUT_PATH", outputPath.toString());

            pb.redirectErrorStream(true);

            Process process = pb.start();

            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8
                    ).trim();

            int exit = process.waitFor();

            if (exit != 0) {

                throw new IOException(
                        output.isEmpty()
                                ? "Scanner failed (exit " + exit + ")."
                                : output
                );
            }

            if (!Files.exists(outputPath)) {

                throw new IOException(
                        "Scanner did not create an image file."
                );
            }

        } finally {

            Files.deleteIfExists(script);
        }
    }


    // ============================================================
    // BUILD INSERT URL   (<server>/<type without _id and _>)
    // ============================================================

    private static String buildScanInsertUrl(
            String server,
            String identifierKey)
            throws Exception {

        if (isEmpty(server)) {

            throw new Exception(
                    "server must be a non-empty URL."
            );
        }

        URI uri = new URI(server.trim());

        String scheme = uri.getScheme();

        if (scheme == null
                || (!scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https"))) {

            throw new Exception(
                    "server must use http or https."
            );
        }

        String path = uri.getRawPath();

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        if (!path.endsWith("/")) {
            path += "/";
        }

        URI base =
                new URI(
                        scheme
                                + "://"
                                + uri.getRawAuthority()
                                + path
                );

        String scannerType =
                identifierKey
                        .replaceAll("(?i)_id$", "")
                        .replace("_", "");

        return base.resolve(scannerType).toString();
    }


    // ============================================================
    // IMAGE FROM url / base64 / path
    // ============================================================

    private static byte[] loadImageBytes(
            String imageUrl,
            String imageBase64,
            String imagePath)
            throws Exception {

        if (!isEmpty(imageUrl)) {

            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(imageUrl))
                            .timeout(Duration.ofSeconds(60))
                            .GET()
                            .build();

            HttpResponse<byte[]> response =
                    HTTP.send(
                            request,
                            HttpResponse.BodyHandlers.ofByteArray()
                    );

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                throw new IOException(
                        "Unable to download image from the provided URL."
                );
            }

            return response.body();
        }

        if (!isEmpty(imageBase64)) {

            Matcher matcher =
                    Pattern.compile(
                            "^data:(image/[^;]+);base64,(.+)$",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
                    ).matcher(imageBase64);

            String raw =
                    matcher.matches()
                            ? matcher.group(2)
                            : imageBase64;

            byte[] decoded;

            try {

                decoded =
                        Base64.getMimeDecoder()
                                .decode(raw.replaceAll("\\s", ""));

            } catch (IllegalArgumentException e) {

                decoded = new byte[0];
            }

            if (decoded.length == 0) {

                throw new IOException(
                        "image_base64 is empty or invalid."
                );
            }

            return decoded;
        }

        if (!isEmpty(imagePath)) {

            Path p = Paths.get(imagePath);

            if (!p.isAbsolute()) {

                p = Paths.get(System.getProperty("user.dir"))
                        .resolve(p);
            }

            if (!Files.exists(p)) {

                throw new IOException(
                        "image_path does not exist."
                );
            }

            return Files.readAllBytes(p);
        }

        throw new IOException("No image data was received.");
    }


    // ============================================================
    // GET /print-card
    // ============================================================

    private static void handlePrintCard(
            HttpExchange exchange) {

        try {

            // ----------------------------------------------------
            // Method
            // ----------------------------------------------------

            if (!exchange.getRequestMethod()
                    .equalsIgnoreCase("GET")) {

                sendJson(
                        exchange,
                        405,
                        jsonError("Only GET is allowed.")
                );

                return;
            }


            // ----------------------------------------------------
            // Parse query parameters (multi-value)
            // ----------------------------------------------------

            Map<String, List<String>> params =
                    parseQuery(
                            exchange.getRequestURI()
                                    .getRawQuery()
                    );


            System.out.println();
            System.out.println("=========================================");
            System.out.println(" PRINT REQUEST");
            System.out.println("=========================================");


            for (Map.Entry<String, List<String>> entry :
                    params.entrySet()) {

                for (String v : entry.getValue()) {

                    System.out.println(
                            entry.getKey()
                                    + " = "
                                    + v
                    );
                }
            }

            String server =
                    first(params, "server");

            String reportPath =
                    first(params, "report_path");

            String printerName =
                    first(params, "printer_name");


            // ----------------------------------------------------
            // Validate
            // ----------------------------------------------------

            if (isEmpty(server)) {

                sendJson(
                        exchange,
                        400,
                        jsonError("Missing server.")
                );

                return;
            }


            if (isEmpty(reportPath)) {

                sendJson(
                        exchange,
                        400,
                        jsonError("Missing report_path.")
                );

                return;
            }


            // ----------------------------------------------------
            // Print
            // ----------------------------------------------------

            PrintResult result =
                    printCard(
                            server,
                            reportPath,
                            printerName,
                            params
                    );


            // ----------------------------------------------------
            // Response
            // ----------------------------------------------------

            String response =
                    "{"
                            + "\"success\":true,"
                            + "\"message\":\"PRINT SUCCESS\","
                            + "\"printer\":\""
                            + escapeJson(printerName)
                            + "\","
                            + "\"pdf_size\":"
                            + result.pdfSize
                            + ","
                            + "\"pages\":"
                            + result.pages
                            + ","
                            + "\"jobs\":"
                            + result.jobs
                            + "}";


            sendJson(
                    exchange,
                    200,
                    response
            );


        } catch (Exception e) {

            e.printStackTrace();

            try {

                sendJson(
                        exchange,
                        500,
                        jsonError(e.getMessage())
                );

            } catch (Exception ignored) {
            }
        }
    }


    // ============================================================
    // PRINT CARD  (one report run + print per split value)
    // ============================================================

    private static PrintResult printCard(
            String server,
            String reportPath,
            String printerName,
            Map<String, List<String>> params)
            throws Exception {

        String splitParam =
                firstNonEmpty(first(params, "split_param"), SPLIT_PARAM);

        // find the real key (with or without _params prefix)
        String splitKey = null;

        for (String k : params.keySet()) {

            String name =
                    k.startsWith("_params")
                            ? k.substring("_params".length())
                            : k;

            if (name.equalsIgnoreCase(splitParam)) {
                splitKey = k;
                break;
            }
        }

        List<String> values =
                splitKey == null
                        ? new ArrayList<>()
                        : params.get(splitKey);

        // zero or one value -> normal single print
        if (values.size() <= 1) {

            return printOne(server, reportPath, printerName, params);
        }

        System.out.println();
        System.out.println("Split parameter " + splitKey
                + " has " + values.size() + " values -> "
                + values.size() + " separate print jobs");

        int totalSize = 0;
        int totalPages = 0;
        int jobs = 0;
        List<String> failed = new ArrayList<>();

        for (String value : values) {

            System.out.println();
            System.out.println(">>> Job " + (jobs + failed.size() + 1)
                    + "/" + values.size()
                    + "  " + splitKey + " = " + value);

            // copy of params with ONLY this value for the split key
            Map<String, List<String>> single =
                    new LinkedHashMap<>(params);

            List<String> one = new ArrayList<>();
            one.add(value);
            single.put(splitKey, one);

            try {

                PrintResult r =
                        printOne(server, reportPath, printerName, single);

                totalSize += r.pdfSize;
                totalPages += r.pages;
                jobs++;

            } catch (Exception e) {

                e.printStackTrace();

                failed.add(value + " (" + e.getMessage() + ")");
            }
        }

        if (!failed.isEmpty()) {

            throw new RuntimeException(
                    "Printed " + jobs + " of " + values.size()
                            + ". Failed: " + String.join("; ", failed)
            );
        }

        return new PrintResult(totalSize, totalPages, jobs);
    }


    // ============================================================
    // PRINT ONE REPORT
    // ============================================================

    private static PrintResult printOne(
            String server,
            String reportPath,
            String printerName,
            Map<String, List<String>> params)
            throws Exception {


        System.out.println();
        System.out.println("-----------------------------------------");
        System.out.println("Printer: " + printerName);
        System.out.println("Server: " + server);
        System.out.println("Report: " + reportPath);
        System.out.println("-----------------------------------------");


        // ========================================================
        // FIND PRINTER
        // ========================================================

        PrintService printer =
                findPrinter(printerName);


        if (printer == null) {

            throw new RuntimeException(
                    "Printer not found: "
                            + printerName
            );
        }


        System.out.println(
                "Printer found: "
                        + printer.getName()
        );


        // ========================================================
        // BUILD BI PUBLISHER URL
        // ========================================================

        String reportUrl =
                buildReportUrl(
                        server,
                        reportPath,
                        params
                );


        System.out.println();
        System.out.println("Report URL:");
        System.out.println(reportUrl);


        // ========================================================
        // DOWNLOAD PDF
        // ========================================================

        System.out.println();
        System.out.println("Downloading PDF...");


        byte[] pdfBytes =
                downloadReport(reportUrl);


        System.out.println(
                "PDF size: "
                        + pdfBytes.length
                        + " bytes"
        );


        // ========================================================
        // OPEN PDF
        // ========================================================

        try (PDDocument document =
                     PDDocument.load(
                             new ByteArrayInputStream(pdfBytes)
                     )) {


            int pages =
                    document.getNumberOfPages();


            System.out.println();
            System.out.println("PDF opened successfully.");
            System.out.println("PDF pages: " + pages);


            if (pages <= 0) {

                throw new RuntimeException(
                        "PDF contains no pages."
                );
            }


            // ====================================================
            // PRINTER JOB
            // ====================================================

            PrinterJob job =
                    PrinterJob.getPrinterJob();

            job.setPrintService(printer);


            // ====================================================
            // PDF PAGEABLE
            // ====================================================

            PDFPageable pageable =
                    new PDFPageable(document);

            job.setPageable(pageable);


            // ====================================================
            // ATTRIBUTES
            // ====================================================

            PrintRequestAttributeSet attributes =
                    new HashPrintRequestAttributeSet();

            attributes.add(
                    new PageRanges(1, pages)
            );


            // ====================================================
            // PRINT
            // ====================================================

            System.out.println();
            System.out.println("Sending PDF directly to printer...");

            synchronized (PRINT_LOCK) {
                job.print(attributes);
            }
            System.out.println();
            System.out.println("=========================================");
            System.out.println(" PRINT SUCCESS");
            System.out.println("=========================================");


            return new PrintResult(
                    pdfBytes.length,
                    pages,
                    1
            );
        }
    }


    // ============================================================
    // BUILD BI PUBLISHER URL
    // ============================================================

    private static String buildReportUrl(
            String server,
            String reportPath,
            Map<String, List<String>> params)
            throws Exception {


        // --------------------------------------------------------
        // Remove trailing slash from server
        // --------------------------------------------------------

        while (server.endsWith("/")) {

            server =
                    server.substring(
                            0,
                            server.length() - 1
                    );
        }


        // --------------------------------------------------------
        // Normalize report path
        // --------------------------------------------------------

        while (reportPath.startsWith("/")) {

            reportPath =
                    reportPath.substring(1);
        }


        while (reportPath.endsWith("/")) {

            reportPath =
                    reportPath.substring(
                            0,
                            reportPath.length() - 1
                    );
        }


        // --------------------------------------------------------
        // Report name = last part of report_path (used for _xt)
        // --------------------------------------------------------

        String reportName =
                reportPath.substring(
                        reportPath.lastIndexOf('/') + 1
                );

        if (reportName.toLowerCase().endsWith(".xdo")) {

            reportName =
                    reportName.substring(
                            0,
                            reportName.length() - 4
                    );
        }


        // --------------------------------------------------------
        // Ensure .xdo
        // --------------------------------------------------------

        if (!reportPath
                .toLowerCase()
                .endsWith(".xdo")) {

            reportPath += ".xdo";
        }


        // --------------------------------------------------------
        // Actual report URL
        // --------------------------------------------------------

        String actualReportPath =
                "/xmlpserver/"
                        + reportPath;


        StringBuilder url =
                new StringBuilder();

        url.append(server);
        url.append(actualReportPath);
        url.append("?");


        // _xpf
        url.append("_xpf=");

        // _xpt
        url.append("&_xpt=1");

        // _xdo
        url.append("&_xdo=");
        url.append(encode("/" + reportPath));

        // _xmode
        url.append("&_xmode=3");


        // ========================================================
        // COPY PARAMETERS (every value of repeated keys)
        // ========================================================

        for (Map.Entry<String, List<String>> entry :
                params.entrySet()) {

            String key =
                    entry.getKey();

            if (key == null) {
                continue;
            }


            // ----------------------------------------------------
            // Ignore local API parameters
            // ----------------------------------------------------

            if (key.equalsIgnoreCase("server")
                    || key.equalsIgnoreCase("report_path")
                    || key.equalsIgnoreCase("printer")
                    || key.equalsIgnoreCase("printer_name")
                    || key.equalsIgnoreCase("split_param")
                    || key.equalsIgnoreCase("report_url")) {

                continue;
            }


            // ----------------------------------------------------
            // Ignore BI Publisher control parameters
            // ----------------------------------------------------

            if (key.equalsIgnoreCase("_xpf")
                    || key.equalsIgnoreCase("_xpt")
                    || key.equalsIgnoreCase("_xdo")
                    || key.equalsIgnoreCase("_xmode")
                    || key.equalsIgnoreCase("_xt")
                    || key.equalsIgnoreCase("_xf")
                    || key.equalsIgnoreCase("_xautorun")) {

                continue;
            }


            // ----------------------------------------------------
            // Remove _params prefix if JS sends it
            // ----------------------------------------------------

            String parameterName =
                    key;

            if (parameterName.startsWith("_params")) {

                parameterName =
                        parameterName.substring(
                                "_params".length()
                        );
            }


            if (isEmpty(parameterName)) {
                continue;
            }


            // ----------------------------------------------------
            // BI Publisher parameter: one &_paramsNAME=value per value
            // ----------------------------------------------------

            for (String value : entry.getValue()) {

                url.append("&_params");
                url.append(encodeParameterName(parameterName));
                url.append("=");
                url.append(encode(value == null ? "" : value));
            }
        }


        // ========================================================
        // BI PUBLISHER OUTPUT
        // ========================================================

        url.append("&_xt=").append(encode(reportName));
        url.append("&_xf=pdf");
        url.append("&_xautorun=true");


        return url.toString();
    }


    // ============================================================
    // ENCODE PARAMETER NAME
    // ============================================================

    private static String encodeParameterName(
            String name) {

        return name;
    }


    // ============================================================
    // DOWNLOAD REPORT
    // ============================================================

    private static byte[] downloadReport(
            String reportUrl)
            throws Exception {


        URL url =
                new URL(reportUrl);


        HttpURLConnection connection =
                (HttpURLConnection)
                        url.openConnection();


        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);

        connection.setRequestProperty(
                "Accept",
                "application/pdf"
        );

        connection.setRequestProperty(
                "User-Agent",
                "Zaha-Java-Printer-API/1.0"
        );


        int status =
                connection.getResponseCode();

        System.out.println("HTTP Status: " + status);


        if (status != HttpURLConnection.HTTP_OK) {

            String error = "";

            if (connection.getErrorStream() != null) {

                error =
                        new String(
                                connection
                                        .getErrorStream()
                                        .readAllBytes(),
                                StandardCharsets.UTF_8
                        );
            }

            connection.disconnect();

            throw new IOException(
                    "BI Publisher returned HTTP "
                            + status
                            + "\n"
                            + error
            );
        }


        byte[] data;

        try (InputStream input =
                     connection.getInputStream()) {

            data = input.readAllBytes();
        }

        connection.disconnect();


        // ========================================================
        // PDF CHECK
        // ========================================================

        if (!isPdf(data)) {

            String response =
                    new String(
                            data,
                            StandardCharsets.UTF_8
                    );

            System.err.println();
            System.err.println("BI Publisher did not return PDF.");
            System.err.println(response);

            throw new IOException(
                    "BI Publisher returned non-PDF response."
            );
        }


        return data;
    }


    // ============================================================
    // PDF MAGIC
    // ============================================================

    private static boolean isPdf(
            byte[] data) {

        if (data == null || data.length < 4) {
            return false;
        }

        return data[0] == '%'
                && data[1] == 'P'
                && data[2] == 'D'
                && data[3] == 'F';
    }


    // ============================================================
    // FIND PRINTER
    // ============================================================

    private static PrintService findPrinter(
            String printerName) {


        PrintService[] printers =
                PrintServiceLookup
                        .lookupPrintServices(
                                null,
                                null
                        );


        for (PrintService printer : printers) {

            System.out.println("Printer: " + printer.getName());

            if (printer.getName()
                    .equalsIgnoreCase(printerName)) {

                return printer;
            }
        }


        return null;
    }


    // ============================================================
    // QUERY PARSER (multi-value: repeated keys are all kept)
    // ============================================================

    private static Map<String, List<String>> parseQuery(
            String query) {


        Map<String, List<String>> result =
                new LinkedHashMap<>();


        if (query == null || query.isBlank()) {

            return result;
        }


        String[] pairs =
                query.split("&");


        for (String pair : pairs) {

            if (pair.isEmpty()) {
                continue;
            }


            int index =
                    pair.indexOf('=');

            String key;
            String value;


            if (index >= 0) {

                key = pair.substring(0, index);
                value = pair.substring(index + 1);

            } else {

                key = pair;
                value = "";
            }


            try {

                key =
                        URLDecoder.decode(
                                key,
                                StandardCharsets.UTF_8
                        );

                value =
                        URLDecoder.decode(
                                value,
                                StandardCharsets.UTF_8
                        );

            } catch (Exception e) {

                e.printStackTrace();
            }


            result
                    .computeIfAbsent(key, k -> new ArrayList<>())
                    .add(value);
        }


        return result;
    }


    /** First value of a (possibly repeated) query parameter, or null. */
    private static String first(
            Map<String, List<String>> params,
            String key) {

        List<String> values = params.get(key);

        return (values == null || values.isEmpty())
                ? null
                : values.get(0);
    }


    // ============================================================
    // URL ENCODE
    // ============================================================

    private static String encode(
            String value)
            throws Exception {

        return URLEncoder.encode(
                value == null ? "" : value,
                StandardCharsets.UTF_8
        );
    }


    // ============================================================
    // SMALL HELPERS
    // ============================================================

    private static boolean isEmpty(
            String value) {

        return value == null
                || value.isBlank();
    }

    private static String firstNonEmpty(
            String... values) {

        for (String v : values) {

            if (!isEmpty(v)) {
                return v;
            }
        }

        return null;
    }

    /** Safe string getter for a Gson object (null if missing/non-primitive). */
    private static String str(
            JsonObject object,
            String key) {

        if (object == null || !object.has(key)) {
            return null;
        }

        JsonElement element = object.get(key);

        return element.isJsonPrimitive()
                ? element.getAsString()
                : null;
    }

    /** toJson("key", value, "key2", value2 ...) */
    private static String toJson(
            Object... keyValues) {

        Map<String, Object> map =
                new LinkedHashMap<>();

        for (int i = 0; i < keyValues.length; i += 2) {

            map.put(
                    (String) keyValues[i],
                    keyValues[i + 1]
            );
        }

        return GSON.toJson(map);
    }


    // ============================================================
    // JSON ERROR
    // ============================================================

    private static String jsonError(
            String message) {

        if (message == null || message.isBlank()) {

            message = "Unknown error.";
        }

        return "{"
                + "\"success\":false,"
                + "\"message\":\""
                + escapeJson(message)
                + "\""
                + "}";
    }


    // ============================================================
    // ESCAPE JSON
    // ============================================================

    private static String escapeJson(
            String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }


    // ============================================================
    // SEND JSON
    // ============================================================

    private static void sendJson(
            HttpExchange exchange,
            int status,
            String json)
            throws IOException {


        byte[] response =
                json.getBytes(StandardCharsets.UTF_8);


        exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=UTF-8");

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Origin", "*");

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Headers", "Content-Type");


        exchange.sendResponseHeaders(
                status,
                response.length
        );


        try (OutputStream output =
                     exchange.getResponseBody()) {

            output.write(response);
        }
    }


    // ============================================================
    // SEND TEXT
    // ============================================================

    private static void sendText(
            HttpExchange exchange,
            int status,
            String text)
            throws IOException {


        byte[] response =
                text.getBytes(StandardCharsets.UTF_8);


        exchange.getResponseHeaders()
                .set("Content-Type", "text/plain; charset=UTF-8");

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Origin", "*");


        exchange.sendResponseHeaders(
                status,
                response.length
        );


        try (OutputStream output =
                     exchange.getResponseBody()) {

            output.write(response);
        }
    }


    // ============================================================
    // GET /print-report   (independent of /print-card)
    //
    // Prints the BI Publisher report as FULL A4 pages:
    //   - paper = A4, zero margins requested
    //   - every PDF page is scaled to fill the A4 sheet
    //
    // Query params:
    //   server        (required) http://host:7001
    //   report_path   (required) /ZAHA/SUBSCRIPTION/SUB1600.xdo
    //   printer_name  (required)
    //   copies        optional, 1..99 (default 1)
    //   scaling       optional: fit (default) | shrink | stretch | actual
    //   orientation   optional: auto (default) | portrait | landscape
    //   any other parameter -> forwarded to BI Publisher as _params<NAME>
    //   (repeated parameters are forwarded with all their values)
    // ============================================================

    private static final double A4_WIDTH_PT  = 595.2756;
    private static final double A4_HEIGHT_PT = 841.8898;

    private static void handlePrintReport(
            HttpExchange exchange) {

        try {

            if (!exchange.getRequestMethod()
                    .equalsIgnoreCase("GET")) {

                sendJson(
                        exchange,
                        405,
                        jsonError("Only GET is allowed.")
                );

                return;
            }

            Map<String, List<String>> params =
                    parseQuery(
                            exchange.getRequestURI()
                                    .getRawQuery()
                    );

            System.out.println();
            System.out.println("=========================================");
            System.out.println(" PRINT-REPORT REQUEST (A4)");
            System.out.println("=========================================");

            for (Map.Entry<String, List<String>> entry :
                    params.entrySet()) {

                for (String v : entry.getValue()) {

                    System.out.println(entry.getKey() + " = " + v);
                }
            }

            String server      = first(params, "server");
            String reportPath  = first(params, "report_path");
            String printerName = first(params, "printer_name");

            if (isEmpty(server)) {

                sendJson(exchange, 400, jsonError("Missing server."));
                return;
            }

            if (isEmpty(reportPath)) {

                sendJson(exchange, 400, jsonError("Missing report_path."));
                return;
            }

            if (isEmpty(printerName)) {

                sendJson(exchange, 400, jsonError("Missing printer_name."));
                return;
            }

            int copies = 1;

            try {

                if (!isEmpty(first(params, "copies"))) {

                    copies = Integer.parseInt(
                            first(params, "copies").trim());
                }

            } catch (NumberFormatException e) {

                sendJson(exchange, 400, jsonError("copies must be a number."));
                return;
            }

            if (copies < 1 || copies > 99) {

                sendJson(exchange, 400, jsonError("copies must be 1..99."));
                return;
            }

            String scaling =
                    firstNonEmpty(first(params, "scaling"), "fit");

            String orientation =
                    firstNonEmpty(first(params, "orientation"), "auto");

            ReportPrintResult result =
                    printReportA4(
                            server,
                            reportPath,
                            printerName,
                            copies,
                            scaling,
                            orientation,
                            params
                    );

            String response =
                    "{"
                            + "\"success\":true,"
                            + "\"message\":\"PRINT SUCCESS\","
                            + "\"printer\":\"" + escapeJson(printerName) + "\","
                            + "\"paper\":\"A4\","
                            + "\"orientation\":\"" + result.orientation + "\","
                            + "\"scaling\":\"" + result.scaling + "\","
                            + "\"copies\":" + result.copies + ","
                            + "\"pdf_size\":" + result.pdfSize + ","
                            + "\"pages\":" + result.pages
                            + "}";

            sendJson(exchange, 200, response);

        } catch (Exception e) {

            e.printStackTrace();

            try {

                sendJson(
                        exchange,
                        500,
                        jsonError(e.getMessage())
                );

            } catch (Exception ignored) {
            }
        }
    }


    private static ReportPrintResult printReportA4(
            String server,
            String reportPath,
            String printerName,
            int copies,
            String scalingName,
            String orientationName,
            Map<String, List<String>> params)
            throws Exception {

        // ---------------- printer ----------------

        PrintService printer = null;

        for (PrintService ps :
                PrintServiceLookup.lookupPrintServices(null, null)) {

            if (ps.getName().equalsIgnoreCase(printerName)) {

                printer = ps;
                break;
            }
        }

        if (printer == null) {

            throw new RuntimeException(
                    "Printer not found: " + printerName);
        }

        // ---------------- scaling ----------------

        Scaling scaling;
        String scalingLabel;

        switch (scalingName.toLowerCase()) {

            case "shrink":
                scaling = Scaling.SHRINK_TO_FIT;
                scalingLabel = "shrink";
                break;

            case "stretch":
                scaling = Scaling.STRETCH_TO_FIT;
                scalingLabel = "stretch";
                break;

            case "actual":
                scaling = Scaling.ACTUAL_SIZE;
                scalingLabel = "actual";
                break;

            default:
                scaling = Scaling.SCALE_TO_FIT;
                scalingLabel = "fit";
        }

        // ---------------- download PDF ----------------

        String reportUrl =
                buildPrintReportUrl(server, reportPath, params);

        System.out.println();
        System.out.println("Report URL:");
        System.out.println(reportUrl);

        HttpRequest request =
                HttpRequest.newBuilder(URI.create(reportUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("Accept", "application/pdf")
                        .header("User-Agent", "Zaha-Java-Printer-API/1.0")
                        .GET()
                        .build();

        HttpResponse<byte[]> response =
                HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );

        System.out.println("HTTP Status: " + response.statusCode());

        if (response.statusCode() != 200) {

            throw new IOException(
                    "BI Publisher returned HTTP "
                            + response.statusCode()
                            + "\n"
                            + new String(
                            response.body(),
                            StandardCharsets.UTF_8));
        }

        byte[] pdfBytes = response.body();

        if (!isPdf(pdfBytes)) {

            System.err.println(
                    new String(pdfBytes, StandardCharsets.UTF_8));

            throw new IOException(
                    "BI Publisher returned non-PDF response.");
        }

        System.out.println("PDF size: " + pdfBytes.length + " bytes");

        // ---------------- print ----------------

        try (PDDocument document =
                     PDDocument.load(
                             new ByteArrayInputStream(pdfBytes))) {

            int pages = document.getNumberOfPages();

            if (pages <= 0) {

                throw new RuntimeException("PDF contains no pages.");
            }

            System.out.println("PDF pages: " + pages);

            // orientation (auto = follow the first PDF page)

            boolean landscape;

            if (orientationName.equalsIgnoreCase("landscape")) {

                landscape = true;

            } else if (orientationName.equalsIgnoreCase("portrait")) {

                landscape = false;

            } else {

                PDPage first = document.getPage(0);
                PDRectangle box = first.getCropBox();

                float w = box.getWidth();
                float h = box.getHeight();

                int rotation = first.getRotation();

                if (rotation == 90 || rotation == 270) {

                    float t = w;
                    w = h;
                    h = t;
                }

                landscape = w > h;
            }

            // A4 page, no margins

            Paper paper = new Paper();
            paper.setSize(A4_WIDTH_PT, A4_HEIGHT_PT);
            paper.setImageableArea(0, 0, A4_WIDTH_PT, A4_HEIGHT_PT);

            PageFormat pageFormat = new PageFormat();
            pageFormat.setPaper(paper);
            pageFormat.setOrientation(
                    landscape
                            ? PageFormat.LANDSCAPE
                            : PageFormat.PORTRAIT);

            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(printer);
            job.setJobName("BI-Report-A4");

            // clamp to the printer's real printable area so nothing is cut
            pageFormat = job.validatePage(pageFormat);

            PDFPrintable printable =
                    new PDFPrintable(document, scaling);

            job.setPrintable(printable, pageFormat);

            PrintRequestAttributeSet attributes =
                    new HashPrintRequestAttributeSet();

            attributes.add(MediaSizeName.ISO_A4);
            attributes.add(
                    new MediaPrintableArea(
                            0f, 0f, 210f, 297f,
                            MediaPrintableArea.MM));
            attributes.add(
                    landscape
                            ? OrientationRequested.LANDSCAPE
                            : OrientationRequested.PORTRAIT);
            attributes.add(new Copies(copies));

            System.out.println(
                    "Sending to printer (A4, "
                            + (landscape ? "landscape" : "portrait")
                            + ", scaling=" + scalingLabel + ")...");

            synchronized (PRINT_LOCK) {

                job.print(attributes);
            }

            System.out.println("PRINT-REPORT SUCCESS");

            return new ReportPrintResult(
                    pdfBytes.length,
                    pages,
                    copies,
                    landscape ? "landscape" : "portrait",
                    scalingLabel
            );
        }
    }


    /** URL builder used only by /print-report. */
    private static String buildPrintReportUrl(
            String server,
            String reportPath,
            Map<String, List<String>> params)
            throws Exception {

        while (server.endsWith("/")) {

            server = server.substring(0, server.length() - 1);
        }

        while (reportPath.startsWith("/")) {

            reportPath = reportPath.substring(1);
        }

        while (reportPath.endsWith("/")) {

            reportPath = reportPath.substring(0, reportPath.length() - 1);
        }

        String reportName =
                reportPath.substring(reportPath.lastIndexOf('/') + 1);

        if (reportName.toLowerCase().endsWith(".xdo")) {

            reportName =
                    reportName.substring(0, reportName.length() - 4);
        }

        if (!reportPath.toLowerCase().endsWith(".xdo")) {

            reportPath += ".xdo";
        }

        StringBuilder url = new StringBuilder();

        url.append(server)
                .append("/xmlpserver/")
                .append(reportPath)
                .append("?_xpf=&_xpt=1&_xdo=")
                .append(encode("/" + reportPath))
                .append("&_xmode=3");

        for (Map.Entry<String, List<String>> entry :
                params.entrySet()) {

            String key = entry.getKey();

            if (key == null) {
                continue;
            }

            // local API parameters of /print-report
            if (key.equalsIgnoreCase("server")
                    || key.equalsIgnoreCase("report_path")
                    || key.equalsIgnoreCase("printer")
                    || key.equalsIgnoreCase("printer_name")
                    || key.equalsIgnoreCase("report_url")
                    || key.equalsIgnoreCase("copies")
                    || key.equalsIgnoreCase("scaling")
                    || key.equalsIgnoreCase("orientation")) {

                continue;
            }

            // BI Publisher control parameters
            if (key.equalsIgnoreCase("_xpf")
                    || key.equalsIgnoreCase("_xpt")
                    || key.equalsIgnoreCase("_xdo")
                    || key.equalsIgnoreCase("_xmode")
                    || key.equalsIgnoreCase("_xt")
                    || key.equalsIgnoreCase("_xf")
                    || key.equalsIgnoreCase("_xautorun")) {

                continue;
            }

            String name = key;

            if (name.startsWith("_params")) {

                name = name.substring("_params".length());
            }

            if (isEmpty(name)) {
                continue;
            }

            for (String value : entry.getValue()) {

                url.append("&_params")
                        .append(name)
                        .append("=")
                        .append(encode(value == null ? "" : value));
            }
        }

        url.append("&_xt=").append(encode(reportName))
                .append("&_xf=pdf")
                .append("&_xautorun=true");

        return url.toString();
    }


    private static class ReportPrintResult {

        final int pdfSize;
        final int pages;
        final int copies;
        final String orientation;
        final String scaling;

        ReportPrintResult(
                int pdfSize,
                int pages,
                int copies,
                String orientation,
                String scaling) {

            this.pdfSize = pdfSize;
            this.pages = pages;
            this.copies = copies;
            this.orientation = orientation;
            this.scaling = scaling;
        }
    }


    // ============================================================
    // GET / POST /print-image
    //
    // Image source (first one found wins, same as the Node version):
    //   image_url | image_base64 (or image) | image_path
    // Printer:
    //   printer | printer_name   (default printer if omitted)
    // Optional:
    //   copies (1..99)
    //
    // Values can come from the JSON body (POST) or the query string.
    // The image is scaled to fit the page (aspect ratio kept, centered);
    // orientation follows the image shape.
    // ============================================================

    private static void handlePrintImage(
            HttpExchange exchange) {

        String requestId =
                "image-print-" + System.currentTimeMillis() + "-"
                        + Integer.toHexString((int) (Math.random() * 0xFFFFFF));

        long startedAt = System.currentTimeMillis();

        try {

            // ---------------- CORS preflight ----------------

            if (exchange.getRequestMethod()
                    .equalsIgnoreCase("OPTIONS")) {

                exchange.getResponseHeaders()
                        .set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders()
                        .set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders()
                        .set("Access-Control-Allow-Headers", "Content-Type");

                exchange.sendResponseHeaders(204, -1);
                exchange.close();

                return;
            }

            String method = exchange.getRequestMethod();

            if (!method.equalsIgnoreCase("GET")
                    && !method.equalsIgnoreCase("POST")) {

                sendJson(
                        exchange,
                        405,
                        toJson("error", "Only GET and POST are allowed.")
                );

                return;
            }

            // ---------------- query + body ----------------

            Map<String, List<String>> query =
                    parseQuery(
                            exchange.getRequestURI().getRawQuery()
                    );

            JsonObject body = new JsonObject();

            if (method.equalsIgnoreCase("POST")) {

                byte[] rawBody =
                        exchange.getRequestBody()
                                .readNBytes(MAX_BODY_BYTES + 1);

                if (rawBody.length > MAX_BODY_BYTES) {

                    sendJson(
                            exchange,
                            413,
                            toJson("error", "Request body too large.")
                    );

                    return;
                }

                if (rawBody.length > 0) {

                    try {

                        JsonElement parsed =
                                JsonParser.parseString(
                                        new String(
                                                rawBody,
                                                StandardCharsets.UTF_8
                                        )
                                );

                        if (parsed.isJsonObject()) {
                            body = parsed.getAsJsonObject();
                        }

                    } catch (JsonSyntaxException e) {

                        sendJson(
                                exchange,
                                400,
                                toJson("error", "Invalid JSON body.")
                        );

                        return;
                    }
                }
            }

            String imageUrl =
                    firstNonEmpty(
                            str(body, "image_url"),
                            first(query, "image_url"));

            String imageBase64 =
                    firstNonEmpty(
                            str(body, "image_base64"),
                            str(body, "image"),
                            first(query, "image_base64"));

            String imagePath =
                    firstNonEmpty(
                            str(body, "image_path"),
                            first(query, "image_path"));

            String requestedPrinter =
                    firstNonEmpty(
                            str(body, "printer"),
                            str(body, "printer_name"),
                            first(query, "printer"),
                            first(query, "printer_name"));

            int copies = 1;

            try {

                String c =
                        firstNonEmpty(
                                str(body, "copies"),
                                first(query, "copies"));

                if (!isEmpty(c)) {
                    copies = Integer.parseInt(c.trim());
                }

            } catch (NumberFormatException e) {

                sendJson(
                        exchange,
                        400,
                        toJson("error", "copies must be a number.")
                );

                return;
            }

            if (copies < 1 || copies > 99) {

                sendJson(
                        exchange,
                        400,
                        toJson("error", "copies must be 1..99.")
                );

                return;
            }

            System.out.println(
                    "[" + requestId + "] Print image request started "
                            + "{method=" + method
                            + ", printer="
                            + (isEmpty(requestedPrinter)
                            ? "(default)" : requestedPrinter)
                            + "}");

            // ---------------- printer ----------------

            PrintService printer;

            if (isEmpty(requestedPrinter)) {

                printer = PrintServiceLookup.lookupDefaultPrintService();

                if (printer == null) {

                    sendJson(
                            exchange,
                            404,
                            toJson(
                                    "error", "Printer not found: no default printer.",
                                    "details", "Printer not found: no default printer."
                            )
                    );

                    return;
                }

            } else {

                printer = findPrinterQuiet(requestedPrinter);

                if (printer == null) {

                    String msg = "Printer not found: " + requestedPrinter;

                    sendJson(
                            exchange,
                            404,
                            toJson("error", msg, "details", msg)
                    );

                    return;
                }
            }

            // ---------------- image bytes ----------------

            if (isEmpty(imageUrl)
                    && isEmpty(imageBase64)
                    && isEmpty(imagePath)) {

                System.out.println(
                        "[" + requestId + "] rejected: no image source");

                sendJson(
                        exchange,
                        400,
                        toJson(
                                "error",
                                "Provide image_url, image_base64/image, or image_path."
                        )
                );

                return;
            }

            String imageSource =
                    !isEmpty(imageUrl) ? "image_url"
                            : !isEmpty(imageBase64) ? "image_base64"
                            : "image_path";

            byte[] imageBytes;

            try {

                imageBytes =
                        loadImageBytes(imageUrl, imageBase64, imagePath);

            } catch (Exception e) {

                System.out.println(
                        "[" + requestId + "] image load failed: "
                                + e.getMessage());

                sendJson(
                        exchange,
                        400,
                        toJson(
                                "error", "Unable to load the image.",
                                "details", e.getMessage() == null
                                        ? e.toString() : e.getMessage()
                        )
                );

                return;
            }

            if (imageBytes == null || imageBytes.length == 0) {

                sendJson(
                        exchange,
                        400,
                        toJson("error", "No image data was received.")
                );

                return;
            }

            BufferedImage image =
                    ImageIO.read(new ByteArrayInputStream(imageBytes));

            if (image == null) {

                sendJson(
                        exchange,
                        400,
                        toJson(
                                "error",
                                "Unsupported or invalid image. Use PNG, JPG, GIF or BMP."
                        )
                );

                return;
            }

            System.out.println(
                    "[" + requestId + "] Image prepared {source="
                            + imageSource
                            + ", bytes=" + imageBytes.length
                            + ", size=" + image.getWidth()
                            + "x" + image.getHeight()
                            + ", printer=" + printer.getName() + "}");

            // ---------------- print ----------------

            printImage(image, printer, copies);

            System.out.println(
                    "[" + requestId + "] Print job sent successfully "
                            + "{printer=" + printer.getName()
                            + ", durationMs="
                            + (System.currentTimeMillis() - startedAt) + "}");

            Map<String, Object> res = new LinkedHashMap<>();

            res.put("success", true);
            res.put("message", "Image print job sent successfully.");
            res.put("source", imageSource);
            res.put("bytes", imageBytes.length);
            res.put("copies", copies);
            res.put("printer", printer.getName());

            sendJson(exchange, 200, GSON.toJson(res));

        } catch (Exception e) {

            System.out.println(
                    "[" + requestId + "] Image print failed after "
                            + (System.currentTimeMillis() - startedAt)
                            + "ms :");

            e.printStackTrace();

            String message =
                    e.getMessage() != null
                            ? e.getMessage()
                            : "Unknown printing error.";

            boolean notFound = message.contains("not found");

            try {

                sendJson(
                        exchange,
                        notFound ? 404 : 500,
                        toJson(
                                "error", notFound ? message : "Image printing failed.",
                                "details", message
                        )
                );

            } catch (Exception ignored) {
            }
        }
    }


    private static PrintService findPrinterQuiet(
            String printerName) {

        for (PrintService ps :
                PrintServiceLookup.lookupPrintServices(null, null)) {

            if (ps.getName().equalsIgnoreCase(printerName)) {

                return ps;
            }
        }

        return null;
    }


    /** Prints one image, scaled to fit the page, centered. */
    private static void printImage(
            BufferedImage image,
            PrintService printer,
            int copies)
            throws Exception {

        boolean landscape = image.getWidth() > image.getHeight();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(printer);
        job.setJobName("Image-Print");

        PageFormat pageFormat = job.defaultPage();
        pageFormat.setOrientation(
                landscape
                        ? PageFormat.LANDSCAPE
                        : PageFormat.PORTRAIT);
        pageFormat = job.validatePage(pageFormat);

        Printable printable =
                (Graphics graphics, PageFormat format, int pageIndex) -> {

                    if (pageIndex > 0) {
                        return Printable.NO_SUCH_PAGE;
                    }

                    Graphics2D g2 = (Graphics2D) graphics;

                    double x = format.getImageableX();
                    double y = format.getImageableY();
                    double w = format.getImageableWidth();
                    double h = format.getImageableHeight();

                    double scale =
                            Math.min(
                                    w / image.getWidth(),
                                    h / image.getHeight());

                    double drawW = image.getWidth() * scale;
                    double drawH = image.getHeight() * scale;

                    g2.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.setRenderingHint(
                            RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);

                    g2.drawImage(
                            image,
                            (int) Math.round(x + (w - drawW) / 2),
                            (int) Math.round(y + (h - drawH) / 2),
                            (int) Math.round(drawW),
                            (int) Math.round(drawH),
                            null);

                    return Printable.PAGE_EXISTS;
                };

        job.setPrintable(printable, pageFormat);

        PrintRequestAttributeSet attributes =
                new HashPrintRequestAttributeSet();

        attributes.add(new Copies(copies));
        attributes.add(
                landscape
                        ? OrientationRequested.LANDSCAPE
                        : OrientationRequested.PORTRAIT);

        synchronized (PRINT_LOCK) {

            job.print(attributes);
        }
    }


    // ============================================================
    // RESULT
    // ============================================================

    private static class PrintResult {

        final int pdfSize;

        final int pages;

        final int jobs;


        PrintResult(
                int pdfSize,
                int pages,
                int jobs) {

            this.pdfSize = pdfSize;
            this.pages = pages;
            this.jobs = jobs;
        }
    }
}