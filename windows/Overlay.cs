using System.Globalization;
using System.Text;

namespace Lumen;

public sealed record Prog(string Title, long Start, long Stop);
public sealed record Chan(string Name, string Num, string Now);

/// <summary>
/// Live TV overlay drawn by mpv itself (ASS on mpv's OSD), so it sits on top of the video without extra windows:
/// a TiviMate-style info bar (channel, programme, progress, next, clock) and a channel list.
/// Coordinates are in a virtual 1080-pixel-high space; <paramref name="w"/> is the matching width.
/// </summary>
public static class Overlay
{
    const string Accent = "&H3296F0&";      // Lumen orange (ASS colours are BGR)
    const string Font = @"\fnSegoe UI";

    static string Esc(string s) => (s ?? "").Replace("\\", "/").Replace("{", "(").Replace("}", ")").Replace("\n", " ").Replace("\r", "");

    static string Box(double x0, double y0, double x1, double y1, string color, string alpha) =>
        $@"{{\an7\pos(0,0)\bord0\shad0\c{color}\1a&H{alpha}&\p1}}m {F(x0)} {F(y0)} l {F(x1)} {F(y0)} {F(x1)} {F(y1)} {F(x0)} {F(y1)}{{\p0}}";

    static string Text(double x, double y, int align, int size, string s, string color = "&HFFFFFF&", bool bold = false, string alpha = "00") =>
        $@"{{\an{align}\pos({F(x)},{F(y)}){Font}\fs{size}\b{(bold ? 1 : 0)}\bord0\shad1\4a&H80&\c{color}\1a&H{alpha}&}}{Esc(s)}";

    static string F(double v) => v.ToString("0.#", CultureInfo.InvariantCulture);

    public static string Time(long unix) =>
        DateTimeOffset.FromUnixTimeSeconds(unix).ToLocalTime().ToString("t", Program.UserCulture);

    public static string InfoBar(double w, string num, string name, string group, Prog? now, Prog? next, string fallbackSub, bool canZap)
    {
        var sb = new StringBuilder();
        void L(string s) => sb.Append(s).Append('\n');
        const double top = 790;
        L(Box(0, top, w, 1080, "&H000000&", "38"));
        L(Box(0, top, w, top + 3, Accent, "00"));
        var x = 64.0;
        L(Text(x, top + 34, 7, 30, group, "&HB4AFA5&"));
        L(Text(x, top + 70, 7, 54, (num.Length > 0 ? num + "   " : "") + name, bold: true));
        L(Text(w - 64, top + 34, 9, 46, DateTime.Now.ToString("t", Program.UserCulture), bold: true));
        var nowUnix = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (now != null)
        {
            L(Text(x, top + 140, 7, 38, now.Title));
            L(Text(w - 64, top + 146, 9, 30, $"{Time(now.Start)} – {Time(now.Stop)}", "&HB4AFA5&"));
            var frac = now.Stop > now.Start ? Math.Clamp((nowUnix - now.Start) / (double)(now.Stop - now.Start), 0, 1) : 0;
            L(Box(x, top + 196, w - 64, top + 204, "&HFFFFFF&", "C0"));
            L(Box(x, top + 196, x + (w - 128) * frac, top + 204, Accent, "00"));
        }
        else L(Text(x, top + 140, 7, 34, fallbackSub.Length > 0 ? fallbackSub : "No programme information", "&HB4AFA5&"));
        if (next != null) L(Text(x, top + 222, 7, 28, $"Next   {Time(next.Start)}   {next.Title}", "&HB4AFA5&"));
        L(Text(w - 64, top + 224, 9, 24, canZap ? "↑ ↓  channels   ·   Enter  channel list   ·   0–9  number   ·   Esc  back" : "Esc  back", "&H9A958C&"));
        return sb.ToString();
    }

    public const int Rows = 12;
    public const double ListTop = 150, RowH = 72, ListW = 720;

    public static int FirstRow(int sel, int count) => Math.Max(0, Math.Min(sel - Rows / 2, count - Rows));

    public static string ChannelList(string group, IReadOnlyList<Chan> chans, int sel, int playing)
    {
        var sb = new StringBuilder();
        void L(string s) => sb.Append(s).Append('\n');
        L(Box(0, 0, ListW, 1080, "&H000000&", "30"));
        L(Text(48, 52, 7, 24, "CHANNELS", Accent, bold: true));
        L(Text(48, 84, 7, 34, group.Length > 0 ? group : "Channels", bold: true));
        var first = FirstRow(sel, chans.Count);
        for (int r = 0; r < Rows && first + r < chans.Count; r++)
        {
            var i = first + r;
            var c = chans[i];
            var y = ListTop + r * RowH;
            var on = i == sel;
            if (on) L(Box(24, y, ListW - 24, y + RowH - 6, "&HFFFFFF&", "00"));
            var fg = on ? "&H000000&" : "&HFFFFFF&";
            var dim = on ? "&H505050&" : "&HB4AFA5&";
            L(Text(48, y + 10, 7, 28, c.Num, dim));
            L(Text(140, y + 6, 7, 32, (i == playing ? "▶ " : "") + c.Name, fg, bold: true));
            if (c.Now.Length > 0) L(Text(140, y + 40, 7, 22, c.Now, dim));
        }
        L(Text(48, 1040, 1, 22, "↑ ↓ / wheel  move   ·   Enter / click  watch   ·   Esc  close", "&H9A958C&"));
        return sb.ToString();
    }

    public static string NumberEntry(double w, string digits) =>
        Box(w - 300, 40, w - 40, 160, "&H000000&", "40") + "\n" + Text(w - 170, 100, 5, 80, digits + "_", bold: true);
}
