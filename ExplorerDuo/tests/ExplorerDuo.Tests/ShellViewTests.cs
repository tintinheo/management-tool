using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.ExceptionServices;
using System.Runtime.InteropServices;
using ExplorerDuo;
using Xunit;

/// <summary>
/// Exercises the real embedded Explorer view (IExplorerBrowser) inside a window that is never shown,
/// so the COM vtable declarations, navigation, history and selection are verified without touching the desktop.
/// </summary>
public class ShellViewTests
{
    sealed class QuietForm : Form
    {
        public QuietForm() => ShowInTaskbar = false;
        protected override bool ShowWithoutActivation => true;
    }

    internal static void RunSta(Action action)
    {
        Exception? error = null;
        var thread = new Thread(() =>
        {
            try { action(); }
            catch (Exception ex) { error = ex; }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        if (!thread.Join(TimeSpan.FromSeconds(90))) throw new TimeoutException("STA test hung");
        if (error != null) ExceptionDispatchInfo.Throw(error);
    }

    internal static bool Pump(Func<bool> done, int timeoutMs)
    {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            Application.DoEvents();
            if (done()) return true;
            Thread.Sleep(20);
        }
        return done();
    }

    [Fact]
    public void Parses_shell_locations()
    {
        foreach (var location in new[] { @"C:\Windows", PathUtil.ThisPc, "shell:Downloads", "%USERPROFILE%" })
        {
            var pidl = Shell.Parse(location);
            Assert.True(pidl != IntPtr.Zero, $"could not parse {location}");
            Shell.Free(pidl);
        }
        Assert.Equal(IntPtr.Zero, Shell.Parse(@"C:\no\such\folder\here"));
    }

    [Fact]
    public void Embedded_explorer_view_navigates_and_reports_state()
    {
        RunSta(() =>
        {
            using var form = new Form { ShowInTaskbar = false, Size = new Size(900, 600) };
            var tab = new BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            tab.Host.Visible = true;
            int navigations = 0;
            tab.Navigated += _ => navigations++;

            tab.EnsureCreated();
            Assert.True(Pump(() => navigations >= 1, 15000), "no navigation event");
            Assert.True(PathUtil.SamePath(tab.Path, @"C:\Windows"), tab.Path);
            Assert.False(string.IsNullOrEmpty(tab.Title));

            Assert.True(Pump(() => tab.Counts().Total > 0, 10000), "view never enumerated C:\\Windows");
            Assert.Equal(0, tab.Counts().Selected);
            Assert.Empty(tab.Selection());

            Assert.True(tab.Navigate(PathUtil.ThisPc));
            Assert.True(Pump(() => navigations >= 2, 10000));
            Assert.True(PathUtil.SamePath(tab.Path, PathUtil.ThisPc), tab.Path);

            Assert.True(tab.Back());
            Assert.True(Pump(() => navigations >= 3, 10000));
            Assert.True(PathUtil.SamePath(tab.Path, @"C:\Windows"), tab.Path);

            Assert.True(tab.Forward());
            Assert.True(Pump(() => navigations >= 4, 10000));
            Assert.True(PathUtil.SamePath(tab.Path, PathUtil.ThisPc), tab.Path);

            Assert.True(tab.Navigate(@"C:\Windows\System32"));
            Assert.True(Pump(() => navigations >= 5, 10000));
            Assert.True(tab.Up());
            Assert.True(Pump(() => navigations >= 6, 10000));
            Assert.True(PathUtil.SamePath(tab.Path, @"C:\Windows"), tab.Path);

            Assert.False(tab.Navigate(@"C:\no\such\folder\here"));

            // Accelerator forwarding must not throw even for keys the view ignores.
            var msg = new MSG { message = 0x0100, wParam = (IntPtr)Keys.F24 };
            tab.TranslateAccelerator(ref msg);
            tab.Dispose();
        });
    }

    [Fact]
    public void Lazy_tab_does_not_create_view_until_shown()
    {
        RunSta(() =>
        {
            var tab = new BrowserTab(@"C:\Windows");
            Assert.False(tab.IsCreated);
            Assert.Equal("Windows", tab.Title);
            Assert.True(tab.Navigate(@"C:\Program Files"));
            Assert.Equal(@"C:\Program Files", tab.Path);
            Assert.False(tab.Navigate(@"C:\no\such\folder\here"));
            Assert.False(tab.IsCreated);
            tab.Dispose();
        });
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string? cls, string? title);
    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);

