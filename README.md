# O|U KTV — Self-Hosting Android App

This app IS the server. Unlike a normal "wrapper" app that loads a website,
this phone runs its own embedded backend (HTTP + WebSocket server, its own
local SQLite database) — the same room/queue/playback logic that used to
live on a remote VM (`server.ts`), now running natively on-device.

**No external domain, VM, or internet server required to host or join.**
The one thing that still needs internet: searching/streaming songs, since
they're pulled live from YouTube — same as the original app, that part was
never really "yours" to self-host.

## How it works

- **Host phone**: installs this app, opens it. A foreground service
  (`HostService`) starts an embedded server (`KtvHttpServer`, built on
  NanoHTTPD) on port `8080`, backed by a local SQLite database (`Db`) that
  mirrors the original Postgres schema (rooms, songs, presence, activity,
  config, users). The app's own WebView loads `http://127.0.0.1:8080/ktv/`
  — the exact same frontend (`ktv-app.html`) as before, just served locally.
- **Guests**: no app install needed. On the *same WiFi network*, they open
  `http://<host-phone's-LAN-IP>:8080/ktv/` in any browser. Tap **Host Info**
  in the app to get that address as text or a scannable QR code (generated
  fully offline).
- **TV/host screen**: same — just open that address in the TV's browser.

Everything (creating rooms, joining, queueing, play/pause/next, presence,
real-time sync over WebSocket) runs entirely over the local network. Only
the search box calls out to YouTube.

## Limitations vs. the original VM-hosted version

- The server only runs while the app (or its background service) is alive
  on the host phone. If the host phone dies/closes the app fully or leaves
  WiFi range, guests lose connection — there's no separate always-on VM
  anymore. The foreground service + `START_STICKY` keeps it alive through
  backgrounding and screen-off, but not through a force-stop or reboot.
- Everyone must be on the same WiFi network (or the host's hotspot). This
  can't be reached over the open internet unless you add port forwarding /
  a tunnel yourself — that was out of scope for "self-hosted, no domain."
  If you later want internet-wide access again, add a dynamic DNS + router
  port-forward, or reverse tunnel (e.g. ngrok/Cloudflare Tunnel) pointed at
  the host phone's port 8080.
- Admin endpoints for QR image upload, login/user logs, and per-room song
  limits are implemented; a couple of very rarely used ones may need minor
  follow-up if you hit an edge case — tell me and I'll patch them.

## How to build the APK

Needs a machine with internet access (Gradle/Android SDK downloads) — this
can't be compiled inside a sandboxed AI environment.

### Option A — Android Studio (easiest)
1. Install [Android Studio](https://developer.android.com/studio).
2. Open Android Studio → "Open" → select this `OUKTV` folder.
3. Let it sync (first run downloads Gradle + SDK + the NanoHTTPD/ZXing
   dependencies automatically).
4. Build → Build Bundle(s) / APK(s) → Build APK(s), or press ▶ Run with a
   device connected.
5. Find the APK at `app/build/outputs/apk/debug/app-debug.apk`.
6. Install on the host phone (and optionally any other phone that also
   wants to host its own room independently).

### Option B — command line
```bash
cd OUKTV
gradle wrapper          # generates gradlew if missing
./gradlew assembleDebug
```
APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## First run checklist
1. Install the APK on the phone that will act as **host**.
2. Open the app — allow camera + notification permissions when prompted
   (camera is for the in-app QR scanner; notification is for the "hosting
   is running" persistent notice).
3. Make sure the phone is connected to a WiFi network (not just mobile
   data) — other devices need to reach it on that network.
4. Tap **Host Info** to get the join address/QR for guests.
5. Guests open that address in their phone's browser — no app needed.

## If something doesn't work once built
Send me the exact error (Logcat output if it's a crash, or a screenshot if
it's a UI/behavior issue) and I'll patch the source directly. Common things
I can adjust from here:
- Server port (currently 8080)
- Database schema / add more admin features
- WebView / native UI appearance
- Permission handling
- Adding a "Guest mode" (join-only, no local server) build variant

## Project layout
```
OUKTV/
├── app/src/main/
│   ├── assets/www/
│   │   ├── ktv-app.html          ← the SPA itself, unmodified from the zip
│   │   └── ext/                  ← fonts, video.js, etc. (static assets)
│   ├── java/com/ouktv/app/
│   │   ├── MainActivity.java     ← WebView + Host Info UI, starts HostService
│   │   ├── HostService.java      ← foreground service running the server
│   │   ├── KtvHttpServer.java    ← the ported backend (routes, room logic, WS relay)
│   │   ├── Db.java               ← SQLite schema + helpers (replaces Postgres)
│   │   ├── YoutubeSearch.java    ← ported YouTube innertube search (needs internet)
│   │   ├── QrUtil.java           ← offline QR code generation (ZXing)
│   │   └── NetUtils.java         ← finds the phone's LAN IP to show guests
│   ├── res/                      ← icons, layout, theme, colors
│   └── AndroidManifest.xml       ← permissions + foreground service declaration
├── app/build.gradle              ← dependencies: NanoHTTPD, ZXing, AndroidX
├── build.gradle / settings.gradle / gradle.properties
```
