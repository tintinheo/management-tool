using System.Diagnostics;
using System.Drawing.Drawing2D;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace ExplorerDuo;

/// <summary>
/// Bottom dock hosting the real Notion and Notion Calendar web apps (WebView2). Notion Calendar has no public
/// API, so the web app itself is embedded. Views are created on first use and share one browser profile, so a
/// sign-in survives restarts.
/// </summary>
internal sealed class NotionDock : Panel
{
    public const string NotionTab = "notion";
    public const string CalendarTab = "calendar";
    public const string NotionHome = "https://www.notion.so/";
    public const string CalendarHome = "https://calendar.notion.so/";
    public const string RuntimeDownload = "https://go.microsoft.com/fwlink/p/?LinkId=2124703";

    /// <summary>Browser profile shared by both views (tests point it at a temp folder).</summary>
    internal static string DataFolder { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ExplorerDuo", "WebView2");

    /// <summary>Hosts that stay inside the dock: Notion itself plus the sign-in providers it uses.</summary>
    static readonly string[] AppDomains = { "notion.so", "notion.com", "notion.site", "notion-static.com", "notionusercontent.com" };
    static readonly string[] SignInDomains =
    {
        "accounts.google.com", "login.microsoftonline.com", "login.live.com", "appleid.apple.com",
    };

    /// <summary>Ctrl+J pressed inside a web view (the form's key filter never sees those keys).</summary>
    public event Action? ToggleRequested;
    /// <summary>Ctrl+Shift+J pressed inside a web view.</summary>
    public event Action? SwitchRequested;
    public event Action? CloseRequested;
    /// <summary>The active tab or a page address changed (worth saving).</summary>
    public event Action? StateChanged;

    readonly HeaderTitle _title;
    readonly Panel _body;
    readonly Label _message;
    WebView2? _notion, _calendar;
    static Task<CoreWebView2Environment>? s_env;

    public NotionDock(string tab, string? notionUrl, string? calendarUrl)
    {
        BackColor = NotionLine; // hairline card outline in Notion's grey, not the ivory/gold of the file side
        Padding = new Padding(1);
        NotionUrl = ValidStart(notionUrl, NotionHome);
        CalendarUrl = ValidStart(calendarUrl, CalendarHome);
        ActiveTab = tab == CalendarTab ? CalendarTab : NotionTab;

        _body = new Panel { Dock = DockStyle.Fill, BackColor = Theme.NotionSurface };
        _message = new Label
        {
            Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Theme.InkMuted,
            Font = Theme.Ui, Visible = false, Cursor = Cursors.Hand,
        };
        _message.Click += (_, _) =>
        {
            if (_message.Tag is string url) OpenExternal(url);
            else if (_message.Tag is Action retry) retry();
        };
        _body.Controls.Add(_message);

        var header = new Panel { Dock = DockStyle.Top, Height = 44, BackColor = Theme.NotionSurface, Padding = new Padding(10, 0, 6, 1) };
        _title = new HeaderTitle { Dock = DockStyle.Fill };
        _title.Set(ActiveTab, "");
        header.Controls.Add(_title);
        // Docking runs from the last-added control, so this reads Back · Reload · Open in app | Files left to right.
        header.Controls.Add(HeaderButton(Theme.GlyphBack, "Back", () => { if (Current?.CanGoBack == true) Current.GoBack(); }));
        header.Controls.Add(HeaderButton(Theme.GlyphRefresh, "Reload", () => Current?.CoreWebView2?.Reload()));
        header.Controls.Add(HeaderButton(Theme.GlyphOpenApp, "Open in the Notion app", OpenInApp));
        header.Controls.Add(new Panel { Dock = DockStyle.Right, Width = 12, BackColor = Theme.NotionSurface });
        var files = new FilesButton { Dock = DockStyle.Right, Width = 120 };
        files.Width = files.GetPreferredSize(Size.Empty).Width;
        new ToolTip().SetToolTip(files, "Back to your Explorer panes (Ctrl+J)");
        files.Click += (_, _) => CloseRequested?.Invoke();
        header.Controls.Add(files);
        header.Paint += (_, e) =>
        {
            using var pen = new Pen(NotionLine);
            e.Graphics.DrawLine(pen, 0, header.Height - 1, header.Width, header.Height - 1);
        };

        Controls.Add(_body);
        Controls.Add(header);
    }

    /// <summary>Notion's divider grey.</summary>
    static readonly Color NotionLine = Color.FromArgb(233, 233, 231);

    public string ActiveTab { get; private set; }
    /// <summary>Title of the page shown in the active tab (for the window caption).</summary>
    public string PageTitle => CleanTitle(Current?.CoreWebView2?.DocumentTitle, ActiveTab == CalendarTab ? "Notion Calendar" : "Notion");
    public event Action? TitleChanged;
    public string NotionUrl { get; private set; }
    public string CalendarUrl { get; private set; }

    internal WebView2? NotionView => _notion;
    internal WebView2? CalendarView => _calendar;

    WebView2? Current => ActiveTab == CalendarTab ? _calendar : _notion;

    FlatButton HeaderButton(string glyph, string tip, Action action)
    {
        var b = new FlatButton { Text = glyph, Font = Theme.Icons(9f), Dock = DockStyle.Right, Width = 36, ForeColor = Theme.Ink };
        new ToolTip().SetToolTip(b, tip);
        b.Click += (_, _) => action();
        return b;
    }

    /// <summary>Shows a tab, creating its web view the first time.</summary>
    public void ShowTab(string tab, bool focus)
    {
        ActiveTab = tab == CalendarTab ? CalendarTab : NotionTab;
        _title.Set(ActiveTab, Current?.CoreWebView2?.DocumentTitle ?? "");
        StateChanged?.Invoke();
        TitleChanged?.Invoke();
        if (!Visible || Parent == null) return; // created when the dock is actually shown
        if (Current is { } existing && !IsAlive(existing)) DropView(existing); // browser process crashed earlier
        var view = Current;
        if (view == null)
        {
            _message.Visible = false;
            view = CreateView(ActiveTab);
        }
        foreach (var v in new[] { _notion, _calendar })
            if (v != null) v.Visible = v == view && !_message.Visible;
        if (focus && view.Visible) view.Focus();
    }

    /// <summary>False once the control was disposed or its browser process died (CoreWebView2 then throws).</summary>
    static bool IsAlive(WebView2 view)
    {
        if (view.IsDisposed) return false;
        if (view.Tag is "initializing") return true;
        try { return view.CoreWebView2 != null; }
        catch (InvalidOperationException) { return false; }
    }

    WebView2? ViewFor(string tab) => tab == CalendarTab ? _calendar : _notion;

    void DropView(WebView2 view)
    {
        if (_notion == view) _notion = null;
        if (_calendar == view) _calendar = null;
        _body.Controls.Remove(view);
        try { view.Dispose(); }
        catch (Exception ex) { Log.Write("webview dispose: " + ex.Message); }
    }

    /// <summary>The shared browser process is gone: both views are dead. Start over with a fresh environment.</summary>
    void RecoverAll()
    {
        s_env = null;
        foreach (var v in new[] { _notion, _calendar }) if (v != null) DropView(v);
        if (!IsDisposed && Visible) ShowTab(ActiveTab, focus: false);
    }

    public void FocusView()
    {
        if (Current is { Visible: true } v) v.Focus();
    }

    /// <summary>The dock was hidden: let Edge freeze the pages (memory, CPU).</summary>
    public void Suspend()
    {
        foreach (var v in new[] { _notion, _calendar })
        {
            if (v == null || !IsAlive(v) || v.CoreWebView2 == null) continue;
            v.Visible = false;
            _ = TrySuspend(v.CoreWebView2);
        }

        static async Task TrySuspend(CoreWebView2 core)
        {
            try { await core.TrySuspendAsync(); }
            catch (Exception ex) { Log.Write("webview suspend: " + ex.Message); }
        }
    }

    /// <summary>Signs out: deletes cookies, storage and cache of the embedded browser profile.</summary>
    public async Task ClearDataAsync()
    {
        var core = (_notion ?? _calendar)?.CoreWebView2;
        if (core != null)
        {
            await core.Profile.ClearBrowsingDataAsync();
            foreach (var v in new[] { _notion, _calendar }) v?.CoreWebView2?.Reload();
        }
        else
        {
            try { if (Directory.Exists(DataFolder)) Directory.Delete(DataFolder, recursive: true); }
            catch (Exception ex) { Log.Write("clear webview data: " + ex.Message); }
        }
        NotionUrl = NotionHome;
        CalendarUrl = CalendarHome;
        StateChanged?.Invoke();
    }

    WebView2 CreateViewSilently(string tab)
    {
        var view = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Theme.NotionSurface, Visible = false, Tag = "initializing" };
        if (tab == CalendarTab) _calendar = view; else _notion = view;
        _body.Controls.Add(view);
        view.BringToFront();
        HookKeys(view);
        return view;
    }

