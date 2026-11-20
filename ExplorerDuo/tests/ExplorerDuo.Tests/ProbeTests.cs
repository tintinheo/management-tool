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
