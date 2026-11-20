using System.Globalization;

namespace ExplorerDuo;

public enum CaseMode { Unchanged, Lower, Upper, Title }

/// <summary>
/// Multi-Rename options. Templates use tokens:
/// [N] name without extension, [E] extension, [C] counter, [P] parent folder name, [D] modified date (yyyy-MM-dd).
/// </summary>
public sealed record RenameOptions(
    string NameTemplate = "[N]",
    string ExtTemplate = "[E]",
    string Find = "",
    string Replace = "",
    bool MatchCase = false,
    CaseMode Case = CaseMode.Unchanged,
    int CounterStart = 1,
    int CounterStep = 1,
    int CounterDigits = 1);

public sealed record RenameItem(string From, string To, string? Error)
{
    public bool Changed => !string.Equals(From, To, StringComparison.Ordinal);
}

public static class RenamePlanner
{
    static readonly char[] Invalid = Path.GetInvalidFileNameChars();

    public static string NewName(RenameOptions o, string fileName, bool isFolder, int index, string parentName, DateTime modified)
    {
        string baseName = isFolder ? fileName : Path.GetFileNameWithoutExtension(fileName);
        string ext = isFolder ? "" : Path.GetExtension(fileName).TrimStart('.');
        string counter = (o.CounterStart + index * o.CounterStep).ToString(CultureInfo.InvariantCulture)
            .PadLeft(Math.Max(1, o.CounterDigits), '0');

        string Expand(string template) => template
            .Replace("[N]", baseName).Replace("[E]", ext).Replace("[C]", counter)
            .Replace("[P]", parentName).Replace("[D]", modified.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));

        var name = Expand(o.NameTemplate ?? "[N]");
        var extension = isFolder ? "" : Expand(o.ExtTemplate ?? "[E]");
        var full = extension.Length > 0 ? $"{name}.{extension}" : name;

        if (!string.IsNullOrEmpty(o.Find))
            full = full.Replace(o.Find, o.Replace ?? "", o.MatchCase ? StringComparison.Ordinal : StringComparison.OrdinalIgnoreCase);

        full = o.Case switch
        {
            CaseMode.Lower => full.ToLowerInvariant(),
            CaseMode.Upper => full.ToUpperInvariant(),
            CaseMode.Title => CultureInfo.CurrentCulture.TextInfo.ToTitleCase(full.ToLower(CultureInfo.CurrentCulture)),
            _ => full,
        };
        return full.Trim().TrimEnd('.');
    }

    /// <summary>Works out every new name and flags problems (invalid characters, duplicates, existing files).</summary>
    public static List<RenameItem> Plan(IReadOnlyList<string> paths, RenameOptions o)
    {
        var items = new List<RenameItem>();
        for (int i = 0; i < paths.Count; i++)
        {
            var from = Highlighter.Normalize(paths[i]);
            bool isFolder = Directory.Exists(from);
            var parent = Path.GetDirectoryName(from) ?? "";
            var modified = isFolder ? Directory.GetLastWriteTime(from) : File.GetLastWriteTime(from);
            var newName = NewName(o, Path.GetFileName(from), isFolder, i, Path.GetFileName(parent), modified);
            string? error = newName.Length == 0 ? "Empty name"
                : newName.IndexOfAny(Invalid) >= 0 ? "Invalid character"
                : null;
            items.Add(new RenameItem(from, Path.Combine(parent, newName), error));
        }

        var sources = new HashSet<string>(items.Select(i => i.From), StringComparer.OrdinalIgnoreCase);
        var targets = items.GroupBy(i => i.To, StringComparer.OrdinalIgnoreCase).Where(g => g.Count() > 1)
            .Select(g => g.Key).ToHashSet(StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < items.Count; i++)
        {
            var it = items[i];
            if (it.Error != null) continue;
            if (targets.Contains(it.To)) items[i] = it with { Error = "Duplicate name" };
            else if (it.Changed && !sources.Contains(it.To) && (File.Exists(it.To) || Directory.Exists(it.To))
                     && !string.Equals(it.From, it.To, StringComparison.OrdinalIgnoreCase))
                items[i] = it with { Error = "Already exists" };
        }
        return items;
    }

    /// <summary>Renames in two phases (via temporary names) so swaps like a→b, b→a work. Rolls back on failure.</summary>
    public static void Execute(IReadOnlyList<RenameItem> plan)
    {
        if (plan.Any(p => p.Error != null)) throw new InvalidOperationException("Fix the highlighted problems first.");
        var work = plan.Where(p => p.Changed).ToList();
        var temps = new List<(string Temp, RenameItem Item)>();
        try
        {
            foreach (var item in work)
            {
                var temp = Path.Combine(Path.GetDirectoryName(item.From)!, $"~mr{Guid.NewGuid():N}");
                Move(item.From, temp);
                temps.Add((temp, item));
            }
            for (int i = 0; i < temps.Count; i++)
            {
                Move(temps[i].Temp, temps[i].Item.To);
                temps[i] = ("", temps[i].Item);
            }
        }
        catch
        {
            foreach (var (temp, item) in temps)
            {
                try
                {
                    if (temp.Length > 0) Move(temp, item.From);
                    else if (Exists(item.To) && !Exists(item.From)) Move(item.To, item.From);
                }
                catch { /* best effort */ }
            }
            throw;
        }
    }

    static bool Exists(string p) => File.Exists(p) || Directory.Exists(p);

    static void Move(string from, string to)
    {
        if (Directory.Exists(from)) Directory.Move(from, to);
        else File.Move(from, to);
    }
}

