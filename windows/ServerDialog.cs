using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;

namespace Lumen;

/// <summary>First run (or "Change server"): type the Lumen address or let the app look for it on the network.</summary>
public sealed class ServerDialog : Form
{
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(4) };

    readonly TextBox url = new() { Width = 360, Font = new Font("Segoe UI", 11f) };
    readonly Label status = new() { AutoSize = true, ForeColor = Color.FromArgb(160, 165, 175), MaximumSize = new Size(380, 0) };
    readonly Button connect = new() { Text = "Connect", AutoSize = true };
    readonly Button search = new() { Text = "Search my network", AutoSize = true };
    public string Server { get; private set; } = "";

    public ServerDialog(string current)
    {
        Text = "Connect to Lumen";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoSize = true; AutoSizeMode = AutoSizeMode.GrowAndShrink;
        BackColor = Color.FromArgb(17, 19, 24); ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        url.Text = string.IsNullOrEmpty(current) ? "http://" : current;
        foreach (var b in new[] { connect, search })
        {
            b.FlatStyle = FlatStyle.Flat; b.BackColor = Color.FromArgb(40, 44, 54); b.Padding = new Padding(8, 2, 8, 2);
            b.FlatAppearance.BorderColor = Color.FromArgb(70, 75, 90);
        }
        connect.BackColor = Color.FromArgb(240, 150, 50); connect.ForeColor = Color.Black;
        var flow = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Padding = new Padding(18), WrapContents = false };
        flow.Controls.Add(new Label { Text = "Lumen server address", AutoSize = true, Font = new Font("Segoe UI Semibold", 12f) });
        flow.Controls.Add(new Label { Text = "For example http://192.168.1.20:8080", AutoSize = true, ForeColor = Color.FromArgb(160, 165, 175) });
        flow.Controls.Add(url);
        var row = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 8, 0, 4) };
        row.Controls.Add(connect); row.Controls.Add(search);
        flow.Controls.Add(row);
        flow.Controls.Add(status);
        Controls.Add(flow);
        AcceptButton = connect;
        connect.Click += async (_, _) => await TryConnect(url.Text);
        search.Click += async (_, _) => await Search();
        Shown += async (_, _) => { if (string.IsNullOrEmpty(current)) await Search(); };
    }

    static string Normalize(string s)
    {
        s = s.Trim().TrimEnd('/');
        if (!s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !s.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) s = "http://" + s;
        if (Uri.TryCreate(s, UriKind.Absolute, out var u) && u.IsDefaultPort && s.Count(c => c == ':') < 2 && u.Scheme == "http") s += ":8080";
        return s;
    }

    static async Task<bool> IsLumen(string baseUrl, CancellationToken ct)
    {
        try
        {
            using var r = await Http.GetAsync(baseUrl + "/api/health", ct);
            if (!r.IsSuccessStatusCode) return false;
            using var doc = JsonDocument.Parse(await r.Content.ReadAsStringAsync(ct));
            return doc.RootElement.TryGetProperty("app", out var a) && a.GetString() == "lumen";
        }
        catch { return false; }
    }

    async Task TryConnect(string text)
    {
        var s = Normalize(text);
        status.Text = "Connecting…"; connect.Enabled = false;
        var ok = await IsLumen(s, CancellationToken.None);
        connect.Enabled = true;
        if (!ok) { status.Text = $"No Lumen server answered at {s}."; return; }
        Server = s; DialogResult = DialogResult.OK; Close();
    }

    /// <summary>Tries lumen:8080, then every address on this PC's local networks (port 8080).</summary>
    async Task Search()
    {
        search.Enabled = false; status.Text = "Looking for Lumen on your network…";
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var candidates = new List<string> { "http://lumen:8080", "http://localhost:8080" };
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var a in ni.GetIPProperties().UnicastAddresses)
            {
                if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.PrefixLength < 16) continue;
                var b = a.Address.GetAddressBytes();
                if (b[0] == 169 && b[1] == 254) continue;
                for (int i = 1; i < 255; i++) candidates.Add($"http://{b[0]}.{b[1]}.{b[2]}.{i}:8080");
            }
        }
        string? found = null;
        using var gate = new SemaphoreSlim(48);
        var quick = new HttpClient { Timeout = TimeSpan.FromMilliseconds(900) };
        var tasks = candidates.Distinct().Select(async c =>
        {
            await gate.WaitAsync(cts.Token).ConfigureAwait(false);
            try
            {
                if (found != null) return;
                using var r = await quick.GetAsync(c + "/api/health", cts.Token).ConfigureAwait(false);
                if (!r.IsSuccessStatusCode) return;
                using var doc = JsonDocument.Parse(await r.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false));
                if (doc.RootElement.TryGetProperty("app", out var a) && a.GetString() == "lumen") { found ??= c; cts.Cancel(); }
            }
            catch { }
            finally { gate.Release(); }
        });
        try { await Task.WhenAll(tasks); } catch { }
        search.Enabled = true;
        if (found != null) { url.Text = found; status.Text = $"Found Lumen at {found}"; }
        else status.Text = "Lumen wasn't found automatically. Type its address (the same one you use in your browser).";
    }
}
