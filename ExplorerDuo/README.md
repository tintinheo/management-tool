# ExplorerDuo 2.2.0

ExplorerDuo is a dual-panel file manager with tabs, in the style of Multi Commander. Each tab shows a
real Windows Explorer view (the official `IExplorerBrowser` shell component). The file list,
right-click menu with your add-ons (7-Zip, Git, …), thumbnails, rename, drag-and-drop and undo all
come from Windows Explorer itself. ExplorerDuo adds the window around those views: two panes, tabs, a
folder tree, bookmarks, colour highlighting and commander-style commands.

## Run

```
dist\ExplorerDuo-2.2.0\ExplorerDuo.exe [folder ...]
```

You need the .NET 8 Desktop Runtime. Each folder you pass opens as a tab. If ExplorerDuo is already
running, the folders open as tabs in that window.

Build and test from source:

```
dotnet publish src/ExplorerDuo/ExplorerDuo.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o dist/ExplorerDuo-2.2.0
dotnet test
```

## Middle-click: open in the other pane
- **Folder in the file list**: opens that folder in the opposite pane. Hold **Shift** to open it as a
  new tab there instead.
- **Empty space in the file list**: the opposite pane shows the same folder.
- **Folder in the tree**: opens it in the pane that is *not* active.
- **Bookmark**: opens it in the opposite pane.
- **Tab**: closes the tab.

## Bookmarks
The bar under the title shows your bookmarks.
- **Ctrl+D** (or ☆ Bookmark) bookmarks the active pane's folder.
- **Alt+1…9** opens bookmarks 1 to 9.
- Click a bookmark to open it in the active pane. **Ctrl+click** opens it as a new tab, and a
  **middle-click** opens it in the other pane.
- Right-click a bookmark to rename, reorder or remove it.

## Colour highlighting
- **Ctrl+H** (or the **Highlight** button) colours the selected files or folders, or the current
  folder if nothing is selected. There are 8 colours plus **Remove highlight**.
- Colour rules by file name come built in:
  - programs and scripts: green
  - archives: purple
  - images: blue
  - audio and video: orange
  - documents: red
- Hidden items are shown in grey.
- Highlights show in the file list, the folder tree and Find Files results.
- To edit the rules, choose ⋯ → **Edit settings**, change `colorRules` (patterns such as
  `*.psd;*.ai`, a colour, and `appliesTo`: `all`, `files` or `folders`), and save. The changes load
  automatically when you switch back to ExplorerDuo.
- Colours need the **Classic list view**, which is on by default (⋯ menu). It is the Windows 7-style
  Explorer list; the modern Windows 11 list doesn't let other programs colour rows.

## Commander commands

| Keys | Action |
|---|---|
| Tab | Switch to the other pane |
| F4 | Edit the selected files (`editor` setting, Notepad by default) |
| F5 / F6 | Copy / move the selection to the other pane (Windows copy engine: progress, conflicts, undo) |
| F7 | New folder |
| F8 or Delete | Send the selection to the Recycle Bin |
| F9 | Open a terminal in this folder (Windows Terminal, or cmd if it isn't installed) |
| Alt+F5 | Zip the selection into the other pane |
| Alt+F9 | Unzip the selected .zip files into the other pane (each into its own folder) |
| Ctrl+M | Multi-Rename: tokens `[N]` name, `[E]` extension, `[C]` counter, `[P]` parent, `[D]` date; find/replace; case; live preview; safe for swaps |
| Ctrl+F | Find Files: name wildcards, optional text inside files, with or without sub-folders, results as they are found. Double-click a result to show it in the active pane; middle-click to show it in the other pane |
| Num + / Num − / Num * | Select by pattern / deselect by pattern / invert the selection |
| Ctrl+Shift+C | Copy the full paths of the selection (or of the current folder) |
| Ctrl+D, Alt+1…9 | Bookmark this folder, open bookmarks 1 to 9 |
| Ctrl+H | Highlight menu |
| Ctrl+E | Show this folder in the other pane too |
| Ctrl+U | Swap the folders of the two panes |
| Ctrl+Enter / Ctrl+Shift+Enter | Open the selected folders as new tabs here / in the other pane |
| Ctrl+T / Ctrl+W / Ctrl+Shift+T | New tab / close tab / reopen the last closed tab |
| Ctrl+Tab, Ctrl+1…9 | Next tab, jump to a tab |
| Ctrl+L, Alt+D | Edit the address |
| Alt+←/→, Backspace, Alt+↑ | Back / forward / parent folder |
| Ctrl+R | Refresh |
| Ctrl+B | Show / hide the folder tree |

Explorer's own keys also work: Ctrl+C/X/V, Ctrl+Z, F2, Ctrl+A, Ctrl+Shift+N and Alt+Enter.

## Folder tree
Explorer's own navigation tree is on the far left: Home; Desktop, Downloads, Documents, Pictures,
Music and Videos; This PC; and Network.
- Click a folder to open it in the active pane, the pane with the blue tab marker.
- When the active pane changes folder, the tree highlights that folder.
- **Tab** moves from the tree into the active pane.

## Opening many folders at once
- Press **Ctrl+Enter** or **Ctrl+Shift+Enter** on selected folders to open them as tabs, or use
  middle-click.
- In the ⋯ menu, turn on **"Open in ExplorerDuo"** to add it to Explorer's right-click menu. Then each
  folder you select in Windows Explorer opens as a tab. On Windows 11 the entry is under "Show more
  options".
- All tabs in both panes are reopened when you next start ExplorerDuo.

## Settings
Settings are stored in `%AppData%\ExplorerDuo\settings.json`:
- `bookmarks`
- `highlights` (path → colour)
- `colorRules`
- `hiddenColor`
- `editor`
- `classicListView`
- tree visible and tree width
- your session

Errors are written to `%AppData%\ExplorerDuo\log.txt`.
