; Inno Setup recipe for the Horizon Translator installer (scripts/build-desktop.sh runs it).
; Installs for the current user only, so it needs no administrator rights.
#define AppVersion GetEnv("APP_VERSION")
#if AppVersion == ""
  #define AppVersion "1.0.0"
#endif

#ifndef BuildDir
  #define BuildDir "build"
#endif
#ifndef DistDir
  #define DistDir "..\dist"
#endif

[Setup]
AppId={{6F3C2E8A-9B1D-4C7E-A5F0-2D8B4E6C1A93}
AppName=Horizon Translator
AppVersion={#AppVersion}
AppPublisher=Horizon Translator (community project)
AppPublisherURL=https://github.com/merenotnoonereal/Horizon-Translator
DefaultDirName={localappdata}\Programs\Horizon Translator
DefaultGroupName=Horizon Translator
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputDir={#DistDir}
OutputBaseFilename=HorizonTranslator-Setup
SetupIconFile={#BuildDir}\logo.ico
UninstallDisplayIcon={app}\Horizon Translator.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
LicenseFile=installer-notice.txt

[Files]
Source: "{#BuildDir}\app-image\Horizon Translator\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Horizon Translator"; Filename: "{app}\Horizon Translator.exe"
Name: "{userdesktop}\Horizon Translator"; Filename: "{app}\Horizon Translator.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Run]
Filename: "{app}\Horizon Translator.exe"; Description: "Open Horizon Translator"; Flags: nowait postinstall skipifsilent
