namespace ExplorerDuo;

/// <summary>The dual-panel window. Each pane holds tabs; each tab is a real embedded Explorer view.</summary>
internal sealed class MainForm : Form, IMessageFilter
{
    static readonly (string Keys, string Action)[] ShortcutHelp =
    {
        ("Tab", "Switch to the other pane"),
        ("F4", "Edit selected files (editor set in settings, Notepad by default)"),
        ("F5 / F6", "Copy / move selection to the other pane"),
        ("F7", "New folder"),
        ("F8 or Delete", "Delete selection (to Recycle Bin)"),
        ("F9", "Open a terminal in this folder"),
        ("Alt+F5 / Alt+F9", "Zip selection into the other pane / unzip into the other pane"),
        ("Ctrl+M", "Multi-Rename selected items"),
        ("Ctrl+F", "Search in this folder"),
        ("Ctrl+H", "Highlight selected items (or this folder) with a colour"),
        ("Num + / Num − / Num *", "Select by pattern / deselect by pattern / invert selection"),
        ("Ctrl+Shift+C", "Copy full path(s) of the selection"),
        ("Ctrl+D, Alt+1…9", "Bookmark this folder, open bookmark 1…9"),
        ("Ctrl+E", "Show this folder in the other pane"),
        ("Ctrl+U", "Swap the folders of both panes"),
        ("Ctrl+Enter", "Open selected folders as new tabs"),
        ("Ctrl+Shift+Enter", "Open selected folders as tabs in the other pane"),
        ("Ctrl+T / Ctrl+W", "New tab / close tab"),
        ("Ctrl+Shift+T", "Reopen closed tab"),
        ("Ctrl+Tab, Ctrl+1…9", "Next tab, jump to tab"),
        ("Ctrl+L, Alt+D", "Edit address"),
        ("Alt+←/→, Backspace", "Back / forward"),
        ("Alt+↑", "Parent folder"),
        ("Ctrl+R", "Refresh"),
        ("Ctrl+B", "Show / hide the folder tree on the left (Tab in the tree jumps to the active pane)"),
        ("Ctrl+Shift+B", "A folder tree inside each pane"),
        ("", ""),
        ("Middle-click", "On a folder (list, tree or bookmark): open it in the OTHER pane · Shift: as new tab · on a tab: close"),
        ("Right-click", "Full Windows menu (Shift: extended items) plus ExplorerDuo commands on top"),
        ("Explorer keys", "Ctrl+C/X/V, Ctrl+Z, F2 rename, Ctrl+Shift+N, Alt+Enter … work as in Explorer"),
        ("Mouse", "Drag & drop between panes or onto tree folders (Shift = move, Ctrl = copy, right-drag = ask);"),
        ("", "hold a drag over a tab to switch to it; double-click the divider to centre it"),
    };

    readonly Settings _settings;
    readonly IReadOnlyList<string> _startupPaths;
    readonly Pane _left = new();
    readonly Pane _right = new();
    readonly SplitContainer _split;
    readonly SplitContainer _outer;
    readonly FolderTree _tree = new();
    readonly BookmarkBar _bookmarkBar = new();
    readonly System.Windows.Forms.Timer _statusTimer = new() { Interval = 500 };
    readonly System.Windows.Forms.Timer _idleTimer = new() { Interval = 60_000 };
    readonly Stack<string> _closed = new();
    readonly CancellationTokenSource _cts = new();
    Pane _active;
    double _ratio = 0.5;
    (FolderTree Tree, long Until, bool AsTab)? _treePickToOther;
    FindFilesDialog? _find;

    /// <summary>For automated screenshots: show without stealing focus.</summary>
    public bool ActivateOnShow { get; set; } = true;
    protected override bool ShowWithoutActivation => !ActivateOnShow;

    public MainForm(Settings settings, IReadOnlyList<string> startupPaths)
    {
        _settings = settings;
        _startupPaths = startupPaths;
        _active = _left;
        BrowserTab.ClassicList = settings.ClassicListView;
        BrowserTab.ShowCloudStatus = settings.ShowCloudStatus;
        RebuildHighlighter();

        Text = "ExplorerDuo";
        Icon = AppIcon.Create();
        Font = Theme.Ui;
        BackColor = Theme.Window;
        Theme.InstallMenuRenderer();
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        MinimumSize = new Size(760, 420);
        Size = new Size(1400, 860);
        StartPosition = FormStartPosition.CenterScreen;

        _split = new SplitContainer { Dock = DockStyle.Fill, SplitterWidth = 10, BackColor = Theme.Window, TabStop = false };
        _split.Panel1.Controls.Add(_left);
        _split.Panel2.Controls.Add(_right);
        _split.SplitterMoved += (_, _) =>
        {
            // SplitContainer also raises this during its own initial layout; only a mouse drag sets the ratio.
            if (MouseButtons != MouseButtons.Left) return;
            _ratio = CurrentRatio();
            BeginInvoke(() => _active.FocusView());
        };
        _split.DoubleClick += (_, _) => { _ratio = 0.5; ApplyRatio(); _active.FocusView(); };
        _split.Resize += (_, _) => ApplyRatio();

        // Folder tree on the far left, shared by both panes: it always drives the active pane.
        _outer = new SplitContainer
        {
            Dock = DockStyle.Fill, SplitterWidth = 10, BackColor = Theme.Window, TabStop = false, FixedPanel = FixedPanel.Panel1,
        };
        // The shared tree sits on its own card: hairline outline around a surface.
        var treeCard = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Border, Padding = new Padding(1) };
        treeCard.Controls.Add(_tree);
        _outer.Panel1.Controls.Add(treeCard);
        _outer.Panel2.Controls.Add(_split);
        WireTree(_tree);
        BrowserTab.MenuExtras = ListMenu;

        _bookmarkBar.SetBookmarks(_settings.Bookmarks);
        _bookmarkBar.AddRequested += AddBookmark;
        _bookmarkBar.OpenRequested += OpenBookmark;
        _bookmarkBar.RemoveRequested += bm => { _settings.Bookmarks.Remove(bm); BookmarksChanged(); };
        _bookmarkBar.RenameRequested += bm =>
        {
            var name = InputDialog.Prompt(this, "Rename bookmark", "Bookmark name:", bm.Name, "Rename");
            if (name != null) { bm.Name = name; BookmarksChanged(); }
        };
        _bookmarkBar.MoveRequested += (bm, delta) =>
        {
            var list = _settings.Bookmarks;
            int i = list.IndexOf(bm), j = i + delta;
            if (i < 0 || j < 0 || j >= list.Count) return;
            (list[i], list[j]) = (list[j], list[i]);
            BookmarksChanged();
        };

