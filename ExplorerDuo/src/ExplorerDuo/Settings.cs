using System.Text.Json;
using System.Text.Json.Serialization;

namespace ExplorerDuo;

public sealed class Session
{
    public List<string> Left { get; set; } = new();
    public List<string> Right { get; set; } = new();
    public int LeftIndex { get; set; }
    public int RightIndex { get; set; }
    public bool RightActive { get; set; }
    public double SplitRatio { get; set; } = 0.5;
    /// <summary>x, y, width, height of the restored (non-maximized) window.</summary>
    public int[]? Bounds { get; set; }
    public bool Maximized { get; set; }
}

public sealed class Bookmark
{
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
}

public sealed class Settings
{
    public static readonly string Folder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ExplorerDuo");
    public static readonly string DefaultPath = Path.Combine(Folder, "settings.json");

    static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    public Session Session { get; set; } = new();
    public bool ShowTree { get; set; } = true;
    public int TreeWidth { get; set; } = 260;
    /// <summary>Where folder trees are shown: "left" (one shared tree), "panes" (one tree in each pane) or "none".</summary>
    public string TreeMode { get; set; } = "";
    public int PaneTreeWidth { get; set; } = 200;
    /// <summary>Tabs that stay in the background this long release their Explorer view (memory). 0 = never.</summary>
    public int UnloadIdleTabsMinutes { get; set; } = 10;

    /// <summary>Classic list layout: needed for colour highlighting and middle-click hit-testing.</summary>
    public bool ClassicListView { get; set; } = true;
    /// <summary>Show the cloud "Status" column automatically in OneDrive / cloud-synced folders.</summary>
    public bool ShowCloudStatus { get; set; } = true;
    public List<Bookmark> Bookmarks { get; set; } = new();
    /// <summary>Full path → colour, set with the Highlight command.</summary>
    public Dictionary<string, string> Highlights { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public List<ColorRule> ColorRules { get; set; } = ColorRule.Defaults();
    public string HiddenColor { get; set; } = "#A39C90";
    /// <summary>Colours were moved to the 2.4 palette.</summary>
    public int PaletteVersion { get; set; }
    /// <summary>F4 opens selected files with this program.</summary>
    public string Editor { get; set; } = "notepad.exe";

    [JsonIgnore] public DateTime SavedUtc { get; private set; }
    public bool RestoreSession { get; set; } = true;

    [JsonIgnore] public string FilePath { get; private set; } = DefaultPath;

    public static Settings Load(string? path = null)
    {
        path ??= DefaultPath;
        Settings settings;
        try
        {
            settings = File.Exists(path)
                ? JsonSerializer.Deserialize<Settings>(File.ReadAllText(path), Json) ?? new Settings()
                : new Settings();
        }
        catch
        {
            settings = new Settings();
        }
        settings.Session ??= new Session();
        settings.Session.Left ??= new();
        settings.Session.Right ??= new();
        settings.Bookmarks ??= new();
        settings.ColorRules ??= new();
        settings.Highlights = new Dictionary<string, string>(settings.Highlights ?? new(), StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(settings.Editor)) settings.Editor = "notepad.exe";
        var mode = (settings.TreeMode ?? "").Trim().ToLowerInvariant();
        settings.TreeMode = mode is "left" or "panes" or "none" ? mode : settings.ShowTree ? "left" : "none"; // 2.2 and earlier: ShowTree
        if (settings.UnloadIdleTabsMinutes < 0) settings.UnloadIdleTabsMinutes = 0;
        if (settings.PaletteVersion < 2)
        {
            // The bright Windows colours of 2.2/2.3 → the softer palette (only exact old values change).
            static string Up(string? hex) => hex != null && Palette.Upgrade.TryGetValue(hex.Trim(), out var n) ? n : hex ?? "";
            foreach (var rule in settings.ColorRules) rule.Color = Up(rule.Color);
            foreach (var key in settings.Highlights.Keys.ToList()) settings.Highlights[key] = Up(settings.Highlights[key]);
            settings.HiddenColor = Up(settings.HiddenColor);
            settings.PaletteVersion = 2;
        }
        settings.FilePath = path;
        settings.SavedUtc = File.Exists(path) ? File.GetLastWriteTimeUtc(path) : DateTime.MinValue;
        return settings;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Json));
            SavedUtc = File.GetLastWriteTimeUtc(FilePath);
        }
        catch
        {
            // Settings are a convenience; never crash over them.
        }
    }
}
