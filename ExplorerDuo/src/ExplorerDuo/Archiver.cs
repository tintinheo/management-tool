using System.IO.Compression;

namespace ExplorerDuo;

/// <summary>Zip pack / unpack (Multi Commander's Alt+F5 / Alt+F9).</summary>
public static class Archiver
{
    /// <summary>Packs files and folders (recursively, keeping the top folder name) into a new zip.</summary>
    public static void Pack(IReadOnlyList<string> sources, string zipPath)
    {
        if (File.Exists(zipPath)) throw new IOException($"{Path.GetFileName(zipPath)} already exists.");
        try
        {
            using var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create);
            foreach (var raw in sources)
            {
                var source = Highlighter.Normalize(raw);
                if (Directory.Exists(source))
                {
                    var baseDir = Path.GetDirectoryName(source) ?? source;
                    var dirs = Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories).Prepend(source);
                    foreach (var dir in dirs)
                    {
                        var files = Directory.EnumerateFiles(dir).ToList();
                        if (files.Count == 0 && !Directory.EnumerateDirectories(dir).Any())
                            zip.CreateEntry(EntryName(baseDir, dir) + "/"); // keep empty folders
                        foreach (var file in files)
                            zip.CreateEntryFromFile(file, EntryName(baseDir, file), CompressionLevel.Optimal);
                    }
                }
                else if (File.Exists(source))
                {
                    zip.CreateEntryFromFile(source, Path.GetFileName(source), CompressionLevel.Optimal);
                }
            }
        }
        catch
        {
            try { File.Delete(zipPath); } catch { }
            throw;
        }
    }

    static string EntryName(string baseDir, string path) =>
        Path.GetRelativePath(baseDir, path).Replace('\\', '/');

    /// <summary>Extracts into a new sub-folder of <paramref name="destinationFolder"/> named after the archive. Returns that folder.</summary>
    public static string Unpack(string zipPath, string destinationFolder)
    {
        var name = Path.GetFileNameWithoutExtension(zipPath);
        var target = Path.Combine(destinationFolder, name);
        for (int n = 2; Directory.Exists(target) || File.Exists(target); n++)
            target = Path.Combine(destinationFolder, $"{name} ({n})");
        ZipFile.ExtractToDirectory(zipPath, target); // guards against "zip slip" paths
        return target;
    }
}
