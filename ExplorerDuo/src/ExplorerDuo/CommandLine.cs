using System.IO.Pipes;
using System.Text.Json;

namespace ExplorerDuo;

/// <summary>
/// ExplorerDuo.exe [folder ...]      open each folder as a tab (in the running window if there is one)
/// ExplorerDuo.exe --register         add "Open in ExplorerDuo" to Explorer's right-click menu
/// ExplorerDuo.exe --unregister       remove it
/// </summary>
public sealed record CommandLine(string Verb, IReadOnlyList<string> Paths)
{
    public static CommandLine Parse(IEnumerable<string> args)
    {
        string verb = "";
        var paths = new List<string>();
        foreach (var a in args)
        {
            if (a.StartsWith("--", StringComparison.Ordinal)) verb = a.ToLowerInvariant();
            else
            {
                var p = CleanPath(a);
                if (p.Length > 0) paths.Add(p);
            }
        }
        return new CommandLine(verb, paths);
    }

    /// <summary>
    /// Explorer passes drive roots as "C:\" which the CRT argv rules turn into C:" (the backslash escapes
    /// the closing quote). Undo that.
    /// </summary>
    public static string CleanPath(string raw)
    {
        var p = raw.Trim().Trim('"').Trim();
        if (p.Length == 2 && p[1] == ':') p += "\\";
        return p;
    }
}

internal static class Ipc
{
    public static string SafeUser => new(Environment.UserName.Where(char.IsLetterOrDigit).ToArray());
    public static string PipeName => "ExplorerDuo2-" + SafeUser;
    public static string MutexName => @"Local\ExplorerDuo2-" + SafeUser;

    public static bool Send(string[] args)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
            client.Connect(3000);
            client.Write(JsonSerializer.SerializeToUtf8Bytes(args));
            client.Flush();
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static async Task ServeAsync(Action<string[]> onMessage, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(PipeName, PipeDirection.In,
                    NamedPipeServerStream.MaxAllowedServerInstances, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                await server.WaitForConnectionAsync(ct);
                using var ms = new MemoryStream();
                await server.CopyToAsync(ms, ct);
                var args = JsonSerializer.Deserialize<string[]>(ms.ToArray());
                if (args != null) onMessage(args);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch
            {
                await Task.Delay(200, CancellationToken.None);
            }
        }
    }
}
