using ExplorerDuo;
using Xunit;

/// <summary>Notion / Notion Calendar dock: link policy, settings, and the dock inside the real main window.</summary>
public class NotionDockTests
{
    [Theory]
    [InlineData("https://www.notion.so/My-page-abc123", true)]
    [InlineData("https://calendar.notion.so/", true)]
    [InlineData("https://notion.so/", true)]
    [InlineData("https://acme.notion.site/Public-page", true)]
    [InlineData("https://www.notion.com/help", true)]
    [InlineData("https://file.notion.so/f/x.pdf", true)]
    [InlineData("https://accounts.google.com/o/oauth2/v2/auth?x=1", true)]
    [InlineData("https://login.microsoftonline.com/common", true)]
    [InlineData("https://github.com/notion", false)]
    [InlineData("https://notion.so.evil.example/", false)]
    [InlineData("https://evilnotion.so/", false)]
    [InlineData("https://www.google.com/search?q=notion", false)]
    public void In_app_hosts(string url, bool expected) => Assert.Equal(expected, NotionDock.IsInAppHost(new Uri(url)));

    [Theory]
    [InlineData("https://www.notion.so/team/Page-abc?pvs=4#block", "notion://www.notion.so/team/Page-abc?pvs=4#block")]
    [InlineData("https://notion.so/abc", "notion://notion.so/abc")]
    [InlineData("https://calendar.notion.so/", null)]
    [InlineData("https://github.com/", null)]
    [InlineData("not a url", null)]
    public void Notion_app_deep_links(string url, string? expected) => Assert.Equal(expected, NotionDock.ToNotionAppUrl(url));

    [Theory]
    [InlineData("https://www.notion.so/Page-abc", true)]
    [InlineData("https://calendar.notion.so/", true)]
    [InlineData("https://www.notion.so/login", false)]
    [InlineData("https://accounts.google.com/x", false)]
    [InlineData("", false)]
    public void Only_notion_pages_are_remembered(string url, bool expected) => Assert.Equal(expected, NotionDock.IsRememberable(url));

    /// <summary>The real stuck URL from a user's settings: Notion's Google popup flow must never become the start page.</summary>
    [Theory]
    [InlineData("https://app.notion.com/verifyNoPopupBlockerHtmlAndRedirect?redirectUri=https%3A%2F%2Fapp.notion.com%2Fgooglepopupredirect%3FcallbackType%3Dpopup", true)]
    [InlineData("https://app.notion.com/googlepopupredirect?callbackType=popup&popupFlowId=x", true)]
    [InlineData("https://www.notion.so/login", true)]
    [InlineData("https://www.notion.so/oauth2callback?code=1", true)]
    [InlineData("https://app.notion.com/acme/Authentication-notes-1f2e3d4c5b6a79881f2e3d4c5b6a7988", false)]
    [InlineData("https://app.notion.com/acme/Roadmap-1f2e3d4c5b6a79881f2e3d4c5b6a7988", false)]
    [InlineData("https://calendar.notion.com/event/NDU1", false)]
    public void Sign_in_flows_are_detected(string url, bool expected)
    {
        Assert.Equal(expected, NotionDock.IsAuthFlow(new Uri(url)));
        if (expected) Assert.False(NotionDock.IsRememberable(url));
        else Assert.True(NotionDock.IsRememberable(url));
    }

    [Fact]
    public void A_saved_sign_in_page_is_not_reopened_on_start()
    {
        using var dock = new NotionDock("notion",
            "https://app.notion.com/verifyNoPopupBlockerHtmlAndRedirect?redirectUri=https%3A%2F%2Fapp.notion.com%2Fgooglepopupredirect%3FcallbackType%3Dpopup%26redirectToAuth%3Dtrue",
            "https://calendar.notion.com/event/NDU1Nzg3");
        Assert.Equal(NotionDock.NotionHome, dock.NotionUrl);
        Assert.Equal("https://calendar.notion.com/event/NDU1Nzg3", dock.CalendarUrl);
    }

    [Fact]
    public void New_notion_domains_are_handled()
    {
        Assert.True(NotionDock.IsInAppHost(new Uri("https://app.notion.com/")));
        Assert.True(NotionDock.IsCalendarHost(new Uri("https://calendar.notion.com/")));
        Assert.Equal("notion://app.notion.com/acme/Page-abc", NotionDock.ToNotionAppUrl("https://app.notion.com/acme/Page-abc"));
        Assert.Null(NotionDock.ToNotionAppUrl("https://calendar.notion.com/"));
    }

    [Theory]
    [InlineData("Roadmap | Notion", "Notion", "Roadmap")]
    [InlineData("Notion", "Notion", "")]
    [InlineData("Notion Calendar", "Notion Calendar", "")]
    [InlineData("Week of Nov 23 | Notion Calendar", "Notion Calendar", "Week of Nov 23")]
    [InlineData(null, "Notion", "")]
    public void Page_titles_drop_the_app_name(string? title, string app, string expected) =>
        Assert.Equal(expected, NotionDock.CleanTitle(title, app));

