---
name: HHT hamburger should be keyholder-only
description: Only keyholders+ should see the HHT hamburger/nav menu; sales associates shouldn't swap screens
type: feedback
originSessionId: 6a7b9dd0-33ca-444c-bdcd-2b76849a486d
---
Sales associates (role `SA`) should NOT be able to open the HHT hamburger menu and switch to Adjustments, Receiving, Transfers, etc. Only keyholder-tier roles and above (SM, ASM, KH) should have access to screen-switching. Stated by user 2026-04-19.

**Why:** Real DG store responsibility boundaries — SAs are cashier-facing, the UHHT's admin functions (inventory adjustments, receiving, compliance) are keyholder duties. An SA with a loose HHT shouldn't be able to adjust stock or receive trucks.

**How to apply:**
- `MainActivity.loggedInRole` already exists (default `"SA"`) — wire it into `ScanScreen.kt` line ~428 (the menu-open click) so the button is gated.
- When role is SA, the hamburger icon should be hidden entirely (or shown disabled with a tooltip "Manager access required").
- This was NOT enforced as of 2026-04-19 — it's aspirational; flag it when building any screen that should be gated. Current state: any logged-in user can pop the menu.
- When adding new HHT screens that should be manager-only (rare — most are), enforce the gate at the nav level, not per-screen.
