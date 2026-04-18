---
name: storeContext bypass prefixes — req.pool will be undefined
description: Certain /api/ prefixes skip the storeContext middleware; handlers under them must resolve the per-store pool manually or they crash with 500
type: feedback
---
`backoffice_web/server.js:22-32` defines a `storeContext` middleware that populates `req.pool` and `req.storeId` from the `X-Store-ID` header. It is **bypassed** for any request whose path matches one of these prefixes / exact paths:

- `/api/stores` (exact)
- `/api/login` (exact)
- `/api/inventory/master` (prefix)
- `/api/inventory/event` (prefix)
- `/api/pogs` (prefix) ← bit me 2026-04-17
- `/api/cyclecount/schedule` (prefix)
- `/api/vendors` UNLESS the path ends in `/inventory` (the `isEnterpriseVendor` check)

Any new endpoint added under one of these prefixes that needs per-store data **must** resolve the pool manually:

```js
app.get('/api/pogs/reset/tasks', async (req, res) => {
    const storeId = req.headers['x-store-id'];
    if (!storeId) return res.status(400).json({ success: false, message: 'X-Store-ID header required.' });
    try {
        const pool = await getStorePool(storeId);
        // ...use pool, not req.pool...
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});
```

Calling `req.pool.query(...)` without the guard gives a 500 whose `err.message` is `Cannot read properties of undefined (reading 'query')`. The stack trace will point into the handler, not the middleware, which makes this easy to chase in the wrong direction.

**How to apply:**
- When adding ANY new endpoint, mentally check whether its path matches a bypass prefix. The list above is authoritative — don't reason about it from scratch.
- If the handler needs per-store data AND is under a bypassed prefix: read `X-Store-ID` and call `getStorePool()`. Pattern used by existing `/api/pogs/reset/scan`, `/api/pogs/reset/create`, `/api/pogs/reset/reprint/:taskId`.
- If the handler is purely enterprise-level (e.g. reads `planograms` or `master_inventory`), use `enterprisePool()` and don't touch a store pool at all.
- When reviewing a new endpoint for a 500, check this list before assuming the bug is in the query itself.
