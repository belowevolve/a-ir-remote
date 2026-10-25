Unicode true
!include MUI2.nsh
!include LogicLib.nsh
!include x64.nsh
!include WinVer.nsh
Name "Air Remote"
OutFile "..\..\artifacts\Air-Remote-Setup-0.5.5.exe"
InstallDir "$PROGRAMFILES64\Air Remote"
InstallDirRegKey HKLM "Software\AirRemote" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
Icon "..\assets\air-remote.ico"
UninstallIcon "..\assets\air-remote.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchCompanion
!define MUI_FINISHPAGE_RUN_TEXT "Запустить Air Remote"
!define MUI_FINISHPAGE_LINK "VB-CABLE · поддержать автора"
!define MUI_FINISHPAGE_LINK_LOCATION "https://vb-audio.com/Cable/"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "NOTICE.txt"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "English"
Function LaunchCompanion
  Exec '"$WINDIR\explorer.exe" "$INSTDIR\air-remote-companion.exe"'
FunctionEnd
Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "Нужна 64-битная Windows 10 или 11."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "Нужна Windows 10 или новее."
    Abort
  ${EndIf}
  SetRegView 64
  SetShellVarContext all
FunctionEnd
Section "Air Remote: пульт, клавиатура и синхронизация" Core
  SectionIn RO
  ; Shut down only our own companion, preserving pairing data.
  IfFileExists "$INSTDIR\air-remote-companion.exe" 0 +3
    ExecWait '"$INSTDIR\air-remote-companion.exe" --quit'
    Sleep 300
  FindWindow $0 "AirRemoteCompanion"
  ${If} $0 != 0
    SendMessage $0 ${WM_COMMAND} 104 0 /TIMEOUT=3000
    Sleep 300
  ${EndIf}
  ; Preserve the startup choice when upgrading from the old Startup shortcut.
  IfFileExists "$SMSTARTUP\Air Remote.lnk" 0 +3
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "AirRemote" '$\"$INSTDIR\air-remote-companion.exe$\" --background'
    Delete "$SMSTARTUP\Air Remote.lnk"
  SetOutPath "$INSTDIR"
  File "..\target\x86_64-pc-windows-gnu\release\air-remote-companion.exe"
  File "NOTICE.txt"
  File "..\target\bundle\THIRD-PARTY-NOTICES.txt"
  WriteRegStr HKLM "Software\AirRemote" "InstallDir" "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  Delete "$SMPROGRAMS\Air Remote\Air Remote.lnk"
  RMDir "$SMPROGRAMS\Air Remote"
  SetShellVarContext current
  CreateDirectory "$SMPROGRAMS\Air Remote"
  CreateShortcut "$SMPROGRAMS\Air Remote\Air Remote.lnk" "$INSTDIR\air-remote-companion.exe"
  SetShellVarContext all
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote" "DisplayName" "Air Remote"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote" "DisplayVersion" "0.5.5"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote" "DisplayIcon" "$INSTDIR\air-remote-companion.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote" "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote" "NoRepair" 1
  nsExec::ExecToLog '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Air Remote Companion"'
  Pop $0
  nsExec::ExecToLog '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="Air Remote Companion" dir=in action=allow program="$INSTDIR\air-remote-companion.exe" protocol=TCP localport=54346 remoteip=LocalSubnet profile=any'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_OK|MB_ICONEXCLAMATION "Не удалось настроить брандмауэр. При запуске разрешите Air Remote доступ к локальной сети."
  ${EndIf}
SectionEnd
Section "Микрофон: VB-CABLE (donationware)" Mic
  SetOutPath "$INSTDIR\vbcable"
  File /r "..\target\bundle\vbcable\*.*"
  ExecWait '"$INSTDIR\air-remote-companion.exe" --check-cable' $0
  ${If} $0 != 0
    DetailPrint "Установка оригинального VB-CABLE от VB-Audio"
    ExecWait '"$INSTDIR\vbcable\VBCABLE_Setup_x64.exe"' $0
    ExecWait '"$INSTDIR\air-remote-companion.exe" --check-cable' $1
    ${If} $1 == 0
      SetRebootFlag true
    ${Else}
      MessageBox MB_OK|MB_ICONINFORMATION "VB-CABLE пока недоступен. Если драйвер установлен, перезагрузите Windows. Повторить установку можно из Air Remote Setup. Пульт и клавиатура уже готовы."
    ${EndIf}
  ${Else}
    DetailPrint "VB-CABLE уже установлен. Существующие настройки сохранены."
  ${EndIf}
SectionEnd
Section /o "Запускать вместе с Windows" Startup
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "AirRemote" '$\"$INSTDIR\air-remote-companion.exe$\" --background'
SectionEnd
Function un.onInit
  SetRegView 64
  SetShellVarContext all
FunctionEnd
Section "Uninstall"
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "AirRemote"
  ExecWait '"$INSTDIR\air-remote-companion.exe" --quit'
  Sleep 300
  nsExec::ExecToLog '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Air Remote Companion"'
  Pop $0
  Delete "$SMSTARTUP\Air Remote.lnk"
  Delete "$SMPROGRAMS\Air Remote\Air Remote.lnk"
  RMDir "$SMPROGRAMS\Air Remote"
  SetShellVarContext current
  Delete "$SMPROGRAMS\Air Remote\Air Remote.lnk"
  RMDir "$SMPROGRAMS\Air Remote"
  SetShellVarContext all
  Delete "$INSTDIR\air-remote-companion.exe"
  Delete "$INSTDIR\NOTICE.txt"
  Delete "$INSTDIR\THIRD-PARTY-NOTICES.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir /r "$INSTDIR\vbcable"
  RMDir "$INSTDIR"
  DeleteRegKey HKLM "Software\AirRemote"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirRemote"
  ; VB-CABLE may be used by other applications; do not uninstall its driver.
SectionEnd
