---
name: POG reset workflow
description: Tag-scan-triggered planogram reset flow — implemented across server + HHT
type: project
originSessionId: a18ed300-b8a1-4807-a699-1ceafd649296
---
POG reset workflow (designed 2026-04-13, implemented since — verified 2026-04-17).

**Physical tag** (DG "Pack-10"-style reset tag) ships with reset supplies. Its barcode = `planograms.pog_id` (6-digit).

**Implementation:**

Server (`backoffice_web/server.js`):
- Schema: `tasks.task_type ENUM('GENERAL','POG_RESET')` column; child table `task_pog_items (task_id, pog_id, pog_name, pog_dimensions, pog_suffix, scanned_at, scanned_by_eid, scanned_by_name)`.
- `POST /api/pogs/reset/create` (server.js:2198) — bundles planogramIds into per-store `tasks` rows + child `task_pog_items`.
- `POST /api/pogs/reset/scan` (server.js:2248) — accepts `{pog_id, eid}` + `X-Store-ID`. Returns one of: `applied`, `completed`, `not_found`, `already_done`. On `completed`, updates `tasks.status=DONE` and fires `buildResetSignoffPDF` → `sendToLaserPrinter` (non-blocking, logs on failure).
- `POST /api/pogs/reset/reprint/:taskId` (server.js:2345) — reprints signoff sheet.
- Push logic shared via `applyPogToStore` helper (server.js:2143) used by both direct `/api/pogs/push` and the reset scan.

HHT (`ScanScreen.kt`):
- Task list filters POG_RESET tasks (line 445, 623).
- Reset task detail dialog at line 949.
- API surface: `StoreNetApiService.kt:276` (`api/pogs/reset/scan`), `:282` (`api/pogs/reset/reprint/{taskId}`).

Dashboard: Store Tasks tab renders POG_RESET rows with attached `pog_items` children inline (server.js:3175-3190).

**Invariants (as implemented):**
- Completion is scan-only. No "mark completed" button; server rejects non-scan completion path.
- Grand reset = one task, many POGs. Signoff prints ONCE at whole-task completion.
- Task is unassigned (`assigned_eid` NULL); anyone can scan.
- Rescanning completed child → `already_done` response with scanner info; HHT offers reprint.

**Why:** Mirrors real retail reset ops — physical tag is the authoritative trigger, signoff paper is the accountability artifact.

**How to apply:** When extending (e.g. partial reset, per-POG signoff, scheduled reset), reuse `applyPogToStore`, `buildResetSignoffPDF`, and existing endpoints. Don't add a "mark done" button — the scan-only invariant is deliberate.
