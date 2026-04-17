---
name: PRP Returns feature
description: Product Return Process batches — schema, endpoints, HHT screen, and dashboard view, implemented 2026-04-16
type: project
---
PRP (Product Return Process) Returns shipped 2026-04-16 based on `PRP_RETURNS_HANDOFF.md`. Follows the "DG store bundles defective/recall items and sends them back to vendor" model.

**Schema (per-store):** `prp_batches` (OPEN/CLOSED/SHIPPED/CANCELLED) and `prp_batch_items` — created in the storeContext init block in `server.js` alongside `inventory_adjustments` / `task_pog_items`.

**Invariants:**
- Only ONE batch may be OPEN per store at a time. `POST /api/prp/batches` returns 409 with the existing batch id if one is already open.
- Adding an item decrements `inventory.quantity`; removing an item credits it back. Close/ship do NOT touch inventory.
- Cannot close an empty batch. CLOSED → SHIPPED only (no reopening).
- Reason codes validated server-side: `DEFECTIVE, RECALL, EXPIRED, MFG_DEFECT, VENDOR_RETURN, CUSTOMER_RETURN`.

**Endpoints** (all per-store, use `req.pool`): `GET /api/prp/batches`, `GET /api/prp/batches/:id`, `POST /api/prp/batches`, `POST /api/prp/batches/:id/item`, `DELETE /api/prp/batches/:id/item/:itemId`, `POST /api/prp/batches/:id/close`, `POST /api/prp/batches/:id/ship`, `POST /api/prp/batches/:id/print`. Print uses `buildPrpManifestPDF` + `sendToLaserPrinter` (HP DeskJet 4155, queue `HP4155`).

**HHT** (`ScanScreen.kt`): nav drawer entry "PRP Returns" (color `0xFF5D4037`) routes to `PrpReturnsContent`. Scans on that screen feed `prpScannedUpc` state which opens an Add-Item dialog with qty/reason/notes. Closing the batch auto-fires a print of the manifest. HHT "Tasks" integration was NOT done — PRP is a self-contained flow.

**Dashboard** (`dashboard.html`): left-nav "PRP Returns" module (`#5D4037`) with filters (Open / Closed / Shipped), table listing batches, View detail, Print, and Mark Shipped (prompts for carrier + tracking).

**Orphan cleanup:** Removed the stub `TransfersPRPContent` composable and its tab from the Transfers screen — PRP is owned by the nav-drawer screen only.

**Why:** Hand-off from a prior session specified full-stack in one pass; user chose that plus deleting the orphan Transfers > PRP tab rather than merging.

**How to apply:** When extending PRP (e.g. shipping manifest barcode scan-in, or vendor-specific return paperwork), reuse `buildPrpManifestPDF` and the existing endpoints rather than forking a parallel lifecycle. If a user wants to reopen a shipped batch, that's a new requirement — current design refuses it.