    [Fact]
    public void Settings_round_trip_and_old_files()
    {
        var file = Path.Combine(Path.GetTempPath(), $"explorerduo-notion-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(file, "{ \"treeMode\": \"left\", \"notionDockVisible\": true, \"notionDockHeight\": 300 }"); // 2.4 / early 2.5
            var old = Settings.Load(file);
            Assert.Equal("files", old.Workspace);
            Assert.False(old.NotionIntroSeen);
            Assert.Equal("notion", old.NotionDockTab);
            Assert.Equal("", old.NotionUrl);

            old.Workspace = "calendar";
            old.NotionIntroSeen = true;
            old.NotionDockTab = "calendar";
            old.NotionUrl = "https://www.notion.so/Page-abc";
            old.Save();
            var back = Settings.Load(file);
            Assert.Equal("calendar", back.Workspace);
            Assert.True(back.NotionIntroSeen);
            Assert.Equal("calendar", back.NotionDockTab);
            Assert.Equal("https://www.notion.so/Page-abc", back.NotionUrl);

            File.WriteAllText(file, "{ \"workspace\": \"bogus\", \"notionDockTab\": \"weird\", \"notionUrl\": null }");
            var bad = Settings.Load(file);
            Assert.Equal("files", bad.Workspace);
            Assert.Equal("notion", bad.NotionDockTab);
            Assert.Equal("", bad.NotionUrl);
        }
        finally
        {
            File.Delete(file);
        }
    }

    /// <summary>Ctrl+J path: Notion replaces the panes full-window (real WebView2, created lazily) and switches back.</summary>
    [Fact]
    public void Workspace_switches_between_files_and_notion_full_window()
    {
        ShellViewTests.RunSta(() =>
        {
            var settingsFile = Path.Combine(Path.GetTempPath(), $"explorerduo-notionws-{Guid.NewGuid():N}.json");
            var profile = Path.Combine(Path.GetTempPath(), $"explorerduo-webview-{Guid.NewGuid():N}");
            var oldProfile = NotionDock.DataFolder;
            NotionDock.DataFolder = profile; // never touch the user's real Notion sign-in
            var settings = Settings.Load(settingsFile);
            settings.Session.Left = new() { @"C:\Windows" };
            settings.Session.Right = new() { @"C:\Program Files" };
            try
            {
                using var form = new MainForm(settings, Array.Empty<string>())
                {
                    ActivateOnShow = false, ShowInTaskbar = false,
                    StartPosition = FormStartPosition.Manual, Location = new Point(-8000, 100), Size = new Size(1400, 900),
                };
                form.Show();
                ShellViewTests.Pump(() => false, 1500);

                Assert.Equal("files", form.CurrentWorkspace);
                Assert.False(form.NotionPanel.Visible);
                Assert.True(form.WorkspaceSwitcher.Spotlight); // never opened → gold dot
                Assert.Null(form.NotionPanel.NotionView); // nothing created until Notion is opened

                form.ToggleNotion();
                Assert.Equal("notion", form.CurrentWorkspace);
                Assert.True(form.NotionPanel.Visible);
                Assert.False(form.LeftPane.Visible || form.RightPane.Visible); // full window: panes are hidden
                Assert.True(form.NotionPanel.Height > form.ClientSize.Height * 0.75, $"{form.NotionPanel.Height} of {form.ClientSize.Height}");
                Assert.False(form.WorkspaceSwitcher.Spotlight);
                Assert.True(settings.NotionIntroSeen);
                Assert.Equal("notion", form.WorkspaceSwitcher.Active);
                Assert.Contains("Notion", form.Text);
                Assert.NotNull(form.NotionPanel.NotionView);
                Assert.Null(form.NotionPanel.CalendarView); // calendar only when shown
                Assert.True(ShellViewTests.Pump(() => form.NotionPanel.NotionView!.CoreWebView2 != null, 30000),
                    "WebView2 did not initialise");
                Assert.True(Directory.Exists(profile));

                form.SetWorkspace("calendar");
                Assert.NotNull(form.NotionPanel.CalendarView);
                Assert.Equal("calendar", settings.NotionDockTab);
                Assert.Contains("Notion Calendar", form.Text);

                form.ToggleNotion(); // back to the files
                Assert.Equal("files", form.CurrentWorkspace);
                Assert.Equal("files", settings.Workspace);
                Assert.False(form.NotionPanel.Visible);
                Assert.True(form.LeftPane.Visible && form.RightPane.Visible);
                Assert.DoesNotContain("Notion", form.Text);

                form.ToggleNotion(); // Ctrl+J returns to the Notion side used last
                Assert.Equal("calendar", form.CurrentWorkspace);
                form.Close();
            }
            finally
            {
                NotionDock.DataFolder = oldProfile;
                File.Delete(settingsFile);
            }
        });
    }

    [Fact]
    public void App_icon_has_every_taskbar_size()
    {
        using var icon = AppIcon.Create();
        Assert.True(icon.Width >= 16);
        var file = Path.Combine(Path.GetTempPath(), $"explorerduo-icon-{Guid.NewGuid():N}.ico");
        try
        {
            AppIcon.WriteIco(file);
            // ICONDIR: entry widths (0 = 256). System.Drawing.Icon never picks the 256 entry, so read the header.
            var bytes = File.ReadAllBytes(file);
            int count = BitConverter.ToInt16(bytes, 4);
            var widths = Enumerable.Range(0, count).Select(i => bytes[6 + 16 * i] == 0 ? 256 : bytes[6 + 16 * i]).ToArray();
            Assert.Equal(AppIcon.IcoSizes, widths);
            using var big = new Icon(file, 128, 128);
            Assert.Equal(128, big.Width);
            using var small = new Icon(file, 16, 16);
            Assert.Equal(16, small.Width);
        }
        finally
        {
            File.Delete(file);
        }
        // Regenerates src/ExplorerDuo/app.ico when asked (EXPLORERDUO_WRITE_ICON=path).
        var target = Environment.GetEnvironmentVariable("EXPLORERDUO_WRITE_ICON");
        if (!string.IsNullOrEmpty(target)) AppIcon.WriteIco(target);
    }
}
