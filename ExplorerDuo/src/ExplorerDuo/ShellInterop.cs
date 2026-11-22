using System.Runtime.InteropServices;

namespace ExplorerDuo;

// COM declarations for the Windows shell's embeddable Explorer view (IExplorerBrowser, Vista+).
// Method order matters: each interface lists every vtable slot up to the last one we call.

[StructLayout(LayoutKind.Sequential)]
internal struct FOLDERSETTINGS
{
    public uint ViewMode;
    public uint Flags;
}

[ComImport, Guid("DFD3B6B5-C10C-4BE9-85F6-A66969F402F6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IExplorerBrowser
{
    void Initialize(IntPtr hwndParent, [In] ref RECT rc, [In] ref FOLDERSETTINGS fs);
    void Destroy();
    void SetRect([In, Out] ref IntPtr hdwp, RECT rcBrowser);
    void SetPropertyBag([MarshalAs(UnmanagedType.LPWStr)] string bag);
    void SetEmptyText([MarshalAs(UnmanagedType.LPWStr)] string text);
    [PreserveSig] int SetFolderSettings([In] ref FOLDERSETTINGS fs);
    [PreserveSig] int Advise(IntPtr events, out uint cookie);
    [PreserveSig] int Unadvise(uint cookie);
    void SetOptions(uint flags);
    void GetOptions(out uint flags);
    [PreserveSig] int BrowseToIDList(IntPtr pidl, uint flags);
    [PreserveSig] int BrowseToObject([MarshalAs(UnmanagedType.IUnknown)] object punk, uint flags);
    void FillFromObject([MarshalAs(UnmanagedType.IUnknown)] object punk, int flags);
    void RemoveAll();
    [PreserveSig] int GetCurrentView(ref Guid riid, out IntPtr ppv);
}

[ComImport, Guid("361BBDC7-E6EE-4E13-BE58-58E2240C810F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IExplorerBrowserEvents
{
    [PreserveSig] int OnNavigationPending(IntPtr pidlFolder);
    [PreserveSig] int OnViewCreated([MarshalAs(UnmanagedType.IUnknown)] object shellView);
    [PreserveSig] int OnNavigationComplete(IntPtr pidlFolder);
    [PreserveSig] int OnNavigationFailed(IntPtr pidlFolder);
}

[ComImport, Guid("000214E3-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IShellView
{
    [PreserveSig] int GetWindow(out IntPtr hwnd);
    [PreserveSig] int ContextSensitiveHelp([MarshalAs(UnmanagedType.Bool)] bool enterMode);
    [PreserveSig] int TranslateAccelerator(ref MSG msg);
    [PreserveSig] int EnableModeless([MarshalAs(UnmanagedType.Bool)] bool enable);
    [PreserveSig] int UIActivate(uint state);
    [PreserveSig] int Refresh();
    [PreserveSig] int CreateViewWindow(IntPtr previous, IntPtr fs, IntPtr browser, IntPtr rc, out IntPtr hwnd);
    [PreserveSig] int DestroyViewWindow();
    [PreserveSig] int GetCurrentInfo(IntPtr fs);
    [PreserveSig] int AddPropertySheetPages(uint reserved, IntPtr callback, IntPtr lparam);
    [PreserveSig] int SaveViewState();
    [PreserveSig] int SelectItem(IntPtr pidl, uint flags);
    /// <summary>SVGIO_BACKGROUND / SVGIO_SELECTION → the same IContextMenu the view itself would show.</summary>
    [PreserveSig] int GetItemObject(uint item, ref Guid riid, out IntPtr ppv);
}

[ComImport, Guid("CDE725B0-CCC9-4519-917E-325D72FAB4CE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IFolderView
{
    [PreserveSig] int GetCurrentViewMode(out uint mode);
    [PreserveSig] int SetCurrentViewMode(uint mode);
    [PreserveSig] int GetFolder(ref Guid riid, out IntPtr ppv);
    [PreserveSig] int Item(int index, out IntPtr pidl);
    [PreserveSig] int ItemCount(uint flags, out int count);
    [PreserveSig] int Items(uint flags, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object? items);
}

/// <summary>Full IFolderView + IFolderView2 vtable up to GetItem (index → IShellItem).</summary>
[ComImport, Guid("1AF3A467-214F-4298-908E-06B03E0B39F9"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IFolderView2
{
    // IFolderView
    [PreserveSig] int GetCurrentViewMode(out uint mode);
    [PreserveSig] int SetCurrentViewMode(uint mode);
    [PreserveSig] int GetFolder(ref Guid riid, out IntPtr ppv);
    [PreserveSig] int Item(int index, out IntPtr pidl);
    [PreserveSig] int ItemCount(uint flags, out int count);
    [PreserveSig] int Items(uint flags, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object? items);
    [PreserveSig] int GetSelectionMarkedItem(out int item);
    [PreserveSig] int GetFocusedItem(out int item);
    [PreserveSig] int GetItemPosition(IntPtr pidl, out POINT pt);
    [PreserveSig] int GetSpacing(out POINT pt);
    [PreserveSig] int GetDefaultSpacing(out POINT pt);
    [PreserveSig] int GetAutoArrange();
    [PreserveSig] int SelectItem(int item, uint flags);
    [PreserveSig] int SelectAndPositionItems(uint count, IntPtr pidls, IntPtr points, uint flags);
    // IFolderView2
    [PreserveSig] int SetGroupBy(IntPtr key, int ascending);
    [PreserveSig] int GetGroupBy(IntPtr key, out int ascending);
    [PreserveSig] int SetViewProperty(IntPtr pidl, IntPtr key, IntPtr value);
    [PreserveSig] int GetViewProperty(IntPtr pidl, IntPtr key, IntPtr value);
    [PreserveSig] int SetTileViewProperties(IntPtr pidl, [MarshalAs(UnmanagedType.LPWStr)] string props);
    [PreserveSig] int SetExtendedTileViewProperties(IntPtr pidl, [MarshalAs(UnmanagedType.LPWStr)] string props);
    [PreserveSig] int SetText(int type, [MarshalAs(UnmanagedType.LPWStr)] string text);
    [PreserveSig] int SetCurrentFolderFlags(uint mask, uint flags);
    [PreserveSig] int GetCurrentFolderFlags(out uint flags);
    [PreserveSig] int GetSortColumnCount(out int count);
    [PreserveSig] int SetSortColumns(IntPtr columns, int count);
    [PreserveSig] int GetSortColumns(IntPtr columns, int count);
    [PreserveSig] int GetItem(int item, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object? ppv);
    [PreserveSig] int GetVisibleItem(int start, int previous, out int item);
    [PreserveSig] int GetSelectedItem(int start, out int item);
    [PreserveSig] int GetSelection(int noneImpliesFolder, out IntPtr items);
    [PreserveSig] int GetSelectionState(IntPtr pidl, out uint flags);
    [PreserveSig] int InvokeVerbOnSelection([MarshalAs(UnmanagedType.LPStr)] string? verb);
    [PreserveSig] int SetViewModeAndIconSize(int mode, int imageSize);
    [PreserveSig] int GetViewModeAndIconSize(out int mode, out int imageSize);
    [PreserveSig] int SetGroupSubsetCount(uint visibleRows);
    [PreserveSig] int GetGroupSubsetCount(out uint visibleRows);
    [PreserveSig] int SetRedraw(int redraw);
    [PreserveSig] int IsMoveInSameFolder();
    [PreserveSig] int DoRename();
}

/// <summary>Lets an ExplorerBrowser host ask for the classic (SysListView32) layout, which supports custom-draw colours.</summary>
[ComImport, Guid("3CC974D2-B302-4D36-AD3E-06D93F695D3F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IFolderViewOptions
{
    [PreserveSig] int SetFolderViewOptions(uint mask, uint flags);
    [PreserveSig] int GetFolderViewOptions(out uint flags);
}

[ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IShellItem
{
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetParent(out IShellItem parent);
    [PreserveSig] int GetDisplayName(uint sigdn, out IntPtr name);
    [PreserveSig] int GetAttributes(uint mask, out uint attributes);
    void Compare(IShellItem other, uint hint, out int order);
}

[ComImport, Guid("B63EA76D-1F85-456F-A19C-48159EFA858B"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IShellItemArray
{
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetPropertyStore(int flags, ref Guid riid, out IntPtr ppv);
    void GetPropertyDescriptionList(IntPtr keyType, ref Guid riid, out IntPtr ppv);
    void GetAttributes(int attribFlags, uint mask, out uint attributes);
    void GetCount(out uint count);
    void GetItemAt(uint index, out IShellItem item);
}

[ComImport, Guid("00000114-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IOleWindow
{
    [PreserveSig] int GetWindow(out IntPtr hwnd);
    [PreserveSig] int ContextSensitiveHelp([MarshalAs(UnmanagedType.Bool)] bool enterMode);
}

/// <summary>The shell's folder tree (Explorer's navigation pane control).</summary>
[ComImport, Guid("028212A3-B627-47E9-8856-C14265554E4F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface INameSpaceTreeControl
{
    void Initialize(IntPtr hwndParent, [In] ref RECT rc, uint styles);
    void TreeAdvise([MarshalAs(UnmanagedType.IUnknown)] object sink, out uint cookie);
    void TreeUnadvise(uint cookie);
    void AppendRoot(IShellItem root, uint enumFlags, uint rootStyle, IntPtr filter);
    void InsertRoot(int index, IShellItem root, uint enumFlags, uint rootStyle, IntPtr filter);
    void RemoveRoot(IShellItem root);
    void RemoveAllRoots();
    void GetRootItems(out IShellItemArray roots);
    [PreserveSig] int SetItemState(IShellItem item, uint mask, uint flags);
    [PreserveSig] int GetItemState(IShellItem item, uint mask, out uint flags);
    [PreserveSig] int GetSelectedItems(out IShellItemArray? items);
    [PreserveSig] int GetItemCustomState(IShellItem item, out int state);
    [PreserveSig] int SetItemCustomState(IShellItem item, int state);
    [PreserveSig] int EnsureItemVisible(IShellItem item);
    [PreserveSig] int SetTheme([MarshalAs(UnmanagedType.LPWStr)] string theme);
    [PreserveSig] int GetNextItem(IShellItem? item, uint flags, out IShellItem? next);
    [PreserveSig] int HitTest(ref POINT pt, out IShellItem? item);
    [PreserveSig] int GetItemRect(IShellItem item, out RECT rect);
}

/// <summary>Queried on the TreeAdvise sink; lets us colour tree items.</summary>
[ComImport, Guid("2D3BA758-33EE-42D5-BB7B-5F3431D86C78"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface INameSpaceTreeControlCustomDraw
{
    [PreserveSig] int PrePaint(IntPtr hdc, IntPtr rc, out IntPtr result);
    [PreserveSig] int PostPaint(IntPtr hdc, IntPtr rc);
    [PreserveSig] int ItemPrePaint(IntPtr hdc, IntPtr rc, IntPtr item, ref uint textColor, ref uint backColor, out IntPtr result);
    [PreserveSig] int ItemPostPaint(IntPtr hdc, IntPtr rc, IntPtr item);
}

[ComImport, Guid("93D77985-B3D8-4484-8318-672CDDA002CE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface INameSpaceTreeControlEvents
{
    [PreserveSig] int OnItemClick(IntPtr item, uint hitTest, uint clickType);
    [PreserveSig] int OnPropertyItemCommit(IntPtr item);
    [PreserveSig] int OnItemStateChanging(IntPtr item, uint mask, uint state);
    [PreserveSig] int OnItemStateChanged(IntPtr item, uint mask, uint state);
    [PreserveSig] int OnSelectionChanged(IntPtr selection);
    [PreserveSig] int OnKeyboardInput(uint msg, IntPtr wParam, IntPtr lParam);
    [PreserveSig] int OnBeforeExpand(IntPtr item);
    [PreserveSig] int OnAfterExpand(IntPtr item);
    [PreserveSig] int OnBeginLabelEdit(IntPtr item);
    [PreserveSig] int OnEndLabelEdit(IntPtr item);
    [PreserveSig] int OnGetToolTip(IntPtr item, IntPtr tip, int cchTip);
    [PreserveSig] int OnBeforeItemDelete(IntPtr item);
    [PreserveSig] int OnItemAdded(IntPtr item, int isRoot);
    [PreserveSig] int OnItemDeleted(IntPtr item, int isRoot);
    [PreserveSig] int OnBeforeContextMenu(IntPtr item, ref Guid riid, out IntPtr ppv);
    [PreserveSig] int OnAfterContextMenu(IntPtr item, IntPtr contextMenu, ref Guid riid, out IntPtr ppv);
    [PreserveSig] int OnBeforeStateImageChange(IntPtr item);
    [PreserveSig] int OnGetDefaultIconIndex(IntPtr item, out int defaultIcon, out int openIcon);
}

/// <summary>Folder-tree callbacks. Only selection changes matter; everything else keeps the default behaviour.</summary>
[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
public sealed class TreeEvents : INameSpaceTreeControlEvents, INameSpaceTreeControlCustomDraw
{
    const int S_OK = 0, S_FALSE = 1, E_NOTIMPL = unchecked((int)0x80004001);
    const int CDRF_DODEFAULT = 0x0, CDRF_NEWFONT = 0x2, CDRF_NOTIFYITEMDRAW = 0x20;
    readonly Action<IntPtr> _selectionChanged;
    readonly Func<IShellItem, uint?> _colorOf;
    readonly Func<bool> _wantsColors;

    internal TreeEvents(Action<IntPtr> selectionChanged, Func<IShellItem, uint?> colorOf, Func<bool> wantsColors)
    {
        _selectionChanged = selectionChanged;
        _colorOf = colorOf;
        _wantsColors = wantsColors;
    }

    public int ItemPaintCalls { get; private set; }

    // No colour rules → no per-row callbacks at all.
    public int PrePaint(IntPtr hdc, IntPtr rc, out IntPtr result)
    {
        result = (IntPtr)(_wantsColors() ? CDRF_NOTIFYITEMDRAW : CDRF_DODEFAULT);
        return S_OK;
    }
    public int PostPaint(IntPtr hdc, IntPtr rc) => S_OK;
    public int ItemPostPaint(IntPtr hdc, IntPtr rc, IntPtr item) => S_OK;

    public int ItemPrePaint(IntPtr hdc, IntPtr rc, IntPtr item, ref uint textColor, ref uint backColor, out IntPtr result)
    {
        result = (IntPtr)CDRF_DODEFAULT;
        ItemPaintCalls++;
        try
        {
            // NSTCCUSTOMDRAW starts with the IShellItem* being drawn.
            var psi = Marshal.ReadIntPtr(item);
            if (psi == IntPtr.Zero || Marshal.GetObjectForIUnknown(psi) is not IShellItem shellItem) return S_OK;
            if (_colorOf(shellItem) is uint color)
            {
                textColor = color;
                result = (IntPtr)CDRF_NEWFONT;
            }
        }
        catch
        {
            // Never break painting.
        }
        return S_OK;
    }

    public int OnItemClick(IntPtr item, uint hitTest, uint clickType) => S_FALSE;
    public int OnPropertyItemCommit(IntPtr item) => S_FALSE;
    public int OnItemStateChanging(IntPtr item, uint mask, uint state) => S_OK;
    public int OnItemStateChanged(IntPtr item, uint mask, uint state) => S_OK;
    public int OnSelectionChanged(IntPtr selection) { _selectionChanged(selection); return S_OK; }
    public int OnKeyboardInput(uint msg, IntPtr wParam, IntPtr lParam) => S_FALSE;
    public int OnBeforeExpand(IntPtr item) => S_OK;
    public int OnAfterExpand(IntPtr item) => S_OK;
    public int OnBeginLabelEdit(IntPtr item) => S_OK;
    public int OnEndLabelEdit(IntPtr item) => S_OK;
    public int OnGetToolTip(IntPtr item, IntPtr tip, int cchTip) => E_NOTIMPL;
    public int OnBeforeItemDelete(IntPtr item) => E_NOTIMPL;
    public int OnItemAdded(IntPtr item, int isRoot) => E_NOTIMPL;
    public int OnItemDeleted(IntPtr item, int isRoot) => E_NOTIMPL;
    public int OnBeforeContextMenu(IntPtr item, ref Guid riid, out IntPtr ppv) { ppv = IntPtr.Zero; return E_NOTIMPL; }
    public int OnAfterContextMenu(IntPtr item, IntPtr contextMenu, ref Guid riid, out IntPtr ppv) { ppv = IntPtr.Zero; return E_NOTIMPL; }
    public int OnBeforeStateImageChange(IntPtr item) => E_NOTIMPL;
    public int OnGetDefaultIconIndex(IntPtr item, out int defaultIcon, out int openIcon) { defaultIcon = openIcon = 0; return E_NOTIMPL; }
}

/// <summary>Receives navigation callbacks from one embedded Explorer view.</summary>
[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
public sealed class BrowserEvents : IExplorerBrowserEvents
{
    readonly Action<IntPtr> _completed;
    readonly Action _failed;
    readonly Action<object> _viewCreated;

    internal BrowserEvents(Action<IntPtr> completed, Action failed, Action<object> viewCreated)
    {
        _completed = completed;
        _failed = failed;
        _viewCreated = viewCreated;
    }

    public int OnNavigationPending(IntPtr pidlFolder) => 0;
    public int OnViewCreated(object shellView) { _viewCreated(shellView); return 0; }
    public int OnNavigationComplete(IntPtr pidlFolder) { _completed(pidlFolder); return 0; }
    public int OnNavigationFailed(IntPtr pidlFolder) { _failed(); return 0; }
}

internal static class Shell
{
    public const uint SIGDN_NORMALDISPLAY = 0;
    public const uint SIGDN_DESKTOPABSOLUTEPARSING = 0x80028000;
    public const uint SFGAO_HIDDEN = 0x00080000;
    public const uint SFGAO_STREAM = 0x00400000;
    public const uint SFGAO_FOLDER = 0x20000000;
    public const uint SFGAO_FILESYSTEM = 0x40000000;

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern int SHParseDisplayName(string name, IntPtr bindCtx, out IntPtr pidl, uint sfgaoIn, out uint sfgaoOut);

    [DllImport("shell32.dll")]
    static extern int SHGetNameFromIDList(IntPtr pidl, uint sigdn, out IntPtr name);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    static extern void SHCreateItemFromParsingName(string path, IntPtr bindCtx, [In] ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IShellItem item);

    /// <summary>Path / "::{CLSID}" / "shell:Downloads" / "%USERPROFILE%" → PIDL (caller frees), or 0.</summary>
    public static IntPtr Parse(string path)
    {
        var text = PathUtil.ToParsable(path);
        if (text.Length == 0) return IntPtr.Zero;
        return SHParseDisplayName(text, IntPtr.Zero, out var pidl, 0, out _) == 0 ? pidl : IntPtr.Zero;
    }

    public static void Free(IntPtr pidl)
    {
        if (pidl != IntPtr.Zero) Marshal.FreeCoTaskMem(pidl);
    }

    public static string? NameOf(IntPtr pidl, uint sigdn)
    {
        if (pidl == IntPtr.Zero || SHGetNameFromIDList(pidl, sigdn, out var p) != 0) return null;
        try { return Marshal.PtrToStringUni(p); }
        finally { Marshal.FreeCoTaskMem(p); }
    }

    public static string? NameOf(IShellItem item, uint sigdn)
    {
        if (item.GetDisplayName(sigdn, out var p) != 0) return null;
        try { return Marshal.PtrToStringUni(p); }
        finally { Marshal.FreeCoTaskMem(p); }
    }

    public static IShellItem CreateItem(string parsingName)
    {
        var iid = typeof(IShellItem).GUID;
        SHCreateItemFromParsingName(PathUtil.ToParsable(parsingName), IntPtr.Zero, ref iid, out var item);
        return item;
    }
}
