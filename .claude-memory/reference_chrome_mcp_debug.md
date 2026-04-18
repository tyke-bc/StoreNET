---
name: Chrome MCP debugging workflow for StoreNET
description: How to use the in-Chrome MCP to debug the StoreNET dashboard live (network + console buffers)
type: reference
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
The user has the Claude Chrome extension installed and uses it for debugging the StoreNET dashboard. Setup that worked 2026-04-17:

1. `tabs_context_mcp({ createIfEmpty: true })` — creates a new MCP-owned Chrome window/tab. Don't try to adopt their existing tabs.
2. `navigate(tabId, "http://192.168.0.192:3000")` — protocol must be `http://` (local Linux server, not HTTPS).
3. The user logs in manually (don't try to type the dev-user PIN — privacy rule blocks me from entering passwords/PINs even when they offer).
4. Wait for them to do whatever, then pull buffers.

**Critical gotcha — buffers don't backfill.** `read_console_messages` and `read_network_requests` only start tracking when **first called**. Anything that happened before the first call is lost. Always:
- Arm the buffers (call both with `clear: true`) BEFORE telling the user "go reproduce it now."
- Same-route navigation keeps the buffer; navigating to a different domain wipes network requests.
- Refreshing the page also clears (and the user explicitly said "reloading stops the debugging" — don't suggest a refresh as a fix).

**Useful default queries:**
- Console: `{ onlyErrors: true, pattern: ".*", limit: 200 }` to surface JS exceptions.
- Network: `{ urlPattern: "/api/", limit: 200 }` to filter to backend calls.

**Real win from this approach:** caught `dashboard.html:1395` `(o.subtotal || 0).toFixed is not a function` — mysql2 returns DECIMAL columns as strings, the `|| 0` fallback is bypassed because strings are truthy, then `.toFixed` blows up. Fix: `Number(o.x || 0).toFixed(2)`. Worth checking dashboard.html for other places this pattern repeats whenever DECIMAL columns are formatted.
