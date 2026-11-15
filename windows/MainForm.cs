using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Lumen;

/// <summary>
/// Lumen for Windows: the Lumen web app (WebView2) with playback handed to mpv through the same bridge the Android TV
/// app uses (window.LumenNative, see docs/ANDROID_TV.md). Kept light for gaming: mpv only exists while something plays,
/// the web view is suspended while a video plays or the window is minimized, and the process runs below normal priority.
/// </summary>
public sealed class MainForm : Form
{
    static readonly string Version = typeof(MainForm).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
    static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

    readonly Config cfg = Config.Load();
    readonly WebView2 web = new() { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.FromArgb(11, 13, 18) };
    readonly Panel video = new() { Dock = DockStyle.Fill, BackColor = Color.Black, Visible = false, TabStop = true };
    readonly Button back = new() { Text = "←  Back", AutoSize = true, Visible = false, FlatStyle = FlatStyle.Flat, ForeColor = Color.White,
        BackColor = Color.FromArgb(30, 30, 34), Font = new Font("Segoe UI Semibold", 10.5f), Cursor = Cursors.Hand, TabStop = false };
    readonly System.Windows.Forms.Timer tick = new() { Interval = 500 };

    Mpv? mpv;
    Play? cur;
    bool fullscreen, fsByPlayer;
    FormWindowState wasState = FormWindowState.Normal;
    long lastReport, backHideAt;
    string? lastMouse, lastPause;
    readonly HashSet<string> skipShown = new();
    int loadSeq;
    int endWait;

    sealed record Play(string Url, string Type, double Start, double Offset, string Title, string Subtitle, bool Live, double Duration,
        string? SubtitleUrl, List<(string Type, double Start, double End)> Markers, bool HasNext, bool CanZap, string? Channel);

