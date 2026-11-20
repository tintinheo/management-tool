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

    /// <summary>Runs on a dedicated STA thread so the UI stays responsive while the copy engine works.</summary>
    public static Task RunAsync(IReadOnlyList<string> sources, string? destination, FileOp op, IntPtr owner)
    {
        var tcs = new TaskCompletionSource();
        var thread = new Thread(() =>
        {
            try
            {
                Execute(sources, destination, op, owner);
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
