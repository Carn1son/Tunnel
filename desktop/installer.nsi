; Tunnel for Windows: per-machine install (the VPN service runs as SYSTEM, so its files live in Program Files).
; makensis -DVERSION=2100.2.0 -DOUTFILE=Tunnel-Setup.exe -DBIN=dir_with_Tunnel.exe_and_engine installer.nsi
; Silent: /S ; /R starts Tunnel again afterwards (used by the in-app updater).
Unicode true
SetCompressor /SOLID lzma
RequestExecutionLevel admin
!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"

!ifndef VERSION
  !define VERSION "0.0.0"
!endif
!ifndef OUTFILE
  !define OUTFILE "Tunnel-Setup.exe"
!endif
!ifndef BIN
  !define BIN "bin"
!endif
!define APPNAME "Tunnel"
!define SERVICE "TunnelVPN"
!define UNKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}"

Name "${APPNAME}"
OutFile "${OUTFILE}"
InstallDir "$PROGRAMFILES64\${APPNAME}"
InstallDirRegKey HKLM "${UNKEY}" "InstallLocation"
BrandingText "${APPNAME} ${VERSION}"
VIProductVersion "1.0.0.0"
VIAddVersionKey "ProductName" "${APPNAME}"
VIAddVersionKey "FileDescription" "${APPNAME} — клиент TrustTunnel"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "CompanyName" "carnison"
VIAddVersionKey "LegalCopyright" "carnison"
VIAddVersionKey "FileVersion" "${VERSION}"

!define MUI_ICON "icon.ico"
!define MUI_UNICON "icon.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION RunAsUser
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"

Function RunAsUser
  ; the installer is elevated; Tunnel itself runs as the normal user (through Explorer)
  Exec '"$WINDIR\explorer.exe" "$INSTDIR\Tunnel.exe"'
FunctionEnd

Function CloseRunning
  nsExec::Exec `powershell -NoProfile -Command "Get-Process Tunnel -EA 0 | ? { $$_.Path -like '$INSTDIR\*' } | % { $$_.CloseMainWindow() | Out-Null }"`
  Sleep 1200
  nsExec::Exec `powershell -NoProfile -Command "Get-Process Tunnel -EA 0 | ? { $$_.Path -like '$INSTDIR\*' } | Stop-Process -Force"`
  ; the service holds its exe and the engine dll: stop it (it is started again on the next connect)
  nsExec::Exec 'sc.exe stop ${SERVICE}'
  Sleep 1500
FunctionEnd

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "Tunnel работает только на 64-битной Windows."
    Abort
  ${EndIf}
  SetRegView 64
FunctionEnd

Section "Tunnel"
  SetShellVarContext all
  Call CloseRunning
  SetOutPath "$INSTDIR"
  File "${BIN}\Tunnel.exe"
  File "${BIN}\trusttunnel.dll"
  File "${BIN}\trusttunnel_service.exe"
  File "${BIN}\trusttunnel_service_installer.exe"
  File "${BIN}\wintun.dll"
  File /nonfatal "${BIN}\TRUSTTUNNEL_LICENSE.txt"
  File /nonfatal "${BIN}\WINTUN_LICENSE.txt"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; the VPN service: installed once ($APPDATA is ProgramData under SetShellVarContext all), kept on updates (same name, same path)
  nsExec::ExecToStack 'sc.exe query ${SERVICE}'
  Pop $0
  Pop $1
  StrCmp $0 "0" svc_ok
    CreateDirectory "$APPDATA\Tunnel\logs"
    ExecWait '"$INSTDIR\trusttunnel_service_installer.exe" install "$INSTDIR\trusttunnel_service.exe" "$APPDATA\Tunnel\logs" "" "${SERVICE}" "Tunnel VPN" "VPN connection for the Tunnel app (TrustTunnel protocol)." "$APPDATA\Tunnel\connections.bin"' $2
    StrCmp $2 "0" svc_ok
      MessageBox MB_ICONEXCLAMATION "Не удалось установить службу Tunnel VPN (код $2). Подключение работать не будет."
  svc_ok:

  CreateShortcut "$SMPROGRAMS\${APPNAME}.lnk" "$INSTDIR\Tunnel.exe"
  IfSilent +2
    CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\Tunnel.exe"

  ; tt:// links open the import in Tunnel
  WriteRegStr HKLM "Software\Classes\tt" "" "URL:TrustTunnel"
  WriteRegStr HKLM "Software\Classes\tt" "URL Protocol" ""
  WriteRegStr HKLM "Software\Classes\tt\DefaultIcon" "" "$INSTDIR\Tunnel.exe,0"
  WriteRegStr HKLM "Software\Classes\tt\shell\open\command" "" '"$INSTDIR\Tunnel.exe" "%1"'

  WriteRegStr HKLM "${UNKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKLM "${UNKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNKEY}" "Publisher" "carnison"
  WriteRegStr HKLM "${UNKEY}" "DisplayIcon" "$INSTDIR\Tunnel.exe"
  WriteRegStr HKLM "${UNKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "${UNKEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKLM "${UNKEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNKEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKLM "${UNKEY}" "EstimatedSize" $0
SectionEnd

Function .onInstSuccess
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/R" $1
  IfErrors +2
    Call RunAsUser
FunctionEnd

Function un.onInit
  SetRegView 64
FunctionEnd

Section "Uninstall"
  SetShellVarContext all
  nsExec::Exec `powershell -NoProfile -Command "Get-Process Tunnel -EA 0 | ? { $$_.Path -like '$INSTDIR\*' } | Stop-Process -Force"`
  nsExec::Exec 'sc.exe stop ${SERVICE}'
  Sleep 1500
  ExecWait '"$INSTDIR\trusttunnel_service_installer.exe" uninstall "${SERVICE}"'
  Sleep 500
  DeleteRegKey HKLM "Software\Classes\tt"
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Tunnel"
  Delete "$SMPROGRAMS\${APPNAME}.lnk"
  Delete "$DESKTOP\${APPNAME}.lnk"
  Delete "$INSTDIR\Tunnel.exe"
  Delete "$INSTDIR\trusttunnel.dll"
  Delete "$INSTDIR\trusttunnel_service.exe"
  Delete "$INSTDIR\trusttunnel_service_installer.exe"
  Delete "$INSTDIR\wintun.dll"
  Delete "$INSTDIR\TRUSTTUNNEL_LICENSE.txt"
  Delete "$INSTDIR\WINTUN_LICENSE.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  RMDir /r "$APPDATA\Tunnel"
  DeleteRegKey HKLM "${UNKEY}"
SectionEnd
