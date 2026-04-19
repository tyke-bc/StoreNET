---
name: Direct commits to main, no PR ceremony for StoreNET
description: User prefers committing straight to main with explanatory commit messages — PRs add overhead with no benefit on this solo testbed
type: feedback
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
StoreNET is a solo testbed (no other contributors, not deployed to real stores). The user explicitly opted out of the PR-per-change workflow 2026-04-17.

**Rule:** Work directly in the main checkout (`C:/Users/User/Documents/GitHub/StoreNET`), not the worktree. Commit straight to `main`. Don't open PRs unless the user asks.

**Why:** Field testing pulls from main — working in the worktree adds a cherry-pick step that's pure friction and an easy place to drop commits. No reviewers means PRs give zero feedback signal either. Reaffirmed 2026-04-18 ("only code in main, since it's just easier for field testing").

**How to apply:**
- Even though the harness drops me in a worktree branch (e.g. `claude/<adjective>-<noun>`), do the editing against files under `C:/Users/User/Documents/GitHub/StoreNET/` directly. `git commit` from that directory.
- Don't push to origin without being asked — the user reviews local main before deciding to push.
- If the main checkout has uncommitted changes when I arrive, stop and ask — don't blindly commit on top of the user's in-progress work.
- Commit messages should be detailed enough that the PR description's role is replaced — what changed, why, and any sharp edges.
