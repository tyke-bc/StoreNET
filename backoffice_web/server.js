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
        const pool = await getStorePool(storeId);
        req.pool = pool;
        req.storeId = storeId;
        
        // Lazy-initialize all BOPIS / Truck / Stocking tables
        try {
            await pool.query(`CREATE TABLE IF NOT EXISTS online_orders (
                id INT AUTO_INCREMENT PRIMARY KEY,
                customer_name VARCHAR(100),
                status ENUM('PENDING', 'PICKING', 'READY', 'COMPLETED') DEFAULT 'PENDING',
                is_mock TINYINT DEFAULT 0,
                subtotal DECIMAL(10,2) DEFAULT 0.00,
                tax DECIMAL(10,2) DEFAULT 0.00,
                total DECIMAL(10,2) DEFAULT 0.00,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS online_order_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                order_id INT,
                sku VARCHAR(50),
                name VARCHAR(100),
                price DECIMAL(10,2) DEFAULT 0.00,
                qty_ordered INT,
                qty_picked INT DEFAULT 0,
                FOREIGN KEY (order_id) REFERENCES online_orders(id) ON DELETE CASCADE
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS truck_manifests (
                id INT AUTO_INCREMENT PRIMARY KEY,
                manifest_number VARCHAR(50) UNIQUE,
                bol_number VARCHAR(15) UNIQUE,
                status ENUM('PENDING', 'RECEIVING', 'COMPLETED') DEFAULT 'PENDING',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS manifest_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                manifest_id INT,
                sku VARCHAR(50),
                expected_packs INT,
                received_packs INT DEFAULT 0,
                FOREIGN KEY (manifest_id) REFERENCES truck_manifests(id) ON DELETE CASCADE
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS rolltainers (
                id INT AUTO_INCREMENT PRIMARY KEY,
                barcode VARCHAR(50) UNIQUE,
                status ENUM('PENDING', 'STOCKED') DEFAULT 'PENDING',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS rolltainer_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                rolltainer_id INT,
                sku VARCHAR(50),
                qty_boxes INT DEFAULT 0,
                FOREIGN KEY (rolltainer_id) REFERENCES rolltainers(id) ON DELETE CASCADE
            )`);
            
            // Migrations for existing tables
            const migs = [
                'ALTER TABLE online_orders ADD COLUMN is_mock TINYINT DEFAULT 0',
                'ALTER TABLE online_orders ADD COLUMN subtotal DECIMAL(10,2) DEFAULT 0.00',
                'ALTER TABLE online_orders ADD COLUMN tax DECIMAL(10,2) DEFAULT 0.00',
                'ALTER TABLE online_order_items ADD COLUMN price DECIMAL(10,2) DEFAULT 0.00',
                'ALTER TABLE inventory ADD COLUMN quantity_backstock INT DEFAULT 0',
                'ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 1'
            ];
            for (const sql of migs) { try { await pool.query(sql); } catch(e){} }
        } catch (e) { console.error(`Table init failed for store ${storeId}:`, e.message); }

        next();
    } catch (err) { 
        console.error(`Store context error for store ${storeId}:`, err.message);
        res.status(500).json({ success: false, message: err.message }); 
    }
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
    const { sku, upc, name, department, std_price, pack_size } = req.body;
    try {
        await enterprisePool().query('INSERT INTO master_inventory (sku, upc, name, department, std_price, pack_size) VALUES (?, ?, ?, ?, ?, ?)', [sku, upc || null, name, department, std_price || 0, pack_size || 1]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/master/update', async (req, res) => {
    const { oldSku, newSku, upc, name, department, std_price, pack_size } = req.body;
    console.log(`[DCC] Updating master SKU: ${oldSku} -> ${newSku}`);
    try {
        const [result] = await enterprisePool().query('UPDATE master_inventory SET sku = ?, upc = ?, name = ?, department = ?, std_price = ?, pack_size = ? WHERE sku = ?', [newSku, upc || null, name, department, std_price || 0, pack_size || 1, oldSku]);
        console.log(`[DCC] Update result: affected rows ${result.affectedRows}`);
        res.json({ success: true });
    } catch (err) { 
        console.error(`[DCC] Update failed: ${err.message}`);
        res.status(500).json({ success: false, message: err.message }); 
    }
});

