---
name: Compose BasicTextField captured-closure race
description: Pattern that drops keystrokes when onValueChange writes off a stale captured snapshot — fix is a local mirror state
type: feedback
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
Anti-pattern that bit Safety Walk temp entry 2026-04-17:

```kotlin
val focused = state.getOrNull(focusIdx) ?: return  // captured per-compose
BasicTextField(
    value = focused.temp,
    onValueChange = { v ->
        state[focusIdx] = focused.copy(temp = v)   // writes off captured `focused`
    }
)
```

When the user types fast, two `onValueChange` calls fire before the first recomposition completes. Both callbacks see the same captured `focused.temp`. Second write overwrites first. Characters disappear. Symptom: user has to dismiss/re-show the keyboard (forcing recompose) for typing to "stick."

**Fix — local mirror state, keyed on the selector that scopes it:**
```kotlin
var inputText by remember(focusIdx) { mutableStateOf(focused.temp) }
BasicTextField(
    value = inputText,
    onValueChange = { v ->
        val cleaned = sanitize(v)
        inputText = cleaned                                // local source of truth
        state[focusIdx] = state[focusIdx].copy(temp = cleaned)  // re-read state, don't reuse captured `focused`
    }
)
```

Two changes that matter together:
1. Local `inputText` mirrors what the field shows. The TextField never reads a delayed snapshot.
2. The state write reads `state[focusIdx]` fresh instead of using the captured `focused`.

**Why:** Discovered while fixing Safety Walk's temperature input — code was at `ScanScreen.kt:1859` (now corrected on main).

**How to apply:**
- Any `BasicTextField` / `TextField` whose `value` reads from a snapshotted-collection element AND whose `onValueChange` writes back to that collection is suspect. Audit when adding similar input flows.
- This is *especially* a problem when the source state is a `SnapshotStateList` of `data class` instances — `.copy()` on a captured element is the trap.
- If the field is single-fixture and not part of a focusable list, the bug is far less likely to surface (no rapid focus shifts), but the pattern still applies.
