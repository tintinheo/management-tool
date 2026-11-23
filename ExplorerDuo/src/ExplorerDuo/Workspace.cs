using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace ExplorerDuo;

/// <summary>The three things ExplorerDuo can show full-window: the file panes, Notion, or Notion Calendar.</summary>
internal static class Workspace
{
    public const string Files = "files";
    public const string Notion = NotionDock.NotionTab;
    public const string Calendar = NotionDock.CalendarTab;

    public static string Normalize(string? value) => value is Notion or Calendar ? value : Files;
    public static bool IsNotion(string value) => value is Notion or Calendar;
}

/// <summary>Small brand marks drawn in code (no image files): Notion's ink "N" tile, a calendar tile with today's date.</summary>
internal static class Marks
{
    /// <summary>Ink rounded square with a white serif N. <paramref name="inverted"/>: white tile, ink N (on an ink background).</summary>
    public static void Notion(Graphics g, RectangleF r, bool inverted = false)
    {
        var old = g.SmoothingMode;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var path = Theme.Rounded(r, r.Width * 0.22f))
        using (var fill = new SolidBrush(inverted ? Color.White : Theme.Ink))
        {
            g.FillPath(fill, path);
            if (!inverted)
            {
                using var edge = new Pen(Theme.Ink);
                g.DrawPath(edge, path);
            }
        }
        DrawCentered(g, "N", r, inverted ? Theme.Ink : Color.White, r.Height * 0.62f);
        g.SmoothingMode = old;
    }

    /// <summary>White tile with an ink top band and today's day number.</summary>
    public static void Calendar(Graphics g, RectangleF r, bool inverted = false)
    {
        var old = g.SmoothingMode;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var ink = Theme.Ink;
        using (var path = Theme.Rounded(r, r.Width * 0.22f))
        {
            using (var fill = new SolidBrush(Color.White)) g.FillPath(fill, path);
            var band = new RectangleF(r.X, r.Y, r.Width, r.Height * 0.3f);
            var clip = g.Clip;
            g.SetClip(path);
            using (var top = new SolidBrush(inverted ? Theme.Accent : Theme.Warning)) g.FillRectangle(top, band);
            g.Clip = clip;
            using var edge = new Pen(inverted ? Color.White : ink);
            g.DrawPath(edge, path);
        }
        var body = new RectangleF(r.X, r.Y + r.Height * 0.26f, r.Width, r.Height * 0.74f);
        DrawCentered(g, DateTime.Now.Day.ToString(), body, ink, r.Height * 0.48f, bold: true);
        g.SmoothingMode = old;
    }

    /// <summary>Gold folder (the file panes).</summary>
    public static void Files(Graphics g, RectangleF r, Color color)
    {
        using var font = new Font(Theme.IconFontName, r.Height * 0.72f, GraphicsUnit.Pixel);
        TextRenderer.DrawText(g, "", font, Rectangle.Round(r), color,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
    }

    static void DrawCentered(Graphics g, string text, RectangleF r, Color color, float pixelSize, bool bold = true)
    {
        using var font = new Font(Theme.SerifName, Math.Max(4f, pixelSize), bold ? FontStyle.Bold : FontStyle.Regular, GraphicsUnit.Pixel);
        using var brush = new SolidBrush(color);
        using var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        var hint = g.TextRenderingHint;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.DrawString(text, font, brush, new RectangleF(r.X, r.Y + r.Height * 0.03f, r.Width, r.Height), fmt);
        g.TextRenderingHint = hint;
    }
}

/// <summary>
/// Segmented switch in the top bar: Files | Notion | Calendar. The Notion side is drawn in Notion's ink so it
/// stands apart from the ivory/gold file side; a gold dot marks Notion until it has been opened once.
/// </summary>
internal sealed class WorkspaceSwitch : Control
{
    static readonly (string Id, string Title)[] Items =
        { (Workspace.Files, "Files"), (Workspace.Notion, "Notion"), (Workspace.Calendar, "Calendar") };

    readonly ToolTip _tip = new() { InitialDelay = 500 };
    string _active = Workspace.Files;
    int _hover = -1;
    bool _spotlight;

    public event Action<string>? Selected;

    public WorkspaceSwitch()
    {
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
            | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
        TabStop = false;
        BackColor = Theme.Window;
        Font = Theme.Ui;
        Dock = DockStyle.Right;
        Cursor = Cursors.Hand;
    }

    public string Active
    {
        get => _active;
        set { _active = Workspace.Normalize(value); Invalidate(); }
    }

    /// <summary>Gold "new" dot on the Notion segment.</summary>
    public bool Spotlight
    {
        get => _spotlight;
        set { _spotlight = value; Invalidate(); }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        var rects = SegmentRects();
        Width = rects[^1].Right + LogicalToDeviceUnits(16);
    }

    protected override void OnDpiChangedAfterParent(EventArgs e)
    {
        base.OnDpiChangedAfterParent(e);
        Width = SegmentRects()[^1].Right + LogicalToDeviceUnits(16);
    }

    RectangleF Track()
    {
        float h = LogicalToDeviceUnits(32);
        return new RectangleF(LogicalToDeviceUnits(4), (Height - h) / 2f, 0, h);
    }

