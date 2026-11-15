using System.Text.Json;

namespace Lumen;

/// <summary>App settings, kept in %APPDATA%\Lumen\config.json. Everything else lives on the Lumen server.</summary>
public sealed class Config
{
    public string Server { get; set; } = "";
    /// <summary>Go full screen when something starts playing.</summary>
    public bool PlayFullscreen { get; set; } = true;
    public double Volume { get; set; } = 100;
    public bool Maximized { get; set; }
    public int[]? Bounds { get; set; }

    public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Lumen");
    static string FilePath => Path.Combine(Dir, "config.json");

    public static Config Load()
    {
        try { return JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath)) ?? new Config(); }
        catch { return new Config(); }
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}
