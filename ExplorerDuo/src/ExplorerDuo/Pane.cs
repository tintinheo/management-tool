namespace ExplorerDuo;

/// <summary>One side of the dual-panel window: tab strip, navigation/address bar, the Explorer views, status line.</summary>
internal sealed class Pane : Panel
{
    readonly List<BrowserTab> _tabs = new();
    readonly Panel _nav = new() { Dock = DockStyle.Top, Height = 46, BackColor = Theme.Surface, Padding = new Padding(8, 7, 8, 7) };
    readonly Panel _addressBox = new() { Dock = DockStyle.Fill, BackColor = Theme.Surface, Padding = new Padding(14, 8, 12, 4), Margin = new Padding(6, 0, 6, 0) };
    readonly TextBox _address = new()
    {
        Dock = DockStyle.Fill, BorderStyle = BorderStyle.None, BackColor = Theme.Tint, ForeColor = Theme.Text,
        Font = Theme.Ui, AutoCompleteMode = AutoCompleteMode.SuggestAppend, AutoCompleteSource = AutoCompleteSource.FileSystemDirectories,
    };
    readonly Panel _body = new() { Dock = DockStyle.Fill, BackColor = Theme.Surface };
    readonly SplitContainer _content = new()
    {
        Dock = DockStyle.Fill, SplitterWidth = 5, BackColor = Theme.Surface, TabStop = false,
        FixedPanel = FixedPanel.Panel1, Panel1Collapsed = true,
    };
    readonly Label _status = new()
    {
        Dock = DockStyle.Bottom, Height = 28, ForeColor = Theme.Muted, BackColor = Theme.Surface,
        TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(14, 0, 0, 0), Font = Theme.UiSmall,
    };
    readonly System.Windows.Forms.Timer _messageTimer = new() { Interval = 3500 };
    int _index = -1;
    bool _active;
    string _freeSpace = "";
    string? _message;
    bool _messageIsWarning;

    public readonly TabStrip Strip = new();

    /// <summary>The user clicked/typed somewhere in this pane.</summary>
    public event Action<Pane>? Activated;
    /// <summary>Current folder or tab set changed (for window title / session).</summary>
    public event Action<Pane>? Changed;
    /// <summary>A tab was closed; the path is offered for "reopen closed tab".</summary>
    public event Action<string>? TabClosed;

    public Pane()
    {
        Dock = DockStyle.Fill;
        // A 1-px padding over a border-coloured background draws the card's hairline outline.
        BackColor = Theme.Border;
        Padding = new Padding(1);

        var back = NavButton(Theme.GlyphBack, "Back (Alt+Left, Backspace)", () => Current?.Back());
        var forward = NavButton(Theme.GlyphForward, "Forward (Alt+Right)", () => Current?.Forward());
        var up = NavButton(Theme.GlyphUp, "Up (Alt+Up)", () => Current?.Up());
        var refresh = NavButton(Theme.GlyphRefresh, "Refresh (Ctrl+R)", () => Current?.Refresh());
        var treeButton = new FlatButton { Text = Theme.GlyphTree, Font = Theme.Icons(10f), Dock = DockStyle.Right, Width = 36 };
        new ToolTip().SetToolTip(treeButton, "Folder tree: on the left, in each pane, or hidden (Ctrl+B / Ctrl+Shift+B)");
        treeButton.Click += (_, _) => { Activated?.Invoke(this); TreeButtonClicked?.Invoke(this, treeButton); };

        _addressBox.Controls.Add(_address);
        _addressBox.Paint += (_, e) =>
        {
            // Rounded, softly tinted field; gold outline while editing.
            var g = e.Graphics;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            var r = new RectangleF(1.5f, 1.5f, _addressBox.Width - 3f, _addressBox.Height - 3f);
            using var path = Theme.Rounded(r, LogicalToDeviceUnits(8));
            using var fill = new SolidBrush(Theme.Tint);
            g.FillPath(fill, path);
            using var pen = new Pen(_address.Focused ? Theme.Accent : Theme.Border);
            g.DrawPath(pen, path);
        };
        _address.GotFocus += (_, _) => { _addressBox.Invalidate(); Activated?.Invoke(this); };
        _address.LostFocus += (_, _) => { _addressBox.Invalidate(); SyncChrome(); };
        _address.KeyDown += OnAddressKey;

        // Docking is applied from the last-added control backwards, so Fill goes first.
        _nav.Controls.Add(_addressBox);
        _nav.Controls.Add(new Panel { Dock = DockStyle.Right, Width = 6, BackColor = Theme.Surface });
        _nav.Controls.Add(treeButton);
        _nav.Controls.Add(new Panel { Dock = DockStyle.Left, Width = 8, BackColor = Theme.Surface });
        _nav.Controls.Add(refresh);
        _nav.Controls.Add(up);
        _nav.Controls.Add(forward);
        _nav.Controls.Add(back);

        _content.Panel2.Controls.Add(_body);
        _content.Paint += (_, e) =>
        {
            // Hairline between a pane's own tree and its file list.
            if (_content.Panel1Collapsed) return;
            var r = _content.SplitterRectangle;
            using var pen = new Pen(Theme.Border);
            int x = r.Left + r.Width / 2;
            e.Graphics.DrawLine(pen, x, r.Top, x, r.Bottom);
        };
        Controls.Add(_content);
        Controls.Add(_status);
        Controls.Add(_nav);
        Controls.Add(Strip);

        Strip.TabClicked += i => { Select(i); Activated?.Invoke(this); FocusView(); };
        Strip.CloseClicked += i => { CloseTab(i); Activated?.Invoke(this); };
        Strip.PlusClicked += () => { AddTab(Current?.Path ?? PathUtil.ThisPc); Activated?.Invoke(this); FocusView(); };
        Strip.DragHovered += i => { if (i != _index) Select(i); }; // drag files over a tab, then drop them into its folder

        _messageTimer.Tick += (_, _) => { _messageTimer.Stop(); _message = null; UpdateStatus(); };
    }

