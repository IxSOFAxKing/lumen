using System.Diagnostics;
using System.Globalization;

namespace Lumen;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
        // Games first: Lumen runs a step below normal priority (video decoding happens on the GPU's video engine anyway).
        try { Process.GetCurrentProcess().PriorityClass = ProcessPriorityClass.BelowNormal; } catch { }
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
