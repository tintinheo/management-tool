using System.Drawing.Drawing2D;

namespace ExplorerDuo;

internal static class AppIcon
{
    /// <summary>Two panes with tab marks.</summary>
    public static Icon Create()
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using (var path = RoundRect(new Rectangle(1, 2, 30, 28), 6))
            using (var bg = new SolidBrush(Theme.Accent))
                g.FillPath(bg, path);
            using var pane = new SolidBrush(Color.White);
            g.FillRectangle(pane, 4, 10, 11, 17);
            g.FillRectangle(pane, 17, 10, 11, 17);
            using var tab = new SolidBrush(Color.FromArgb(255, 200, 70));
            g.FillRectangle(tab, 4, 6, 6, 3);
            g.FillRectangle(tab, 17, 6, 6, 3);
        }
        return Icon.FromHandle(bmp.GetHicon());
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
            ClientSize = new Size(380, 120),
            Font = Theme.Ui,
        };
        var caption = new Label { Text = label, Left = 14, Top = 14, AutoSize = true };
        var box = new TextBox { Text = initial, Left = 14, Top = 40, Width = 352 };
        var ok = new Button { Text = okText, DialogResult = DialogResult.OK, Left = 206, Top = 78, Width = 76 };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Left = 290, Top = 78, Width = 76 };
        form.Controls.AddRange(new Control[] { caption, box, ok, cancel });
        form.AcceptButton = ok;
        form.CancelButton = cancel;
        form.Shown += (_, _) => { box.SelectAll(); box.Focus(); };
        var result = owner == null ? form.ShowDialog() : form.ShowDialog(owner);
        return result == DialogResult.OK && box.Text.Trim().Length > 0 ? box.Text.Trim() : null;
    }
}
