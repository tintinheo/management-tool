# NotifyHub

A local Windows app that captures every toast notification Windows shows (Teams, Outlook,
Slack, browsers, Jira desktop, and so on) and keeps them in one searchable feed with message
previews. History is stored locally in a SQLite database inside the app's package storage.

## Requirements

- Windows 10 1809 or later, or Windows 11
- .NET 8 SDK
- VS Code with the C# Dev Kit extension (or Visual Studio 2022)
- Developer Mode enabled in Windows settings

## Why packaging is needed

Reading other apps' notifications goes through `UserNotificationListener`, which requires the
`userNotificationListener` capability. Capabilities are declared in an app package manifest, so
NotifyHub has to run with package identity (MSIX). Unpackaged, the access request fails.

## Run with Visual Studio Code

VS Code can't build Visual Studio packaging projects, so this repo registers the build output
folder directly as a package instead ("loose layout"). `Package/AppxManifest.xml` declares the
capability and is copied next to `NotifyHub.exe` on every build.

One-time setup:

1. Install the .NET 8 SDK and the **C# Dev Kit** extension in VS Code.
2. Turn on **Developer Mode**: Settings > System > For developers (Windows 11), or
   Settings > Update & Security > For developers (Windows 10).
3. On an ARM PC, change `ProcessorArchitecture="x64"` to `arm64` in `Package/AppxManifest.xml`.

Run it:

1. Open the `NotifyHub` folder in VS Code.
2. Press **Ctrl+Shift+B** (runs the **register and run** task), or in a terminal:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\run.ps1
   ```
3. Approve the "Let NotifyHub access your notifications?" prompt.

Always launch NotifyHub through the package: the script, the Start menu entry, or
`shell:AppsFolder`. Double-clicking `NotifyHub.exe` in `bin` runs it without package identity,
and notification access fails.

Debug it: start the app with the task, then press **F5** with **Attach to NotifyHub** and pick
`NotifyHub.exe` from the process list.

After code changes, run the task again. It closes the app, rebuilds, re-registers, and relaunches.

## Install for daily use

Publish a Release build to a permanent folder and register it from there:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run.ps1 -Configuration Release -OutDir C:\Apps\NotifyHub
```

NotifyHub then appears in the Start menu like any other app. Don't delete or move
`C:\Apps\NotifyHub`: the registration points at those files.

To uninstall:

```powershell
Get-AppxPackage NotifyHub.Local | Remove-AppxPackage
```

Uninstalling also deletes the notification history, because packaged apps keep their
`%LOCALAPPDATA%` writes in per-package storage.

## Alternative: Visual Studio 2022

If you'd rather use Visual Studio, add a **Windows Application Packaging Project** that
references `NotifyHub`, add `<uap3:Capability Name="userNotificationListener" />` to its
`Package.appxmanifest`, and set it as the startup project. In that case, remove the loose-layout
`ItemGroup` from `NotifyHub.csproj` so the two manifests don't conflict.

## Using it

- New notifications appear at the top within about 2 seconds.
- Filter by app with the dropdown, or search across titles, messages and app names.
- Selecting a notification marks it read; **Mark all read** clears the unread dots.
- Right-click a notification to copy its text or delete it.

## What it can and can't capture

- It captures anything that lands in the Windows notification center, including during
  Do Not Disturb.
- It can't capture in-app popups that never become Windows toasts, or apps whose
  notifications you've turned off in Windows settings.
- A notification dismissed within the 2-second polling window can be missed. Lower the interval
  in `MainWindow.xaml.cs` if that matters to you.
- Only text is captured; images and action buttons are not.
- The database is not encrypted. It may contain message previews from work chat and email, so
  treat it like any other local copy of that data.

## Ideas for next steps

- Tray icon with an unread badge, and minimize-to-tray on close (`H.NotifyIcon.Wpf`)
- App logos via `AppInfo.DisplayInfo.GetLogo(...)`
- Start with Windows (`StartupTask` in the package manifest)
- Retention setting, e.g. delete notifications older than 30 days
- Rules: mute apps, highlight keywords such as your name or "PIV"
