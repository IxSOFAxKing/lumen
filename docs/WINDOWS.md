# Lumen for Windows

A small Windows app: the Lumen web interface, with playback in **mpv** instead of the browser. That means 4K, HDR,
HEVC/AV1 and Dolby / DTS sound play directly (no transcoding on the server).

## Install

Download `Lumen-Windows.zip` (Settings → Devices in Lumen, or the `windows-latest` release on GitHub), unzip it
anywhere and run `Lumen.exe`. It looks for your Lumen server on the network (port 8080), or type the address.
Sign in as in the browser. Needs 64-bit Windows 10/11 (the Edge WebView2 runtime is built in).

## Made to sit next to games

- Video is decoded on the graphics card's video engine (`hwdec=auto-safe`), and mpv runs its `fast` profile (no heavy
  scaling shaders).
- mpv only exists while something plays; closing the video unloads it.
- While a video plays or the window is minimized, the web interface is suspended and asked to use little memory.
- The app runs at *below normal* priority, so the game gets the CPU first.

## Keys while playing

Esc / Backspace back · F, F11 or double-click full screen · Space pause · ← → seek · ↑ ↓ channel (Live TV) or volume ·
PgUp / PgDn channel · S skip intro / credits · N next episode · mouse wheel volume.

Live TV: an info bar (channel, programme, progress, next, clock) shows when the channel changes or the mouse moves;
Enter (again) or the Channels button opens the channel list (↑ ↓, wheel, Enter or click); type a number to jump to a channel. All of mpv's usual keys work too
(`j` subtitles, `#` audio track, `i` stats).

## Your own mpv settings

Put an `mpv.conf` in `%APPDATA%\Lumen\mpv` — it's read every time a video starts and overrides Lumen's defaults
(for example `profile=gpu-hq` when you aren't gaming, or `audio-spdif=ac3,eac3,dts` for a receiver).

App settings (server address, window size, volume) are in `%APPDATA%\Lumen\config.json`.
