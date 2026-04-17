---
name: POG reset workflow design (in progress)
description: Agreed design for planogram reset tasks triggered by physical tag barcode scan on HHT
type: project
originSessionId: f4fc095c-432c-4778-8cbc-2fd6d1b31a43
---
Design agreed with user on 2026-04-13 for a "planogram reset" workflow. Not yet implemented.

**Physical tag** (looks like DG "Pack-10" reset tag) ships with reset supplies. Its barcode = the `planograms.pog_id` (6-digit, e.g. `000004`).

**Flow:**
1. User creates planogram(s) in backoffice. Data sits dormant in enterprise DB — NOT pushed to store inventory yet.
2. User bundles one or more planograms into a "reset task" and pushes it to selected stores. A row lands in each selected store's `tasks` table (new type `POG_RESET`), with child rows tracking each POG in the bundle.
3. Reset task appears in backoffice "Store Tasks" tab and HHT "Tasks" dropdown menu.
4. Employee scans the tag's barcode on HHT → triggers `/api/pogs/push` for that POG into that store's inventory (location/faces/pog_info/position). Locations tab immediately reflects new positions. Child row marked scanned.
5. When all child POGs scanned → task auto-marked DONE → laser signoff sheet prints ONCE on the HP laser (`sendToLaserPrinter`) listing all POGs in the reset + blank rows for name/EID/hours + "reset hours not counted in weekly total" note (SM handles that offline via DM).
6. Re-scanning a completed POG → HHT shows "Already completed on X by Y. Reprint? Y/N" → Y triggers reprint.

**Constraints:**
- Completion is scan-only. No "Mark Completed" button. Barcode scan is mandatory.
- Grand reset = one task, many POGs. Signoff prints once at whole-task completion, not per-POG.
- Task is unassigned (`assigned_eid` NULL) — anyone can scan/complete.
- New-SKU issue is not a concern: master list push is expected to precede reset push, so all SKUs exist locally.

**Deferred (not now):**
- Per-store laser printer routing (currently one hardcoded IP, fine for testbed).
- Shelf strip printing (user's current hardware can't do it).

**Schema changes needed:**
- Extend per-store `tasks` with `task_type` (GENERAL|POG_RESET) and ref linkage.
- New child table `task_pog_items(task_id, pog_id, scanned_at, scanned_by_eid)`.

**Why:** Mirrors real retail reset ops — physical tag is the authoritative trigger, signoff paper is the accountability artifact.

**How to apply:** When the user returns to implement this, reuse `sendToLaserPrinter`, existing `/api/pogs/push`, existing `tasks` table. Don't redesign. Check current state of schema before writing migrations — memory may be stale.
