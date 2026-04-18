---
name: HHT reference-matching redesign (2026-04-17)
description: Four HHT screens rebuilt to match the real DG UHHT reference images; includes a new refrigeration_units table
type: project
---
Four HHT screens redesigned 2026-04-17 to match photos of the real DG UHHT device (`handheld_assets/_jpg/IMG_15{40..66}.jpg` — converted from HEIC via Python Pillow + pillow-heif). All live in `handheld_app/.../ScanScreen.kt`.

## 1. Adjustments (IMG_1545-1548)
- Three tabs wired: `Damages`, `Store Use`, `Donations` (the 3rd tab was previously declared but not hooked to content).
- `AdjustmentForm` signature now takes `reasonCodes: List<Pair<String, String>>` (display label → stored code). Empty list = no reason picker (Store Use / Donations auto-imply reason from `adjustmentType`).
- Reason-code picker is a modal `AlertDialog` with radio rows (ref IMG_1547): top row "Tap to select" clears, below are the actual codes. Damages uses the DG real codes: `IN_STORE_DAMAGE | CUSTOMER_RETURN | PAST_EXPIRE_DATE | COOLER_FREEZER_OUTAGE | RECEIVED_DAMAGE`.
- Layout: label-left (120dp fixed width) / value-right rows via private `AdjLabelRow` helper.
- UPC field now renders entered text in red `#DC2626` with "Scan or Enter UPC" placeholder when empty.
- Buttons: `Print Label` (outlined, hits `printSticker` endpoint) + `SUBMIT` (filled, header color).

## 2. Store Compliance Checks (IMG_1560-1564)
- Replaced the generic `ChecklistScreen` call with a guided walk-through.
- Private `ComplianceItem` data class wraps each checklist item (name, instruction, icon).
- Intro `AlertDialog` (matches IMG_1560) shows once on entry.
- Layout: photo panel + instruction on top (currently uses Material icons as placeholders — drop real images into `res/drawable` and swap the Icon for an Image painter when ready), checklist below.
- Tapping a radio (`ComplianceRadio` helper) auto-advances `focusIdx` to the next uncompleted item.
- Submit wired to existing `submitCompliance` with `check_type = "COMPLIANCE"`, details encoded as `NAME=PASS|FAIL|SKIP,...`.
- `allChecked` gates the `Done` button.

## 3. Cooler / Freezer Safety Checks (IMG_1559)
- Replaced the generic `ChecklistScreen` with a per-fixture temperature-entry flow.
- Private `FridgeFixtureState` holds `temp, positive (sign), oos` per fixture.
- Fixture list is hardcoded (Perishables Cooler, Freezer, Freezer, Ice Cream) with sign default set by type.
- UI: sign radios `(+)` / `(−)`, temp input (orange border, centered, bold), advance arrow button that skips to next un-filled row, OOS toggle.
- Fixture table below with red highlight on the currently-focused row (matches reference).
- `Daily Safety Check (N/total)` checkbox gates `Done` alongside all-entered; submit wired to `submitCompliance` with `check_type = "COOLER_FREEZER_WALK"` (kept legacy type from call site).
- Out-of-range detection unchanged (freezer/ice cream > 0 or < -20, cooler < 32 or > 45).

## 4. Refrigeration Maintenance (IMG_1565) — NEW backend + full UI rewrite
- **Schema**: per-store table `refrigeration_units (id, unit_number VARCHAR(50), description VARCHAR(100), category VARCHAR(50), oos TINYINT, created_at)`. Created in `storeContext` init block.
- **Endpoints**: `GET /api/refrigeration/units[?category=]`, `POST /api/refrigeration/units`, `PUT /api/refrigeration/units/:id`, `DELETE /api/refrigeration/units/:id`. All use `req.pool`.
- **HHT DTOs** in `StoreNetApiService.kt`: `RefrigerationUnit`, `RefrigerationUnitsResponse`, `CreateRefrigerationUnitRequest`, `UpdateRefrigerationUnitRequest`.
- **UI flow**:
  - Editable category at top (pencil icon → dialog). Server filters the list by this category.
  - `RESPOND` link top-right — currently just a toast stub ("Open DG Respond from launcher"). Wire it up later if you want click-through to the vendor Respond flow.
  - Column headers: sort icon | OOS | Delete.
  - Each row: unit number + `Desc: ...` + OOS checkbox (blue when set) + trash icon. Tapping either fires `PUT` or `DELETE` and refreshes.
  - Bottom: `New Unit` + green plus → dialog for (unit_number, description).
- The old `REFRIGERATION TEMP LOG` temp-entry UI is gone; temperature logging now belongs to Cooler/Freezer Safety.

## Assets
- HEIC conversion: `pip install pillow-heif` then a small Python script resized + JPEG-encoded everything under 100KB so `Read` works on them. Converted JPGs live in `handheld_assets/_jpg/` (gitignore this directory if the repo cares — pure build artifact).

## Shared private helpers added
- `AdjLabelRow(label, value, valueColor)` — used by Adjustments form
- `ComplianceItem` data class — Compliance screen
- `ComplianceRadio(label, selected, color, onClick)` — reused by Compliance + Cooler/Freezer Safety
- `FridgeFixtureState` data class — Cooler/Freezer Safety

## How to apply when extending
- If you build Bucket 1 (unified `DGScreenHeader`) next, the new screens are good candidates to migrate since their orange title bar is already a self-contained Row.
- If you want real photos in Compliance Checks, stash JPEGs in `handheld_app/app/src/main/res/drawable/` and swap the `Icon(focused.icon, ...)` Call for `Image(painter = painterResource(...))`.
- If you add more Refrigeration categories (per-store freezers, cases, etc.), reuse the existing CRUD — just set a different `category` string. No schema change needed.
