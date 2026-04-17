---
name: StoreNET deployment topology
description: Server runs on a separate Linux box; Windows dev machine SSHes in to deploy; printer is USB on the Linux host
type: reference
---
The StoreNET node server and MySQL run on a **separate Linux machine**, not the Windows dev box where code is edited. Workflow:
- Edit code on Windows (this repo).
- SSH into the Linux server to copy files over and restart node.
- The laser printer (CUPS queue `HP4155`, see `LASER_PRINTER_QUEUE` in server.js:650) is **USB-connected to the Linux box**, not to Windows.

**How to apply:**
- `sendToLaserPrinter` shells out to `lp -d HP4155`. `lp` IS present (it's Linux/CUPS) — don't assume missing `lp` when diagnosing print 500s. Instead check: is the CUPS queue named `HP4155` actually configured? Is it enabled/accepting? Is the printer online? Does the `node` user have permission to print? Check `lpstat -p HP4155` on the server.
- Don't propose Windows-specific print paths (PowerShell Out-Printer, SumatraPDF). The server is Linux.
- When diagnosing runtime bugs, remember the observable logs are on the Linux host — the user has to SSH in to pull them. Propose `journalctl -u <service>` or whatever tail command matches the real process, rather than assuming logs are available locally.
- File paths in the repo use Windows conventions (this is the dev checkout); the *runtime* paths on the Linux server may differ if anything is absolute.
