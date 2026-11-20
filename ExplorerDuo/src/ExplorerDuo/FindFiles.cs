using System.Collections.Concurrent;

namespace ExplorerDuo;

public sealed record FoundItem(string Path, bool IsFolder, long Size, DateTime Modified);

/// <summary>Multi Commander-style "Find files": name wildcards, optional text inside files, optional sub-folders.</summary>
public static class FileSearch
{
    public const long MaxContentBytes = 64L * 1024 * 1024;

    public static void Run(string root, string namePatterns, string? containsText, bool recursive,
        Action<FoundItem> onFound, CancellationToken ct)
    {
        var names = Highlighter.Wildcard(string.IsNullOrWhiteSpace(namePatterns) ? "*" : namePatterns);
        bool byContent = !string.IsNullOrEmpty(containsText);
        var options = new EnumerationOptions
        {
            RecurseSubdirectories = recursive,
            IgnoreInaccessible = true,
            AttributesToSkip = 0,
            ReturnSpecialDirectories = false,
        };

        foreach (var path in Directory.EnumerateFileSystemEntries(root, "*", options))
        {
            ct.ThrowIfCancellationRequested();
            if (!names.IsMatch(Path.GetFileName(path))) continue;
            try
            {
                var info = new FileInfo(path);
                bool isFolder = (info.Attributes & FileAttributes.Directory) != 0;
                if (byContent && (isFolder || info.Length > MaxContentBytes || !Contains(path, containsText!, ct))) continue;
                onFound(new FoundItem(path, isFolder, isFolder ? 0 : info.Length, info.LastWriteTime));
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch
            {
                // Locked / vanished file: skip.
            }
        }
    }

    /// <summary>Case-insensitive text search, streaming (detects UTF-8/UTF-16 BOMs, falls back to UTF-8).</summary>
    public static bool Contains(string file, string text, CancellationToken ct)
    {
        using var reader = new StreamReader(file, detectEncodingFromByteOrderMarks: true);
        var buffer = new char[64 * 1024];
        string carry = "";
        int read;
        while ((read = reader.Read(buffer, 0, buffer.Length)) > 0)
        {
            ct.ThrowIfCancellationRequested();
            var chunk = carry + new string(buffer, 0, read);
            if (chunk.Contains(text, StringComparison.OrdinalIgnoreCase)) return true;
            carry = chunk.Length >= text.Length ? chunk[^Math.Max(0, text.Length - 1)..] : chunk;
        }
        return false;
    }
}

/// <summary>Non-modal Find Files window. Double-click / Enter = show in active pane, middle-click = other pane.</summary>
internal sealed class FindFilesDialog : Form
{
    const int MaxResults = 20000;

    readonly TextBox _names = new() { Text = "*" };
    readonly TextBox _contains = new();
    readonly TextBox _folder = new();
    readonly CheckBox _recursive = new() { Text = "Include sub-folders", Checked = true, AutoSize = true };
    readonly Button _go = new() { Text = "Search", Width = 90 };
    readonly ListView _results = new() { View = View.Details, FullRowSelect = true, Dock = DockStyle.Fill, HideSelection = false };
    readonly Label _status = new() { AutoSize = true, ForeColor = Theme.Muted, Padding = new Padding(10, 6, 0, 6) };
    readonly ConcurrentQueue<FoundItem> _incoming = new();
    readonly System.Windows.Forms.Timer _drain = new() { Interval = 200 };
    CancellationTokenSource? _cts;
    int _count;

    /// <summary>A result was activated: (path, isFolder, target).</summary>
    public event Action<FoundItem, OpenTarget>? ResultActivated;

