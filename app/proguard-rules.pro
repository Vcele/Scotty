# Instantiated by Shizuku by class name inside the shell process.
-keep class app.scotty.shell.ScottyShellService { <init>(...); *; }
-keep class app.scotty.shell.IScottyShell** { *; }
