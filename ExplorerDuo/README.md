# ExplorerDuo 2.5.1

ExplorerDuo is a dual-panel file manager with tabs, in the style of Multi Commander. Each tab shows a
real Windows Explorer view (the official `IExplorerBrowser` shell component). The file list,
right-click menu with your add-ons (7-Zip, Git, …), thumbnails, rename, drag-and-drop and undo all
come from Windows Explorer itself. ExplorerDuo adds the window around those views: two panes, tabs, a
folder tree, bookmarks, colour highlighting and commander-style commands.

## Run

```
dist\ExplorerDuo-2.5.1\ExplorerDuo.exe [folder ...]
```

You need the .NET 8 Desktop Runtime. Each folder you pass opens as a tab. If ExplorerDuo is already
running, the folders open as tabs in that window.

Build and test from source:

```
dotnet publish src/ExplorerDuo/ExplorerDuo.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o dist/ExplorerDuo-2.5.1
dotnet test
```

## Look
Minimalist and warm: ivory window, charcoal text, a single champagne-gold accent, hairline borders,
and serif headings (the ExplorerDuo wordmark, tab titles and dialog titles). Each pane and the shared
folder tree sit on their own card. The active pane has a gold-tinted outline and a slim gold line
under its current tab. On Windows 11 the title bar matches: an ivory caption with a fine gold border.
The file lists themselves are Windows' own views, so they keep the Windows look.

Highlight colours are muted jewel tones: Garnet, Amber, Gold, Sage, Teal, Sapphire, Amethyst and Rose.
If your settings still contain the old bright colours (the 2.3 defaults or the old Highlight menu
colours), they switch to the matching new colours automatically, once. Any colour you typed in
yourself stays as it is.

## Cloud status (OneDrive)
In OneDrive folders, the Windows **Status** column (synced, available online only, always available,
syncing) is added automatically, right after Name. This also works in other cloud folders that are
registered with Windows, such as Dropbox, Google Drive and iCloud. Turn it off under ⋯ → **Cloud status
column in OneDrive folders**.

## Notion and Notion Calendar
ExplorerDuo can show three things across the whole window. Pick one with the switch at the top right:
**Files** (your two Explorer panes), **Notion**, or **Calendar** (Notion Calendar).

- **Ctrl+J** switches between your files and Notion. It goes back to whichever you used last, Notion
  or Calendar, and it works while you're typing in Notion too.
- **Ctrl+Shift+J** switches between Notion and Notion Calendar.
- You can also click **Notion** in the bottom bar (it has the black N mark), or **Files** in the
  Notion header to go back.
- A bookmark you click, or a folder opened from Windows Explorer, takes you back to Files.

You can always tell which side you're on:
- **Files** keeps the ivory and gold look.
- **Notion** uses Notion's own white and ink. It has a white header with the Notion or Calendar mark
  and the page title, and the window border turns ink.
- The window title shows the Notion page, for example "Roadmap — Notion — ExplorerDuo".
- Until you first open Notion, a small gold dot on the switch points it out.

The other details:
- **Sign in once.** ExplorerDuo remembers your sign-in and the last page you had open, also after a
  restart, and starts on the side you were last using. This sign-in is separate from your web
  browser and the Notion apps.
- **Calendar** shows the Google accounts you've connected in Notion Calendar.
- **Links to other websites** open in your default browser. Notion pages opened from Calendar show
  in Notion.
- Header buttons: back, reload, open the page in the Notion desktop app (or Notion Calendar), and
  **Files**.
- **Sign out** under ⋯ → **Notion**. This clears ExplorerDuo's Notion cookies and cache only.
- Nothing loads until you open Notion the first time, and Calendar loads only when you first show it.
  While you're in Files, the Notion pages are paused so they use no CPU.
- Notion needs the Microsoft Edge WebView2 Runtime. Windows 11 includes it.

Notion Calendar has no public API, so ExplorerDuo shows the Notion Calendar web app itself rather than
its own calendar view.

## Taskbar icon
ExplorerDuo has its own icon, a charcoal tile with two ivory panes and gold tab marks. It shows on the
taskbar, in Alt+Tab, in Start and when you pin the program, and stays sharp at any display scaling.

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
| Ctrl+B | Show / hide the folder tree on the left |
| Ctrl+Shift+B | A folder tree inside each pane (on / off) |
| Ctrl+J / Ctrl+Shift+J | Files ↔ Notion (full window) · Notion ↔ Notion Calendar |