    void HookKeys(WebView2 view)
    {
        view.KeyDown += (_, e) =>
        {
            // Accelerator keys from inside the page arrive here.
            if (e.KeyData == (Keys.Control | Keys.J)) { e.Handled = true; BeginInvoke(() => ToggleRequested?.Invoke()); }
            else if (e.KeyData == (Keys.Control | Keys.Shift | Keys.J)) { e.Handled = true; BeginInvoke(() => SwitchRequested?.Invoke()); }
        };
    }

    WebView2 CreateView(string tab)
    {
        var view = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Theme.NotionSurface, Visible = false };
        if (tab == CalendarTab) _calendar = view; else _notion = view;
        _body.Controls.Add(view);
        view.BringToFront();
        HookKeys(view);
        view.Tag = "initializing";
        _ = InitAsync(view, tab, retried: false);
        return view;
    }

    async Task InitAsync(WebView2 view, string tab, bool retried)
    {
        try
        {
            await view.EnsureCoreWebView2Async(await SharedEnvironment());
            view.Tag = null;
        }
        catch (WebView2RuntimeNotFoundException)
        {
            ShowMessage("Notion needs the Microsoft Edge WebView2 Runtime. Click here to download it.", RuntimeDownload);
            return;
        }
        catch (Exception ex)
        {
            Log.Write($"webview init ({tab}, retried {retried}): " + ex);
            s_env = null; // the cached environment may belong to a browser process that has exited
            view.Tag = null;
            if (IsDisposed) return;
            DropView(view);
            if (!retried)
            {
                // One quiet retry with a fresh environment (fixes "Class not registered" / E_ABORT after a browser exit).
                await Task.Delay(700);
                if (IsDisposed || ViewFor(tab) != null) return;
                var again = CreateViewSilently(tab);
                _ = InitAsync(again, tab, retried: true);
                if (Visible && ActiveTab == tab) ShowTab(tab, focus: false);
                return;
            }
            ShowMessage($"Notion couldn't start ({ex.Message.Trim()}). Click here to try again.", (Action)(() =>
            {
                _message.Visible = false;
                ShowTab(ActiveTab, focus: true);
            }));
            return;
        }

        var core = view.CoreWebView2;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreDevToolsEnabled = false;
        core.NavigationStarting += (_, e) => OnNavigationStarting(e);
        core.NavigationCompleted += (_, e) =>
        {
            if (!e.IsSuccess) Log.Write($"notion navigation failed ({tab}): {e.WebErrorStatus} http {e.HttpStatusCode} {SafeSource(core)}");
        };
        core.ProcessFailed += (_, e) =>
        {
            Log.Write($"webview process failed ({tab}): {e.ProcessFailedKind} {e.Reason} exit {e.ExitCode}");
            switch (e.ProcessFailedKind)
            {
                case CoreWebView2ProcessFailedKind.RenderProcessExited:
                case CoreWebView2ProcessFailedKind.RenderProcessUnresponsive:
                    BeginInvoke(() => { try { core.Reload(); } catch { RecoverAll(); } });
                    break;
                case CoreWebView2ProcessFailedKind.BrowserProcessExited:
                    BeginInvoke(RecoverAll);
                    break;
            }
        };
        core.NewWindowRequested += (_, e) => OnNewWindowRequested(e, tab);
        core.DocumentTitleChanged += (_, _) =>
        {
            if (ActiveTab != tab) return;
            _title.Set(tab, core.DocumentTitle);
            TitleChanged?.Invoke();
        };
        core.SourceChanged += (_, _) =>
        {
            var url = core.Source;
            if (!IsRememberable(url)) return;
            if (tab == CalendarTab) CalendarUrl = url; else NotionUrl = url;
            StateChanged?.Invoke();
        };
        core.Navigate(tab == CalendarTab ? CalendarUrl : NotionUrl);
        if (!IsDisposed && Visible && ActiveTab == tab) ShowTab(tab, focus: false);
    }

    static string SafeSource(CoreWebView2 core)
    {
        try { return core.Source; } catch { return ""; }
    }

    static Task<CoreWebView2Environment> SharedEnvironment()
    {
        return s_env ??= CoreWebView2Environment.CreateAsync(null, DataFolder);
    }

    void OnNavigationStarting(CoreWebView2NavigationStartingEventArgs e)
    {
        if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri)) return;
        if (uri.Scheme is not ("http" or "https" or "about" or "data" or "blob"))
        {
            e.Cancel = true; // notion:, mailto:, tel: … → the program Windows has for them
            OpenExternal(e.Uri);
            return;
        }
        // Links the user clicks to other sites open in the default browser; redirects (sign-in, SSO) stay here.
        if (e.IsUserInitiated && !e.IsRedirected && !IsInAppHost(uri))
        {
            e.Cancel = true;
            OpenExternal(e.Uri);
        }
    }

    void OnNewWindowRequested(CoreWebView2NewWindowRequestedEventArgs e, string fromTab)
    {
        Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri);
        bool popupWindow = e.WindowFeatures != null && (e.WindowFeatures.HasSize || e.WindowFeatures.HasPosition);
        if (uri == null || uri.Scheme == "about" || IsSignInHost(uri) || IsAuthFlow(uri) || (popupWindow && IsInAppHost(uri)))
        {
            // Sign-in popups need a real window with window.opener intact.
            e.Handled = true;
            _ = OpenPopupAsync(e, e.GetDeferral());
            return;
        }
        e.Handled = true;
        if (IsInAppHost(uri))
        {
            // A Notion page opened from Calendar (or "open in new tab") shows in the matching dock tab.
            var target = IsCalendarHost(uri) ? CalendarTab : NotionTab;
            if (target == NotionTab) NotionUrl = e.Uri; else CalendarUrl = e.Uri;
            var view = target == fromTab ? Current : target == CalendarTab ? _calendar : _notion;
            if (view?.CoreWebView2 != null) view.CoreWebView2.Navigate(e.Uri);
            ShowTab(target, focus: true);
            return;
        }
        OpenExternal(e.Uri);
    }

    async Task OpenPopupAsync(CoreWebView2NewWindowRequestedEventArgs e, CoreWebView2Deferral deferral)
    {
        try
        {
            var owner = FindForm();
            var popup = new Form
            {
                Text = "Sign in", Icon = owner?.Icon, ShowInTaskbar = false, BackColor = Theme.Surface,
                StartPosition = FormStartPosition.CenterParent, Size = new Size(LogicalToDeviceUnits(520), LogicalToDeviceUnits(700)),
            };
            if (owner != null)
                popup.Location = new Point(owner.Left + (owner.Width - popup.Width) / 2, owner.Top + (owner.Height - popup.Height) / 2);
            popup.StartPosition = FormStartPosition.Manual;
            var view = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Theme.Surface };
            popup.Controls.Add(view);
            popup.Show(owner);
            await view.EnsureCoreWebView2Async(await SharedEnvironment());
            view.CoreWebView2.WindowCloseRequested += (_, _) => popup.Close();
            view.CoreWebView2.DocumentTitleChanged += (_, _) => popup.Text = view.CoreWebView2.DocumentTitle;
            e.NewWindow = view.CoreWebView2;
        }
        catch (Exception ex)
        {
            Log.Write("sign-in popup: " + ex);
            OpenExternal(e.Uri);
        }
        finally
        {
            deferral.Complete();
        }
    }

    void OpenInApp()
    {
        if (ActiveTab == CalendarTab)
        {
            var exe = FindCalendarApp();
            if (exe != null) Start(exe);
            else OpenExternal(CalendarUrl);
            return;
        }
        var deep = ToNotionAppUrl(Current?.CoreWebView2?.Source ?? NotionUrl);
        if (deep == null || !Start(deep)) OpenExternal(NotionUrl);
    }

    void ShowMessage(string text, object? clickAction)
    {
        if (IsDisposed) return;
        _message.Text = text;
        _message.Tag = clickAction;
        _message.Cursor = clickAction != null ? Cursors.Hand : Cursors.Default;
        _message.Visible = true;
        _message.BringToFront();
        foreach (var v in new[] { _notion, _calendar }) if (v != null) v.Visible = false;
    }

    static string? FindCalendarApp()
    {
        var programs = Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "Programs");
        foreach (var dir in new[] { "notion-calendar-web", "Notion Calendar", "cron-web" })
        {
            var folder = Path.Combine(programs, dir);
            if (!Directory.Exists(folder)) continue;
            var exe = Directory.EnumerateFiles(folder, "*.exe")
                .FirstOrDefault(f => !Path.GetFileName(f).StartsWith("Uninstall", StringComparison.OrdinalIgnoreCase));
            if (exe != null) return exe;
        }
        return null;
    }

    static bool Start(string target)
    {
        try
        {
            Process.Start(new ProcessStartInfo(target) { UseShellExecute = true })?.Dispose();
            return true;
        }
        catch (Exception ex)
        {
            Log.Write("open " + target + ": " + ex.Message);
            return false;
        }
    }

    static void OpenExternal(string url) => Start(url);

    // ---- pure helpers (unit-tested) ----

    static bool HostIs(string host, string domain) =>
        host.Equals(domain, StringComparison.OrdinalIgnoreCase) || host.EndsWith("." + domain, StringComparison.OrdinalIgnoreCase);

    /// <summary>Pages that stay inside the dock.</summary>
    public static bool IsInAppHost(Uri uri) =>
        uri.Scheme is "https" or "http" && (AppDomains.Any(d => HostIs(uri.Host, d)) || IsSignInHost(uri));

    public static bool IsSignInHost(Uri uri) => uri.Scheme is "https" && SignInDomains.Any(d => HostIs(uri.Host, d));

    public static bool IsCalendarHost(Uri uri) =>
        uri.Host.Equals("calendar.notion.so", StringComparison.OrdinalIgnoreCase)
        || uri.Host.Equals("calendar.notion.com", StringComparison.OrdinalIgnoreCase);

    static readonly System.Text.RegularExpressions.Regex PageId = new("[0-9a-f]{32}$", System.Text.RegularExpressions.RegexOptions.Compiled);

    static readonly string[] AuthWords =
        { "popup", "oauth", "login", "logout", "signup", "auth", "sso", "redirect", "callback", "verify" };

    /// <summary>Sign-in / redirect pages (e.g. Notion's Google popup flow): shown in a popup, never remembered.</summary>
    public static bool IsAuthFlow(Uri uri)
    {
        var query = uri.Query.ToLowerInvariant();
        if (query.Contains("callbacktype=popup") || query.Contains("popupflowid")) return true;
        foreach (var segment in uri.AbsolutePath.ToLowerInvariant().Split('/', StringSplitOptions.RemoveEmptyEntries))
        {
            // Notion pages end in a 32-hex id ("Authentication-notes-1f2e…"): those are pages, whatever their title says.
            if (PageId.IsMatch(segment)) continue;
            if (AuthWords.Any(w => segment.Contains(w))) return true;
        }
        return false;
    }

    /// <summary>https://www.notion.so/page → notion://www.notion.so/page (opens in the Notion desktop app).</summary>
    public static string? ToNotionAppUrl(string? url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme != "https") return null;
        if (!(HostIs(uri.Host, "notion.so") || HostIs(uri.Host, "notion.com")) || IsCalendarHost(uri)) return null;
        return "notion://" + uri.Host + uri.PathAndQuery + uri.Fragment;
    }

    /// <summary>Only Notion pages are remembered as start pages (not sign-in screens).</summary>
    public static bool IsRememberable(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme == "https"
        && AppDomains.Any(d => HostIs(uri.Host, d)) && !IsAuthFlow(uri);

    static string ValidStart(string? url, string fallback) => IsRememberable(url) ? url! : fallback;

    /// <summary>Header title: the Notion or Calendar mark and the page title, in Notion's ink.</summary>
    sealed class HeaderTitle : Control
    {
        string _tab = NotionTab;
        string _title = "";

        public HeaderTitle()
        {
            SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
                | ControlStyles.ResizeRedraw, true);
            SetStyle(ControlStyles.Selectable, false);
            TabStop = false;
            BackColor = Theme.NotionSurface;
            Font = Theme.Serif;
        }

        public void Set(string tab, string title)
        {
            _tab = tab;
            _title = title;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(BackColor);
            float m = LogicalToDeviceUnits(20);
            var mark = new RectangleF(LogicalToDeviceUnits(4), (Height - m) / 2f, m, m);
            if (_tab == CalendarTab) Marks.Calendar(g, mark); else Marks.Notion(g, mark);
            int x = (int)mark.Right + LogicalToDeviceUnits(10);
            var app = _tab == CalendarTab ? "Notion Calendar" : "Notion";
            var flags = TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix;
            TextRenderer.DrawText(g, app, Theme.DialogTitle, new Rectangle(x, 0, Width - x, Height), Theme.Ink, flags);
            x += TextRenderer.MeasureText(app, Theme.DialogTitle, Size.Empty, TextFormatFlags.NoPadding).Width + LogicalToDeviceUnits(12);
            var page = CleanTitle(_title, app);
            if (page.Length > 0 && x < Width)
                TextRenderer.DrawText(g, page, Theme.Ui, new Rectangle(x, 0, Width - x, Height), Theme.InkMuted,
                    flags | TextFormatFlags.EndEllipsis);
        }
    }

    /// <summary>"My page | Notion" → "My page"; the app name alone → "".</summary>
    internal static string CleanTitle(string? title, string app)
    {
        var t = (title ?? "").Trim();
        foreach (var suffix in new[] { " | Notion Calendar", " | Notion", " - Notion" })
            if (t.EndsWith(suffix, StringComparison.OrdinalIgnoreCase)) t = t[..^suffix.Length].Trim();
        return t.Equals(app, StringComparison.OrdinalIgnoreCase) || t.Equals("Notion", StringComparison.OrdinalIgnoreCase) ? "" : t;
    }

    /// <summary>"Files" button: gold folder + label, the way back to the Explorer panes.</summary>
    sealed class FilesButton : FlatButton
    {
        public FilesButton() { Text = ""; }

        public override Size GetPreferredSize(Size proposedSize)
        {
            int w = TextRenderer.MeasureText("Files", Theme.UiBold).Width + TextRenderer.MeasureText("Ctrl+J", Theme.UiSmall).Width;
            return new Size(w + LogicalToDeviceUnits(44), LogicalToDeviceUnits(30));
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            PaintBackground(e);
            var g = e.Graphics;
            float m = LogicalToDeviceUnits(16);
            var mark = new RectangleF(LogicalToDeviceUnits(8), (Height - m) / 2f, m, m);
            Marks.Files(g, mark, Theme.Accent);
            var flags = TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine;
            int x = (int)mark.Right + LogicalToDeviceUnits(6);
            TextRenderer.DrawText(g, "Files", Theme.UiBold, new Rectangle(x, 0, Width - x, Height), Theme.Text, flags);
            x += TextRenderer.MeasureText("Files", Theme.UiBold, Size.Empty, TextFormatFlags.NoPadding).Width + LogicalToDeviceUnits(6);
            TextRenderer.DrawText(g, "Ctrl+J", Theme.UiSmall, new Rectangle(x, 0, Width - x, Height), Theme.Muted, flags);
        }
    }
}
