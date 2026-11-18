<#
.SYNOPSIS
  Installs one Technopick MFI deployable (core-banking, switch, integration, worker) as a
  Windows Service using WinSW.

.DESCRIPTION
  - Creates install, config, log directories with restrictive ACLs.
  - Verifies the WinSW executable against an expected SHA-256 (supply it; never download
    unverified binaries on a production host).
  - Renders the WinSW XML template and installs the service with delayed auto-start.
  - Runs under a group Managed Service Account (gMSA) or a dedicated least-privilege account.
  Idempotent: re-running with the same parameters upgrades the JAR and config in place
  (the previous JAR is kept as .previous for rollback).

  Status: written in Phase 1 increment 1; parse-checked in CI; NOT yet executed on a
  Windows Server host. Verify on the Windows test VM before first use.

.EXAMPLE
  .\Install-MfiService.ps1 -ServiceId mfi-core-banking -JarPath .\core-banking-0.1.0.jar `
     -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-25' -WinSwPath .\WinSW-x64.exe `
     -WinSwSha256 '<expected hash>' -ServiceAccount 'CONTOSO\gmsa-mfi-core$'
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [ValidatePattern('^mfi-[a-z-]+$')] [string] $ServiceId,
    [Parameter(Mandatory)] [ValidateScript({ Test-Path $_ -PathType Leaf })] [string] $JarPath,
    [Parameter(Mandatory)] [ValidateScript({ Test-Path (Join-Path $_ 'bin\java.exe') })] [string] $JavaHome,
    [Parameter(Mandatory)] [ValidateScript({ Test-Path $_ -PathType Leaf })] [string] $WinSwPath,
    [Parameter(Mandatory)] [ValidatePattern('^[A-Fa-f0-9]{64}$')] [string] $WinSwSha256,
    [Parameter(Mandatory)] [string] $ServiceAccount,
    [string] $InstallRoot = 'D:\Technopick\MFI',
    [string] $JvmOpts = '-Xms512m -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError',
    [string[]] $DependsOn = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$actualHash = (Get-FileHash -Algorithm SHA256 -Path $WinSwPath).Hash
if ($actualHash -ne $WinSwSha256.ToUpperInvariant()) {
    throw "WinSW hash mismatch. Expected $WinSwSha256, got $actualHash. Refusing to install."
}

$installDir = Join-Path $InstallRoot $ServiceId
$appDir     = Join-Path $installDir 'app'
$configDir  = Join-Path $installDir 'config'
$logDir     = Join-Path $installDir 'logs'
$dumpDir    = Join-Path $installDir 'dumps'
foreach ($d in @($installDir, $appDir, $configDir, $logDir, $dumpDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

# Restrictive ACLs: Administrators + SYSTEM full control, service account read (app/config)
# and modify (logs/dumps). Heap dumps are protected because they may contain sensitive data.
function Set-RestrictedAcl([string] $Path, [string] $Rights) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $inherit = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    $prop = [System.Security.AccessControl.PropagationFlags]::None
    foreach ($id in @('BUILTIN\Administrators', 'NT AUTHORITY\SYSTEM')) {
        $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($id, 'FullControl', $inherit, $prop, 'Allow')))
    }
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($ServiceAccount, $Rights, $inherit, $prop, 'Allow')))
    Set-Acl -Path $Path -AclObject $acl
}
if ($PSCmdlet.ShouldProcess($installDir, 'Apply ACLs')) {
    Set-RestrictedAcl $appDir 'ReadAndExecute'
    Set-RestrictedAcl $configDir 'Read'
    Set-RestrictedAcl $logDir 'Modify'
    Set-RestrictedAcl $dumpDir 'Modify'
}

$jarName = Split-Path $JarPath -Leaf
$targetJar = Join-Path $appDir $jarName
if (Test-Path $targetJar) { Copy-Item $targetJar "$targetJar.previous" -Force }
Copy-Item $JarPath $targetJar -Force

$exe = Join-Path $installDir "$ServiceId.exe"
Copy-Item $WinSwPath $exe -Force

$templatePath = Join-Path $PSScriptRoot '..\..\deployment\windows-server\winsw\service-template.xml'
$xml = Get-Content -Raw -Encoding UTF8 $templatePath
function Escape-Xml([string] $s) { [System.Security.SecurityElement]::Escape($s) }
# Plain values are XML-escaped; the two fragment tokens are built from escaped values.
$depends = ($DependsOn | ForEach-Object { "<depend>$(Escape-Xml $_)</depend>" }) -join "`n  "
$accountXml = "<serviceaccount><username>$(Escape-Xml $ServiceAccount)</username><allowservicelogon>true</allowservicelogon></serviceaccount>"
$jvm = "$JvmOpts -XX:HeapDumpPath=`"$dumpDir`""
$values = @{
    '@@SERVICE_ID@@'   = $ServiceId
    '@@DISPLAY_NAME@@' = "Technopick MFI - $ServiceId"
    '@@JAVA_HOME@@'    = $JavaHome
    '@@JVM_OPTS@@'     = $jvm
    '@@INSTALL_DIR@@'  = $installDir
    '@@JAR_NAME@@'     = $jarName
    '@@CONFIG_DIR@@'   = ($configDir -replace '\\', '/')
    '@@LOG_DIR@@'      = $logDir
}
foreach ($k in $values.Keys) { $xml = $xml.Replace($k, (Escape-Xml $values[$k])) }
$xml = $xml.Replace('@@DEPENDS@@', $depends).Replace('@@SERVICE_ACCOUNT@@', $accountXml)
Set-Content -Path (Join-Path $installDir "$ServiceId.xml") -Value $xml -Encoding UTF8

$existing = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
if ($PSCmdlet.ShouldProcess($ServiceId, 'Install or refresh Windows Service')) {
    if ($existing) {
        & $exe stop  | Out-Host
        & $exe refresh | Out-Host
    } else {
        & $exe install | Out-Host
    }
    & $exe start | Out-Host
}
Write-Host "Installed $ServiceId at $installDir. Health: check /actuator/health/readiness."
