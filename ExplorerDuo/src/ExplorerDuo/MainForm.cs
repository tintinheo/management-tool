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
        ("Ctrl+B", "Show / hide the folder tree (Tab in the tree jumps to the active pane)"),
        ("", ""),
        ("Middle-click", "On a folder (list, tree or bookmark): open it in the OTHER pane · Shift: as new tab · on a tab: close"),
        ("Explorer keys", "Ctrl+C/X/V, Ctrl+Z, F2 rename, Ctrl+Shift+N, Alt+Enter … work as in Explorer"),
        ("Mouse", "Drag & drop between panes; double-click the divider to centre it"),
    };

    readonly Settings _settings;
    readonly IReadOnlyList<string> _startupPaths;
    readonly Pane _left = new();
    readonly Pane _right = new();
    readonly SplitContainer _split;
    readonly SplitContainer _outer;
    readonly FolderTree _tree = new();
    readonly BookmarkBar _bookmarkBar = new();
    readonly System.Windows.Forms.Timer _statusTimer = new() { Interval = 400 };
    readonly Stack<string> _closed = new();
    readonly CancellationTokenSource _cts = new();
    Pane _active;
    double _ratio = 0.5;
    string _revealed = "";
    (long Until, bool AsTab)? _treePickToOther;
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
        RebuildHighlighter();

        Text = "ExplorerDuo";
        Icon = AppIcon.Create();
        Font = Theme.Ui;
        BackColor = Theme.Window;
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        MinimumSize = new Size(760, 420);
        Size = new Size(1400, 860);
        StartPosition = FormStartPosition.CenterScreen;

        _split = new SplitContainer { Dock = DockStyle.Fill, SplitterWidth = 5, BackColor = Theme.Border, TabStop = false };
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
            Dock = DockStyle.Fill, SplitterWidth = 5, BackColor = Theme.Border, TabStop = false, FixedPanel = FixedPanel.Panel1,
        };
        _outer.Panel1.Controls.Add(_tree);
        _outer.Panel2.Controls.Add(_split);
        _tree.FolderPicked += (item, path) =>
        {
            if (_treePickToOther is { } route)
            {
                _treePickToOther = null;
                if (Environment.TickCount64 <= route.Until)
                {
                    OpenInOtherPane(_active, path, route.AsTab);
                    return;
                }
            }
            var tab = _active.Current;
            if (tab == null || PathUtil.SamePath(tab.Path, path)) return;
            _revealed = path;
            if (!tab.NavigateItem(item, path)) _active.ShowMessage("Couldn't open that location.");
        };

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

        Controls.Add(_outer);
        Controls.Add(_bookmarkBar);
        Controls.Add(BuildFunctionBar());

        foreach (var pane in new[] { _left, _right })
        {
            pane.Activated += SetActive;
            pane.Changed += p => { if (p == _active) { UpdateTitle(); RevealInTree(); } };
            pane.TabClosed += path => _closed.Push(path);
        }
        _statusTimer.Tick += (_, _) => { _left.UpdateStatus(); _right.UpdateStatus(); };
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e); // DPI auto-scaling happens here, so restore pixel bounds afterwards
        RestoreWindowBounds();
        _outer.Panel1Collapsed = !_settings.ShowTree;
        ApplyTreeWidth();
        RestoreSession();
        Application.AddMessageFilter(this);
        _statusTimer.Start();
        _ = Ipc.ServeAsync(args => BeginInvoke(() => ReceiveArgs(args)), _cts.Token);
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        ApplyTreeWidth();
        ApplyRatio();
        _revealed = "";
        RevealInTree();
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
        if (_split != null && WindowState != FormWindowState.Minimized) ApplyRatio();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        SaveSession();
        base.OnFormClosing(e);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        Application.RemoveMessageFilter(this);
        _statusTimer.Stop();
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
        if (!_outer.Panel1Collapsed) _settings.TreeWidth = _outer.SplitterDistance;
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
        if (_outer.Panel1Collapsed || !_tree.IsReady || string.IsNullOrEmpty(path) || PathUtil.SamePath(path, _revealed)) return;
        _revealed = path;
        _tree.Reveal(path);
    }

    void ToggleTree()
    {
        if (!_outer.Panel1Collapsed) _settings.TreeWidth = _outer.SplitterDistance;
        _settings.ShowTree = _outer.Panel1Collapsed;
        _outer.Panel1Collapsed = !_settings.ShowTree;
        if (_settings.ShowTree)
        {
            ApplyTreeWidth();
            _revealed = "";
            RevealInTree();
        }
        ApplyRatio();
        _settings.Save();
    }

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
        if (keyCode == Keys.Tab && Native.IsWithin(_tree, m.HWnd))
        {
            _active.FocusView(); // Tab from the tree jumps into the active pane
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

    void Transfer(FileOp op)
    {
        var src = _active.Current;
        if (src == null) return;
        var items = src.Selection();
        if (items.Count == 0)
        {
            _active.ShowMessage("Select files or folders first.");
            return;
        }

        string? destination = null;
        if (op != FileOp.Delete)
        {
            var dst = Other(_active).Current;
            if (dst == null || !PathUtil.IsFileSystemPath(dst.Path))
            {
                _active.ShowMessage("The other pane isn't showing a regular folder.");
                return;
            }
            if (PathUtil.SamePath(src.Path, dst.Path))
            {
                _active.ShowMessage("Both panes show the same folder.");
                return;
            }
            destination = dst.Path;
        }

        var pane = _active;
        var sources = items.Select(i => i.ParsingName).ToList();
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

    void NewFolder()
    {
        var tab = _active.Current;
        if (tab == null || !PathUtil.IsFileSystemPath(tab.Path))
        {
            _active.ShowMessage("New folders can only be created inside a regular folder.");
            return;
        }
        var name = InputDialog.Prompt(this, "New folder", $"Create a folder in {tab.Path}:", "New folder", "Create");
        if (name == null) { _active.FocusView(); return; }
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        {
            _active.ShowMessage("A folder name can't contain \\ / : * ? \" < > |");
            return;
        }
        try
        {
            Directory.CreateDirectory(Path.Combine(tab.Path, name));
        }
        catch (Exception ex)
        {
            _active.ShowMessage(ex.Message);
        }
        _active.FocusView();
    }

    // ───────────────────────────── Multi Commander-style commands ─────────────────────────────

    /// <summary>Middle-click on a folder (file list or tree) opens it in the opposite pane. Returns true if handled.</summary>
    bool HandleMiddleClick(IntPtr hwnd, Point client)
    {
        if (!Native.IsWithin(this, hwnd)) return false;
        bool asTab = (ModifierKeys & Keys.Shift) != 0;

        if (Native.IsWithin(_tree, hwnd))
        {
            // Select the item under the mouse; the resulting pick is routed to the other pane.
            _treePickToOther = (Environment.TickCount64 + 1500, asTab);
            if (!_tree.SelectAt(hwnd, client, out var alreadySelected)) _treePickToOther = null;
            else if (alreadySelected != null)
            {
                _treePickToOther = null;
                OpenInOtherPane(_active, alreadySelected, asTab);
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
        if (tab == null) return;
        if (_settings.Bookmarks.Any(b => PathUtil.SamePath(b.Path, tab.Path)))
        {
            _active.ShowMessage("This folder is already bookmarked.", warning: false);
            return;
        }
        _settings.Bookmarks.Add(new Bookmark { Name = tab.Title, Path = tab.Path });
        BookmarksChanged();
        _active.ShowMessage($"Bookmarked \"{tab.Title}\" (Alt+{Math.Min(_settings.Bookmarks.Count, 9)}).", warning: false);
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
        _tree.Repaint();
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

    void OpenTerminal()
    {
        var path = _active.Current?.Path;
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
        var bar = new Panel { Dock = DockStyle.Bottom, Height = 34, BackColor = Theme.Window };
        var flow = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, WrapContents = false, AutoScroll = false,
            Padding = new Padding(6, 3, 0, 3), BackColor = Theme.Window,
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
        bar.Paint += (_, e) =>
        {
            using var pen = new Pen(Theme.Border);
            e.Graphics.DrawLine(pen, 0, 0, bar.Width, 0);
        };
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

        menu.Items.Add(new ToolStripMenuItem("Folder tree", null, (_, _) => ToggleTree())
            { Checked = _settings.ShowTree, ShortcutKeyDisplayString = "Ctrl+B" });
        menu.Items.Add(new ToolStripMenuItem("Classic list view (colours + middle-click)", null, (_, _) =>
        {
            _settings.ClassicListView = !_settings.ClassicListView;
            BrowserTab.ClassicList = _settings.ClassicListView;
            _settings.Save();
            _active.ShowMessage("Applies to tabs opened from now on, and to all tabs after restarting.", warning: false);
        }) { Checked = _settings.ClassicListView });
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
            ShortcutHelp.Select(s => s.Keys.Length == 0 ? "" : $"{s.Keys}\t{s.Action}"));
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
        return new Size(k.Width + l.Width + LogicalToDeviceUnits(26), LogicalToDeviceUnits(28));
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var flags = TextFormatFlags.NoPadding | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine;
        var k = TextRenderer.MeasureText(_key, Theme.UiBold, Size.Empty, TextFormatFlags.NoPadding);
        int x = LogicalToDeviceUnits(10);
        TextRenderer.DrawText(e.Graphics, _key, Theme.UiBold, new Rectangle(x, 0, k.Width + 2, Height), Theme.Accent, flags);
        x += k.Width + LogicalToDeviceUnits(6);
        TextRenderer.DrawText(e.Graphics, _label, Font, new Rectangle(x, 0, Width - x, Height), Theme.Text, flags);
    }
}
