using System.Runtime.InteropServices;

namespace ExplorerDuo;

/// <summary>
/// Explorer's own navigation tree (INameSpaceTreeControl): Quick access, This PC, Network,
/// with native icons, expand-on-demand, drag & drop targets and right-click menus.
/// </summary>
internal sealed class FolderTree : Panel
{
    static readonly Guid ClsidNamespaceTree = new("AE054212-3535-4430-83ED-D501AA6680E6");

    const uint NSTCS_HASEXPANDOS = 0x1;
    const uint NSTCS_FULLROWSELECT = 0x8;
    const uint NSTCS_ROOTHASEXPANDO = 0x40;
    const uint NSTCS_SHOWSELECTIONALWAYS = 0x80;
    const uint NSTCS_NOEDITLABELS = 0x10000;
    const uint NSTCS_TABSTOP = 0x20000;
    const uint NSTCS_AUTOHSCROLL = 0x100000;
    const uint NSTCS_FADEINOUTEXPANDOS = 0x200000;

    const uint SHCONTF_FOLDERS = 0x20;
    const uint SHCONTF_NAVIGATION_ENUM = 0x1000;
    const uint NSTCRS_VISIBLE = 0x0;
    const uint NSTCRS_EXPANDED = 0x2;

    public const uint NSTCIS_SELECTED = 0x1;
    public const uint NSTCIS_EXPANDED = 0x2;

