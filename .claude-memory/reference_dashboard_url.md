---
name: StoreNET dashboard URL
description: Local URL for the StoreNET backoffice dashboard on the Linux server
type: reference
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
The StoreNET backoffice runs at `http://192.168.0.192:3000` on the Linux server. Hitting the root routes to the login page, then through to the dashboard.

**How to apply:**
- When debugging via Chrome MCP, navigate to `http://192.168.0.192:3000` in the MCP-owned tab. The user logs in manually (a dev user exists but credentials are not stored here — the user enters them).
- It's HTTP not HTTPS — include the protocol explicitly when navigating.
- Server is on the Linux box (see `reference_deployment.md`); this URL won't resolve from anywhere outside the LAN.
