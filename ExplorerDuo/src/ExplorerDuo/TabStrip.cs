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
    /// <summary>Files were dragged over a tab and held there briefly: switch to it so they can be dropped into its folder.</summary>
    public event Action<int>? DragHovered;

    readonly System.Windows.Forms.Timer _dragTimer = new() { Interval = 450 };
    int _dragIndex = -1;

    protected override void OnDragEnter(DragEventArgs e)
    {
        e.Effect = DragDropEffects.None; // tabs aren't drop targets themselves
        base.OnDragEnter(e);
    }

    protected override void OnDragOver(DragEventArgs e)
    {
        e.Effect = DragDropEffects.None;
        var p = PointToClient(new Point(e.X, e.Y));
        int index = Array.FindIndex(TabRects(), r => r.Contains(p));
        if (index != _dragIndex)
        {
            _dragIndex = index;
            _hover = index;
            Invalidate();
            _dragTimer.Stop();
            if (index >= 0 && index != _selected) _dragTimer.Start();
        }
        base.OnDragOver(e);
    }

    protected override void OnDragLeave(EventArgs e)
    {
        _dragTimer.Stop();
        _dragIndex = _hover = -1;
        Invalidate();
        base.OnDragLeave(e);
    }

    public TabStrip()
    {
        AllowDrop = true;
        _dragTimer.Tick += (_, _) =>
        {
            _dragTimer.Stop();
            if (_dragIndex >= 0 && _dragIndex < _titles.Count) DragHovered?.Invoke(_dragIndex);
        };
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        SetStyle(ControlStyles.Selectable, false);
        Dock = DockStyle.Top;
        Font = Theme.Serif;
        Height = 38;
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
        for (int i = 0; i < n; i++) rects[i] = new Rectangle(L(8) + i * w, L(6), w - L(4), Height - L(6));
        return rects;
    }

    Rectangle PlusRect(Rectangle[] tabs)
    {
        int x = tabs.Length == 0 ? L(6) : tabs[^1].Right + L(4);
        x = Math.Min(x, Width - L(34));
        return new Rectangle(x, L(7), L(30), Height - L(11));
    }

    Rectangle CloseRect(Rectangle tab) => new(tab.Right - L(28), tab.Top + (tab.Height - L(20)) / 2 - L(1), L(20), L(20));

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Theme.Tint);
        var tabs = TabRects();

        for (int i = 0; i < tabs.Length; i++)
        {
            var r = tabs[i];
            bool selected = i == _selected;
            if (selected || i == _hover)
            {
                using var path = TopRounded(r, L(8));
                using var fill = new SolidBrush(selected ? Theme.Surface : Theme.Hover);
                g.FillPath(fill, path);
                if (selected)
                {
                    using var edge = new Pen(Theme.Border);
                    g.DrawPath(edge, path);
                }
                if (selected && _activePane)
                {
                    // Slim gold rule under the title of the active tab.
                    using var accent = new SolidBrush(Theme.Accent);
                    g.FillRectangle(accent, r.Left + L(14), r.Bottom - L(4), r.Width - L(28), L(2));
                }
            }
            else if (i + 1 != _selected && i + 1 < tabs.Length && i + 1 != _hover)
            {
                using var sep = new Pen(Theme.Border);
                g.DrawLine(sep, r.Right + L(1), r.Top + L(10), r.Right + L(1), r.Bottom - L(10));
            }

            bool showClose = selected || i == _hover;
            var textRect = new Rectangle(r.Left + L(14), r.Top, r.Width - L(14) - (showClose ? L(30) : L(10)), r.Height - L(2));
            TextRenderer.DrawText(g, _titles[i], Font, textRect,
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
            using var path = Rounded(plus, L(7));
            g.FillPath(hb, path);
        }
        TextRenderer.DrawText(g, Theme.GlyphAdd, _iconFont, plus, Theme.AccentDeep,
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
            _dragTimer.Dispose();
            _iconFont.Dispose();
        }
        base.Dispose(disposing);
    }
}
