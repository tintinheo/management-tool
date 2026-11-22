using System.Runtime.InteropServices;

namespace ExplorerDuo;

public sealed record ShellEntry(string ParsingName, string Name, bool IsFolder, bool IsFileSystem, bool IsHidden = false);

/// <summary>
/// One tab = one embedded Windows Explorer view (IExplorerBrowser) living inside <see cref="Host"/>.
/// The native view is created lazily the first time the tab is shown, so restored sessions with many tabs stay cheap.
/// </summary>
internal sealed class BrowserTab : IDisposable
{
    static readonly Guid ClsidExplorerBrowser = new("71F96385-DDD6-48D3-A0C1-AE06E8B055FB");

    const uint EBO_NOBORDER = 0x40;
    const uint FVM_DETAILS = 4;
    const uint FWF_NOCLIENTEDGE = 0x0200;
    const uint FWF_SHOWSELALWAYS = 0x2000;
    const uint SBSP_ABSOLUTE = 0x0000;
    const uint SBSP_PARENT = 0x2000;
    const uint SBSP_NAVIGATEBACK = 0x4000;
    const uint SBSP_NAVIGATEFORWARD = 0x8000;
    const uint SVGIO_SELECTION = 0x1;
    const uint SVGIO_ALLVIEW = 0x2;
    const uint SVUIA_ACTIVATE_FOCUS = 2;
    const uint FVO_VISTALAYOUT = 0x1;
    const uint SVSI_DESELECT = 0x0;
    const uint SVSI_SELECT = 0x1;
    const int LVM_HITTEST = 0x1012;

    /// <summary>Use the classic SysListView32 layout (colour highlighting + middle-click). Applies to views created afterwards.</summary>
    public static bool ClassicList { get; set; } = true;

    /// <summary>Add the cloud "Status" column automatically in OneDrive and other cloud-synced folders.</summary>
    public static bool ShowCloudStatus { get; set; } = true;

    /// <summary>How many list rows have been painted with a highlight colour (diagnostics/tests).</summary>
    public static int ColoredItemPaints { get; private set; }
    IExplorerBrowser? _browser;
    object? _view; // the current shell view (IShellView / IFolderView2), cached per navigation
    DefViewHook? _hook;
    BrowserEvents? _events;
    uint _cookie;
    string _pending;

    public Panel Host { get; } = new() { Dock = DockStyle.Fill, Visible = false, BackColor = Theme.Surface };
    public string Path { get; private set; }
    public string Title { get; private set; }
    public bool IsCreated => _browser != null;
    /// <summary>The classic list control inside the current view (0 when using the modern layout).</summary>
    public IntPtr ListHandle { get; private set; }

    public event Action<BrowserTab>? Navigated;
    public event Action<BrowserTab>? NavigationFailed;

    public BrowserTab(string path)
    {
        _pending = Path = string.IsNullOrWhiteSpace(path) ? PathUtil.ThisPc : path;
        Title = PathUtil.ShortTitle(Path);
        Host.Resize += (_, _) => ResizeView();
    }

