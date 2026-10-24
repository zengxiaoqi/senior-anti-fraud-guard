' Hidden launcher for the anti-fraud backend server (ASCII only)
' Usage: wscript scripts\start-server-hidden.vbs
Dim fso
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
' Repo root = parent of the scripts/ dir holding this file
sh.CurrentDirectory = fso.GetParentFolderName(fso.GetParentFolderName(WScript.ScriptFullName))
' Kill any existing listener on port 3000 first
sh.Run "cmd /c for /f ""tokens=5"" %p in ('netstat -ano ^| findstr "":3000 .*LISTENING""') do taskkill /F /PID %p >nul 2>&1", 0, True
' Start node server detached, logs to server.log
sh.Run "cmd /c node server.js > server.log 2>&1", 0, False
