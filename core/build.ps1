# Build the TrustTunnel engine for Tunnel: AdGuard's TrustTunnelClient at core/REVISION
# plus the split-tunnelling hook (core/apply_split.py). Run from a VS x64 developer shell.
# Output: core/out/ with trusttunnel.dll, trusttunnel_service.exe, trusttunnel_service_installer.exe, wintun.dll, licenses.
param([string]$Version = "1.3.0")
$ErrorActionPreference = "Stop"
$Core = Split-Path -Parent $MyInvocation.MyCommand.Path
$Src = Join-Path $Core "src"
$Rev = (Get-Content (Join-Path $Core "REVISION")).Trim()

if (-not (Test-Path (Join-Path $Src ".git"))) {
    git init -q $Src
    git -C $Src remote add origin https://github.com/TrustTunnel/TrustTunnelClient.git
}
git -C $Src fetch -q --depth 1 origin $Rev
git -C $Src checkout -q --force FETCH_HEAD
git -C $Src clean -qfdx -e platform/windows/build -e platform/windows/wintun -e "platform/windows/wintun-*.zip"
python (Join-Path $Core "apply_split.py") $Src
if ($LASTEXITCODE -ne 0) { throw "apply_split failed" }

# Strawberry Perl's gcc confuses the compiler detection (see TrustTunnelClient DEVELOPMENT.md)
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -notlike '*Strawberry\c\bin*' }) -join ';'

Push-Location $Src
try {
    python scripts/bootstrap_conan_deps.py
    if ($LASTEXITCODE -ne 0) { throw "bootstrap_conan_deps failed" }
    & platform\windows\scripts\build.ps1 -Version $Version -Arch x86_64 -BuildType Release
    if ($LASTEXITCODE -ne 0) { throw "build.ps1 failed" }
} finally { Pop-Location }

$Stage = Join-Path $Src "platform\windows\staging\trusttunnel-client-windows-$Version-x86_64"
$Out = Join-Path $Core "out"
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force $Out | Out-Null
foreach ($f in "trusttunnel.dll", "trusttunnel_service.exe", "trusttunnel_service_installer.exe", "wintun.dll") {
    $p = Get-ChildItem -Recurse -File -Path $Stage -Filter $f | Select-Object -First 1
    if (-not $p) { Get-ChildItem -Recurse $Stage | Format-Table FullName; throw "$f not found in the staging directory" }
    Copy-Item $p.FullName $Out
}
Copy-Item (Join-Path $Src "LICENSE") (Join-Path $Out "TRUSTTUNNEL_LICENSE.txt")
if (Test-Path (Join-Path $Stage "WINTUN_LICENSE.txt")) { Copy-Item (Join-Path $Stage "WINTUN_LICENSE.txt") $Out }
Get-ChildItem $Out | Format-Table Name, Length
