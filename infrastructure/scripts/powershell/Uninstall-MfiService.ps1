<#
.SYNOPSIS
  Stops and removes a Technopick MFI Windows Service. Never deletes logs, dumps or
  config (they may be needed for audit or incident review); pass -RemoveBinaries to
  delete only the app and WinSW files.
  Status: parse-checked in CI; NOT yet executed on a Windows Server host.
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [ValidatePattern('^mfi-[a-z-]+$')] [string] $ServiceId,
    [string] $InstallRoot = 'D:\Technopick\MFI',
    [switch] $RemoveBinaries
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$installDir = Join-Path $InstallRoot $ServiceId
$exe = Join-Path $installDir "$ServiceId.exe"
if (-not (Test-Path $exe)) { throw "WinSW wrapper not found at $exe" }

if ($PSCmdlet.ShouldProcess($ServiceId, 'Stop and uninstall service')) {
    & $exe stop | Out-Host
    & $exe uninstall | Out-Host
}
if ($RemoveBinaries -and $PSCmdlet.ShouldProcess($installDir, 'Remove app binaries')) {
    Remove-Item (Join-Path $installDir 'app') -Recurse -Force
    Remove-Item $exe -Force
}
Write-Host "Uninstalled $ServiceId. Logs, dumps and config retained in $installDir."
