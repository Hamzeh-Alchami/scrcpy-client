# Scrcpy Mirror

A small, foldable-friendly Android app for viewing and controlling a phone that is shared through
[ws-scrcpy](https://github.com/NetrisTV/ws-scrcpy). Enter your server URL and the mirrored screen
fills the whole display, and touch works.

## Features

- **Full screen, edge to edge**: immersive mode, and the stream also uses the area around the camera cutout.
- **Touch control**: taps, swipes and multi-touch go straight to ws-scrcpy. The WebView's own zoom, scroll and long-press are turned off so they don't take your gestures.
- **Works on foldables**: folding, unfolding, rotating or resizing in split screen keeps the session open.
  After the screen size settles, the app reconnects the stream once so ws-scrcpy sends video sized for the new screen. You can turn this off.
- **Hides the ws-scrcpy toolbar** (optional) so the device screen gets all the space. You can show it again from the menu.
- **Floating ⋮ button** (you can drag it, and hide it) and the **Back** key both open a menu: show/hide toolbar,
  reconnect, back to device list, disconnect.
- **Recent URLs**, an option to auto-connect on launch, and keep-screen-on.
- **HTTP Basic Auth** login prompt (for ws-scrcpy behind nginx/Caddy with a password). Credentials are remembered.
- Plain `http://` and `ws://` work (cleartext is allowed, since ws-scrcpy usually runs on a LAN or VPN).
  Certificates you install as user CAs on the phone are trusted, so a private CA for HTTPS works too.

## Which URL to enter

- `http://<server>:8000` shows ws-scrcpy's device list. Tap your device's stream link (e.g. *H264 Converter* / *MSE*).
- Or paste the **full stream link** (open ws-scrcpy in a browser, start the stream, copy the address bar,
  something like `http://server:8000/#!action=stream&udid=...&player=mse&ws=...`). The app then opens the
  mirrored screen right away.

## Get the APK

On every push, GitHub Actions builds the app and publishes it to the **[Latest build](../../releases/tag/latest)** release.
Open that page on your phone, tap `scrcpy-mirror.apk` and install it. Allow installs from unknown sources when asked. Use the normal Android installer; there is no need for APKMirror/SAI.

## Build locally

Requires JDK 17+ and the Android SDK (API 35).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security note

ws-scrcpy gives full control of the phone and has no login of its own. If you reach it "from everywhere",
put it behind a VPN (Tailscale/WireGuard) or a reverse proxy with HTTPS + authentication instead of
exposing port 8000 to the internet.
