const express = require('express');
const path = require('path');
const session = require('express-session');
const bcrypt = require('bcrypt');
const { enterprisePool, initEnterpriseDatabase, getStorePool } = require('./database');

const app = express();
const PORT = 3000;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use(session({
    secret: 'storenet_district_key_999',
    resave: false,
    saveUninitialized: false,
    cookie: { secure: false }
}));

app.use(express.static(__dirname));

const storeContext = async (req, res, next) => {
    if (!req.path.startsWith('/api/') || req.path === '/api/stores' || req.path === '/api/login' || req.path.startsWith('/api/inventory/master') || req.path.startsWith('/api/inventory/event') || req.path.startsWith('/api/pogs')) return next();
    const storeId = req.headers['x-store-id'] || req.session.currentStoreId;
    if (!storeId) return res.status(400).json({ success: false, message: 'No Store ID provided.' });
    try {
        req.pool = await getStorePool(storeId);
        req.storeId = storeId;
        next();
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
};

app.use(storeContext);

// --- GLOBAL ENTERPRISE APIS ---

app.get('/api/stores', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query('SELECT id, name, ip_address, status FROM stores');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: 'Enterprise DB Error' }); }
});

app.post('/api/login', async (req, res) => {
    const { username, password } = req.body;
    try {
        const [rows] = await enterprisePool().query('SELECT * FROM global_users WHERE eid = ? OR username = ?', [username, username]);
        const user = rows[0];
        if (!user) return res.status(401).json({ success: false, message: 'Invalid Credentials' });
        const match = await bcrypt.compare(password, user.password);
        if (match) {
            req.session.userId = user.id;
            req.session.eid = user.eid;
            req.session.role = user.role;
            return res.json({ success: true });
        }
        res.status(401).json({ success: false, message: 'Invalid Credentials' });
    } catch (err) { res.status(500).json({ success: false, message: 'Database error' }); }
});

app.get('/logout', (req, res) => { req.session.destroy(); res.redirect('/'); });

// --- MASTER INVENTORY APIS (CRUD) ---

app.get('/api/inventory/master', async (req, res) => {
    const [rows] = await enterprisePool().query('SELECT * FROM master_inventory');
    res.json(rows);
});

