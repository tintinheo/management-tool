using System.Diagnostics;
using System.Reflection;

namespace ExplorerDuo;

internal sealed class TrayApp : ApplicationContext
{
    static readonly Dictionary<string, string> ActionTitles = new()
    {
        ["TogglePair"] = "Turn dual pane on/off for the active Explorer window",
        ["FocusOther"] = "Jump to the other pane",
        ["CopyToOther"] = "Copy selection to the other pane",
        ["MoveToOther"] = "Move selection to the other pane",
        ["SwapPanes"] = "Swap left and right panes",
        ["MirrorToOther"] = "Show this folder in the other pane too",
        ["OpenSelectedAsTabs"] = "Open selected folders as tabs (this pane)",
        ["OpenSelectedInOther"] = "Open selected folders as tabs in the other pane",
        ["Workspaces"] = "Workspaces menu (saved pane + tab sets)",
    };

    readonly string _exe = Environment.ProcessPath ?? Application.ExecutablePath;
    readonly SynchronizationContext _ui;
    readonly ShellBridge _shell = new();
    readonly PairManager _pairs = new();
    readonly HotkeyHost _hotkeys = new();
    readonly NotifyIcon _tray;
    readonly CancellationTokenSource _cts = new();
    readonly List<string> _pendingTabs = new();
    readonly System.Windows.Forms.Timer _tabBatch = new() { Interval = 450 };
    Settings _settings;
    bool _busy;

    public TrayApp(CommandLine startup)
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _settings = Settings.Load();
        _pairs.ExpandSurvivorOnClose = _settings.ExpandSurvivorOnClose;

        var menu = new ContextMenuStrip();
        menu.Opening += (_, _) => BuildMenu(menu);
        BuildMenu(menu);
        _tray = new NotifyIcon
        {
            Icon = AppIcon.Create(),
            Text = "ExplorerDuo – dual-pane Explorer",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
                typeof(NotifyIcon).GetMethod("ShowContextMenu", BindingFlags.Instance | BindingFlags.NonPublic)?.Invoke(_tray, null);
        };

        _tabBatch.Tick += (_, _) =>
        {
            _tabBatch.Stop();
            var paths = _pendingTabs.Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList();
            _pendingTabs.Clear();
            if (paths.Count > 0) Fire(() => OpenTabsAsync(paths));
        };

