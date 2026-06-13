# Portal TV → PC Webcam

This project lets you turn a **Meta Portal TV** into a webcam for your Windows PC.

Once set up, you can use the Portal TV camera in apps like **Zoom, Google Meet, Microsoft Teams, Discord, OBS**, or anything else that lets you choose a webcam.

The basic idea is simple: the Portal TV captures the video, your PC receives it over USB (or Wi-Fi), and **OBS** then makes that video appear to other apps as a normal webcam.

This guide documents the full process — including the problems we ran into — so you don't have to figure them out from scratch.

## Simple Summary

There are two ways to do this:

- **Easiest method:** install the **IP Webcam** app on the Portal TV, add it to OBS, and turn on OBS Virtual Camera. About 30 minutes, no coding.
- **Best method:** install the small **PortalCam** app from this project. It starts automatically, has no ads, and is built specifically for this setup. **You can download a prebuilt copy** (see [Releases](../../releases/latest)) — no coding required.
- **Best connection:** use **USB**. It's faster, more stable, and avoids Wi-Fi problems.
- **Optional improvement:** if you have an **NVIDIA RTX** graphics card, OBS filters can clean up and upscale the video from 720p to a better-looking 1080p feed.

## Important Limits

Before you start, a few things to understand:

**The video is limited to 720p.** The camera hardware can do more, but normal apps can only access a 720p version of the feed — the higher-quality path appears reserved for Meta's own software. Even if an app asks for 1080p, the Portal still returns 720p.

**The built-in microphone doesn't work for this.** The Portal's mic array is locked behind a Meta-only permission, so apps you install yourself receive silence. **Use a separate mic** (USB or Bluetooth).

**The Portal TV has no touchscreen.** It's built for a remote or voice. Almost everything here is driven from your PC using `adb`. Once PortalCam is installed it starts on its own, so you rarely touch the Portal.

**The hardware privacy button overrides everything.** The camera bar has a physical privacy button — if the **red light** is on, the camera and mic are off. No software can override this. If the camera seems dead, **check this button first.**

## What You Need

