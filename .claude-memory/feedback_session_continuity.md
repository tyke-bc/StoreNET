---
name: Sessions don't carry context automatically — check memory and ask
description: User expects prior-session context to be available; save it proactively
type: feedback
originSessionId: f4fc095c-432c-4778-8cbc-2fd6d1b31a43
---
The user has expressed frustration that previous sessions didn't save their work to memory (e.g. "Did the previous session not save its doing to your memory?" about printer setup that clearly existed in code). They expect me to remember project details across sessions.

**Why:** User is iterating on a real long-running project (StoreNET). Re-explaining "which printer is the big paper one" every session is friction they notice.

**How to apply:**
- At the start of relevant sessions, read memory files for the project before asking structural questions.
- Before asking a question, grep/read the repo to see if the answer is already in code — "sendToLaserPrinter uses the HP at 192.168.0.233" is findable, don't make the user say it.
- When a session involves non-trivial design decisions or newly-learned facts about hardware, workflows, or product intent, save a memory before wrapping.
- Don't claim "previous sessions" did things you can't verify — if memory is empty, say so honestly rather than pretending.
