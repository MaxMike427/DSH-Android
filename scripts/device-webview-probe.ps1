# Attach to the WebView inside the installed debug APK and read its live layout.
#
# The debug APK enables WebView DevTools automatically, so no build or download is
# needed: forward the app's devtools socket and ask the page for its real numbers.
# That distinguishes "the page is laid out but not composited" from "the page has
# no box to paint into".
#
# Usage: pwsh -File scripts/device-webview-probe.ps1 [-Package com.dshapp]
param(
    [string]$Package = 'com.dshapp',
    [int]$Port = 9222
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

$devices = (adb devices) -split "`n" | Where-Object { $_ -match "`tdevice$" }
if (-not $devices) {
    Write-Error 'no device attached (check USB debugging)'
    exit 1
}

$sockets = adb shell cat /proc/net/unix 2>$null
$match = [regex]::Match($sockets, 'webview_devtools_remote_(\d+)')
if (-not $match.Success) {
    Write-Error "no WebView devtools socket; is the installed build debuggable and the app running?"
    exit 1
}

$socket = "webview_devtools_remote_$($match.Groups[1].Value)"
Write-Host "devtools socket: $socket"

adb forward "tcp:$Port" "localabstract:$socket" | Out-Null
try {
    Start-Sleep -Milliseconds 800
    $targets = Invoke-RestMethod "http://127.0.0.1:$Port/json/list"
    $targets | ForEach-Object { "page: $($_.url)  ($($_.title))" }
    node "$repo\scripts\cdp-probe.mjs" --cdp "http://127.0.0.1:$Port" --nav false `
        --shot "$env:TEMP\webview-live.png"
}
finally {
    adb forward --remove "tcp:$Port" | Out-Null
}
