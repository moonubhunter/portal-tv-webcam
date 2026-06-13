# Portal TV → PC Webcam

Turn a Meta **Portal TV** (or other ADB-unlocked Portal) into a low-latency, optionally AI-enhanced **webcam for your PC** — usable in Zoom, Google Meet, Teams, Discord, OBS, anything that takes a camera.

In late 2026 Meta unlocked ADB / developer access on Portal devices. This project repurposes that hardware: the Portal captures video, streams it to a Windows PC over USB (or Wi-Fi), and OBS exposes it as a standard **"OBS Virtual Camera."**

> Documented end-to-end from a real build session — including the dead-ends — so you don't have to rediscover them.

---

## TL;DR

- **Quick path:** sideload **IP Webcam** on the Portal → OBS **Browser** source → OBS Virtual Camera (~30 min, no compiling).
- **Best path:** build & install **PortalCam** (the tiny app in this repo) → auto-starts, no ads, self-heals, drop-in replacement.
- **Transport:** **USB** (`adb forward`) for low, stable latency; Wi-Fi works too.
- **Enhance (NVIDIA):** OBS **RTX Super Resolution + Artefact Reduction** upscales the 720p feed to a clean 1080p.

## ⚠️ Hard limits (read these first)

| Limit | Why |
|---|---|
| **Video maxes at 720p** | The camera is owned by a privileged on-device "Smart Camera" service that only exposes **720p** to apps. The sensor does 4K, but that path is reserved for Meta-signed code. *(Verified: requesting 1080p just returns 720p; both camera IDs behave the same.)* |
| **Built-in mic is unusable** | The far-field mic array requires a Meta-signed permission; sideloaded apps get **digital silence**. Use a **Bluetooth or USB mic** for audio. |
| **No touchscreen (Portal TV)** | It's remote/voice-driven. Everything here is driven from the PC over adb; PortalCam auto-starts so you rarely touch the device. |
| **Hardware privacy button** | A physical button on the camera bar kills cam+mic (**red light = off**). No software can override it — check it first if the camera "dies." |

## Requirements

