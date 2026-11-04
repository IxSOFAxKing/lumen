# Lumen for Android TV — plan & integration contract

This document describes how the Android TV app will be built on top of the Lumen server, and what the web app already does to support it.

## Recommended architecture

A small **native Kotlin shell** that:

1. On first launch asks for the Lumen server address (e.g. `http://192.168.1.50:8080`) and stores it.
2. Hosts the Lumen web UI in a full-screen **WebView**. All browsing, the guide, settings and so on are the existing web app, so every feature works on TV without being rewritten.
3. Hands **video playback to Media3 / ExoPlayer**, so streams play with Android's hardware decoders. That covers HEVC/H.265, Dolby Digital (AC-3/E-AC-3), DTS passthrough, MKV and MPEG-TS, which a browser can't always play, and avoids server transcoding for most content.

Why this split: Android TV's WebView is fine for UI, but `<video>` inside a WebView doesn't get the full codec support or audio passthrough. ExoPlayer does, and it's what Plex, Jellyfin and TiviMate use.

```
┌──────────── Android TV app ────────────┐
│  WebView  ──(JS bridge)──►  ExoPlayer  │
│  (Lumen web UI)              (native)  │
└───────────────┬────────────────┬───────┘
                │ HTTP           │ HTTP (direct streams)
                ▼                ▼
           Lumen server ──► Plex / IPTV provider
```

## What the web app already provides

| Feature | Where |
|---|---|
| **D-pad / remote navigation.** Arrows move focus to the nearest item, OK/Enter activates, Back goes back. | `web/js/tv.js` |
| **TV mode** (auto-detected from the user agent, `?tv=1`, or `LumenNative.isTV()`): large focus rings, no hover-only controls, no cursor. | `web/js/tv.js`, CSS `body.tv-mode` |
| **Per-device settings**, so the TV can have its own scale, theme and sidebar. | Settings → Appearance |
| **Back button hook:** `window.lumenBack()` returns `true` if Lumen handled it (closed a dialog or player, or went back a page). If it returns `false`, the app should exit or minimise. | `web/js/tv.js` |
| **Native player bridge:** when `window.LumenNative.play` exists, every player call (Plex, live TV, catch-up, VOD, recordings) goes to the native player instead of the web player. | `web/js/player.js` |
| **Wider direct play for native clients.** The web app sends `client: "native"`, and the server then direct-plays MKV/HEVC/AC-3/DTS instead of transcoding. | `server/src/routes/plex.js`, `iptv.js` |

## JS bridge contract

### Native → web: `window.LumenNative` (injected with `addJavascriptInterface`)

| Method | Description |
|---|---|
| `play(json: String)` | Start playback (see the payload below). Replaces anything already playing. |
| `stop()` | Stop playback and close the player. |
| `isTV(): Boolean` | Optional. Return `true` to force TV mode. |
| `version(): String` | Optional. App version, for diagnostics. |

`play` payload:

```json
{
  "url": "http://lumen:8080/api/plex/file?path=...",
  "type": "file | hls | mpegts",
  "startPosition": 1234.5,
  "startOffset": 0,
  "title": "Movie title",
  "subtitle": "S1E2 · Episode name",
  "live": false,
  "duration": 5400,
  "subtitleUrl": "http://lumen:8080/api/plex/subtitle/...vtt or null",
  "markers": [{ "type": "intro", "start": 30, "end": 95 }],
  "hasNext": true,
  "canZap": false,
  "channel": { "id": "101", "name": "BBC One", "num": 1, "logo": "http://..." }
}
```

- `startOffset`: for server-transcoded sessions the stream starts at this offset, so the true position is `player position + startOffset`.
- `canZap`: when `true`, map CH+/CH− (and D-pad up/down while the controls are hidden) to `lumenNative.zap(±1)`.
- `markers`: use these to show "Skip intro" and "Skip credits" buttons.

### Web → native callbacks: `window.lumenNative` (call with `evaluateJavascript`)

