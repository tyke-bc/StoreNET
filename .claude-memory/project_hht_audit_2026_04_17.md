---
name: HHT audit findings 2026-04-17 — visual gaps + no dead tabs
description: Survey results — no Coming Soon fallthrough on HHT or dashboard, but most pre-redesign screens still use the old color palette
type: project
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
Two surveys run 2026-04-17 against the HHT app and dashboard.

## Non-functional-tab audit: clean
Every nav drawer entry on the HHT and every left-nav module on the dashboard is fully wired. No "Coming Soon" else-branch is reachable. Only quasi-stub: the Home screen's "General" tab shows hardcoded "UNASSIGNED" / "CORE" values, but that's intentional display, not a TODO.

## Visual consistency: header palette is the gap
The four screens redesigned in `project_hht_reference_redesign.md` (Adjustments, Compliance, Cooler/Freezer Safety, Refrigeration Maintenance) match the real DG UHHT photos in `handheld_assets/_jpg/IMG_15XX.jpg`. The OLDER screens still use a mixed palette:

- Real DG UHHT uses **yellow** (#FFC107 range) for header bars brand-wide.
- Older HHT screens use either dark blue `#1E3A5F` or gray `#D6D6D6` — wrong.
- Affected: Home (Main/Sales History/Locations/General), Receiving, Counts/Recalls (Cycle Count), Transfers, Review, Nones & Tons, PRP Returns.

Other inconsistencies:
- Tab active/inactive: real device uses yellow-active / white-inactive; current is gray-on-gray.
- Form label widths vary — Adjustments uses 120dp fixed; others don't have a convention.
- Dropdowns / dialog buttons don't match DG-blue primary / gray secondary.

## How to apply
- If the user asks for visual cleanup, the unifying pass is "header bar yellow + tab styling consistent" — that single pass would close most of the gap.
- A reusable `DGScreenHeader` composable (mentioned at the bottom of `project_hht_reference_redesign.md`) is the natural vehicle for that pass.
- Do NOT touch the four redesigned screens' palette — they intentionally match a different photo region (orange icon-badge on a soft yellow gradient, not solid yellow).
- This is "next session" work — it's cosmetic, not blocking, and the user explicitly deferred it 2026-04-17.