    FlatButton NavButton(string glyph, string tip, Action action)
    {
        var b = new FlatButton { Text = glyph, Font = Theme.Icons(10f), Dock = DockStyle.Left, Width = 36 };
        new ToolTip().SetToolTip(b, tip);
        b.Click += (_, _) => { Activated?.Invoke(this); action(); FocusView(); };
        return b;
    }

    /// <summary>The tree button in the address bar was clicked.</summary>
    public event Action<Pane, Control>? TreeButtonClicked;

    /// <summary>This pane's own folder tree (tree mode "panes"), or null.</summary>
    public FolderTree? Tree { get; private set; }

    /// <summary>Shows <paramref name="tree"/> on the left of the file view, or removes (and frees) the current one.</summary>
    public void SetTree(FolderTree? tree, int width)
    {
        if (Tree == tree) return;
        if (Tree != null)
        {
            _content.Panel1.Controls.Remove(Tree);
            Tree.Dispose();
        }
        Tree = tree;
        if (tree != null)
        {
            _content.Panel1.Controls.Add(tree);
            _content.Panel1Collapsed = false;
            TreeWidth = width;
        }
        else
        {
            _content.Panel1Collapsed = true;
        }
    }

    public int TreeWidth
    {
        get => _content.Panel1Collapsed ? 0 : _content.SplitterDistance;
        set
        {
            if (_content.Panel1Collapsed || _content.Width <= 0) return;
            int max = Math.Max(_content.Panel1MinSize, _content.Width / 2);
            _content.SplitterDistance = Math.Clamp(value, Math.Min(120, max), max);
        }
    }

    public IReadOnlyList<BrowserTab> Tabs => _tabs;
    public int CurrentIndex => _index;
    public BrowserTab? Current => _index >= 0 && _index < _tabs.Count ? _tabs[_index] : null;

    public bool IsActive
    {
        get => _active;
        set
        {
            _active = value;
            Strip.ActivePane = value;
            BackColor = value ? Theme.AccentSoft : Theme.Border;
            _status.ForeColor = value ? Theme.Text : Theme.Muted;
        }
    }

    public BrowserTab AddTab(string path, bool select = true)
    {
        var tab = new BrowserTab(path);
        tab.Navigated += t =>
        {
            if (t == Current) { SyncChrome(); UpdateFreeSpace(); }
            else RefreshStrip();
            Changed?.Invoke(this);
        };
        tab.NavigationFailed += t => { if (t == Current) ShowMessage("Couldn't open that location."); };

        int at = _index < 0 ? _tabs.Count : _index + 1;
        _tabs.Insert(at, tab);
        _body.Controls.Add(tab.Host);
        if (select || _index < 0) Select(at);
        else RefreshStrip();
        Changed?.Invoke(this);
        return tab;
    }

    public void Select(int index)
    {
        if (index < 0 || index >= _tabs.Count) return;
        _index = index;
        var tab = _tabs[index];
        tab.Host.Visible = true;
        tab.Host.BringToFront();
        tab.EnsureCreated();
        foreach (var other in _tabs)
        {
            if (other == tab || !other.Host.Visible) continue;
            other.Host.Visible = false;
            other.HiddenSince = Environment.TickCount64;
        }
        tab.HiddenSince = null;
        SyncChrome();
        UpdateFreeSpace();
        Changed?.Invoke(this);
    }

