using System.Diagnostics;

namespace ExplorerDuo;

internal static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        var cmd = CommandLine.Parse(args);
        var exe = Environment.ProcessPath ?? Application.ExecutablePath;

        switch (cmd.Verb)
        {
            case "--register":
                Integration.SetContextMenu(true, exe);
                return 0;
            case "--unregister":
                Integration.SetContextMenu(false, exe);
                return 0;
        }

        using var mutex = new Mutex(true, Ipc.MutexName, out bool firstInstance);
        if (!firstInstance)
        {
            // Launched from Explorer (which has the foreground) → let the running window come to the front.
            foreach (var p in Process.GetProcessesByName("ExplorerDuo"))
                if (p.Id != Environment.ProcessId) Native.AllowSetForegroundWindow(p.Id);
            if (Ipc.Send(args)) return 0;
        }

        ApplicationConfiguration.Initialize();
        Application.OleRequired(); // drag & drop targets (folder tree, views) need OLE, not just COM
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) =>
        {
            Log.Write("unhandled: " + e.Exception);
            MessageBox.Show(e.Exception.Message, "ExplorerDuo", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        };
        Application.Run(new MainForm(Settings.Load(), cmd.Paths));
        GC.KeepAlive(mutex);
        return 0;
    }
}