    public FindFilesDialog()
    {
        Text = "Find files";
        Font = Theme.Ui;
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        Size = new Size(900, 600);
        MinimumSize = new Size(600, 360);
        StartPosition = FormStartPosition.CenterParent;
        ShowInTaskbar = false;
        KeyPreview = true;

        var grid = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 3, Padding = new Padding(10, 10, 10, 4) };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        void Row(string label, Control c, Control? extra = null)
        {
            grid.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 6, 8, 0) });
            c.Dock = DockStyle.Fill;
            grid.Controls.Add(c);
            grid.Controls.Add(extra ?? new Label());
        }
        var browse = new Button { Text = "…", Width = 34 };
        browse.Click += (_, _) =>
        {
            using var dlg = new FolderBrowserDialog { InitialDirectory = _folder.Text, UseDescriptionForTitle = true, Description = "Search in" };
            if (dlg.ShowDialog(this) == DialogResult.OK) _folder.Text = dlg.SelectedPath;
        };
        Row("Names:", _names, _go);
        Row("Containing text:", _contains, _recursive);
        Row("Search in:", _folder, browse);
        grid.Controls.Add(new Label());
        grid.Controls.Add(new Label
        {
            Text = "Wildcards, ';' between patterns (e.g. *.pdf;*.docx). Double-click/Enter: show in active pane · middle-click: other pane",
            AutoSize = true, ForeColor = Theme.Muted,
        });

        _results.Columns.Add("Name", 260);
        _results.Columns.Add("Folder", 380);
        _results.Columns.Add("Size", 90, HorizontalAlignment.Right);
        _results.Columns.Add("Modified", 130);
        var host = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10, 4, 10, 0) };
        host.Controls.Add(_results);

        Controls.Add(host);
        Controls.Add(_status);
        _status.Dock = DockStyle.Bottom;
        Controls.Add(grid);
        AcceptButton = _go;

        _go.Click += (_, _) => { if (_cts == null) Start(); else Stop(); };
        _drain.Tick += (_, _) => Drain();
        _results.DoubleClick += (_, _) => Activate(OpenTarget.ActivePane);
        _results.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; Activate(OpenTarget.ActivePane); }
        };
        _results.MouseUp += (_, e) =>
        {
            if (e.Button != MouseButtons.Middle) return;
            var hit = _results.HitTest(e.Location).Item;
            if (hit == null) return;
            _results.SelectedItems.Clear();
            hit.Selected = true;
            Activate(OpenTarget.OtherPane);
        };
        KeyDown += (_, e) => { if (e.KeyCode == Keys.Escape) { if (_cts != null) Stop(); else Hide(); } };
        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Stop(); Hide(); }
        };
    }

    public void Prepare(string folder)
    {
        if (_cts == null) _folder.Text = folder;
        _names.Focus();
        _names.SelectAll();
    }

    void Start()
    {
        var root = _folder.Text.Trim();
        if (!Directory.Exists(root)) { _status.Text = "That folder doesn't exist."; _status.ForeColor = Theme.Warning; return; }
        _results.Items.Clear();
        _count = 0;
        while (_incoming.TryDequeue(out _)) { }
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        string names = _names.Text, contains = _contains.Text;
        bool recursive = _recursive.Checked;
        _go.Text = "Stop";
        _status.ForeColor = Theme.Muted;
        _status.Text = "Searching…";
        _drain.Start();

        _ = Task.Run(() =>
        {
            string done;
            try
            {
                int n = 0;
                FileSearch.Run(root, names, contains, recursive, item =>
                {
                    _incoming.Enqueue(item);
                    if (++n >= MaxResults) throw new OperationCanceledException();
                }, ct);
                done = "Done";
            }
            catch (OperationCanceledException)
            {
                done = ct.IsCancellationRequested ? "Stopped" : $"Stopped at {MaxResults:N0} results";
            }
            catch (Exception ex)
            {
                done = "Error: " + ex.Message;
            }
            BeginInvoke(() => Finish(done));
        }, ct);
    }

    void Stop() => _cts?.Cancel();

    void Finish(string done)
    {
        Drain();
        _drain.Stop();
        _cts?.Dispose();
        _cts = null;
        _go.Text = "Search";
        _status.Text = $"{done} — {_count:N0} found.";
    }

    void Drain()
    {
        if (_incoming.IsEmpty) return;
        _results.BeginUpdate();
        while (_incoming.TryDequeue(out var f))
        {
            _count++;
            var row = new ListViewItem(new[]
            {
                Path.GetFileName(f.Path), Path.GetDirectoryName(f.Path) ?? "",
                f.IsFolder ? "<folder>" : Pane.FormatBytes(f.Size), f.Modified.ToString("yyyy-MM-dd HH:mm"),
            }) { Tag = f };
            if (Highlights.Current?.ColorFor(f.Path, f.IsFolder, false) is Color c) row.ForeColor = c;
            _results.Items.Add(row);
        }
        _results.EndUpdate();
        if (_cts != null) _status.Text = $"Searching… {_count:N0} found";
    }

    void Activate(OpenTarget target)
    {
        if (_results.SelectedItems.Count == 0 || _results.SelectedItems[0].Tag is not FoundItem f) return;
        ResultActivated?.Invoke(f, target);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _cts?.Cancel();
            _drain.Dispose();
        }
        base.Dispose(disposing);
    }
}
