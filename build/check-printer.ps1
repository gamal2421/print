param(
    [string]$PrinterName = "XPS Card Printer",
    [switch]$PrintTest,
    [string]$TestPdf
)

$ErrorActionPreference = "Stop"
$failed = $false

function Write-Check {
    param(
        [string]$Label,
        [ValidateSet("PASS", "FAIL", "WARN")][string]$Status,
        [string]$Details
    )

    $color = switch ($Status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        default { "Yellow" }
    }

    Write-Host "[$Status] $Label - $Details" -ForegroundColor $color
}

Write-Host "ZAHA printer check"
Write-Host "Computer: $env:COMPUTERNAME"
Write-Host "Printer:  $PrinterName"
Write-Host ""

$printer = $null
try {
    $printer = Get-Printer -Name $PrinterName -ErrorAction Stop
    Write-Check "Windows printer" "PASS" "Found; driver='$($printer.DriverName)', port='$($printer.PortName)'"
}
catch {
    Write-Check "Windows printer" "FAIL" "Not found. Add the printer and install its driver."
    $failed = $true
}

$document = $null
$ghostscriptPath = $null

if ($printer) {
    try {
        Add-Type -AssemblyName System.Drawing
        $document = New-Object System.Drawing.Printing.PrintDocument
        $document.PrinterSettings.PrinterName = $PrinterName

        if (-not $document.PrinterSettings.IsValid) {
            throw "Windows reports the printer settings as invalid."
        }

        $paperSizes = @($document.PrinterSettings.PaperSizes)
        $cardPaper = $paperSizes | Where-Object {
            $_.PaperName -eq "ISO ID-1 (85.60 x 53.98 mm)" -and
            $_.Width -eq 213 -and
            $_.Height -eq 338
        } | Select-Object -First 1

        if ($cardPaper) {
            Write-Check "ID-1 stock support" "PASS" "Driver offers '$($cardPaper.PaperName)' ($($cardPaper.Width)x$($cardPaper.Height) hundredths-inch)."
        }
        else {
            $available = ($paperSizes | ForEach-Object { $_.PaperName }) -join "; "
            Write-Check "ID-1 stock support" "FAIL" "Exact ISO ID-1 size not found. Available: $available"
            $failed = $true
        }

        $landscape = $document.DefaultPageSettings.Landscape
        if ($landscape) {
            Write-Check "Default orientation" "PASS" "Landscape is enabled."
        }
        else {
            Write-Check "Default orientation" "FAIL" "Set Landscape in the printer's Windows Printing Preferences."
            $failed = $true
        }

        $defaultPaper = $document.DefaultPageSettings.PaperSize
        Write-Check "Default media" "WARN" "Windows reports '$($defaultPaper.PaperName)' ($($defaultPaper.Width)x$($defaultPaper.Height)); the service explicitly requests 243x153 points."
    }
    catch {
        Write-Check "Printer settings" "FAIL" $_.Exception.Message
        $failed = $true
    }
    finally {
        if ($document) {
            $document.Dispose()
        }
    }
}

$runtimeRoots = @(
    (Join-Path $PSScriptRoot "ghostscript"),
    (Join-Path $env:LOCALAPPDATA "ZAHA\ghostscript"),
    (Join-Path $env:ProgramFiles "gs"),
    (Join-Path ${env:ProgramFiles(x86)} "gs")
) | Where-Object { $_ -and (Test-Path $_) }

foreach ($runtimeRoot in $runtimeRoots) {
    $versions = @(Get-ChildItem -Path $runtimeRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    foreach ($version in $versions) {
        foreach ($executable in @("gswin64c.exe", "gswin32c.exe")) {
            $candidate = Join-Path $version.FullName "bin\$executable"
            if (Test-Path $candidate) {
                $ghostscriptPath = $candidate
                break
            }
        }
        if ($ghostscriptPath) { break }
    }
    if ($ghostscriptPath) { break }
}

if (-not $ghostscriptPath) {
    foreach ($executable in @("gswin64c.exe", "gswin32c.exe")) {
        $command = Get-Command $executable -ErrorAction SilentlyContinue
        if ($command) {
            $ghostscriptPath = $command.Source
            break
        }
    }
}

if ($ghostscriptPath) {
    try {
        $versionOutput = (& $ghostscriptPath -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) {
            throw "Ghostscript returned exit code $LASTEXITCODE."
        }
        Write-Check "Ghostscript" "PASS" "$versionOutput at '$ghostscriptPath'"
    }
    catch {
        Write-Check "Ghostscript" "FAIL" $_.Exception.Message
        $failed = $true
    }
}
else {
    Write-Check "Ghostscript" "FAIL" "Not found. Run install.bat or install the bundled runtime."
    $failed = $true
}

$servicePath = Join-Path $env:LOCALAPPDATA "ZAHA\zaha-print-service.exe"
if (Test-Path $servicePath) {
    Write-Check "Print service executable" "PASS" $servicePath
}
else {
    Write-Check "Print service executable" "WARN" "Not installed at '$servicePath'. Run install.bat first."
}

try {
    $health = Invoke-WebRequest -Uri "http://127.0.0.1:9999/health" -UseBasicParsing -TimeoutSec 3
    if ($health.StatusCode -eq 200 -and $health.Content.Trim() -eq "OK") {
        Write-Check "Print service health" "PASS" "http://127.0.0.1:9999/health responded OK."
    }
    else {
        Write-Check "Print service health" "WARN" "Unexpected health response."
    }
}
catch {
    Write-Check "Print service health" "WARN" "Not responding on port 9999; start or restart the service after installation."
}

if ($PrintTest) {
    if (-not $TestPdf -or -not (Test-Path $TestPdf -PathType Leaf)) {
        Write-Check "Physical print test" "FAIL" "Provide an existing PDF with -TestPdf."
        $failed = $true
    }
    elseif (-not $printer -or -not $ghostscriptPath) {
        Write-Check "Physical print test" "FAIL" "Printer and Ghostscript must both be available."
        $failed = $true
    }
    else {
        Write-Host ""
        Write-Host "This will send one physical page to '$PrinterName': $TestPdf" -ForegroundColor Yellow
        $confirmation = Read-Host "Type PRINT to continue"

        if ($confirmation -ceq "PRINT") {
            $arguments = @(
                "-dSAFER",
                "-dBATCH",
                "-dNOPAUSE",
                "-dNoCancel",
                "-sDEVICE=mswinpr2",
                "-sOutputFile=%printer%$PrinterName",
                "-dDEVICEWIDTHPOINTS=243",
                "-dDEVICEHEIGHTPOINTS=153",
                "-dFIXEDMEDIA",
                "-dPDFFitPage",
                (Resolve-Path $TestPdf).Path
            )

            & $ghostscriptPath @arguments
            if ($LASTEXITCODE -eq 0) {
                Write-Check "Physical print test" "PASS" "Ghostscript accepted the print job. Check the physical card for layout."
            }
            else {
                Write-Check "Physical print test" "FAIL" "Ghostscript exit code $LASTEXITCODE."
                $failed = $true
            }
        }
        else {
            Write-Check "Physical print test" "WARN" "Cancelled; no page was sent."
        }
    }
}

Write-Host ""
if ($failed) {
    Write-Host "RESULT: CHECK FAILED. Fix the FAIL items above." -ForegroundColor Red
    exit 1
}

Write-Host "RESULT: CHECK PASSED. Review any WARN items above." -ForegroundColor Green
exit 0
