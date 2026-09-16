async function openReportsAndWatch(reports) {
    if (window._printingNow === true) {

        console.log("Printing already in progress...");
        return;
    }
    window._printingNow = true;
    reports = (reports || []).filter(function (report) {

        return report &&
               report.url &&
               String(report.url).trim() !== "";

    });
    if (reports.length === 0) {

        window._printingNow = false;
        return;
    }
    var printSession =
        "PRINT_" +
        Date.now() +
        "_" +
        Math.random()
            .toString(36)
            .substring(2);
    var reportWindows = [];
    for (var i = 0; i < reports.length; i++) {
        var windowName =
            printSession +
            "_REPORT_" +
            i;
        var reportWindow =
            window.open(
                "about:blank",
                windowName,
                "width=900,height=700,scrollbars=yes,resizable=yes"
            );
        if (!reportWindow) {
            reportWindows.forEach(function (win) {
                try {
                    if (
                        win &&
                        !win.closed
                    ) {
                        win.close();
                    }
                } catch (e) {}
            });
            window._printingNow = false;
            apex.message.alert(
                "Please allow pop-ups for this site."
            );
            return;
        }
        reportWindows.push(
            reportWindow
        );
    }
    for (var j = 0; j < reports.length; j++) {

        try {

            reportWindows[j].location.replace(
                reports[j].url
            );

        } catch (e) {

            console.error(
                "Error loading report:",
                j,
                e
            );
        }
    }
    try {
        for (
            var r = 0;
            r < reports.length;
            r++
        ) {
            var report =
                reports[r];
            var reportUrl =
                report.url;
            var printerItem =
                report.printerItem;
            var printersJson =
                printerItem
                    ? $v(printerItem)
                    : null;
            if (!printersJson) {

                console.warn(
                    "No printers found for:",
                    printerItem
                );

                continue;
            }
            var printers = [];
            try {

                printers =
                    JSON.parse(
                        printersJson
                    );

            } catch (e) {

                console.error(
                    "Invalid printer JSON:",
                    printerItem,
                    e
                );

                continue;
            } if (
                !Array.isArray(printers) ||
                printers.length === 0
            ) {

                console.warn(
                    "Printer list is empty:",
                    printerItem
                );

                continue;
            }
            for (
                var p = 0;
                p < printers.length;
                p++
            ) {

                var printer =
                    printers[p];


                if (
                    !printer ||
                    !printer.printer_name
                ) {

                    console.warn(
                        "Invalid printer:",
                        printer
                    );

                    continue;
                }
                try {
  await printBIReport(
                        reportUrl,
                        printer.printer_name,
                        3
                    );


                } catch (printError) {

                    console.error(
                        "Print error:",
                        printError
                    );
                }
            }
        }
    } catch (e) {
        console.error(
            "Printing process error:",
            e
        );
    }
    var checkClosed =
        setInterval(
            function () {
                var allClosed = true;
                for (
                    var k = 0;
                    k < reportWindows.length;
                    k++
                ) {
                    try {
                        if (
                            !reportWindows[k] ||
                            !reportWindows[k].closed
                        ) {
                            allClosed = false;
                            break;
                        }
                    } catch (e) {
                        allClosed = false;
                        break;
                    }
                }
 if (allClosed) {
                    clearInterval(
                        checkClosed
                    );
                    window._printingNow = false;
                    apex.submit({
                        request: "PRINT_CLOSED"
                    });
                }

            },
            500
        );
}