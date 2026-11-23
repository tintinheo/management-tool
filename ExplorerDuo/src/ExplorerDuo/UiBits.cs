using System.Drawing.Drawing2D;

namespace ExplorerDuo;

internal static class AppIcon
{
    /// <summary>Sizes written into app.ico (the exe's icon: taskbar, Alt+Tab, Start, pinned).</summary>
    public static readonly int[] IcoSizes = { 16, 20, 24, 32, 40, 48, 64, 96, 128, 256 };

    /// <summary>The window icon: every size from the embedded app.ico, so the taskbar gets a sharp one at any DPI.</summary>
    public static Icon Create()
    {
        try
        {
            using var stream = typeof(AppIcon).Assembly.GetManifestResourceStream("ExplorerDuo.app.ico");
            if (stream != null) return new Icon(stream);
        }
        catch
        {
            // fall back to drawing it
        }
        using var bmp = Render(32);
        return Icon.FromHandle(bmp.GetHicon());
    }

    /// <summary>Charcoal tile, two ivory panes, gold tab marks: drawn on a 32-unit grid scaled to <paramref name="size"/>.</summary>
    public static Bitmap Render(int size)
    {
        var bmp = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.Clear(Color.Transparent);
        float k = size / 32f;
        RectangleF R(float x, float y, float w, float h) => new(x * k, y * k, w * k, h * k);
        using (var path = Theme.Rounded(R(1, 1, 30, 30), 7 * k))
        using (var bg = new SolidBrush(Theme.Text))
            g.FillPath(bg, path);
        using var pane = new SolidBrush(Theme.Window);
        g.FillRectangle(pane, R(5, 11, 10, 15));
        g.FillRectangle(pane, R(17, 11, 10, 15));
        using var tab = new SolidBrush(Theme.Accent);
        g.FillRectangle(tab, R(5, 7, 6, 2));
        g.FillRectangle(tab, R(17, 7, 6, 2));
        return bmp;
    }

    /// <summary>Writes a multi-size .ico (PNG-compressed entries, supported since Windows Vista).</summary>
    public static void WriteIco(string path)
    {
        var images = IcoSizes.Select(size =>
        {
            using var bmp = Render(size);
            using var ms = new MemoryStream();
            bmp.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
            return (size, data: ms.ToArray());
        }).ToList();
        using var w = new BinaryWriter(File.Create(path));
        w.Write((short)0); w.Write((short)1); w.Write((short)images.Count); // ICONDIR
        int offset = 6 + 16 * images.Count;
        foreach (var (size, data) in images)
        {
            w.Write((byte)(size >= 256 ? 0 : size)); w.Write((byte)(size >= 256 ? 0 : size));
            w.Write((byte)0); w.Write((byte)0);       // palette, reserved
            w.Write((short)1); w.Write((short)32);    // planes, bit count
            w.Write(data.Length); w.Write(offset);
            offset += data.Length;
        }
        foreach (var (_, data) in images) w.Write(data);
    }

    static GraphicsPath RoundRect(Rectangle r, int radius)
    {
        int d = radius * 2;
        var path = new GraphicsPath();
        path.AddArc(r.Left, r.Top, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }
}

internal static class InputDialog
{
    public static string? Prompt(IWin32Window? owner, string title, string label, string initial, string okText = "OK")
    {
        using var form = new Form
        {
            Text = title,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = owner == null ? FormStartPosition.CenterScreen : FormStartPosition.CenterParent,
            MinimizeBox = false,
            MaximizeBox = false,
            ShowInTaskbar = false,
            AutoScaleDimensions = new SizeF(96f, 96f),
            AutoScaleMode = AutoScaleMode.Dpi,
            ClientSize = new Size(420, 178),
            Font = Theme.Ui,
            BackColor = Theme.Window,
            ForeColor = Theme.Text,
        };
        form.HandleCreated += (_, _) => Theme.StyleFrame(form);

        var heading = new Label { Text = title, Left = 22, Top = 16, AutoSize = true, Font = Theme.DialogTitle, ForeColor = Theme.Text };
        var caption = new Label { Text = label, Left = 22, Top = 50, Width = 376, Height = 18, AutoEllipsis = true, ForeColor = Theme.Muted };
        var field = new Panel { Left = 22, Top = 74, Width = 376, Height = 34, BackColor = Theme.Window, Padding = new Padding(12, 9, 12, 6) };
        var box = new TextBox { Text = initial, Dock = DockStyle.Fill, BorderStyle = BorderStyle.None, BackColor = Theme.Surface, ForeColor = Theme.Text };
        field.Controls.Add(box);
        field.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var path = Theme.Rounded(new RectangleF(0.5f, 0.5f, field.Width - 1.5f, field.Height - 1.5f), 7);
            using var fill = new SolidBrush(Theme.Surface);
            using var pen = new Pen(Theme.Accent);
            e.Graphics.FillPath(fill, path);
            e.Graphics.DrawPath(pen, path);
        };

        var ok = new PillButton(okText, primary: true) { DialogResult = DialogResult.OK, Left = 222, Top = 128, Width = 84, Height = 32 };
        var cancel = new PillButton("Cancel", primary: false) { DialogResult = DialogResult.Cancel, Left = 314, Top = 128, Width = 84, Height = 32 };
        form.Controls.AddRange(new Control[] { heading, caption, field, ok, cancel });
        form.AcceptButton = ok;
        form.CancelButton = cancel;
        form.Shown += (_, _) => { box.SelectAll(); box.Focus(); };
        var result = owner == null ? form.ShowDialog() : form.ShowDialog(owner);
        return result == DialogResult.OK && box.Text.Trim().Length > 0 ? box.Text.Trim() : null;
    }

    /// <summary>Rounded dialog button: charcoal with ivory text (primary) or a hairline outline.</summary>
    sealed class PillButton : Button
    {
        readonly bool _primary;
        bool _hover;

        public PillButton(string text, bool primary)
        {
            _primary = primary;
            Text = text;
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        }

        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; Invalidate(); base.OnMouseLeave(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(Parent?.BackColor ?? Theme.Window);
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var path = Theme.Rounded(new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f), Height / 2f);
            if (_primary)
            {
                using var fill = new SolidBrush(_hover ? Color.FromArgb(58, 53, 46) : Theme.Text);
                g.FillPath(fill, path);
            }
            else
            {
                using var fill = new SolidBrush(_hover ? Theme.Hover : Theme.Window);
                using var pen = new Pen(Theme.AccentSoft);
                g.FillPath(fill, path);
                g.DrawPath(pen, path);
            }
            TextRenderer.DrawText(g, Text, Font, ClientRectangle, _primary ? Theme.Window : Theme.Text,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
        }
    }
}
