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
    public int RootCount { get; private set; }
    public IntPtr TreeHandle => _hwnd;

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        if (_tree != null) return;

        var tree = (INameSpaceTreeControl)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidNamespaceTree)!)!;
        var rc = new RECT(ClientRectangle);
        tree.Initialize(Handle, ref rc,
            NSTCS_HASEXPANDOS | NSTCS_ROOTHASEXPANDO | NSTCS_FADEINOUTEXPANDOS | NSTCS_FULLROWSELECT |
            NSTCS_SHOWSELECTIONALWAYS | NSTCS_AUTOHSCROLL | NSTCS_TABSTOP | NSTCS_NOEDITLABELS);
        tree.SetTheme("Explorer");

        _events = new TreeEvents(OnSelectionChanged, ColorOf);
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

    void OnSelectionChanged(IntPtr selection)
    {
        if (_syncing || selection == IntPtr.Zero) return;
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

    static uint? ColorOf(IShellItem item)
    {
        if (Highlights.Current is not { IsEmpty: false } h) return null;
        var path = Shell.NameOf(item, Shell.SIGDN_DESKTOPABSOLUTEPARSING);
        if (string.IsNullOrEmpty(path)) return null;
        item.GetAttributes(Shell.SFGAO_HIDDEN, out var attrs);
        return h.ColorFor(path, isFolder: true, isHidden: (attrs & Shell.SFGAO_HIDDEN) != 0) is Color c
            ? Highlighter.ToColorRef(c)
            : null;
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
            Native.SendMessage(tv, TVM_SELECTITEM, (IntPtr)TVGN_CARET, hItem);
            return true;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

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
