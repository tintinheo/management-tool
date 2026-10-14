<#
  Builds NotifyHub, registers the output folder as a (loose) MSIX package, and launches it.
  Requires Windows Developer Mode.

  Examples:
    .\scripts\run.ps1                                   # Debug build, for development
    .\scripts\run.ps1 -Configuration Release -OutDir C:\Apps\NotifyHub   # "install" for daily use
    .\scripts\run.ps1 -Reinstall                        # unregister first (also wipes the app's history and access consent)
#>
param(
    [ValidateSet('Debug', 'Release')] [string] $Configuration = 'Debug',
    [string] $OutDir,
    [switch] $Reinstall,
    [switch] $NoLaunch
)

$ErrorActionPreference = 'Stop'
# Works whether this script sits in the project folder or in its scripts\ subfolder.
$root = @($PSScriptRoot, (Split-Path $PSScriptRoot -Parent)) |
    Where-Object { Test-Path (Join-Path $_ 'NotifyHub.csproj') } |
    Select-Object -First 1
if (-not $root) { throw "NotifyHub.csproj not found in $PSScriptRoot or its parent folder." }
$project = Join-Path $root 'NotifyHub.csproj'
$packageName = 'NotifyHub.Local'

# A running instance locks the exe.
Get-Process NotifyHub -ErrorAction SilentlyContinue | Stop-Process -Force

if ($OutDir) {
    dotnet publish $project -c $Configuration -o $OutDir
} else {
    dotnet build $project -c $Configuration
    $OutDir = Join-Path $root "bin\$Configuration\net8.0-windows10.0.19041.0"
}
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

if ($Reinstall) {
    Get-AppxPackage -Name $packageName | Remove-AppxPackage
}

$manifest = Join-Path (Resolve-Path $OutDir) 'AppxManifest.xml'
Add-AppxPackage -Register $manifest -ForceApplicationShutdown

$pkg = Get-AppxPackage -Name $packageName
Write-Host "Registered $($pkg.PackageFullName) from $OutDir"

if (-not $NoLaunch) {
    # Launching through the package (not NotifyHub.exe directly) is what gives the app its identity.
    Start-Process "shell:AppsFolder\$($pkg.PackageFamilyName)!App"
}
