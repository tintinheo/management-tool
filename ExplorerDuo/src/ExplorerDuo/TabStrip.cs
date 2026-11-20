using System.Drawing.Drawing2D;

namespace ExplorerDuo;

/// <summary>Owner-drawn browser-style tab row: click to select, × or middle-click to close, + or double-click to add.</summary>
internal sealed class TabStrip : Control
{
    readonly List<string> _titles = new();
    readonly List<string> _tips = new();
    readonly ToolTip _toolTip = new() { InitialDelay = 600 };
    readonly Font _iconFont = Theme.Icons(8f);
    int _selected = -1;
    int _hover = -1;
    bool _hoverClose;
    bool _hoverPlus;
    bool _activePane;

    public event Action<int>? TabClicked;
    public event Action<int>? CloseClicked;
    public event Action? PlusClicked;

    public TabStrip()
    {
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
        Dock = DockStyle.Top;
        Font = Theme.Ui;
        Height = 34;
    }

    public bool ActivePane
    {
        get => _activePane;
        set { _activePane = value; Invalidate(); }
    }

    public void SetTabs(IEnumerable<string> titles, IEnumerable<string> tips, int selected)
    {
        _titles.Clear();
        _titles.AddRange(titles);
        _tips.Clear();
        _tips.AddRange(tips);
        _selected = selected;
        Invalidate();
    }

    int L(int px) => LogicalToDeviceUnits(px);

    Rectangle[] TabRects()
    {
        int n = _titles.Count;
        if (n == 0) return Array.Empty<Rectangle>();
        int avail = Width - L(48);
        int w = Math.Clamp(avail / n, L(56), L(220));
        var rects = new Rectangle[n];
        for (int i = 0; i < n; i++) rects[i] = new Rectangle(L(6) + i * w, L(5), w - L(2), Height - L(5));
        return rects;
    }

    Rectangle PlusRect(Rectangle[] tabs)
    {
        int x = tabs.Length == 0 ? L(6) : tabs[^1].Right + L(4);
        x = Math.Min(x, Width - L(34));
        return new Rectangle(x, L(7), L(30), Height - L(11));
    }

    Rectangle CloseRect(Rectangle tab) => new(tab.Right - L(26), tab.Top + (tab.Height - L(20)) / 2, L(20), L(20));

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Theme.Window);
        var tabs = TabRects();

        for (int i = 0; i < tabs.Length; i++)
        {
            var r = tabs[i];
            bool selected = i == _selected;
            if (selected || i == _hover)
            {
                using var path = TopRounded(r, L(6));
                using var fill = new SolidBrush(selected ? Theme.Surface : Theme.Hover);
                g.FillPath(fill, path);
                if (selected && _activePane)
                {
                    using var accent = new SolidBrush(Theme.Accent);
                    g.FillRectangle(accent, r.Left + L(8), r.Top, r.Width - L(16), L(2));
                }
            }
            else if (i + 1 != _selected && i + 1 < tabs.Length && i + 1 != _hover)
            {
                using var sep = new Pen(Theme.Border);
                g.DrawLine(sep, r.Right, r.Top + L(8), r.Right, r.Bottom - L(8));
            }

            bool showClose = selected || i == _hover;
            var textRect = new Rectangle(r.Left + L(10), r.Top, r.Width - L(10) - (showClose ? L(28) : L(8)), r.Height);
            TextRenderer.DrawText(g, _titles[i], selected && _activePane ? Theme.UiBold : Font, textRect,
                selected ? Theme.Text : Theme.Muted,
                TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix | TextFormatFlags.SingleLine);

            if (showClose)
            {
                var c = CloseRect(r);
                if (i == _hover && _hoverClose)
                {
                    using var hb = new SolidBrush(Theme.Pressed);
                    g.FillEllipse(hb, c);
                }
                TextRenderer.DrawText(g, Theme.GlyphClose, _iconFont, c, Theme.Muted,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            }
        }

        var plus = PlusRect(tabs);
        if (_hoverPlus)
        {
            using var hb = new SolidBrush(Theme.Hover);
            using var path = Rounded(plus, L(5));
            g.FillPath(hb, path);
        }
        TextRenderer.DrawText(g, Theme.GlyphAdd, _iconFont, plus, Theme.Text,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);

        using var line = new Pen(Theme.Border);
        g.DrawLine(line, 0, Height - 1, Width, Height - 1);
        if (_selected >= 0 && _selected < tabs.Length)
        {
            using var cover = new Pen(Theme.Surface);
            g.DrawLine(cover, tabs[_selected].Left + 1, Height - 1, tabs[_selected].Right - 1, Height - 1);
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        var tabs = TabRects();
        int hover = Array.FindIndex(tabs, r => r.Contains(e.Location));
        bool hoverClose = hover >= 0 && CloseRect(tabs[hover]).Contains(e.Location);
        bool hoverPlus = PlusRect(tabs).Contains(e.Location);
        if (hover != _hover || hoverClose != _hoverClose || hoverPlus != _hoverPlus)
        {
            if (hover != _hover) _toolTip.SetToolTip(this, hover >= 0 && hover < _tips.Count ? _tips[hover] : hoverPlus ? "New tab (Ctrl+T)" : "");
            _hover = hover;
            _hoverClose = hoverClose;
            _hoverPlus = hoverPlus;
            Invalidate();
        }
        base.OnMouseMove(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        _hover = -1;
        _hoverClose = _hoverPlus = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        var tabs = TabRects();
        int index = Array.FindIndex(tabs, r => r.Contains(e.Location));
        if (e.Button == MouseButtons.Middle && index >= 0) CloseClicked?.Invoke(index);
        else if (e.Button == MouseButtons.Left)
        {
            if (index >= 0 && CloseRect(tabs[index]).Contains(e.Location)) CloseClicked?.Invoke(index);
            else if (index >= 0) TabClicked?.Invoke(index);
            else if (PlusRect(tabs).Contains(e.Location)) PlusClicked?.Invoke();
        }
        base.OnMouseUp(e);
    }

    protected override void OnMouseDoubleClick(MouseEventArgs e)
    {
        var tabs = TabRects();
        if (e.Button == MouseButtons.Left && !tabs.Any(r => r.Contains(e.Location)) && !PlusRect(tabs).Contains(e.Location))
            PlusClicked?.Invoke();
        base.OnMouseDoubleClick(e);
    }

    static GraphicsPath TopRounded(Rectangle r, int radius)
    {
        int d = radius * 2;
        var p = new GraphicsPath();
        p.AddArc(r.Left, r.Top, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        p.AddLine(r.Right, r.Bottom, r.Left, r.Bottom);
        p.CloseFigure();
        return p;
    }

    static GraphicsPath Rounded(Rectangle r, int radius)
    {
        int d = radius * 2;
        var p = new GraphicsPath();
        p.AddArc(r.Left, r.Top, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _toolTip.Dispose();
            _iconFont.Dispose();
        }
        base.Dispose(disposing);
    }
}
