using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace ExplorerDuo;

[StructLayout(LayoutKind.Sequential)]
internal struct PROPERTYKEY
{
    public Guid fmtid;
    public uint pid;

    public PROPERTYKEY(Guid fmtid, uint pid)
    {
        this.fmtid = fmtid;
        this.pid = pid;
    }

    public bool Same(PROPERTYKEY other) => fmtid == other.fmtid && pid == other.pid;
}

[ComImport, Guid("D8EC27BB-3F3B-4042-B10A-4ACFD924D453"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IColumnManager
{
    [PreserveSig] int SetColumnInfo(ref PROPERTYKEY key, IntPtr info);
    [PreserveSig] int GetColumnInfo(ref PROPERTYKEY key, IntPtr info);
    [PreserveSig] int GetColumnCount(uint flags, out uint count);
    [PreserveSig] int GetColumns(uint flags, [Out, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 2)] PROPERTYKEY[] keys, uint count);
    [PreserveSig] int SetColumns([In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 1)] PROPERTYKEY[] keys, uint count);
}

/// <summary>
/// Shows Windows' cloud "Status" column (synced / online-only / always available / syncing) for folders inside
/// OneDrive or any other cloud sync root (Dropbox, Google Drive, iCloud … that register with Windows).
/// </summary>
internal static class CloudStatus
{
    const uint CM_ENUM_VISIBLE = 0x2;

    [DllImport("propsys.dll", CharSet = CharSet.Unicode)]
    static extern int PSGetPropertyKeyFromName(string name, out PROPERTYKEY key);

    [DllImport("propsys.dll")]
    static extern int PSGetNameFromPropertyKey(ref PROPERTYKEY key, out IntPtr name);

    /// <summary>System.StorageProviderUIStatus — the column Explorer calls "Status" in OneDrive.</summary>
    public static readonly PROPERTYKEY StatusKey =
        PSGetPropertyKeyFromName("System.StorageProviderUIStatus", out var k) == 0
            ? k
            : new PROPERTYKEY(new Guid("E77E90DF-6271-4F5B-834F-2DD1F245DDA4"), 2);

    static readonly PROPERTYKEY NameKey = new(new Guid("B725F130-47EF-101A-A5F1-02608C9EEBAC"), 10); // System.ItemNameDisplay

    static string[]? _roots;
    static long _rootsAt;

    /// <summary>Local folders of cloud sync roots (re-read every few minutes; cheap registry reads).</summary>
    public static IReadOnlyList<string> Roots
    {
        get
        {
            if (_roots == null || Environment.TickCount64 - _rootsAt > 300_000)
            {
                _roots = FindRoots();
                _rootsAt = Environment.TickCount64;
            }
            return _roots;
        }
    }

    static string[] FindRoots()
    {
        var roots = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        void Add(string? path)
        {
            if (string.IsNullOrWhiteSpace(path)) return;
            try
            {
                var full = Path.GetFullPath(path.Trim()).TrimEnd('\\');
                if (full.Length > 3 && Directory.Exists(full)) roots.Add(full);
            }
            catch
            {
                // Not a usable path.
            }
        }

        foreach (var v in new[] { "OneDrive", "OneDriveCommercial", "OneDriveConsumer" })
            Add(Environment.GetEnvironmentVariable(v));
        try
        {
            using var accounts = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\OneDrive\Accounts");
            foreach (var name in accounts?.GetSubKeyNames() ?? Array.Empty<string>())
            {
                using var account = accounts!.OpenSubKey(name);
                Add(account?.GetValue("UserFolder") as string);
            }
        }
        catch
        {
            // No OneDrive.
        }
        try
        {
            // Every provider that uses the Windows cloud files API registers its folders here.
            using var manager = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\SyncRootManager");
            foreach (var name in manager?.GetSubKeyNames() ?? Array.Empty<string>())
            {
                using var userRoots = manager!.OpenSubKey(name + @"\UserSyncRoots");
                foreach (var value in userRoots?.GetValueNames() ?? Array.Empty<string>())
                    Add(userRoots!.GetValue(value) as string);
            }
        }
        catch
        {
            // Not readable: keep what we have.
        }
        return roots.ToArray();
    }

    public static bool IsCloudPath(string? path)
    {
        if (!PathUtil.IsFileSystemPath(path)) return false;
        var p = path!.TrimEnd('\\');
        foreach (var root in Roots)
            if (p.Equals(root, StringComparison.OrdinalIgnoreCase) || p.StartsWith(root + "\\", StringComparison.OrdinalIgnoreCase))
                return true;
        return false;
    }

    /// <summary>Visible column keys of a view (diagnostics/tests).</summary>
    public static PROPERTYKEY[] VisibleColumns(object view)
    {
        if (view is not IColumnManager cm || cm.GetColumnCount(CM_ENUM_VISIBLE, out var n) != 0 || n == 0) return Array.Empty<PROPERTYKEY>();
        var keys = new PROPERTYKEY[n];
        return cm.GetColumns(CM_ENUM_VISIBLE, keys, n) == 0 ? keys : Array.Empty<PROPERTYKEY>();
    }

    public static string NameOf(PROPERTYKEY key)
    {
        if (PSGetNameFromPropertyKey(ref key, out var p) != 0) return $"{key.fmtid},{key.pid}";
        try { return Marshal.PtrToStringUni(p) ?? ""; }
        finally { Marshal.FreeCoTaskMem(p); }
    }

    /// <summary>Adds the Status column right after Name, if the view doesn't show it yet. Returns true if it's now visible.</summary>
    public static bool EnsureStatusColumn(object view)
    {
        if (view is not IColumnManager cm) return false;
        var visible = VisibleColumns(view).ToList();
        if (visible.Count == 0) return false;
        if (visible.Any(k => k.Same(StatusKey))) return true;
        int at = visible.FindIndex(k => k.Same(NameKey));
        visible.Insert(at < 0 ? Math.Min(1, visible.Count) : at + 1, StatusKey);
        return cm.SetColumns(visible.ToArray(), (uint)visible.Count) == 0;
    }
}
