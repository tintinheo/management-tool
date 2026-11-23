using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace ExplorerDuo;

/// <summary>
/// Minimalist-luxury look: warm ivory chrome, charcoal text, a single champagne-gold accent, hairline borders,
/// generous spacing and serif headings.
/// </summary>
internal static class Theme
{
    public static readonly Color Window = Color.FromArgb(246, 243, 236);     // ivory backdrop
    public static readonly Color Surface = Color.FromArgb(255, 254, 251);    // card / view surface
    public static readonly Color Tint = Color.FromArgb(250, 247, 241);       // tab row, address field
    public static readonly Color Border = Color.FromArgb(230, 223, 211);     // hairlines
    public static readonly Color Text = Color.FromArgb(36, 33, 29);          // charcoal
    public static readonly Color Muted = Color.FromArgb(139, 132, 120);
    public static readonly Color Hover = Color.FromArgb(240, 234, 223);
    public static readonly Color Pressed = Color.FromArgb(229, 220, 203);
    public static readonly Color Accent = Color.FromArgb(168, 131, 74);      // champagne gold
    public static readonly Color AccentDeep = Color.FromArgb(132, 100, 52);  // gold for small text (contrast)
    public static readonly Color AccentSoft = Color.FromArgb(216, 196, 159); // active card outline, frame border
    public static readonly Color Warning = Color.FromArgb(160, 72, 42);      // terracotta
    // The Notion side (Notion, Calendar) uses Notion's own ink on white, so it never looks like the file side.
    public static readonly Color Ink = Color.FromArgb(55, 53, 47);           // Notion text colour
    public static readonly Color InkMuted = Color.FromArgb(120, 119, 116);
    public static readonly Color NotionSurface = Color.White;

    static readonly string SansName = Has("Segoe UI Variable Text") ? "Segoe UI Variable Text" : "Segoe UI";
    public static readonly string SerifName = Has("Georgia") ? "Georgia" : Has("Cambria") ? "Cambria" : "Times New Roman";

    public static readonly Font Ui = new(SansName, 9f);
    public static readonly Font UiSmall = new(SansName, 8.25f);
    public static readonly Font UiBold = new(SansName, 9f, FontStyle.Bold);
    /// <summary>Serif for headings: tab titles, wordmark, dialog captions.</summary>
    public static readonly Font Serif = new(SerifName, 9.75f);
    public static readonly Font Wordmark = new(SerifName, 13.5f);
    public static readonly Font DialogTitle = new(SerifName, 12f);

    public static readonly string IconFontName = Has("Segoe Fluent Icons") ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";

    static bool Has(string family) => FontFamily.Families.Any(f => f.Name == family);

    public static Font Icons(float size) => new(IconFontName, size);

    public const string GlyphBack = "";
    public const string GlyphForward = "";
    public const string GlyphUp = "";
    public const string GlyphRefresh = "";
    public const string GlyphAdd = "";
    public const string GlyphClose = "";
    public const string GlyphMore = "";
    public const string GlyphOpenApp = ""; // open in new window
    public const string GlyphTree = ""; // "open pane"

    public static GraphicsPath Rounded(RectangleF r, float radius)
    {
        float d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        var p = new GraphicsPath();
        if (d <= 0) { p.AddRectangle(r); return p; }
        p.AddArc(r.Left, r.Top, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    // ── window frame ──

    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    public static int ColorRef(Color c) => c.R | (c.G << 8) | (c.B << 16);

    /// <summary>Windows 11: ivory caption, charcoal caption text, gold hairline border, rounded corners. Ignored on older Windows.</summary>
    public static void StyleFrame(Form form, Color? border = null)
    {
        if (!form.IsHandleCreated) return;
        void Set(int attribute, int value)
        {
            try { DwmSetWindowAttribute(form.Handle, attribute, ref value, sizeof(int)); } catch { }
        }
        Set(33, 2);                       // DWMWA_WINDOW_CORNER_PREFERENCE = round
        Set(34, ColorRef(border ?? AccentSoft)); // DWMWA_BORDER_COLOR
        Set(35, ColorRef(Window));        // DWMWA_CAPTION_COLOR
        Set(36, ColorRef(Text));          // DWMWA_TEXT_COLOR
    }

    /// <summary>Menus drawn by ExplorerDuo (⋯, Highlight, tree, bookmarks) in the same palette.</summary>
    public static void InstallMenuRenderer() => ToolStripManager.Renderer = new LuxuryRenderer();

    sealed class LuxuryColors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Surface;
        public override Color ImageMarginGradientBegin => Surface;
        public override Color ImageMarginGradientMiddle => Surface;
        public override Color ImageMarginGradientEnd => Surface;
        public override Color MenuBorder => Border;
        public override Color MenuItemBorder => Hover;
        public override Color MenuItemSelected => Hover;
        public override Color MenuItemSelectedGradientBegin => Hover;
        public override Color MenuItemSelectedGradientEnd => Hover;
        public override Color MenuItemPressedGradientBegin => Pressed;
        public override Color MenuItemPressedGradientEnd => Pressed;
        public override Color SeparatorDark => Border;
        public override Color SeparatorLight => Surface;
        public override Color CheckBackground => Hover;
        public override Color CheckSelectedBackground => Pressed;
        public override Color CheckPressedBackground => Pressed;
        public override Color ButtonSelectedBorder => Hover;
    }

    sealed class LuxuryRenderer : ToolStripProfessionalRenderer
    {
        public LuxuryRenderer() : base(new LuxuryColors()) => RoundedEdges = false;

        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            if (e.Item.Enabled && e.Item.ForeColor == SystemColors.ControlText) e.TextColor = Text;
            base.OnRenderItemText(e);
        }

        protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
        {
            // A small gold dot instead of the stock check box.
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = e.ImageRectangle;
            float d = Math.Max(6, r.Height / 3f);
            using var brush = new SolidBrush(Accent);
            g.FillEllipse(brush, r.Left + (r.Width - d) / 2f, r.Top + (r.Height - d) / 2f, d, d);
        }
    }
}

/// <summary>Flat button that never takes keyboard focus away from the file view; soft rounded hover.</summary>
internal class FlatButton : Button
{
    bool _hover, _down;

    public FlatButton()
    {
        SetStyle(ControlStyles.Selectable, false);
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint, true);
        TabStop = false;
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        BackColor = Color.Transparent;
        ForeColor = Theme.Text;
        Cursor = Cursors.Default;
        UseVisualStyleBackColor = false;
    }

    protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { _hover = _down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { _down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { _down = false; Invalidate(); base.OnMouseUp(e); }

    protected Color BackgroundColor
    {
        get
        {
            for (Control? c = Parent; c != null; c = c.Parent)
                if (c.BackColor.A == 255) return c.BackColor;
            return Theme.Window;
        }
    }

    /// <summary>Parent colour plus the rounded hover / pressed highlight.</summary>
    protected void PaintBackground(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackgroundColor);
        if (_hover || _down)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(2, 3, Width - 4, Height - 6);
            using var path = Theme.Rounded(r, LogicalToDeviceUnits(7));
            using var brush = new SolidBrush(_down ? Theme.Pressed : Theme.Hover);
            g.FillPath(brush, path);
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        PaintBackground(e);
        if (Text.Length > 0)
            TextRenderer.DrawText(g, Text, Font, ClientRectangle, Enabled ? ForeColor : Theme.Muted,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
    }
}