    public void CloseTab(int index)
    {
        if (index < 0 || index >= _tabs.Count) return;
        var tab = _tabs[index];
        TabClosed?.Invoke(tab.Path);
        if (_tabs.Count == 1)
        {
            // Always keep one tab; closing the last one just goes home.
            tab.Navigate(PathUtil.ThisPc);
            return;
        }

        _tabs.RemoveAt(index);
        _body.Controls.Remove(tab.Host);
        tab.Dispose();
        if (index < _index) _index--;
        else if (index == _index) _index = Math.Min(index, _tabs.Count - 1);
        Select(_index);
    }

    public void CycleTab(int step)
    {
        if (_tabs.Count > 1) Select(((_index + step) % _tabs.Count + _tabs.Count) % _tabs.Count);
    }

    public void FocusView() => Current?.FocusView();

    public void FocusAddress()
    {
        _address.Focus();
        _address.SelectAll();
    }

    public void ShowMessage(string message, bool warning = true)
    {
        _message = message;
        _messageIsWarning = warning;
        _messageTimer.Stop();
        _messageTimer.Start();
        UpdateStatus();
    }

    public void UpdateStatus()
    {
        if (_message != null)
        {
            _status.ForeColor = _messageIsWarning ? Theme.Warning : Theme.Accent;
            _status.Text = _message;
            return;
        }
        _status.ForeColor = _active ? Theme.Text : Theme.Muted;
        var tab = Current;
        if (tab == null || !tab.IsCreated) { _status.Text = ""; return; }
        tab.TrySelectPending();
        var (total, selected) = tab.Counts();
        var text = total == 1 ? "1 item" : $"{total:N0} items";
        if (selected > 0) text += $"   ·   {selected:N0} selected";
        if (_freeSpace.Length > 0) text += $"   ·   {_freeSpace}";
        if (_status.Text != text) _status.Text = text;
    }

    string _freeSpaceRoot = "";
    long _freeSpaceAt;

    /// <summary>
    /// Drive free space for the status line. Read on a worker thread (a sleeping USB disk or a disconnected network
    /// drive can block for seconds) and at most every few seconds per drive.
    /// </summary>
    void UpdateFreeSpace()
    {
        var path = Current?.Path;
        string root = "";
        try { root = PathUtil.IsFileSystemPath(path) ? System.IO.Path.GetPathRoot(path) ?? "" : ""; }
        catch { /* odd path */ }
        if (root.Length == 0 || root.StartsWith(@"\\", StringComparison.Ordinal))
        {
            _freeSpace = "";
            _freeSpaceRoot = "";
            return;
        }
        bool sameDrive = string.Equals(root, _freeSpaceRoot, StringComparison.OrdinalIgnoreCase);
        if (sameDrive && Environment.TickCount64 - _freeSpaceAt < 5000) return;
        if (!sameDrive) _freeSpace = "";
        _freeSpaceRoot = root;
        _freeSpaceAt = Environment.TickCount64;
        _ = Task.Run(() =>
        {
            try
            {
                var drive = new DriveInfo(root);
                return drive.IsReady ? $"{FormatBytes(drive.AvailableFreeSpace)} free of {FormatBytes(drive.TotalSize)}" : "";
            }
            catch
            {
                return ""; // unavailable drive: just omit
            }
        }).ContinueWith(t =>
        {
            if (IsDisposed || !string.Equals(root, _freeSpaceRoot, StringComparison.OrdinalIgnoreCase)) return;
            _freeSpace = t.Result;
            UpdateStatus();
        }, TaskScheduler.FromCurrentSynchronizationContext());
    }

    public static string FormatBytes(long bytes)
    {
        string[] units = { "B", "KB", "MB", "GB", "TB" };
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.Length - 1) { v /= 1024; u++; }
        return u == 0 ? $"{bytes} B" : $"{v:0.#} {units[u]}";
    }

    void OnAddressKey(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Enter)
        {
            e.SuppressKeyPress = true;
            var text = _address.Text.Trim();
            if (Current == null || text.Length == 0) return;
            if (Current.Navigate(text)) FocusView();
            else ShowMessage($"Can't find \"{text}\".");
        }
        else if (e.KeyCode == Keys.Escape)
        {
            e.SuppressKeyPress = true;
            SyncChrome();
            FocusView();
        }
    }

    void SyncChrome()
    {
        var tab = Current;
        if (!_address.Focused) _address.Text = tab == null ? "" : PathUtil.DisplayPath(tab.Path, tab.Title);
        RefreshStrip();
    }

    void RefreshStrip() => Strip.SetTabs(
        _tabs.Select(t => t.Title),
        _tabs.Select(t => PathUtil.DisplayPath(t.Path, t.Title)),
        _index);

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _messageTimer.Dispose();
            foreach (var t in _tabs) t.Dispose();
            _tabs.Clear();
        }
        base.Dispose(disposing);
    }
}
