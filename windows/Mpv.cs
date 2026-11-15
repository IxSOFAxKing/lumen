using System.Globalization;
using System.Runtime.InteropServices;

namespace Lumen;

/// <summary>
/// mpv (libmpv-2.dll next to Lumen.exe) drawing into a window of ours. It plays what browsers can't: HEVC, Dolby / DTS
/// audio, HDR, any container. Created when playback starts and destroyed when it stops, so nothing runs in between.
/// </summary>
public sealed class Mpv : IDisposable
{
    const string Lib = "libmpv-2.dll";
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_create();
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_initialize(IntPtr ctx);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_set_option_string(IntPtr ctx, byte[] name, byte[] value);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_set_property_string(IntPtr ctx, byte[] name, byte[] value);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_get_property_string(IntPtr ctx, byte[] name);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern void mpv_free(IntPtr data);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int mpv_command(IntPtr ctx, IntPtr[] args);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_wait_event(IntPtr ctx, double timeout);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern void mpv_wakeup(IntPtr ctx);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern void mpv_terminate_destroy(IntPtr ctx);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr mpv_error_string(int error);

    [StructLayout(LayoutKind.Sequential)] struct Event { public int Id; public int Error; public ulong Reply; public IntPtr Data; }
    [StructLayout(LayoutKind.Sequential)] struct ClientMessageData { public int NumArgs; public IntPtr Args; }
    [StructLayout(LayoutKind.Sequential)] struct EndFileData { public int Reason; public int Error; }

    const int EventNone = 0, EventShutdown = 1, EventEndFile = 7, EventFileLoaded = 8, EventClientMessage = 16;
    public const int EndEof = 0, EndStop = 2, EndQuit = 3, EndError = 4;

    IntPtr h;
    readonly Thread events;
    volatile bool closing;

    /// <summary>Raised on mpv's event thread.</summary>
    public event Action<string[]>? ClientMessage;
    public event Action<int, string>? EndFile;
    public event Action? FileLoaded;

    static byte[] U(string s) => System.Text.Encoding.UTF8.GetBytes(s + "\0");

    public Mpv(IntPtr window, string configDir, string inputConf, string userAgent)
    {
        h = mpv_create();
        if (h == IntPtr.Zero) throw new InvalidOperationException("mpv could not start");
        Opt("wid", window.ToInt64().ToString(CultureInfo.InvariantCulture));
        // Your own mpv.conf in %APPDATA%\Lumen\mpv is read too (it can override anything below).
        Opt("config-dir", configDir);
        Opt("config", "yes");
        Opt("input-conf", inputConf);
        Opt("input-default-bindings", "yes");
        Opt("input-vo-keyboard", "yes");
        Opt("osc", "yes");
        Opt("idle", "yes");
        Opt("force-window", "yes");
        Opt("keep-open", "no");
        Opt("terminal", "no");
        // Light on the GPU while gaming: decode on the video engine, skip the expensive scaling shaders.
        Opt("profile", "fast");
        Opt("hwdec", "auto-safe");
        Opt("cache", "yes");
        Opt("demuxer-max-bytes", "96MiB");
        Opt("demuxer-max-back-bytes", "32MiB");
        Opt("network-timeout", "30");
        Opt("user-agent", userAgent);
        Opt("screenshot-directory", "~~desktop/");
        Check(mpv_initialize(h), "initialize");
        events = new Thread(Loop) { IsBackground = true, Name = "mpv events" };
        events.Start();
    }

    void Opt(string name, string value) => mpv_set_option_string(h, U(name), U(value));

    static void Check(int r, string what)
    {
        if (r < 0) throw new InvalidOperationException($"mpv {what}: {Marshal.PtrToStringUTF8(mpv_error_string(r))}");
    }

    public int Command(params string[] args)
    {
        if (h == IntPtr.Zero) return -1;
        var ptrs = new IntPtr[args.Length + 1];
        try
        {
            for (int i = 0; i < args.Length; i++) ptrs[i] = Marshal.StringToCoTaskMemUTF8(args[i]);
            return mpv_command(h, ptrs);
        }
        finally { foreach (var p in ptrs) if (p != IntPtr.Zero) Marshal.FreeCoTaskMem(p); }
    }

    public void Set(string name, string value) { if (h != IntPtr.Zero) mpv_set_property_string(h, U(name), U(value)); }

    public string? Get(string name)
    {
        if (h == IntPtr.Zero) return null;
        var p = mpv_get_property_string(h, U(name));
        if (p == IntPtr.Zero) return null;
        try { return Marshal.PtrToStringUTF8(p); } finally { mpv_free(p); }
    }

    public double? GetDouble(string name) =>
        double.TryParse(Get(name), NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;

    void Loop()
    {
        while (true)
        {
            var ev = Marshal.PtrToStructure<Event>(mpv_wait_event(h, -1));
            if (closing || ev.Id == EventShutdown) return;
            try
            {
                switch (ev.Id)
                {
                    case EventClientMessage:
                        var cm = Marshal.PtrToStructure<ClientMessageData>(ev.Data);
                        var args = new string[cm.NumArgs];
                        for (int i = 0; i < cm.NumArgs; i++) args[i] = Marshal.PtrToStringUTF8(Marshal.ReadIntPtr(cm.Args, i * IntPtr.Size)) ?? "";
                        ClientMessage?.Invoke(args);
                        break;
                    case EventEndFile:
                        var ef = Marshal.PtrToStructure<EndFileData>(ev.Data);
                        EndFile?.Invoke(ef.Reason, ef.Error < 0 ? Marshal.PtrToStringUTF8(mpv_error_string(ef.Error)) ?? "error" : "");
                        break;
                    case EventFileLoaded:
                        FileLoaded?.Invoke();
                        break;
                }
            }
            catch { /* a handler failed; keep the loop alive */ }
        }
    }

    public void Dispose()
    {
        if (h == IntPtr.Zero) return;
        closing = true;
        mpv_wakeup(h);
        events.Join(2000);
        mpv_terminate_destroy(h);
        h = IntPtr.Zero;
    }
}
