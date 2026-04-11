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
                'ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 1',
                'ALTER TABLE shifts MODIFY COLUMN eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL',
                'ALTER TABLE time_punches ADD COLUMN eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT \'\' AFTER id',
                'ALTER TABLE time_punches MODIFY COLUMN eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL',
                "ALTER TABLE time_punches ADD COLUMN action ENUM('CLOCK_IN','CLOCK_OUT','BREAK_IN','BREAK_OUT') NOT NULL DEFAULT 'CLOCK_IN' AFTER eid",
                'ALTER TABLE time_punches ADD COLUMN timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP AFTER action',
                'ALTER TABLE tasks ADD COLUMN assigned_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci AFTER description',
                'ALTER TABLE tasks MODIFY COLUMN assigned_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
                "ALTER TABLE tasks ADD COLUMN priority ENUM('LOW','NORMAL','HIGH') DEFAULT 'NORMAL' AFTER due_date",
                "ALTER TABLE tasks MODIFY COLUMN status ENUM('OPEN','DONE') DEFAULT 'OPEN'",
                'ALTER TABLE tasks ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP'
            ];
            for (const sql of migs) { try { await pool.query(sql); } catch(e){} }

            // Shifts (employee schedule)
            await pool.query(`CREATE TABLE IF NOT EXISTS shifts (
                id INT AUTO_INCREMENT PRIMARY KEY,
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                shift_date DATE NOT NULL,
                start_time TIME NOT NULL,
                end_time TIME NOT NULL,
                position VARCHAR(50) DEFAULT 'SA',
                notes VARCHAR(255),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )`);

            // Promotions / coupons (managed via web, printed by POS)
            await pool.query(`CREATE TABLE IF NOT EXISTS promotions (
                id INT AUTO_INCREMENT PRIMARY KEY,
                type ENUM('WEEKLY','SATURDAY') NOT NULL,
                title VARCHAR(100) NOT NULL,
                discount DECIMAL(10,2) NOT NULL,
                minimum DECIMAL(10,2) NOT NULL,
                fine_print TEXT,
                valid_date VARCHAR(50),
                active BOOLEAN DEFAULT TRUE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )`);

            // Tasks (created via web, visible & completable on HHT)
            await pool.query(`CREATE TABLE IF NOT EXISTS tasks (
                id INT AUTO_INCREMENT PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                description TEXT,
                assigned_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                due_date DATE,
                priority ENUM('LOW','NORMAL','HIGH') DEFAULT 'NORMAL',
                status ENUM('OPEN','DONE') DEFAULT 'OPEN',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )`);

            // Time punches (written by POS, visible via web)
            await pool.query(`CREATE TABLE IF NOT EXISTS time_punches (
                id INT AUTO_INCREMENT PRIMARY KEY,
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                action ENUM('CLOCK_IN','CLOCK_OUT','BREAK_IN','BREAK_OUT') NOT NULL,
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )`);

        } catch (e) { console.error(`Table init failed for store ${storeId}:`, e.message); }

        next();
    } catch (err) { 
        console.error(`Store context error for store ${storeId}:`, err.message);
        res.status(500).json({ success: false, message: err.message }); 
    }
};

app.use(storeContext);

