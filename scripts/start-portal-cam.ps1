<#
  Portal TV webcam launcher
  1. Arms the USB tunnel (adb forward) to the Portal's camera server
  2. Ensures the camera app is streaming (launches it if not)
  3. Launches OBS with the Virtual Camera

  EDIT the CONFIG block below for your machine. See the README.
#>

# ===================== CONFIG =====================
$Adb         = 'C:\platform-tools\adb.exe'                          # path to adb.exe
$ObsExe      = "$env:ProgramFiles\obs-studio\bin\64bit\obs64.exe"   # OBS executable
$LocalPort   = 8088   # PC side of the tunnel (OBS Browser source uses http://127.0.0.1:8088/)
$DevPort     = 8080   # camera-server port on the Portal
$CamActivity = 'com.portalcam/.MainActivity'   # PortalCam (this repo). IP Webcam: com.pas.webcam/.Configuration
# ==================================================

$env:ADB_LIBUSB = '1'   # REQUIRED: the Zadig WinUSB driver needs adb's libusb backend on Windows

Write-Host "===== Portal TV webcam: startup =====" -ForegroundColor Cyan
& $Adb start-server | Out-Null
Write-Host "Looking for the Portal over USB" -NoNewline -ForegroundColor Gray
$found = $false
for ($i = 0; $i -lt 15; $i++) {
    if ((& $Adb devices) | Select-String '\bdevice$') { $found = $true; break }
    Write-Host '.' -NoNewline -ForegroundColor Gray; Start-Sleep -Seconds 1
}
Write-Host ''
if (-not $found) {
    Write-Host "Portal NOT connected. Plug in USB-C; on the device re-toggle Settings > Debug > ADB," -ForegroundColor Yellow
    Write-Host "unplug/replug, accept 'Allow USB debugging', then run again. (See README, Part 1.)" -ForegroundColor Yellow
    Read-Host "`nPress Enter to close"; exit 1
}
Write-Host "[ok] Portal connected." -ForegroundColor Green

& $Adb forward --remove-all | Out-Null
& $Adb forward "tcp:$LocalPort" "tcp:$DevPort" | Out-Null
Write-Host "[ok] USB tunnel: http://127.0.0.1:$LocalPort -> Portal:$DevPort" -ForegroundColor Green

function Test-Cam { try { Invoke-WebRequest "http://127.0.0.1:$LocalPort/shot.jpg" -TimeoutSec 4 -UseBasicParsing -OutFile "$env:TEMP\pcam_probe.jpg" | Out-Null; return $true } catch { return $false } }
if (Test-Cam) {
    Write-Host "[ok] Camera server is live." -ForegroundColor Green
} else {
    Write-Host "[..] Camera server not responding - launching the camera app on the Portal..." -ForegroundColor Yellow
    & $Adb shell am start -n $CamActivity | Out-Null
    Write-Host -NoNewline "     Waiting for the server" -ForegroundColor Gray
    for ($i = 0; $i -lt 30; $i++) { Start-Sleep -Seconds 2; if (Test-Cam) { break }; Write-Host '.' -NoNewline -ForegroundColor Gray }
    Write-Host ''
    if (Test-Cam) { Write-Host "[ok] Camera server is up." -ForegroundColor Green }
    else { Write-Host "[!!] Still no server - start the camera app on the Portal, then re-run." -ForegroundColor Yellow }
}

if (Get-Process obs64 -ErrorAction SilentlyContinue) {
    Write-Host "[i]  OBS already running. If the camera shows the OBS logo, Stop then Start the Virtual Camera." -ForegroundColor Yellow
} elseif (Test-Path $ObsExe) {
    Start-Process -FilePath $ObsExe -WorkingDirectory (Split-Path $ObsExe) -ArgumentList '--startvirtualcam','--disable-shutdown-check'
    Write-Host "[ok] Launched OBS with the Virtual Camera." -ForegroundColor Green
} else {
    Write-Host "[!!] OBS not found at $ObsExe - edit the CONFIG block." -ForegroundColor Yellow
}
Write-Host "`nReady. In your video app, choose 'OBS Virtual Camera'." -ForegroundColor Cyan
Start-Sleep -Seconds 3