    [Fact]
    public void Folder_tree_reveals_deep_folder_and_reports_user_picks()
    {
        RunSta(() =>
        {
            using var form = new Form { ShowInTaskbar = false, Size = new Size(320, 700) };
            var tree = new FolderTree();
            form.Controls.Add(tree);
            _ = form.Handle;
            _ = tree.Handle;
            Assert.True(tree.IsReady);
            Assert.NotEqual(IntPtr.Zero, tree.TreeHandle);
            Assert.Equal(9, tree.RootCount); // Home, 6 user folders, This PC, Network

            var picks = new List<string>();
            tree.FolderPicked += (_, path) => picks.Add(path);

            Assert.True(tree.Reveal(@"C:\Windows\System32"), "reveal failed");
            Assert.True(PathUtil.SamePath(tree.SelectedPath(), @"C:\Windows\System32"), tree.SelectedPath());
            Assert.True(tree.IsExpanded(@"C:\Windows"));
            Pump(() => false, 400);
            Assert.Empty(picks); // programmatic reveal must not navigate

            // Simulate the user pressing ↑ in the tree: selection moves to a sibling → one debounced pick.
            var treeView = FindWindowEx(tree.TreeHandle, IntPtr.Zero, "SysTreeView32", null);
            if (treeView == IntPtr.Zero) treeView = tree.TreeHandle;
            SendMessage(treeView, 0x0100 /* WM_KEYDOWN */, (IntPtr)Keys.Up, IntPtr.Zero);
            Assert.True(Pump(() => picks.Count > 0, 3000), "no pick event from keyboard selection");
            Assert.False(PathUtil.SamePath(picks[^1], @"C:\Windows\System32"));
            Assert.Single(picks);
        });
    }

    [StructLayout(LayoutKind.Sequential)]
    struct LVITEM
    {
        public uint mask;
        public int iItem;
        public int iSubItem;
        public uint state;
        public uint stateMask;
        public IntPtr pszText;
        public int cchTextMax;
        public int iImage;
        public IntPtr lParam;
        public int iIndent;
        public int iGroupId;
        public uint cColumns;
        public IntPtr puColumns;
        public IntPtr piColFmt;
        public int iGroup;
    }

