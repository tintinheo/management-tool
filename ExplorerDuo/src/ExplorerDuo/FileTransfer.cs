using System.Runtime.InteropServices;

namespace ExplorerDuo;

[ComImport, Guid("947aab5f-0a5c-4c13-b4d6-4bf7836fc9f8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IFileOperation
{
    void Advise(IntPtr sink, out uint cookie);
    void Unadvise(uint cookie);
    void SetOperationFlags(uint flags);
    void SetProgressMessage([MarshalAs(UnmanagedType.LPWStr)] string message);
    void SetProgressDialog(IntPtr dialog);
    void SetProperties(IntPtr properties);
    void SetOwnerWindow(IntPtr owner);
    void ApplyPropertiesToItem(IShellItem item);
    void ApplyPropertiesToItems(IntPtr items);
    void RenameItem(IShellItem item, [MarshalAs(UnmanagedType.LPWStr)] string newName, IntPtr sink);
    void RenameItems(IntPtr items, [MarshalAs(UnmanagedType.LPWStr)] string newName);
    void MoveItem(IShellItem item, IShellItem destination, [MarshalAs(UnmanagedType.LPWStr)] string? newName, IntPtr sink);
    void MoveItems(IntPtr items, IShellItem destination);
    void CopyItem(IShellItem item, IShellItem destination, [MarshalAs(UnmanagedType.LPWStr)] string? newName, IntPtr sink);
    void CopyItems(IntPtr items, IShellItem destination);
    void DeleteItem(IShellItem item, IntPtr sink);
    void DeleteItems(IntPtr items);
    void NewItem(IShellItem folder, uint attributes, [MarshalAs(UnmanagedType.LPWStr)] string name, [MarshalAs(UnmanagedType.LPWStr)] string? template, IntPtr sink);
    void PerformOperations();
    [return: MarshalAs(UnmanagedType.Bool)] bool GetAnyOperationsAborted();
}

internal enum FileOp { Copy, Move, Delete }

/// <summary>Copy / move / recycle through the Windows copy engine: native progress, conflict, elevation and undo.</summary>
internal static class FileTransfer
{
    static readonly Guid ClsidFileOperation = new("3ad05575-8857-4850-9277-11b85bdb8e09");
    const uint FOF_ALLOWUNDO = 0x0040;
    const uint FOF_NOCONFIRMMKDIR = 0x0200;
    const int ERROR_CANCELLED = unchecked((int)0x800704C7);
    const int COPYENGINE_E_USER_CANCELLED = unchecked((int)0x80270000);

    [DllImport("shell32.dll", PreserveSig = false)]
    static extern void SHCreateShellItemArrayFromIDLists(uint count, IntPtr[] pidls, out IShellItemArray array);

    [DllImport("shell32.dll", PreserveSig = false)]
    static extern void SHCreateItemFromIDList(IntPtr pidl, [In] ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IShellItem item);

    /// <summary>Runs on a dedicated STA thread so the UI stays responsive while the copy engine works.</summary>
    public static Task RunAsync(IReadOnlyList<string> sources, string? destination, FileOp op, IntPtr owner) =>
        RunOnWorker(() => Execute(sources, destination, op, owner));

    /// <summary>
    /// Same, for items taken straight from a view (absolute ID lists; freed here). The whole batch goes to the copy
    /// engine as one operation without re-resolving every path, which matters for large selections.
    /// </summary>
    public static Task RunAsync(List<IntPtr> idLists, string? destination, FileOp op, IntPtr owner) =>
        RunOnWorker(() =>
        {
            try { ExecuteIdLists(idLists, destination, op, owner); }
            finally { foreach (var pidl in idLists) Marshal.FreeCoTaskMem(pidl); }
        });

    static Task RunOnWorker(Action work)
    {
        var tcs = new TaskCompletionSource();
        var thread = new Thread(() =>
        {
            try
            {
                work();
                tcs.SetResult();
            }
            catch (COMException ex) when (ex.HResult is ERROR_CANCELLED or COPYENGINE_E_USER_CANCELLED)
            {
                tcs.SetResult();
            }
            catch (Exception ex)
            {
                tcs.SetException(ex);
            }
        })
        { IsBackground = true, Name = "ExplorerDuo file operation" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return tcs.Task;
    }

    static void ExecuteIdLists(List<IntPtr> idLists, string? destination, FileOp op, IntPtr owner)
    {
        if (idLists.Count == 0) return;
        var fo = (IFileOperation)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidFileOperation)!)!;
        IShellItemArray? array = null;
        try
        {
            fo.SetOperationFlags(FOF_ALLOWUNDO | FOF_NOCONFIRMMKDIR);
            if (owner != IntPtr.Zero) fo.SetOwnerWindow(owner);
            SHCreateShellItemArrayFromIDLists((uint)idLists.Count, idLists.ToArray(), out array);
            var unknown = Marshal.GetIUnknownForObject(array);
            try
            {
                switch (op)
                {
                    case FileOp.Copy: fo.CopyItems(unknown, Shell.CreateItem(destination!)); break;
                    case FileOp.Move: fo.MoveItems(unknown, Shell.CreateItem(destination!)); break;
                    case FileOp.Delete: fo.DeleteItems(unknown); break;
                }
            }
            finally
            {
                Marshal.Release(unknown);
            }
            fo.PerformOperations();
        }
        finally
        {
            if (array != null) Marshal.FinalReleaseComObject(array);
            Marshal.FinalReleaseComObject(fo);
        }
    }

    /// <summary>Renames through the copy engine (so Ctrl+Z undoes it). Runs on the calling STA thread.</summary>
    public static void Rename(string path, string newName, IntPtr owner)
    {
        var fo = (IFileOperation)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidFileOperation)!)!;
        try
        {
            fo.SetOperationFlags(FOF_ALLOWUNDO);
            if (owner != IntPtr.Zero) fo.SetOwnerWindow(owner);
            fo.RenameItem(Shell.CreateItem(path), newName, IntPtr.Zero);
            fo.PerformOperations();
        }
        finally
        {
            Marshal.FinalReleaseComObject(fo);
        }
    }

    static void Execute(IReadOnlyList<string> sources, string? destination, FileOp op, IntPtr owner)
    {
        var fo = (IFileOperation)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidFileOperation)!)!;
        try
        {
            fo.SetOperationFlags(FOF_ALLOWUNDO | FOF_NOCONFIRMMKDIR); // ALLOWUNDO also means Delete → Recycle Bin
            if (owner != IntPtr.Zero) fo.SetOwnerWindow(owner);
            var dest = op == FileOp.Delete ? null : Shell.CreateItem(destination!);
            foreach (var source in sources)
            {
                var item = Shell.CreateItem(source);
                switch (op)
                {
                    case FileOp.Copy: fo.CopyItem(item, dest!, null, IntPtr.Zero); break;
                    case FileOp.Move: fo.MoveItem(item, dest!, null, IntPtr.Zero); break;
                    case FileOp.Delete: fo.DeleteItem(item, IntPtr.Zero); break;
                }
            }
            fo.PerformOperations();
        }
        finally
        {
            Marshal.FinalReleaseComObject(fo);
        }
    }
}