/// <summary>Multi-Rename dialog with live preview.</summary>
internal sealed class MultiRenameDialog : Form
{
    readonly IReadOnlyList<string> _paths;
    readonly TextBox _name = new() { Text = "[N]" };
    readonly TextBox _ext = new() { Text = "[E]" };
    readonly TextBox _find = new();
    readonly TextBox _replace = new();
    readonly CheckBox _matchCase = new() { Text = "Match case", AutoSize = true };
    readonly ComboBox _case = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    readonly NumericUpDown _start = new() { Minimum = 0, Maximum = 1_000_000, Value = 1 };
    readonly NumericUpDown _step = new() { Minimum = 1, Maximum = 1000, Value = 1 };
    readonly NumericUpDown _digits = new() { Minimum = 1, Maximum = 10, Value = 1 };
    readonly ListView _preview = new() { View = View.Details, FullRowSelect = true, GridLines = true, Dock = DockStyle.Fill };
    readonly Button _ok = new() { Text = "Rename", DialogResult = DialogResult.None, Width = 90 };
    readonly Label _summary = new() { AutoSize = true, ForeColor = Theme.Muted };
    List<RenameItem> _plan = new();

    public MultiRenameDialog(IReadOnlyList<string> paths)
    {
        _paths = paths;
        Text = $"Multi-Rename – {paths.Count} item{(paths.Count == 1 ? "" : "s")}";
        Font = Theme.Ui;
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        StartPosition = FormStartPosition.CenterParent;
        Size = new Size(860, 600);
        MinimumSize = new Size(640, 420);
        ShowInTaskbar = false;
        MinimizeBox = false;

        _case.Items.AddRange(new object[] { "Unchanged", "lowercase", "UPPERCASE", "Title Case" });
        _case.SelectedIndex = 0;

        var grid = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 4, Padding = new Padding(10, 10, 10, 4) };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        void Row(string l1, Control c1, string l2, Control c2)
        {
            grid.Controls.Add(new Label { Text = l1, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 6, 6, 0) });
            c1.Dock = DockStyle.Fill;
            grid.Controls.Add(c1);
            grid.Controls.Add(new Label { Text = l2, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(12, 6, 6, 0) });
            c2.Dock = DockStyle.Fill;
            grid.Controls.Add(c2);
        }
        Row("Name:", _name, "Extension:", _ext);
        Row("Find:", _find, "Replace with:", _replace);
        Row("Case:", _case, "", _matchCase);
        var counter = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = Padding.Empty };
        foreach (var (label, box) in new[] { ("start", _start), ("step", _step), ("digits", _digits) })
        {
            box.Width = 70;
            counter.Controls.Add(new Label { Text = label, AutoSize = true, Margin = new Padding(0, 6, 4, 0) });
            counter.Controls.Add(box);
        }
        Row("Counter [C]:", counter, "", new Label
        {
            Text = "Tokens: [N] name  [E] extension  [C] counter  [P] parent folder  [D] date",
            AutoSize = true, ForeColor = Theme.Muted, Margin = new Padding(0, 6, 0, 0),
        });

        _preview.Columns.Add("Current name", 300);
        _preview.Columns.Add("New name", 300);
        _preview.Columns.Add("Problem", 160);
        var previewHost = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10, 4, 10, 4) };
        previewHost.Controls.Add(_preview);

        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Width = 90 };
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Padding = new Padding(10, 6, 10, 10) };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(_ok);
        buttons.Controls.Add(_summary);

        Controls.Add(previewHost);
        Controls.Add(buttons);
        Controls.Add(grid);
        AcceptButton = _ok;
        CancelButton = cancel;

        foreach (var c in new Control[] { _name, _ext, _find, _replace }) c.TextChanged += (_, _) => Refresh();
        foreach (var n in new[] { _start, _step, _digits }) n.ValueChanged += (_, _) => Refresh();
        _case.SelectedIndexChanged += (_, _) => Refresh();
        _matchCase.CheckedChanged += (_, _) => Refresh();
        _ok.Click += (_, _) => Apply();
        Refresh();
    }

    RenameOptions Options() => new(_name.Text, _ext.Text, _find.Text, _replace.Text, _matchCase.Checked,
        (CaseMode)_case.SelectedIndex, (int)_start.Value, (int)_step.Value, (int)_digits.Value);

    new void Refresh()
    {
        _plan = RenamePlanner.Plan(_paths, Options());
        _preview.BeginUpdate();
        _preview.Items.Clear();
        foreach (var p in _plan)
        {
            var row = new ListViewItem(new[] { Path.GetFileName(p.From), Path.GetFileName(p.To), p.Error ?? "" });
            if (p.Error != null) row.ForeColor = Theme.Warning;
            else if (!p.Changed) row.ForeColor = Theme.Muted;
            _preview.Items.Add(row);
        }
        _preview.EndUpdate();
        int errors = _plan.Count(p => p.Error != null), changes = _plan.Count(p => p.Changed && p.Error == null);
        _summary.Text = errors > 0 ? $"{errors} problem(s)" : $"{changes} will be renamed";
        _summary.ForeColor = errors > 0 ? Theme.Warning : Theme.Muted;
        _ok.Enabled = errors == 0 && changes > 0;
    }

    void Apply()
    {
        try
        {
            RenamePlanner.Execute(_plan);
            DialogResult = DialogResult.OK;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Rename failed, changes were rolled back:\n" + ex.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            Refresh();
        }
    }
}