- A Meta Portal TV with **ADB/developer access** enabled
- A Windows 10 or 11 PC
- [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (includes `adb`)
- [Zadig](https://zadig.akeo.ie/) (used once, to set up the USB driver)
- [OBS Studio](https://obsproject.com/) 28 or newer
- *Optional:* an NVIDIA RTX GPU + [NVIDIA Broadcast](https://www.nvidia.com/en-us/geforce/broadcasting/broadcast-app/) for AI video cleanup

> **Note on running `adb`:** `adb` isn't a built-in Windows command. Run the commands below from inside the `platform-tools` folder, or [add that folder to your PATH](https://www.java.com/en/download/help/path.html) so `adb` works from anywhere.

## Step 1: Connect the Portal TV to Windows

On the Portal TV, go to **Settings → Debug → ADB Enabled** (you may need to enter a PIN). Then connect the Portal to your PC with a USB-C cable.

Windows may show the Portal in Device Manager **without a working driver** — this is expected. To fix it, use **Zadig**:

1. Open Zadig.
2. **Options → ✓ List All Devices.**
3. Select **PortalTV** (confirm the USB ID starts with `2EC6`).
4. Choose **WinUSB** as the driver.
5. Click **Install Driver.**

(This only affects how *this PC* talks to *this Portal* over USB, and can be reversed later.)

Next, open **PowerShell** and tell ADB to use its USB backend, then list devices:

```powershell
$env:ADB_LIBUSB='1'
adb devices
```

The Portal TV should appear. If you see an error about **missing ADB endpoints**, turn ADB off and on again on the Portal, then unplug and reconnect the USB cable. You should also get an **"Allow USB debugging"** prompt on the TV — accept it. The Portal should then show as a connected `device`.

## Step 2: Start the Camera Stream

### Option A: PortalCam (recommended)

PortalCam is the small app in this project. It opens the Portal camera and serves the video to your PC. It provides a live feed at `:8080/video` and a single image at `:8080/shot.jpg`, **starts automatically** (on launch and boot), **retries automatically** if the camera isn't ready after a reboot, has **no ads**, and needs **no Google services**.

**Easiest — install the prebuilt app (no build tools):**

1. Download `PortalCam-v1.0.apk` from the [Releases page](../../releases/latest).
2. Install and launch it:

```powershell
adb install -r -g PortalCam-v1.0.apk
adb shell am start -n com.portalcam/.MainActivity
```

(`-g` grants the camera permission during install. The APK is debug-signed, which is fine for sideloading.)

**For developers — build it yourself:**

You'll need **JDK 17**, the **Android SDK** (platform 34 + build-tools 34), and **Gradle 8.x**. Then:

```powershell
cd PortalCam
gradle assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.portalcam/.MainActivity
```

### Option B: IP Webcam (easiest, no app from this project)

Install the **Android 9 / arm64** version of [IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam). The Portal has **no app store**, so download the APK from a source like **APKMirror** and push it over USB. If it comes as an `.apkm` bundle, unzip it and install the needed parts:

```powershell
adb install-multiple base.apk split_config.arm64_v8a.apk split_config.xhdpi.apk split_config.en.apk
adb shell pm grant com.pas.webcam android.permission.CAMERA
```

Launch IP Webcam on the Portal and tap **Start server**. It serves the same `:8080/video` feed, and the rest of the setup is identical.

## Step 3: Send the Camera Feed Over USB

For the best results, send the video over USB instead of Wi-Fi:

```powershell
adb forward tcp:8088 tcp:8080
```

Your PC can now see the video at `http://127.0.0.1:8088/video`. Using USB avoids Wi-Fi dropouts, changing IP addresses, and extra latency.

## Step 4: Add the Camera to OBS

> **Don't use OBS "Media Source"** for this feed — it works but adds about a second of delay. Use a **Browser Source** instead.

In OBS:

1. Add a **Browser** source.
2. Check **Local file** and select **`scripts/portalcam.html`**.
3. Right-click the source → **Transform → Fit to Screen.**
4. Click **Start Virtual Camera.**

That HTML page rapidly grabs still JPEG frames from the Portal and displays them in OBS — which is far more reliable than asking OBS to play the MJPEG video stream directly. (If your tunnel uses a port other than `8088`, edit the URL inside `portalcam.html`.)

Now open Zoom / Meet / Teams / Discord and choose **OBS Virtual Camera**. If it doesn't appear, **restart that app or browser** — many apps only check for cameras when they first open.

> **Tip:** for everyday use, just double-click **`scripts/Start-Portal-Cam.cmd`** — it arms the USB tunnel, makes sure the camera app is running, and launches OBS, all in the right order.

## Step 5: Improve the Video with NVIDIA RTX (optional)

If you have an NVIDIA RTX card, you can clean up and upscale the 720p feed in OBS. Install the [OBS RTX Super Resolution plugin](https://github.com/Bemjo/OBS-RTX-SuperResolution) (it needs the NVIDIA Video Effects runtime, which comes with NVIDIA Broadcast). Then add these filters to the camera source, **in this order**:

1. **NVIDIA Artefact Reduction**
2. **NVIDIA Super Resolution** (set to 2×)

Artefact Reduction removes compression blocks *before* the image is upscaled, which makes the 720p feed look closer to a clean 1080p webcam.

## Included Scripts (`scripts/`)

- **`start-portal-cam.ps1`** — starts the whole setup in the right order (USB tunnel → camera app → OBS + virtual camera). Edit the CONFIG block at the top to point at your `adb` and OBS locations.
- **`Start-Portal-Cam.cmd`** — a double-click launcher for the PowerShell script.
- **`portalcam.html`** — the browser page OBS uses to display the camera feed.
- **`Register-OBS-VirtualCam.cmd`** — repairs/re-registers the OBS Virtual Camera if it disappears or stops working (run as administrator).

## Troubleshooting

**Camera doesn't work and the red light is on** → the hardware privacy button is enabled. Press the button on the camera bar. No software can bypass it.

**Camera doesn't work right after boot** → the Portal's camera service may not be ready yet. PortalCam auto-retries until it is; other apps may need to be closed and reopened.

**The video freezes on an old frame** → the camera stream stalled. Restart the camera app. (PortalCam may later include a watchdog to detect this automatically.)

**OBS says "Failed to start virtual camera"** → the virtual camera got stuck, often after force-closing OBS while it was running. Run **`Register-OBS-VirtualCam.cmd`** as administrator; if that doesn't help, reboot. Avoid force-killing OBS while the virtual camera is active.

**OBS preview is blank** → the USB tunnel probably isn't running. Run **`start-portal-cam.ps1`** (it arms the tunnel first). Opening OBS by itself doesn't start the tunnel.

## How the Setup Works

```text
Portal TV camera
  → PortalCam (turns the camera into a local video stream)
  → USB tunnel using ADB
  → OBS Browser Source
  → optional NVIDIA cleanup/upscale
  → OBS Virtual Camera
  → Zoom / Meet / Teams / Discord
```

## What We Learned

- The Portal TV camera hardware appears to support higher resolutions, but normal apps are limited to **720p**.
- The built-in mic array isn't usable here — it needs a Meta-only permission, so installed apps get silence.
- OBS doesn't reliably show the direct MJPEG stream in a browser source, but it works great when repeatedly loading still JPEG frames.
- Force-closing OBS can break the virtual camera driver. Closing OBS normally is safer.

## License

[MIT](LICENSE). This project is not affiliated with or endorsed by Meta.
