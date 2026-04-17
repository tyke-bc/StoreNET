---
name: Vendors / DSD / DG Respond feature
description: Full DSD stack — vendor master data, DG Respond web app, vendor returns/orders, HHT vendor deliveries, built 2026-04-16
type: project
---
DSD (Direct Store Delivery) vendor stack shipped 2026-04-16 as an extension on top of PRP Returns.

**What "DSD" means here:** vendors that deliver direct to the store (Coke, Frito-Lay, bread, etc.) rather than through the warehouse. They bring product in person, leave invoices, pick up bad product themselves. Parallel to but separate from truck manifests and PRP.

**Data model:**
- Enterprise: `vendors` (code, name, contact, delivery_schedule, active). `master_inventory.vendor_id` (nullable FK → vendors, SET NULL on delete).
- Per-store: `inventory.vendor_id` (nullable FK).
- Per-store lifecycle tables: `vendor_returns` + `vendor_return_items`, `vendor_orders` + `vendor_order_items`, `vendor_deliveries` + `vendor_delivery_items`.
- Vendor deletes are **soft** (UPDATE active = 0) — historic returns/orders keep their FK.

**Four distinct surfaces, four distinct jobs** (updated 2026-04-16 after user clarified DG Respond is an HHT app, not web):
1. **Dashboard Vendors module** (left nav "Vendors (DSD)") — SM/DM manages the vendor roster.
2. **DG Respond HHT app** — the tile on the launcher home screen (Page1, row 3, `LauncherScreen.kt:135`) now wires to `navController.navigate("respond/14302")` → `RespondScreen.kt` (hardcoded storeId like COMPASS). Flow: vendor picker → hub (rep name + Returns / DSD Order buttons) → scan-and-add sessions. Reuses `MainActivity.scanEvents`. **This is the primary vendor flow** — used in-aisle with the rep present.
3. **DG Respond web** (`/respond` → `respond.html`) — repurposed as the **SM desk tool**, NOT a scanning UI. Three tabs: Vendor Orders (browse/search/filter + detail modal where SM enriches with PO#, carrier, driver, truck#, expected/actual delivery, mark FULFILLED), Vendor Returns (browse/detail/reprint memo), Tag Items to Vendors (paginated search over master_inventory with per-row vendor dropdown saving via `PUT /api/vendors/master-tag`). Store picker modal pops on load.
4. **HHT Vendor Delivery** (nav drawer "Vendor Delivery" in `ScanScreen.kt`) — vendor drops off product, employee scans each item in, on-hand increments per scan. Uses `VendorGreen = 0xFF059669`.

**Separation rules vs PRP:**
- PRP batches = warehouse/return-center route (shipped out; truck + shipping label).
- Vendor returns = vendor takes it in person; no shipping; credit memo instead of manifest.
- They are completely separate tables and endpoints. A SKU without a `vendor_id` goes through PRP; a tagged SKU can go through either, but DG Respond only shows vendor-tagged SKUs in the "low stock" list.

**Routing gotcha:** `/api/vendors/*` is mostly enterprise (no per-store context), EXCEPT `/api/vendors/:id/inventory` which reads per-store `inventory`. The storeContext middleware excludes anything under `/api/vendors` that doesn't end in `/inventory` — see the `isEnterpriseVendor` check.

**Endpoints added:**
- `GET/POST/PUT/DELETE /api/vendors` (enterprise)
- `PUT /api/vendors/master-tag` (enterprise, ties a SKU to a vendor)
- `GET /api/inventory/master-search?q=&limit=&offset=` (enterprise; paginated, feeds the Tag Items tab)
- `GET /api/vendors/:id/inventory` (per-store, for DG Respond HHT's low-stock list)
- `/api/vendor-returns/*` — list/detail/create/add-item/remove-item/close/print
- `/api/vendor-orders/*` — list/detail/create/add-item/remove-item/submit/print + `PUT :id/meta` (PO#/carrier/driver/truck#/dates/notes) + `POST :id/fulfill`
- `/api/vendor-deliveries/*` — list/detail/create/scan/complete

**vendor_orders metadata columns** (added via ALTER migrations in the per-store init): `po_number`, `carrier`, `driver_name`, `driver_phone`, `truck_number`, `expected_delivery` (DATE), `actual_delivery` (DATETIME), `fulfilled_at` (TIMESTAMP). These are all nullable and editable post-submit — the SM fills them in at their desk after the rep leaves.

**Scan-to-order suggestion:** DG Respond's order flow pre-fills qty as `max(1, reorder_max - on_hand)` when scanning, so scanning low items auto-suggests a sane quantity.

**Inventory rules (consistent with PRP + existing):**
- Vendor return: add decrements, remove credits back, close locks (no ship step).
- Vendor order: does NOT touch inventory (it's a request).
- Vendor delivery: each scan increments on-hand immediately (no pre-arranged manifest).

**Order→Delivery bridge (how the two tables actually connect):**
- Vendor order PDF (`buildVendorOrderPDF` in server.js ~4598) prints a Code128 barcode of `VO-{id}` on the DSD ORDER REQUEST slip — this slip IS the receiving document.
- HHT `GET /api/vendor-orders/resolve/:code` accepts "VO-123" or "123", returns the SUBMITTED order + items + any in-progress delivery for resume (server.js:4507).
- Creating a delivery with `order_id` set links them. Scan endpoint rejects SKUs not on the linked order with a reconcile hint (server.js:4796–4804).
- Completing a linked delivery auto-cascades the order to FULFILLED with `actual_delivery = NOW()` (server.js:4828–4836).
- `/api/vendor-orders/:id/cancel` exists (OPEN or SUBMITTED only; FULFILLED is sealed).

**Vendor invoices:** there is no separate invoice entity. `vendor_deliveries.invoice_number` is a plain VARCHAR captured at delivery creation — the paper invoice the vendor hands over is just referenced by number, not stored as a document. AP/matching is out of scope.

**Why:** User noticed PRP alone couldn't model DSD vendors like Frito-Lay (they don't ship back through us, they take it themselves) and asked to extend. Chose full-stack-in-one-pass over phased. Named it "DG Respond" after the real app vendor reps use.

**How to apply:** When extending — for example adding a "vendor check-in log" or tying a `vendor_delivery` to a `vendor_order` for fulfillment tracking — reuse the existing lifecycle endpoints rather than creating a 4th vendor_* table. The `vendor_deliveries.order_id` FK already exists for that linkage; it's just not exposed in UI yet.

**Split-of-concerns rule for future changes:** scan-driven, in-aisle-with-rep flows go on the HHT (`RespondScreen.kt` / `ScanScreen.kt`). Desk-driven, post-hoc enrichment and bulk admin (tagging items, filling in truck/driver info, browsing history, paginated search) go on `/respond`. Don't mix them — the HHT is for "right now, rep is here" moments, the web is for "after the fact, at the desk" moments.
