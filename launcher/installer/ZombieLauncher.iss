; Inno Setup script to create a Windows installer for ZombieLauncher
; Requires Inno Setup (ISCC.exe) to compile this script.

[Setup]
AppName=ZombieLauncher
AppVersion=1.0.1
DefaultDirName={pf}\ZombieLauncher
DefaultGroupName=ZombieLauncher
OutputDir=..\\build\\installer
OutputBaseFilename=ZombieLauncher-Setup
Compression=lzma
SolidCompression=yes

[Files]
; Include the entire app-image produced by jpackage (exe + app + runtime)
Source: "..\\build\\installer\\ZombieLauncher\\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs

[Icons]
Name: "{group}\ZombieLauncher"; Filename: "{app}\ZombieLauncher.exe"
Name: "{commondesktop}\ZombieLauncher"; Filename: "{app}\ZombieLauncher.exe"; Tasks: desktopicon

[Tasks]
Name: desktopicon; Description: "Create a Desktop Icon"; GroupDescription: "Additional icons"; Flags: unchecked

[Run]
Filename: "{app}\ZombieLauncher.exe"; Description: "Launch ZombieLauncher"; Flags: nowait postinstall skipifsilent

; End of script