app.post('/api/inventory/master/delete', async (req, res) => {
    const { sku } = req.body;
    console.log(`[DCC] Deleting master SKU: ${sku}`);
    try {
        const conn = await enterprisePool().getConnection();
        await conn.beginTransaction();

        // Remove references in Pricing Events first
        await conn.query('DELETE FROM event_items WHERE sku = ?', [sku]);

        // Then delete the master inventory item
        const [result] = await conn.query('DELETE FROM master_inventory WHERE sku = ?', [sku]);

        await conn.commit();
        conn.release();

        console.log(`[DCC] Delete result: affected rows ${result.affectedRows}`);
        res.json({ success: true });
    } catch (err) { 
        console.error(`[DCC] Delete failed: ${err.message}`);
        res.status(500).json({ success: false, message: err.message }); 
    }
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
                quantity INT DEFAULT 0,
                quantity_backstock INT DEFAULT 0,
                pack_size INT DEFAULT 1
            )`);

            await pool.query(`CREATE TABLE IF NOT EXISTS rolltainers (
                id INT AUTO_INCREMENT PRIMARY KEY,
                barcode VARCHAR(50) UNIQUE,
                status ENUM('PENDING', 'STOCKED') DEFAULT 'PENDING',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);

            await pool.query(`CREATE TABLE IF NOT EXISTS rolltainer_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                rolltainer_id INT,
                sku VARCHAR(50),
                qty_boxes INT DEFAULT 0,
                FOREIGN KEY (rolltainer_id) REFERENCES rolltainers(id) ON DELETE CASCADE
            )`);
            
            // Migrations
            try { await pool.query('ALTER TABLE inventory ADD COLUMN quantity_backstock INT DEFAULT 0'); } catch(e){}
            try { await pool.query('ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 1'); } catch(e){}

            await pool.query(`CREATE TABLE IF NOT EXISTS truck_manifests (
                id INT AUTO_INCREMENT PRIMARY KEY,
                manifest_number VARCHAR(50) UNIQUE,
                bol_number VARCHAR(15) UNIQUE,
                status ENUM('PENDING', 'RECEIVING', 'COMPLETED') DEFAULT 'PENDING',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);

            await pool.query(`CREATE TABLE IF NOT EXISTS manifest_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                manifest_id INT,
                sku VARCHAR(50),
                expected_packs INT,
                received_packs INT DEFAULT 0,
                FOREIGN KEY (manifest_id) REFERENCES truck_manifests(id) ON DELETE CASCADE
            )`);
            
            // Migrations
            try { await pool.query('ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 1'); } catch(e){}

            await pool.query(`CREATE TABLE IF NOT EXISTS online_orders (
                id INT AUTO_INCREMENT PRIMARY KEY,
                customer_name VARCHAR(100),
                status ENUM('PENDING', 'PICKING', 'READY', 'COMPLETED') DEFAULT 'PENDING',
                total DECIMAL(10,2),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);

            await pool.query(`CREATE TABLE IF NOT EXISTS online_order_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                order_id INT,
                sku VARCHAR(50),
                name VARCHAR(100),
                qty_ordered INT,
                qty_picked INT DEFAULT 0,
                FOREIGN KEY (order_id) REFERENCES online_orders(id) ON DELETE CASCADE
            )`);
            
            try {
                await pool.query('ALTER TABLE inventory ADD COLUMN upc VARCHAR(50) UNIQUE AFTER sku');
            } catch (e) { /* Column already exists */ }
            
            try {
                await pool.query('ALTER TABLE inventory ADD COLUMN reg_price DECIMAL(10,2) DEFAULT NULL');
            } catch (e) { /* Ignore if exists */ }
            
            try {
                await pool.query('ALTER TABLE inventory ADD COLUMN sale_id VARCHAR(50) DEFAULT NULL');
            } catch (e) { /* Ignore if exists */ }

            for (const item of items) {
                await pool.query('INSERT INTO inventory (sku, upc, name, department, price, pack_size) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE upc = VALUES(upc), price = VALUES(price), name = VALUES(name), department = VALUES(department), pack_size = VALUES(pack_size)', 
                    [item.sku, item.upc, item.name, item.department, item.std_price, item.pack_size]);
            }
            await enterprisePool().query('INSERT INTO push_logs (store_id, event_id) VALUES (?, NULL)', [storeId]);
        }
        res.json({ success: true, message: `Master inventory synced to ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

const { exec } = require('child_process');
const net = require('net');

// --- PRINTER UTILITY (Ported from dgPOS) ---
const PRINTER_CONFIG = { IP: '192.168.0.179', PORT: 9100 };
const ESC = {
    INIT: Buffer.from([0x1b, 0x40]),
    CENTER: Buffer.from([0x1b, 0x61, 0x01]),
    LEFT: Buffer.from([0x1b, 0x61, 0x00]),
    RIGHT: Buffer.from([0x1b, 0x61, 0x02]),
    BOLD_ON: Buffer.from([0x1b, 0x45, 0x01]),
    BOLD_OFF: Buffer.from([0x1b, 0x45, 0x00]),
    FEED_3: Buffer.from([0x1b, 0x64, 0x03]),
    CUT: Buffer.from([0x1d, 0x56, 0x41, 0x03])
};

async function sendToPrinter(data) {
    return new Promise((resolve, reject) => {
        const client = new net.Socket();
        client.connect(PRINTER_CONFIG.PORT, PRINTER_CONFIG.IP, () => {
            client.write(data);
            client.end();
            resolve();
        });
        client.on('error', (err) => { client.destroy(); reject(err); });
    });
}

// --- LOCAL STORE MANAGEMENT (EMPLOYEES & LOGS) ---
app.post('/api/print_sticker', (req, res) => {
    const { name, sku, upc, location, faces, department, pog_info } = req.body;
    if (!sku) return res.status(400).json({ success: false, message: 'SKU is required' });

    // Path to the Python script
    const scriptPath = path.join(__dirname, '../RecieptApp/print_receipt.py');
    const safeName = (name || '').replace(/"/g, '\\"');
    const safeSku = (sku || '').replace(/"/g, '\\"');
    const safeUpc = (upc || '').replace(/"/g, '\\"');
    const safeLocation = (location || 'N/A').replace(/"/g, '\\"');
    const safeFaces = (faces || 'F1').replace(/"/g, '\\"');
    const safeDepartment = (department || 'GENERAL').replace(/"/g, '\\"');
    const safePogInfo = (pog_info || '').replace(/"/g, '\\"');

    const args = `"${scriptPath}" --print-sticker --name "${safeName}" --sku "${safeSku}" --upc "${safeUpc}" --location "${safeLocation}" --faces "${safeFaces}" --department "${safeDepartment}" --pog-info "${safePogInfo}"`;
    const command = `python3 ${args} || python ${args}`;

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

app.post('/api/inventory/local/update_pack_size', async (req, res) => {
    const { sku, pack_size } = req.body;
    try {
        await req.pool.query('UPDATE inventory SET pack_size = ? WHERE sku = ?', [pack_size, sku]);
        res.json({ success: true });
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

app.post('/api/inventory/local/delete', async (req, res) => {
    const { sku } = req.body;
    try {
        await req.pool.query('DELETE FROM inventory WHERE sku = ?', [sku]);
        res.json({ success: true, message: 'Item deleted.' });
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
        const [eventData] = await enterprisePool().query('SELECT * FROM pricing_events WHERE id = ?', [eventId]);
        if (eventData.length === 0) return res.status(404).json({ success: false, message: 'Event not found.' });
        const eventType = eventData[0].type; // 'SALE', 'CLEARANCE', 'MOS'

        const [items] = await enterprisePool().query('SELECT * FROM event_items WHERE event_id = ?', [eventId]);
        
        for (const storeId of storeIds) {
            const pool = await getStorePool(storeId);
            
            // Ensure reg_price column exists on the remote store's inventory table
            try {
                await pool.query('ALTER TABLE inventory ADD COLUMN reg_price DECIMAL(10,2) DEFAULT NULL');
            } catch (e) { /* Ignore if exists */ }
            
            try {
                await pool.query('ALTER TABLE inventory ADD COLUMN sale_id VARCHAR(50) DEFAULT NULL');
            } catch (e) { /* Ignore if exists */ }

            for (const item of items) {
                if (eventType === 'MOS') {
                    // MOS: Set price to 0.01. Wipe out reg_price so it doesn't revert.
                    await pool.query('UPDATE inventory SET price = 0.01, reg_price = NULL, sale_id = ? WHERE sku = ? OR upc = ?', [eventId, item.sku, item.sku]);
                    await pool.query('INSERT INTO price_changes (sku, old_price, new_price, date, status) VALUES (?, 0, 0.01, CURDATE(), "Complete")', [item.sku]);
                } else {
                    // SALE or CLEARANCE
                    // Save the current price into reg_price IF reg_price is currently null
                    await pool.query('UPDATE inventory SET reg_price = price WHERE (sku = ? OR upc = ?) AND reg_price IS NULL', [item.sku, item.sku]);
                    // Update active price to the sale price
                    await pool.query('UPDATE inventory SET price = ?, sale_id = ? WHERE sku = ? OR upc = ?', [item.price, eventId, item.sku, item.sku]);
                    await pool.query('INSERT INTO price_changes (sku, old_price, new_price, date, status) VALUES (?, 0, ?, CURDATE(), "Complete")', [item.sku, item.price]);
                }
            }
            await enterprisePool().query('INSERT INTO push_logs (store_id, event_id) VALUES (?, ?)', [storeId, eventId]);
        }
        await enterprisePool().query('UPDATE pricing_events SET status = "PUSHED" WHERE id = ?', [eventId]);
        res.json({ success: true, message: `Event pushed to ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/event/revert', async (req, res) => {
    const { eventId, storeIds } = req.body;
    try {
        const [eventData] = await enterprisePool().query('SELECT * FROM pricing_events WHERE id = ?', [eventId]);
        if (eventData.length === 0) return res.status(404).json({ success: false, message: 'Event not found.' });
        const eventType = eventData[0].type;
        
        if (eventType === 'MOS') {
            return res.status(400).json({ success: false, message: 'MOS events cannot be reverted. Items remain a penny.' });
        }

        const [items] = await enterprisePool().query('SELECT * FROM event_items WHERE event_id = ?', [eventId]);
        
        for (const storeId of storeIds) {
            const pool = await getStorePool(storeId);
            for (const item of items) {
                // Revert price back to reg_price, then nullify reg_price
                await pool.query('UPDATE inventory SET price = reg_price, reg_price = NULL WHERE (sku = ? OR upc = ?) AND reg_price IS NOT NULL', [item.sku, item.sku]);
            }
        }
        await enterprisePool().query('UPDATE pricing_events SET status = "REVERTED" WHERE id = ?', [eventId]);
        res.json({ success: true, message: `Event reverted for ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/event/delete', async (req, res) => {
    const { eventId } = req.body;
    try {
        const conn = await enterprisePool().getConnection();
        await conn.beginTransaction();
        await conn.query('DELETE FROM event_items WHERE event_id = ?', [eventId]);
        await conn.query('DELETE FROM pricing_events WHERE id = ?', [eventId]);
        await conn.commit();
        conn.release();
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/event/update', async (req, res) => {
    const { id, name, type, description, items } = req.body;
    const conn = await enterprisePool().getConnection();
    try {
        await conn.beginTransaction();
        await conn.query('UPDATE pricing_events SET name = ?, type = ?, description = ? WHERE id = ?', [name, type, description, id]);
        // Simple approach: wipe and re-insert items for the event
        await conn.query('DELETE FROM event_items WHERE event_id = ?', [id]);
        for (const item of items) {
            await conn.query('INSERT INTO event_items (event_id, sku, price) VALUES (?, ?, ?)', [id, item.sku, item.price]);
        }
        await conn.commit();
        res.json({ success: true });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally { conn.release(); }
});

app.get('/api/inventory/events/:id/items', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query('SELECT * FROM event_items WHERE event_id = ?', [req.params.id]);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- MESSAGING APIS ---

app.get('/api/messages', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query('SELECT * FROM enterprise_messages ORDER BY created_at DESC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/messages/send', async (req, res) => {
    const { subject, body, target_type, target_id } = req.body;
    const sender_eid = req.session.eid || 'SYSTEM';
    try {
        await enterprisePool().query('INSERT INTO enterprise_messages (sender_eid, subject, body, target_type, target_id) VALUES (?, ?, ?, ?, ?)', 
            [sender_eid, subject, body, target_type, target_id]);
        res.json({ success: true, message: 'Message dispatched.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/messages/delete', async (req, res) => {
    const { id } = req.body;
    try {
        await enterprisePool().query('DELETE FROM enterprise_messages WHERE id = ?', [id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- COMPATIBILITY REDIRECTS (LEGACY HHT SUPPORT) ---
app.get('/api/orders/pending', (req, res) => res.redirect(307, '/api/bopis/pending'));
app.get('/api/orders/:id', (req, res) => res.redirect(307, `/api/bopis/order/${req.params.id}`));
app.post('/api/orders/pick/:id', (req, res) => res.redirect(307, `/api/bopis/pick/${req.params.id}`));
app.post('/api/orders/:id/pick', (req, res) => res.redirect(307, `/api/bopis/pick/${req.params.id}`));
app.post('/api/orders/:id/finalize', (req, res) => res.redirect(307, `/api/bopis/finalize/${req.params.id}`));

// --- ONLINE ORDER & PICKING APIS (BOPIS) ---

// Get ALL Online Orders (DCC Dashboard)
app.get('/api/bopis/all', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM online_orders ORDER BY created_at DESC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/bopis/pending', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM online_orders WHERE status IN ("PENDING", "PICKING") ORDER BY created_at ASC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/bopis/order/:id', async (req, res) => {
    try {
        const [order] = await req.pool.query('SELECT * FROM online_orders WHERE id = ?', [req.params.id]);
        const [items] = await req.pool.query('SELECT * FROM online_order_items WHERE order_id = ?', [req.params.id]);
        res.json({ success: true, order: order[0], items: items });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Delete an Order (DCC Only)
app.delete('/api/bopis/order/:id', async (req, res) => {
    try {
        await req.pool.query('DELETE FROM online_orders WHERE id = ?', [req.params.id]);
        res.json({ success: true, message: 'Order deleted.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Create a Custom Online Order
app.post('/api/bopis/create', async (req, res) => {
    const { customer_name, items, is_mock } = req.body;
    if (!items || items.length === 0) return res.status(400).json({ success: false, message: 'No items provided.' });

    try {
        const [reslt] = await req.pool.query('INSERT INTO online_orders (customer_name, is_mock) VALUES (?, ?)', [customer_name || "Custom Order", is_mock ? 1 : 0]);
        const orderId = reslt.insertId;
        
        for (const itm of items) {
            const [inv] = await req.pool.query('SELECT name, price FROM inventory WHERE sku = ? OR upc = ?', [itm.sku, itm.sku]);
            if (inv.length > 0) {
                await req.pool.query(
                    'INSERT INTO online_order_items (order_id, sku, name, qty_ordered, price) VALUES (?, ?, ?, ?, ?)', 
                    [orderId, itm.sku, inv[0].name, itm.qty || 1, inv[0].price]
                );
            }
        }
        res.json({ success: true, orderId: orderId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/pick/:id', async (req, res) => {
    let { sku } = req.body;
    if (!sku) return res.json({ success: false, message: 'No SKU provided.' });
    sku = sku.trim();

    try {
        if (sku.length === 18 && sku.startsWith("0000") && sku.endsWith("000")) {
            sku = sku.substring(4, 15);
        }

        const [inv] = await req.pool.query('SELECT sku, price FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        let itemSku = null;
        let currentPrice = 0.00;

        if (inv.length === 0) {
            const [invFallback] = await req.pool.query('SELECT sku, price FROM inventory WHERE upc = ?', [sku.slice(0, -1)]);
            if (invFallback.length > 0) {
                itemSku = invFallback[0].sku;
                currentPrice = invFallback[0].price;
            } else {
                return res.json({ success: false, message: `Item [${sku}] not in local inventory.` });
            }
        } else {
            itemSku = inv[0].sku;
            currentPrice = inv[0].price;
        }

        const [result] = await req.pool.query(
            'UPDATE online_order_items SET qty_picked = qty_picked + 1, price = ? WHERE order_id = ? AND sku = ? AND qty_picked < qty_ordered', 
            [currentPrice, req.params.id, itemSku]
        );

        if (result.affectedRows === 0) {
            const [inOrder] = await req.pool.query('SELECT qty_picked, qty_ordered FROM online_order_items WHERE order_id = ? AND sku = ?', [req.params.id, itemSku]);
            if (inOrder.length === 0) {
                return res.json({ success: false, message: 'Item not in this order.' });
            } else {
                return res.json({ success: false, message: 'Item already fully picked.' });
            }
        }

        await req.pool.query('UPDATE online_orders SET status = "PICKING" WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- TRUCK RECEIVING APIS ---

app.get('/api/bopis/manifests', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM truck_manifests ORDER BY created_at DESC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/bopis/manifests/:id', async (req, res) => {
    try {
        const [manifest] = await req.pool.query('SELECT * FROM truck_manifests WHERE id = ?', [req.params.id]);
        const [items] = await req.pool.query(`
            SELECT mi.*, i.name, i.pack_size 
            FROM manifest_items mi 
            JOIN inventory i ON mi.sku = i.sku 
            WHERE mi.manifest_id = ?
        `, [req.params.id]);
        res.json({ success: true, manifest: manifest[0], items: items });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/manifests/create', async (req, res) => {
    const { manifest_number, bol_number, items } = req.body;
    if (!items || items.length === 0) return res.status(400).json({ success: false, message: 'No items provided.' });

    try {
        const [reslt] = await req.pool.query('INSERT INTO truck_manifests (manifest_number, bol_number) VALUES (?, ?)', [manifest_number || `TRK-${Date.now()}`, bol_number || null]);
        const manifestId = reslt.insertId;

        for (const itm of items) {
            await req.pool.query(
                'INSERT INTO manifest_items (manifest_id, sku, expected_packs) VALUES (?, ?, ?)', 
                [manifestId, itm.sku, itm.qty || 1]
            );
        }
        res.json({ success: true, manifestId: manifestId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/manifest/master-receive', async (req, res) => {
    const { bol_number, manifest_id } = req.body;
    try {
        let manifests = [];
        if (manifest_id) {
            [manifests] = await req.pool.query('SELECT id, status FROM truck_manifests WHERE id = ?', [manifest_id]);
        } else if (bol_number) {
            [manifests] = await req.pool.query('SELECT id, status FROM truck_manifests WHERE bol_number = ?', [bol_number]);
        } else {
            return res.json({ success: false, message: 'Provide BOL or Manifest ID.' });
        }
        
        if (manifests.length === 0) return res.json({ success: false, message: 'Manifest not found.' });
        if (manifests[0].status === 'COMPLETED') return res.json({ success: false, message: 'Manifest already received.' });

        const manifestId = manifests[0].id;
        const [items] = await req.pool.query('SELECT id, sku, expected_packs FROM manifest_items WHERE manifest_id = ?', [manifestId]);

        for (const item of items) {
            const [inv] = await req.pool.query('SELECT pack_size FROM inventory WHERE sku = ?', [item.sku]);
            const packSize = inv.length > 0 ? (inv[0].pack_size || 1) : 1;
            const units = item.expected_packs * packSize;
            await req.pool.query('UPDATE inventory SET quantity_backstock = quantity_backstock + ? WHERE sku = ?', [units, item.sku]);
            await req.pool.query('UPDATE manifest_items SET received_packs = expected_packs WHERE id = ?', [item.id]);
        }

        await req.pool.query('UPDATE truck_manifests SET status = "COMPLETED" WHERE id = ?', [manifestId]);
        res.json({ success: true, message: `Master Receive Complete. Manifest processed.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/manifest/print/:id', async (req, res) => {
    try {
        const [manifests] = await req.pool.query('SELECT * FROM truck_manifests WHERE id = ?', [req.params.id]);
        const manifest = manifests[0];
        const [items] = await req.pool.query('SELECT mi.*, i.name, i.pack_size FROM manifest_items mi JOIN inventory i ON mi.sku = i.sku WHERE mi.manifest_id = ?', [req.params.id]);

        let receiptData = Buffer.concat([
            ESC.INIT, ESC.CENTER, ESC.BOLD_ON,
            Buffer.from("DOLLAR GENERAL DISTRICT CONTROL\n"),
            Buffer.from(`MANIFEST SUMMARY: ${manifest.manifest_number}\n`),
            Buffer.from(`BOL: ${manifest.bol_number || 'N/A'}\n\n`),
            ESC.BOLD_OFF, ESC.LEFT,
            Buffer.from(`CREATED: ${new Date(manifest.created_at).toLocaleString()}\n`),
            Buffer.from(`STATUS: ${manifest.status}\n`),
            Buffer.from("--------------------------------\n"),
            Buffer.from("PLANNED DELIVERY (BOXES):\n")
        ]);

        for (const item of items) {
            const line = `${item.name.padEnd(20).substring(0, 20)} ${item.expected_packs}bx\n`;
            receiptData = Buffer.concat([receiptData, Buffer.from(line)]);
        }

        receiptData = Buffer.concat([
            receiptData,
            Buffer.from("--------------------------------\n"),
            ESC.CENTER,
            Buffer.from("MASTER RECEIVE AUTHORIZED\n"),
            ESC.FEED_3, ESC.CUT
        ]);
        
        await sendToPrinter(receiptData);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/rolltainer/print/:id', async (req, res) => {
    try {
        const [rts] = await req.pool.query('SELECT * FROM rolltainers WHERE id = ?', [req.params.id]);
        const rt = rts[0];
        const [items] = await req.pool.query('SELECT ri.*, i.name, i.pack_size FROM rolltainer_items ri JOIN inventory i ON ri.sku = i.sku WHERE ri.rolltainer_id = ?', [req.params.id]);

        let receiptData = Buffer.concat([
            ESC.INIT, ESC.CENTER, ESC.BOLD_ON,
            Buffer.from("DOLLAR GENERAL STORENET\n"),
            Buffer.from(`ROLLTAINER: ${rt.barcode}\n\n`),
            ESC.BOLD_OFF, ESC.LEFT,
            Buffer.from(`STATUS: ${rt.status}\n`),
            Buffer.from("--------------------------------\n"),
            Buffer.from("ITEMS LOADED:\n")
        ]);

        for (const item of items) {
            const line = `${item.name.padEnd(20).substring(0, 20)} ${item.qty_boxes}bx\n`;
            receiptData = Buffer.concat([receiptData, Buffer.from(line)]);
        }

        receiptData = Buffer.concat([
            receiptData,
            Buffer.from("--------------------------------\n"),
            ESC.CENTER,
            Buffer.from("SCAN BARCODE TO STOCK TO FLOOR\n"),
            ESC.FEED_3, ESC.CUT
        ]);
        
        await sendToPrinter(receiptData);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/receive/:id', async (req, res) => {
    let { sku } = req.body;
    if (!sku) return res.json({ success: false, message: 'No SKU provided.' });
    sku = sku.trim();

    try {
        // Cleaning
        if (sku.length === 18 && sku.startsWith("0000") && sku.endsWith("000")) {
            sku = sku.substring(4, 15);
        }

        // 1. Find the item in local inventory to get pack_size
        const [inv] = await req.pool.query('SELECT sku, pack_size FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (inv.length === 0) return res.json({ success: false, message: `Item [${sku}] not in local inventory.` });

        const itemSku = inv[0].sku;
        const packSize = inv[0].pack_size || 1;

        // 2. Verify it's on the manifest
        const [manifestItem] = await req.pool.query('SELECT id FROM manifest_items WHERE manifest_id = ? AND sku = ?', [req.params.id, itemSku]);
        if (manifestItem.length === 0) return res.json({ success: false, message: 'Item not found on this manifest.' });

        // 3. Update BACKSTOCK Inventory (Stage 1)
        await req.pool.query('UPDATE inventory SET quantity_backstock = quantity_backstock + ? WHERE sku = ?', [packSize, itemSku]);

        // 4. Update Manifest Progress
        await req.pool.query('UPDATE manifest_items SET received_packs = received_packs + 1 WHERE manifest_id = ? AND sku = ?', [req.params.id, itemSku]);
        await req.pool.query('UPDATE truck_manifests SET status = "RECEIVING" WHERE id = ? AND status = "PENDING"', [req.params.id]);

        res.json({ success: true, message: `Received 1 box of ${itemSku} to Backstock (+${packSize} units).` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- STOCKING APIS (Stage 2: Backstock to Floor) ---

app.post('/api/inventory/stock/box', async (req, res) => {
    let { sku } = req.body;
    try {
        if (sku.length === 18 && sku.startsWith("0000") && sku.endsWith("000")) sku = sku.substring(4, 15);
        
        const [inv] = await req.pool.query('SELECT sku, quantity_backstock, pack_size FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (inv.length === 0) return res.json({ success: false, message: 'Item not found.' });
        
        const item = inv[0];
        const packSize = item.pack_size || 1;
        
        if (item.quantity_backstock < packSize) return res.json({ success: false, message: 'Not enough backstock to stock a full box.' });

        await req.pool.query('UPDATE inventory SET quantity_backstock = quantity_backstock - ?, quantity = quantity + ? WHERE sku = ?', [packSize, packSize, item.sku]);
        res.json({ success: true, message: `Stocked ${packSize} units to floor.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- ROLLTAINER APIS ---

app.get('/api/bopis/rolltainers', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM rolltainers ORDER BY created_at DESC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/bopis/rolltainers/:id/items', async (req, res) => {
    try {
        const [items] = await req.pool.query(`
            SELECT ri.*, i.name, i.pack_size 
            FROM rolltainer_items ri 
            JOIN inventory i ON ri.sku = i.sku 
            WHERE ri.rolltainer_id = ?
        `, [req.params.id]);
        res.json(items);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/rolltainers/create', async (req, res) => {
    const { barcode, items } = req.body;
    if (!barcode || !items || items.length === 0) return res.status(400).json({ success: false, message: 'Invalid data.' });

    try {
        const [reslt] = await req.pool.query('INSERT INTO rolltainers (barcode) VALUES (?)', [barcode]);
        const rtId = reslt.insertId;
        
        for (const itm of items) {
            await req.pool.query(
                'INSERT INTO rolltainer_items (rolltainer_id, sku, qty_boxes) VALUES (?, ?, ?)', 
                [rtId, itm.sku, itm.qty || 1]
            );
        }
        res.json({ success: true, rolltainerId: rtId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/bopis/rolltainers/:id', async (req, res) => {
    try {
        await req.pool.query('DELETE FROM rolltainers WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/stock/rolltainer', async (req, res) => {
    const { barcode } = req.body;
    try {
        const [rtRows] = await req.pool.query('SELECT id, status FROM rolltainers WHERE barcode = ?', [barcode]);
        if (rtRows.length === 0) return res.json({ success: false, message: 'Rolltainer not found.' });
        if (rtRows[0].status === 'STOCKED') return res.json({ success: false, message: 'Rolltainer already stocked.' });
        
        const rtId = rtRows[0].id;
        const [items] = await req.pool.query('SELECT sku, qty_boxes FROM rolltainer_items WHERE rolltainer_id = ?', [rtId]);
        
        for (const item of items) {
            const [inv] = await req.pool.query('SELECT pack_size FROM inventory WHERE sku = ?', [item.sku]);
            const ps = inv.length > 0 ? (inv[0].pack_size || 1) : 1;
            const units = item.qty_boxes * ps;
            await req.pool.query('UPDATE inventory SET quantity_backstock = quantity_backstock - ?, quantity = quantity + ? WHERE sku = ?', [units, units, item.sku]);
        }
        
        await req.pool.query('UPDATE rolltainers SET status = "STOCKED" WHERE id = ?', [rtId]);
        res.json({ success: true, message: 'Rolltainer stocked to floor successfully.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/finalize/:id', async (req, res) => {
    try {
        const [orderRows] = await req.pool.query('SELECT * FROM online_orders WHERE id = ?', [req.params.id]);
        if (orderRows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        const order = orderRows[0];
        const [items] = await req.pool.query('SELECT * FROM online_order_items WHERE order_id = ?', [req.params.id]);

        let subtotal = 0;
        for (const item of items) {
            subtotal += item.qty_picked * item.price;
        }
        const tax = subtotal * 0.055; 
        const total = subtotal + tax;

        if (!order.is_mock) {
            for (const item of items) {
                if (item.qty_picked > 0) {
                    await req.pool.query('UPDATE inventory SET quantity = quantity - ? WHERE sku = ?', [item.qty_picked, item.sku]);
                }
            }
        }

        await req.pool.query('UPDATE online_orders SET status = "COMPLETED", subtotal = ?, tax = ?, total = ? WHERE id = ?', [subtotal, tax, total, req.params.id]);

        try {
            const dateStr = new Date().toLocaleString();
            let receiptData = Buffer.concat([
                ESC.INIT, ESC.CENTER, ESC.BOLD_ON,
                Buffer.from("DOLLAR GENERAL STORE #14302\n"),
                Buffer.from("216 BELKNAP ST\nSUPERIOR, WI 54880\n"),
                Buffer.from(order.is_mock ? "TEST ORDER - NO INV IMPACT\n" : "ONLINE PICKUP RECEIPT\n"),
                Buffer.from("\n"),
                ESC.BOLD_OFF, ESC.LEFT,
                Buffer.from(`CUSTOMER: ${order.customer_name}\n`),
                Buffer.from(`ORDER ID: ${order.id}\n`),
                Buffer.from(`DATE: ${dateStr}\n`),
                Buffer.from("--------------------------------\n"),
                Buffer.from("ITEMS PICKED:\n")
            ]);

            for (const item of items) {
                if (item.qty_picked > 0) {
                    const priceNum = Number(item.price) || 0;
                    const itemLine = `${item.name.padEnd(20).substring(0, 20)} ${item.qty_picked}x $${priceNum.toFixed(2)}\n`;
                    receiptData = Buffer.concat([receiptData, Buffer.from(itemLine)]);
                }
            }

            receiptData = Buffer.concat([
                receiptData,
                Buffer.from("--------------------------------\n"),
                ESC.RIGHT,
                Buffer.from(`SUBTOTAL: $${subtotal.toFixed(2)}\n`),
                Buffer.from(`TAX (5.5%): $${tax.toFixed(2)}\n`),
                ESC.BOLD_ON,
                Buffer.from(`TOTAL: $${total.toFixed(2)}\n\n`),
                ESC.BOLD_OFF, ESC.CENTER,
                Buffer.from("THANK YOU FOR SHOPPING AT MYDG!\n"),
                Buffer.from(order.is_mock ? "DCC TEST ORDER\n" : "PICKED VIA STORENET HHT\n"),
                ESC.FEED_3, ESC.CUT
            ]);
            
            await sendToPrinter(receiptData);
        } catch (printErr) { console.error("Printing failed:", printErr.message); }

        res.json({ success: true, message: order.is_mock ? "Test order finalized (No inventory impact)." : "Order finalized and receipt printed.", subtotal, tax, total });
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