        RegisterHotkeys();
        _ = Ipc.ServeAsync(args => _ui.Post(_ => Handle(CommandLine.Parse(args), fromOtherInstance: true), null), _cts.Token);
        _ui.Post(_ => Handle(startup, fromOtherInstance: false), null);
    }

    // ───────────────────────────── command dispatch ─────────────────────────────

    void Handle(CommandLine cmd, bool fromOtherInstance)
    {
        switch (cmd.Verb)
        {
            case "--pair":
                var folder = cmd.Paths.FirstOrDefault() ?? ShellBridge.ThisPc;
                Fire(() => OpenLayoutAsync(new[] { folder }, new[] { folder }));
                break;
            case "--tabs":
                _pendingTabs.AddRange(cmd.Paths);
                _tabBatch.Stop();
                _tabBatch.Start(); // Explorer launches one process per selected folder; collect them.
                break;
            case "--background":
                break;
            default:
                Info(fromOtherInstance
                    ? "ExplorerDuo is already running. Click the tray icon for options."
                    : $"Running. Press {HotkeyText("TogglePair")} in any File Explorer window for dual-pane mode.");
                break;
        }
    }

    void RegisterHotkeys()
    {
        _hotkeys.Clear();
        var actions = new Dictionary<string, Action>
        {
            ["TogglePair"] = () => Fire(TogglePairAsync),
            ["FocusOther"] = () => Fire(FocusOtherAsync),
            ["CopyToOther"] = () => Fire(() => TransferAsync(move: false)),
            ["MoveToOther"] = () => Fire(() => TransferAsync(move: true)),
            ["SwapPanes"] = () => Fire(SwapAsync),
            ["MirrorToOther"] = () => Fire(MirrorAsync),
            ["OpenSelectedAsTabs"] = () => Fire(() => OpenSelectedAsTabsAsync(intoOther: false)),
            ["OpenSelectedInOther"] = () => Fire(() => OpenSelectedAsTabsAsync(intoOther: true)),
            ["Workspaces"] = ShowWorkspaceMenuAtCursor,
        };

        var failed = new List<string>();
        foreach (var (name, action) in actions)
        {
            if (!_settings.Hotkeys.TryGetValue(name, out var text) || string.IsNullOrWhiteSpace(text)) continue;
            if (!Hotkey.TryParse(text, out var hotkey) || !_hotkeys.Register(hotkey, action))
                failed.Add($"{text} ({name})");
        }
        if (failed.Count > 0)
            Warn("These shortcuts are invalid or used by another app: " + string.Join(", ", failed) +
                 ". Change them via tray menu → Edit settings.");
    }

    void Fire(Func<Task> action) => _ = RunExclusive(action);

    async Task RunExclusive(Func<Task> action)
    {
        if (_busy) return; // one Explorer automation at a time; keystrokes would interleave otherwise
        _busy = true;
        try { await action(); }
        catch (Exception ex) { Warn(ex.Message); }
        finally { _busy = false; }
    }

    // ───────────────────────────── actions ─────────────────────────────

    IntPtr CurrentExplorer()
    {
        var fg = Native.GetForegroundWindow();
        if (WindowFrame.IsExplorer(fg)) return fg;
        var last = _pairs.LastExplorer;
        return WindowFrame.IsExplorer(last) && Native.IsWindowVisible(last) ? last : IntPtr.Zero;
    }

    (PanePair Pair, IntPtr Current, IntPtr Other)? CurrentPair()
    {
        var current = CurrentExplorer();
        if (current == IntPtr.Zero)
        {
            Info("Click into a File Explorer window first.");
            return null;
        }
        var pair = _pairs.Find(current);
        if (pair == null)
        {
            Info($"This Explorer window isn't in dual-pane mode. Press {HotkeyText("TogglePair")} first.");
            return null;
        }
        return (pair, current, pair.Other(current));
    }

    async Task TogglePairAsync()
    {
        var a = CurrentExplorer();
        if (a == IntPtr.Zero)
        {
            Info("Open or click into a File Explorer window first.");
            return;
        }

        var existing = _pairs.Find(a);
        if (existing != null)
        {
            _pairs.Dissolve(existing, a, closeOther: true);
            return;
        }

        var path = _shell.ActiveTab(a)?.Path;
        if (string.IsNullOrEmpty(path)) path = ShellBridge.ThisPc;
        var area = Box.FromRectangle(Screen.FromHandle(a).WorkingArea);

        var b = await _shell.OpenWindowAsync(path, a);
        if (b == IntPtr.Zero)
        {
            Warn("Couldn't open the second pane.");
            return;
        }
        _pairs.Pair(a, b, area, _settings.SplitRatio);
        await Input.ActivateAsync(a);
    }

    async Task FocusOtherAsync()
    {
        if (CurrentPair() is { } c) await Input.ActivateAsync(c.Other);
    }

    Task SwapAsync()
    {
        if (CurrentPair() is { } c) _pairs.Swap(c.Pair);
        return Task.CompletedTask;
    }

    Task MirrorAsync()
    {
        if (CurrentPair() is not { } c) return Task.CompletedTask;
        var src = _shell.ActiveTab(c.Current);
        var dst = _shell.ActiveTab(c.Other);
        if (src != null && dst != null && src.Path.Length > 0) _shell.Navigate(dst, src.Path);
        return Task.CompletedTask;
    }

    Task TransferAsync(bool move)
    {
        if (CurrentPair() is not { } c) return Task.CompletedTask;
        var src = _shell.ActiveTab(c.Current);
        var dst = _shell.ActiveTab(c.Other);
        if (src == null || dst == null) return Task.CompletedTask;

        var items = _shell.SelectedPaths(src);
        if (items.Count == 0) { Info("Select files or folders first."); return Task.CompletedTask; }
        if (!ShellBridge.IsFileSystemPath(dst.Path)) { Warn("The other pane isn't showing a regular folder."); return Task.CompletedTask; }
        if (ShellBridge.SamePath(src.Path, dst.Path)) { Info("Both panes show the same folder."); return Task.CompletedTask; }

        // Runs on its own thread with the native progress dialog; don't block other shortcuts meanwhile.
        _ = FileTransfer.RunAsync(items, dst.Path, move, c.Current).ContinueWith(t =>
        {
            if (t.Exception != null) _ui.Post(_ => Warn((move ? "Move" : "Copy") + " failed: " + t.Exception.GetBaseException().Message), null);
        }, TaskScheduler.Default);
        return Task.CompletedTask;
    }

    async Task OpenSelectedAsTabsAsync(bool intoOther)
    {
        var current = CurrentExplorer();
        if (current == IntPtr.Zero) { Info("Click into a File Explorer window first."); return; }
        var src = _shell.ActiveTab(current);
        var folders = src == null ? new List<string>() : _shell.SelectedPaths(src).Where(Directory.Exists).ToList();
        if (folders.Count == 0) { Info("Select one or more folders first."); return; }

        var target = current;
        if (intoOther)
        {
            if (CurrentPair() is not { } c) return;
            target = c.Other;
        }
        foreach (var folder in folders) await _shell.OpenTabAsync(target, folder);
        if (intoOther) await Input.ActivateAsync(current);
    }

    async Task OpenTabsAsync(List<string> paths)
    {
        var target = _pairs.LastExplorer;
        int start = 0;
        if (!WindowFrame.IsExplorer(target) || !Native.IsWindowVisible(target))
        {
            target = await _shell.OpenWindowAsync(paths[0], IntPtr.Zero);
            start = 1;
            if (target == IntPtr.Zero) { Warn("Couldn't open an Explorer window."); return; }
        }
        for (int i = start; i < paths.Count; i++) await _shell.OpenTabAsync(target, paths[i]);
    }

    async Task OpenLayoutAsync(IReadOnlyList<string> left, IReadOnlyList<string> right)
    {
        left = Existing(left);
        right = Existing(right);
        if (left.Count == 0) left = new[] { ShellBridge.ThisPc };
        if (right.Count == 0) right = new[] { left[0] };

        var a = await _shell.OpenWindowAsync(left[0], IntPtr.Zero);
        if (a == IntPtr.Zero) { Warn("Couldn't open an Explorer window."); return; }
        for (int i = 1; i < left.Count; i++) await _shell.OpenTabAsync(a, left[i]);

        var b = await _shell.OpenWindowAsync(right[0], a);
        if (b == IntPtr.Zero) { Warn("Couldn't open the second pane."); return; }
        _pairs.Pair(a, b, Box.FromRectangle(Screen.FromHandle(a).WorkingArea), _settings.SplitRatio);
        for (int i = 1; i < right.Count; i++) await _shell.OpenTabAsync(b, right[i]);
        await Input.ActivateAsync(a);
    }

    static List<string> Existing(IEnumerable<string> paths) =>
        paths.Where(p => p.Length > 0 && (!ShellBridge.IsFileSystemPath(p) || Directory.Exists(p))).ToList();

    // ───────────────────────────── workspaces ─────────────────────────────

    Task SaveWorkspaceAsync()
    {
        var a = CurrentExplorer();
        if (a == IntPtr.Zero) { Info("Click into the File Explorer window you want to save first."); return Task.CompletedTask; }

        var pair = _pairs.Find(a);
        var left = TabPaths(pair?.Left ?? a);
        var right = pair != null ? TabPaths(pair.Right) : new List<string>();
        if (left.Count + right.Count == 0) { Warn("Couldn't read the tabs of that window."); return Task.CompletedTask; }

        var suggested = left.Select(p => Path.GetFileName(p.TrimEnd('\\'))).FirstOrDefault(n => !string.IsNullOrEmpty(n)) ?? "Workspace";
        var name = InputDialog.Prompt("Save workspace", "Name for this pane + tab layout:", suggested);
        if (name == null) return Task.CompletedTask;

        _settings.Workspaces.RemoveAll(w => w.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        _settings.Workspaces.Add(new Workspace { Name = name, Left = left, Right = right });
        _settings.Save();
        Info($"Saved workspace \"{name}\" ({left.Count + right.Count} tabs).");
        return Task.CompletedTask;
    }

    List<string> TabPaths(IntPtr window) =>
        _shell.GetTabs(window).Select(t => t.Path).Where(p => p.Length > 0).ToList();

    void AddWorkspaceItems(ToolStripItemCollection items)
    {
        if (_settings.Workspaces.Count == 0)
            items.Add(new ToolStripMenuItem("(no saved workspaces yet)") { Enabled = false });

        foreach (var ws in _settings.Workspaces.OrderBy(w => w.Name, StringComparer.CurrentCultureIgnoreCase))
        {
            var item = new ToolStripMenuItem(ws.Name)
            {
                ToolTipText = "Left: " + string.Join(", ", ws.Left) + "\nRight: " + string.Join(", ", ws.Right),
            };
            item.Click += (_, _) => Fire(() => OpenLayoutAsync(ws.Left, ws.Right));
            items.Add(item);
        }

        items.Add(new ToolStripSeparator());
        items.Add(new ToolStripMenuItem("Save active Explorer window as workspace…", null, (_, _) => Fire(SaveWorkspaceAsync)));
        if (_settings.Workspaces.Count > 0)
        {
            var delete = new ToolStripMenuItem("Delete");
            foreach (var ws in _settings.Workspaces)
            {
                delete.DropDownItems.Add(ws.Name, null, (_, _) =>
                {
                    _settings.Workspaces.Remove(ws);
                    _settings.Save();
                });
            }
            items.Add(delete);
        }
    }

    void ShowWorkspaceMenuAtCursor()
    {
        var menu = new ContextMenuStrip();
        AddWorkspaceItems(menu.Items);
        menu.Closed += (_, _) => _ui.Post(_ => menu.Dispose(), null);
        menu.Show(Cursor.Position);
    }

    // ───────────────────────────── tray menu ─────────────────────────────

    void BuildMenu(ContextMenuStrip menu)
    {
        menu.Items.Clear();
        menu.Items.Add(new ToolStripLabel("ExplorerDuo") { Font = new Font(menu.Font, FontStyle.Bold) });
        menu.Items.Add(new ToolStripSeparator());

        menu.Items.Add(Item("Dual pane for last Explorer window", "TogglePair", () => Fire(TogglePairAsync)));
        menu.Items.Add(Item("New dual-pane window", null, () => Fire(() => OpenLayoutAsync(new[] { ShellBridge.ThisPc }, new[] { ShellBridge.ThisPc }))));
        var lastPair = _pairs.Find(_pairs.LastExplorer);
        var unlink = Item("Unlink pane pair (keep both windows)", null, () => { if (lastPair != null) _pairs.Unpair(lastPair); });
        unlink.Enabled = lastPair != null;
        menu.Items.Add(unlink);

        var workspaces = new ToolStripMenuItem("Workspaces") { ShortcutKeyDisplayString = HotkeyText("Workspaces") };
        AddWorkspaceItems(workspaces.DropDownItems);
        menu.Items.Add(workspaces);
        menu.Items.Add(new ToolStripSeparator());

        var contextMenu = Item("Add to Explorer right-click menu", null, () =>
        {
            bool enable = !Integration.IsContextMenuRegistered();
            Integration.SetContextMenu(enable, _exe);
            Info(enable
                ? "Added. In Windows 11 the entries are under \"Show more options\" (or Shift+right-click)."
                : "Removed from the Explorer right-click menu.");
        });
        contextMenu.Checked = Integration.IsContextMenuRegistered();
        menu.Items.Add(contextMenu);

        var autostart = Item("Start with Windows", null, () => Integration.SetAutostart(!Integration.IsAutostartEnabled(), _exe));
        autostart.Checked = Integration.IsAutostartEnabled();
        menu.Items.Add(autostart);
        menu.Items.Add(new ToolStripSeparator());

        menu.Items.Add(Item("Keyboard shortcuts…", null, ShowShortcuts));
        menu.Items.Add(Item("Edit settings…", null, () =>
            Process.Start(new ProcessStartInfo("notepad.exe", $"\"{Settings.FilePath}\"") { UseShellExecute = false })));
        menu.Items.Add(Item("Reload settings", null, () =>
        {
            _settings = Settings.Load();
            _pairs.ExpandSurvivorOnClose = _settings.ExpandSurvivorOnClose;
            RegisterHotkeys();
            Info("Settings reloaded.");
        }));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(Item("Exit", null, ExitThread));
    }

    ToolStripMenuItem Item(string text, string? hotkeyName, Action onClick)
    {
        var item = new ToolStripMenuItem(text);
        if (hotkeyName != null) item.ShortcutKeyDisplayString = HotkeyText(hotkeyName);
        item.Click += (_, _) => onClick();
        return item;
    }

    string HotkeyText(string name) =>
        _settings.Hotkeys.TryGetValue(name, out var t) && !string.IsNullOrWhiteSpace(t) ? t : "(no shortcut)";

    void ShowShortcuts()
    {
        var lines = ActionTitles.Select(kv => $"{HotkeyText(kv.Key),-16}  {kv.Value}");
        MessageBox.Show(
            string.Join(Environment.NewLine, lines) + Environment.NewLine + Environment.NewLine +
            "Explorer's own tab keys still work: Ctrl+T new tab, Ctrl+W close tab, Ctrl+Tab next tab." + Environment.NewLine +
            "Drag the border between the two panes to resize them; maximize either pane to fill the screen.",
            "ExplorerDuo shortcuts", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    void Info(string message) => _tray.ShowBalloonTip(3000, "ExplorerDuo", message, ToolTipIcon.Info);
    void Warn(string message) => _tray.ShowBalloonTip(4000, "ExplorerDuo", message, ToolTipIcon.Warning);

    protected override void ExitThreadCore()
    {
        _cts.Cancel();
        _hotkeys.Dispose();
        _pairs.Dispose();
        _tabBatch.Dispose();
        _tray.Visible = false;
        _tray.Dispose();
        base.ExitThreadCore();
    }
}
