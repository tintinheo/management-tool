using Microsoft.Win32;

namespace ExplorerDuo;

/// <summary>Per-user (HKCU, no admin) "Open in ExplorerDuo" entries in File Explorer's right-click menu.</summary>
internal static class Integration
{
    const string Classes = @"Software\Classes\";

    static readonly string[] Keys =
    {
        @"Directory\shell\ExplorerDuo",
        @"Drive\shell\ExplorerDuo",
        @"Directory\Background\shell\ExplorerDuo",
    };

    public static bool IsContextMenuRegistered()
    {
        using var key = Registry.CurrentUser.OpenSubKey(Classes + Keys[0]);
        return key != null;
    }

    public static void SetContextMenu(bool enabled, string exePath)
    {
        foreach (var k in Keys)
        {
            Registry.CurrentUser.DeleteSubKeyTree(Classes + k, throwOnMissingSubKey: false);
            if (!enabled) continue;

            using var key = Registry.CurrentUser.CreateSubKey(Classes + k);
            key.SetValue("MUIVerb", "Open in ExplorerDuo");
            key.SetValue("Icon", "explorer.exe");
            // Several selected folders → one launch per folder; the running window turns each into a tab.
            key.SetValue("MultiSelectModel", "Player");
            using var command = key.CreateSubKey("command");
            command.SetValue("", $"\"{exePath}\" \"%V\"");
        }
        Native.SHChangeNotify(0x08000000 /* SHCNE_ASSOCCHANGED */, 0, IntPtr.Zero, IntPtr.Zero);
    }
}