    static string ListItemText(IntPtr list, int index)
    {
        var text = Marshal.AllocHGlobal(1024);
        var item = Marshal.AllocHGlobal(Marshal.SizeOf<LVITEM>());
        try
        {
            Marshal.StructureToPtr(new LVITEM { iSubItem = 0, pszText = text, cchTextMax = 512 }, item, false);
            SendMessage(list, 0x1073 /* LVM_GETITEMTEXTW */, (IntPtr)index, item);
            return Marshal.PtrToStringUni(text) ?? "";
        }
        finally
        {
            Marshal.FreeHGlobal(text);
            Marshal.FreeHGlobal(item);
        }
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

    [Fact]
    public void Classic_list_view_maps_rows_to_items_hit_tests_and_selects_by_pattern()
    {
        RunSta(() =>
        {
            BrowserTab.ClassicList = true;
            using var form = new QuietForm { Size = new Size(1000, 700), StartPosition = FormStartPosition.Manual, Location = new Point(-8000, 100) };
            var tab = new BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            tab.Host.Visible = true;
            form.Show();
            int navigations = 0;
            tab.Navigated += _ => navigations++;
            tab.EnsureCreated();
            Assert.True(Pump(() => navigations >= 1 && tab.Counts().Total > 0, 15000));

            Assert.NotEqual(IntPtr.Zero, tab.ListHandle); // classic layout active → colouring + hit-testing possible
            int rows = (int)SendMessage(tab.ListHandle, 0x1004 /* LVM_GETITEMCOUNT */, IntPtr.Zero, IntPtr.Zero);
            Assert.Equal(tab.Counts().Total, rows);

            // Row i of the list control is item i of the view (what the colour hook relies on).
            foreach (var i in new[] { 0, rows / 2, rows - 1 })
            {
                var entry = tab.ItemAt(i);
                Assert.NotNull(entry);
                Assert.Equal(ListItemText(tab.ListHandle, i), entry!.Name);
            }

            // Middle-click hit test: centre of row 3's label → item 3; far below the rows → nothing.
            var r = ListItemRect(tab.ListHandle, 3);
            Assert.False(r.IsEmpty, "row 3 has no rectangle");
            var hit = tab.HitTest(new Point(r.Left + 4, r.Top + r.Height / 2));
            Assert.Equal(tab.ItemAt(3)!.ParsingName, hit?.ParsingName);

            // Select by pattern / invert.
            int exes = tab.SelectByPattern("*.exe", select: true);
            Assert.True(exes > 0, "C:\\Windows has no .exe?");
            Assert.True(Pump(() => tab.Counts().Selected == exes, 3000));
            Assert.All(tab.Selection(), s => Assert.EndsWith(".exe", s.ParsingName, StringComparison.OrdinalIgnoreCase));
            tab.InvertSelection();
            Assert.True(Pump(() => tab.Counts().Selected == rows - exes, 3000));
            tab.SelectByPattern("*.*", select: false);
            Assert.True(Pump(() => tab.Counts().Selected == 0, 3000));

            // Colour hook: force the list to paint into a bitmap; rows matching a rule must be coloured red.
            var previous = Highlights.Current;
            Highlights.Current = new Highlighter(null, new[] { new ColorRule { Patterns = "*", Color = "#FF0000" } }, null);
            try
            {
                int before = BrowserTab.ColoredItemPaints;
                bool anyRed = false;
                var sw = Stopwatch.StartNew();
                while (!anyRed && sw.ElapsedMilliseconds < 3000)
                {
                    tab.Repaint();
                    UpdateWindow(tab.ListHandle); // synchronous WM_PAINT through the custom-draw hook
                    Pump(() => false, 100);
                    using var bmp = new Bitmap(900, 600);
                    using (var g = Graphics.FromImage(bmp))
                    {
                        var hdc = g.GetHdc();
                        PrintWindow(tab.ListHandle, hdc, 2);
                        g.ReleaseHdc(hdc);
                    }
                    for (int y = 0; y < bmp.Height && !anyRed; y += 2)
                        for (int x = 0; x < 300 && !anyRed; x++)
                        {
                            var p = bmp.GetPixel(x, y);
                            anyRed = p.R > 180 && p.G < 90 && p.B < 90;
                        }
                }
                Assert.True(BrowserTab.ColoredItemPaints > before, "custom draw never coloured a row");
                Assert.True(anyRed, "no red text pixels in the rendered list");
            }
            finally
            {
                Highlights.Current = previous;
            }

            // Find-files result: select a specific file once listed.
            tab.SelectWhenLoaded(@"C:\Windows\notepad.exe");
            Assert.True(Pump(() => tab.Selection().Any(s => PathUtil.SamePath(s.ParsingName, @"C:\Windows\notepad.exe")), 5000));
            Assert.Equal(1, tab.Counts().Selected);
            tab.Dispose();
        });
    }

    [Fact]
    public void Folder_tree_hit_test_finds_item_under_point()
    {
        RunSta(() =>
        {
            // Per-row paint callbacks only run while there are colour rules.
            Highlights.Current = new Highlighter(null, ColorRule.Defaults(), "#8A8A8A");
            // Shown off-screen without activation: the tree only lays items out once it is visible.
            using var form = new QuietForm { Size = new Size(320, 700), StartPosition = FormStartPosition.Manual, Location = new Point(-8000, 100) };
            var tree = new FolderTree();
            form.Controls.Add(tree);
            form.Show();
            Pump(() => false, 300);
            var picks = new List<string>();
            tree.FolderPicked += (_, path) => picks.Add(path);
            // The tree loads children asynchronously, so re-reveal until the row has settled on screen.
            Assert.True(Pump(() =>
            {
                tree.Reveal(@"C:\Windows\System32");
                return PathUtil.SamePath(tree.SelectedPath(), @"C:\Windows\System32")
                       && tree.SelectedItemRect() is { } r && r.Top >= 0 && r.Bottom <= TreeClientHeight(tree.TreeViewHandle);
            }, 5000), "selected item never scrolled into view");
            picks.Clear();

            var rect = tree.SelectedItemRect();
            Assert.NotNull(rect);
            var onLabel = new Point(rect!.Value.Left + 10, rect.Value.Top + rect.Value.Height / 2);

            // Middle-click on the item that is already selected → its path comes back immediately.
            Assert.True(tree.SelectAt(tree.TreeViewHandle, onLabel, out var already));
            Assert.True(PathUtil.SamePath(already, @"C:\Windows\System32"), already ?? "(null)");

            // Middle-click one row higher → that folder gets selected and reported once.
            var rowAbove = new Point(onLabel.X, onLabel.Y - rect.Value.Height);
            Assert.True(tree.SelectAt(tree.TreeViewHandle, rowAbove, out var already2));
            Assert.Null(already2);
            Assert.True(Pump(() => picks.Count > 0, 3000), "no pick after SelectAt");
            Assert.False(PathUtil.SamePath(picks[0], @"C:\Windows\System32"));
            Assert.StartsWith(@"C:\", picks[0], StringComparison.OrdinalIgnoreCase); // a neighbouring folder on C:
            Assert.Single(picks);

            Assert.False(tree.SelectAt(tree.TreeViewHandle, new Point(5, 5000), out _)); // nowhere
            Assert.True(tree.ItemPaintCalls > 0, "custom-draw callbacks never fired (colours would not show)");
        });
    }

    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] static extern bool UpdateWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr hwnd, out ExplorerDuoRect rect);
    [StructLayout(LayoutKind.Sequential)] struct ExplorerDuoRect { public int L, T, R, B; }

