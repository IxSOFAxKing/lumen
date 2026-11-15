using System.Diagnostics;
using System.Globalization;

namespace Lumen;

internal static class Program
{
    /// <summary>The user's own regional settings (times on the overlay); everything else uses the invariant culture.</summary>
    public static CultureInfo UserCulture { get; private set; } = CultureInfo.InvariantCulture;

    [STAThread]
    static void Main()
    {
        UserCulture = CultureInfo.CurrentCulture;
        CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
        // Games first: Lumen runs a step below normal priority (video decoding happens on the GPU's video engine anyway).
        try { Process.GetCurrentProcess().PriorityClass = ProcessPriorityClass.BelowNormal; } catch { }
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