    public MainForm()
    {
        Text = "Lumen";
        BackColor = Color.FromArgb(11, 13, 18);
        MinimumSize = new Size(640, 400);
        KeyPreview = true;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        if (cfg.Bounds is { Length: 4 } b) { StartPosition = FormStartPosition.Manual; Bounds = new Rectangle(b[0], b[1], b[2], b[3]); }
        else Size = new Size(1400, 860);
        if (cfg.Maximized) WindowState = FormWindowState.Maximized;
        back.FlatAppearance.BorderSize = 0;
        back.Click += (_, _) => ClosePlayer();
        Controls.Add(back); Controls.Add(video); Controls.Add(web);
        back.BringToFront();
        tick.Tick += (_, _) => OnTick();
        Load += async (_, _) => await Start();
        Resize += (_, _) => UpdateWebActivity();
        FormClosing += (_, _) => Shutdown();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        try { int on = 1; DwmSetWindowAttribute(Handle, 20, ref on, sizeof(int)); } catch { } // dark title bar
    }
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr hWnd, uint cmd);
    [DllImport("user32.dll")] static extern IntPtr SetFocus(IntPtr hWnd);

    // ------------------------------------------------------------------ start-up
    async Task Start()
    {
        if (string.IsNullOrEmpty(cfg.Server) && !AskServer()) { Close(); return; }
        try
        {
            var env = await CoreWebView2Environment.CreateAsync(null,
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Lumen", "WebView2"),
                new CoreWebView2EnvironmentOptions("--disable-background-networking --disable-component-update"));
            await web.EnsureCoreWebView2Async(env);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Lumen needs the Microsoft Edge WebView2 Runtime (included with Windows 10 and 11).\n\n" + ex.Message, "Lumen",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close(); return;
        }
        var core = web.CoreWebView2;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsZoomControlEnabled = true;
        await core.AddScriptToExecuteOnDocumentCreatedAsync(BridgeScript());
        core.WebMessageReceived += (_, e) => OnWebMessage(e.TryGetWebMessageAsString());
        core.ContainsFullScreenElementChanged += (_, _) => { if (cur == null) SetFullscreen(core.ContainsFullScreenElement); };
        core.NewWindowRequested += (_, e) => { e.Handled = true; OpenExternal(e.Uri); }; // e.g. plex.tv sign-in
        core.DocumentTitleChanged += (_, _) => Text = string.IsNullOrWhiteSpace(core.DocumentTitle) ? "Lumen" : core.DocumentTitle;
        core.Navigate(cfg.Server);
    }

    bool AskServer()
    {
        using var d = new ServerDialog(cfg.Server);
        if (d.ShowDialog(this) != DialogResult.OK) return false;
        cfg.Server = d.Server; cfg.Save();
        return true;
    }

    string BridgeScript() => $$"""
        (() => {
          const post = (op, data) => window.chrome.webview.postMessage(JSON.stringify({ op, data: data ?? null }));
          window.LumenNative = {
            play: (json) => post('play', json),
            stop: () => post('stop'),
            version: () => {{JsonSerializer.Serialize(Version)}},
            serverUrl: () => {{JsonSerializer.Serialize(cfg.Server)}},
            platform: () => 'windows',
            webUi: true,
            openAppSettings: () => post('settings'),
          };
        })();
        """;

    static void OpenExternal(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }

    void Js(string script)
    {
        try { _ = web.CoreWebView2?.ExecuteScriptAsync(script); } catch { }
    }

    static string N(double v) => double.IsFinite(v) ? v.ToString("0.###", Inv) : "0";

    // ------------------------------------------------------------------ web → app
    void OnWebMessage(string raw)
    {
        try
        {
            using var doc = JsonDocument.Parse(raw);
            var op = doc.RootElement.GetProperty("op").GetString();
            var data = doc.RootElement.GetProperty("data");
            switch (op)
            {
                case "play": StartPlay(data.GetString() ?? "{}"); break;
                case "stop": ClosePlayer(report: false); break;
                case "settings":
                    if (AskServer()) web.CoreWebView2.Navigate(cfg.Server);
                    break;
            }
        }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine(ex); }
    }

    static Play ParsePlay(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var r = doc.RootElement;
        string S(string k) => r.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
        double D(string k) => r.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;
        bool B(string k) => r.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.True;
        var markers = new List<(string, double, double)>();
        if (r.TryGetProperty("markers", out var m) && m.ValueKind == JsonValueKind.Array)
            foreach (var x in m.EnumerateArray())
                markers.Add((x.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "",
                    x.TryGetProperty("start", out var s) && s.ValueKind == JsonValueKind.Number ? s.GetDouble() : 0,
                    x.TryGetProperty("end", out var e) && e.ValueKind == JsonValueKind.Number ? e.GetDouble() : 0));
        string? channel = null;
        if (r.TryGetProperty("channel", out var c) && c.ValueKind == JsonValueKind.Object)
        {
            var num = c.TryGetProperty("num", out var n) && n.ValueKind == JsonValueKind.Number ? n.GetRawText() + "  " : "";
            channel = num + (c.TryGetProperty("name", out var nm) ? nm.GetString() : "");
        }
        var sub = S("subtitleUrl");
        return new Play(S("url"), S("type"), D("startPosition"), D("startOffset"), S("title"), S("subtitle"), B("live"), D("duration"),
            sub.Length > 0 ? sub : null, markers, B("hasNext"), B("canZap"), channel);
    }

    // ------------------------------------------------------------------ playback
    async void StartPlay(string json)
    {
        Play p;
        try { p = ParsePlay(json); } catch { return; }
        if (string.IsNullOrEmpty(p.Url)) return;
        var seq = ++loadSeq;
        endWait = 0;
        if (!EnsureMpv()) return;
        cur = p; skipShown.Clear(); lastReport = 0; lastPause = null;

        // The stream needs the same sign-in as the web app.
        string cookie = "";
        try
        {
            var list = await web.CoreWebView2.CookieManager.GetCookiesAsync(cfg.Server);
            cookie = string.Join("; ", list.Select(c => $"{c.Name}={c.Value}"));
        }
        catch { }
        if (seq != loadSeq || mpv == null) return;
        mpv.Command("change-list", "http-header-fields", "clr", "");
        if (cookie.Length > 0) mpv.Command("change-list", "http-header-fields", "append", "Cookie: " + cookie);
        mpv.Set("start", p.Start > 0 ? N(p.Start) : "none");
        mpv.Set("force-media-title", p.Subtitle.Length > 0 ? $"{p.Title} — {p.Subtitle}" : p.Title);
        // Live TV: a small buffer, so channels start quickly and don't drift far behind.
        mpv.Set("cache-secs", p.Live ? "10" : "60");
        ShowVideo(true);
        mpv.Command("loadfile", p.Url, "replace");
    }

    bool EnsureMpv()
    {
        if (mpv != null) return true;
        try
        {
            var dir = Path.Combine(Config.Dir, "mpv");
            Directory.CreateDirectory(dir);
            var input = Path.Combine(dir, "lumen-input.conf");
            File.WriteAllText(input, InputConf);
            _ = video.Handle; // mpv draws into this panel
            mpv = new Mpv(video.Handle, dir, input, $"LumenWindows/{Version}");
            mpv.Set("volume", N(cfg.Volume));
            mpv.ClientMessage += a => BeginInvoke(() => OnMpvMessage(a));
            mpv.EndFile += (reason, err) => BeginInvoke(() => OnEndFile(reason, err));
            mpv.FileLoaded += () => BeginInvoke(OnFileLoaded);
            return true;
        }
        catch (Exception ex)
        {
            mpv = null;
            MessageBox.Show(this, "The video player (libmpv-2.dll) could not start.\n\n" + ex.Message, "Lumen", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return false;
        }
    }

    // Keys: mpv's own defaults (space, arrows ←/→ seek, m mute, 9/0 volume, j subtitles, # audio track, i stats…)
    // plus Lumen's: Esc / Backspace back, F / F11 / double-click full screen, S skip intro/credits, ↑↓ / PgUp PgDn channels.
    const string InputConf = """
        ESC script-message lumen-close
        BS script-message lumen-close
        q script-message lumen-close
        f script-message lumen-fs
        F11 script-message lumen-fs
        MBTN_LEFT_DBL script-message lumen-fs
        s script-message lumen-skip
        n script-message lumen-next
        UP script-message lumen-up
        DOWN script-message lumen-down
        PGUP script-message lumen-ch -1
        PGDWN script-message lumen-ch 1
        WHEEL_UP add volume 5
        WHEEL_DOWN add volume -5
        """;

    void OnFileLoaded()
    {
        if (cur == null || mpv == null) return;
        if (cur.SubtitleUrl != null) mpv.Command("sub-add", cur.SubtitleUrl, "select");
        if (cur.Live) mpv.Command("show-text", (cur.Channel ?? cur.Title) + (cur.Subtitle.Length > 0 ? "\n" + cur.Subtitle : ""), "4000");
        FocusVideo();
    }

    void OnEndFile(int reason, string err)
    {
        if (cur == null) return;
        if (reason == Mpv.EndError)
        {
            ReportError(err);
            return;
        }
        if (reason != Mpv.EndEof) return; // replaced by the next channel / episode, or stopped by us
        if (cur.Live) { ReportError("the stream ended"); return; }
        Js("window.lumenNative && lumenNative.ended()");
        // Lumen starts the next episode if it can; otherwise leave the player.
        var seq = loadSeq;
        endWait = 1;
        var t = new System.Windows.Forms.Timer { Interval = cur.HasNext ? 4000 : 300 };
        t.Tick += (_, _) => { t.Dispose(); if (seq == loadSeq && endWait == 1) ClosePlayer(); };
        t.Start();
    }

    /// <summary>Lumen retries with a more compatible stream; if it has nothing left to try, leave the player.</summary>
    void ReportError(string err)
    {
        Js($"window.lumenNative && lumenNative.error({JsonSerializer.Serialize(err)})");
        mpv?.Command("show-text", "Playback problem — trying another way…", "3000");
        var seq = loadSeq;
        var t = new System.Windows.Forms.Timer { Interval = 6000 };
        t.Tick += (_, _) => { t.Dispose(); if (seq == loadSeq && cur != null) ClosePlayer(); };
        t.Start();
    }

    void OnMpvMessage(string[] a)
    {
        if (a.Length == 0 || cur == null || mpv == null) return;
        switch (a[0])
        {
            case "lumen-close": ClosePlayer(); break;
            case "lumen-fs": SetFullscreen(!fullscreen); fsByPlayer = fullscreen; break;
            case "lumen-skip": Skip(); break;
            case "lumen-next": if (cur.HasNext) Js("window.lumenNative && lumenNative.ended()"); break;
            case "lumen-up": if (cur.CanZap) Zap(-1); else mpv.Command("add", "volume", "5"); break;
            case "lumen-down": if (cur.CanZap) Zap(1); else mpv.Command("add", "volume", "-5"); break;
            case "lumen-ch": if (cur.CanZap && a.Length > 1) Zap(a[1] == "-1" ? -1 : 1); break;
        }
    }

    void Zap(int dir)
    {
        mpv?.Command("show-text", dir < 0 ? "Channel up…" : "Channel down…", "1500");
        Js($"window.lumenNative && lumenNative.zap({dir})");
    }

    double Position() => (mpv?.GetDouble("time-pos") ?? 0) + (cur?.Offset ?? 0);

    (string Type, double Start, double End)? CurrentMarker(double pos) =>
        cur?.Markers.Where(m => (m.Type == "intro" || m.Type == "credits") && pos >= m.Start && pos < m.End - 1)
            .Select(m => ((string, double, double)?)m).FirstOrDefault();

    void Skip()
    {
        if (cur == null || mpv == null) return;
        var m = CurrentMarker(Position());
        if (m == null) return;
        if (m.Value.Type == "credits" && cur.HasNext) { Js("window.lumenNative && lumenNative.ended()"); return; }
        mpv.Command("seek", N(m.Value.End - cur.Offset), "absolute");
    }

    void OnTick()
    {
        if (cur == null || mpv == null) return;
        var now = Environment.TickCount64;
        // Back button: shows while the mouse moves over the video.
        var mouse = mpv.Get("mouse-pos");
        if (mouse != lastMouse) { lastMouse = mouse; back.Visible = true; backHideAt = now + 2500; }
        else if (back.Visible && now > backHideAt) back.Visible = false;

        var pos = Position();
        var paused = mpv.Get("pause");
        // "Skip intro" / "Skip credits" prompt (press S).
        var m = CurrentMarker(pos);
        if (m != null && skipShown.Add($"{m.Value.Type}{m.Value.Start}"))
            mpv.Command("show-text", m.Value.Type == "credits" && cur.HasNext ? "Press S for the next episode" : $"Press S to skip the {m.Value.Type}", "6000");
        // Resume points for Lumen / Plex, every 10 s and on pause / play.
        if (!cur.Live && (now - lastReport > 10_000 || paused != lastPause))
        {
            lastReport = now; lastPause = paused;
            var dur = cur.Duration > 0 ? cur.Duration : (mpv.GetDouble("duration") ?? 0) + cur.Offset;
            if (pos > 0.5) Js($"window.lumenNative && lumenNative.progress({N(pos)}, {N(dur)}, '{(paused == "yes" ? "paused" : "playing")}')");
        }
    }

    void ClosePlayer(bool report = true)
    {
        if (cur == null) { ShowVideo(false); return; }
        var p = cur; cur = null; loadSeq++;
        var pos = Position();
        if (mpv != null)
        {
            if (double.TryParse(mpv.Get("volume"), NumberStyles.Float, Inv, out var v)) cfg.Volume = v;
            mpv.Command("stop");
        }
        // Nothing playing: mpv goes away completely (no GPU or memory use in the background).
        var m = mpv; mpv = null;
        m?.Dispose();
        ShowVideo(false);
        if (report) Js($"window.lumenNative && lumenNative.closed({N(p.Live ? 0 : pos)})");
    }

    void ShowVideo(bool on)
    {
        tick.Enabled = on;
        if (on)
        {
            video.Visible = true; video.BringToFront(); back.BringToFront();
            web.Visible = false;
            if (cfg.PlayFullscreen && !fullscreen) { SetFullscreen(true); fsByPlayer = true; }
            FocusVideo();
        }
        else
        {
            back.Visible = false;
            web.Visible = true; web.BringToFront();
            video.Visible = false;
            if (fsByPlayer && fullscreen) SetFullscreen(false);
            fsByPlayer = false;
            web.Focus();
        }
        UpdateWebActivity();
    }

    void FocusVideo()
    {
        video.Focus();
        var child = GetWindow(video.Handle, 5 /* GW_CHILD */);
        if (child != IntPtr.Zero) SetFocus(child);
    }

    /// <summary>The web app sleeps while a video plays or the window is minimized (less CPU, GPU and memory).</summary>
    async void UpdateWebActivity()
    {
        var core = web.CoreWebView2;
        if (core == null) return;
        var minimized = WindowState == FormWindowState.Minimized;
        if (cur == null && web.Visible == minimized) web.Visible = !minimized;
        var idle = cur != null || minimized;
        try
        {
            core.MemoryUsageTargetLevel = idle ? CoreWebView2MemoryUsageTargetLevel.Low : CoreWebView2MemoryUsageTargetLevel.Normal;
            if (idle && !web.Visible) { if (!core.IsSuspended) await core.TrySuspendAsync(); }
            else if (core.IsSuspended) core.Resume();
        }
        catch { }
    }

    void SetFullscreen(bool on)
    {
        if (on == fullscreen) return;
        fullscreen = on;
        SuspendLayout();
        if (on)
        {
            wasState = WindowState;
            WindowState = FormWindowState.Normal;
            FormBorderStyle = FormBorderStyle.None;
            WindowState = FormWindowState.Maximized;
        }
        else
        {
            FormBorderStyle = FormBorderStyle.Sizable;
            WindowState = wasState;
        }
        ResumeLayout();
    }

    /// <summary>Keys that reach the window instead of mpv (e.g. before the video has focus) are passed on to mpv.</summary>
    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        if (cur != null && mpv != null)
        {
            var key = (keyData & Keys.KeyCode) switch
            {
                Keys.Escape => "ESC", Keys.Back => "BS", Keys.Space => "SPACE", Keys.Left => "LEFT", Keys.Right => "RIGHT",
                Keys.Up => "UP", Keys.Down => "DOWN", Keys.PageUp => "PGUP", Keys.PageDown => "PGDWN", Keys.F11 => "F11",
                Keys.Enter => "ENTER", Keys.Home => "HOME", Keys.End => "END",
                >= Keys.A and <= Keys.Z => ((char)('a' + (int)((keyData & Keys.KeyCode) - Keys.A))).ToString(),
                >= Keys.D0 and <= Keys.D9 => ((char)('0' + (int)((keyData & Keys.KeyCode) - Keys.D0))).ToString(),
                _ => null,
            };
            if (key != null)
            {
                if ((keyData & Keys.Shift) != 0 && key.Length == 1 && char.IsLetter(key[0])) key = key.ToUpperInvariant();
                mpv.Command("keypress", key);
                return true;
            }
        }
        if (keyData == Keys.F11 && cur == null) { SetFullscreen(!fullscreen); return true; }
        return base.ProcessCmdKey(ref msg, keyData);
    }

    void Shutdown()
    {
        if (cur != null) ClosePlayer();
        mpv?.Dispose(); mpv = null;
        if (!fullscreen)
        {
            cfg.Maximized = WindowState == FormWindowState.Maximized;
            var r = WindowState == FormWindowState.Normal ? Bounds : RestoreBounds;
            cfg.Bounds = new[] { r.X, r.Y, r.Width, r.Height };
        }
        cfg.Save();
    }
}
