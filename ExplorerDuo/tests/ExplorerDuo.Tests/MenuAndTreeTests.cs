using System.Runtime.InteropServices;
using System.Text;
using ExplorerDuo;
using Xunit;

/// <summary>
/// Right-click menus, drag &amp; drop onto the tree, tree placement, memory/speed features — all against the real shell
/// controls in windows that are never activated (off-screen).
/// </summary>
public class MenuAndTreeTests
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetProp(IntPtr h, string name);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindow(string? cls, string? title);
    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr h, int msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, int msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] static extern int GetMenuItemCount(IntPtr m);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetMenuString(IntPtr m, uint id, StringBuilder sb, int n, uint flags);
    [DllImport("user32.dll")] static extern bool EndMenu();
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);

    sealed class QuietForm : Form
    {
        public QuietForm()
        {
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            Location = new Point(-8000, 100);
        }
        protected override bool ShowWithoutActivation => true;
    }

    /// <summary>Runs <paramref name="open"/> (which shows a popup menu modally), reads the menu's items, then closes it.</summary>
    static List<string> CaptureMenu(Action open)
    {
        var items = new List<string>();
        using var timer = new System.Windows.Forms.Timer { Interval = 200 };
        int ticks = 0;
        timer.Tick += (_, _) =>
        {
            ticks++;
            var popup = FindWindow("#32768", null);
            if (popup != IntPtr.Zero && IsWindowVisible(popup))
            {
                var menu = SendMessage(popup, 0x01E1 /* MN_GETHMENU */, IntPtr.Zero, IntPtr.Zero);
                for (uint i = 0; i < GetMenuItemCount(menu); i++)
                {
                    var sb = new StringBuilder(256);
                    GetMenuString(menu, i, sb, 256, 0x400 /* MF_BYPOSITION */);
                    items.Add(sb.ToString());
                }
                timer.Stop();
                EndMenu();
            }
            else if (ticks > 40)
            {
                timer.Stop();
            }
        };
        timer.Start();
        open(); // returns when the menu closes
        ShellViewTests.Pump(() => !timer.Enabled, 3000);
        return items;
    }

    static Rectangle ListItemRect(IntPtr list, int index)
    {
        var rect = Marshal.AllocHGlobal(16);
        try
        {
            Marshal.WriteInt32(rect, 2 /* LVIR_LABEL */);
            SendMessage(list, 0x100E /* LVM_GETITEMRECT */, (IntPtr)index, rect);
            return Rectangle.FromLTRB(Marshal.ReadInt32(rect, 0), Marshal.ReadInt32(rect, 4), Marshal.ReadInt32(rect, 8), Marshal.ReadInt32(rect, 12));
        }
        finally
        {
            Marshal.FreeHGlobal(rect);
        }
    }

    static bool HasItem(List<string> items, string text) =>
        items.Any(i => i.Replace("&", "").StartsWith(text, StringComparison.OrdinalIgnoreCase));

    [Fact]
    public void Folder_tree_accepts_drops_and_shows_full_menu_with_own_commands()
    {
        ShellViewTests.RunSta(() =>
        {
            using var form = new QuietForm { Size = new Size(320, 700) };
            var tree = new FolderTree();
            form.Controls.Add(tree);
            form.Show();
            Assert.True(tree.IsReady);

            // Drag & drop onto tree folders: the tree must be registered as an OLE drop target.
            Assert.NotEqual(IntPtr.Zero, GetProp(tree.TreeViewHandle, "OleDropTargetInterface"));

            string? extrasFor = null;
            tree.MenuExtras = (_, path) =>
            {
                extrasFor = path;
                return new List<MenuCommand> { new("Open in the other pane", () => { }), MenuCommand.Separator, new("Highlight", null) { Children = new() { new("Red", () => { }) } } };
            };

            Assert.True(ShellViewTests.Pump(() =>
            {
                tree.Reveal(@"C:\Windows");
                return PathUtil.SamePath(tree.SelectedPath(), @"C:\Windows") && tree.SelectedItemRect() is { Top: >= 0 };
            }, 5000));
            var r = tree.SelectedItemRect()!.Value;

            // Real right-click path: the tree view sends NM_RCLICK; the menu must be ours, for the folder under the mouse.
            var items = CaptureMenu(() => tree.OpenMenuAt(new Point(r.Left + 10, r.Top + r.Height / 2)));
            Assert.True(PathUtil.SamePath(extrasFor, @"C:\Windows"), extrasFor ?? "(no extras requested)");
            Assert.True(HasItem(items, "Open in the other pane"), string.Join(" | ", items));
            Assert.True(HasItem(items, "Highlight"), string.Join(" | ", items));
            Assert.True(HasItem(items, "Open"), string.Join(" | ", items));        // the Windows part…
            Assert.True(HasItem(items, "Properties"), string.Join(" | ", items));
            Assert.True(HasItem(items, "Copy"), string.Join(" | ", items));
            Assert.Equal(1, tree.MenusShown);

            // Keyboard (Shift+F10 / menu key) path through WM_CONTEXTMENU.
            var keyboard = CaptureMenu(() => SendMessage(tree.TreeViewHandle, 0x007B, tree.TreeViewHandle, (IntPtr)(-1)));
            Assert.True(HasItem(keyboard, "Open in the other pane"), string.Join(" | ", keyboard));
            Assert.Equal(2, tree.MenusShown);
        });
    }

    [Fact]
    public void File_list_menu_has_windows_items_plus_own_commands_for_items_and_background()
    {
        ShellViewTests.RunSta(() =>
        {
            BrowserTab.ClassicList = true;
            using var form = new QuietForm { Size = new Size(1000, 700) };
            var tab = new BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            tab.Host.Visible = true;
            form.Show();
            tab.EnsureCreated();
            Assert.True(ShellViewTests.Pump(() => tab.Counts().Total > 0 && tab.ListHandle != IntPtr.Zero, 15000));

            var calls = new List<bool>();
            BrowserTab.MenuExtras = (t, onItems) =>
            {
                calls.Add(onItems);
                return onItems
                    ? new List<MenuCommand> { new("Copy to the other pane", () => { }, "F5") }
                    : new List<MenuCommand> { new("Open in new tab", () => { }) };
            };
            try
            {
                tab.SelectWhenLoaded(@"C:\Windows\notepad.exe");
                Assert.True(ShellViewTests.Pump(() => tab.Counts().Selected == 1, 5000));

                var onItem = CaptureMenu(() => SendMessage(tab.ListHandle, 0x007B, tab.ListHandle, (IntPtr)(-1)));
                Assert.Equal(new[] { true }, calls);
                Assert.True(HasItem(onItem, "Copy to the other pane"), string.Join(" | ", onItem));
                Assert.True(HasItem(onItem, "Open"), string.Join(" | ", onItem));
                Assert.True(HasItem(onItem, "Rename"), string.Join(" | ", onItem));
                Assert.True(HasItem(onItem, "Properties"), string.Join(" | ", onItem));
                Assert.True(HasItem(onItem, "Send to"), string.Join(" | ", onItem));

                tab.SelectByPattern("*.*", select: false);
                Assert.True(ShellViewTests.Pump(() => tab.Counts().Selected == 0, 3000));
                var background = CaptureMenu(() => SendMessage(tab.ListHandle, 0x007B, tab.ListHandle, (IntPtr)(-1)));
                Assert.Equal(new[] { true, false }, calls);
                Assert.True(HasItem(background, "View"), string.Join(" | ", background));    // Windows' own View sub-menu
                Assert.Single(background, i => i.Replace("&", "") == "View");             // …only once
                Assert.True(HasItem(background, "Open in new tab"), string.Join(" | ", background));
                Assert.True(HasItem(background, "Sort by"), string.Join(" | ", background));
                Assert.True(HasItem(background, "New"), string.Join(" | ", background));
                Assert.True(HasItem(background, "Properties"), string.Join(" | ", background));
                Assert.Equal(2, tab.MenusShown);

                // A genuine right-button press on a visible row: the list selects it and the full menu opens for it.
                int top = (int)SendMessage(tab.ListHandle, 0x1027 /* LVM_GETTOPINDEX */, IntPtr.Zero, IntPtr.Zero);
                int target = top + 2;
                var row = ListItemRect(tab.ListHandle, target);
                var lParam = (IntPtr)(((row.Top + row.Height / 2) << 16) | (row.Left + 6));
                var mouse = CaptureMenu(() =>
                {
                    PostMessage(tab.ListHandle, 0x0205 /* WM_RBUTTONUP */, IntPtr.Zero, lParam);
                    SendMessage(tab.ListHandle, 0x0204 /* WM_RBUTTONDOWN */, (IntPtr)2, lParam);
                });
                Assert.True(calls.SequenceEqual(new[] { true, false, true }),
                    $"calls={string.Join(",", calls)} menus={tab.MenusShown} selected={tab.Counts().Selected} row={row} items={string.Join(" | ", mouse)}");
                Assert.True(HasItem(mouse, "Copy to the other pane"), string.Join(" | ", mouse));
                Assert.True(HasItem(mouse, "Properties"), string.Join(" | ", mouse));
                Assert.Equal(3, tab.MenusShown);
                Assert.Equal(tab.ItemAt(target)!.ParsingName, tab.Selection().Single().ParsingName);
            }
            finally
            {
                BrowserTab.MenuExtras = null;
                tab.Dispose();
            }
        });
    }

    [Fact]
    public void Colour_cache_and_unloading_idle_tabs()
    {
        ShellViewTests.RunSta(() =>
        {
            BrowserTab.ClassicList = true;
            using var form = new QuietForm { Size = new Size(1000, 700) };
            var tab = new BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            tab.Host.Visible = true;
            form.Show();
            int navigations = 0;
            tab.Navigated += _ => navigations++;
            tab.EnsureCreated();
            Assert.True(ShellViewTests.Pump(() => navigations >= 1 && tab.Counts().Total > 0, 15000));

            var highlighter = new Highlighter(null, ColorRule.Defaults(), "#8A8A8A");
            int total = tab.Counts().Total;
            var first = Enumerable.Range(0, total).Select(i => tab.ColorAt(i, highlighter)).ToList();
            Assert.Equal(total, tab.CachedColorCount);
            // Same answers from the cache, and nothing new gets cached.
            var second = Enumerable.Range(0, total).Select(i => tab.ColorAt(i, highlighter)).ToList();
            Assert.Equal(first, second);
            Assert.Equal(total, tab.CachedColorCount);
            // And they match what the rules say for each item.
            for (int i = 0; i < total; i += Math.Max(1, total / 25))
                Assert.Equal(highlighter.ColorFor(tab.ItemAt(i)!), first[i]);

            // Unload frees the view but keeps the folder; showing the tab again restores it.
            tab.Unload();
            Assert.False(tab.IsCreated);
            Assert.Equal(0, tab.CachedColorCount);
            Assert.True(PathUtil.SamePath(tab.Path, @"C:\Windows"));
            tab.EnsureCreated();
            Assert.True(ShellViewTests.Pump(() => navigations >= 2 && tab.Counts().Total > 0, 15000));
            Assert.True(PathUtil.SamePath(tab.Path, @"C:\Windows"), tab.Path);
            tab.Dispose();
        });
    }

    [Fact]
    public void Copy_and_move_take_items_straight_from_the_view()
    {
        var root = Path.Combine(Path.GetTempPath(), "explorerduo-transfer-" + Guid.NewGuid().ToString("N"));
        var source = Directory.CreateDirectory(Path.Combine(root, "src")).FullName;
        var target = Directory.CreateDirectory(Path.Combine(root, "dst")).FullName;
        try
        {
            for (int i = 0; i < 30; i++) File.WriteAllText(Path.Combine(source, $"file{i:00}.txt"), new string('x', 1000 + i));
            File.WriteAllText(Path.Combine(source, "keep.dat"), "stays");

            ShellViewTests.RunSta(() =>
            {
                using var form = new QuietForm { Size = new Size(900, 600) };
                var tab = new BrowserTab(source);
                form.Controls.Add(tab.Host);
                tab.Host.Visible = true;
                form.Show();
                tab.EnsureCreated();
                Assert.True(ShellViewTests.Pump(() => tab.Counts().Total == 31, 15000));

                Assert.Equal(30, tab.SelectByPattern("*.txt", select: true));
                Assert.True(ShellViewTests.Pump(() => tab.Counts().Selected == 30, 3000));

                var copy = FileTransfer.RunAsync(tab.SelectionIdLists(), target, FileOp.Copy, IntPtr.Zero);
                Assert.True(ShellViewTests.Pump(() => copy.IsCompleted, 30000), "copy did not finish");
                copy.GetAwaiter().GetResult();
                Assert.Equal(30, Directory.GetFiles(target, "*.txt").Length);
                Assert.Equal(31, Directory.GetFiles(source).Length);

                var moved = Directory.CreateDirectory(Path.Combine(root, "moved")).FullName;
                var move = FileTransfer.RunAsync(tab.SelectionIdLists(), moved, FileOp.Move, IntPtr.Zero);
                Assert.True(ShellViewTests.Pump(() => move.IsCompleted, 30000), "move did not finish");
                move.GetAwaiter().GetResult();
                Assert.Equal(30, Directory.GetFiles(moved, "*.txt").Length);
                Assert.Equal(new[] { "keep.dat" }, Directory.GetFiles(source).Select(Path.GetFileName));
                tab.Dispose();
            });
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    [Fact]
    public void Cloud_status_column_is_shown_in_onedrive_folders_only()
    {
        var root = CloudStatus.Roots.FirstOrDefault();
        if (root == null) return; // no OneDrive / cloud folder on this machine
        Assert.True(CloudStatus.IsCloudPath(root));
        Assert.True(CloudStatus.IsCloudPath(Path.Combine(root, "Documents")));
        Assert.False(CloudStatus.IsCloudPath(@"C:\Windows"));
        Assert.False(CloudStatus.IsCloudPath(root + "-not-really"));
        Assert.Equal("System.StorageProviderUIStatus", CloudStatus.NameOf(CloudStatus.StatusKey));

        ShellViewTests.RunSta(() =>
        {
            using var form = new QuietForm { Size = new Size(1000, 700) };
            var tab = new BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            tab.Host.Visible = true;
            form.Show();
            int navigations = 0;
            tab.Navigated += _ => navigations++;
            tab.EnsureCreated();
            Assert.True(ShellViewTests.Pump(() => navigations >= 1, 15000));
            string Columns() => string.Join(", ", CloudStatus.VisibleColumns(tab.ShellView!).Select(CloudStatus.NameOf));
            Assert.DoesNotContain("System.StorageProviderUIStatus", Columns());

            Assert.True(tab.Navigate(root));
            Assert.True(ShellViewTests.Pump(() => navigations >= 2, 15000));
            ShellViewTests.Pump(() => false, 300);
            var cols = Columns();
            Assert.Contains("System.StorageProviderUIStatus", cols);
            // Right after the name.
            var list = cols.Split(", ").ToList();
            Assert.Equal(list.IndexOf("System.ItemNameDisplay") + 1, list.IndexOf("System.StorageProviderUIStatus"));
            tab.Dispose();
        });
    }

    [Fact]
    public void Tree_mode_setting_migrates_from_show_tree()
    {
        var file = Path.Combine(Path.GetTempPath(), $"explorerduo-tm-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(file, "{ \"showTree\": false }");
            Assert.Equal("none", Settings.Load(file).TreeMode);
            File.WriteAllText(file, "{ \"showTree\": true }");
            Assert.Equal("left", Settings.Load(file).TreeMode);
            File.WriteAllText(file, "{ \"showTree\": false, \"treeMode\": \"Panes\" }");
            Assert.Equal("panes", Settings.Load(file).TreeMode);
            File.WriteAllText(file, "{ \"treeMode\": \"sideways\" }");
            Assert.Equal("left", Settings.Load(file).TreeMode);
            Assert.Equal(10, Settings.Load(file).UnloadIdleTabsMinutes);
        }
        finally
        {
            File.Delete(file);
        }
    }

    [Fact]
    public void Main_window_can_show_a_tree_in_each_pane()
    {
        ShellViewTests.RunSta(() =>
        {
            var settingsFile = Path.Combine(Path.GetTempPath(), $"explorerduo-panes-{Guid.NewGuid():N}.json");
            var settings = Settings.Load(settingsFile);
            settings.TreeMode = "panes";
            settings.Session.Left = new() { @"C:\Windows" };
            settings.Session.Right = new() { @"C:\Program Files" };
            try
            {
                using var form = new MainForm(settings, Array.Empty<string>())
                {
                    ActivateOnShow = false, ShowInTaskbar = false,
                    StartPosition = FormStartPosition.Manual, Location = new Point(-8000, 100), Size = new Size(1500, 860),
                };
                form.Show();
                ShellViewTests.Pump(() => false, 3000);

                Assert.NotNull(form.LeftPane.Tree);
                Assert.NotNull(form.RightPane.Tree);
                Assert.True(form.LeftPane.Tree!.IsReady && form.RightPane.Tree!.IsReady);
                // Each pane's tree follows its own pane.
                Assert.True(ShellViewTests.Pump(() => PathUtil.SamePath(form.LeftPane.Tree!.SelectedPath(), @"C:\Windows")
                                                      && PathUtil.SamePath(form.RightPane.Tree!.SelectedPath(), @"C:\Program Files"), 8000),
                    $"{form.LeftPane.Tree!.SelectedPath()} / {form.RightPane.Tree!.SelectedPath()} panes: {form.LeftPane.Current?.Path} / {form.RightPane.Current?.Path} revealed: {form.LeftPane.Tree!.Revealed}");
                Assert.NotEqual(IntPtr.Zero, GetProp(form.LeftPane.Tree!.TreeViewHandle, "OleDropTargetInterface"));

                form.SetTreeModeForTest("left");
                Assert.Null(form.LeftPane.Tree);
                Assert.Null(form.RightPane.Tree);
                Assert.Equal("left", settings.TreeMode);
                form.SetTreeModeForTest("none");
                Assert.Equal("none", settings.TreeMode);
                form.Close();
            }
            finally
            {
                File.Delete(settingsFile);
            }
        });
    }
}

public class PaletteUpgradeTests
{
    [Fact]
    public void Old_bright_colours_move_to_the_new_palette_once()
    {
        var file = Path.Combine(Path.GetTempPath(), $"explorerduo-pal-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(file, """
            { "hiddenColor": "#8A8A8A",
              "highlights": { "C:\\A": "#D13438", "C:\\B": "#123456" },
              "colorRules": [ { "name": "Programs & scripts", "patterns": "*.exe", "color": "#107C10" },
                              { "name": "Mine", "patterns": "*.x", "color": "#ABCDEF" } ] }
            """);
            var s = ExplorerDuo.Settings.Load(file);
            Assert.True(s.Highlights.ContainsKey(@"C:\A"), string.Join(";", s.Highlights.Keys) + " | " + System.Text.Json.JsonDocument.Parse(File.ReadAllText(file)).RootElement);
            Assert.Equal("#A8433A", s.Highlights[@"C:\A"]);
            Assert.Equal("#123456", s.Highlights[@"C:\B"]);   // custom colours untouched
            Assert.Equal("#4F7A55", s.ColorRules[0].Color);
            Assert.Equal("#ABCDEF", s.ColorRules[1].Color);
            Assert.Equal("#A39C90", s.HiddenColor);
            Assert.Equal(2, s.PaletteVersion);
            s.Save();
            s.Highlights[@"C:\C"] = "#D13438"; // chosen after the upgrade → stays
            s.Save();
            Assert.Equal("#D13438", ExplorerDuo.Settings.Load(file).Highlights[@"C:\C"]);
        }
        finally
        {
            File.Delete(file);
        }
    }
}
