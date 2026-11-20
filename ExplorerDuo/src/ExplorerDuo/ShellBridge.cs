using System.Diagnostics;
using System.Runtime.InteropServices;

namespace ExplorerDuo;

[ComImport, Guid("6D5140C1-7436-11CE-8034-00AA006009FA"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IComServiceProvider
{
    [PreserveSig] int QueryService(ref Guid service, ref Guid riid, out IntPtr ppv);
}

[ComImport, Guid("000214E2-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IShellBrowser
{
    // Only the first IOleWindow slot is needed.
    [PreserveSig] int GetWindow(out IntPtr hwnd);
}

/// <summary>One Explorer tab. <see cref="Window"/> is the top-level CabinetWClass, <see cref="TabHandle"/> its ShellTabWindowClass.</summary>
internal sealed record ExplorerTab(IntPtr Window, IntPtr TabHandle, string Path, object Com);

/// <summary>Talks to live File Explorer windows/tabs through the Shell.Application automation model.</summary>
internal sealed class ShellBridge
{
    public const string ThisPc = "shell:::{20D04FE0-3AEA-1069-A2D8-08002B30309D}";
    static readonly Guid SidTopLevelBrowser = new("4C96BE40-915C-11CF-99D3-00AA004AE837");

    readonly dynamic _shell;

    public ShellBridge()
    {
        var type = Type.GetTypeFromProgID("Shell.Application") ?? throw new InvalidOperationException("Shell.Application is unavailable.");
        _shell = Activator.CreateInstance(type)!;
    }

    public List<ExplorerTab> GetTabs(IntPtr window = default)
    {
        var tabs = new List<ExplorerTab>();
        dynamic windows = _shell.Windows();
        int count = windows.Count;
        for (int i = 0; i < count; i++)
        {
            try
            {
                dynamic? w = windows.Item(i);
                if (w == null) continue;
                var hwnd = new IntPtr(Convert.ToInt64(w.HWND));
                if (window != IntPtr.Zero && hwnd != window) continue;
                if (!WindowFrame.IsExplorer(hwnd)) continue;
                object com = w;
                tabs.Add(new ExplorerTab(hwnd, TabHandleOf(com), PathOf(w), com));
            }
            catch
            {
                // A window may be closing while we enumerate.
            }
        }
        return tabs;
    }

    public ExplorerTab? ActiveTab(IntPtr window)
    {
        var tabs = GetTabs(window);
        if (tabs.Count == 0) return null;
        // The first ShellTabWindowClass child of an Explorer window is the active tab.
        var active = Native.FindWindowEx(window, IntPtr.Zero, "ShellTabWindowClass", null);
        return tabs.FirstOrDefault(t => t.TabHandle == active) ?? tabs[0];
    }

    public void Navigate(ExplorerTab tab, string path)
    {
        dynamic com = tab.Com;
        com.Navigate2(ToNavigable(path));
    }

    public List<string> SelectedPaths(ExplorerTab tab)
    {
        var list = new List<string>();
        try
        {
            dynamic items = ((dynamic)tab.Com).Document.SelectedItems();
            int n = items.Count;
            for (int i = 0; i < n; i++)
            {
                string p = items.Item(i).Path;
                if (!string.IsNullOrEmpty(p)) list.Add(p);
            }
        }
        catch
        {
            // View not ready / not a folder view.
        }
        return list;
    }

    /// <summary>Opens a brand-new Explorer window at <paramref name="path"/> and returns its handle (0 on failure).</summary>
    public async Task<IntPtr> OpenWindowAsync(string path, IntPtr host)
    {
        var before = new HashSet<IntPtr>(WindowFrame.ExplorerWindows());
        if (host == IntPtr.Zero) host = before.FirstOrDefault(h => !Native.IsIconic(h));

        // Ctrl+N from an existing window always yields a new window (explorer.exe <path> may become a tab
        // when "open folders in new tab" is on), so prefer it whenever a window exists.
        bool viaCtrlN = false;
        if (host != IntPtr.Zero && await Input.ActivateAsync(host))
        {
            await Input.WaitForModifiersReleasedAsync();
            if (Native.GetForegroundWindow() == host)
            {
                Input.SendChord(Input.VK_CONTROL, 'N');
                viaCtrlN = true;
            }
        }
        if (!viaCtrlN) StartExplorer(path);

        IntPtr hwnd = await Wait.Until(() => WindowFrame.ExplorerWindows().FirstOrDefault(h => !before.Contains(h)), 6000);
        if (hwnd == IntPtr.Zero && viaCtrlN)
        {
            StartExplorer(path);
            hwnd = await Wait.Until(() => WindowFrame.ExplorerWindows().FirstOrDefault(h => !before.Contains(h)), 6000);
        }
        if (hwnd == IntPtr.Zero) return IntPtr.Zero;

        var tab = await Wait.Until(() => ActiveTab(hwnd), 5000);
        if (tab != null && !SamePath(tab.Path, path)) Navigate(tab, path);
        await Task.Delay(250); // let Explorer finish its own placement before we position it
        return hwnd;
    }

    /// <summary>Opens <paramref name="path"/> as a new tab in an existing Explorer window.</summary>
    public async Task<ExplorerTab?> OpenTabAsync(IntPtr window, string path)
    {
        if (!await Input.ActivateAsync(window)) return null;
        await Input.WaitForModifiersReleasedAsync();
        var before = Native.FindWindowEx(window, IntPtr.Zero, "ShellTabWindowClass", null);
        Input.SendChord(Input.VK_CONTROL, 'T');

        var tab = await Wait.Until(() =>
        {
            var active = Native.FindWindowEx(window, IntPtr.Zero, "ShellTabWindowClass", null);
            return active != IntPtr.Zero && active != before
                ? GetTabs(window).FirstOrDefault(t => t.TabHandle == active)
                : null;
        }, 4000);
        if (tab == null) return null;

        Navigate(tab, path);
        await Task.Delay(150);
        return tab;
    }

    public static bool IsFileSystemPath(string path) =>
        !string.IsNullOrEmpty(path) && System.IO.Path.IsPathFullyQualified(path);

    public static bool SamePath(string a, string b) =>
        string.Equals(Normalize(a), Normalize(b), StringComparison.OrdinalIgnoreCase);

    public static string ToNavigable(string path) => path.StartsWith("::", StringComparison.Ordinal) ? "shell:" + path : path;

    static string Normalize(string p)
    {
        p = ToNavigable(p.Trim());
        return p.Length > 3 ? p.TrimEnd('\\') : p;
    }

    static void StartExplorer(string path)
    {
        var p = ToNavigable(path);
        if (p.Length > 3) p = p.TrimEnd('\\');
        Process.Start(new ProcessStartInfo("explorer.exe", p.Contains(' ') ? $"\"{p}\"" : p) { UseShellExecute = false });
    }

    static string PathOf(dynamic w)
    {
        try
        {
            string p = w.Document.Folder.Self.Path;
            if (!string.IsNullOrEmpty(p)) return p;
        }
        catch { }
        try
        {
            string url = w.LocationURL;
            if (Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.IsFile) return uri.LocalPath;
        }
        catch { }
        return "";
    }

    static IntPtr TabHandleOf(object com)
    {
        try
        {
            if (com is not IComServiceProvider sp) return IntPtr.Zero;
            var sid = SidTopLevelBrowser;
            var iid = typeof(IShellBrowser).GUID;
            if (sp.QueryService(ref sid, ref iid, out var ptr) != 0 || ptr == IntPtr.Zero) return IntPtr.Zero;
            try
            {
                var browser = (IShellBrowser)Marshal.GetObjectForIUnknown(ptr);
                return browser.GetWindow(out var hwnd) == 0 ? hwnd : IntPtr.Zero;
            }
            finally
            {
                Marshal.Release(ptr);
            }
        }
        catch
        {
            return IntPtr.Zero;
        }
    }
}
