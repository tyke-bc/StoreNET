---
name: Direct commits to main, no PR ceremony for StoreNET
description: User prefers committing straight to main with explanatory commit messages — PRs add overhead with no benefit on this solo testbed
type: feedback
originSessionId: 78ba5635-a3fc-4bd7-8bb1-faf6e4dd255d
---
StoreNET is a solo testbed (no other contributors, not deployed to real stores). The user explicitly opted out of the PR-per-change workflow 2026-04-17.

**Rule:** Commit straight to `main`. Don't open PRs unless the user asks.

**Why:** No reviewers means PRs give zero feedback signal. `git log --oneline` covers feature-level diffs and `git revert <sha>` covers undo — the two real PR benefits. The branch/push/merge ceremony is pure overhead at this scale.

**How to apply:**
- The harness will still drop me in a worktree branch (e.g. `claude/<adjective>-<noun>`). That's automatic, not a workflow choice. Commit there as needed but the destination is `main`.
- After committing in the worktree, cherry-pick or fast-forward onto `main` immediately (don't leave commits orphaned on the worktree branch).
- Don't push to origin without being asked — the user reviews local main before deciding to push.
- Always check `git status` in the main checkout before merging from the worktree — see `feedback_worktree_vs_main_builds.md` for why (uncommitted main work can block / silently merge wrong).
- Commit messages should be detailed enough that the PR description's role is replaced — what changed, why, and any sharp edges.
