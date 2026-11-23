using System.Runtime.InteropServices;
using System.Text;

namespace ExplorerDuo;

[StructLayout(LayoutKind.Sequential)]
internal struct CMINVOKECOMMANDINFOEX
{
    public int cbSize;
    public uint fMask;
    public IntPtr hwnd;
    public IntPtr lpVerb;
    public IntPtr lpParameters;
    public IntPtr lpDirectory;
    public int nShow;
    public uint dwHotKey;
    public IntPtr hIcon;
    public IntPtr lpTitle;
    public IntPtr lpVerbW;
    public IntPtr lpParametersW;
    public IntPtr lpDirectoryW;
    public IntPtr lpTitleW;
    public POINT ptInvoke;
}

[ComImport, Guid("000214E4-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IContextMenu
{
    [PreserveSig] int QueryContextMenu(IntPtr hmenu, uint indexMenu, uint idCmdFirst, uint idCmdLast, uint flags);
    [PreserveSig] int InvokeCommand(ref CMINVOKECOMMANDINFOEX info);
    [PreserveSig] int GetCommandString(UIntPtr idCmd, uint type, IntPtr reserved, IntPtr name, uint cchMax);
}

[ComImport, Guid("000214F4-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IContextMenu2
{
    [PreserveSig] int QueryContextMenu(IntPtr hmenu, uint indexMenu, uint idCmdFirst, uint idCmdLast, uint flags);
    [PreserveSig] int InvokeCommand(ref CMINVOKECOMMANDINFOEX info);
    [PreserveSig] int GetCommandString(UIntPtr idCmd, uint type, IntPtr reserved, IntPtr name, uint cchMax);
    [PreserveSig] int HandleMenuMsg(uint msg, IntPtr wParam, IntPtr lParam);
}

[ComImport, Guid("BCFCE0A0-EC17-11D0-8D10-00A0C90F2719"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IContextMenu3
{
    [PreserveSig] int QueryContextMenu(IntPtr hmenu, uint indexMenu, uint idCmdFirst, uint idCmdLast, uint flags);
    [PreserveSig] int InvokeCommand(ref CMINVOKECOMMANDINFOEX info);
    [PreserveSig] int GetCommandString(UIntPtr idCmd, uint type, IntPtr reserved, IntPtr name, uint cchMax);
    [PreserveSig] int HandleMenuMsg(uint msg, IntPtr wParam, IntPtr lParam);
    [PreserveSig] int HandleMenuMsg2(uint msg, IntPtr wParam, IntPtr lParam, out IntPtr result);
}

[ComImport, Guid("FC4801A3-2BA9-11CF-A229-00AA003D7352"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IObjectWithSite
{
    [PreserveSig] int SetSite([MarshalAs(UnmanagedType.IUnknown)] object? site);
    [PreserveSig] int GetSite(ref Guid riid, out IntPtr ppv);
}

/// <summary>An ExplorerDuo command placed in a right-click menu (above the Windows items).</summary>
internal sealed class MenuCommand
{
    public MenuCommand(string text, Action? run, string? shortcut = null, bool enabled = true)
    {
        Text = shortcut == null ? text : text + "\t" + shortcut;
        Run = run;
        Enabled = enabled;
    }

    public string Text { get; }
    public Action? Run { get; }
    public bool Enabled { get; }
    public List<MenuCommand>? Children { get; init; }
    public bool Checked { get; init; }
    public bool IsSeparator => Text == "-";

    public static MenuCommand Separator => new("-", null);
}

/// <summary>
/// Shows the complete Windows right-click menu (every shell extension, Send to, Open with, New, …) for a set of
/// items or a folder background, with ExplorerDuo's own commands on top.
/// </summary>
internal static class ShellMenu
{
    public const uint SVGIO_BACKGROUND = 0x0;
    public const uint SVGIO_SELECTION = 0x1;

    const uint CMF_NORMAL = 0x0, CMF_EXPLORE = 0x4, CMF_CANRENAME = 0x10, CMF_EXTENDEDVERBS = 0x100, CMF_ITEMMENU = 0x80;
    const uint CMIC_MASK_UNICODE = 0x4000, CMIC_MASK_PTINVOKE = 0x20000000, CMIC_MASK_SHIFT_DOWN = 0x10000000, CMIC_MASK_CONTROL_DOWN = 0x40000000;
    const uint GCS_VERBW = 0x4;
    const uint TPM_RETURNCMD = 0x100, TPM_RIGHTBUTTON = 0x2;
    const uint MF_STRING = 0x0, MF_GRAYED = 0x1, MF_CHECKED = 0x8, MF_POPUP = 0x10, MF_SEPARATOR = 0x800, MF_BYPOSITION = 0x400;
    const uint FirstShellId = 1, LastShellId = 0x6FFF, FirstOwnId = 0x7000;
    static readonly Guid BhidSfUiObject = new("3981E225-F559-11D3-8E3A-00C04F6837D5");

    [DllImport("user32.dll")] static extern IntPtr CreatePopupMenu();
    [DllImport("user32.dll")] static extern bool DestroyMenu(IntPtr menu);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool InsertMenu(IntPtr menu, uint position, uint flags, UIntPtr id, string? text);
    [DllImport("user32.dll")] static extern bool DeleteMenu(IntPtr menu, uint position, uint flags);
    [DllImport("user32.dll")] static extern int GetMenuItemCount(IntPtr menu);
    [DllImport("user32.dll")] static extern uint GetMenuState(IntPtr menu, uint id, uint flags);
    [DllImport("user32.dll")] static extern int TrackPopupMenuEx(IntPtr menu, uint flags, int x, int y, IntPtr owner, IntPtr tpm);
    [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);

    /// <summary>The Windows menu for one shell item (tree items). Caller releases.</summary>
    public static IContextMenu? ForItem(IShellItem item)
    {
        try
        {
            var bhid = BhidSfUiObject;
            var iid = typeof(IContextMenu).GUID;
            item.BindToHandler(IntPtr.Zero, ref bhid, ref iid, out var ppv);
            return Wrap(ppv);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>The Windows menu the view itself would show for its selection or background. Caller releases.</summary>
    public static IContextMenu? ForView(IShellView view, uint what)
    {
        var iid = typeof(IContextMenu).GUID;
        return view.GetItemObject(what, ref iid, out var ppv) == 0 ? Wrap(ppv) : null;
    }

    static IContextMenu? Wrap(IntPtr ppv)
    {
        if (ppv == IntPtr.Zero) return null;
        try { return Marshal.GetObjectForIUnknown(ppv) as IContextMenu; }
        finally { Marshal.Release(ppv); }
    }

    /// <summary>Every text in the menu (tests/diagnostics) without showing it.</summary>
    public static List<string> Texts(IContextMenu? cm, IReadOnlyList<MenuCommand> own, bool extended = false)
    {
        var menu = Build(cm, own, extended, out _);
        try { return MenuTexts(menu); }
        finally { DestroyMenu(menu); }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetMenuString(IntPtr menu, uint id, StringBuilder text, int max, uint flags);

    static List<string> MenuTexts(IntPtr menu)
    {
        var list = new List<string>();
        int n = GetMenuItemCount(menu);
        for (uint i = 0; i < n; i++)
        {
            var sb = new StringBuilder(256);
            GetMenuString(menu, i, sb, sb.Capacity, MF_BYPOSITION);
            list.Add(sb.Length == 0 ? "-" : sb.ToString());
        }
        return list;
    }

    /// <summary>
    /// Shows the menu at <paramref name="screen"/> and runs the chosen command. <paramref name="verbOverride"/> gets the
    /// canonical verb ("open", "rename", …) first and can handle it itself by returning true.
    /// </summary>
    public static void Show(IntPtr owner, IContextMenu? cm, Point screen, IReadOnlyList<MenuCommand> own,
        string? directory = null, Func<string, bool>? verbOverride = null, object? site = null)
    {
        bool shift = (Control.ModifierKeys & Keys.Shift) != 0;
        if (site != null && cm is IObjectWithSite withSite) withSite.SetSite(site);
        var menu = Build(cm, own, shift, out var actions);
        using var host = new MenuHost(cm, GetAncestor(owner, 2 /* GA_ROOT */));
        int id;
        try
        {
            id = TrackPopupMenuEx(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, screen.X, screen.Y, host.Handle, IntPtr.Zero);
        }
        finally
        {
            DestroyMenu(menu);
        }

        try
        {
            if (id <= 0) return;
            if (id >= FirstOwnId)
            {
                if (actions.TryGetValue(id, out var run)) run();
                return;
            }
            if (cm == null) return;
            uint offset = (uint)id - FirstShellId;
            var verb = VerbOf(cm, offset);
            if (verb != null && verbOverride?.Invoke(verb) == true) return;
            Invoke(cm, offset, owner, screen, directory);
        }
        catch (Exception ex)
        {
            Log.Write("context menu command failed: " + ex);
        }
        finally
        {
            if (site != null && cm is IObjectWithSite ws) ws.SetSite(null);
        }
    }

    static IntPtr Build(IContextMenu? cm, IReadOnlyList<MenuCommand> own, bool extended, out Dictionary<int, Action> actions)
    {
        var menu = CreatePopupMenu();
        actions = new Dictionary<int, Action>();
        uint nextId = FirstOwnId;
        AddOwn(menu, own, actions, ref nextId);
        if (cm != null)
        {
            if (own.Count > 0) InsertMenu(menu, uint.MaxValue, MF_BYPOSITION | MF_SEPARATOR, UIntPtr.Zero, null);
            uint flags = CMF_NORMAL | CMF_EXPLORE | CMF_CANRENAME | CMF_ITEMMENU | (extended ? CMF_EXTENDEDVERBS : 0);
            cm.QueryContextMenu(menu, (uint)GetMenuItemCount(menu), FirstShellId, LastShellId, flags);
        }
        TidySeparators(menu);
        return menu;
    }

    static void AddOwn(IntPtr menu, IReadOnlyList<MenuCommand> items, Dictionary<int, Action> actions, ref uint nextId)
    {
        foreach (var item in items)
        {
            if (item.IsSeparator)
            {
                InsertMenu(menu, uint.MaxValue, MF_BYPOSITION | MF_SEPARATOR, UIntPtr.Zero, null);
            }
            else if (item.Children is { Count: > 0 } children)
            {
                var sub = CreatePopupMenu();
                AddOwn(sub, children, actions, ref nextId);
                InsertMenu(menu, uint.MaxValue, MF_BYPOSITION | MF_POPUP | (item.Enabled ? 0 : MF_GRAYED), (UIntPtr)(ulong)sub, item.Text);
            }
            else
            {
                uint id = nextId++;
                if (item.Run != null) actions[(int)id] = item.Run;
                InsertMenu(menu, uint.MaxValue, MF_BYPOSITION | MF_STRING | (item.Enabled && item.Run != null ? 0 : MF_GRAYED) | (item.Checked ? MF_CHECKED : 0), (UIntPtr)id, item.Text);
            }
        }
    }

    /// <summary>No leading, trailing or doubled separators (shell extensions add their own).</summary>
    static void TidySeparators(IntPtr menu)
    {
        bool lastWasSeparator = true;
        for (int i = 0; i < GetMenuItemCount(menu); i++)
        {
            bool sep = IsSeparator(menu, i);
            if (sep && lastWasSeparator) { DeleteMenu(menu, (uint)i, MF_BYPOSITION); i--; continue; }
            lastWasSeparator = sep;
        }
        int n = GetMenuItemCount(menu);
        if (n > 0 && IsSeparator(menu, n - 1)) DeleteMenu(menu, (uint)(n - 1), MF_BYPOSITION);
    }

    [DllImport("user32.dll")] static extern IntPtr GetSubMenu(IntPtr menu, int position);

    // For sub-menus GetMenuState puts the item count in the high byte, which overlaps MF_SEPARATOR.
    static bool IsSeparator(IntPtr menu, int position) =>
        GetSubMenu(menu, position) == IntPtr.Zero && (GetMenuState(menu, (uint)position, MF_BYPOSITION) & MF_SEPARATOR) != 0;

    static string? VerbOf(IContextMenu cm, uint offset)
    {
        var buffer = Marshal.AllocCoTaskMem(512);
        try
        {
            Marshal.WriteInt16(buffer, 0);
            return cm.GetCommandString((UIntPtr)offset, GCS_VERBW, IntPtr.Zero, buffer, 256) == 0 ? Marshal.PtrToStringUni(buffer) : null;
        }
        catch
        {
            return null;
        }
        finally
        {
            Marshal.FreeCoTaskMem(buffer);
        }
    }

    public static void Invoke(IContextMenu cm, uint offset, IntPtr owner, Point screen, string? directory)
    {
        var dirA = directory == null ? IntPtr.Zero : Marshal.StringToHGlobalAnsi(directory);
        var dirW = directory == null ? IntPtr.Zero : Marshal.StringToHGlobalUni(directory);
        try
        {
            var info = new CMINVOKECOMMANDINFOEX
            {
                cbSize = Marshal.SizeOf<CMINVOKECOMMANDINFOEX>(),
                fMask = CMIC_MASK_UNICODE | CMIC_MASK_PTINVOKE
                        | ((Control.ModifierKeys & Keys.Control) != 0 ? CMIC_MASK_CONTROL_DOWN : 0)
                        | ((Control.ModifierKeys & Keys.Shift) != 0 ? CMIC_MASK_SHIFT_DOWN : 0),
                hwnd = owner,
                lpVerb = (IntPtr)offset,
                lpVerbW = (IntPtr)offset,
                lpDirectory = dirA,
                lpDirectoryW = dirW,
                nShow = 1, // SW_SHOWNORMAL
                ptInvoke = new POINT(screen.X, screen.Y),
            };
            int hr = cm.InvokeCommand(ref info);
            if (hr < 0 && hr != unchecked((int)0x800704C7) /* cancelled */) Log.Write($"InvokeCommand {offset} → 0x{hr:X8}");
        }
        finally
        {
            if (dirA != IntPtr.Zero) Marshal.FreeHGlobal(dirA);
            if (dirW != IntPtr.Zero) Marshal.FreeHGlobal(dirW);
        }
    }

    /// <summary>Invisible owner of the popup menu: forwards owner-drawn/submenu messages (Send to, Open with, New) to the shell.</summary>
    sealed class MenuHost : NativeWindow, IDisposable
    {
        const int WM_INITMENUPOPUP = 0x0117, WM_DRAWITEM = 0x002B, WM_MEASUREITEM = 0x002C, WM_MENUCHAR = 0x0120;
        readonly IContextMenu2? _cm2;
        readonly IContextMenu3? _cm3;

        public MenuHost(IContextMenu? cm, IntPtr parent)
        {
            _cm2 = cm as IContextMenu2;
            _cm3 = cm as IContextMenu3;
            CreateHandle(new CreateParams { Parent = parent, Style = unchecked((int)0x80000000) /* WS_POPUP */, Caption = "ExplorerDuo menu" });
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg is WM_INITMENUPOPUP or WM_DRAWITEM or WM_MEASUREITEM or WM_MENUCHAR)
            {
                try
                {
                    if (_cm3 != null && _cm3.HandleMenuMsg2((uint)m.Msg, m.WParam, m.LParam, out var result) == 0)
                    {
                        m.Result = result;
                        return;
                    }
                    if (_cm2 != null && _cm2.HandleMenuMsg((uint)m.Msg, m.WParam, m.LParam) == 0)
                    {
                        m.Result = m.Msg == WM_INITMENUPOPUP ? IntPtr.Zero : (IntPtr)1;
                        return;
                    }
                }
                catch
                {
                    // A misbehaving extension must not take the menu down.
                }
            }
            base.WndProc(ref m);
        }

        public void Dispose() => DestroyHandle();
    }

    public static void Release(object? com)
    {
        if (com != null && Marshal.IsComObject(com))
        {
            try { Marshal.ReleaseComObject(com); } catch { }
        }
    }
}