        // Ivory margin around the cards.
        var stage = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Window, Padding = new Padding(12, 2, 12, 0) };
        stage.Controls.Add(_outer);
        Controls.Add(stage);
        Controls.Add(_bookmarkBar);
        Controls.Add(BuildFunctionBar());

        foreach (var pane in new[] { _left, _right })
        {
            pane.Activated += SetActive;
            pane.Changed += p =>
            {
                if (p == _active) { UpdateTitle(); RevealInTree(); }
                RevealInPaneTree(p);
            };
            pane.TabClosed += path => _closed.Push(path);
            pane.TreeButtonClicked += (_, button) => ShowTreeModeMenu(button);
        }
        _statusTimer.Tick += (_, _) =>
        {
            // Nothing to show while minimized; the next restore refreshes it.
            if (WindowState == FormWindowState.Minimized) return;
            _left.UpdateStatus();
            _right.UpdateStatus();
        };
        _idleTimer.Tick += (_, _) => UnloadIdleTabs();
    }

    // ───────────────────────────── folder trees ─────────────────────────────

    /// <summary>The pane a tree drives: the shared tree follows the active pane, a pane's own tree drives that pane.</summary>
    Pane TargetOf(FolderTree tree) => tree == _left.Tree ? _left : tree == _right.Tree ? _right : _active;

    IEnumerable<FolderTree> Trees()
    {
        yield return _tree;
        if (_left.Tree != null) yield return _left.Tree;
        if (_right.Tree != null) yield return _right.Tree;
    }

    FolderTree? TreeAt(IntPtr hwnd) => Trees().FirstOrDefault(t => Native.IsWithin(t, hwnd));

    void WireTree(FolderTree tree)
    {
        tree.FolderPicked += (item, path) =>
        {
            var pane = TargetOf(tree);
            if (_treePickToOther is { } route && route.Tree == tree)
            {
                _treePickToOther = null;
                if (Environment.TickCount64 <= route.Until)
                {
                    OpenInOtherPane(pane, path, route.AsTab);
                    return;
                }
            }
            var tab = pane.Current;
            if (tab == null || PathUtil.SamePath(tab.Path, path)) return;
            tree.Revealed = path;
            if (pane != _active) SetActive(pane);
            if (!tab.NavigateItem(item, path)) pane.ShowMessage("Couldn't open that location.");
        };
        tree.MenuExtras = (_, path) => TreeMenu(tree, path);
        tree.RenameRequested += RenamePath;
    }

    string TreeMode => _settings.TreeMode;

    internal Pane LeftPane => _left;
    internal Pane RightPane => _right;
    internal void SetTreeModeForTest(string mode) => SetTreeMode(mode);

    /// <summary>"left": one tree for both panes · "panes": a tree inside each pane · "none".</summary>
    void SetTreeMode(string mode)
    {
        SaveTreeWidths();
        _settings.TreeMode = mode;
        ApplyTreeMode();
        _settings.Save();
        _active.FocusView();
    }

    void ApplyTreeMode()
    {
        _settings.ShowTree = TreeMode == "left"; // keeps older versions' setting meaningful
        _outer.Panel1Collapsed = TreeMode != "left";
        foreach (var pane in new[] { _left, _right })
        {
            if (TreeMode == "panes")
            {
                if (pane.Tree == null)
                {
                    var tree = new FolderTree();
                    WireTree(tree);
                    pane.SetTree(tree, LogicalToDeviceUnits(_settings.PaneTreeWidth));
                }
            }
            else
            {
                pane.SetTree(null, 0); // frees the tree control
            }
        }
        if (TreeMode == "left") ApplyTreeWidth();
        foreach (var t in Trees()) t.Revealed = "";
        RevealInTree();
        RevealInPaneTree(_left);
        RevealInPaneTree(_right);
        ApplyRatio();
    }

    void SaveTreeWidths()
    {
        if (!_outer.Panel1Collapsed) _settings.TreeWidth = _outer.SplitterDistance;
        var paneTree = _active.TreeWidth;
        if (paneTree > 0) _settings.PaneTreeWidth = (int)Math.Round(paneTree * 96.0 / DeviceDpi);
    }

    void RevealInPaneTree(Pane pane)
    {
        var tree = pane.Tree;
        var path = pane.Current?.Path;
        if (tree == null || !tree.IsReady || string.IsNullOrEmpty(path) || PathUtil.SamePath(path, tree.Revealed)) return;
        tree.Revealed = path;
        tree.Reveal(path);
    }

    void ShowTreeModeMenu(Control anchor)
    {
        var menu = new ContextMenuStrip { Font = Theme.Ui };
        menu.Closed += (_, _) => BeginInvoke(() => menu.Dispose());
        AddTreeModeItems(menu.Items);
        menu.Show(anchor, new Point(0, anchor.Height));
    }

    void AddTreeModeItems(ToolStripItemCollection items)
    {
        items.Add(new ToolStripMenuItem("Folder tree on the left (follows the active pane)", null, (_, _) => SetTreeMode("left"))
            { Checked = TreeMode == "left", ShortcutKeyDisplayString = "Ctrl+B" });
        items.Add(new ToolStripMenuItem("Folder tree in each pane", null, (_, _) => SetTreeMode("panes"))
            { Checked = TreeMode == "panes", ShortcutKeyDisplayString = "Ctrl+Shift+B" });
        items.Add(new ToolStripMenuItem("No folder tree", null, (_, _) => SetTreeMode("none")) { Checked = TreeMode == "none" });
    }

    // ───────────────────────────── memory ─────────────────────────────

    /// <summary>Background tabs untouched for a while give their Explorer view back (they reload when shown).</summary>
    void UnloadIdleTabs()
    {
        int minutes = _settings.UnloadIdleTabsMinutes;
        if (minutes <= 0) return;
        long cutoff = Environment.TickCount64 - minutes * 60_000L;
        int unloaded = 0;
        foreach (var pane in new[] { _left, _right })
            foreach (var tab in pane.Tabs)
                if (tab != pane.Current && tab.IsCreated && tab.HiddenSince is long since && since < cutoff)
                {
                    tab.Unload();
                    unloaded++;
                }
        if (unloaded > 0) TrimMemory(workingSet: false);
    }

    [System.Runtime.InteropServices.DllImport("psapi.dll")] static extern bool EmptyWorkingSet(IntPtr process);

    /// <summary>
    /// Releases the COM wrappers of closed views and returns freed memory to Windows. The working set is only
    /// trimmed while minimized (trimming an active window would just cause page faults).
    /// </summary>
    static void TrimMemory(bool workingSet)
    {
        GC.Collect();
        GC.WaitForPendingFinalizers(); // RCW finalizers release the shell objects
        GC.Collect();
        if (workingSet)
        {
            try { EmptyWorkingSet(System.Diagnostics.Process.GetCurrentProcess().Handle); } catch { }
        }
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Theme.StyleFrame(this); // ivory title bar, gold hairline frame
    }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e); // DPI auto-scaling happens here, so restore pixel bounds afterwards
        RestoreWindowBounds();
        _outer.Panel1Collapsed = TreeMode != "left";
        ApplyTreeWidth();
        RestoreSession();
        if (TreeMode == "panes") ApplyTreeMode();
        Application.AddMessageFilter(this);
        _statusTimer.Start();
        _idleTimer.Start();
        _ = Ipc.ServeAsync(args => BeginInvoke(() => ReceiveArgs(args)), _cts.Token);
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        ApplyTreeWidth();
        ApplyRatio();
        foreach (var t in Trees()) t.Revealed = "";
        if (TreeMode == "panes")
        {
            _left.TreeWidth = LogicalToDeviceUnits(_settings.PaneTreeWidth);
            _right.TreeWidth = LogicalToDeviceUnits(_settings.PaneTreeWidth);
        }
        RevealInTree();
        RevealInPaneTree(_left);
        RevealInPaneTree(_right);
        _active.FocusView();
    }

    protected override void OnActivated(EventArgs e)
    {
        base.OnActivated(e);
        // Picked up edits made to settings.json in Notepad (colour rules, bookmarks, editor…).
        try
        {
            if (File.Exists(_settings.FilePath) && File.GetLastWriteTimeUtc(_settings.FilePath) > _settings.SavedUtc.AddSeconds(1))
                ReloadUserSettings();
        }
        catch
        {
            // Ignore; settings stay as they are.
        }
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        if (_split == null) return;
        if (WindowState != FormWindowState.Minimized)
        {
            _wasMinimized = false;
            ApplyRatio();
        }
        else if (!_wasMinimized)
        {
            _wasMinimized = true;
            BeginInvoke(() => TrimMemory(workingSet: true));
        }
    }

    bool _wasMinimized;

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        SaveSession();
        base.OnFormClosing(e);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        Application.RemoveMessageFilter(this);
        _statusTimer.Stop();
        _idleTimer.Stop();
        BrowserTab.MenuExtras = null;
        _cts.Cancel();
        base.OnFormClosed(e);
    }

    void RestoreWindowBounds()
    {
        var s = _settings.Session;
        if (s.Bounds is { Length: 4 } b)
        {
            var rect = new Rectangle(b[0], b[1], b[2], b[3]);
            if (rect.Width >= 400 && rect.Height >= 300 && Screen.AllScreens.Any(sc => sc.WorkingArea.IntersectsWith(rect)))
                Bounds = rect;
        }
        if (s.Maximized) WindowState = FormWindowState.Maximized;
    }

    void RestoreSession()
    {
        var s = _settings.Session;
        bool restore = _settings.RestoreSession;
        var left = restore && s.Left.Count > 0 ? s.Left : new List<string> { PathUtil.ThisPc };
        var right = restore && s.Right.Count > 0 ? s.Right : new List<string> { Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) };

        Fill(_left, left, restore ? s.LeftIndex : 0);
        Fill(_right, right, restore ? s.RightIndex : 0);
        _ratio = Math.Clamp(double.IsFinite(s.SplitRatio) ? s.SplitRatio : 0.5, 0.15, 0.85);
        SetActive(restore && s.RightActive ? _right : _left);
        OpenPaths(_startupPaths);

        static void Fill(Pane pane, List<string> paths, int index)
        {
            foreach (var p in paths) pane.AddTab(p, select: false);
            pane.Select(Math.Clamp(index, 0, pane.Tabs.Count - 1));
        }
    }

    void SaveSession()
    {
        var s = _settings.Session;
        s.Left = _left.Tabs.Select(t => t.Path).ToList();
        s.Right = _right.Tabs.Select(t => t.Path).ToList();
        s.LeftIndex = _left.CurrentIndex;
        s.RightIndex = _right.CurrentIndex;
        s.RightActive = _active == _right;
        s.SplitRatio = _ratio;
        SaveTreeWidths();
        var b =WindowState == FormWindowState.Normal ? Bounds : RestoreBounds;
        s.Bounds = new[] { b.X, b.Y, b.Width, b.Height };
        s.Maximized = WindowState == FormWindowState.Maximized;
        _settings.Save();
    }

    void ReceiveArgs(string[] args)
    {
        OpenPaths(CommandLine.Parse(args).Paths);
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }

    public void OpenPaths(IEnumerable<string> paths)
    {
        bool any = false;
        foreach (var raw in paths)
        {
            var path = File.Exists(raw) ? Path.GetDirectoryName(raw) ?? raw : raw;
            _active.AddTab(path);
            any = true;
        }
        if (any) _active.FocusView();
    }

    // ───────────────────────────── panes ─────────────────────────────

    Pane Other(Pane p) => p == _left ? _right : _left;

    void SetActive(Pane pane)
    {
        _active = pane;
        _left.IsActive = pane == _left;
        _right.IsActive = pane == _right;
        UpdateTitle();
        RevealInTree();
    }

    /// <summary>Keep the tree's highlight on the active pane's folder.</summary>
    void RevealInTree()
    {
        var path = _active.Current?.Path;
        if (_outer.Panel1Collapsed || !_tree.IsReady || string.IsNullOrEmpty(path) || PathUtil.SamePath(path, _tree.Revealed)) return;
        _tree.Revealed = path;
        _tree.Reveal(path);
    }

    void ToggleTree() => SetTreeMode(TreeMode == "left" ? "none" : "left");

    void ApplyTreeWidth()
    {
        if (_outer.Panel1Collapsed || _outer.Width <= 0) return;
        int max = Math.Max(_outer.Panel1MinSize, _outer.Width / 2);
        _outer.SplitterDistance = Math.Clamp(_settings.TreeWidth, Math.Min(140, max), max);
    }

    void UpdateTitle()
    {
        var title = _active.Current?.Title;
        Text = string.IsNullOrEmpty(title) ? "ExplorerDuo" : $"{title} — ExplorerDuo";
    }

    double CurrentRatio()
    {
        int usable = _split.Width - _split.SplitterWidth;
        return usable <= 0 ? 0.5 : Math.Clamp((double)_split.SplitterDistance / usable, 0.15, 0.85);
    }

    void ApplyRatio()
    {
        int usable = _split.Width - _split.SplitterWidth;
        if (usable <= 0) return;
        int d = (int)Math.Round(usable * _ratio);
        d = Math.Clamp(d, _split.Panel1MinSize, Math.Max(_split.Panel1MinSize, usable - _split.Panel2MinSize));
        if (_split.SplitterDistance != d) _split.SplitterDistance = d;
    }

    Pane? PaneOf(IntPtr hwnd) =>
        Native.IsWithin(_left, hwnd) ? _left : Native.IsWithin(_right, hwnd) ? _right : null;

    // ───────────────────────────── keyboard ─────────────────────────────

    public bool PreFilterMessage(ref Message m)
    {
        if (m.Msg == Native.WM_MBUTTONDOWN && HandleMiddleClick(m.HWnd, Native.PointFromLParam(m.LParam)))
            return true;
        if (m.Msg is Native.WM_LBUTTONDOWN or Native.WM_RBUTTONDOWN or Native.WM_MBUTTONDOWN)
        {
            var clicked = PaneOf(m.HWnd);
            if (clicked != null && clicked != _active) SetActive(clicked);
            return false;
        }
        if (m.Msg is not (Native.WM_KEYDOWN or Native.WM_SYSKEYDOWN)) return false;
        if (!Native.IsWithin(this, m.HWnd)) return false; // dialogs, menus, other windows

        var keyCode = ((Keys)(int)m.WParam & Keys.KeyCode) | ModifierKeys;
        if (keyCode == Keys.Tab && TreeAt(m.HWnd) is { } fromTree)
        {
            var target = TargetOf(fromTree);
            if (target != _active) SetActive(target);
            target.FocusView(); // Tab from a tree jumps into the pane it drives
            return true;
        }

        var pane = PaneOf(m.HWnd);
        if (pane != null && pane != _active) SetActive(pane);

        var tab = pane?.Current;
        bool inBrowser = tab != null && Native.IsWithin(tab.Host, m.HWnd);
        bool inView = inBrowser && Native.ClassName(m.HWnd) != "Edit"; // not while renaming a file

        var keyData = ((Keys)(int)m.WParam & Keys.KeyCode) | ModifierKeys;
        try
        {
            if (HandleShortcut(keyData, inView)) return true;
        }
        catch (Exception ex)
        {
            Log.Write("shortcut failed: " + ex);
            _active.ShowMessage(ex.Message);
            return true;
        }

        if (inBrowser)
        {
            var msg = new MSG { hwnd = m.HWnd, message = (uint)m.Msg, wParam = m.WParam, lParam = m.LParam };
            if (tab!.TranslateAccelerator(ref msg)) return true;
        }
        return false;
    }

    bool HandleShortcut(Keys keyData, bool inView)
    {
        var current = _active.Current;
        switch (keyData)
        {
            case Keys.Tab when inView:
                SwitchPane();
                return true;
            case Keys.Back when inView:
                current?.Back();
                return true;
            case Keys.Control | Keys.Enter when inView:
                OpenSelectionAsTabs(_active);
                return true;
            case Keys.Control | Keys.Shift | Keys.Enter when inView:
                OpenSelectionAsTabs(Other(_active));
                return true;

            case Keys.Control | Keys.T: NewTab(); return true;
            case Keys.Control | Keys.W:
            case Keys.Control | Keys.F4:
                _active.CloseTab(_active.CurrentIndex);
                _active.FocusView();
                return true;
            case Keys.Control | Keys.Shift | Keys.T: ReopenClosed(); return true;
            case Keys.Control | Keys.Tab: _active.CycleTab(1); _active.FocusView(); return true;
            case Keys.Control | Keys.Shift | Keys.Tab: _active.CycleTab(-1); _active.FocusView(); return true;
            case Keys.Add when inView: SelectByPattern(select: true); return true;
            case Keys.Subtract when inView: SelectByPattern(select: false); return true;
            case Keys.Multiply when inView: current?.InvertSelection(); return true;

            case Keys.Control | Keys.L:
            case Keys.Alt | Keys.D:
                _active.FocusAddress();
                return true;
            case Keys.F4: EditSelected(); return true;
            case Keys.F9: OpenTerminal(); return true;
            case Keys.Alt | Keys.F5: Pack(); return true;
            case Keys.Alt | Keys.F9: Unpack(); return true;
            case Keys.Control | Keys.M: MultiRename(); return true;
            case Keys.Control | Keys.F: Search(); return true;
            case Keys.Control | Keys.H: ShowHighlightMenu(Cursor.Position); return true;
            case Keys.Control | Keys.D: AddBookmark(); return true;
            case Keys.Control | Keys.Shift | Keys.C: CopyPaths(); return true;
            case Keys.Alt | Keys.Left: current?.Back(); return true;
            case Keys.Alt | Keys.Right: current?.Forward(); return true;
            case Keys.Alt | Keys.Up: current?.Up(); return true;
            case Keys.Control | Keys.R: current?.Refresh(); return true;
            case Keys.F5: Transfer(FileOp.Copy); return true;
            case Keys.F6: Transfer(FileOp.Move); return true;
            case Keys.F7: NewFolder(); return true;
            case Keys.F8: Transfer(FileOp.Delete); return true;
            case Keys.Control | Keys.E: Mirror(); return true;
            case Keys.Control | Keys.U: SwapFolders(); return true;
            case Keys.Control | Keys.B: ToggleTree(); return true;
            case Keys.Control | Keys.Shift | Keys.B: SetTreeMode(TreeMode == "panes" ? "none" : "panes"); return true;
        }

        var key = keyData & Keys.KeyCode;
        if ((keyData & Keys.Modifiers) == Keys.Alt && key is >= Keys.D1 and <= Keys.D9)
        {
            int n = key - Keys.D1;
            if (n < _settings.Bookmarks.Count) OpenBookmark(_settings.Bookmarks[n], OpenTarget.ActivePane);
            return true;
        }
        if ((keyData & Keys.Modifiers) == Keys.Control && key is >= Keys.D1 and <= Keys.D9)
        {
            int n = _active.Tabs.Count;
            _active.Select(key == Keys.D9 ? n - 1 : Math.Min(key - Keys.D1, n - 1));
            _active.FocusView();
            return true;
        }
        return false;
    }

    // ───────────────────────────── commands ─────────────────────────────

    void SwitchPane()
    {
        var other = Other(_active);
        SetActive(other);
        other.FocusView();
    }

    void NewTab()
    {
        _active.AddTab(_active.Current?.Path ?? PathUtil.ThisPc);
        _active.FocusView();
    }

    void ReopenClosed()
    {
        if (_closed.Count == 0) return;
        _active.AddTab(_closed.Pop());
        _active.FocusView();
    }

    void Mirror()
    {
        var path = _active.Current?.Path;
        if (path != null) Other(_active).Current?.Navigate(path);
    }

    void SwapFolders()
    {
        var a = _left.Current;
        var b = _right.Current;
        if (a == null || b == null) return;
        var (pa, pb) = (a.Path, b.Path);
        a.Navigate(pb);
        b.Navigate(pa);
    }

    void OpenSelectionAsTabs(Pane target)
    {
        var folders = _active.Current?.Selection().Where(s => s.IsFolder).ToList() ?? new();
        if (folders.Count == 0)
        {
            _active.ShowMessage("Select one or more folders first.");
            return;
        }
        foreach (var f in folders) target.AddTab(f.ParsingName, select: false);
        _active.ShowMessage(
            $"Opened {folders.Count} folder{(folders.Count == 1 ? "" : "s")} in new tabs" + (target == _active ? "." : " in the other pane."),
            warning: false);
    }

    void Transfer(FileOp op) => Transfer(op, op == FileOp.Delete ? null : Other(_active).Current?.Path, "The other pane");

    /// <summary>Copies / moves / recycles the active pane's selection (into <paramref name="destination"/>).</summary>
    void Transfer(FileOp op, string? destination, string destinationName)
    {
        var src = _active.Current;
        if (src == null) return;
        if (src.Counts().Selected == 0)
        {
            _active.ShowMessage("Select files or folders first.");
            return;
        }

        if (op != FileOp.Delete)
        {
            if (destination == null || !PathUtil.IsFileSystemPath(destination))
            {
                _active.ShowMessage($"{destinationName} isn't a regular folder.");
                return;
            }
            if (PathUtil.SamePath(src.Path, destination))
            {
                _active.ShowMessage("The items are already in that folder.");
                return;
            }
        }

        var pane = _active;
        var sources = src.SelectionIdLists(); // straight from the view: no per-item path parsing
        if (sources.Count == 0) return;
        _ = Run();

        async Task Run()
        {
            try
            {
                await FileTransfer.RunAsync(sources, destination, op, Handle);
            }
            catch (Exception ex)
            {
                Log.Write($"{op} failed: {ex}");
                pane.ShowMessage($"{op} failed: {ex.Message}");
            }
        }
    }

    void NewFolder() => NewFolderIn(_active.Current?.Path);

    void NewFolderIn(string? parent)
    {
        if (parent == null || !PathUtil.IsFileSystemPath(parent))
        {
            _active.ShowMessage("New folders can only be created inside a regular folder.");
            return;
        }
        var name = InputDialog.Prompt(this, "New folder", $"Create a folder in {parent}:", "New folder", "Create");
        if (name == null) { _active.FocusView(); return; }
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        {
            _active.ShowMessage("A folder name can't contain \\ / : * ? \" < > |");
            return;
        }
        try
        {
            Directory.CreateDirectory(Path.Combine(parent, name));
        }
        catch (Exception ex)
        {
            _active.ShowMessage(ex.Message);
        }
        _active.FocusView();
    }

    /// <summary>Rename a folder from the tree's menu (through the copy engine, so Ctrl+Z works).</summary>
    void RenamePath(string path)
    {
        var normalized = Highlighter.Normalize(path);
        var oldName = Path.GetFileName(normalized);
        if (!PathUtil.IsFileSystemPath(path) || string.IsNullOrEmpty(oldName))
        {
            _active.ShowMessage("This item can't be renamed here.");
            return;
        }
        var name = InputDialog.Prompt(this, "Rename", $"New name for \"{oldName}\":", oldName, "Rename");
        if (name == null || name == oldName) return;
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        {
            _active.ShowMessage("A name can't contain \\ / : * ? \" < > |");
            return;
        }
        try
        {
            FileTransfer.Rename(path, name, Handle);
        }
        catch (System.Runtime.InteropServices.COMException ex) when (ex.HResult == unchecked((int)0x800704C7))
        {
            // Cancelled by the user.
        }
        catch (Exception ex)
        {
            _active.ShowMessage(ex.Message);
        }
    }

    // ───────────────────────────── right-click menus ─────────────────────────────

    /// <summary>ExplorerDuo's part of the file list menu; the complete Windows menu follows below it.</summary>
    List<MenuCommand> ListMenu(BrowserTab tab, bool onItems)
    {
        var pane = _left.Tabs.Contains(tab) ? _left : _right;
        if (pane != _active) SetActive(pane);
        var other = Other(pane);
        bool otherIsFolder = other.Current != null && PathUtil.IsFileSystemPath(other.Current.Path)
                             && !PathUtil.SamePath(other.Current.Path, tab.Path);
        var list = new List<MenuCommand>();

        if (!onItems)
        {
            list.Add(new MenuCommand("Show this folder in the other pane", Mirror, "Ctrl+E"));
            list.Add(new MenuCommand("Open in new tab", NewTab, "Ctrl+T"));
            list.Add(new MenuCommand("New folder…", NewFolder, "F7", PathUtil.IsFileSystemPath(tab.Path)));
            list.Add(new MenuCommand("Open terminal here", OpenTerminal, "F9", PathUtil.IsFileSystemPath(tab.Path)));
            list.Add(MenuCommand.Separator);
            list.Add(new MenuCommand("Select by pattern…", () => SelectByPattern(select: true), "Num +"));
            list.Add(new MenuCommand("Invert selection", () => tab.InvertSelection(), "Num *"));
            list.Add(new MenuCommand("Find files…", Search, "Ctrl+F"));
            list.Add(MenuCommand.Separator);
            list.Add(new MenuCommand("Copy folder path", CopyPaths, "Ctrl+Shift+C"));
            list.Add(new MenuCommand("Bookmark this folder", AddBookmark, "Ctrl+D"));
            list.Add(new MenuCommand("Highlight this folder", null) { Children = HighlightCommands(new List<string> { tab.Path }) });
            return list;
        }

        var selection = tab.Selection();
        var folders = selection.Where(s => s.IsFolder).ToList();
        bool anyFiles = selection.Any(s => !s.IsFolder && s.IsFileSystem);
        bool anyZips = selection.Any(s => s.IsFileSystem && s.ParsingName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase));

        if (folders.Count == 1 && selection.Count == 1)
        {
            var folder = folders[0].ParsingName;
            list.Add(new MenuCommand("Open in the other pane", () => OpenInOtherPane(pane, folder, asTab: false), "Middle-click"));
            list.Add(new MenuCommand("Open in new tab", () => pane.AddTab(folder), "Ctrl+Enter"));
            list.Add(new MenuCommand("Open in new tab in the other pane", () => other.AddTab(folder, select: false), "Ctrl+Shift+Enter"));
            list.Add(MenuCommand.Separator);
        }
        else if (folders.Count > 1)
        {
            list.Add(new MenuCommand($"Open {folders.Count} folders as new tabs", () => OpenSelectionAsTabs(pane), "Ctrl+Enter"));
            list.Add(new MenuCommand($"Open {folders.Count} folders as tabs in the other pane", () => OpenSelectionAsTabs(other), "Ctrl+Shift+Enter"));
            list.Add(MenuCommand.Separator);
        }

        string what = selection.Count == 1 ? "" : $" {selection.Count} items";
        list.Add(new MenuCommand($"Copy{what} to the other pane", () => Transfer(FileOp.Copy), "F5", otherIsFolder));
        list.Add(new MenuCommand($"Move{what} to the other pane", () => Transfer(FileOp.Move), "F6", otherIsFolder));
        list.Add(new MenuCommand("Zip to the other pane…", Pack, "Alt+F5", otherIsFolder));
        if (anyZips) list.Add(new MenuCommand("Unzip to the other pane", Unpack, "Alt+F9", otherIsFolder));
        list.Add(MenuCommand.Separator);
        if (anyFiles) list.Add(new MenuCommand("Edit", EditSelected, "F4"));
        list.Add(new MenuCommand("Multi-Rename…", MultiRename, "Ctrl+M"));
        list.Add(new MenuCommand(selection.Count == 1 ? "Copy full path" : "Copy full paths", CopyPaths, "Ctrl+Shift+C"));
        list.Add(new MenuCommand("Highlight", null) { Children = HighlightCommands(selection.Select(s => s.ParsingName).ToList()) });
        return list;
    }

    /// <summary>ExplorerDuo's part of a tree folder's menu.</summary>
    List<MenuCommand> TreeMenu(FolderTree tree, string path)
    {
        var target = TargetOf(tree);
        var other = Other(target);
        bool isFolder = PathUtil.IsFileSystemPath(path);
        var list = new List<MenuCommand>
        {
            new("Open in the other pane", () => OpenInOtherPane(target, path, asTab: false), "Middle-click"),
            new("Open in new tab", () => { target.AddTab(path); target.FocusView(); }),
            new("Open in new tab in the other pane", () => other.AddTab(path, select: false)),
            MenuCommand.Separator,
        };

        // Commander-style: send the active pane's selection straight into this folder.
        var source = _active.Current;
        int selected = source?.Counts().Selected ?? 0;
        if (selected > 0 && isFolder && source != null && !PathUtil.SamePath(source.Path, path))
        {
            var items = selected == 1 ? "the selected item" : $"{selected} selected items";
            list.Add(new MenuCommand($"Copy {items} here", () => Transfer(FileOp.Copy, path, "That folder")));
            list.Add(new MenuCommand($"Move {items} here", () => Transfer(FileOp.Move, path, "That folder")));
            list.Add(MenuCommand.Separator);
        }

        list.Add(new MenuCommand("New folder…", () => NewFolderIn(path), null, isFolder));
        list.Add(new MenuCommand("Rename…", () => RenamePath(path), null, isFolder && Path.GetFileName(Highlighter.Normalize(path)).Length > 0));
        list.Add(new MenuCommand("Open terminal here", () => OpenTerminalIn(path), null, isFolder));
        list.Add(new MenuCommand("Copy path", () =>
        {
            Clipboard.SetText(path);
            _active.ShowMessage($"Copied: {path}", warning: false);
        }));
        list.Add(new MenuCommand("Bookmark", () => AddBookmark(path, PathUtil.ShortTitle(path))));
        list.Add(new MenuCommand("Highlight", null) { Children = HighlightCommands(new List<string> { path }) });
        return list;
    }

    List<MenuCommand> HighlightCommands(List<string> targets)
    {
        var list = Palette.Colors.Select(c => new MenuCommand(c.Name, () => SetHighlight(targets, c.Hex))).ToList();
        list.Add(MenuCommand.Separator);
        list.Add(new MenuCommand("Remove highlight", () => SetHighlight(targets, null), null,
            targets.Any(t => _settings.Highlights.ContainsKey(Highlighter.Normalize(t)))));
        list.Add(new MenuCommand("Edit colour rules…", EditSettingsFile));
        return list;
    }

    // ───────────────────────────── Multi Commander-style commands ─────────────────────────────

    /// <summary>Middle-click on a folder (file list or tree) opens it in the opposite pane. Returns true if handled.</summary>
    bool HandleMiddleClick(IntPtr hwnd, Point client)
    {
        if (!Native.IsWithin(this, hwnd)) return false;
        bool asTab = (ModifierKeys & Keys.Shift) != 0;

        if (TreeAt(hwnd) is { } tree)
        {
            // Select the item under the mouse; the resulting pick is routed to the other pane.
            _treePickToOther = (tree, Environment.TickCount64 + 1500, asTab);
            if (!tree.SelectAt(hwnd, client, out var alreadySelected)) _treePickToOther = null;
            else if (alreadySelected != null)
            {
                _treePickToOther = null;
                OpenInOtherPane(TargetOf(tree), alreadySelected, asTab);
            }
            return true;
        }

        var pane = PaneOf(hwnd);
        var tab = pane?.Current;
        if (pane == null || tab == null || tab.ListHandle == IntPtr.Zero || hwnd != tab.ListHandle) return false;
        if (pane != _active) SetActive(pane);

        var entry = tab.HitTest(client);
        if (entry == null) OpenInOtherPane(pane, tab.Path, asTab);                  // empty space: same folder over there
        else if (entry.IsFolder) OpenInOtherPane(pane, entry.ParsingName, asTab);
        else pane.ShowMessage("Middle-click opens folders in the other pane.", warning: false);
        return true;
    }

    void OpenInOtherPane(Pane from, string path, bool asTab)
    {
        var other = Other(from);
        if (asTab) other.AddTab(path);
        else if (other.Current?.Navigate(path) != true) { from.ShowMessage("Couldn't open that location."); return; }
    }

    void AddBookmark()
    {
        var tab = _active.Current;
        if (tab != null) AddBookmark(tab.Path, tab.Title);
    }

    void AddBookmark(string path, string title)
    {
        if (_settings.Bookmarks.Any(b => PathUtil.SamePath(b.Path, path)))
        {
            _active.ShowMessage("This folder is already bookmarked.", warning: false);
            return;
        }
        _settings.Bookmarks.Add(new Bookmark { Name = title, Path = path });
        BookmarksChanged();
        _active.ShowMessage($"Bookmarked \"{title}\" (Alt+{Math.Min(_settings.Bookmarks.Count, 9)}).", warning: false);
    }

    void OpenBookmark(Bookmark bm, OpenTarget target)
    {
        switch (target)
        {
            case OpenTarget.NewTab: _active.AddTab(bm.Path); break;
            case OpenTarget.OtherPane: OpenInOtherPane(_active, bm.Path, asTab: false); break;
            default:
                if (_active.Current?.Navigate(bm.Path) != true) _active.ShowMessage($"Can't open \"{bm.Path}\".");
                break;
        }
        _active.FocusView();
    }

    void BookmarksChanged()
    {
        _bookmarkBar.SetBookmarks(_settings.Bookmarks);
        _settings.Save();
    }

    void RebuildHighlighter()
    {
        Highlights.Current = new Highlighter(_settings.Highlights, _settings.ColorRules, _settings.HiddenColor);
        foreach (var tab in _left.Tabs.Concat(_right.Tabs)) tab.Repaint();
        foreach (var tree in Trees()) tree.Repaint();
    }

    void ReloadUserSettings()
    {
        var fresh = Settings.Load(_settings.FilePath);
        _settings.Bookmarks = fresh.Bookmarks;
        _settings.Highlights = fresh.Highlights;
        _settings.ColorRules = fresh.ColorRules;
        _settings.HiddenColor = fresh.HiddenColor;
        _settings.Editor = fresh.Editor;
        _settings.Save();
        _bookmarkBar.SetBookmarks(_settings.Bookmarks);
        RebuildHighlighter();
        _active.ShowMessage("Settings reloaded.", warning: false);
    }

    /// <summary>Paths the highlight command applies to: the selection, or the current folder if nothing is selected.</summary>
    List<string> HighlightTargets()
    {
        var tab = _active.Current;
        if (tab == null) return new();
        var selected = tab.Selection().Select(s => s.ParsingName).ToList();
        return selected.Count > 0 ? selected : new List<string> { tab.Path };
    }

    void ShowHighlightMenu(Point screen)
    {
        var menu = new ContextMenuStrip { Font = Theme.Ui };
        AddHighlightItems(menu.Items);
        menu.Closed += (_, _) => BeginInvoke(() => { menu.Dispose(); _active.FocusView(); });
        menu.Show(screen);
    }

    void AddHighlightItems(ToolStripItemCollection items)
    {
        var targets = HighlightTargets();
        var what = targets.Count == 1 ? $"\"{PathUtil.ShortTitle(targets[0])}\"" : $"{targets.Count} items";
        items.Add(new ToolStripMenuItem($"Highlight {what}") { Enabled = false });
        foreach (var (name, hex) in Palette.Colors)
        {
            Highlighter.TryParseColor(hex, out var color);
            var swatch = new Bitmap(16, 16);
            using (var g = Graphics.FromImage(swatch))
            using (var brush = new SolidBrush(color))
                g.FillRectangle(brush, 2, 2, 12, 12);
            items.Add(new ToolStripMenuItem(name, swatch, (_, _) => SetHighlight(targets, hex)) { ForeColor = color });
        }
        items.Add(new ToolStripSeparator());
        items.Add(new ToolStripMenuItem("Remove highlight", null, (_, _) => SetHighlight(targets, null))
            { Enabled = targets.Any(t => _settings.Highlights.ContainsKey(Highlighter.Normalize(t))) });
        items.Add(new ToolStripMenuItem("Edit colour rules…", null, (_, _) => EditSettingsFile()));
    }

    void SetHighlight(IEnumerable<string> paths, string? hex)
    {
        foreach (var p in paths)
        {
            var key = Highlighter.Normalize(p);
            if (hex == null) _settings.Highlights.Remove(key);
            else _settings.Highlights[key] = hex;
        }
        _settings.Save();
        RebuildHighlighter();
    }

    void EditSettingsFile()
    {
        _settings.Save();
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("notepad.exe", $"\"{_settings.FilePath}\"") { UseShellExecute = false });
            _active.ShowMessage("Edit colorRules / bookmarks / editor, save, then come back — changes load automatically.", warning: false);
        }
        catch (Exception ex)
        {
            _active.ShowMessage(ex.Message);
        }
    }

    void SelectByPattern(bool select)
    {
        var tab = _active.Current;
        if (tab == null) return;
        var pattern = InputDialog.Prompt(this, select ? "Select" : "Deselect",
            $"{(select ? "Select" : "Deselect")} items matching (wildcards, ';' between patterns):", "*.*", select ? "Select" : "Deselect");
        if (pattern != null)
        {
            int n = tab.SelectByPattern(pattern, select);
            _active.ShowMessage($"{n} item{(n == 1 ? "" : "s")} {(select ? "selected" : "deselected")}.", warning: false);
        }
        _active.FocusView();
    }

    void CopyPaths()
    {
        var tab = _active.Current;
        if (tab == null) return;
        var paths = tab.Selection().Select(s => s.ParsingName).ToList();
        if (paths.Count == 0) paths.Add(tab.Path);
        Clipboard.SetText(string.Join(Environment.NewLine, paths));
        _active.ShowMessage(paths.Count == 1 ? $"Copied: {paths[0]}" : $"Copied {paths.Count} paths.", warning: false);
    }

    void OpenTerminal() => OpenTerminalIn(_active.Current?.Path);

    void OpenTerminalIn(string? path)
    {
        if (!PathUtil.IsFileSystemPath(path)) { _active.ShowMessage("A terminal can only open in a regular folder."); return; }
        try
        {
            // A trailing backslash would escape the closing quote; "C:\." keeps drive roots valid.
            var dir = path!.TrimEnd('\\');
            if (dir.Length == 2) dir += @"\.";
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("wt.exe", $"-d \"{dir}\"") { UseShellExecute = true });
        }
        catch
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("cmd.exe") { WorkingDirectory = path, UseShellExecute = true });
        }
    }

    void EditSelected()
    {
        var files = _active.Current?.Selection().Where(s => s.IsFileSystem && !s.IsFolder && File.Exists(s.ParsingName)).ToList() ?? new();
        if (files.Count == 0) { _active.ShowMessage("Select one or more files to edit."); return; }
        foreach (var f in files.Take(10))
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(_settings.Editor, $"\"{f.ParsingName}\"") { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                _active.ShowMessage($"Couldn't start editor \"{_settings.Editor}\": {ex.Message}");
                return;
            }
        }
    }

    void Search()
    {
        if (_find == null || _find.IsDisposed)
        {
            _find = new FindFilesDialog();
            _find.ResultActivated += (item, target) => ShowFound(item, target);
        }
        var path = _active.Current?.Path;
        _find.Prepare(PathUtil.IsFileSystemPath(path) ? path! : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile));
        if (!_find.Visible) _find.Show(this);
        _find.Activate();
    }

    /// <summary>Opens a search result: folders are entered, files are shown selected in their folder.</summary>
    void ShowFound(FoundItem item, OpenTarget target)
    {
        var pane = target == OpenTarget.OtherPane ? Other(_active) : _active;
        var tab = pane.Current;
        if (tab == null) return;
        var folder = item.IsFolder ? item.Path : Path.GetDirectoryName(item.Path) ?? item.Path;
        if (!tab.Navigate(folder)) { pane.ShowMessage("Couldn't open that folder."); return; }
        if (!item.IsFolder) tab.SelectWhenLoaded(item.Path);
        SetActive(pane);
        Activate();
        pane.FocusView();
    }

    void MultiRename()
    {
        var items = _active.Current?.Selection().Where(s => s.IsFileSystem).Select(s => s.ParsingName).ToList() ?? new();
        if (items.Count == 0) { _active.ShowMessage("Select the files or folders to rename."); return; }
        using var dialog = new MultiRenameDialog(items);
        if (dialog.ShowDialog(this) == DialogResult.OK) _active.ShowMessage($"Renamed {items.Count} item(s).", warning: false);
        _active.FocusView();
    }

    void Pack()
    {
        var src = _active.Current;
        var dst = Other(_active).Current;
        var items = src?.Selection().Where(s => s.IsFileSystem).Select(s => s.ParsingName).ToList() ?? new();
        if (src == null || items.Count == 0) { _active.ShowMessage("Select files or folders to zip."); return; }
        if (dst == null || !PathUtil.IsFileSystemPath(dst.Path)) { _active.ShowMessage("The other pane isn't showing a regular folder."); return; }

        var suggested = (items.Count == 1 ? Path.GetFileNameWithoutExtension(Highlighter.Normalize(items[0])) : src.Title) + ".zip";
        var name = InputDialog.Prompt(this, "Zip", $"Create archive in {dst.Path}:", suggested, "Zip");
        if (name == null) { _active.FocusView(); return; }
        if (!name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) name += ".zip";
        var zipPath = Path.Combine(dst.Path, name);
        RunBackground($"Zipping {items.Count} item(s)…", () => Archiver.Pack(items, zipPath), $"Created {name}.");
    }

    void Unpack()
    {
        var dst = Other(_active).Current;
        var zips = _active.Current?.Selection()
            .Where(s => s.IsFileSystem && s.ParsingName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)).Select(s => s.ParsingName).ToList() ?? new();
        if (zips.Count == 0) { _active.ShowMessage("Select one or more .zip files."); return; }
        if (dst == null || !PathUtil.IsFileSystemPath(dst.Path)) { _active.ShowMessage("The other pane isn't showing a regular folder."); return; }
        var target = dst.Path;
        RunBackground($"Extracting {zips.Count} archive(s)…", () => { foreach (var z in zips) Archiver.Unpack(z, target); },
            $"Extracted {zips.Count} archive(s) into {target}.");
    }

    void RunBackground(string busy, Action work, string done)
    {
        var pane = _active;
        pane.ShowMessage(busy, warning: false);
        _ = Run();

        async Task Run()
        {
            try
            {
                await Task.Run(work);
                pane.ShowMessage(done, warning: false);
            }
            catch (Exception ex)
            {
                Log.Write($"{busy} failed: {ex}");
                pane.ShowMessage(ex.Message);
            }
        }
    }

    // ───────────────────────────── chrome ─────────────────────────────

    Control BuildFunctionBar()
    {
        var bar = new Panel { Dock = DockStyle.Bottom, Height = 46, BackColor = Theme.Window, Padding = new Padding(8, 0, 12, 0) };
        var flow = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, WrapContents = false, AutoScroll = false,
            Padding = new Padding(0, 8, 0, 6), BackColor = Theme.Window,
        };
        flow.Controls.Add(Fn("Tab", "Switch", SwitchPane));
        flow.Controls.Add(Fn("F4", "Edit", EditSelected));
        flow.Controls.Add(Fn("F5", "Copy", () => Transfer(FileOp.Copy)));
        flow.Controls.Add(Fn("F6", "Move", () => Transfer(FileOp.Move)));
        flow.Controls.Add(Fn("F7", "New folder", NewFolder));
        flow.Controls.Add(Fn("F8", "Delete", () => Transfer(FileOp.Delete)));
        flow.Controls.Add(Fn("F9", "Terminal", OpenTerminal));
        flow.Controls.Add(Fn("Ctrl+M", "Rename", MultiRename));
        flow.Controls.Add(Fn("Alt+F5", "Zip", Pack));
        flow.Controls.Add(Fn("Ctrl+F", "Search", Search));
        var highlight = Fn("Ctrl+H", "Highlight", () => { });
        highlight.Click += (_, _) => ShowHighlightMenu(highlight.PointToScreen(new Point(0, 0)));
        flow.Controls.Add(highlight);

        var more = new FlatButton { Text = Theme.GlyphMore, Font = Theme.Icons(11f), Dock = DockStyle.Right, Width = 44 };
        new ToolTip().SetToolTip(more, "More");
        more.Click += (_, _) => BuildMoreMenu().Show(more, new Point(more.Width, 0), ToolStripDropDownDirection.AboveLeft);

        bar.Controls.Add(flow);
        bar.Controls.Add(more);
        return bar;
    }

    FnButton Fn(string key, string label, Action action)
    {
        var b = new FnButton(key, label);
        b.Click += (_, _) => { action(); _active.FocusView(); };
        return b;
    }

    ContextMenuStrip BuildMoreMenu()
    {
        var menu = new ContextMenuStrip { Font = Theme.Ui };
        menu.Closed += (_, _) => BeginInvoke(() => { menu.Dispose(); _active.FocusView(); });

        menu.Items.Add(new ToolStripMenuItem("New tab", null, (_, _) => NewTab()) { ShortcutKeyDisplayString = "Ctrl+T" });
        menu.Items.Add(new ToolStripMenuItem("Reopen closed tab", null, (_, _) => ReopenClosed())
            { ShortcutKeyDisplayString = "Ctrl+Shift+T", Enabled = _closed.Count > 0 });
        menu.Items.Add(new ToolStripSeparator());

        var bookmarks = new ToolStripMenuItem("Bookmarks");
        bookmarks.DropDownItems.Add(new ToolStripMenuItem("Bookmark this folder", null, (_, _) => AddBookmark()) { ShortcutKeyDisplayString = "Ctrl+D" });
        if (_settings.Bookmarks.Count > 0) bookmarks.DropDownItems.Add(new ToolStripSeparator());
        for (int i = 0; i < _settings.Bookmarks.Count; i++)
        {
            var bm = _settings.Bookmarks[i];
            bookmarks.DropDownItems.Add(new ToolStripMenuItem(bm.Name, null, (_, _) => OpenBookmark(bm, OpenTarget.ActivePane))
                { ShortcutKeyDisplayString = i < 9 ? $"Alt+{i + 1}" : "", ToolTipText = bm.Path });
        }
        menu.Items.Add(bookmarks);

        var highlight = new ToolStripMenuItem("Highlight") { ShortcutKeyDisplayString = "Ctrl+H" };
        AddHighlightItems(highlight.DropDownItems);
        menu.Items.Add(highlight);
        menu.Items.Add(new ToolStripSeparator());

        var trees = new ToolStripMenuItem("Folder tree");
        AddTreeModeItems(trees.DropDownItems);
        menu.Items.Add(trees);
        menu.Items.Add(new ToolStripMenuItem($"Unload tabs unused for {Math.Max(_settings.UnloadIdleTabsMinutes, 10)} min (saves memory)", null, (_, _) =>
        {
            _settings.UnloadIdleTabsMinutes = _settings.UnloadIdleTabsMinutes > 0 ? 0 : 10;
            _settings.Save();
        }) { Checked = _settings.UnloadIdleTabsMinutes > 0 });
        menu.Items.Add(new ToolStripMenuItem("Classic list view (colours + middle-click)", null, (_, _) =>
        {
            _settings.ClassicListView = !_settings.ClassicListView;
            BrowserTab.ClassicList = _settings.ClassicListView;
            _settings.Save();
            _active.ShowMessage("Applies to tabs opened from now on, and to all tabs after restarting.", warning: false);
        }) { Checked = _settings.ClassicListView });
        menu.Items.Add(new ToolStripMenuItem("Cloud status column in OneDrive folders", null, (_, _) =>
        {
            _settings.ShowCloudStatus = !_settings.ShowCloudStatus;
            BrowserTab.ShowCloudStatus = _settings.ShowCloudStatus;
            _settings.Save();
            if (_settings.ShowCloudStatus) _active.Current?.Refresh();
        }) { Checked = _settings.ShowCloudStatus });
        menu.Items.Add(new ToolStripMenuItem("Reopen my tabs on startup", null, (_, _) =>
        {
            _settings.RestoreSession = !_settings.RestoreSession;
            _settings.Save();
        }) { Checked = _settings.RestoreSession });

        var exe = Environment.ProcessPath ?? Application.ExecutablePath;
        menu.Items.Add(new ToolStripMenuItem("Add \"Open in ExplorerDuo\" to Explorer's right-click menu", null, (_, _) =>
        {
            bool enable = !Integration.IsContextMenuRegistered();
            Integration.SetContextMenu(enable, exe);
            _active.ShowMessage(enable
                ? "Added. In Windows 11 it's under \"Show more options\" (Shift+right-click)."
                : "Removed from Explorer's right-click menu.", warning: false);
        }) { Checked = Integration.IsContextMenuRegistered() });
        menu.Items.Add(new ToolStripMenuItem("Edit settings (colour rules, editor…)", null, (_, _) => EditSettingsFile()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Keyboard shortcuts", null, (_, _) => ShowShortcuts()) { ShortcutKeyDisplayString = "" });
        menu.Items.Add(new ToolStripMenuItem("About ExplorerDuo", null, (_, _) =>
            MessageBox.Show(this,
                $"ExplorerDuo {Application.ProductVersion.Split('+')[0]}\n\nDual-pane, tabbed file manager built on the Windows Explorer view.",
                "About", MessageBoxButtons.OK, MessageBoxIcon.Information)));
        return menu;
    }

    void ShowShortcuts()
    {
        var text = string.Join(Environment.NewLine,
            ShortcutHelp.Select(s => s.Keys.Length == 0 && s.Action.Length == 0 ? "" : $"{s.Keys}\t{s.Action}"));
        MessageBox.Show(this, text, "ExplorerDuo – keyboard shortcuts", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _statusTimer.Dispose();
            _cts.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>Function-key bar button: key hint in grey, action in black.</summary>
internal sealed class FnButton : FlatButton
{
    readonly string _key;
    readonly string _label;

    public FnButton(string key, string label)
    {
        _key = key;
        _label = label;
        AutoSize = true;
        Margin = new Padding(1, 0, 1, 0);
        Text = "";
    }

    public override Size GetPreferredSize(Size proposedSize)
    {
        var k = TextRenderer.MeasureText(_key, Theme.UiBold, Size.Empty, TextFormatFlags.NoPadding);
        var l = TextRenderer.MeasureText(_label, Font, Size.Empty, TextFormatFlags.NoPadding);
        return new Size(k.Width + l.Width + LogicalToDeviceUnits(30), LogicalToDeviceUnits(32));
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var flags = TextFormatFlags.NoPadding | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine;
        var k = TextRenderer.MeasureText(_key, Theme.UiBold, Size.Empty, TextFormatFlags.NoPadding);
        int x = LogicalToDeviceUnits(10);
        TextRenderer.DrawText(e.Graphics, _key, Theme.UiBold, new Rectangle(x, 0, k.Width + 2, Height), Theme.AccentDeep, flags);
        x += k.Width + LogicalToDeviceUnits(6);
        TextRenderer.DrawText(e.Graphics, _label, Font, new Rectangle(x, 0, Width - x, Height), Theme.Text, flags);
    }
}
