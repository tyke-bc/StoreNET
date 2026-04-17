---
name: Feature extensions shipped 2026-04-17
description: Four extensions added in one session — BOPIS short-pick reasons, POG reset history, vendor visits, recurring tasks
type: project
---
Four feature extensions shipped 2026-04-17 on top of the existing StoreNET feature surface. All four went full-stack: server endpoints + HHT (where relevant) + dashboard widgets.

## 1. BOPIS short-pick reason codes
- Per-store migration: `ALTER TABLE online_order_items ADD COLUMN short_reason ENUM('OOS','DAMAGED','NOT_FOUND','SUB_OFFERED') NULL`.
- `POST /api/bopis/finalize/:id` now accepts `{ short_reasons: { [sku]: reason } }` and **rejects** if any short-picked item lacks a reason (400 with `missing_skus` array). Validation list `BOPIS_SHORT_REASONS` lives in server.js.
- `GET /api/bopis/short-pick-report?days=30` — returns `summary` by reason + `top_skus` (limit 20). Drives the dashboard panel in Online Orders module.
- HHT: when user taps FINALIZE PARTIAL, a dialog lists every short-picked SKU with radio buttons (OOS / Damaged / Couldn't find / Substitution offered). Finalize button in dialog is disabled until every SKU has a reason. `onFinalize` callback signature changed to `(Map<String,String>) -> Unit`.

## 2. POG reset history + in-progress HHT card
- No schema change — data was already in `task_pog_items` (scanned_at, scanned_by_name). Just exposed it.
- `GET /api/pogs/reset/tasks?status=OPEN|DONE|ALL&limit=25` — returns pog_total / pog_done / last_scan_at / last_scan_by per reset task.
- `GET /api/pogs/reset/tasks/:id` — detail with all children.
- HHT Home: under the existing open-tasks banner, a purple "In-progress resets" strip shows any OPEN reset with `pog_done > 0 && pog_done < pog_total`, with "last: Alex · 2026-04-17 14:22" and `3/7` progress. Tap jumps to Tasks screen.
- Dashboard: "POG Reset History" card in Tasks module with Open/Completed/All filter buttons and a progress-bar column.

## 3. Vendor visits (DSD check-in log)
- New per-store table `vendor_visits (id, vendor_id, rep_name, checked_in_at, checked_out_at, checked_in_by_eid, notes)`.
- Endpoints: `GET /api/vendor-visits/active` (static — registered BEFORE `:id`), `GET /api/vendor-visits` (recent, optional `?vendor_id=`), `GET /api/vendor-visits/:id` (detail with activity — returns/orders/deliveries in the visit window by timestamp), `POST /api/vendor-visits` (refuses 409 if vendor already has active visit), `POST /api/vendor-visits/:id/checkout`.
- HHT: added check-in surface on DG Respond `VendorHubView` above the Returns / DSD Order buttons. Green "CHECKED IN" badge once logged; turns into CHECK OUT button. `activeVisit` state fetched when vendor is picked. Uses `MainActivity.loggedInEid`.
- Dashboard: "Vendor Visits (check-in log)" card in Vendors module — On-Site Now / Recent toggle, click row for detail panel that lists any returns/orders/deliveries captured in the visit window.

## 4. Recurring task generator
- New per-store table `task_recurrence (title, description, priority, task_type, recurrence_type ENUM('DAILY','WEEKLY','MONTHLY'), day_of_week TINYINT NULL, day_of_month TINYINT NULL, active, last_generated_date)`.
- `generateRecurringTasks(pool)` helper — iterates active rules, checks today against the rule, INSERTs a `tasks` row with due_date=today, updates `last_generated_date` to prevent duplicates.
- Endpoints: `GET/POST /api/task-recurrence`, `POST /api/task-recurrence/run-now` (manual trigger), `PUT/DELETE /api/task-recurrence/:id`.
- Background: `setInterval` hourly runs across all stores, next to the existing Auto-Reorder tick. Logged as `[Recurring Tasks]` in stdout.
- Dashboard: "Recurring Task Rules" card in Tasks module with a modal (Title / Description / DAILY/WEEKLY/MONTHLY / dow or dom / priority / active toggle). Table shows schedule in human form ("every Tue", "day 15 of month"), last generated date, ON/OFF.

## Cross-cutting notes
- All route-order gotchas respected — `/active` before `/:id` on vendor-visits; `/tasks` (list) before `/tasks/:id` on pogs/reset; `/run-now` before `/:id` on task-recurrence.
- BOPIS finalize was ALSO restructured in this session to wrap inventory decrements + order status update in a single transaction (fix for the partial-decrement drift bug identified earlier).
- HHT `finalizeOrder` Retrofit signature gained a `@Body FinalizeOrderRequest` param with a default, so existing call sites elsewhere keep compiling.

## How to apply
- When extending short-pick: the enum is server-side `BOPIS_SHORT_REASONS`; adding a reason means ALTER the column's enum values AND update the const.
- When extending vendor visits: `activity` block in the detail endpoint relies on `created_at BETWEEN checked_in_at AND checked_out_at` — if you add a new vendor_* table, join it in there too.
- When extending recurring tasks: if you add a new recurrence type, update both `generateRecurringTasks` dispatch and the POST/PUT validation blocks.
- `generateRecurringTasks` always uses the server's local time; if stores live in different TZs this becomes load-bearing.
