---
name: Worktree builds can hide errors when main has uncommitted work
description: A clean build inside a Claude worktree does not prove main builds, when main has uncommitted changes the worktree never saw
type: feedback
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
The harness puts me in a git worktree branched off some main commit. If main has UNCOMMITTED work in its working directory (a prior session's edits never committed), the worktree is unaware of that work — its source is the older committed state.

**What this means:** running `./gradlew assembleDebug` in the worktree only validates the OLD source. It can succeed even when main's actual state (worktree commits + main's uncommitted edits, after merge/cherry-pick) won't compile.

**Why:** discovered 2026-04-17 on StoreNET HHT build. My worktree-side build said BUILD SUCCESSFUL. After committing main's pending HHT redesign and cherry-picking my deletions, Android Studio (which builds main directly) failed with three latent errors in the redesign code: missing `PrintRequest` args, misplaced `@Composable` annotation, `Icons.AutoMirrored.Filled.ArrowForward` doesn't exist. None of those touched the lines I edited.

**How to apply:**
- When entering a worktree on this repo, run `git -C <main_path> status` early. If main has uncommitted changes touching the same files I'm about to edit (or even the same module), flag it to the user before doing work in isolation.
- After cherry-picking worktree commits onto a main that had pending changes, **rerun the build against main's checkout**, not just trust the worktree's earlier success.
- "Auto-merging" output during cherry-pick is the warning sign — git silently 3-way merged because the patches didn't textually conflict, but the resulting file may have semantic issues neither side built before.
- Specific to StoreNET: HHT compiles via Gradle wrapper. Use `JAVA_HOME="C:/Program Files/Java/jdk-25"` + `ANDROID_HOME="C:/Users/User/AppData/Local/Android/Sdk"` (PATH java is the wrong version). Local `gradlew.bat` is the entry point.
