---
name: StoreNET project overview
description: What StoreNET is, its components, and who the user is building it for
type: project
originSessionId: f4fc095c-432c-4778-8cbc-2fd6d1b31a43
---
StoreNET is a DG-style (Dollar General-style) store operations suite the user is building. It is currently a testbed / not deployed to real stores — design decisions can defer multi-site concerns.

**Components:**
- `backoffice_web/` — Node/Express web app (server.js, dashboard.html, database.js). Central "enterprise" DB + per-store DBs accessed via `getStorePool(storeId)`. Hosts Store Tasks tab (last tab) which also prints opening/closing reports.
- `dgPOS/` — Java POS register (MainTransactionScreen, PosLogin, DatabaseManager, PrinterService).
- `handheld_app/` — Android/Kotlin HHT scanner app (ScanScreen.kt, StoreNetApiService.kt). Has a Tasks option in the dropdown menu.
- `master_ingest.py` — master inventory ingest.

**Hardware already wired in server.js:**
- Receipt printer (Royal PT-300): `sendToPrinter` → `PRINTER_CONFIG` IP.
- Laser printer (HP LaserJet M276nw): `sendToLaserPrinter` → `192.168.0.233:9100`. This is the "big paper printer" for office-sized documents.

**Key existing modules:** planograms (pog_id/name/dimensions/suffix/pog_type) + planogram_items + push_logs + cycle_count_schedule + tasks (per-store: title/description/assigned_eid/due_date/priority/status OPEN|DONE) + time_punches + pricing_events + master_inventory. Push model: `/api/pogs/push` does per-store UPDATE against `inventory.location/faces/pog_info/position`.

**Why:** User works in retail (DG context based on artifacts like Pack-10 reset tags) and is building tooling that mirrors real retail ops flows.

**How to apply:** When suggesting features, reuse existing push/printer/tasks infrastructure rather than inventing parallel systems. Treat per-store printer routing, real-store concerns, and scale issues as deferrable until the project actually deploys.