    static int TreeClientHeight(IntPtr hwnd) => GetClientRect(hwnd, out var r) ? r.B - r.T : 0;

    /// <summary>Renders the full window off-screen (never activated) to a PNG. Runs only when EXPLORERDUO_SHOT is set.</summary>
    [Fact]
    public void Screenshot_main_window()
    {
        var output = Environment.GetEnvironmentVariable("EXPLORERDUO_SHOT");
        if (string.IsNullOrEmpty(output)) return;

        RunSta(() =>
        {
            var settingsFile = Path.Combine(Path.GetTempPath(), $"explorerduo-shot-{Guid.NewGuid():N}.json");
            var settings = Settings.Load(settingsFile);
            settings.Session.Left = new() { @"C:\Windows", @"C:\Program Files", PathUtil.ThisPc };
            settings.Session.Right = new() { @"C:\Users", @"C:\Windows\System32" };
            settings.Session.LeftIndex = 0;
            settings.TreeMode = Environment.GetEnvironmentVariable("EXPLORERDUO_SHOT_TREE") ?? "left";
            if (Environment.GetEnvironmentVariable("EXPLORERDUO_SHOT_NOTION") == "1")
            {
                settings.Workspace = Environment.GetEnvironmentVariable("EXPLORERDUO_SHOT_WS") ?? "notion";
                NotionDock.DataFolder = Path.Combine(Path.GetTempPath(), "explorerduo-shot-webview");
            }
            settings.Highlights[@"C:\Windows\Boot"] = "#D13438";
            settings.Highlights[@"C:\Windows\System32"] = "#107C10";
            settings.Highlights[@"C:\Users\Public"] = "#8764B8";
            settings.Bookmarks.Add(new Bookmark { Name = "Windows", Path = @"C:\Windows" });
            settings.Bookmarks.Add(new Bookmark { Name = "Program Files", Path = @"C:\Program Files" });
            settings.Bookmarks.Add(new Bookmark { Name = "Users", Path = @"C:\Users" });

            using var form = new MainForm(settings, Array.Empty<string>())
            {
                ActivateOnShow = false, ShowInTaskbar = false,
                StartPosition = FormStartPosition.Manual, Location = new Point(-8000, 100), Size = new Size(1500, 860),
            };
            form.Show();
            Pump(() => false, 5000);
            form.Refresh(); // repaint after asynchronous icon extraction
            Pump(() => false, 6000);

            using var bmp = new Bitmap(form.Width, form.Height);
            using (var g = Graphics.FromImage(bmp))
            {
                var hdc = g.GetHdc();
                PrintWindow(form.Handle, hdc, 2 /* PW_RENDERFULLCONTENT */);
                g.ReleaseHdc(hdc);
            }
            bmp.Save(output, ImageFormat.Png);
            form.Close();
            File.Delete(settingsFile);
        });
    }
}
