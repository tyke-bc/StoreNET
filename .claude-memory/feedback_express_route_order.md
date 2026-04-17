---
name: Express route-order shadowing in server.js
description: Specific static routes must be registered BEFORE wildcard `:id` routes, or Express silently runs the wrong handler
type: feedback
---
In `backoffice_web/server.js`, multiple REST resources have a static sub-route and a `:id` wildcard route on the same HTTP verb (e.g. `PUT /api/vendors/master-tag` and `PUT /api/vendors/:id`). Express matches routes in registration order — if the `:id` route comes first, it captures the static segment (`master-tag`) as its `:id` parameter and the intended handler never runs. The wrong handler usually no-ops (0 affectedRows) and returns `{success:true}`, so the bug is invisible from the client and from the mysql log.

**Why:** Discovered 2026-04-16 while debugging "Tag Items to Vendors" silently failing — the `/:id` vendor-update handler at server.js:4267 was shadowing the `/master-tag` handler at :4289. The generic handler ran `UPDATE vendors SET ... WHERE id = 'master-tag'`, matched 0 rows, returned 200.

**How to apply:**
- When adding any `app.<verb>('/api/resource/:id', ...)` route, audit for sibling static routes on the same prefix and verb — move specifics ABOVE the wildcard.
- When debugging a 200 response that isn't persisting, the first thing to check is whether another route with `:id` or `:anything` was registered earlier on that prefix.
- Known prefixes in this codebase with both static + wildcard routes: `/api/vendors/*`, `/api/vendor-orders/*` (has `/resolve/:code`), `/api/vendor-returns/*`, `/api/prp/batches/*`. Check each when adding new static sub-routes.
- Add a defensive comment above any static route that sits near a `:id` sibling so future edits don't reorder them.