- A Portal with ADB unlocked: **Settings → Debug → ADB Enabled**
- Windows 10/11 PC
- [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`)
- [Zadig](https://zadig.akeo.ie/) (one-time USB driver bind)
- [OBS Studio](https://obsproject.com/) 28+
- *(optional, AI enhancement)* NVIDIA RTX GPU + [NVIDIA Broadcast](https://www.nvidia.com/en-us/geforce/broadcasting/broadcast-app/) (ships the Video Effects runtime)

---

## Part 1 — Connect ADB (Windows)

Windows has no driver for the Portal's USB vendor ID (`2EC6`), so you bind the inbox **WinUSB** driver:

1. On the Portal: **Settings → Debug → ADB Enabled** (enter PIN).
2. Connect the Portal to the PC via USB-C. In Device Manager it appears as **PortalTV** with no driver (Code 28).
3. Run **Zadig** → **Options → ✓ List All Devices** → select **PortalTV** (confirm the USB ID is `2EC6 xxxx`) → target driver **WinUSB** → **Install Driver**. *(Reversible; only affects how this PC talks to this device.)*
4. **Run adb with `ADB_LIBUSB=1`** (PowerShell: `$env:ADB_LIBUSB='1'`). Zadig assigns a generic device-interface GUID, so adb's classic backend can't find it — the **libusb backend** reads USB descriptors directly and works.
5. `adb devices` should list the Portal. If it instead logs `ADB interface missing endpoints: bulk_out=`, **re-toggle ADB Enabled off/on on the device AND unplug/replug USB** — the ADB function races the connect and enumerates without endpoints until re-enumerated.
6. Accept **"Allow USB debugging"** on the Portal's screen (it shows on the connected TV — make sure it's awake). Status flips to `device`.

## Part 2 — Get the camera streaming

### Option A — PortalCam (recommended; this repo's app)

A tiny **headless** app: opens the camera via Camera2, serves **MJPEG** at `:8080/video` and a single JPEG at `:8080/shot.jpg`, runs as a foreground service, **auto-starts on launch and on boot**, and **auto-retries** the camera (handles the post-boot `ERROR_CAMERA_DISABLED` transient). No Google services required.

Build & install (one-time toolchain: **JDK 17**, **Android SDK** platform-34 + build-tools 34, **Gradle 8.x**):

```bash
cd PortalCam
# point at your toolchain (example):
#   $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17...'
#   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
gradle assembleDebug          # no wrapper is bundled; use system Gradle 8.x (AGP 8.2)
adb install -r -g app/build/outputs/apk/debug/app-debug.apk   # -g grants CAMERA
adb shell am start -n com.portalcam/.MainActivity
```

### Option B — IP Webcam (no build)

Sideload [IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam) (grab the **arm64 / Android 9** APK from APKMirror). It usually comes as an `.apkm` bundle — unzip it and install the needed splits:

```bash
adb install-multiple base.apk split_config.arm64_v8a.apk split_config.xhdpi.apk split_config.en.apk
adb shell pm grant com.pas.webcam android.permission.CAMERA
```

Launch it on the Portal and tap **Start server**. It serves the same `:8080/video` endpoint, so the rest of this guide is identical.

## Part 3 — Transport: the USB tunnel (low latency)

Forward the camera port over the USB cable instead of relying on Wi-Fi:

```bash
adb forward tcp:8088 tcp:8080
```

The stream is now at `http://127.0.0.1:8088/video` — stable (no DHCP address to chase), low-latency, and works with Wi-Fi off.

## Part 4 — PC side: OBS Virtual Camera

> **Do not use OBS's "Media Source"** for the MJPEG — its ffmpeg ingest buffers ~1 second of latency. Use a **Browser source** instead.

1. Add a **Browser** source → check **Local file** → pick **`scripts/portalcam.html`**. That page polls `/shot.jpg` in a tight load-chained loop and renders near-instantly in OBS's embedded Chromium.
   - *Why polling instead of `<img src="…/video">`? OBS's CEF rejects some multipart-MJPEG streams that ffmpeg/VLC happily accept. Polling plain JPEGs is bulletproof.*
   - If your tunnel port isn't `8088`, edit the URL in `portalcam.html`.
2. Right-click the source → **Transform → Fit to screen**.
3. Click **Start Virtual Camera** (Controls dock).
4. In Zoom / Meet / Teams / Discord, choose **"OBS Virtual Camera."** If it doesn't appear, **restart that app or browser** — apps enumerate cameras once at launch.

## Part 5 — AI enhancement (optional, NVIDIA RTX)

Clean up and upscale the 720p feed to a sharp 1080p:

1. Install [obs-rtx-superresolution](https://github.com/Bemjo/OBS-RTX-SuperResolution) into your OBS install dir (needs the NVIDIA Video Effects runtime, which ships with NVIDIA Broadcast).
2. On the camera source, add filters **in this order**: **NVIDIA Artefact Reduction** → **NVIDIA Super Resolution** (2×). Artefact Reduction strips the JPEG/compression blocking *before* the AI upscale, which matters for a compressed source.

## Scripts (`scripts/`)

| File | What it does |
|---|---|
| **`start-portal-cam.ps1`** | One-click bring-up: arms the USB tunnel → ensures the camera app is running → launches OBS with the virtual camera, **in the correct order**. **Edit the CONFIG block** (adb path, OBS path). |
| **`Start-Portal-Cam.cmd`** | Double-click wrapper for the PowerShell script. |
| **`portalcam.html`** | The OBS Browser-source page (JPEG-polling renderer). |
| **`Register-OBS-VirtualCam.cmd`** | Re-registers the OBS Virtual Camera filter if it goes missing. |

## Troubleshooting

- **No camera, red light on the bar** → the **hardware privacy button** is engaged. Press it. No software can override it.
- **Camera `ERROR_CAMERA_DISABLED` right after boot** → the Smart Camera service isn't ready yet. PortalCam auto-retries until it is; other apps just need a relaunch.
- **Feed frozen on an old frame** → the camera silently stalled (can happen on long-running sessions). Relaunch the camera app. *(PortalCam: a frame-stall watchdog is a known TODO.)*
- **OBS "Failed to start virtual camera" / button missing** → the vcam filter got wedged — usually after **force-killing OBS** while it was running. Run `Register-OBS-VirtualCam.cmd` (admin) and/or **reboot**. Don't force-kill OBS.
- **OBS preview blank** → the USB tunnel isn't armed. Run `start-portal-cam.ps1` (it arms the tunnel *before* launching OBS). Opening OBS by itself skips that step.

## How it works / what we learned

```
Portal camera ──(Camera2, 720p)──> PortalCam (MJPEG server :8080)
      │                                   │
      │                         adb forward 8088→8080 (USB)
      ▼                                   ▼
 [privileged Smart            OBS Browser source (polls /shot.jpg)
  Camera service caps           → NVIDIA Artefact-Reduction + Super-Resolution
  apps at 720p]                 → OBS Virtual Camera → Zoom/Meet/Teams/…
```

Notable findings from the build:
- `dumpsys media.camera` shows the HAL exposing 1080p/4K, but every app opens the **Smart Camera virtual camera**, which clamps output to **720p**. The 4K path is privileged-only.
- The mic array is gated behind a **Meta-signed** permission → sideloaded apps record silence. Bring your own (BT/USB) mic.
- OBS's CEF won't render the multipart MJPEG, but renders polled JPEGs fine — hence `portalcam.html`.
- Force-killing OBS wedges the virtual-camera DirectShow filter; a clean exit (and, if needed, re-register + reboot) avoids it.

## License

[MIT](LICENSE). Not affiliated with or endorsed by Meta.