    const uint SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);


    /// <summary>Same order as Windows 11's navigation pane. Alternatives are tried left to right; missing ones are skipped.</summary>
    static readonly (string[] Candidates, uint Style)[] Roots =
    {
        (new[] { "::{F874310E-B6B7-47DC-BC84-B9E6B38F5903}", "::{679F85CB-0220-4080-B29B-5540CC05AAB6}" }, NSTCRS_VISIBLE), // Home / Quick access
        (new[] { "shell:Desktop" }, NSTCRS_VISIBLE),
        (new[] { "shell:Downloads" }, NSTCRS_VISIBLE),
        (new[] { "shell:Personal" }, NSTCRS_VISIBLE),   // Documents
        (new[] { "shell:My Pictures" }, NSTCRS_VISIBLE),
        (new[] { "shell:My Music" }, NSTCRS_VISIBLE),
        (new[] { "shell:My Video" }, NSTCRS_VISIBLE),
        (new[] { PathUtil.ThisPc }, NSTCRS_EXPANDED),
        (new[] { "::{F02C1A0D-BE21-4350-88B0-7367FC96EF3C}" }, NSTCRS_VISIBLE), // Network
    };

    readonly System.Windows.Forms.Timer _debounce = new() { Interval = 150 };
    INameSpaceTreeControl? _tree;
    TreeEvents? _events;
    uint _cookie;
    IntPtr _hwnd;
    bool _syncing;
    IShellItem? _pendingPick;

    /// <summary>The user picked a folder in the tree (click or keyboard).</summary>
    public event Action<IShellItem, string>? FolderPicked;

    public FolderTree()
    {
        Dock = DockStyle.Fill;
        BackColor = Theme.Surface;
        _debounce.Tick += (_, _) =>
        {
            _debounce.Stop();
            var item = _pendingPick;
            _pendingPick = null;
            if (item == null) return;
            var path = Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING);
            if (!string.IsNullOrEmpty(path)) FolderPicked?.Invoke(item, path);
        };
    }

    public bool IsReady => _tree != null;
    /// <summary>The path last revealed for the pane this tree follows (avoids re-expanding on every refresh).</summary>
    public string Revealed { get; set; } = "";
    public int RootCount { get; private set; }
    public IntPtr TreeHandle => _hwnd;

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        if (_tree != null) return;

        // The tree registers itself as a drop target only if OLE (not just COM) is initialised on this thread;
        // without it, files can't be dragged onto tree folders.
        Application.OleRequired();
        var tree = (INameSpaceTreeControl)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidNamespaceTree)!)!;
        var rc = new RECT(ClientRectangle);
        tree.Initialize(Handle, ref rc,
            NSTCS_HASEXPANDOS | NSTCS_ROOTHASEXPANDO | NSTCS_FADEINOUTEXPANDOS | NSTCS_FULLROWSELECT |
            NSTCS_SHOWSELECTIONALWAYS | NSTCS_AUTOHSCROLL | NSTCS_TABSTOP | NSTCS_NOEDITLABELS);
        tree.SetTheme("Explorer");

        _events = new TreeEvents(OnSelectionChanged, ColorOf, () => Highlights.Current is { IsEmpty: false });
        tree.TreeAdvise(_events, out _cookie);

        foreach (var (candidates, style) in Roots)
        {
            foreach (var path in candidates)
            {
                try
                {
                    tree.AppendRoot(Shell.CreateItem(path), SHCONTF_FOLDERS | SHCONTF_NAVIGATION_ENUM, style, IntPtr.Zero);
                    RootCount++;
                    break;
                }
                catch
                {
                    // Not available on this Windows build; try the next candidate.
                }
            }
        }

        if (tree is IOleWindow window && window.GetWindow(out var hwnd) == 0) _hwnd = hwnd;
        _tree = tree;
        ResizeTree();

        // Replace the tree's short built-in menu with the full Windows menu plus ExplorerDuo commands.
        if (_hwnd != IntPtr.Zero)
        {
            _frameHook = new MenuHook(this, treeView: false);
            _frameHook.AssignHandle(_hwnd);
            var tv = TreeViewHandle;
            if (tv != _hwnd)
            {
                _viewHook = new MenuHook(this, treeView: true);
                _viewHook.AssignHandle(tv);
            }
        }
    }

    MenuHook? _frameHook, _viewHook;

    /// <summary>ExplorerDuo commands shown above the Windows items when a tree folder is right-clicked.</summary>
    public Func<IShellItem, string, List<MenuCommand>>? MenuExtras { get; set; }

    /// <summary>"Rename" was chosen in the tree's menu.</summary>
    public event Action<string>? RenameRequested;

    /// <summary>Number of right-click menus shown (tests).</summary>
    public int MenusShown { get; private set; }

    const int WM_CONTEXTMENU = 0x007B, NM_RCLICK = -5, TVGN_DROPHILITE = 0x8;

    /// <summary>Right-click / Shift+F10 / menu key on the tree. <paramref name="lParam"/> is screen coordinates, or -1 for the keyboard.</summary>
    void ShowContextMenu(IntPtr lParam)
    {
        var tv = TreeViewHandle;
        if (tv == IntPtr.Zero) return;
        Point screen;
        IntPtr hItem;
        IShellItem? item;
        if (lParam == (IntPtr)(-1))
        {
            hItem = Native.SendMessage(tv, TVM_GETNEXTITEM, (IntPtr)TVGN_CARET, IntPtr.Zero);
            item = SelectedItem();
            var r = SelectedItemRect() ?? Rectangle.Empty;
            var pt = new POINT(r.Left + 8, r.Bottom);
            Native.MapWindowPoints(tv, IntPtr.Zero, ref pt, 1);
            screen = new Point(pt.X, pt.Y);
        }
        else
        {
            screen = Native.PointFromLParam(lParam);
            var pt = new POINT(screen.X, screen.Y);
            Native.MapWindowPoints(IntPtr.Zero, tv, ref pt, 1);
            hItem = HitTestHandle(tv, new Point(pt.X, pt.Y));
            item = hItem == IntPtr.Zero ? null : ItemAt(tv, new Point(pt.X, pt.Y));
        }
        if (item == null) return;
        var path = Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING) ?? "";

        MenusShown++;
        Native.SendMessage(tv, TVM_SELECTITEM, (IntPtr)TVGN_DROPHILITE, hItem); // show which folder the menu is for
        var cm = ShellMenu.ForItem(item);
        try
        {
            var own = MenuExtras?.Invoke(item, path) ?? new List<MenuCommand>();
            ShellMenu.Show(_hwnd, cm, screen, own, PathUtil.IsFileSystemPath(path) ? path : null, verb =>
            {
                switch (verb.ToLowerInvariant())
                {
                    case "open":
                    case "explore":
                        FolderPicked?.Invoke(item, path); // open in ExplorerDuo instead of a new Explorer window
                        return true;
                    case "rename":
                        RenameRequested?.Invoke(path);
                        return true;
                    default:
                        return false;
                }
            });
        }
        finally
        {
            Native.SendMessage(tv, TVM_SELECTITEM, (IntPtr)TVGN_DROPHILITE, IntPtr.Zero);
            ShellMenu.Release(cm);
        }
    }

    IntPtr HitTestHandle(IntPtr tv, Point client)
    {
        var buffer = Marshal.AllocHGlobal(IntPtr.Size * 3);
        try
        {
            Marshal.WriteInt32(buffer, 0, client.X);
            Marshal.WriteInt32(buffer, 4, client.Y);
            var hItem = Native.SendMessage(tv, TVM_HITTEST, IntPtr.Zero, buffer);
            return (Marshal.ReadInt32(buffer, 8) & TVHT_ON_ROW) != 0 ? hItem : IntPtr.Zero;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    IShellItem? SelectedItem()
    {
        if (_tree == null || _tree.GetSelectedItems(out var items) != 0 || items == null) return null;
        items.GetCount(out var n);
        if (n == 0) return null;
        items.GetItemAt(0, out var item);
        return item;
    }

    /// <summary>Opens the menu for the folder under a tree-view client point, as a right-click would (tests).</summary>
    internal void OpenMenuAt(Point treeViewClient)
    {
        var pt = new POINT(treeViewClient.X, treeViewClient.Y);
        Native.MapWindowPoints(TreeViewHandle, IntPtr.Zero, ref pt, 1);
        ShowContextMenu((IntPtr)((pt.Y << 16) | (pt.X & 0xFFFF)));
    }

    sealed class MenuHook : NativeWindow
    {
        static readonly int CodeOffset = IntPtr.Size * 2;
        readonly FolderTree _owner;
        readonly bool _treeView;

        public MenuHook(FolderTree owner, bool treeView)
        {
            _owner = owner;
            _treeView = treeView;
        }

        protected override void WndProc(ref Message m)
        {
            // WM_KEYDOWN, WM_CHAR, WM_LBUTTONDOWN, WM_LBUTTONDBLCLK, WM_MBUTTONDOWN: the user is driving the tree.
            if (_treeView && m.Msg is 0x0100 or 0x0102 or 0x0201 or 0x0203 or 0x0207)
                _owner._lastUserInput = Environment.TickCount64;
            try
            {
                if (m.Msg == WM_CONTEXTMENU)
                {
                    _owner.ShowContextMenu(m.LParam);
                    return;
                }
                if (!_treeView && m.Msg == Native.WM_NOTIFY && m.LParam != IntPtr.Zero
                    && Marshal.ReadInt32(m.LParam, CodeOffset) == NM_RCLICK && Marshal.ReadIntPtr(m.LParam) == _owner.TreeViewHandle)
                {
                    var p = Cursor.Position;
                    _owner.ShowContextMenu((IntPtr)((p.Y << 16) | (p.X & 0xFFFF)));
                    m.Result = (IntPtr)1; // handled: no second (built-in) menu
                    return;
                }
            }
            catch (Exception ex)
            {
                Log.Write("tree menu failed: " + ex);
                return;
            }
            base.WndProc(ref m);
        }
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        ResizeTree();
    }

    void ResizeTree()
    {
        if (_hwnd != IntPtr.Zero)
            SetWindowPos(_hwnd, IntPtr.Zero, 0, 0, ClientSize.Width, ClientSize.Height, SWP_NOZORDER | SWP_NOACTIVATE);
    }

    long _lastUserInput;

    void OnSelectionChanged(IntPtr selection)
    {
        if (_syncing || selection == IntPtr.Zero) return;
        // A new tree selects its first root by itself; only selections that follow a click or key press are picks.
        if (Environment.TickCount64 - _lastUserInput > 2000) return;
        try
        {
            if (Marshal.GetObjectForIUnknown(selection) is not IShellItemArray array) return;
            array.GetCount(out var count);
            if (count == 0) return;
            array.GetItemAt(0, out var item);
            _pendingPick = item;
            _debounce.Stop();
            _debounce.Start(); // arrow-key browsing through the tree doesn't navigate on every step
        }
        catch
        {
            // Item vanished while selecting.
        }
    }

    /// <summary>Selects <paramref name="path"/> in the tree, expanding its parents, without navigating anything.</summary>
    public bool Reveal(string path)
    {
        if (_tree == null || string.IsNullOrEmpty(path)) return false;
        IShellItem item;
        try { item = Shell.CreateItem(path); }
        catch { return false; }

        _syncing = true;
        try
        {
            _tree.EnsureItemVisible(item);
            if (_tree.SetItemState(item, NSTCIS_SELECTED, NSTCIS_SELECTED) == 0) return ScrollSelectionIntoView();

            // Not loaded yet: expand the ancestors top-down, then select.
            var chain = new List<IShellItem>();
            var current = item;
            while (true)
            {
                try
                {
                    current.GetParent(out var parent);
                    chain.Add(parent);
                    current = parent;
                }
                catch
                {
                    break;
                }
            }
            chain.Reverse();
            foreach (var ancestor in chain) _tree.SetItemState(ancestor, NSTCIS_EXPANDED, NSTCIS_EXPANDED);
            _tree.EnsureItemVisible(item);
            return _tree.SetItemState(item, NSTCIS_SELECTED, NSTCIS_SELECTED) == 0 && ScrollSelectionIntoView();
        }
        catch
        {
            return false;
        }
        finally
        {
            _syncing = false;
        }
    }

    // Painting asks for every visible row on every repaint; remember answers until the rules change.
    readonly Dictionary<string, uint?> _colorCache = new(StringComparer.OrdinalIgnoreCase);
    Highlighter? _colorCacheFor;

    uint? ColorOf(IShellItem item)
    {
        if (Highlights.Current is not { IsEmpty: false } h) return null;
        if (!ReferenceEquals(h, _colorCacheFor) || _colorCache.Count > 20000)
        {
            _colorCache.Clear();
            _colorCacheFor = h;
        }
        var path = Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING);
        if (string.IsNullOrEmpty(path)) return null;
        if (_colorCache.TryGetValue(path, out var cached)) return cached;
        item.GetAttributes(Shell.SFGAO_HIDDEN, out var attrs);
        uint? color = h.ColorFor(path, isFolder: true, isHidden: (attrs & Shell.SFGAO_HIDDEN) != 0) is Color c
            ? Highlighter.ToColorRef(c)
            : null;
        _colorCache[path] = color;
        return color;
    }

    /// <summary>Number of item custom-draw callbacks so far (diagnostics/tests).</summary>
    public int ItemPaintCalls => _events?.ItemPaintCalls ?? 0;

    /// <summary>The inner tree-view window, whose client coordinates HitTest/GetItemRect use.</summary>
    public IntPtr TreeViewHandle
    {
        get
        {
            if (_hwnd == IntPtr.Zero) return IntPtr.Zero;
            var inner = Native.FindWindowEx(_hwnd, IntPtr.Zero, "SysTreeView32", null);
            return inner != IntPtr.Zero ? inner : _hwnd;
        }
    }

    const int TVM_GETITEMRECT = 0x1104, TVM_GETNEXTITEM = 0x110A, TVM_SELECTITEM = 0x110B, TVM_HITTEST = 0x1111;
    const int TVGN_CARET = 0x9;
    const uint TVHT_ON_ROW = 0x2 | 0x4 | 0x8 | 0x20 | 0x40; // icon, label, indent, right of label, state icon

    /// <summary>
    /// Selects the tree item under a point (client coordinates of <paramref name="fromHwnd"/>) using the tree-view's
    /// own hit test. Returns false if there is no item there. If the item was already selected, its path is
    /// returned straight away (no selection-change event will follow); otherwise <see cref="FolderPicked"/> fires shortly.
    /// </summary>
    public bool SelectAt(IntPtr fromHwnd, Point client, out string? alreadySelectedPath)
    {
        alreadySelectedPath = null;
        var tv = TreeViewHandle;
        if (tv == IntPtr.Zero) return false;
        var pt = new POINT(client.X, client.Y);
        if (fromHwnd != tv) Native.MapWindowPoints(fromHwnd, tv, ref pt, 1);

        var buffer = Marshal.AllocHGlobal(IntPtr.Size * 3);
        try
        {
            Marshal.WriteInt32(buffer, 0, pt.X);
            Marshal.WriteInt32(buffer, 4, pt.Y);
            var hItem = Native.SendMessage(tv, TVM_HITTEST, IntPtr.Zero, buffer);
            uint flags = (uint)Marshal.ReadInt32(buffer, 8);
            if (hItem == IntPtr.Zero || (flags & TVHT_ON_ROW) == 0) return false;

            if (Native.SendMessage(tv, TVM_GETNEXTITEM, (IntPtr)TVGN_CARET, IntPtr.Zero) == hItem)
            {
                alreadySelectedPath = SelectedPath();
                return alreadySelectedPath != null;
            }
            _lastUserInput = Environment.TickCount64;
            Native.SendMessage(tv, TVM_SELECTITEM, (IntPtr)TVGN_CARET, hItem);
            return true;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    /// <summary>Shell item under a point (client coordinates of <paramref name="fromHwnd"/>), or null.</summary>
    public IShellItem? ItemAt(IntPtr fromHwnd, Point client)
    {
        if (_tree == null || _hwnd == IntPtr.Zero) return null;
        var pt = new POINT(client.X, client.Y);
        if (fromHwnd != _hwnd) Native.MapWindowPoints(fromHwnd, _hwnd, ref pt, 1);
        try { return _tree.HitTest(ref pt, out var item) == 0 ? item : null; }
        catch { return null; }
    }

    public string? HitTestPath(IntPtr fromHwnd, Point client) =>
        ItemAt(fromHwnd, client) is { } item ? Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING) : null;

    bool ScrollSelectionIntoView()
    {
        var tv = TreeViewHandle;
        var caret = tv == IntPtr.Zero ? IntPtr.Zero : Native.SendMessage(tv, TVM_GETNEXTITEM, (IntPtr)TVGN_CARET, IntPtr.Zero);
        if (caret != IntPtr.Zero) Native.SendMessage(tv, 0x1114 /* TVM_ENSUREVISIBLE */, IntPtr.Zero, caret);
        return true;
    }

    /// <summary>Client rectangle (in <see cref="TreeViewHandle"/>) of the selected item's label.</summary>
    public Rectangle? SelectedItemRect()
    {
        var tv = TreeViewHandle;
        if (tv == IntPtr.Zero) return null;
        var hItem = Native.SendMessage(tv, TVM_GETNEXTITEM, (IntPtr)TVGN_CARET, IntPtr.Zero);
        if (hItem == IntPtr.Zero) return null;
        var rect = Marshal.AllocHGlobal(16);
        try
        {
            Marshal.WriteIntPtr(rect, hItem); // TVM_GETITEMRECT takes the HTREEITEM in the RECT buffer
            if (Native.SendMessage(tv, TVM_GETITEMRECT, (IntPtr)1, rect) == IntPtr.Zero) return null;
            return Rectangle.FromLTRB(Marshal.ReadInt32(rect, 0), Marshal.ReadInt32(rect, 4), Marshal.ReadInt32(rect, 8), Marshal.ReadInt32(rect, 12));
        }
        finally
        {
            Marshal.FreeHGlobal(rect);
        }
    }

    public void Repaint()
    {
        var h = TreeViewHandle;
        if (h != IntPtr.Zero) Native.InvalidateRect(h, IntPtr.Zero, true);
    }

    public string? SelectedPath()
    {
        if (_tree == null || _tree.GetSelectedItems(out var items) != 0 || items == null) return null;
        items.GetCount(out var n);
        if (n == 0) return null;
        items.GetItemAt(0, out var item);
        return Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING);
    }

    public bool IsExpanded(string path)
    {
        if (_tree == null) return false;
        try { return _tree.GetItemState(Shell.CreateItem(path), NSTCIS_EXPANDED, out var s) == 0 && (s & NSTCIS_EXPANDED) != 0; }
        catch { return false; }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _debounce.Dispose();
            _frameHook?.ReleaseHandle();
            _viewHook?.ReleaseHandle();
            if (_tree != null)
            {
                try { _tree.TreeUnadvise(_cookie); } catch { }
                Marshal.ReleaseComObject(_tree);
                _tree = null;
            }
        }
        base.Dispose(disposing);
    }
}