    public void EnsureCreated()
    {
        if (_browser != null) return;

        var browser = (IExplorerBrowser)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidExplorerBrowser)!)!;
        browser.SetOptions(EBO_NOBORDER);
        if (ClassicList && browser is IFolderViewOptions options)
            options.SetFolderViewOptions(FVO_VISTALAYOUT, FVO_VISTALAYOUT);
        var rc = new RECT(Host.ClientRectangle);
        var fs = new FOLDERSETTINGS { ViewMode = FVM_DETAILS, Flags = FWF_SHOWSELALWAYS | FWF_NOCLIENTEDGE };
        browser.Initialize(Host.Handle, ref rc, ref fs);

        _events = new BrowserEvents(OnNavigated, () => NavigationFailed?.Invoke(this), _ => { _view = null; ClearColorCache(); });
        var sink = Marshal.GetComInterfaceForObject(_events, typeof(IExplorerBrowserEvents));
        try { browser.Advise(sink, out _cookie); }
        finally { Marshal.Release(sink); }

        _browser = browser;
        HiddenSince = null;
        if (!Navigate(_pending)) Navigate(PathUtil.ThisPc);
    }

    /// <summary>When the tab was last hidden (null while visible); idle tabs are unloaded to save memory.</summary>
    public long? HiddenSince { get; set; }

    /// <summary>
    /// Frees the embedded Explorer view (and the shell extensions it loaded) but keeps the tab and its folder;
    /// the view is recreated the next time the tab is shown.
    /// </summary>
    public void Unload()
    {
        if (_browser == null) return;
        _pending = Path;
        DestroyBrowser();
    }

    void DestroyBrowser()
    {
        _hook?.ReleaseHandle();
        _hook = null;
        _view = null;
        ListHandle = IntPtr.Zero;
        ClearColorCache();
        if (_browser == null) return;
        try
        {
            _browser.Unadvise(_cookie);
            _browser.Destroy();
        }
        catch
        {
            // Already torn down by the shell.
        }
        Marshal.ReleaseComObject(_browser);
        _browser = null;
    }

    /// <summary>Navigates to a path, "::{CLSID}", "shell:..." or %VAR% string. Returns false if it can't be resolved.</summary>
    public bool Navigate(string path)
    {
        if (_browser == null)
        {
            if (!PathUtil.IsFileSystemPath(PathUtil.ToParsable(path)) || Directory.Exists(PathUtil.ToParsable(path)))
            {
                _pending = Path = path;
                Title = PathUtil.ShortTitle(path);
                return true;
            }
            return false;
        }

        var pidl = Shell.Parse(path);
        if (pidl == IntPtr.Zero) return false;
        try { return _browser.BrowseToIDList(pidl, SBSP_ABSOLUTE) >= 0; }
        finally { Shell.Free(pidl); }
    }

    /// <summary>Navigates straight to a shell item (from the folder tree) — works for virtual folders too.</summary>
    public bool NavigateItem(IShellItem item, string parsingName)
    {
        if (_browser == null) return Navigate(parsingName);
        return _browser.BrowseToObject(item, SBSP_ABSOLUTE) >= 0 || Navigate(parsingName);
    }

    public bool Back() => Browse(SBSP_NAVIGATEBACK);
    public bool Forward() => Browse(SBSP_NAVIGATEFORWARD);
    public bool Up() => Browse(SBSP_PARENT);

    bool Browse(uint flags) => _browser != null && _browser.BrowseToIDList(IntPtr.Zero, flags) >= 0;

    void OnNavigated(IntPtr pidl)
    {
        Path = Shell.NameOf(pidl, Shell.SIGDN_DESKTOPABSOLUTEPARSING) ?? Path;
        Title = Shell.NameOf(pidl, Shell.SIGDN_NORMALDISPLAY) ?? PathUtil.ShortTitle(Path);
        _pending = Path;
        _view = null; // re-read lazily: the view object of the finished navigation
        ClearColorCache();
        AttachHook();
        if (ShowCloudStatus && CloudStatus.IsCloudPath(Path) && View<IShellView>() is { } view)
        {
            try { CloudStatus.EnsureStatusColumn(view); }
            catch (Exception ex) { Log.Write("cloud status column: " + ex.Message); }
        }
        Navigated?.Invoke(this);
        TrySelectPending();
    }

    /// <summary>Each navigation creates a new view window; subclass it to colour items via NM_CUSTOMDRAW.</summary>
    void AttachHook()
    {
        ListHandle = IntPtr.Zero;
        var view = View<IShellView>();
        if (view == null || view.GetWindow(out var defView) != 0 || defView == IntPtr.Zero) return;
        var list = Native.FindWindowEx(defView, IntPtr.Zero, "SysListView32", null);
        ListHandle = list;
        if (list == IntPtr.Zero) return;
        if (_hook != null && _hook.Handle == defView) return;
        _hook?.ReleaseHandle();
        _hook = new DefViewHook(this, list);
        _hook.AssignHandle(defView);
        Native.InvalidateRect(list, IntPtr.Zero, true);
    }

    /// <summary>Repaint after highlight rules change.</summary>
    public void Repaint()
    {
        ClearColorCache();
        if (ListHandle != IntPtr.Zero) Native.InvalidateRect(ListHandle, IntPtr.Zero, true);
    }

    // ── colour cache ──
    // The list asks for a colour for every visible row on every repaint (scrolling, hovering, selection).
    // Resolving a row means several COM calls plus pattern matching, so answers are cached per item ID
    // (a hash of the item's PIDL, which also changes when the item is renamed or modified).
    readonly Dictionary<ulong, Color?> _colors = new();
    Highlighter? _colorsFor;
    byte[] _pidlBuffer = new byte[512];

    void ClearColorCache()
    {
        _colors.Clear();
        _colorsFor = null;
    }

    [DllImport("shell32.dll")] static extern uint ILGetSize(IntPtr pidl);

    internal Color? ColorAt(int index, Highlighter highlighter)
    {
        if (!ReferenceEquals(highlighter, _colorsFor) || _colors.Count > 50000)
        {
            _colors.Clear();
            _colorsFor = highlighter;
        }
        var fv = View<IFolderView2>();
        if (fv == null || fv.Item(index, out var pidl) != 0 || pidl == IntPtr.Zero) return null;
        ulong key;
        try
        {
            int size = (int)ILGetSize(pidl);
            if (size > _pidlBuffer.Length) _pidlBuffer = new byte[size * 2];
            Marshal.Copy(pidl, _pidlBuffer, 0, size);
            key = 14695981039346656037UL; // FNV-1a
            for (int i = 0; i < size; i++) key = (key ^ _pidlBuffer[i]) * 1099511628211UL;
        }
        finally
        {
            Marshal.FreeCoTaskMem(pidl);
        }
        if (_colors.TryGetValue(key, out var cached)) return cached;
        var color = ItemAt(index) is { } entry ? highlighter.ColorFor(entry) : null;
        _colors[key] = color;
        return color;
    }

    /// <summary>Items whose colour is cached (tests/diagnostics).</summary>
    internal int CachedColorCount => _colors.Count;

    // ── right-click menu ──

    /// <summary>ExplorerDuo commands for the view's right-click menu: (tab, clicked on items?) → commands. Null = native menu.</summary>
    public static Func<BrowserTab, bool, List<MenuCommand>>? MenuExtras { get; set; }

    const int LVM_GETITEMRECT = 0x100E;

    /// <summary>
    /// Shows the complete Windows menu (as Explorer builds it) for the selection or the folder background, with
    /// ExplorerDuo's commands on top. <paramref name="lParam"/> is screen coordinates, or -1 for the keyboard.
    /// </summary>
    internal bool ShowContextMenu(IntPtr lParam)
    {
        var view = View<IShellView>();
        if (view == null || view.GetWindow(out var defView) != 0) return false;

        bool onItems;
        Point screen;
        if (lParam == (IntPtr)(-1))
        {
            onItems = Counts().Selected > 0;
            screen = Cursor.Position;
            var fv = View<IFolderView2>();
            if (onItems && fv != null && fv.GetFocusedItem(out var focused) == 0 && focused >= 0 && ListHandle != IntPtr.Zero)
            {
                var rect = Marshal.AllocHGlobal(16);
                try
                {
                    Marshal.WriteInt32(rect, 2 /* LVIR_LABEL */);
                    if (Native.SendMessage(ListHandle, LVM_GETITEMRECT, (IntPtr)focused, rect) != IntPtr.Zero)
                    {
                        var pt = new POINT(Marshal.ReadInt32(rect, 0) + 8, Marshal.ReadInt32(rect, 12));
                        Native.MapWindowPoints(ListHandle, IntPtr.Zero, ref pt, 1);
                        screen = new Point(pt.X, pt.Y);
                    }
                }
                finally
                {
                    Marshal.FreeHGlobal(rect);
                }
            }
        }
        else
        {
            screen = Native.PointFromLParam(lParam);
            var pt = new POINT(screen.X, screen.Y);
            if (ListHandle != IntPtr.Zero) Native.MapWindowPoints(IntPtr.Zero, ListHandle, ref pt, 1);
            onItems = ListHandle != IntPtr.Zero && HitTest(new Point(pt.X, pt.Y)) != null && Counts().Selected > 0;
        }

        var cm = ShellMenu.ForView(view, onItems ? ShellMenu.SVGIO_SELECTION : ShellMenu.SVGIO_BACKGROUND);
        if (cm == null) return false;
        MenusShown++;
        try
        {
            var own = MenuExtras?.Invoke(this, onItems) ?? new List<MenuCommand>();
            ShellMenu.Show(defView, cm, screen, own, PathUtil.IsFileSystemPath(Path) ? Path : null, verb =>
            {
                switch (verb.ToLowerInvariant())
                {
                    case "rename":
                        View<IFolderView2>()?.DoRename(); // in-place rename, exactly like F2
                        return true;
                    case "open" or "explore" when onItems:
                        var selection = Selection();
                        if (selection.Count == 1 && selection[0].IsFolder) return Navigate(selection[0].ParsingName);
                        return false;
                    default:
                        return false;
                }
            }, site: view);
        }
        finally
        {
            ShellMenu.Release(cm);
        }
        return true;
    }

    /// <summary>Right-click menus shown so far (tests).</summary>
    public int MenusShown { get; private set; }

    [DllImport("shell32.dll")] static extern int SHGetIDListFromObject([MarshalAs(UnmanagedType.IUnknown)] object punk, out IntPtr pidl);

    /// <summary>
    /// Absolute ID lists of the selected items (caller frees each with CoTaskMemFree). Handing these to the copy engine
    /// avoids re-parsing every path from disk, and they can safely cross to the worker thread.
    /// </summary>
    public List<IntPtr> SelectionIdLists()
    {
        var list = new List<IntPtr>();
        var fv = View<IFolderView>();
        if (fv == null) return list;
        var iid = typeof(IShellItemArray).GUID;
        if (fv.Items(SVGIO_SELECTION, ref iid, out var obj) != 0 || obj is not IShellItemArray array) return list;
        array.GetCount(out var count);
        for (uint i = 0; i < count; i++)
        {
            array.GetItemAt(i, out var item);
            if (SHGetIDListFromObject(item, out var pidl) == 0 && pidl != IntPtr.Zero) list.Add(pidl);
        }
        return list;
    }

    /// <summary>Item at a view index (same index as the list control row).</summary>
    public ShellEntry? ItemAt(int index)
    {
        var fv = View<IFolderView2>();
        if (fv == null) return null;
        var iid = typeof(IShellItem).GUID;
        return fv.GetItem(index, ref iid, out var obj) == 0 && obj is IShellItem item ? EntryOf(item) : null;
    }

    /// <summary>Which item is under a point (client coordinates of <see cref="ListHandle"/>)? null = empty space.</summary>
    public ShellEntry? HitTest(Point client)
    {
        if (ListHandle == IntPtr.Zero) return null;
        var info = new LVHITTESTINFO { pt = new POINT(client.X, client.Y), iItem = -1 };
        var buffer = Marshal.AllocHGlobal(Marshal.SizeOf<LVHITTESTINFO>());
        try
        {
            Marshal.StructureToPtr(info, buffer, false);
            int index = (int)Native.SendMessage(ListHandle, LVM_HITTEST, IntPtr.Zero, buffer);
            info = Marshal.PtrToStructure<LVHITTESTINFO>(buffer);
            return index >= 0 && (info.flags & 0x0E /* LVHT_ONITEM */) != 0 ? ItemAt(index) : null;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    /// <summary>Selects (or deselects) every item whose file name matches the wildcard patterns. Returns how many matched.</summary>
    public int SelectByPattern(string patterns, bool select)
    {
        var fv = View<IFolderView2>();
        if (fv == null || fv.ItemCount(SVGIO_ALLVIEW, out var count) != 0) return 0;
        var regex = Highlighter.Wildcard(patterns);
        int matched = 0;
        for (int i = 0; i < count; i++)
        {
            var e = ItemAt(i);
            if (e == null || !regex.IsMatch(FileNameOf(e))) continue;
            fv.SelectItem(i, select ? SVSI_SELECT : SVSI_DESELECT);
            matched++;
        }
        return matched;
    }

    string? _pendingSelect;
    long _pendingSelectUntil;

    /// <summary>Selects (and scrolls to) <paramref name="path"/> as soon as the view has listed it.</summary>
    public void SelectWhenLoaded(string path)
    {
        _pendingSelect = path;
        _pendingSelectUntil = Environment.TickCount64 + 8000;
        TrySelectPending();
    }

    /// <summary>Called on navigation and periodically; the view enumerates items asynchronously.</summary>
    public bool TrySelectPending()
    {
        if (_pendingSelect == null) return false;
        if (Environment.TickCount64 > _pendingSelectUntil) { _pendingSelect = null; return false; }
        if (!PathUtil.SamePath(System.IO.Path.GetDirectoryName(Highlighter.Normalize(_pendingSelect)), Path)) return false;
        var fv = View<IFolderView2>();
        if (fv == null || fv.ItemCount(SVGIO_ALLVIEW, out var count) != 0) return false;
        for (int i = 0; i < count; i++)
        {
            if (ItemAt(i) is { } e && PathUtil.SamePath(e.ParsingName, _pendingSelect))
            {
                fv.SelectItem(i, SVSI_SELECT | 0x4 /* DESELECTOTHERS */ | 0x8 /* ENSUREVISIBLE */ | 0x10 /* FOCUSED */);
                _pendingSelect = null;
                return true;
            }
        }
        return false;
    }

    public void InvertSelection()
    {
        var fv = View<IFolderView2>();
        if (fv == null || fv.ItemCount(SVGIO_ALLVIEW, out var count) != 0) return;
        var selected = new HashSet<string>(Selection().Select(s => s.ParsingName), StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < count; i++)
        {
            var e = ItemAt(i);
            if (e != null) fv.SelectItem(i, selected.Contains(e.ParsingName) ? SVSI_DESELECT : SVSI_SELECT);
        }
    }

    public static string FileNameOf(ShellEntry e)
    {
        if (!e.IsFileSystem) return e.Name;
        var p = Highlighter.Normalize(e.ParsingName);
        var n = System.IO.Path.GetFileName(p);
        return string.IsNullOrEmpty(n) ? e.Name : n;
    }

    static ShellEntry? EntryOf(IShellItem item)
    {
        var parsing = Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING);
        if (string.IsNullOrEmpty(parsing)) return null;
        item.GetAttributes(Shell.SFGAO_FOLDER | Shell.SFGAO_STREAM | Shell.SFGAO_FILESYSTEM | Shell.SFGAO_HIDDEN, out var attrs);
        bool isFolder = (attrs & Shell.SFGAO_FOLDER) != 0 && (attrs & Shell.SFGAO_STREAM) == 0; // zip files are "folders" too
        return new ShellEntry(parsing, Shell.NameOf(item, Shell.SIGDN_NORMALDISPLAY) ?? parsing,
            isFolder, (attrs & Shell.SFGAO_FILESYSTEM) != 0, (attrs & Shell.SFGAO_HIDDEN) != 0);
    }

    [StructLayout(LayoutKind.Sequential)]
    struct LVHITTESTINFO
    {
        public POINT pt;
        public uint flags;
        public int iItem;
        public int iSubItem;
        public int iGroup;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct NMHDR
    {
        public IntPtr hwndFrom;
        public UIntPtr idFrom;
        public int code; // padded to 8 bytes on x64 as part of the nested struct
    }

    [StructLayout(LayoutKind.Sequential)]
    struct NMCUSTOMDRAW
    {
        public NMHDR hdr;
        public uint dwDrawStage;
        public IntPtr hdc;
        public RECT rc;
        public UIntPtr dwItemSpec;
        public uint uItemState;
        public IntPtr lItemlParam;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct NMLVCUSTOMDRAW
    {
        public NMCUSTOMDRAW nmcd;
        public uint clrText;
        public uint clrTextBk;
    }

    /// <summary>Subclass of the view window (SHELLDLL_DefView) that adds per-item text colours to the list's custom draw.</summary>
    sealed class DefViewHook : NativeWindow
    {
        const int NM_CUSTOMDRAW = -12;
        const int WM_CONTEXTMENU = 0x007B, NM_RCLICK = -5;
        // NMITEMACTIVATE: NMHDR, iItem, iSubItem, uNewState, uOldState, uChanged, ptAction
        static readonly int ActionOffset = Marshal.SizeOf<NMHDR>() + 5 * 4;
        const uint CDDS_PREPAINT = 0x1, CDDS_ITEMPREPAINT = 0x10001;
        const long CDRF_NEWFONT = 0x2, CDRF_NOTIFYITEMDRAW = 0x20;
        static readonly int CodeOffset = (int)Marshal.OffsetOf<NMHDR>(nameof(NMHDR.code));
        static readonly int StageOffset = (int)Marshal.OffsetOf<NMCUSTOMDRAW>(nameof(NMCUSTOMDRAW.dwDrawStage));
        static readonly int ItemOffset = (int)Marshal.OffsetOf<NMCUSTOMDRAW>(nameof(NMCUSTOMDRAW.dwItemSpec));
        static readonly int TextColorOffset = (int)Marshal.OffsetOf<NMLVCUSTOMDRAW>(nameof(NMLVCUSTOMDRAW.clrText));

        readonly BrowserTab _tab;
        readonly IntPtr _list;

        public DefViewHook(BrowserTab tab, IntPtr list)
        {
            _tab = tab;
            _list = list;
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_CONTEXTMENU && MenuExtras != null && (m.WParam == _list || m.WParam == Handle))
            {
                try
                {
                    if (_tab.ShowContextMenu(m.LParam)) return;
                }
                catch (Exception ex)
                {
                    Log.Write("view menu failed: " + ex);
                }
            }
            // A mouse right-click arrives first as NM_RCLICK from the list (handled → no WM_CONTEXTMENU follows).
            if (m.Msg == Native.WM_NOTIFY && MenuExtras != null && m.LParam != IntPtr.Zero
                && Marshal.ReadIntPtr(m.LParam) == _list && Marshal.ReadInt32(m.LParam, CodeOffset) == NM_RCLICK)
            {
                try
                {
                    // NMITEMACTIVATE.ptAction: the click in list client coordinates.
                    var pt = new POINT(Marshal.ReadInt32(m.LParam, ActionOffset), Marshal.ReadInt32(m.LParam, ActionOffset + 4));
                    Native.MapWindowPoints(_list, IntPtr.Zero, ref pt, 1);
                    if (_tab.ShowContextMenu((IntPtr)((pt.Y << 16) | (pt.X & 0xFFFF))))
                    {
                        m.Result = (IntPtr)1;
                        return;
                    }
                }
                catch (Exception ex)
                {
                    Log.Write("view menu failed: " + ex);
                }
            }
            if (m.Msg == Native.WM_NOTIFY && Highlights.Current is { IsEmpty: false } highlighter && m.LParam != IntPtr.Zero)
            {
                try
                {
                    if (Marshal.ReadIntPtr(m.LParam) == _list && Marshal.ReadInt32(m.LParam, CodeOffset) == NM_CUSTOMDRAW)
                    {
                        base.WndProc(ref m);
                        uint stage = (uint)Marshal.ReadInt32(m.LParam, StageOffset);
                        long result = m.Result.ToInt64();
                        if (stage == CDDS_PREPAINT)
                        {
                            m.Result = (IntPtr)(result | CDRF_NOTIFYITEMDRAW);
                        }
                        else if (stage == CDDS_ITEMPREPAINT)
                        {
                            int index = (int)Marshal.ReadIntPtr(m.LParam, ItemOffset);
                            if (_tab.ColorAt(index, highlighter) is Color color)
                            {
                                Marshal.WriteInt32(m.LParam, TextColorOffset, (int)Highlighter.ToColorRef(color));
                                m.Result = (IntPtr)(result | CDRF_NEWFONT);
                                ColoredItemPaints++;
                            }
                        }
                        return;
                    }
                }
                catch
                {
                    // Fall through to default handling; never break painting.
                }
            }
            base.WndProc(ref m);
        }
    }

    void ResizeView()
    {
        if (_browser == null) return;
        var hdwp = IntPtr.Zero;
        _browser.SetRect(ref hdwp, new RECT(Host.ClientRectangle));
    }

    /// <summary>The current view as <typeparamref name="T"/>. The view object is fetched once per navigation, not per call.</summary>
    T? View<T>() where T : class
    {
        if (_browser == null) return null;
        if (_view == null)
        {
            var iid = typeof(IShellView).GUID;
            if (_browser.GetCurrentView(ref iid, out var ptr) != 0 || ptr == IntPtr.Zero) return null;
            try { _view = Marshal.GetObjectForIUnknown(ptr); }
            finally { Marshal.Release(ptr); }
        }
        return _view as T;
    }

    /// <summary>Gives the shell view its keyboard accelerators (Ctrl+C/V/X/Z/A, Delete, F2, Alt+Enter, Ctrl+Shift+N...).</summary>
    public bool TranslateAccelerator(ref MSG msg) => View<IShellView>()?.TranslateAccelerator(ref msg) == 0;

    public void Refresh() => View<IShellView>()?.Refresh();

    internal IShellView? ShellView => View<IShellView>();

    public void FocusView()
    {
        var view = View<IShellView>();
        if (view == null) return;
        view.UIActivate(SVUIA_ACTIVATE_FOCUS);
        if (view.GetWindow(out var hwnd) == 0) Native.SetFocus(hwnd);
    }

    public (int Total, int Selected) Counts()
    {
        var fv = View<IFolderView>();
        if (fv == null) return (0, 0);
        fv.ItemCount(SVGIO_ALLVIEW, out var total);
        fv.ItemCount(SVGIO_SELECTION, out var selected);
        return (total, selected);
    }

    public List<ShellEntry> Selection()
    {
        var list = new List<ShellEntry>();
        var fv = View<IFolderView>();
        if (fv == null) return list;
        var iid = typeof(IShellItemArray).GUID;
        if (fv.Items(SVGIO_SELECTION, ref iid, out var obj) != 0 || obj is not IShellItemArray array) return list;

        array.GetCount(out var count);
        for (uint i = 0; i < count; i++)
        {
            array.GetItemAt(i, out var item);
            if (EntryOf(item) is { } entry) list.Add(entry);
        }
        return list;
    }

    public void Dispose()
    {
        DestroyBrowser();
        Host.Dispose();
    }
}