// --- AUTH MIDDLEWARE ---
// Protects endpoints that create, update, or delete data.
// GET (read-only) routes are left open for HHT scanner compatibility.
const requireAuth = (req, res, next) => {
    if (!req.session.userId) return res.status(401).json({ success: false, message: 'Login required.' });
    next();
};

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
    const conn = await enterprisePool().getConnection();
    try {
        await conn.beginTransaction();

        // Remove references in Pricing Events first
        await conn.query('DELETE FROM event_items WHERE sku = ?', [sku]);

        // Then delete the master inventory item
        const [result] = await conn.query('DELETE FROM master_inventory WHERE sku = ?', [sku]);

        await conn.commit();
        console.log(`[DCC] Delete result: affected rows ${result.affectedRows}`);
        res.json({ success: true });
    } catch (err) {
        await conn.rollback();
        console.error(`[DCC] Delete failed: ${err.message}`);
        res.status(500).json({ success: false, message: err.message });
    } finally {
        conn.release();
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

const { execFile } = require('child_process');
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

// Resolve python interpreter once — try python3 first, fall back to python
function runPython(scriptPath, args, callback) {
    execFile('python3', [scriptPath, ...args], (err, stdout, stderr) => {
        if (err && (err.code === 'ENOENT' || err.code === 127)) {
            execFile('python', [scriptPath, ...args], callback);
        } else {
            callback(err, stdout, stderr);
        }
    });
}

// --- LOCAL STORE MANAGEMENT (EMPLOYEES & LOGS) ---
app.post('/api/print_sticker', (req, res) => {
    const { name, sku, upc, location, faces, department, pog_info } = req.body;
    if (!sku) return res.status(400).json({ success: false, message: 'SKU is required' });

    const scriptPath = path.join(__dirname, '../RecieptApp/print_receipt.py');
    const args = [
        '--print-sticker',
        '--name', name || '',
        '--sku', String(sku),
        '--upc', String(upc || ''),
        '--location', location || 'N/A',
        '--faces', faces || 'F1',
        '--department', department || 'GENERAL',
        '--pog-info', pog_info || ''
    ];

    runPython(scriptPath, args, (error) => {
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
    const args = [
        '--brand', brand || '',
        '--name', name || '',
        '--variant', variant || '',
        '--size', size || '',
        '--upc', String(upc),
        '--price', String(price || 0),
        '--unit-price', unit_price_unit || 'per each',
        '--pog-date', pog_date || 'N/A',
        '--location', location || 'N/A',
        '--faces', faces || 'F1'
    ];
    if (taxable === true || taxable === 'true' || taxable === 1) args.push('--taxable');

    runPython(scriptPath, args, (error) => {
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

app.post('/api/employees/local/delete', requireAuth, async (req, res) => {
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

app.post('/api/inventory/local/clear', requireAuth, async (req, res) => {
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

// --- SCHEDULE APIS ---

app.get('/api/schedule/week', async (req, res) => {
    // ?date=YYYY-MM-DD  →  returns shifts + time punches for the Mon-Sun week containing that date
    try {
        const dateStr = req.query.date || new Date().toISOString().slice(0, 10);
        const d = new Date(dateStr + 'T00:00:00');
        const dayOfWeek = d.getDay();
        const diffToMon = (dayOfWeek === 0) ? -6 : 1 - dayOfWeek;
        const monday = new Date(d);
        monday.setDate(d.getDate() + diffToMon);
        const sunday = new Date(monday);
        sunday.setDate(monday.getDate() + 6);

        const monStr = monday.toISOString().slice(0, 10);
        const sunStr = sunday.toISOString().slice(0, 10);

        const [shiftRows] = await req.pool.query(
            `SELECT s.*, u.name AS employee_name FROM shifts s
             LEFT JOIN users u ON s.eid = u.eid
             WHERE s.shift_date BETWEEN ? AND ?
             ORDER BY s.shift_date, s.start_time`,
            [monStr, sunStr]
        );

        // Normalize DATE columns — MySQL2 returns them as JS Date objects, not strings.
        // Use local date parts (not toISOString/UTC) to avoid timezone-offset date shifting.
        const toDateStr = (v) => {
            if (v instanceof Date) return `${v.getFullYear()}-${String(v.getMonth()+1).padStart(2,'0')}-${String(v.getDate()).padStart(2,'0')}`;
            return String(v).slice(0, 10);
        };
        const shifts = shiftRows.map(s => ({ ...s, shift_date: toDateStr(s.shift_date) }));

        // Fetch all time punches for the week and group by eid+date
        const [punchRows] = await req.pool.query(
            `SELECT tp.eid, tp.action, tp.timestamp, u.name AS employee_name
             FROM time_punches tp
             LEFT JOIN users u ON tp.eid = u.eid
             WHERE DATE(tp.timestamp) BETWEEN ? AND ?
             ORDER BY tp.timestamp ASC`,
            [monStr, sunStr]
        );

        // Build a lookup: "eid_YYYY-MM-DD" → { eid, name, date, actions: [{action, time}] }
        // Use local date parts so late-night punches land on the correct local day.
        const punches = {};
        punchRows.forEach(p => {
            const ts = new Date(p.timestamp);
            const dateKey = `${ts.getFullYear()}-${String(ts.getMonth()+1).padStart(2,'0')}-${String(ts.getDate()).padStart(2,'0')}`;
            const key = `${p.eid}_${dateKey}`;
            if (!punches[key]) punches[key] = { eid: p.eid, name: p.employee_name, date: dateKey, actions: [] };
            punches[key].actions.push({
                action: p.action,
                time: ts.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: true })
            });
        });

        res.json({ weekStart: monStr, weekEnd: sunStr, shifts, punches });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/schedule/shift', requireAuth, async (req, res) => {
    const { eid, shift_date, start_time, end_time, position, notes } = req.body;
    if (!eid || !shift_date || !start_time || !end_time) {
        return res.status(400).json({ success: false, message: 'eid, shift_date, start_time, end_time are required.' });
    }
    try {
        const [result] = await req.pool.query(
            'INSERT INTO shifts (eid, shift_date, start_time, end_time, position, notes) VALUES (?, ?, ?, ?, ?, ?)',
            [eid, shift_date, start_time, end_time, position || 'SA', notes || null]
        );
        res.json({ success: true, id: result.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/schedule/shift/:id', requireAuth, async (req, res) => {
    const { eid, shift_date, start_time, end_time, position, notes } = req.body;
    try {
        await req.pool.query(
            'UPDATE shifts SET eid=?, shift_date=?, start_time=?, end_time=?, position=?, notes=? WHERE id=?',
            [eid, shift_date, start_time, end_time, position || 'SA', notes || null, req.params.id]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/schedule/shift/:id', requireAuth, async (req, res) => {
    try {
        await req.pool.query('DELETE FROM shifts WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- PROMOTIONS / COUPON APIS ---

app.get('/api/promotions', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM promotions ORDER BY type, id DESC');
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/promotions', requireAuth, async (req, res) => {
    const { type, title, discount, minimum, fine_print, valid_date } = req.body;
    if (!type || !title || discount == null || minimum == null) {
        return res.status(400).json({ success: false, message: 'type, title, discount, minimum are required.' });
    }
    try {
        // Deactivate any existing active promotion of the same type before adding new one
        await req.pool.query('UPDATE promotions SET active = FALSE WHERE type = ? AND active = TRUE', [type]);
        const [result] = await req.pool.query(
            'INSERT INTO promotions (type, title, discount, minimum, fine_print, valid_date, active) VALUES (?, ?, ?, ?, ?, ?, TRUE)',
            [type, title, discount, minimum, fine_print || null, valid_date || null]
        );
        res.json({ success: true, id: result.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/promotions/:id', requireAuth, async (req, res) => {
    const { title, discount, minimum, fine_print, valid_date, active } = req.body;
    try {
        await req.pool.query(
            'UPDATE promotions SET title=?, discount=?, minimum=?, fine_print=?, valid_date=?, active=? WHERE id=?',
            [title, discount, minimum, fine_print || null, valid_date || null, active ? 1 : 0, req.params.id]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/promotions/:id', requireAuth, async (req, res) => {
    try {
        await req.pool.query('DELETE FROM promotions WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- TIME PUNCHES (read-only from web) ---

app.get('/api/timepunches', async (req, res) => {
    try {
        const { eid, date } = req.query;
        let sql = `SELECT tp.*, u.name AS employee_name FROM time_punches tp
                   LEFT JOIN users u ON tp.eid = u.eid WHERE 1=1`;
        const params = [];
        if (eid) { sql += ' AND tp.eid = ?'; params.push(eid); }
        if (date) { sql += ' AND DATE(tp.timestamp) = ?'; params.push(date); }
        sql += ' ORDER BY tp.timestamp DESC LIMIT 500';
        const [rows] = await req.pool.query(sql, params);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/timepunches/:eid/:date', requireAuth, async (req, res) => {
    try {
        await req.pool.query(
            'DELETE FROM time_punches WHERE eid = ? AND DATE(timestamp) = ?',
            [req.params.eid, req.params.date]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- MARKDOWN / EVENT CHECK (bypasses storeContext via /api/inventory/event* prefix) ---

app.get('/api/inventory/event_check/:sku', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query(
            `SELECT pe.id, pe.name, pe.type, ei.price
             FROM event_items ei
             JOIN pricing_events pe ON ei.event_id = pe.id
             WHERE ei.sku = ? AND pe.status = 'ACTIVE'`,
            [req.params.sku]
        );
        res.json({ success: true, events: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- TASKS APIs ---

app.get('/api/tasks', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT t.*, u.name AS assigned_name FROM tasks t
             LEFT JOIN users u ON t.assigned_eid = u.eid
             ORDER BY FIELD(t.priority,'HIGH','NORMAL','LOW'), t.due_date ASC, t.created_at ASC`
        );
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/tasks', requireAuth, async (req, res) => {
    const { title, description, assigned_eid, due_date, priority } = req.body;
    if (!title) return res.status(400).json({ success: false, message: 'title required' });
    try {
        const [result] = await req.pool.query(
            'INSERT INTO tasks (title, description, assigned_eid, due_date, priority) VALUES (?,?,?,?,?)',
            [title, description || null, assigned_eid || null, due_date || null, priority || 'NORMAL']
        );
        res.json({ success: true, id: result.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/tasks/:id', async (req, res) => {
    const { status } = req.body;
    try {
        await req.pool.query('UPDATE tasks SET status = ? WHERE id = ?', [status, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/tasks/:id', requireAuth, async (req, res) => {
    try {
        await req.pool.query('DELETE FROM tasks WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- CYCLE COUNT APIs ---

app.post('/api/print_section_barcode', async (req, res) => {
    const { pog_id, name, dims, suffix, section } = req.body;
    if (!pog_id || !section) return res.status(400).json({ success: false, message: 'pog_id and section required' });
    try {
        const barcodeData = `CYCL_${pog_id}_${section}`;
        const payload = `{B${barcodeData}`;
        const data = Buffer.concat([
            ESC.INIT,
            ESC.CENTER,
            Buffer.from([0x1D, 0x48, 0x02]),             // HRI below
            Buffer.from([0x1D, 0x68, 0x60]),             // barcode height 96 dots
            Buffer.from([0x1D, 0x77, 0x02]),             // barcode width multiplier 2
            Buffer.from([0x1D, 0x6B, 0x49, payload.length]), // Code128
            Buffer.from(payload, 'ascii'),
            ESC.BOLD_ON,
            Buffer.from(`\nID: ${pog_id}_${name}_${dims}_${suffix}\nSEC: ${section}\n`),
            ESC.BOLD_OFF,
            ESC.FEED_3
        ]);
        await sendToPrinter(data);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/cyclecount/section/:pogId/:section', async (req, res) => {
    try {
        const { pogId, section } = req.params;
        const [pogRows] = await enterprisePool().query('SELECT * FROM planograms WHERE pog_id = ?', [pogId]);
        if (pogRows.length === 0) return res.status(404).json({ success: false, message: `POG ${pogId} not found` });
        const pog = pogRows[0];

        const [items] = await enterprisePool().query(
            `SELECT pi.sku, pi.section, pi.shelf, pi.faces, pi.position, m.name, m.upc
             FROM planogram_items pi
             JOIN master_inventory m ON pi.sku = m.sku
             WHERE pi.planogram_id = ? AND pi.section = ?
             ORDER BY pi.shelf, pi.position`,
            [pog.id, section]
        );

        const skus = items.map(i => i.sku);
        const quantities = {};
        if (skus.length > 0) {
            const [invRows] = await req.pool.query(
                `SELECT sku, quantity FROM inventory WHERE sku IN (${skus.map(() => '?').join(',')})`,
                skus
            );
            invRows.forEach(r => { quantities[r.sku] = r.quantity; });
        }

        res.json({
            success: true,
            pog_id: pog.pog_id,
            pog_name: pog.name,
            section,
            items: items.map(i => ({ ...i, quantity: quantities[i.sku] ?? 0 }))
        });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/cyclecount/submit', async (req, res) => {
    const { counts } = req.body;
    if (!Array.isArray(counts) || counts.length === 0)
        return res.status(400).json({ success: false, message: 'counts array required' });
    try {
        for (const { sku, counted_qty } of counts) {
            await req.pool.query('UPDATE inventory SET quantity = ? WHERE sku = ?', [counted_qty, sku]);
        }
        res.json({ success: true, updated: counts.length });
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
