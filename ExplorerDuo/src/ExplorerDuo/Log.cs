namespace ExplorerDuo;

/// <summary>Small rolling diagnostic log at %AppData%\ExplorerDuo\log.txt.</summary>
internal static class Log
{
    static readonly string FilePath = Path.Combine(Settings.Folder, "log.txt");
    static readonly object Gate = new();

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Settings.Folder);
                var info = new FileInfo(FilePath);
                if (info.Exists && info.Length > 512 * 1024) info.Delete();
                File.AppendAllText(FilePath, $"{DateTime.Now:HH:mm:ss.fff} {message}{Environment.NewLine}");
            }
        }
        catch
        {
            // Logging must never break the app.
        }
    }
}
