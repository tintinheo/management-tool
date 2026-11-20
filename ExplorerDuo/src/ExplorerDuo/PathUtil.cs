namespace ExplorerDuo;

public static class PathUtil
{
    public const string ThisPc = "::{20D04FE0-3AEA-1069-A2D8-08002B30309D}";

    public static bool IsFileSystemPath(string? path) =>
        !string.IsNullOrEmpty(path) && !path.StartsWith("::", StringComparison.Ordinal) && Path.IsPathFullyQualified(path);

    /// <summary>Normalises user / shell input into something SHParseDisplayName accepts.</summary>
    public static string ToParsable(string? path)
    {
        var p = Environment.ExpandEnvironmentVariables((path ?? "").Trim().Trim('"'));
        if (p.StartsWith("shell:::", StringComparison.OrdinalIgnoreCase)) p = p[6..];
        if (p.Length == 2 && p[1] == ':') p += "\\";
        return p;
    }

    public static bool SamePath(string? a, string? b) =>
        string.Equals(Normalize(a), Normalize(b), StringComparison.OrdinalIgnoreCase);

    static string Normalize(string? p)
    {
        p = ToParsable(p);
        return p.Length > 3 ? p.TrimEnd('\\') : p;
    }

    /// <summary>Tab title before the shell has given us a display name.</summary>
    public static string ShortTitle(string path)
    {
        var p = ToParsable(path);
        if (SamePath(p, ThisPc)) return "This PC";
        if (!IsFileSystemPath(p)) return p;
        var trimmed = p.Length > 3 ? p.TrimEnd('\\') : p;
        var name = Path.GetFileName(trimmed);
        return string.IsNullOrEmpty(name) ? trimmed : name;
    }

    /// <summary>What the address bar shows: real path for folders on disk, display name for virtual folders.</summary>
    public static string DisplayPath(string path, string title) => IsFileSystemPath(path) ? path : title;
}
