using ExplorerDuo;
using Xunit;

public class PathUtilTests
{
    [Theory]
    [InlineData(@"C:\Data\", @"c:\data", true)]
    [InlineData(@"C:\", @"C:\", true)]
    [InlineData("C:", @"C:\", true)]
    [InlineData("shell:::{20D04FE0-3AEA-1069-A2D8-08002B30309D}", PathUtil.ThisPc, true)]
    [InlineData(@"C:\a", @"C:\b", false)]
    public void SamePath(string a, string b, bool expected) => Assert.Equal(expected, PathUtil.SamePath(a, b));

    [Fact]
    public void FileSystem_detection()
    {
        Assert.True(PathUtil.IsFileSystemPath(@"C:\Windows"));
        Assert.True(PathUtil.IsFileSystemPath(@"\\server\share"));
        Assert.False(PathUtil.IsFileSystemPath(PathUtil.ThisPc));
        Assert.False(PathUtil.IsFileSystemPath("shell:Downloads"));
        Assert.False(PathUtil.IsFileSystemPath(""));
    }

    [Fact]
    public void ToParsable_expands_variables_and_strips_quotes()
    {
        Assert.Equal(Environment.GetEnvironmentVariable("WINDIR"), PathUtil.ToParsable("\"%WINDIR%\""));
        Assert.Equal(PathUtil.ThisPc, PathUtil.ToParsable("shell:::{20D04FE0-3AEA-1069-A2D8-08002B30309D}"));
    }

    [Theory]
    [InlineData(@"C:\Users\me\Documents\", "Documents")]
    [InlineData(@"D:\", @"D:\")]
    [InlineData(PathUtil.ThisPc, "This PC")]
    public void ShortTitle(string path, string expected) => Assert.Equal(expected, PathUtil.ShortTitle(path));

    [Fact]
    public void DisplayPath_uses_title_for_virtual_folders()
    {
        Assert.Equal(@"C:\Temp", PathUtil.DisplayPath(@"C:\Temp", "Temp"));
        Assert.Equal("Network", PathUtil.DisplayPath("::{F02C1A0D-BE21-4350-88B0-7367FC96EF3C}", "Network"));
    }

    [Theory]
    [InlineData(512L, "512 B")]
    [InlineData(1536L, "1.5 KB")]
    [InlineData(5L * 1024 * 1024 * 1024, "5 GB")]
    public void FormatBytes(long bytes, string expected) => Assert.Equal(expected, Pane.FormatBytes(bytes));
}

public class CommandLineTests
{
    [Fact]
    public void Paths_become_tabs()
    {
        var cmd = CommandLine.Parse(new[] { @"D:\Projects\My App", @"C:\Temp" });
        Assert.Equal("", cmd.Verb);
        Assert.Equal(new[] { @"D:\Projects\My App", @"C:\Temp" }, cmd.Paths);
    }

    [Fact]
    public void Repairs_drive_root_mangled_by_argv_quoting()
    {
        // Explorer runs: ExplorerDuo.exe "C:\"  → argv gives  C:"
        Assert.Equal(@"C:\", CommandLine.Parse(new[] { "C:\"" }).Paths.Single());
    }

    [Fact]
    public void Register_verb()
    {
        var cmd = CommandLine.Parse(new[] { "--REGISTER" });
        Assert.Equal("--register", cmd.Verb);
        Assert.Empty(cmd.Paths);
    }
}

public class SettingsTests
{
    [Fact]
    public void Round_trips_session()
    {
        var file = Path.Combine(Path.GetTempPath(), $"explorerduo-test-{Guid.NewGuid():N}.json");
        try
        {
            var s = Settings.Load(file);
            Assert.True(s.RestoreSession);
            s.Session.Left = new() { @"C:\A", @"C:\B" };
            s.Session.Right = new() { PathUtil.ThisPc };
            s.Session.LeftIndex = 1;
            s.Session.RightActive = true;
            s.Session.SplitRatio = 0.4;
            s.Session.Bounds = new[] { 10, 20, 1200, 800 };
            Assert.True(s.ShowTree);
            s.ShowTree = false;
            s.TreeWidth = 310;
            s.Save();

            var back = Settings.Load(file);
            Assert.Equal(new[] { @"C:\A", @"C:\B" }, back.Session.Left);
            Assert.Equal(new[] { PathUtil.ThisPc }, back.Session.Right);
            Assert.Equal(1, back.Session.LeftIndex);
            Assert.True(back.Session.RightActive);
            Assert.Equal(0.4, back.Session.SplitRatio);
            Assert.Equal(new[] { 10, 20, 1200, 800 }, back.Session.Bounds);
            Assert.False(back.ShowTree);
            Assert.Equal(310, back.TreeWidth);
        }
        finally
        {
            File.Delete(file);
        }
    }

    [Fact]
    public void Corrupt_file_falls_back_to_defaults()
    {
        var file = Path.Combine(Path.GetTempPath(), $"explorerduo-test-{Guid.NewGuid():N}.json");
        File.WriteAllText(file, "{ not json");
        try
        {
            var s = Settings.Load(file);
            Assert.Empty(s.Session.Left);
            Assert.True(s.RestoreSession);
        }
        finally
        {
            File.Delete(file);
        }
    }
}
