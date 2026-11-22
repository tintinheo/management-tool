namespace ExplorerDuo;

internal enum OpenTarget { ActivePane, NewTab, OtherPane }

/// <summary>
/// Row of bookmark buttons under the title bar.
/// Click = open in the active pane, Ctrl+click = new tab, middle-click = other pane, right-click = options.
/// </summary>
internal sealed class BookmarkBar : Panel
{
    const string GlyphStarAdd = "";
    const string GlyphFolder = "";

    readonly FlowLayoutPanel _flow = new()
    {
        Dock = DockStyle.Fill, WrapContents = false, AutoScroll = false, BackColor = Theme.Window, Padding = new Padding(6, 8, 0, 6),
    };
    readonly ToolTip _tip = new();

    public event Action<Bookmark, OpenTarget>? OpenRequested;
    public event Action? AddRequested;
    public event Action<Bookmark>? RenameRequested;
    public event Action<Bookmark>? RemoveRequested;
    public event Action<Bookmark, int>? MoveRequested;

    public BookmarkBar()
    {
        Dock = DockStyle.Top;
        Height = 48;
        BackColor = Theme.Window;
        Padding = new Padding(14, 0, 0, 0);
        Controls.Add(_flow);
        Controls.Add(new WordmarkLabel());
    }

    public void SetBookmarks(IReadOnlyList<Bookmark> bookmarks)
    {
        _flow.SuspendLayout();
        foreach (Control c in _flow.Controls) c.Dispose();
        _flow.Controls.Clear();

        var add = new GlyphButton(GlyphStarAdd, "Bookmark", Theme.Accent, Theme.Muted);
        _tip.SetToolTip(add, "Bookmark the active pane's folder (Ctrl+D)");
        add.Click += (_, _) => AddRequested?.Invoke();
        _flow.Controls.Add(add);

        if (bookmarks.Count == 0)
        {
            _flow.Controls.Add(new Label
            {
                Text = "Ctrl+D bookmarks the current folder · middle-click a bookmark or folder to open it in the other pane",
                AutoSize = true, ForeColor = Theme.Muted, Font = Theme.UiSmall, Margin = new Padding(8, 8, 0, 0),
            });
        }

        for (int i = 0; i < bookmarks.Count; i++)
        {
            var bm = bookmarks[i];
            int index = i;
            var b = new GlyphButton(GlyphFolder, bm.Name, Theme.Accent, Theme.Text);
            _tip.SetToolTip(b, $"{bm.Path}\n{(index < 9 ? $"Alt+{index + 1} · " : "")}Click: open · Ctrl+click: new tab · Middle-click: other pane");
            b.MouseUp += (_, e) =>
            {
                if (e.Button == MouseButtons.Left)
                    OpenRequested?.Invoke(bm, (ModifierKeys & Keys.Control) != 0 ? OpenTarget.NewTab : OpenTarget.ActivePane);
                else if (e.Button == MouseButtons.Middle)
                    OpenRequested?.Invoke(bm, OpenTarget.OtherPane);
                else if (e.Button == MouseButtons.Right)
                    ShowMenu(b, e.Location, bm, index, bookmarks.Count);
            };
            _flow.Controls.Add(b);
        }
        _flow.ResumeLayout();
    }

    void ShowMenu(Control owner, Point at, Bookmark bm, int index, int count)
    {
        var menu = new ContextMenuStrip { Font = Theme.Ui };
        menu.Items.Add("Open", null, (_, _) => OpenRequested?.Invoke(bm, OpenTarget.ActivePane));
        menu.Items.Add("Open in new tab", null, (_, _) => OpenRequested?.Invoke(bm, OpenTarget.NewTab));
        menu.Items.Add("Open in other pane", null, (_, _) => OpenRequested?.Invoke(bm, OpenTarget.OtherPane));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Rename…", null, (_, _) => RenameRequested?.Invoke(bm));
        menu.Items.Add(new ToolStripMenuItem("Move left", null, (_, _) => MoveRequested?.Invoke(bm, -1)) { Enabled = index > 0 });
        menu.Items.Add(new ToolStripMenuItem("Move right", null, (_, _) => MoveRequested?.Invoke(bm, +1)) { Enabled = index < count - 1 });
        menu.Items.Add("Remove", null, (_, _) => RemoveRequested?.Invoke(bm));
        menu.Closed += (_, _) => BeginInvoke(menu.Dispose);
        menu.Show(owner, at);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) _tip.Dispose();
        base.Dispose(disposing);
    }

    /// <summary>"ExplorerDuo" in serif with a small gold mark and a hairline divider — the header's anchor.</summary>
    sealed class WordmarkLabel : Control
    {
        public WordmarkLabel()
        {
            SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.ResizeRedraw, true);
            SetStyle(ControlStyles.Selectable, false);
            Dock = DockStyle.Left;
            BackColor = Theme.Window;
            Width = 150;
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            var t = TextRenderer.MeasureText("ExplorerDuo", Theme.Wordmark, Size.Empty, TextFormatFlags.NoPadding);
            Width = t.Width + LogicalToDeviceUnits(40);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(BackColor);
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            float s = LogicalToDeviceUnits(4);
            float cy = Height / 2f;
            using (var gold = new SolidBrush(Theme.Accent))
                g.FillPolygon(gold, new[] { new PointF(s, cy - s), new PointF(2 * s, cy), new PointF(s, cy + s), new PointF(0, cy) });
            int x = LogicalToDeviceUnits(14);
            TextRenderer.DrawText(g, "ExplorerDuo", Theme.Wordmark, new Rectangle(x, 0, Width - x, Height), Theme.Text,
                TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine);
            using var pen = new Pen(Theme.Border);
            int inset = LogicalToDeviceUnits(14);
            g.DrawLine(pen, Width - 1, inset, Width - 1, Height - inset);
        }
    }

    /// <summary>Flat button: small icon-font glyph + text.</summary>
    sealed class GlyphButton : FlatButton
    {
        readonly string _glyph;
        readonly string _label;
        readonly Color _glyphColor;
        readonly Color _textColor;
        readonly Font _glyphFont = Theme.Icons(9.5f);

        public GlyphButton(string glyph, string label, Color glyphColor, Color textColor)
        {
            _glyph = glyph;
            _label = label;
            _glyphColor = glyphColor;
            _textColor = textColor;
            AutoSize = true;
            Margin = new Padding(1, 0, 1, 0);
            Text = "";
        }

        public override Size GetPreferredSize(Size proposedSize)
        {
            var t = TextRenderer.MeasureText(_label, Font, Size.Empty, TextFormatFlags.NoPadding);
            return new Size(Math.Min(t.Width, LogicalToDeviceUnits(180)) + LogicalToDeviceUnits(40), LogicalToDeviceUnits(32));
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            int x = LogicalToDeviceUnits(10);
            int g = LogicalToDeviceUnits(16);
            var flags = TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine;
            TextRenderer.DrawText(e.Graphics, _glyph, _glyphFont, new Rectangle(x, 0, g, Height), _glyphColor, flags | TextFormatFlags.HorizontalCenter);
            x += g + LogicalToDeviceUnits(4);
            TextRenderer.DrawText(e.Graphics, _label, Font, new Rectangle(x, 0, Width - x - LogicalToDeviceUnits(4), Height), _textColor,
                flags | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) _glyphFont.Dispose();
            base.Dispose(disposing);
        }
    }
}
