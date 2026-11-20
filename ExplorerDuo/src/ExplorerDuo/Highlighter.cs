using System.Text.RegularExpressions;

namespace ExplorerDuo;

/// <summary>A Multi-Commander-style colouring rule: name patterns (wildcards, ';'-separated) → text colour.</summary>
public sealed class ColorRule
{
    public string Name { get; set; } = "";
    public string Patterns { get; set; } = "";
    public string Color { get; set; } = "#000000";
    /// <summary>"all", "files" or "folders".</summary>
    public string AppliesTo { get; set; } = "all";

    public static List<ColorRule> Defaults() => new()
    {
        new() { Name = "Programs & scripts", Patterns = "*.exe;*.msi;*.bat;*.cmd;*.ps1;*.com;*.appx;*.msix", Color = "#107C10", AppliesTo = "files" },
        new() { Name = "Archives", Patterns = "*.zip;*.7z;*.rar;*.tar;*.gz;*.tgz;*.bz2;*.xz;*.iso;*.cab", Color = "#8764B8", AppliesTo = "files" },
        new() { Name = "Images", Patterns = "*.jpg;*.jpeg;*.png;*.gif;*.bmp;*.webp;*.heic;*.tif;*.tiff;*.svg;*.ico;*.raw", Color = "#0063B1", AppliesTo = "files" },
        new() { Name = "Audio & video", Patterns = "*.mp3;*.flac;*.wav;*.m4a;*.aac;*.ogg;*.mp4;*.mkv;*.avi;*.mov;*.wmv;*.webm", Color = "#CA5010", AppliesTo = "files" },
        new() { Name = "Documents", Patterns = "*.pdf;*.doc;*.docx;*.xls;*.xlsx;*.ppt;*.pptx;*.epub;*.odt", Color = "#A4262C", AppliesTo = "files" },
    };
}

/// <summary>Named highlight colours offered in the "Highlight" menu.</summary>
public static class Palette
{
    public static readonly (string Name, string Hex)[] Colors =
    {
        ("Red", "#D13438"), ("Orange", "#CA5010"), ("Gold", "#B58B00"), ("Green", "#107C10"),
        ("Teal", "#038387"), ("Blue", "#0063B1"), ("Purple", "#8764B8"), ("Pink", "#C239B3"),
    };
}

/// <summary>
/// Decides the text colour of an item: explicit per-path highlights win, then rules in order,
/// then the "hidden items" colour. Immutable; rebuild when settings change.
/// </summary>
public sealed class Highlighter
{
    readonly Dictionary<string, Color> _paths = new(StringComparer.OrdinalIgnoreCase);
    readonly List<(Regex Pattern, Color Color, string AppliesTo)> _rules = new();
    readonly Color? _hidden;

    public Highlighter(IReadOnlyDictionary<string, string>? highlights, IEnumerable<ColorRule>? rules, string? hiddenColor)
    {
        foreach (var (path, color) in highlights ?? new Dictionary<string, string>())
            if (TryParseColor(color, out var c)) _paths[Normalize(path)] = c;
        foreach (var rule in rules ?? Enumerable.Empty<ColorRule>())
            if (!string.IsNullOrWhiteSpace(rule.Patterns) && TryParseColor(rule.Color, out var c))
                _rules.Add((Wildcard(rule.Patterns), c, (rule.AppliesTo ?? "all").Trim().ToLowerInvariant()));
        if (TryParseColor(hiddenColor, out var h)) _hidden = h;
    }

    public bool IsEmpty => _paths.Count == 0 && _rules.Count == 0 && _hidden == null;

    public Color? ColorFor(string parsingPath, bool isFolder, bool isHidden)
    {
        if (string.IsNullOrEmpty(parsingPath)) return null;
        if (_paths.TryGetValue(Normalize(parsingPath), out var explicitColor)) return explicitColor;

        var name = NameOf(parsingPath);
        foreach (var (pattern, color, appliesTo) in _rules)
        {
            if (appliesTo == "files" && isFolder) continue;
            if (appliesTo == "folders" && !isFolder) continue;
            if (pattern.IsMatch(name)) return color;
        }
        return isHidden ? _hidden : null;
    }

    public Color? ColorFor(ShellEntry e) => ColorFor(e.ParsingName, e.IsFolder, e.IsHidden);

    public static string Normalize(string path)
    {
        var p = path.Trim();
        return p.Length > 3 ? p.TrimEnd('\\') : p;
    }

    static string NameOf(string path)
    {
        var p = Normalize(path);
        var name = Path.GetFileName(p);
        return string.IsNullOrEmpty(name) ? p : name;
    }

    /// <summary>"*.jpg;*.png" → case-insensitive regex. "*.*" means everything, like in Windows.</summary>
    public static Regex Wildcard(string patterns)
    {
        var parts = patterns.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(p => p == "*.*" ? "*" : p)
            .Select(p => "^" + Regex.Escape(p).Replace(@"\*", ".*").Replace(@"\?", ".") + "$");
        return new Regex(string.Join("|", parts), RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    }

    public static bool TryParseColor(string? text, out Color color)
    {
        color = Color.Empty;
        if (string.IsNullOrWhiteSpace(text)) return false;
        try
        {
            color = ColorTranslator.FromHtml(text.Trim());
            return !color.IsEmpty;
        }
        catch
        {
            return false;
        }
    }

    public static uint ToColorRef(Color c) => (uint)(c.R | (c.G << 8) | (c.B << 16));
}

/// <summary>The highlighter currently in effect; views and the tree read it while painting.</summary>
internal static class Highlights
{
    public static Highlighter? Current { get; set; }
}
