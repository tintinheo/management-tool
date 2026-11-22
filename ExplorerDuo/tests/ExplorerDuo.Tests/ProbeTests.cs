using System.Runtime.InteropServices;
using Xunit;
using Xunit.Abstractions;

/// <summary>Diagnostics for developing against the shell; only run when EXPLORERDUO_PROBE is set.</summary>
public class ProbeTests(ITestOutputHelper output)
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct SHFILEINFO
    {
        public IntPtr hIcon;
        public int iIcon;
        public uint dwAttributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szDisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string szTypeName;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr SHGetFileInfo(string path, uint attrs, ref SHFILEINFO info, uint size, uint flags);

    delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr parent, EnumProc proc, IntPtr l);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, System.Text.StringBuilder sb, int n);
    [DllImport("user32.dll")] static extern IntPtr GetParent(IntPtr h);

    static bool Enabled => Environment.GetEnvironmentVariable("EXPLORERDUO_PROBE") != null;

    [Fact]
    public void Probe_view_windows()
    {
        if (!Enabled) return;
        ShellViewTests.RunSta(() =>
        {
            using var form = new Form { ShowInTaskbar = false, Size = new Size(900, 600) };
            ExplorerDuo.BrowserTab.ClassicList = true;
            var tab = new ExplorerDuo.BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            tab.Host.Visible = true;
            tab.EnsureCreated();
            ShellViewTests.Pump(() => tab.Counts().Total > 0, 10000);
            output.WriteLine($"ListHandle after load: {tab.ListHandle}");
            EnumChildWindows(tab.Host.Handle, (h, _) =>
            {
                var sb = new System.Text.StringBuilder(128);
                GetClassName(h, sb, 128);
                output.WriteLine($"WIN {h} parent={GetParent(h)} {sb}");
                return true;
            }, IntPtr.Zero);
        });
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetProp(IntPtr h, string name);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindow(string? cls, string? title);
    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr h, int msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, int msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] static extern int GetMenuItemCount(IntPtr m);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetMenuString(IntPtr m, uint id, System.Text.StringBuilder sb, int n, uint flags);
    [DllImport("user32.dll")] static extern bool EndMenu();

    /// <summary>Posts WM_CONTEXTMENU to a window and lists the popup menu that appears (then closes it).</summary>
    List<string> MenuOf(IntPtr target)
    {
        var items = new List<string>();
        using var timer = new System.Windows.Forms.Timer { Interval = 300 };
        int ticks = 0;
        timer.Tick += (_, _) =>
        {
            ticks++;
            var popup = FindWindow("#32768", null);
            if (popup != IntPtr.Zero)
            {
                var menu = SendMessage(popup, 0x01E1 /* MN_GETHMENU */, IntPtr.Zero, IntPtr.Zero);
                int n = GetMenuItemCount(menu);
                for (uint i = 0; i < n; i++)
                {
                    var sb = new System.Text.StringBuilder(256);
                    GetMenuString(menu, i, sb, 256, 0x400);
                    items.Add(sb.ToString());
                }
                EndMenu();
                timer.Stop();
            }
            else if (ticks > 15) timer.Stop();
        };
        timer.Start();
        PostMessage(target, 0x007B /* WM_CONTEXTMENU */, target, (IntPtr)(-1));
        ShellViewTests.Pump(() => !timer.Enabled, 8000);
        return items;
    }

    [Fact]
    public void Probe_tree_drop_and_menus()
    {
        if (!Enabled) return;
        ShellViewTests.RunSta(() =>
        {
            using var form = new Form { ShowInTaskbar = false, Size = new Size(900, 600), StartPosition = FormStartPosition.Manual, Location = new Point(-8000, 100) };
            var tree = new ExplorerDuo.FolderTree { Dock = DockStyle.Left, Width = 300 };
            var tab = new ExplorerDuo.BrowserTab(@"C:\Windows");
            form.Controls.Add(tab.Host);
            form.Controls.Add(tree);
            tab.Host.Visible = true;
            form.Show();
            tab.EnsureCreated();
            ShellViewTests.Pump(() => tab.Counts().Total > 0, 10000);
            tree.Reveal(@"C:\Windows");
            ShellViewTests.Pump(() => false, 500);
            output.WriteLine($"tree drop target: nstc={GetProp(tree.TreeHandle, "OleDropTargetInterface")} tv={GetProp(tree.TreeViewHandle, "OleDropTargetInterface")}");
            output.WriteLine($"list drop target: {GetProp(tab.ListHandle, "OleDropTargetInterface")}");
            output.WriteLine("TREE MENU: " + string.Join(" | ", MenuOf(tree.TreeViewHandle)));
            tab.SelectWhenLoaded(@"C:\Windows\notepad.exe");
            ShellViewTests.Pump(() => tab.Counts().Selected > 0, 5000);
            output.WriteLine("LIST MENU: " + string.Join(" | ", MenuOf(tab.ListHandle)));
            var view = tab.ShellView!;
            output.WriteLine("SEL VIA API: " + string.Join(" | ", ExplorerDuo.ShellMenu.Texts(ExplorerDuo.ShellMenu.ForView(view, 1), Array.Empty<ExplorerDuo.MenuCommand>())));
            output.WriteLine("BG VIA API: " + string.Join(" | ", ExplorerDuo.ShellMenu.Texts(ExplorerDuo.ShellMenu.ForView(view, 0), Array.Empty<ExplorerDuo.MenuCommand>())));
            output.WriteLine("TREE VIA API: " + string.Join(" | ", ExplorerDuo.ShellMenu.Texts(ExplorerDuo.ShellMenu.ForItem(ExplorerDuo.Shell.CreateItem(@"C:\Windows")), Array.Empty<ExplorerDuo.MenuCommand>())));
            var r = tree.SelectedItemRect()!.Value;
            output.WriteLine($"HITTEST rect={r} sel={tree.SelectedPath()} tv={tree.HitTestPath(tree.TreeViewHandle, new Point(r.Left + 5, r.Top + 3))} above={tree.HitTestPath(tree.TreeViewHandle, new Point(r.Left + 5, r.Top - 5))}");
            Application.OleRequired();
            var tree2 = new ExplorerDuo.FolderTree { Dock = DockStyle.Left, Width = 200 };
            form.Controls.Add(tree2);
            _ = tree2.Handle;
            output.WriteLine($"after OleRequired tree drop target: nstc={GetProp(tree2.TreeHandle, "OleDropTargetInterface")} tv={GetProp(tree2.TreeViewHandle, "OleDropTargetInterface")}");
            tab.Dispose();
        });
    }

    [Fact]
    public void Probe_icon_indices()
    {
        if (!Enabled) return;
        ShellViewTests.RunSta(() =>
        {
            foreach (var p in new[] { @"C:\Windows\Boot", @"C:\Windows\Fonts", @"C:\Program Files", @"C:\Users", @"C:\nope\file.zzz" })
            {
                var info = new SHFILEINFO();
                SHGetFileInfo(p, 0, ref info, (uint)Marshal.SizeOf<SHFILEINFO>(), 0x4000 /* SHGFI_SYSICONINDEX */);
                output.WriteLine($"ICON {info.iIcon,5}  {p}");
            }
        });
    }
}