Explorer's own keys also work: Ctrl+C/X/V, Ctrl+Z, F2, Ctrl+A, Ctrl+Shift+N and Alt+Enter.

## Folder tree
The folder tree is Explorer's own navigation tree: Home; Desktop, Downloads, Documents, Pictures, Music
and Videos; This PC; and Network. Choose where it appears with the tree button at the right end of
each address bar, or under ⋯ → **Folder tree**:

| Choice | What you get |
|---|---|
| **On the left** (Ctrl+B) | One tree for the whole window. Clicking a folder opens it in the active pane (the pane with the blue tab marker), and the tree follows the active pane. |
| **In each pane** (Ctrl+Shift+B) | Each pane gets its own tree, and each tree follows and drives only its own pane. |
| **No folder tree** | More room for the file lists. |

- **Tab** moves from a tree into the pane it drives.
- **Drag and drop onto tree folders.** Drop files on a folder in the tree to copy or move them there,
  the same as in Explorer: within one drive, a drop moves; between drives, it copies. Hold **Shift**
  to move, hold **Ctrl** to copy, or drag with the right mouse button to choose.
- **Drop into another tab.** While dragging, hold the pointer over a tab for half a second to switch
  to it, then drop into its file list.

## Right-click menus
The right-click menus in the file lists and in the folder trees are the complete Windows menus: Open
with, Send to, New, Paste, Undo, View, Sort by, Properties, and every add-on such as 7-Zip, Git or
VS Code. Hold **Shift** while right-clicking to get the extended items, such as "Copy as path" and
"Open PowerShell window here".

ExplorerDuo adds its own commands at the top of each menu:
- **On files and folders**: open in the other pane or as a new tab, copy or move to the other pane,
  zip or unzip to the other pane, Edit, Multi-Rename, copy full paths, and Highlight.
- **On empty space**: show this folder in the other pane, open it in a new tab, new folder, terminal
  here, select by pattern, invert selection, find files, copy the folder path, bookmark, and
  highlight.
- **On a tree folder**: open in the other pane or as a new tab; **copy or move the active pane's
  selection into this folder**; new folder, rename, terminal here, copy path, bookmark, and highlight.

The menus need the Classic list view, which is on by default. With the modern Windows 11 list, you
get Windows' own menu without the ExplorerDuo commands.

## Speed and memory
- **Colouring is cached.** Each item's colour is looked up once per folder instead of on every
  repaint, so scrolling large folders with colour rules uses much less CPU. The folder tree skips
  colouring completely when there are no rules.
- **Faster copy and move.** F5, F6 and F8, and "Copy/Move here" in the tree menu, pass the selected
  items straight to the Windows copy engine in one batch. Paths aren't parsed again from disk, which
  helps with thousands of files. The operation runs on its own thread, so you can keep browsing
  while it copies. You still get the normal Windows progress, conflict and undo handling.
- **No freezes from slow drives.** Free disk space is read in the background and refreshed at most
  every 5 seconds. A sleeping USB disk or a disconnected network drive no longer freezes the window
  when you open it.
- **Background tabs release their memory.** A tab you haven't looked at for 10 minutes closes its
  Explorer view and any add-ons the view loaded. The folder stays in the tab, and the view reloads
  when you click the tab, but its Back/Forward history is lost. Turn this off under ⋯ → **Unload
  tabs unused for 10 min**, or change `unloadIdleTabsMinutes` in the settings (0 = never).
- **Less memory when minimized.** Freed memory is given back to Windows when you minimize the window.
- **Faster start-up.** The program is precompiled (ReadyToRun) and uses a smaller garbage-collector
  configuration.

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
- `treeMode` (`left`, `panes` or `none`), plus the tree widths
- `unloadIdleTabsMinutes` (10 by default; 0 = never unload)
- `showCloudStatus` (true by default)
- `workspace` (`files`, `notion` or `calendar`), `notionDockTab` (the Notion side used last), and the
  last Notion and Calendar pages (`notionUrl`, `calendarUrl`)
- your session

Notion keeps its sign-in in `%LocalAppData%\ExplorerDuo\WebView2`.

Errors are written to `%AppData%\ExplorerDuo\log.txt`.