app.post('/api/inventory/master/add', async (req, res) => {
    const { sku, upc, name, department, std_price } = req.body;
    try {
        await enterprisePool().query('INSERT INTO master_inventory (sku, upc, name, department, std_price) VALUES (?, ?, ?, ?, ?)', [sku, upc || null, name, department, std_price || 0]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/master/update', async (req, res) => {
    const { oldSku, newSku, upc, name, department, std_price } = req.body;
    try {
        await enterprisePool().query('UPDATE master_inventory SET sku = ?, upc = ?, name = ?, department = ?, std_price = ? WHERE sku = ?', [newSku, upc || null, name, department, std_price || 0, oldSku]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/master/delete', async (req, res) => {
    const { sku } = req.body;
    try {
        await enterprisePool().query('DELETE FROM master_inventory WHERE sku = ?', [sku]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/master/push', async (req, res) => {
    const { storeIds } = req.body;
    try {
        const [items] = await enterprisePool().query('SELECT * FROM master_inventory');
        for (const storeId of storeIds) {
            const pool = await getStorePool(storeId);
            
            await pool.query(`CREATE TABLE IF NOT EXISTS inventory (
                sku VARCHAR(50) PRIMARY KEY,
                upc VARCHAR(50) UNIQUE,
                name VARCHAR(100),
                department VARCHAR(50),
                price DECIMAL(10,2),
                quantity INT DEFAULT 0
            )`);
            
            try {
                await pool.query('ALTER TABLE inventory ADD COLUMN upc VARCHAR(50) UNIQUE AFTER sku');
            } catch (e) { /* Column already exists */ }

            for (const item of items) {
                await pool.query('INSERT INTO inventory (sku, upc, name, department, price) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE upc = VALUES(upc), price = VALUES(price), name = VALUES(name), department = VALUES(department)', 
                    [item.sku, item.upc, item.name, item.department, item.std_price]);
            }
            await enterprisePool().query('INSERT INTO push_logs (store_id, event_id) VALUES (?, NULL)', [storeId]);
        }
        res.json({ success: true, message: `Master inventory synced to ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

const { exec } = require('child_process');

// --- LOCAL STORE MANAGEMENT (EMPLOYEES & LOGS) ---
app.post('/api/print_sticker', (req, res) => {
    const { name, sku, upc } = req.body;
    if (!sku) return res.status(400).json({ success: false, message: 'SKU is required' });

    // Path to the Python script
    const scriptPath = path.join(__dirname, '../RecieptApp/print_receipt.py');
    const safeName = (name || '').replace(/"/g, '\\"');
    const safeSku = (sku || '').replace(/"/g, '\\"');
    const safeUpc = (upc || '').replace(/"/g, '\\"');

    const command = `python3 "${scriptPath}" --print-sticker --name "${safeName}" --sku "${safeSku}" --upc "${safeUpc}" || python "${scriptPath}" --print-sticker --name "${safeName}" --sku "${safeSku}" --upc "${safeUpc}"`;

    exec(command, (error, stdout, stderr) => {
        if (error) {
            console.error(`Error printing sticker: ${error.message}`);
            return res.status(500).json({ success: false, message: error.message });
        }
        res.json({ success: true, message: 'Sticker print job sent' });
    });
});

app.post('/api/print_shelf_label', (req, res) => {
    const { brand, name, variant, size, upc, price, unit_price_unit, pog_date, location, faces, taxable } = req.body;
    if (!upc) return res.status(400).json({ success: false, message: 'UPC is required' });

    const scriptPath = path.join(__dirname, '../RecieptApp/print_label.py');

    // Safely escape strings for CLI
    const safeBrand = (brand || '').replace(/"/g, '\\"');
    const safeName = (name || '').replace(/"/g, '\\"');
    const safeVariant = (variant || '').replace(/"/g, '\\"');
    const safeSize = (size || '').replace(/"/g, '\\"');
    const safeUpc = (upc || '').replace(/"/g, '\\"');
    const safePrice = price || 0.00;
    const safeUnitPrice = (unit_price_unit || 'per each').replace(/"/g, '\\"');
    const safePogDate = (pog_date || 'N/A').replace(/"/g, '\\"');
    const safeLocation = (location || 'N/A').replace(/"/g, '\\"');
    const safeFaces = (faces || 'F1').replace(/"/g, '\\"');

    let args = `"${scriptPath}" --brand "${safeBrand}" --name "${safeName}" --variant "${safeVariant}" --size "${safeSize}" --upc "${safeUpc}" --price ${safePrice} --unit-price "${safeUnitPrice}" --pog-date "${safePogDate}" --location "${safeLocation}" --faces "${safeFaces}"`;

    // Only pass the flag if it is strictly true
    if (taxable === true || taxable === "true" || taxable === 1) {
        args += ` --taxable`;
    }

    const command = `python3 ${args} || python ${args}`;

    exec(command, (error, stdout, stderr) => {
        if (error) {
            console.error(`Error printing shelf label: ${error.message}`);
            return res.status(500).json({ success: false, message: error.message });
        }
        res.json({ success: true, message: 'Shelf label print job sent' });
    });
});

app.get('/api/employees/local', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT eid, name, role FROM users');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/employees/local/auth', async (req, res) => {
    const { eid, pin } = req.body;
    try {
        const [rows] = await req.pool.query('SELECT name, role FROM users WHERE eid = ? AND pin = ?', [eid, pin]);
        if (rows.length > 0) {
            res.json({ success: true, user: rows[0] });
        } else {
            res.json({ success: false, message: 'Invalid credentials' });
        }
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/employees/local/update', async (req, res) => {
    const { eid, name, pin, role } = req.body;
    try {
        if (pin) {
            await req.pool.query('INSERT INTO users (eid, name, pin, role) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), pin = VALUES(pin), role = VALUES(role)', [eid, name, pin, role]);
        } else {
            await req.pool.query('UPDATE users SET name = ?, role = ? WHERE eid = ?', [name, role, eid]);
        }
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/employees/local/delete', async (req, res) => {
    const { eid } = req.body;
    try {
        await req.pool.query('DELETE FROM users WHERE eid = ?', [eid]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/logs/local', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM transaction_logs ORDER BY timestamp DESC LIMIT 200');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- LOCAL STORE INVENTORY APIS ---

app.get('/api/inventory/local', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM inventory');
        const mappedRows = rows.map(r => ({ ...r, taxable: !!r.taxable }));
        res.json(mappedRows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/inventory/local/:upcOrSku', async (req, res) => {
    const { upcOrSku } = req.params;
    try {
        let [rows] = await req.pool.query('SELECT * FROM inventory WHERE sku = ? OR upc = ?', [upcOrSku, upcOrSku]);
        
        // Fallback for 12-digit UPCs to match 11-digit database records
        if (rows.length === 0 && upcOrSku.length === 12) {
            const shortUpc = upcOrSku.substring(0, 11);
            [rows] = await req.pool.query('SELECT * FROM inventory WHERE sku = ? OR upc = ?', [shortUpc, shortUpc]);
        }

        if (rows.length > 0) {
            const item = rows[0];
            item.taxable = !!item.taxable;
            res.json({ success: true, item: item });
        } else {
            res.json({ success: false, message: 'Item not found' });
        }
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/local/update', async (req, res) => {
    const { oldSku, newSku, name, department, price, quantity } = req.body;
    try {
        await req.pool.query('UPDATE inventory SET sku = ?, name = ?, department = ?, price = ?, quantity = ? WHERE sku = ?', [newSku, name, department, price, quantity, oldSku]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/local/add', async (req, res) => {
    const { sku, upc, name, department, price, quantity } = req.body;
    try {
        await req.pool.query('INSERT INTO inventory (sku, upc, name, department, price, quantity) VALUES (?, ?, ?, ?, ?, ?)', [sku, upc || null, name, department, price || 0, quantity || 0]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/local/clear', async (req, res) => {
    try {
        await req.pool.query('DELETE FROM inventory');
        res.json({ success: true, message: 'Store inventory cleared.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/local/sync_to_master', async (req, res) => {
    try {
        const [localItems] = await req.pool.query('SELECT * FROM inventory');
        for (const item of localItems) {
            await enterprisePool().query(
                'INSERT INTO master_inventory (sku, upc, name, department, std_price) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE upc = VALUES(upc), name = VALUES(name), department = VALUES(department), std_price = VALUES(std_price)',
                [item.sku, item.upc, item.name, item.department, item.price]
            );
        }
        res.json({ success: true, message: `Synced ${localItems.length} items to Master Inventory.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- PLANOGRAM MAINTENANCE APIS ---

app.get('/api/pogs', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query('SELECT * FROM planograms ORDER BY created_at DESC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/pogs', async (req, res) => {
    const { pog_id, name, dimensions, suffix } = req.body;
    try {
        await enterprisePool().query('INSERT INTO planograms (pog_id, name, dimensions, suffix) VALUES (?, ?, ?, ?)', [pog_id, name, dimensions, suffix]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/pogs/:id', async (req, res) => {
    const { id } = req.params;
    try {
        await enterprisePool().query('DELETE FROM planograms WHERE id = ?', [id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/pogs/:id/items', async (req, res) => {
    const { id } = req.params;
    try {
        const query = `
            SELECT pi.id, pi.sku, pi.section, pi.shelf, pi.faces, pi.position, m.name, m.upc 
            FROM planogram_items pi 
            JOIN master_inventory m ON pi.sku = m.sku 
            WHERE pi.planogram_id = ?
            ORDER BY pi.section, pi.shelf, pi.position
        `;
        const [rows] = await enterprisePool().query(query, [id]);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/pogs/:id/items', async (req, res) => {
    const planogram_id = req.params.id;
    const { sku, section, shelf, faces, position } = req.body;
    try {
        // Check if item already exists on this specific POG, Section, and Shelf
        const [existing] = await enterprisePool().query('SELECT id FROM planogram_items WHERE planogram_id = ? AND sku = ? AND section = ? AND shelf = ?', [planogram_id, sku, section, shelf]);
        if (existing.length > 0) {
            await enterprisePool().query('UPDATE planogram_items SET faces = ?, position = ? WHERE id = ?', [faces, position || 1, existing[0].id]);
        } else {
            await enterprisePool().query('INSERT INTO planogram_items (planogram_id, sku, section, shelf, faces, position) VALUES (?, ?, ?, ?, ?, ?)', [planogram_id, sku, section, shelf, faces, position || 1]);
        }
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/pogs/:id/items/:itemId', async (req, res) => {
    const { itemId } = req.params;
    try {
        await enterprisePool().query('DELETE FROM planogram_items WHERE id = ?', [itemId]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/pogs/push', async (req, res) => {
    const { pog_id, storeIds } = req.body;
    try {
        const [pogs] = await enterprisePool().query('SELECT * FROM planograms WHERE id = ?', [pog_id]);
        if (pogs.length === 0) return res.status(404).json({ success: false, message: 'POG not found' });
        
        const pog = pogs[0];
        const pogInfoString = `${pog.pog_id} ${pog.name} ${pog.dimensions} ${pog.suffix}`;
        
        const [items] = await enterprisePool().query('SELECT * FROM planogram_items WHERE planogram_id = ?', [pog_id]);
        const currentDate = new Date();
        const formattedDate = `${String(currentDate.getMonth() + 1).padStart(2, '0')}/${String(currentDate.getFullYear()).slice(-2)}`; // e.g., "03/26"
        
        for (const storeId of storeIds) {
            const pool = await getStorePool(storeId);
            
            // Ensure local table has POG columns
            const cols = [
                "ALTER TABLE inventory ADD COLUMN location VARCHAR(50)",
                "ALTER TABLE inventory ADD COLUMN faces VARCHAR(10) DEFAULT 'F1'",
                "ALTER TABLE inventory ADD COLUMN pog_date VARCHAR(20)",
                "ALTER TABLE inventory ADD COLUMN pog_info VARCHAR(255)",
                "ALTER TABLE inventory ADD COLUMN position INT DEFAULT 1"
            ];
            for (const col of cols) {
                try { await pool.query(col); } catch (e) {} // Ignore if already exists
            }

            for (const item of items) {
                const loc = `${item.section}-${item.shelf}`;
                // We do an UPDATE. If item doesn't exist locally, it's skipped. They need to sync inventory first if new.
                await pool.query('UPDATE inventory SET location = ?, faces = ?, pog_date = ?, pog_info = ?, position = ? WHERE sku = ?', [loc, item.faces, formattedDate, pogInfoString, item.position, item.sku]);
            }
        }
        res.json({ success: true, message: `Planogram pushed to ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- PRICING EVENT APIS ---

app.get('/api/inventory/events', async (req, res) => {
    const [rows] = await enterprisePool().query('SELECT * FROM pricing_events ORDER BY created_at DESC');
    res.json(rows);
});

app.post('/api/inventory/event/create', async (req, res) => {
    const { name, type, description, items } = req.body;
    const conn = await enterprisePool().getConnection();
    try {
        await conn.beginTransaction();
        const [result] = await conn.query('INSERT INTO pricing_events (name, type, description) VALUES (?, ?, ?)', [name, type, description]);
        const eventId = result.insertId;
        for (const item of items) {
            await conn.query('INSERT INTO event_items (event_id, sku, price) VALUES (?, ?, ?)', [eventId, item.sku, item.price]);
        }
        await conn.commit();
        res.json({ success: true, eventId });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally { conn.release(); }
});

app.post('/api/inventory/event/push', async (req, res) => {
    const { eventId, storeIds } = req.body;
    try {
        const [items] = await enterprisePool().query('SELECT * FROM event_items WHERE event_id = ?', [eventId]);
        for (const storeId of storeIds) {
            const pool = await getStorePool(storeId);
            for (const item of items) {
                await pool.query('UPDATE inventory SET price = ? WHERE sku = ?', [item.price, item.sku]);
                await pool.query('INSERT INTO price_changes (sku, old_price, new_price, date, status) VALUES (?, 0, ?, CURDATE(), "Complete")', [item.sku, item.price]);
            }
            await enterprisePool().query('INSERT INTO push_logs (store_id, event_id) VALUES (?, ?)', [storeId, eventId]);
        }
        res.json({ success: true, message: `Event pushed to ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- UTILITY ---
(async () => {
    try {
        await initEnterpriseDatabase();
        console.log("District Control Center - Utility Ready.");
    } catch (e) { console.error("Initialization failed:", e.message); }
})();

app.get('/dashboard', (req, res) => { if (!req.session.userId) return res.redirect('/'); res.sendFile(path.join(__dirname, 'dashboard.html')); });
app.get('*', (req, res) => res.sendFile(path.join(__dirname, 'index.html')));

app.listen(PORT, () => {
    console.log(`====================================================`);
    console.log(`   StoreNET DISTRICT CONTROL CENTER - ONLINE       `);
    console.log(`   Access it at: http://localhost:${PORT}          `);
    console.log(`====================================================`);
});