| Call | When |
|---|---|
| `lumenNative.progress(positionSec, durationSec, "playing" or "paused")` | Every ~10 s and on pause/resume. Lumen syncs resume points to Plex and IPTV VOD. |
| `lumenNative.ended()` | Playback reached the end. Lumen autoplays the next episode if enabled. |
| `lumenNative.closed(positionSec)` | The user left the player. Lumen saves the position and releases server resources. |
| `lumenNative.zap(+1 or -1)` | Channel up/down during live TV. Lumen calls `play()` again with the new channel. |
| `lumenNative.error("message")` | ExoPlayer failed. Lumen retries with a more compatible stream (remux → transcode). |

## Authentication

If `APP_PASSWORD` is set, Lumen uses an HttpOnly session cookie. The WebView logs in normally. ExoPlayer must send the same cookie: read it with `CookieManager.getInstance().getCookie(serverUrl)` and add it as a `Cookie` header in a `DefaultHttpDataSource.Factory().setDefaultRequestProperties(...)`.

## Key mapping

| Remote button | Action |
|---|---|
| D-pad | Passed to the WebView (Lumen moves focus) |
| OK / Center | `Enter` |
| Back | `evaluateJavascript("lumenBack()")`. If it returns `false`, `finish()` |
| Play/Pause, FF/REW | Handled by ExoPlayer while playing |
| CH+ / CH− | `lumenNative.zap(±1)` while playing live TV |
| Number keys | Optional: forward to the WebView (live number entry) |

## Manifest essentials

- `<uses-feature android:name="android.software.leanback" android:required="false"/>` and `android.hardware.touchscreen required="false"`
- A `LEANBACK_LAUNCHER` intent filter and a 320×180 `android:banner`
- `android:usesCleartextTraffic="true"` (home servers are usually plain HTTP), or a network security config for the server's address

## Build steps (when we start)

1. New Android Studio project: "Android TV – Empty Activity" (Kotlin, minSdk 26).
2. Dependencies: `androidx.media3:media3-exoplayer`, `media3-exoplayer-hls`, `media3-ui`, `androidx.webkit`.
3. `MainActivity`: server-address setup, WebView (JavaScript and DOM storage enabled, `mediaPlaybackRequiresUserGesture=false`), plus the `LumenNative` interface above.
4. `PlayerActivity` / fragment: ExoPlayer with `PlayerView` and the callbacks above.
5. A GitHub Actions workflow to build a signed APK for sideloading (Downloader / ADB).

## Installing & updating

1. On the TV, install **Downloader** (AFTVnews) from the Play Store / Amazon Appstore.
2. Enter `http://<your-lumen-server>:8080/tv.apk`. It redirects to the latest APK on GitHub Releases.
3. Open Lumen. It scans your network for the server, then shows a pairing code and QR code. Approve it in Lumen → Settings → Devices.

## Signing key (do this once, so updates install over each other)

Android only installs an update over an existing app when both are signed with the **same key**. Until you add one, CI signs each build with a temporary key, so you'd have to uninstall before every update and pair again.

Create a key on your computer (keep the file and passwords private, and **never commit it**):

```bash
keytool -genkeypair -v -keystore lumen-release.jks -storetype PKCS12 -alias lumen -keyalg RSA -keysize 2048 -validity 36500
```

Then add three repository secrets (GitHub → Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `LUMEN_KEYSTORE_BASE64` | output of `base64 -w0 lumen-release.jks; echo` (macOS: `base64 -i lumen-release.jks`). Copy **all** of it; the `; echo` keeps the end of the text from running into your prompt. Check the length with `base64 -w0 lumen-release.jks \| wc -c`. The build log prints the length it received. |
| `LUMEN_KEYSTORE_PASSWORD` | the keystore password |
| `LUMEN_KEY_ALIAS` | `lumen` |
| `LUMEN_KEY_PASSWORD` | optional, not needed (keytool's default PKCS12 keystores use the keystore password for the key) |

From then on, every build is signed with your key and installs as an update.

## How settings stay in sync

The Lumen server is the hub. Plex and IPTV logins, settings, favourite lists, channel names and order, recordings and watch history are all stored on the server. The TV app, the web app and any future apps read and write the same data, so a change on one shows up on all of them. The only things kept per device are the optional "separate settings for this device" (Appearance) and TV mode.