    internal Rectangle[] SegmentRects()
    {
        var track = Track();
        var rects = new Rectangle[Items.Length];
        int x = (int)track.X + LogicalToDeviceUnits(3);
        int y = (int)track.Y + LogicalToDeviceUnits(3), h = (int)track.Height - LogicalToDeviceUnits(6);
        for (int i = 0; i < Items.Length; i++)
        {
            int text = TextRenderer.MeasureText(Items[i].Title, Font, Size.Empty, TextFormatFlags.NoPadding).Width;
            int w = LogicalToDeviceUnits(16 + 6 + 22) + text; // pad + mark + gap + pad
            rects[i] = new Rectangle(x, y, w, h);
            x += w + LogicalToDeviceUnits(2);
        }
        return rects;
    }

    int HitTest(Point p) => Array.FindIndex(SegmentRects(), r => r.Contains(p));

    protected override void OnMouseMove(MouseEventArgs e)
    {
        int h = HitTest(e.Location);
        if (h != _hover)
        {
            _hover = h;
            Invalidate();
            _tip.SetToolTip(this, h switch
            {
                0 => "Files: your two Explorer panes (Ctrl+J switches back and forth)",
                1 => "Notion, full window (Ctrl+J)",
                2 => "Notion Calendar, full window (Ctrl+Shift+J)",
                _ => "",
            });
        }
        base.OnMouseMove(e);
    }

    protected override void OnMouseLeave(EventArgs e) { _hover = -1; Invalidate(); base.OnMouseLeave(e); }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        int i = HitTest(e.Location);
        if (e.Button == MouseButtons.Left && i >= 0) Selected?.Invoke(Items[i].Id);
        base.OnMouseUp(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackColor);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var rects = SegmentRects();

        var track = Track();
        track.Width = rects[^1].Right + LogicalToDeviceUnits(3) - track.X;
        using (var path = Theme.Rounded(track, track.Height / 2f))
        using (var fill = new SolidBrush(Theme.Tint))
        using (var edge = new Pen(Theme.Border))
        {
            g.FillPath(fill, path);
            g.DrawPath(edge, path);
        }

        for (int i = 0; i < Items.Length; i++)
        {
            var r = rects[i];
            var id = Items[i].Id;
            bool active = id == _active;
            bool notionSide = id != Workspace.Files;
            using (var path = Theme.Rounded(r, r.Height / 2f))
            {
                if (active)
                {
                    using var fill = new SolidBrush(notionSide ? Theme.Ink : Theme.Surface);
                    g.FillPath(fill, path);
                    if (!notionSide)
                    {
                        using var edge = new Pen(Theme.AccentSoft);
                        g.DrawPath(edge, path);
                    }
                }
                else if (i == _hover)
                {
                    using var fill = new SolidBrush(Theme.Hover);
                    g.FillPath(fill, path);
                }
            }

            float m = LogicalToDeviceUnits(18);
            var mark = new RectangleF(r.X + LogicalToDeviceUnits(10), r.Y + (r.Height - m) / 2f, m, m);
            if (id == Workspace.Files) Marks.Files(g, mark, active ? Theme.Accent : Theme.AccentDeep);
            else if (id == Workspace.Notion) Marks.Notion(g, mark, inverted: active);
            else Marks.Calendar(g, mark, inverted: active);

            var textRect = new Rectangle((int)(mark.Right + LogicalToDeviceUnits(6)), r.Y, r.Right - (int)mark.Right, r.Height);
            var color = active ? (notionSide ? Color.White : Theme.Text) : notionSide ? Theme.Ink : Theme.Muted;
            TextRenderer.DrawText(g, Items[i].Title, active ? Theme.UiBold : Font, textRect, color,
                TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine);

            if (id == Workspace.Notion && _spotlight && !active)
            {
                float d = LogicalToDeviceUnits(7);
                using var dot = new SolidBrush(Theme.Accent);
                using var ring = new Pen(Theme.Window, LogicalToDeviceUnits(2));
                var dr = new RectangleF(r.Right - d - LogicalToDeviceUnits(4), r.Y + LogicalToDeviceUnits(1), d, d);
                g.FillEllipse(dot, dr);
                g.DrawEllipse(ring, dr);
            }
        }
    }
}

/// <summary>Bottom-bar button for Notion: the ink N mark makes it stand out among the file commands.</summary>
internal sealed class NotionFnButton : FnButton
{
    public NotionFnButton() : base("Ctrl+J", "Notion") { }

    public override Size GetPreferredSize(Size proposedSize)
    {
        var s = base.GetPreferredSize(proposedSize);
        return new Size(s.Width + LogicalToDeviceUnits(26), s.Height);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        PaintBackground(e);
        var g = e.Graphics;
        float m = LogicalToDeviceUnits(18);
        var mark = new RectangleF(LogicalToDeviceUnits(10), (Height - m) / 2f, m, m);
        Marks.Notion(g, mark);
        int x = (int)mark.Right + LogicalToDeviceUnits(7);
        var flags = TextFormatFlags.NoPadding | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine;
        var k = TextRenderer.MeasureText(Key, Theme.UiBold, Size.Empty, TextFormatFlags.NoPadding);
        TextRenderer.DrawText(g, Label, Theme.UiBold, new Rectangle(x, 0, Width - x, Height), Theme.Ink, flags);
        x += TextRenderer.MeasureText(Label, Theme.UiBold, Size.Empty, TextFormatFlags.NoPadding).Width + LogicalToDeviceUnits(6);
        TextRenderer.DrawText(g, Key, Theme.UiSmall, new Rectangle(x, 0, k.Width + 4, Height), Theme.Muted, flags);
    }
}
