namespace ExplorerDuo;

internal static class Theme
{
    public static readonly Color Window = Color.FromArgb(243, 243, 243);
    public static readonly Color Surface = Color.White;
    public static readonly Color Border = Color.FromArgb(220, 220, 220);
    public static readonly Color Text = Color.FromArgb(26, 26, 26);
    public static readonly Color Muted = Color.FromArgb(112, 112, 112);
    public static readonly Color Hover = Color.FromArgb(229, 229, 229);
    public static readonly Color Pressed = Color.FromArgb(214, 214, 214);
    public static readonly Color Accent = Color.FromArgb(0, 95, 184);
    public static readonly Color Warning = Color.FromArgb(176, 74, 0);

    public static readonly Font Ui = new("Segoe UI", 9f);
    public static readonly Font UiBold = new("Segoe UI Semibold", 9f);

    public static readonly string IconFontName =
        FontFamily.Families.Any(f => f.Name == "Segoe Fluent Icons") ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";

    public static Font Icons(float size) => new(IconFontName, size);

    public const string GlyphBack = "";
    public const string GlyphForward = "";
    public const string GlyphUp = "";
    public const string GlyphRefresh = "";
    public const string GlyphAdd = "";
    public const string GlyphClose = "";
    public const string GlyphMore = "";
}

/// <summary>Flat button that never takes keyboard focus away from the file view.</summary>
internal class FlatButton : Button
{
    public FlatButton()
    {
        SetStyle(ControlStyles.Selectable, false);
        TabStop = false;
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        FlatAppearance.MouseOverBackColor = Theme.Hover;
        FlatAppearance.MouseDownBackColor = Theme.Pressed;
        BackColor = Color.Transparent;
        ForeColor = Theme.Text;
        Cursor = Cursors.Default;
        UseVisualStyleBackColor = false;
    }
}
