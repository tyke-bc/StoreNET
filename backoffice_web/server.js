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
    // Enterprise-only vendor paths: /api/vendors, /api/vendors/:id, /api/vendors/master-tag
    // Per-store vendor path: /api/vendors/:id/inventory (needs req.pool) → NOT skipped
    const isEnterpriseVendor = req.path.startsWith('/api/vendors') && !req.path.endsWith('/inventory');
    if (!req.path.startsWith('/api/') || req.path === '/api/stores' || req.path === '/api/login' || req.path.startsWith('/api/inventory/master') || req.path.startsWith('/api/inventory/event') || req.path.startsWith('/api/pogs') || req.path.startsWith('/api/cyclecount/schedule') || isEnterpriseVendor) return next();
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
            await pool.query(`CREATE TABLE IF NOT EXISTS auto_reorders (
                id INT AUTO_INCREMENT PRIMARY KEY,
                sku VARCHAR(50),
                name VARCHAR(100),
                current_qty INT,
                order_qty INT,
                pack_size INT DEFAULT 6,
                status ENUM('PENDING','ORDERED','RECEIVED','CANCELLED') DEFAULT 'PENDING',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);

            await pool.query(`CREATE TABLE IF NOT EXISTS cycle_count_logs (
                id INT AUTO_INCREMENT PRIMARY KEY,
                sku VARCHAR(50),
                system_qty INT,
                counted_qty INT,
                variance INT,
                counted_by VARCHAR(50),
                counted_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )`);

            // Migrations for existing tables
            const migs = [
                'ALTER TABLE online_orders ADD COLUMN is_mock TINYINT DEFAULT 0',
                'ALTER TABLE online_orders ADD COLUMN subtotal DECIMAL(10,2) DEFAULT 0.00',
                'ALTER TABLE online_orders ADD COLUMN tax DECIMAL(10,2) DEFAULT 0.00',
                'ALTER TABLE online_order_items ADD COLUMN price DECIMAL(10,2) DEFAULT 0.00',
                "ALTER TABLE online_order_items ADD COLUMN short_reason ENUM('OOS','DAMAGED','NOT_FOUND','SUB_OFFERED') NULL",
                'ALTER TABLE inventory ADD COLUMN quantity_backstock INT DEFAULT 0',
                'ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 6',
                'ALTER TABLE shifts MODIFY COLUMN eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL',
                'ALTER TABLE time_punches ADD COLUMN eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT \'\' AFTER id',
                'ALTER TABLE time_punches MODIFY COLUMN eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL',
                "ALTER TABLE time_punches ADD COLUMN action ENUM('CLOCK_IN','CLOCK_OUT','BREAK_IN','BREAK_OUT') NOT NULL DEFAULT 'CLOCK_IN' AFTER eid",
                'ALTER TABLE time_punches ADD COLUMN timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP AFTER action',
                'ALTER TABLE tasks ADD COLUMN assigned_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci AFTER description',
                'ALTER TABLE tasks MODIFY COLUMN assigned_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
                "ALTER TABLE tasks ADD COLUMN priority ENUM('LOW','NORMAL','HIGH') DEFAULT 'NORMAL' AFTER due_date",
                "ALTER TABLE tasks MODIFY COLUMN status ENUM('OPEN','DONE') DEFAULT 'OPEN'",
                'ALTER TABLE tasks ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP',
                "ALTER TABLE tasks ADD COLUMN task_type ENUM('GENERAL','POG_RESET') DEFAULT 'GENERAL'",
                'ALTER TABLE tasks ADD COLUMN completed_at DATETIME NULL',
                'ALTER TABLE inventory ADD COLUMN reorder_min INT DEFAULT 5',
                'ALTER TABLE inventory ADD COLUMN reorder_max INT DEFAULT 10',
                'ALTER TABLE inventory ADD COLUMN auto_reorder_enabled BOOLEAN DEFAULT TRUE',
                'ALTER TABLE inventory ADD COLUMN vendor_id INT NULL'
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

            // Compliance / maintenance / safety-walk logs — shared table, differentiated by check_type.
            await pool.query(`CREATE TABLE IF NOT EXISTS compliance_logs (
                id INT AUTO_INCREMENT PRIMARY KEY,
                check_type VARCHAR(40) NOT NULL,
                fixture_id VARCHAR(50),
                details TEXT,
                passed TINYINT DEFAULT 1,
                notes VARCHAR(500),
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_cl_type (check_type),
                INDEX idx_cl_time (created_at)
            )`);

            // Inter-store inventory transfer requests (outbound only for now — receiving is a future task).
            await pool.query(`CREATE TABLE IF NOT EXISTS inventory_transfers (
                id INT AUTO_INCREMENT PRIMARY KEY,
                direction ENUM('OUT','IN') NOT NULL,
                other_store_id INT,
                sku VARCHAR(50) NOT NULL,
                quantity INT NOT NULL,
                status ENUM('PENDING','APPROVED','SHIPPED','RECEIVED','CANCELLED') DEFAULT 'PENDING',
                notes VARCHAR(255),
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_tr_status (status)
            )`);

            // Inventory adjustments (damages, store use, shrink, expired) — scanned from the HHT.
            await pool.query(`CREATE TABLE IF NOT EXISTS inventory_adjustments (
                id INT AUTO_INCREMENT PRIMARY KEY,
                sku VARCHAR(50) NOT NULL,
                adjustment_type ENUM('DAMAGES','STORE_USE','EXPIRED','SHRINK','FOUND') NOT NULL,
                quantity INT NOT NULL,
                reason_code VARCHAR(30),
                notes VARCHAR(255),
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_adj_sku (sku),
                INDEX idx_adj_time (created_at)
            )`);

            // PRP (Product Return Process) batches — defective/recall/vendor-return items
            // bundled and shipped back to vendor. HHT opens a batch, scans items in,
            // closes it; batch is later marked shipped once the carrier picks up.
            await pool.query(`CREATE TABLE IF NOT EXISTS prp_batches (
                id INT AUTO_INCREMENT PRIMARY KEY,
                status ENUM('OPEN','CLOSED','SHIPPED','CANCELLED') DEFAULT 'OPEN',
                vendor VARCHAR(100),
                carrier VARCHAR(50),
                tracking_number VARCHAR(100),
                opened_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                closed_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                shipped_at DATETIME NULL,
                notes VARCHAR(500),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                closed_at TIMESTAMP NULL,
                INDEX idx_prp_status (status)
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS prp_batch_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                batch_id INT NOT NULL,
                sku VARCHAR(50) NOT NULL,
                quantity INT NOT NULL,
                reason_code VARCHAR(30) NOT NULL,
                notes VARCHAR(255),
                scanned_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                scanned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (batch_id) REFERENCES prp_batches(id) ON DELETE CASCADE,
                INDEX idx_prp_batch (batch_id)
            )`);

            // --- VENDOR / DSD TABLES ---
            // Vendor-specific returns (vendor takes the product out the door directly; no shipping).
            // Separate from prp_batches — different lifecycle (OPEN → CLOSED, no SHIPPED state).
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_returns (
                id INT AUTO_INCREMENT PRIMARY KEY,
                vendor_id INT NOT NULL,
                status ENUM('OPEN','CLOSED','CANCELLED') DEFAULT 'OPEN',
                rep_name VARCHAR(100),
                credit_memo_number VARCHAR(50),
                notes VARCHAR(500),
                opened_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                closed_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                closed_at TIMESTAMP NULL,
                INDEX idx_vr_vendor (vendor_id),
                INDEX idx_vr_status (status)
            )`);
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_return_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                return_id INT NOT NULL,
                sku VARCHAR(50) NOT NULL,
                quantity INT NOT NULL,
                reason_code VARCHAR(30) NOT NULL,
                notes VARCHAR(255),
                scanned_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                scanned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (return_id) REFERENCES vendor_returns(id) ON DELETE CASCADE,
                INDEX idx_vri_return (return_id)
            )`);

            // DSD order requests (scan-to-order — vendor fills on next visit)
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_orders (
                id INT AUTO_INCREMENT PRIMARY KEY,
                vendor_id INT NOT NULL,
                status ENUM('OPEN','SUBMITTED','FULFILLED','CANCELLED') DEFAULT 'OPEN',
                rep_name VARCHAR(100),
                notes VARCHAR(500),
                created_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                submitted_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                submitted_at TIMESTAMP NULL,
                INDEX idx_vo_vendor (vendor_id),
                INDEX idx_vo_status (status)
            )`);
            // Metadata columns added after the fact — SM enriches post-submit at the backoffice desk
            const voMigs = [
                'ALTER TABLE vendor_orders ADD COLUMN po_number VARCHAR(60)',
                'ALTER TABLE vendor_orders ADD COLUMN carrier VARCHAR(80)',
                'ALTER TABLE vendor_orders ADD COLUMN driver_name VARCHAR(100)',
                'ALTER TABLE vendor_orders ADD COLUMN driver_phone VARCHAR(30)',
                'ALTER TABLE vendor_orders ADD COLUMN truck_number VARCHAR(40)',
                'ALTER TABLE vendor_orders ADD COLUMN expected_delivery DATE NULL',
                'ALTER TABLE vendor_orders ADD COLUMN actual_delivery DATETIME NULL',
                'ALTER TABLE vendor_orders ADD COLUMN fulfilled_at TIMESTAMP NULL'
            ];
            for (const sql of voMigs) { try { await pool.query(sql); } catch (_) {} }
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_order_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                order_id INT NOT NULL,
                sku VARCHAR(50) NOT NULL,
                quantity_requested INT NOT NULL,
                notes VARCHAR(255),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (order_id) REFERENCES vendor_orders(id) ON DELETE CASCADE,
                INDEX idx_voi_order (order_id)
            )`);

            // Vendor deliveries (what they drop off — scanned in on HHT, increments inventory)
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_deliveries (
                id INT AUTO_INCREMENT PRIMARY KEY,
                vendor_id INT NOT NULL,
                order_id INT NULL,
                status ENUM('OPEN','COMPLETED','CANCELLED') DEFAULT 'OPEN',
                invoice_number VARCHAR(50),
                rep_name VARCHAR(100),
                notes VARCHAR(500),
                received_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                FOREIGN KEY (order_id) REFERENCES vendor_orders(id) ON DELETE SET NULL,
                INDEX idx_vd_vendor (vendor_id),
                INDEX idx_vd_status (status)
            )`);
            // Heal older tables that were created before these columns existed (CREATE TABLE IF NOT EXISTS is a no-op on an existing table)
            const vdMigs = [
                'ALTER TABLE vendor_deliveries ADD COLUMN order_id INT NULL',
                'ALTER TABLE vendor_deliveries ADD COLUMN invoice_number VARCHAR(50)',
                'ALTER TABLE vendor_deliveries ADD FOREIGN KEY (order_id) REFERENCES vendor_orders(id) ON DELETE SET NULL'
            ];
            for (const sql of vdMigs) { try { await pool.query(sql); } catch (_) {} }
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_delivery_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                delivery_id INT NOT NULL,
                sku VARCHAR(50) NOT NULL,
                quantity_received INT NOT NULL,
                scanned_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                scanned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (delivery_id) REFERENCES vendor_deliveries(id) ON DELETE CASCADE,
                INDEX idx_vdi_delivery (delivery_id)
            )`);

            // Refrigeration units — inventory of coolers/freezers/ice-cream units the store owns.
            // Populated and maintained via HHT "Refrigeration Maintenance" screen. `oos` flags a
            // unit as out-of-service. `category` groups related units (e.g. "Ice Cream" in the ref).
            await pool.query(`CREATE TABLE IF NOT EXISTS refrigeration_units (
                id INT AUTO_INCREMENT PRIMARY KEY,
                unit_number VARCHAR(50) NOT NULL,
                description VARCHAR(100),
                category VARCHAR(50),
                oos TINYINT DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_ru_category (category),
                INDEX idx_ru_oos (oos)
            )`);

            // Recurring task templates — nightly generator materializes tasks from these rules.
            // recurrence_type DAILY (runs every day), WEEKLY (uses day_of_week 0=Sun..6=Sat),
            // MONTHLY (uses day_of_month 1-31; if month has fewer days, clamps to last).
            // last_generated_date guards against double-generation when the interval ticks multiple times/day.
            await pool.query(`CREATE TABLE IF NOT EXISTS task_recurrence (
                id INT AUTO_INCREMENT PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                description TEXT,
                priority ENUM('LOW','NORMAL','HIGH') DEFAULT 'NORMAL',
                task_type ENUM('GENERAL','POG_RESET') DEFAULT 'GENERAL',
                recurrence_type ENUM('DAILY','WEEKLY','MONTHLY') NOT NULL,
                day_of_week TINYINT NULL,
                day_of_month TINYINT NULL,
                active BOOLEAN DEFAULT TRUE,
                last_generated_date DATE NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_tr_active (active)
            )`);

            // Vendor visit log — one row per vendor rep visit to the store. Links to returns/orders/deliveries
            // created in the same window via timestamp queries (no FK since the rep may do nothing).
            await pool.query(`CREATE TABLE IF NOT EXISTS vendor_visits (
                id INT AUTO_INCREMENT PRIMARY KEY,
                vendor_id INT NOT NULL,
                rep_name VARCHAR(100),
                checked_in_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                checked_out_at TIMESTAMP NULL,
                checked_in_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
                notes VARCHAR(500),
                INDEX idx_vv_vendor (vendor_id),
                INDEX idx_vv_time (checked_in_at)
            )`);

            // POG reset child rows (one per planogram in a reset task)
            await pool.query(`CREATE TABLE IF NOT EXISTS task_pog_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                task_id INT NOT NULL,
                pog_id VARCHAR(50) NOT NULL,
                pog_name VARCHAR(100),
                pog_dimensions VARCHAR(20),
                pog_suffix VARCHAR(20),
                scanned_at DATETIME NULL,
                scanned_by_eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
                scanned_by_name VARCHAR(100) NULL,
                FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                UNIQUE KEY uq_task_pog (task_id, pog_id)
            )`);

            // Time punches (written by POS, visible via web)
            await pool.query(`CREATE TABLE IF NOT EXISTS time_punches (
                id INT AUTO_INCREMENT PRIMARY KEY,
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                action ENUM('CLOCK_IN','CLOCK_OUT','BREAK_IN','BREAK_OUT') NOT NULL,
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )`);

            // ---------- TILL & CASH (Store Menu — ingested from dgPOS) ----------
            // One row per cashier till session. Opened on login to a register, closed on Z-Out
            // (normal close) or a manager force-close. expected_cash is a running total computed
            // from events; actual_cash is what the cashier counted at close. over_short is the delta.
            await pool.query(`CREATE TABLE IF NOT EXISTS till_sessions (
                id INT AUTO_INCREMENT PRIMARY KEY,
                register_id VARCHAR(50) NOT NULL,
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                eid_name VARCHAR(100),
                opened_at DATETIME NOT NULL,
                closed_at DATETIME NULL,
                starting_bank DECIMAL(10,2) DEFAULT 0.00,
                cash_sales DECIMAL(10,2) DEFAULT 0.00,
                cash_refunds DECIMAL(10,2) DEFAULT 0.00,
                pickups_total DECIMAL(10,2) DEFAULT 0.00,
                expected_cash DECIMAL(10,2) DEFAULT 0.00,
                actual_cash DECIMAL(10,2) NULL,
                over_short DECIMAL(10,2) NULL,
                status ENUM('OPEN','CLOSED','FORCE_CLOSED','HELD') DEFAULT 'OPEN',
                notes VARCHAR(500) NULL,
                INDEX idx_ts_eid (eid),
                INDEX idx_ts_register (register_id),
                INDEX idx_ts_status (status),
                INDEX idx_ts_opened (opened_at)
            )`);

            // Every money-touching event inside a till session. Types: PICKUP (drop to safe),
            // REFUND (cash refund out), VOID (voided transaction), SALE_SUMMARY (periodic cash
            // running-total from POS). Used to render the reconcile timeline + feed expected_cash.
            await pool.query(`CREATE TABLE IF NOT EXISTS till_session_events (
                id INT AUTO_INCREMENT PRIMARY KEY,
                session_id INT NULL,
                register_id VARCHAR(50) NOT NULL,
                eid VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
                event_type ENUM('PICKUP','REFUND','VOID','SALE','CLOCK_IN','CLOCK_OUT','BREAK_IN','BREAK_OUT') NOT NULL,
                amount DECIMAL(10,2) DEFAULT 0.00,
                authorized_by VARCHAR(50) NULL,
                receipt_id VARCHAR(50) NULL,
                note VARCHAR(255) NULL,
                occurred_at DATETIME NOT NULL,
                INDEX idx_tse_session (session_id),
                INDEX idx_tse_type (event_type),
                INDEX idx_tse_time (occurred_at),
                FOREIGN KEY (session_id) REFERENCES till_sessions(id) ON DELETE SET NULL
            )`);

            // Manager-initiated force-close commands. Backoffice inserts PENDING; dgPOS polls,
            // applies (clears held drawer + closes session), acks with APPLIED. Applying store
            // an actual_cash value if the manager chose to enter a count; NULL means "just close".
            await pool.query(`CREATE TABLE IF NOT EXISTS force_close_commands (
                id INT AUTO_INCREMENT PRIMARY KEY,
                session_id INT NOT NULL,
                register_id VARCHAR(50) NOT NULL,
                actual_cash DECIMAL(10,2) NULL,
                note VARCHAR(255) NULL,
                requested_by_eid VARCHAR(50) NOT NULL,
                requested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                status ENUM('PENDING','APPLIED','CANCELLED') DEFAULT 'PENDING',
                applied_at DATETIME NULL,
                INDEX idx_fcc_status (status),
                INDEX idx_fcc_register (register_id),
                FOREIGN KEY (session_id) REFERENCES till_sessions(id) ON DELETE CASCADE
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
    const q = req.query.q && req.query.q.trim();
    if (q) {
        const like = `%${q}%`;
        const [rows] = await enterprisePool().query(
            'SELECT * FROM master_inventory WHERE name LIKE ? OR sku LIKE ? OR upc LIKE ? LIMIT 60',
            [like, like, like]
        );
        return res.json(rows);
    }
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
                pack_size INT DEFAULT 6
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
            try { await pool.query('ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 6'); } catch(e){}

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
            try { await pool.query('ALTER TABLE inventory ADD COLUMN pack_size INT DEFAULT 6'); } catch(e){}

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

const { execFile, spawn } = require('child_process');
const net = require('net');
const PDFDocument = require('pdfkit');
const JsBarcode = require('jsbarcode');
const { createCanvas } = require('canvas');

// --- BARCODE CLEANING ---
// Handles Datalogic QW2400 quirks and other scanner oddities centrally.
function upcCheckDigit(eleven) {
    // UPC-A check digit: odd positions (0-indexed even) × 3, even positions × 1, sum mod 10
    const digits = eleven.split('').map(Number);
    const sum = digits.reduce((acc, d, i) => acc + d * (i % 2 === 0 ? 3 : 1), 0);
    return (10 - (sum % 10)) % 10;
}

function cleanScannedCode(raw) {
    let code = (raw || '').trim();

    // Strip AIM Code Identifiers (Datalogic QW2400 with identifiers enabled)
    // "A" prefix = UPC/EAN family, "QR" prefix = QR Code
    if (code.startsWith('QR')) code = code.substring(2);
    else if (code.startsWith('A')) code = code.substring(1);

    // Strip DG warehouse label: 18 digits, '0000' prefix, '00' suffix, 12-digit UPC at positions 4..15.
    // The '00' suffix is what keeps this from false-matching a regular 18-char numeric code.
    if (code.length === 18 && code.startsWith('0000') && code.endsWith('00') && /^\d+$/.test(code)) {
        code = code.substring(4, 16);
    }


    // If 11 digits, scanner dropped the UPC-A check digit — calculate and restore it
    if (/^\d{11}$/.test(code)) {
        code = code + upcCheckDigit(code);
    }

    return code;
}

// Resolve a scanned code to an inventory row
async function resolveScannedItem(pool, rawCode) {
    const code = cleanScannedCode(rawCode);
    const [rows] = await pool.query('SELECT * FROM inventory WHERE sku = ? OR upc = ?', [code, code]);
    return rows.length > 0 ? rows[0] : null;
}

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
    CUT: Buffer.from([0x1d, 0x56, 0x41, 0x03]),
    BARCODE: (data) => {
        const encoded = Buffer.from(data, 'ascii');
        return Buffer.concat([
            Buffer.from([0x1d, 0x68, 0x50]),
            Buffer.from([0x1d, 0x77, 0x02]),
            Buffer.from([0x1d, 0x48, 0x02]),
            Buffer.from([0x1d, 0x6b, 0x49]),
            Buffer.from([encoded.length]),
            encoded
        ]);
    }
}



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

// --- LASER PRINTER (HP DeskJet Plus 4155, USB via CUPS+ipp-usb on the host) ---
// We pipe the PDF buffer into `lp -d <queue>`. CUPS handles the driverless IPP-over-USB path.
// Change LASER_PRINTER_QUEUE if the queue is renamed in CUPS.
const LASER_PRINTER_QUEUE = 'HP4155';

async function sendToLaserPrinter(data) {
    return new Promise((resolve, reject) => {
        const p = spawn('lp', ['-d', LASER_PRINTER_QUEUE]);
        let stderr = '';
        p.stderr.on('data', chunk => { stderr += chunk.toString(); });
        p.on('error', reject);
        p.on('close', code => {
            if (code === 0) resolve();
            else reject(new Error(`lp exited ${code}${stderr ? ': ' + stderr.trim() : ''}`));
        });
        p.stdin.on('error', reject);
        p.stdin.end(data);
    });
}

function buildReceivingPDF(manifest, items, storeId, copyLabel = 'STORE COPY', includeBolBarcode = false) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);

        const date = new Date(manifest.created_at).toLocaleString();
        const totalBoxes = items.reduce((s, i) => s + i.expected_packs, 0);
        const totalReceived = items.reduce((s, i) => s + i.received_packs, 0);
        const pageW = 612 - 100; // letter width minus margins

        function drawPage(label, showBarcode) {
            // Header
            doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
            doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
            doc.moveDown(0.3);
            doc.fontSize(14).text('TRUCK DELIVERY RECEIVING REPORT', { align: 'center' });
            doc.fontSize(11).font('Helvetica').text(`-- ${label} --`, { align: 'center' });
            doc.moveDown(0.5);

            // BOL Barcode (Code 128)
            if (showBarcode && manifest.bol_number) {
                const canvas = createCanvas();
                JsBarcode(canvas, manifest.bol_number, {
                    format: 'CODE128',
                    width: 2,
                    height: 50,
                    displayValue: true,
                    fontSize: 14,
                    margin: 5
                });
                const barcodePng = canvas.toBuffer('image/png');
                const imgWidth = 250;
                const imgX = (612 - imgWidth) / 2;
                doc.image(barcodePng, imgX, doc.y, { width: imgWidth });
                doc.y += 70;
                doc.moveDown(0.3);
            }

            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.5);

            // Manifest info
            doc.fontSize(10).font('Helvetica-Bold');
            const infoY = doc.y;
            doc.text('Manifest #:', 50, infoY, { continued: true }).font('Helvetica').text(`  ${manifest.manifest_number}`);
            doc.font('Helvetica-Bold').text('BOL #:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${manifest.bol_number || 'N/A'}`);
            doc.font('Helvetica-Bold').text('Date:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${date}`);
            doc.font('Helvetica-Bold').text('Status:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${manifest.status}`);
            doc.moveDown(0.5);

            // Item table header
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.3);
            doc.fontSize(9).font('Helvetica-Bold');
            const tableTop = doc.y;
            doc.text('SKU', 50, tableTop, { width: 100 });
            doc.text('Item Name', 155, tableTop, { width: 220 });
            doc.text('Expected', 380, tableTop, { width: 60, align: 'right' });
            doc.text('Received', 450, tableTop, { width: 60, align: 'right' });
            doc.moveDown(0.2);
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.2);

            // Item rows
            doc.font('Helvetica').fontSize(8);
            const pageBottom = 732 - 40; // letter height minus bottom margin
            items.forEach(item => {
                if (doc.y > pageBottom - 15) {
                    doc.addPage();
                    doc.x = 50;
                    // Reprint table header on new page
                    doc.fontSize(9).font('Helvetica-Bold');
                    const hdrY = doc.y;
                    doc.text('SKU', 50, hdrY, { width: 100 });
                    doc.text('Item Name', 155, hdrY, { width: 220 });
                    doc.text('Expected', 380, hdrY, { width: 60, align: 'right' });
                    doc.text('Received', 450, hdrY, { width: 60, align: 'right' });
                    doc.moveDown(0.2);
                    doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
                    doc.moveDown(0.2);
                    doc.font('Helvetica').fontSize(8);
                }
                const rowY = doc.y;
                doc.text(item.sku || '', 50, rowY, { width: 100 });
                doc.text((item.name || '').substring(0, 35), 155, rowY, { width: 220 });
                doc.text(String(item.expected_packs), 380, rowY, { width: 60, align: 'right' });
                doc.text(String(item.received_packs), 450, rowY, { width: 60, align: 'right' });
                doc.moveDown(0.1);
            });

            // Totals
            doc.moveDown(0.2);
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.3);
            doc.fontSize(9).font('Helvetica-Bold');
            const totY = doc.y;
            doc.text('TOTALS:', 50, totY);
            doc.text(String(totalBoxes), 380, totY, { width: 60, align: 'right' });
            doc.text(String(totalReceived), 450, totY, { width: 60, align: 'right' });
            doc.moveDown(1);

            // Page break — damage report and signatures on page 2
            doc.addPage();
            doc.x = 50;

            // Damage report section
            doc.fontSize(12).font('Helvetica-Bold').text('DAMAGE REPORT', 50, doc.y, { width: pageW, align: 'center' });
            doc.moveDown(0.3);
            doc.fontSize(9).text(`Manifest #: ${manifest.manifest_number}     BOL #: ${manifest.bol_number || 'N/A'}     -- ${label} --`, 50, doc.y, { width: pageW, align: 'center' });
            doc.moveDown(0.5);
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.5);

            doc.fontSize(10).font('Helvetica-Bold').text('Damaged Boxes (list SKU and description):', 50);
            doc.moveDown(0.5);
            doc.font('Helvetica').fontSize(10);
            for (let i = 1; i <= 4; i++) {
                doc.text(`${i}. _____________________________________________________________________`, 50);
                doc.moveDown(0.5);
            }

            doc.moveDown(0.5);
            doc.font('Helvetica-Bold').text('Property Damage (damage to building or equipment):', 50);
            doc.moveDown(0.5);
            doc.font('Helvetica');
            for (let i = 0; i < 3; i++) {
                doc.text('________________________________________________________________________', 50);
                doc.moveDown(0.5);
            }

            doc.moveDown(0.5);
            doc.font('Helvetica-Bold').text('Personnel Injury (injuries to any persons):', 50);
            doc.moveDown(0.5);
            doc.font('Helvetica');
            for (let i = 0; i < 3; i++) {
                doc.text('________________________________________________________________________', 50);
                doc.moveDown(0.5);
            }

            // Signatures
            doc.moveDown(0.5);
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.5);
            doc.fontSize(12).font('Helvetica-Bold').text('SIGNATURES', 50, doc.y, { width: pageW, align: 'center' });
            doc.moveDown(0.7);

            doc.fontSize(10).font('Helvetica');
            doc.text('Store Manager: ___________________________________   Date: _______________', 50);
            doc.moveDown(0.7);
            doc.text('Driver:        ___________________________________   Date: _______________', 50);
            doc.moveDown(0.7);
            doc.text('Driver License / ID #: ______________________________________________________', 50);
            doc.moveDown(0.7);
            doc.text('Truck / Trailer #:    ______________________________________________________', 50);
            doc.moveDown(1);

            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.3);
            doc.fontSize(8).font('Helvetica-Bold').text('THIS DOCUMENT MUST BE RETAINED FOR 90 DAYS', 50, doc.y, { width: pageW, align: 'center' });
        }

        drawPage(copyLabel, includeBolBarcode);
        doc.end();
    });
}

// --- PLANOGRAM RESET SIGNOFF PDF ---
// One sheet per completed reset task. Lists all POGs that made up the reset,
// a blank employee signoff table, and the "hours excluded" footer.
async function buildResetSignoffPDF(pool, taskId, storeId) {
    const [taskRows] = await pool.query('SELECT * FROM tasks WHERE id = ?', [taskId]);
    if (taskRows.length === 0) throw new Error('Task not found');
    const task = taskRows[0];
    const [children] = await pool.query(
        `SELECT pog_id, pog_name, pog_dimensions, pog_suffix, scanned_at, scanned_by_eid, scanned_by_name
         FROM task_pog_items WHERE task_id = ? ORDER BY id ASC`,
        [taskId]
    );

    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);

        const pageW = 612 - 100;
        const completed = task.completed_at ? new Date(task.completed_at).toLocaleString() : new Date().toLocaleString();

        // Header
        doc.fontSize(18).font('Helvetica-Bold').text('PLANOGRAM RESET SIGNOFF', { align: 'center' });
        doc.moveDown(0.2);
        doc.fontSize(11).font('Helvetica').text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.6);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.4);

        // Task info
        doc.fontSize(11).font('Helvetica-Bold').text('Reset:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${task.title}`);
        doc.moveDown(0.15);
        doc.font('Helvetica-Bold').text('Completed:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${completed}`);
        doc.moveDown(0.15);
        doc.font('Helvetica-Bold').text('Task ID:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${task.id}`);
        if (task.description) {
            doc.moveDown(0.15);
            doc.font('Helvetica-Bold').text('Notes:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${task.description}`);
        }
        doc.moveDown(0.6);

        // POG table
        doc.fontSize(12).font('Helvetica-Bold').text('Planograms in this reset', 50);
        doc.moveDown(0.25);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.2);
        doc.fontSize(9).font('Helvetica-Bold');
        const pogHdrY = doc.y;
        doc.text('POG ID',   50,  pogHdrY, { width: 80 });
        doc.text('Name',     135, pogHdrY, { width: 180 });
        doc.text('Dims',     320, pogHdrY, { width: 60 });
        doc.text('Scanned By', 385, pogHdrY, { width: 110 });
        doc.text('Scanned At', 500, pogHdrY, { width: 62, align: 'right' });
        doc.moveDown(0.2);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.15);
        doc.font('Helvetica').fontSize(9);
        for (const c of children) {
            const rowY = doc.y;
            doc.text(c.pog_id || '',                      50,  rowY, { width: 80 });
            doc.text((c.pog_name || '').substring(0, 40), 135, rowY, { width: 180 });
            doc.text(c.pog_dimensions || '',              320, rowY, { width: 60 });
            doc.text(c.scanned_by_name || c.scanned_by_eid || '—', 385, rowY, { width: 110 });
            doc.text(c.scanned_at ? new Date(c.scanned_at).toLocaleString() : '—', 500, rowY, { width: 62, align: 'right' });
            doc.moveDown(0.15);
        }
        doc.moveDown(0.3);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.7);

        // Employee signoff table (blank rows)
        doc.fontSize(12).font('Helvetica-Bold').text('Employees who worked this reset', 50);
        doc.moveDown(0.25);
        doc.fontSize(9).font('Helvetica').text('Each person who worked on this reset must print their name, EID, hours worked, and sign.', 50);
        doc.moveDown(0.4);

        doc.fontSize(9).font('Helvetica-Bold');
        const empHdrY = doc.y;
        doc.text('Name',      50,  empHdrY, { width: 170 });
        doc.text('EID',       225, empHdrY, { width: 80 });
        doc.text('Hours',     310, empHdrY, { width: 50 });
        doc.text('Signature', 365, empHdrY, { width: 197 });
        doc.moveDown(0.2);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.35);

        doc.font('Helvetica').fontSize(11);
        for (let i = 0; i < 10; i++) {
            const rowY = doc.y;
            // Underline the whole row
            doc.moveTo(50,  rowY + 14).lineTo(220, rowY + 14).stroke();
            doc.moveTo(225, rowY + 14).lineTo(305, rowY + 14).stroke();
            doc.moveTo(310, rowY + 14).lineTo(360, rowY + 14).stroke();
            doc.moveTo(365, rowY + 14).lineTo(562, rowY + 14).stroke();
            doc.moveDown(1.1);
        }
        doc.moveDown(0.4);

        // Totals + SM signoff
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.5);
        doc.fontSize(10).font('Helvetica-Bold');
        doc.text('Total reset hours: __________', 50);
        doc.moveDown(0.8);
        doc.text('Store Manager / Lead signature: _______________________________   Date: ____________', 50);
        doc.moveDown(1);

        // Footer note
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.4);
        doc.fontSize(10).font('Helvetica-Bold').text('IMPORTANT — RESET HOURS POLICY', 50, doc.y, { width: pageW, align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(9).font('Helvetica').text(
            'These reset hours are NOT counted toward the store\'s weekly labor total. ' +
            'The Store Manager should send this sheet to the District Manager, or adjust the ' +
            'schedule hours accordingly if possible.',
            50, doc.y, { width: pageW, align: 'left' }
        );

        doc.end();
    });
}

async function printTruckReceivingReport(pool, manifestId, storeId, copyLabel = 'STORE COPY', includeBolBarcode = false) {
    const [manifests] = await pool.query('SELECT * FROM truck_manifests WHERE id = ?', [manifestId]);
    if (manifests.length === 0) throw new Error('Manifest not found');
    const manifest = manifests[0];
    const [items] = await pool.query(
        'SELECT mi.*, i.name, i.pack_size FROM manifest_items mi JOIN inventory i ON mi.sku = i.sku WHERE mi.manifest_id = ?',
        [manifestId]
    );

    const pdfBuffer = await buildReceivingPDF(manifest, items, storeId, copyLabel, includeBolBarcode);
    await sendToLaserPrinter(pdfBuffer);
}

// --- PRICE CHANGE REPORT PDF ---
function buildPriceChangeReportPDF(event, items, oldPriceMap, storeId) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);

        const pageW = 612 - 100;
        const pageBottom = 732 - 40;
        const typeColors = { SALE: '#3498db', CLEARANCE: '#e67e22', MOS: '#e74c3c' };

        // Header
        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('PRICE CHANGE REPORT', { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(11).font('Helvetica').text(`Event: ${event.name}`, { align: 'center' });
        doc.fontSize(10).fillColor(typeColors[event.type] || '#000').text(`Type: ${event.type}`, { align: 'center' });
        doc.fillColor('#000');
        doc.fontSize(9).text(`Date: ${new Date().toLocaleString()}`, { align: 'center' });
        if (event.description) doc.fontSize(8).text(event.description, { align: 'center' });
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);

        // Table header
        function drawTableHeader() {
            doc.fontSize(9).font('Helvetica-Bold');
            const hY = doc.y;
            doc.text('SKU', 50, hY, { width: 80 });
            doc.text('Item Name', 135, hY, { width: 200 });
            doc.text('Old Price', 340, hY, { width: 70, align: 'right' });
            doc.text('New Price', 415, hY, { width: 70, align: 'right' });
            doc.text('Change', 490, hY, { width: 70, align: 'right' });
            doc.moveDown(0.2);
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.2);
        }
        drawTableHeader();

        // Rows
        doc.font('Helvetica').fontSize(8);
        items.forEach(item => {
            if (doc.y > pageBottom - 15) {
                doc.addPage();
                doc.x = 50;
                drawTableHeader();
                doc.font('Helvetica').fontSize(8);
            }
            const old = oldPriceMap[item.sku] || {};
            const oldPrice = old.price != null ? parseFloat(old.price) : 0;
            const newPrice = event.type === 'MOS' ? 0.01 : parseFloat(item.price);
            const change = newPrice - oldPrice;
            const rowY = doc.y;
            doc.text(item.sku || '', 50, rowY, { width: 80 });
            doc.text((old.name || item.sku || '').substring(0, 30), 135, rowY, { width: 200 });
            doc.text(`$${oldPrice.toFixed(2)}`, 340, rowY, { width: 70, align: 'right' });
            doc.text(`$${newPrice.toFixed(2)}`, 415, rowY, { width: 70, align: 'right' });
            doc.fillColor(change < 0 ? '#e74c3c' : '#27ae60').text(`${change >= 0 ? '+' : ''}$${change.toFixed(2)}`, 490, rowY, { width: 70, align: 'right' });
            doc.fillColor('#000');
            doc.moveDown(0.1);
        });

        // Footer
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);
        doc.fontSize(9).font('Helvetica-Bold').text(`Total items affected: ${items.length}`, 50);
        doc.end();
    });
}

// --- WEEKLY SCHEDULE PDF ---
function buildWeeklySchedulePDF(shifts, employees, storeId, weekStart, weekEnd) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', layout: 'landscape', margins: { top: 25, bottom: 25, left: 30, right: 30 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);

        const fullDays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
        const dayDates = [];
        const ws = new Date(weekStart + 'T00:00:00');
        for (let i = 0; i < 7; i++) {
            const d = new Date(ws);
            d.setDate(ws.getDate() + i);
            dayDates.push(`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`);
        }

        const shiftMap = {};
        shifts.forEach(s => {
            if (!shiftMap[s.eid]) shiftMap[s.eid] = {};
            shiftMap[s.eid][s.shift_date] = { start: s.start_time, end: s.end_time, position: s.position };
        });

        const posColors = { SM: '#1e40af', ASM: '#7c3aed', LSA: '#0891b2', SA: '#059669', KEY: '#d97706' };

        function fmt12(timeStr) {
            if (!timeStr) return '';
            const [h, m] = timeStr.split(':').map(Number);
            const ampm = h >= 12 ? 'p' : 'a';
            const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
            return `${h12}:${String(m).padStart(2, '0')}${ampm}`;
        }

        // Layout
        const leftMargin = 30;
        const rightMargin = 732;
        const nameColW = 115;
        const totalColW = 45;
        const dayColW = (rightMargin - leftMargin - nameColW - totalColW) / 7;
        // Scale row height to fill the page — header ~75px, footer ~40px, column header 22px
        const availableHeight = 562 - 25 - 25 - 75 - 22 - 40; // page height minus margins, header, col header, footer
        const rowH = Math.max(28, Math.min(70, Math.floor(availableHeight / Math.max(employees.length, 1))));

        // Header
        doc.fontSize(15).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(10).text(`Store #${storeId} — Weekly Employee Schedule`, { align: 'center' });
        doc.fontSize(8).font('Helvetica').text(`${weekStart}  through  ${weekEnd}`, { align: 'center' });
        doc.moveDown(0.4);

        // Position legend
        const legendY = doc.y;
        let legendX = leftMargin;
        doc.fontSize(6).font('Helvetica-Bold');
        Object.entries(posColors).forEach(([pos, color]) => {
            doc.rect(legendX, legendY, 8, 8).fill(color);
            doc.fillColor('#000').text(pos, legendX + 10, legendY, { width: 30 });
            legendX += 45;
        });
        doc.moveDown(0.6);

        // Column header background
        const headerY = doc.y;
        doc.rect(leftMargin, headerY, rightMargin - leftMargin, 22).fill('#1e293b');

        // Column headers
        doc.fillColor('#ffffff').fontSize(7).font('Helvetica-Bold');
        doc.text('EMPLOYEE', leftMargin + 4, headerY + 4, { width: nameColW - 8 });
        for (let i = 0; i < 7; i++) {
            const x = leftMargin + nameColW + i * dayColW;
            const dateShort = dayDates[i].slice(5);
            doc.text(`${fullDays[i].slice(0,3).toUpperCase()} ${dateShort}`, x + 2, headerY + 4, { width: dayColW - 4, align: 'center' });
        }
        doc.text('HRS', leftMargin + nameColW + 7 * dayColW + 2, headerY + 4, { width: totalColW - 4, align: 'center' });
        doc.y = headerY + 22;

        // Daily totals accumulators
        const dailyMins = [0, 0, 0, 0, 0, 0, 0];
        let grandTotalMins = 0;

        // Employee rows
        employees.forEach((emp, rowIdx) => {
            const rowY = doc.y;

            // Alternating row background
            if (rowIdx % 2 === 0) {
                doc.rect(leftMargin, rowY, rightMargin - leftMargin, rowH).fill('#f1f5f9');
            } else {
                doc.rect(leftMargin, rowY, rightMargin - leftMargin, rowH).fill('#ffffff');
            }

            // Row border
            doc.moveTo(leftMargin, rowY + rowH).lineTo(rightMargin, rowY + rowH).lineWidth(0.3).strokeColor('#cbd5e1').stroke();

            // Vertical lines
            for (let i = 0; i <= 7; i++) {
                const x = leftMargin + nameColW + i * dayColW;
                doc.moveTo(x, rowY).lineTo(x, rowY + rowH).stroke();
            }

            const eidShifts = shiftMap[emp.eid] || {};
            let totalMins = 0;

            // Employee name — vertically centered
            const nameY = rowY + (rowH / 2) - 8;
            doc.fillColor('#0f172a').fontSize(8).font('Helvetica-Bold');
            doc.text(emp.name || emp.eid, leftMargin + 4, nameY, { width: nameColW - 8 });
            doc.fontSize(6).font('Helvetica').fillColor('#64748b');
            doc.text(emp.role || '', leftMargin + 4, nameY + 12, { width: nameColW - 8 });

            // Day cells
            for (let i = 0; i < 7; i++) {
                const x = leftMargin + nameColW + i * dayColW;
                const s = eidShifts[dayDates[i]];
                if (s) {
                    const startParts = s.start.split(':').map(Number);
                    const endParts = s.end.split(':').map(Number);
                    const mins = (endParts[0]*60 + endParts[1]) - (startParts[0]*60 + startParts[1]);
                    const validMins = mins > 0 ? mins : 0;
                    totalMins += validMins;
                    dailyMins[i] += validMins;

                    // Position color bar
                    const posColor = posColors[s.position] || '#64748b';
                    doc.rect(x + 2, rowY + 2, dayColW - 4, rowH - 4).lineWidth(1.5).strokeColor(posColor).stroke();

                    // Time — vertically centered in cell
                    const cellMidY = rowY + (rowH / 2) - 8;
                    doc.fillColor('#0f172a').fontSize(7).font('Helvetica-Bold');
                    doc.text(`${fmt12(s.start)} - ${fmt12(s.end)}`, x + 2, cellMidY, { width: dayColW - 4, align: 'center' });
                    // Hours + position
                    doc.fontSize(5).font('Helvetica').fillColor('#64748b');
                    doc.text(`${(validMins/60).toFixed(1)}h · ${s.position || 'SA'}`, x + 2, cellMidY + 12, { width: dayColW - 4, align: 'center' });
                } else {
                    doc.fillColor('#94a3b8').fontSize(7).font('Helvetica');
                    doc.text('—', x + 2, rowY + (rowH / 2) - 4, { width: dayColW - 4, align: 'center' });
                }
            }
            grandTotalMins += totalMins;

            // Total hours
            doc.fillColor('#0f172a').fontSize(8).font('Helvetica-Bold');
            doc.text(`${(totalMins/60).toFixed(1)}`, leftMargin + nameColW + 7 * dayColW + 2, rowY + (rowH / 2) - 4, { width: totalColW - 4, align: 'center' });

            doc.y = rowY + rowH;
        });

        // Footer totals row
        const footY = doc.y;
        doc.rect(leftMargin, footY, rightMargin - leftMargin, 20).fill('#1e293b');
        doc.fillColor('#ffffff').fontSize(7).font('Helvetica-Bold');
        doc.text('DAILY TOTALS', leftMargin + 4, footY + 5, { width: nameColW - 8 });
        for (let i = 0; i < 7; i++) {
            const x = leftMargin + nameColW + i * dayColW;
            doc.text(`${(dailyMins[i]/60).toFixed(1)}h`, x + 2, footY + 5, { width: dayColW - 4, align: 'center' });
        }
        doc.text(`${(grandTotalMins/60).toFixed(1)}`, leftMargin + nameColW + 7 * dayColW + 2, footY + 5, { width: totalColW - 4, align: 'center' });
        doc.y = footY + 20;

        doc.moveDown(0.5);
        doc.fillColor('#64748b').fontSize(7).font('Helvetica').text(`Generated: ${new Date().toLocaleString()}`, { align: 'center' });

        doc.strokeColor('#000').lineWidth(1).fillColor('#000'); // reset
        doc.end();
    });
}

function buildIndividualSchedulePDF(empName, eid, shifts, storeId, weekStart, weekEnd) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);

        const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
        const ws = new Date(weekStart + 'T00:00:00');
        const dayDates = [];
        for (let i = 0; i < 7; i++) {
            const d = new Date(ws);
            d.setDate(ws.getDate() + i);
            dayDates.push(`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`);
        }

        const shiftByDate = {};
        shifts.forEach(s => { shiftByDate[s.shift_date] = s; });

        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('EMPLOYEE SCHEDULE', { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(11).font('Helvetica').text(`${empName} (EID: ${eid})`, { align: 'center' });
        doc.fontSize(9).text(`Week: ${weekStart} to ${weekEnd}`, { align: 'center' });
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);

        // Table header
        doc.fontSize(10).font('Helvetica-Bold');
        const hY = doc.y;
        doc.text('Day', 50, hY, { width: 90 });
        doc.text('Date', 145, hY, { width: 80 });
        doc.text('Start', 230, hY, { width: 60 });
        doc.text('End', 295, hY, { width: 60 });
        doc.text('Hours', 360, hY, { width: 50, align: 'right' });
        doc.text('Position', 420, hY, { width: 60 });
        doc.text('Notes', 485, hY, { width: 77 });
        doc.moveDown(0.2);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);

        function fmt12(timeStr) {
            if (!timeStr) return '';
            const [h, m] = timeStr.split(':').map(Number);
            const ampm = h >= 12 ? 'PM' : 'AM';
            const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
            return `${h12}:${String(m).padStart(2, '0')} ${ampm}`;
        }

        let totalMins = 0;
        const rowH = 32;
        let curY = doc.y;

        for (let i = 0; i < 7; i++) {
            const s = shiftByDate[dayDates[i]];

            // Alternating row background
            if (i % 2 === 0) doc.rect(50, curY, 512, rowH).fill('#f1f5f9');
            else doc.rect(50, curY, 512, rowH).fill('#ffffff');
            doc.moveTo(50, curY + rowH).lineTo(562, curY + rowH).lineWidth(0.3).strokeColor('#cbd5e1').stroke();

            const textY = curY + (rowH / 2) - 5;
            doc.fillColor('#0f172a').font('Helvetica-Bold').fontSize(9);
            doc.text(days[i], 55, textY, { width: 85 });
            doc.font('Helvetica').fontSize(9).fillColor('#64748b');
            doc.text(dayDates[i], 145, textY, { width: 80 });

            if (s) {
                const startParts = s.start_time.split(':').map(Number);
                const endParts = s.end_time.split(':').map(Number);
                const mins = (endParts[0]*60 + endParts[1]) - (startParts[0]*60 + startParts[1]);
                totalMins += mins > 0 ? mins : 0;
                doc.fillColor('#0f172a').font('Helvetica').fontSize(9);
                doc.text(fmt12(s.start_time), 230, textY, { width: 70 });
                doc.text(fmt12(s.end_time), 305, textY, { width: 70 });
                doc.font('Helvetica-Bold').text(`${(mins/60).toFixed(1)}h`, 380, textY, { width: 40, align: 'right' });
                doc.font('Helvetica').text(s.position || 'SA', 430, textY, { width: 50 });
                doc.fontSize(8).text((s.notes || '').substring(0, 20), 485, textY, { width: 77 });
            } else {
                doc.fillColor('#94a3b8').font('Helvetica').fontSize(9);
                doc.text('OFF', 230, textY);
            }
            curY += rowH;
        }

        doc.y = curY;
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).lineWidth(1).strokeColor('#000').stroke();
        doc.moveDown(0.4);
        doc.fillColor('#000').fontSize(12).font('Helvetica-Bold').text(`Total Estimated Hours: ${(totalMins/60).toFixed(1)}`, 50);
        doc.end();
    });
}

// --- CYCLE COUNT SHEET PDF ---
function buildCycleCountPDF(sections, storeId, date) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);
        const pageBottom = 732 - 40;

        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('CYCLE COUNT SHEET', { align: 'center' });
        doc.fontSize(10).font('Helvetica').text(date, { align: 'center' });
        doc.moveDown(0.5);

        sections.forEach((sec, idx) => {
            if (idx > 0) doc.addPage();
            doc.x = 50;
            doc.fontSize(11).font('Helvetica-Bold').text(`Planogram: ${sec.pog_name} — Section ${sec.section}`, 50);
            doc.moveDown(0.3);
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.3);

            // Table header
            doc.fontSize(8).font('Helvetica-Bold');
            const hY = doc.y;
            doc.text('Pos', 50, hY, { width: 30 }); doc.text('Shelf', 85, hY, { width: 35 });
            doc.text('SKU', 125, hY, { width: 80 }); doc.text('Item Name', 210, hY, { width: 170 });
            doc.text('System', 385, hY, { width: 45, align: 'right' });
            doc.text('Counted', 435, hY, { width: 55, align: 'right' });
            doc.text('Variance', 495, hY, { width: 55, align: 'right' });
            doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);

            doc.font('Helvetica').fontSize(8);
            sec.items.forEach(item => {
                if (doc.y > pageBottom - 15) {
                    doc.addPage(); doc.x = 50;
                    doc.fontSize(8).font('Helvetica-Bold');
                    const rhY = doc.y;
                    doc.text('Pos', 50, rhY, { width: 30 }); doc.text('Shelf', 85, rhY, { width: 35 });
                    doc.text('SKU', 125, rhY, { width: 80 }); doc.text('Item Name', 210, rhY, { width: 170 });
                    doc.text('System', 385, rhY, { width: 45, align: 'right' });
                    doc.text('Counted', 435, rhY, { width: 55, align: 'right' });
                    doc.text('Variance', 495, rhY, { width: 55, align: 'right' });
                    doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);
                    doc.font('Helvetica').fontSize(8);
                }
                const rY = doc.y;
                doc.text(String(item.position || ''), 50, rY, { width: 30 });
                doc.text(item.shelf || '', 85, rY, { width: 35 });
                doc.text(item.sku || '', 125, rY, { width: 80 });
                doc.text((item.name || '').substring(0, 25), 210, rY, { width: 170 });
                doc.text(String(item.quantity != null ? item.quantity : '?'), 385, rY, { width: 45, align: 'right' });
                // Counted and Variance left blank for hand-writing
                doc.text('_____', 435, rY, { width: 55, align: 'right' });
                doc.text('_____', 495, rY, { width: 55, align: 'right' });
                doc.moveDown(0.1);
            });
        });

        if (sections.length === 0) {
            doc.fontSize(11).font('Helvetica').text('No cycle count sections scheduled for today.', { align: 'center' });
        }

        doc.moveDown(1);
        doc.fontSize(8).font('Helvetica').text(`Generated: ${new Date().toLocaleString()}`, { align: 'center' });
        doc.end();
    });
}

// --- DAILY STORE SUMMARY PDFS ---
function buildOpeningReportPDF(data, storeId, date) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);
        const pageW = 612 - 100;
        const pageBottom = 732 - 40;

        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('DAILY OPENING REPORT', { align: 'center' });
        doc.fontSize(10).font('Helvetica').text(date, { align: 'center' });
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();

        // Section 1: Scheduled Employees
        doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text("TODAY'S SCHEDULED EMPLOYEES", 50);
        doc.moveDown(0.3);
        if (data.employees.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No employees scheduled.', 50);
        } else {
            doc.fontSize(8).font('Helvetica-Bold');
            const hY = doc.y;
            doc.text('Name', 50, hY, { width: 150 }); doc.text('EID', 205, hY, { width: 70 });
            doc.text('Start', 280, hY, { width: 60 }); doc.text('End', 345, hY, { width: 60 });
            doc.text('Position', 410, hY, { width: 60 });
            doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);
            doc.font('Helvetica').fontSize(8);
            data.employees.forEach(e => {
                const rY = doc.y;
                doc.text(e.employee_name || e.eid, 50, rY, { width: 150 }); doc.text(e.eid, 205, rY, { width: 70 });
                doc.text(e.start_time?.slice(0,5) || '', 280, rY, { width: 60 }); doc.text(e.end_time?.slice(0,5) || '', 345, rY, { width: 60 });
                doc.text(e.position || 'SA', 410, rY, { width: 60 });
                doc.moveDown(0.1);
            });
        }

        // Section 2: Pending Tasks
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('PENDING TASKS', 50);
        doc.moveDown(0.3);
        if (data.tasks.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No pending tasks.', 50);
        } else {
            doc.fontSize(8).font('Helvetica');
            data.tasks.forEach(t => {
                doc.text(`[${t.priority || 'NORMAL'}] ${t.description}${t.assigned_name ? ' — ' + t.assigned_name : ''}`, 50);
                doc.moveDown(0.1);
            });
        }

        // Section 3: Low Stock Alerts
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('LOW STOCK ALERTS', 50);
        doc.moveDown(0.3);
        if (data.lowStock.length === 0) {
            doc.fontSize(9).font('Helvetica').text('All items above reorder threshold.', 50);
        } else {
            doc.fontSize(8).font('Helvetica-Bold');
            const lY = doc.y;
            doc.text('SKU', 50, lY, { width: 100 }); doc.text('Name', 155, lY, { width: 200 });
            doc.text('Qty', 360, lY, { width: 50, align: 'right' }); doc.text('Min', 415, lY, { width: 50, align: 'right' });
            doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);
            doc.font('Helvetica').fontSize(8);
            data.lowStock.slice(0, 30).forEach(i => {
                if (doc.y > pageBottom - 15) { doc.addPage(); doc.x = 50; }
                const rY = doc.y;
                doc.text(i.sku, 50, rY, { width: 100 }); doc.text((i.name || '').substring(0, 30), 155, rY, { width: 200 });
                doc.text(String(i.total_qty), 360, rY, { width: 50, align: 'right' }); doc.text(String(i.reorder_min), 415, rY, { width: 50, align: 'right' });
                doc.moveDown(0.1);
            });
            if (data.lowStock.length > 30) doc.text(`... and ${data.lowStock.length - 30} more`, 50);
        }

        // Section 4: Pending Manifests
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('PENDING TRUCK MANIFESTS', 50);
        doc.moveDown(0.3);
        if (data.manifests.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No pending manifests.', 50);
        } else {
            doc.fontSize(8).font('Helvetica');
            data.manifests.forEach(m => {
                doc.text(`${m.manifest_number} — BOL: ${m.bol_number || 'N/A'} — Created: ${new Date(m.created_at).toLocaleDateString()}`, 50);
                doc.moveDown(0.1);
            });
        }

        doc.moveDown(1);
        doc.fontSize(8).font('Helvetica').text(`Generated: ${new Date().toLocaleString()}`, { align: 'center' });
        doc.end();
    });
}

function buildClosingReportPDF(data, storeId, date) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);
        const pageW = 612 - 100;
        const pageBottom = 732 - 40;

        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('DAILY CLOSING REPORT', { align: 'center' });
        doc.fontSize(10).font('Helvetica').text(date, { align: 'center' });
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();

        // Section 1: Employee Hours Worked
        doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('EMPLOYEE HOURS WORKED', 50);
        doc.moveDown(0.3);
        if (data.employeeHours.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No time punches recorded today.', 50);
        } else {
            doc.fontSize(8).font('Helvetica-Bold');
            const hY = doc.y;
            doc.text('Name', 50, hY, { width: 130 }); doc.text('EID', 185, hY, { width: 60 });
            doc.text('Clock In', 250, hY, { width: 70 }); doc.text('Clock Out', 325, hY, { width: 70 });
            doc.text('Hours', 400, hY, { width: 50, align: 'right' }); doc.text('Status', 460, hY, { width: 80 });
            doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);
            doc.font('Helvetica').fontSize(8);
            let totalHrs = 0;
            data.employeeHours.forEach(e => {
                const rY = doc.y;
                doc.text(e.name || e.eid, 50, rY, { width: 130 }); doc.text(e.eid, 185, rY, { width: 60 });
                doc.text(e.clockIn || '-', 250, rY, { width: 70 }); doc.text(e.clockOut || '-', 325, rY, { width: 70 });
                doc.text(e.hours.toFixed(1), 400, rY, { width: 50, align: 'right' });
                doc.text(e.stillClockedIn ? 'Still Clocked In' : 'Complete', 460, rY, { width: 80 });
                totalHrs += e.hours;
                doc.moveDown(0.1);
            });
            doc.moveDown(0.2);
            doc.font('Helvetica-Bold').text(`Total Store Hours: ${totalHrs.toFixed(1)}`, 50);
        }

        // Section 2: Trucks Received
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('TRUCKS RECEIVED TODAY', 50);
        doc.moveDown(0.3);
        if (data.trucksReceived.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No trucks received today.', 50);
        } else {
            doc.fontSize(8).font('Helvetica');
            data.trucksReceived.forEach(m => {
                doc.text(`${m.manifest_number} — BOL: ${m.bol_number || 'N/A'} — ${m.item_count || 0} items`, 50);
                doc.moveDown(0.1);
            });
        }

        // Section 3: Completed Tasks
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('COMPLETED TASKS', 50);
        doc.moveDown(0.3);
        if (data.completedTasks.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No tasks completed today.', 50);
        } else {
            doc.fontSize(8).font('Helvetica');
            data.completedTasks.forEach(t => {
                const label = t.title || '(untitled task)';
                const descPart = t.description ? ` — ${t.description}` : '';
                const whoPart = t.assigned_name ? ` — ${t.assigned_name}` : '';
                doc.text(`${label}${descPart}${whoPart}`, 50);
                doc.moveDown(0.1);
            });
        }

        // Section 4: Reorder Summary
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('REORDER SUMMARY', 50);
        doc.moveDown(0.3);
        if (data.reorders.length === 0) {
            doc.fontSize(9).font('Helvetica').text('No active reorders.', 50);
        } else {
            doc.fontSize(8).font('Helvetica');
            const pending = data.reorders.filter(r => r.status === 'PENDING').length;
            doc.text(`Pending reorders: ${pending}  |  Total active: ${data.reorders.length}`, 50);
        }

        doc.moveDown(1);
        doc.fontSize(8).font('Helvetica').text(`Generated: ${new Date().toLocaleString()}`, { align: 'center' });
        doc.end();
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
    try {
        const item = await resolveScannedItem(req.pool, req.params.upcOrSku);
        if (item) {
            item.taxable = !!item.taxable;
            res.json({ success: true, item });
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

app.post('/api/inventory/master/clear', async (req, res) => {
    const conn = await enterprisePool().getConnection();
    try {
        await conn.beginTransaction();
        await conn.query('DELETE FROM event_items');
        await conn.query('DELETE FROM master_inventory');
        await conn.commit();
        res.json({ success: true, message: 'Global Master List cleared.' });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally {
        conn.release();
    }
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

// --- SALES HISTORY ---
// Recent sales for a given SKU or UPC, joined from receipts + receipt_items. Used by the HHT Sales History tab.
app.get('/api/inventory/sales/:upcOrSku', async (req, res) => {
    const key = req.params.upcOrSku;
    try {
        const [rows] = await req.pool.query(
            `SELECT r.timestamp, r.tender_type, r.total AS receipt_total, r.barcode,
                    ri.quantity, ri.price, ri.original_price, ri.name, ri.sku, ri.upc
             FROM receipt_items ri JOIN receipts r ON ri.receipt_id = r.id
             WHERE ri.sku = ? OR ri.upc = ?
             ORDER BY r.timestamp DESC
             LIMIT 50`,
            [key, key]
        );
        res.json({ success: true, sales: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- INVENTORY ADJUSTMENTS (Damages / Store Use / etc.) ---
// POST decrements on-hand and records an audit row. Called from the HHT.
app.post('/api/inventory/adjust', async (req, res) => {
    const { sku, adjustment_type, quantity, reason_code, notes, eid } = req.body;
    if (!sku || !adjustment_type || !quantity || Number(quantity) <= 0) {
        return res.status(400).json({ success: false, message: 'sku, adjustment_type, and positive quantity are required.' });
    }
    const validTypes = ['DAMAGES','STORE_USE','EXPIRED','SHRINK','FOUND'];
    if (!validTypes.includes(adjustment_type)) {
        return res.status(400).json({ success: false, message: 'Invalid adjustment_type.' });
    }
    const qty = Number(quantity);
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        // Verify the item exists locally.
        const [items] = await conn.query('SELECT sku, name, quantity FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (items.length === 0) {
            await conn.rollback();
            return res.status(404).json({ success: false, message: `Item ${sku} not in local inventory.` });
        }
        const item = items[0];
        // FOUND increments, everything else decrements.
        const delta = adjustment_type === 'FOUND' ? qty : -qty;
        await conn.query('UPDATE inventory SET quantity = GREATEST(0, quantity + ?) WHERE sku = ?', [delta, item.sku]);
        await conn.query(
            'INSERT INTO inventory_adjustments (sku, adjustment_type, quantity, reason_code, notes, eid) VALUES (?, ?, ?, ?, ?, ?)',
            [item.sku, adjustment_type, qty, reason_code || null, notes || null, eid || null]
        );
        await conn.commit();
        const [after] = await req.pool.query('SELECT quantity FROM inventory WHERE sku = ?', [item.sku]);
        res.json({
            success: true,
            message: `Adjusted ${item.name}: ${adjustment_type === 'FOUND' ? '+' : '-'}${qty}. New OH: ${after[0].quantity}.`,
            sku: item.sku,
            new_quantity: after[0].quantity
        });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally { conn.release(); }
});

app.get('/api/inventory/adjustments', async (req, res) => {
    const { type, sku, days } = req.query;
    let where = '1=1';
    const params = [];
    if (type) { where += ' AND adjustment_type = ?'; params.push(type); }
    if (sku) { where += ' AND sku = ?'; params.push(sku); }
    if (days) { where += ' AND created_at >= NOW() - INTERVAL ? DAY'; params.push(Number(days)); }
    try {
        const [rows] = await req.pool.query(
            `SELECT a.*, i.name, u.name AS eid_name
             FROM inventory_adjustments a
             LEFT JOIN inventory i ON a.sku = i.sku
             LEFT JOIN users u ON a.eid = u.eid
             WHERE ${where}
             ORDER BY a.created_at DESC
             LIMIT 200`,
            params
        );
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- COMPLIANCE / SAFETY / REFRIGERATION LOGS ---
app.post('/api/compliance/submit', async (req, res) => {
    const { check_type, fixture_id, details, passed, notes, eid } = req.body;
    if (!check_type) return res.status(400).json({ success: false, message: 'check_type required.' });
    try {
        const detailStr = typeof details === 'string' ? details : JSON.stringify(details || {});
        const [r] = await req.pool.query(
            'INSERT INTO compliance_logs (check_type, fixture_id, details, passed, notes, eid) VALUES (?, ?, ?, ?, ?, ?)',
            [check_type, fixture_id || null, detailStr, passed == null ? 1 : (passed ? 1 : 0), notes || null, eid || null]
        );
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/compliance', async (req, res) => {
    const { type, days } = req.query;
    let where = '1=1';
    const params = [];
    if (type) { where += ' AND check_type = ?'; params.push(type); }
    if (days) { where += ' AND created_at >= NOW() - INTERVAL ? DAY'; params.push(Number(days)); }
    try {
        const [rows] = await req.pool.query(
            `SELECT c.*, u.name AS eid_name
             FROM compliance_logs c LEFT JOIN users u ON c.eid = u.eid
             WHERE ${where}
             ORDER BY c.created_at DESC
             LIMIT 200`, params);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- INVENTORY TRANSFERS (outbound requests) ---
app.post('/api/inventory/transfer/request', async (req, res) => {
    const { sku, quantity, other_store_id, notes, eid } = req.body;
    if (!sku || !quantity) return res.status(400).json({ success: false, message: 'sku and quantity required.' });
    try {
        const [r] = await req.pool.query(
            'INSERT INTO inventory_transfers (direction, other_store_id, sku, quantity, notes, eid) VALUES (?, ?, ?, ?, ?, ?)',
            ['OUT', other_store_id || null, sku, Number(quantity), notes || null, eid || null]
        );
        res.json({ success: true, id: r.insertId, message: `Transfer request logged: ${quantity} x ${sku}.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/inventory/transfers', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT t.*, i.name AS item_name, u.name AS eid_name
             FROM inventory_transfers t LEFT JOIN inventory i ON t.sku = i.sku LEFT JOIN users u ON t.eid = u.eid
             ORDER BY t.created_at DESC LIMIT 200`);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- REPORTS: NONES (dead stock) & TONS (top movers) ---
// days param: lookback window. Tons = most units sold. Nones = in inventory but no sales in window.
app.get('/api/reports/movers', async (req, res) => {
    const days = Math.max(1, Number(req.query.days) || 30);
    try {
        const [tons] = await req.pool.query(
            `SELECT ri.sku, ri.name, SUM(ri.quantity) AS units_sold, SUM(ri.quantity * ri.price) AS revenue
             FROM receipt_items ri JOIN receipts r ON ri.receipt_id = r.id
             WHERE r.timestamp >= NOW() - INTERVAL ? DAY
             GROUP BY ri.sku, ri.name
             ORDER BY units_sold DESC LIMIT 30`, [days]);
        const [nones] = await req.pool.query(
            `SELECT i.sku, i.name, i.quantity AS on_hand, i.department, i.price
             FROM inventory i
             WHERE i.sku NOT IN (
                 SELECT DISTINCT ri.sku FROM receipt_items ri JOIN receipts r ON ri.receipt_id = r.id
                 WHERE r.timestamp >= NOW() - INTERVAL ? DAY
             ) AND i.quantity > 0
             ORDER BY i.quantity DESC LIMIT 50`, [days]);
        res.json({ success: true, window_days: days, tons, nones });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- REVIEW QUEUE ---
// Aggregates items from multiple tables that SM/lead might want to review.
app.get('/api/review/pending', async (req, res) => {
    const days = Math.max(1, Number(req.query.days) || 7);
    try {
        const [adjustments] = await req.pool.query(
            `SELECT a.id, a.sku, a.adjustment_type, a.quantity, a.reason_code, a.notes, a.eid, a.created_at,
                    i.name AS item_name, u.name AS eid_name
             FROM inventory_adjustments a LEFT JOIN inventory i ON a.sku = i.sku LEFT JOIN users u ON a.eid = u.eid
             WHERE a.created_at >= NOW() - INTERVAL ? DAY
             ORDER BY a.created_at DESC LIMIT 100`, [days]);
        const [transfers] = await req.pool.query(
            `SELECT t.*, i.name AS item_name FROM inventory_transfers t LEFT JOIN inventory i ON t.sku = i.sku
             WHERE t.status IN ('PENDING','APPROVED') ORDER BY t.created_at DESC LIMIT 50`);
        const [failed_compliance] = await req.pool.query(
            `SELECT * FROM compliance_logs WHERE passed = 0 AND created_at >= NOW() - INTERVAL ? DAY
             ORDER BY created_at DESC LIMIT 50`, [days]);
        res.json({ success: true, adjustments, transfers, failed_compliance });
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
    const { pog_id, name, dimensions, suffix, pog_type, predecessor_pog_id } = req.body;
    try {
        await enterprisePool().query(
            'INSERT INTO planograms (pog_id, name, dimensions, suffix, pog_type, predecessor_pog_id) VALUES (?, ?, ?, ?, ?, ?)',
            [pog_id, name, dimensions, suffix, pog_type || 'standard', predecessor_pog_id || null]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/pogs/:id', async (req, res) => {
    const { id } = req.params;
    const { pog_id, name, dimensions, suffix, pog_type, predecessor_pog_id } = req.body;
    try {
        // Prevent a POG from pointing at itself as its predecessor.
        const [existing] = await enterprisePool().query('SELECT pog_id FROM planograms WHERE id = ?', [id]);
        if (existing.length === 0) return res.status(404).json({ success: false, message: 'Planogram not found.' });
        if (predecessor_pog_id && predecessor_pog_id === (pog_id || existing[0].pog_id)) {
            return res.status(400).json({ success: false, message: 'A planogram cannot be its own predecessor.' });
        }
        await enterprisePool().query(
            'UPDATE planograms SET pog_id = ?, name = ?, dimensions = ?, suffix = ?, pog_type = ?, predecessor_pog_id = ? WHERE id = ?',
            [pog_id, name, dimensions, suffix, pog_type || 'standard', predecessor_pog_id || null, id]
        );
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

// Ordering across a planogram for global position numbering:
//   - Sections A..J alphabetical (column-major — finish a section before moving on).
//   - Within a section, TOP to BOTTOM: 'P' (peg) first if present, then highest numeric shelf down to 1.
//   - Within a shelf, left to right (stored position ASC).
function shelfRank(shelf) {
    if (shelf == null) return 0; // location-only POGs have no shelves
    if (String(shelf).toUpperCase() === 'P') return 0; // pegs at the top
    const n = parseInt(shelf, 10);
    if (isNaN(n)) return 999; // unknown shelf labels sink to the bottom
    return 1000 - n; // higher shelf number => earlier; shelf 9 -> 991, shelf 1 -> 999
}

function sectionRank(section) {
    if (section == null) return 0;
    return String(section).toUpperCase().charCodeAt(0); // A=65, B=66, ...
}

// Fetches items for a planogram with a computed `global_position` field (1..N) reflecting the
// reading order described by shelfRank/sectionRank. Caller gets items sorted in that order.
async function fetchItemsWithGlobalPosition(planogramRowId) {
    const [rows] = await enterprisePool().query(
        `SELECT pi.id, pi.sku, pi.section, pi.shelf, pi.faces, pi.position, pi.location, m.name, m.upc
         FROM planogram_items pi JOIN master_inventory m ON pi.sku = m.sku
         WHERE pi.planogram_id = ?`,
        [planogramRowId]
    );
    rows.sort((a, b) => {
        const sa = sectionRank(a.section), sb = sectionRank(b.section);
        if (sa !== sb) return sa - sb;
        const ha = shelfRank(a.shelf), hb = shelfRank(b.shelf);
        if (ha !== hb) return ha - hb;
        return (a.position || 0) - (b.position || 0);
    });
    rows.forEach((r, i) => { r.global_position = i + 1; });
    return rows;
}

app.get('/api/pogs/:id/items', async (req, res) => {
    const { id } = req.params;
    try {
        const rows = await fetchItemsWithGlobalPosition(id);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/pogs/:id/items', async (req, res) => {
    const planogram_id = req.params.id;
    const { sku, section, shelf, faces, position } = req.body;
    // Resolve next position within the same (planogram, section, shelf) group if caller omitted it.
    async function nextPosition() {
        let q, p;
        if (section && shelf) {
            q = 'SELECT COALESCE(MAX(position), 0) + 1 AS next_pos FROM planogram_items WHERE planogram_id = ? AND section = ? AND shelf = ?';
            p = [planogram_id, section, shelf];
        } else {
            q = 'SELECT COALESCE(MAX(position), 0) + 1 AS next_pos FROM planogram_items WHERE planogram_id = ? AND section IS NULL AND shelf IS NULL';
            p = [planogram_id];
        }
        const [rows] = await enterprisePool().query(q, p);
        return rows[0].next_pos;
    }
    try {
        const resolvedPosition = (position && Number(position) > 0) ? Number(position) : await nextPosition();
        if (section && shelf) {
            // Standard section/shelf item
            const [existing] = await enterprisePool().query('SELECT id FROM planogram_items WHERE planogram_id = ? AND sku = ? AND section = ? AND shelf = ?', [planogram_id, sku, section, shelf]);
            if (existing.length > 0) {
                // Keep existing position if the caller didn't specify one — don't shove dup-adds to the end.
                const keepPos = (position && Number(position) > 0) ? Number(position) : null;
                if (keepPos != null) {
                    await enterprisePool().query('UPDATE planogram_items SET faces = ?, position = ? WHERE id = ?', [faces, keepPos, existing[0].id]);
                } else {
                    await enterprisePool().query('UPDATE planogram_items SET faces = ? WHERE id = ?', [faces, existing[0].id]);
                }
            } else {
                await enterprisePool().query('INSERT INTO planogram_items (planogram_id, sku, section, shelf, faces, position) VALUES (?, ?, ?, ?, ?, ?)', [planogram_id, sku, section, shelf, faces, resolvedPosition]);
            }
        } else {
            // Location-only planogram — no section/shelf, just sku on the planogram
            const [existing] = await enterprisePool().query('SELECT id FROM planogram_items WHERE planogram_id = ? AND sku = ?', [planogram_id, sku]);
            if (existing.length > 0) {
                const keepPos = (position && Number(position) > 0) ? Number(position) : null;
                if (keepPos != null) {
                    await enterprisePool().query('UPDATE planogram_items SET faces = ?, position = ? WHERE id = ?', [faces || 'F1', keepPos, existing[0].id]);
                } else {
                    await enterprisePool().query('UPDATE planogram_items SET faces = ? WHERE id = ?', [faces || 'F1', existing[0].id]);
                }
            } else {
                await enterprisePool().query('INSERT INTO planogram_items (planogram_id, sku, section, shelf, faces, position) VALUES (?, ?, NULL, NULL, ?, ?)', [planogram_id, sku, faces || 'F1', resolvedPosition]);
            }
        }
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Reorder items within a single (section, shelf) — or the whole planogram for location-only POGs.
// Body: { orderedIds: [id1, id2, ...] } — positions become 1..N in that order.
app.put('/api/pogs/:id/items/reorder', async (req, res) => {
    const planogram_id = req.params.id;
    const { orderedIds } = req.body;
    if (!Array.isArray(orderedIds) || orderedIds.length === 0) {
        return res.status(400).json({ success: false, message: 'orderedIds[] required.' });
    }
    const conn = await enterprisePool().getConnection();
    try {
        await conn.beginTransaction();
        for (let i = 0; i < orderedIds.length; i++) {
            await conn.query(
                'UPDATE planogram_items SET position = ? WHERE id = ? AND planogram_id = ?',
                [i + 1, orderedIds[i], planogram_id]
            );
        }
        await conn.commit();
        res.json({ success: true });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally {
        conn.release();
    }
});

app.delete('/api/pogs/:id/items/:itemId', async (req, res) => {
    const { itemId } = req.params;
    try {
        await enterprisePool().query('DELETE FROM planogram_items WHERE id = ?', [itemId]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Shared helper: applies a planogram's item data into one store's local inventory.
// Used by both /api/pogs/push (direct push) and /api/pogs/reset/scan (HHT-triggered).
async function applyPogToStore(planogramRowId, storeId) {
    const [pogs] = await enterprisePool().query('SELECT * FROM planograms WHERE id = ?', [planogramRowId]);
    if (pogs.length === 0) throw new Error('POG not found');
    const pog = pogs[0];
    const pogInfoString = `${pog.pog_id} ${pog.name} ${pog.dimensions} ${pog.suffix}`;
    // Use global (whole-planogram) position so store inventory reflects continuous numbering.
    const items = await fetchItemsWithGlobalPosition(planogramRowId);
    const currentDate = new Date();
    const formattedDate = `${String(currentDate.getMonth() + 1).padStart(2, '0')}/${String(currentDate.getFullYear()).slice(-2)}`;

    const pool = await getStorePool(storeId);
    const cols = [
        "ALTER TABLE inventory ADD COLUMN location VARCHAR(50)",
        "ALTER TABLE inventory ADD COLUMN faces VARCHAR(10) DEFAULT 'F1'",
        "ALTER TABLE inventory ADD COLUMN pog_date VARCHAR(20)",
        "ALTER TABLE inventory ADD COLUMN pog_info VARCHAR(255)",
        "ALTER TABLE inventory ADD COLUMN position INT DEFAULT 1"
    ];
    for (const col of cols) { try { await pool.query(col); } catch (e) {} }

    // If this planogram replaces a predecessor, demote any item assigned to the predecessor
    // to "MAG" (free-floating, no planogram). New-POG UPDATEs below will re-assign any SKU
    // that's still on the new POG, so only truly-dropped SKUs remain as MAG.
    if (pog.predecessor_pog_id) {
        const prefix = `${pog.predecessor_pog_id} %`;
        await pool.query(
            "UPDATE inventory SET location = 'MAG', faces = NULL, pog_date = NULL, pog_info = NULL, position = NULL WHERE pog_info LIKE ?",
            [prefix]
        );
    }

    for (const item of items) {
        const loc = item.section ? `${item.section}-${item.shelf}` : pog.pog_id;
        await pool.query('UPDATE inventory SET location = ?, faces = ?, pog_date = ?, pog_info = ?, position = ? WHERE sku = ?',
            [loc, item.faces, formattedDate, pogInfoString, item.global_position, item.sku]);
    }
    return pog;
}

app.post('/api/pogs/push', async (req, res) => {
    const { pog_id, storeIds } = req.body;
    try {
        for (const storeId of storeIds) {
            await applyPogToStore(pog_id, storeId);
        }
        res.json({ success: true, message: `Planogram pushed to ${storeIds.length} stores.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- PLANOGRAM RESET TASK APIS ---
// A "reset" bundles one or more planograms into a task deployed to selected stores.
// The planogram data itself is NOT pushed to store inventory at creation time —
// that happens lazily when the HHT scans each POG's physical tag barcode.

app.post('/api/pogs/reset/create', requireAuth, async (req, res) => {
    const { title, planogramIds, storeIds, due_date, priority, description } = req.body;
    if (!title || !Array.isArray(planogramIds) || planogramIds.length === 0 || !Array.isArray(storeIds) || storeIds.length === 0) {
        return res.status(400).json({ success: false, message: 'title, planogramIds[], and storeIds[] are required.' });
    }
    try {
        // Pull POG metadata once so we can snapshot it into each store.
        const [pogs] = await enterprisePool().query(
            `SELECT id, pog_id, name, dimensions, suffix FROM planograms WHERE id IN (${planogramIds.map(() => '?').join(',')})`,
            planogramIds
        );
        if (pogs.length !== planogramIds.length) {
            return res.status(404).json({ success: false, message: 'One or more planograms not found.' });
        }
        const createdTaskIds = [];
        for (const storeId of storeIds) {
            const pool = await getStorePool(storeId);
            const conn = await pool.getConnection();
            try {
                await conn.beginTransaction();
                const [taskResult] = await conn.query(
                    'INSERT INTO tasks (title, description, due_date, priority, task_type) VALUES (?, ?, ?, ?, ?)',
                    [title, description || null, due_date || null, priority || 'NORMAL', 'POG_RESET']
                );
                const taskId = taskResult.insertId;
                for (const p of pogs) {
                    await conn.query(
                        'INSERT INTO task_pog_items (task_id, pog_id, pog_name, pog_dimensions, pog_suffix) VALUES (?, ?, ?, ?, ?)',
                        [taskId, p.pog_id, p.name, p.dimensions, p.suffix]
                    );
                }
                await conn.commit();
                createdTaskIds.push({ storeId, taskId });
            } catch (err) {
                await conn.rollback();
                throw err;
            } finally {
                conn.release();
            }
        }
        res.json({ success: true, message: `Reset task created for ${storeIds.length} store(s).`, tasks: createdTaskIds });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// HHT scans a planogram tag. Body: { pog_id, eid }, Header: X-Store-ID.
// Response statuses:
//   'applied'      — first scan; POG data pushed to store, child marked, task still has siblings open
//   'completed'    — first scan AND this was the last child; task auto-DONE, signoff printed
//   'not_found'    — no open POG_RESET task contains this pog_id
//   'already_done' — this child was previously scanned; caller decides whether to reprint
app.post('/api/pogs/reset/scan', async (req, res) => {
    const storeId = req.headers['x-store-id'];
    const { pog_id, eid } = req.body;
    if (!storeId) return res.status(400).json({ success: false, message: 'X-Store-ID header required.' });
    if (!pog_id) return res.status(400).json({ success: false, message: 'pog_id required.' });
    try {
        const pool = await getStorePool(storeId);
        // Find an open POG_RESET task that contains this pog_id.
        const [matches] = await pool.query(
            `SELECT tpi.id AS child_id, tpi.task_id, tpi.pog_id, tpi.pog_name, tpi.pog_dimensions, tpi.scanned_at, tpi.scanned_by_eid, tpi.scanned_by_name, t.status AS task_status, t.title AS task_title
             FROM task_pog_items tpi JOIN tasks t ON tpi.task_id = t.id
             WHERE tpi.pog_id = ? AND t.task_type = 'POG_RESET'
             ORDER BY (t.status = 'OPEN') DESC, t.id DESC
             LIMIT 1`,
            [pog_id]
        );
        if (matches.length === 0) {
            // Return 200 with status:'not_found' so the HHT can fall through to normal scan handling.
            return res.json({ success: false, status: 'not_found', message: `No reset task contains POG ${pog_id}.` });
        }
        const row = matches[0];
        if (row.scanned_at) {
            return res.json({
                success: true,
                status: 'already_done',
                task_id: row.task_id,
                pog_id: row.pog_id,
                pog_name: row.pog_name,
                scanned_at: row.scanned_at,
                scanned_by_eid: row.scanned_by_eid,
                scanned_by_name: row.scanned_by_name
            });
        }

        // Resolve scanner name (best-effort)
        let scannerName = null;
        if (eid) {
            try {
                const [uRows] = await pool.query('SELECT name FROM users WHERE eid = ?', [eid]);
                if (uRows.length > 0) scannerName = uRows[0].name;
            } catch (_) {}
        }

        // Look up the enterprise planogram row id from the pog_id string.
        const [pogRows] = await enterprisePool().query('SELECT id FROM planograms WHERE pog_id = ?', [pog_id]);
        if (pogRows.length === 0) {
            return res.status(404).json({ success: false, message: `Planogram ${pog_id} not present in enterprise DB.` });
        }
        const enterpriseRowId = pogRows[0].id;

        // Apply POG data to store inventory.
        await applyPogToStore(enterpriseRowId, storeId);

        // Mark the child scanned.
        await pool.query(
            'UPDATE task_pog_items SET scanned_at = NOW(), scanned_by_eid = ?, scanned_by_name = ? WHERE id = ?',
            [eid || null, scannerName, row.child_id]
        );

        // Check siblings — if all scanned, close the task and print signoff.
        const [siblings] = await pool.query('SELECT COUNT(*) AS total, SUM(scanned_at IS NOT NULL) AS done FROM task_pog_items WHERE task_id = ?', [row.task_id]);
        const total = Number(siblings[0].total);
        const done = Number(siblings[0].done);

        if (done >= total) {
            await pool.query("UPDATE tasks SET status = 'DONE', completed_at = NOW() WHERE id = ?", [row.task_id]);
            // Print signoff sheet on laser printer (non-blocking; failures logged but don't fail the scan).
            try {
                const pdfBuffer = await buildResetSignoffPDF(pool, row.task_id, storeId);
                await sendToLaserPrinter(pdfBuffer);
            } catch (printErr) {
                console.error('[Reset Signoff] Print failed:', printErr.message);
            }
            return res.json({
                success: true,
                status: 'completed',
                task_id: row.task_id,
                pog_id: row.pog_id,
                pog_name: row.pog_name,
                completed: done,
                total
            });
        }

        return res.json({
            success: true,
            status: 'applied',
            task_id: row.task_id,
            pog_id: row.pog_id,
            pog_name: row.pog_name,
            completed: done,
            total
        });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// In-progress + recently-completed reset tasks with progress counts.
// Used by HHT home card ("resets pending") and dashboard Reset History panel.
// Query ?status=OPEN|DONE|ALL (default OPEN), ?limit=25
// NOTE: /api/pogs is bypassed by storeContext (see the middleware), so these
// endpoints resolve the per-store pool manually from the X-Store-ID header.
app.get('/api/pogs/reset/tasks', async (req, res) => {
    const storeId = req.headers['x-store-id'];
    if (!storeId) return res.status(400).json({ success: false, message: 'X-Store-ID header required.' });
    const status = (req.query.status || 'OPEN').toUpperCase();
    const limit = Math.min(100, Math.max(1, parseInt(req.query.limit) || 25));
    try {
        const pool = await getStorePool(storeId);
        let where = "t.task_type = 'POG_RESET'";
        const params = [];
        if (status === 'OPEN' || status === 'DONE') {
            where += ' AND t.status = ?';
            params.push(status);
        }
        const [rows] = await pool.query(
            `SELECT t.id, t.title, t.status, t.created_at, t.completed_at, t.due_date, t.priority,
                    (SELECT COUNT(*) FROM task_pog_items WHERE task_id = t.id) AS pog_total,
                    (SELECT COUNT(*) FROM task_pog_items WHERE task_id = t.id AND scanned_at IS NOT NULL) AS pog_done,
                    (SELECT MAX(scanned_at) FROM task_pog_items WHERE task_id = t.id) AS last_scan_at,
                    (SELECT scanned_by_name FROM task_pog_items WHERE task_id = t.id AND scanned_at IS NOT NULL ORDER BY scanned_at DESC LIMIT 1) AS last_scan_by
             FROM tasks t
             WHERE ${where}
             ORDER BY (t.status = 'OPEN') DESC, COALESCE(t.completed_at, t.created_at) DESC
             LIMIT ?`,
            [...params, limit]
        );
        res.json({ success: true, tasks: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Detail for a single reset task, including each child POG's scan state.
app.get('/api/pogs/reset/tasks/:id', async (req, res) => {
    const storeId = req.headers['x-store-id'];
    if (!storeId) return res.status(400).json({ success: false, message: 'X-Store-ID header required.' });
    try {
        const pool = await getStorePool(storeId);
        const [tasks] = await pool.query("SELECT * FROM tasks WHERE id = ? AND task_type = 'POG_RESET'", [req.params.id]);
        if (tasks.length === 0) return res.status(404).json({ success: false, message: 'Reset task not found.' });
        const [children] = await pool.query(
            `SELECT id, pog_id, pog_name, pog_dimensions, pog_suffix, scanned_at, scanned_by_eid, scanned_by_name
             FROM task_pog_items WHERE task_id = ? ORDER BY id ASC`,
            [req.params.id]
        );
        res.json({ success: true, task: tasks[0], pog_items: children });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Reprint the signoff sheet for a completed reset task.
app.post('/api/pogs/reset/reprint/:taskId', async (req, res) => {
    const storeId = req.headers['x-store-id'];
    if (!storeId) return res.status(400).json({ success: false, message: 'X-Store-ID header required.' });
    try {
        const pool = await getStorePool(storeId);
        const [tasks] = await pool.query("SELECT id, task_type FROM tasks WHERE id = ?", [req.params.taskId]);
        if (tasks.length === 0) return res.status(404).json({ success: false, message: 'Task not found.' });
        if (tasks[0].task_type !== 'POG_RESET') return res.status(400).json({ success: false, message: 'Not a POG reset task.' });
        const pdfBuffer = await buildResetSignoffPDF(pool, req.params.taskId, storeId);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Signoff sheet reprinted.' });
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

            // Capture old prices before updating (for price change report)
            const skuList = items.map(i => i.sku);
            const placeholders = skuList.map(() => '?').join(',');
            const [oldPrices] = await pool.query(`SELECT sku, name, price FROM inventory WHERE sku IN (${placeholders})`, skuList);
            const oldPriceMap = {};
            oldPrices.forEach(r => { oldPriceMap[r.sku] = { name: r.name, price: r.price }; });

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

            // Auto-print price change report
            try {
                const pdfBuffer = await buildPriceChangeReportPDF(eventData[0], items, oldPriceMap, storeId);
                await sendToLaserPrinter(pdfBuffer);
            } catch (printErr) { console.error('[Price Report] Print failed:', printErr.message); }
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

// Short-pick rollup — groups completed orders' short_reason rows by reason code, with optional day window.
app.get('/api/bopis/short-pick-report', async (req, res) => {
    const days = Math.min(365, Math.max(1, parseInt(req.query.days) || 30));
    try {
        const [summary] = await req.pool.query(
            `SELECT ooi.short_reason AS reason,
                    COUNT(*) AS occurrences,
                    SUM(ooi.qty_ordered - ooi.qty_picked) AS units_short
             FROM online_order_items ooi
             JOIN online_orders oo ON ooi.order_id = oo.id
             WHERE ooi.short_reason IS NOT NULL
               AND oo.status = 'COMPLETED'
               AND oo.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
             GROUP BY ooi.short_reason
             ORDER BY occurrences DESC`,
            [days]
        );
        const [topSkus] = await req.pool.query(
            `SELECT ooi.sku, ooi.name, ooi.short_reason AS reason,
                    COUNT(*) AS occurrences,
                    SUM(ooi.qty_ordered - ooi.qty_picked) AS units_short
             FROM online_order_items ooi
             JOIN online_orders oo ON ooi.order_id = oo.id
             WHERE ooi.short_reason IS NOT NULL
               AND oo.status = 'COMPLETED'
               AND oo.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
             GROUP BY ooi.sku, ooi.name, ooi.short_reason
             ORDER BY occurrences DESC
             LIMIT 20`,
            [days]
        );
        res.json({ success: true, days, summary, top_skus: topSkus });
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
    if (!req.body.sku) return res.json({ success: false, message: 'No SKU provided.' });

    try {
        const inv = await resolveScannedItem(req.pool, req.body.sku);
        if (!inv) {
            return res.json({ success: false, message: `Item [${req.body.sku}] not in local inventory.` });
        }
        const itemSku = inv.sku;
        const currentPrice = inv.price;

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
    const page = Math.max(1, parseInt(req.query.page) || 1);
    const limit = Math.min(100, Math.max(1, parseInt(req.query.limit) || 25));
    const offset = (page - 1) * limit;
    const status = req.query.status;
    try {
        let where = '';
        const params = [];
        if (status) { where = 'WHERE status = ?'; params.push(status.toUpperCase()); }
        const [[{ total }]] = await req.pool.query(`SELECT COUNT(*) AS total FROM truck_manifests ${where}`, params);
        const [rows] = await req.pool.query(`SELECT * FROM truck_manifests ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`, [...params, limit, offset]);
        res.json({ rows, page, limit, total, totalPages: Math.ceil(total / limit) });
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
    const { bol_number, manifest_id, print: shouldPrint = true } = req.body;
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

        let updated = 0, skipped = 0;
        for (const item of items) {
            const [inv] = await req.pool.query('SELECT pack_size FROM inventory WHERE sku = ?', [item.sku]);
            if (inv.length === 0) { console.log(`[Master Receive] SKU ${item.sku} NOT FOUND in inventory — skipped`); skipped++; continue; }
            const packSize = inv[0].pack_size || 1;
            const units = item.expected_packs * packSize;
            const [result] = await req.pool.query('UPDATE inventory SET quantity_backstock = quantity_backstock + ? WHERE sku = ?', [units, item.sku]);
            console.log(`[Master Receive] SKU ${item.sku}: +${units} units to backstock (${item.expected_packs} boxes x ${packSize}), affected=${result.affectedRows}`);
            await req.pool.query('UPDATE manifest_items SET received_packs = expected_packs WHERE id = ?', [item.id]);
            updated++;
        }
        console.log(`[Master Receive] Done: ${updated} updated, ${skipped} skipped`);

        await req.pool.query('UPDATE truck_manifests SET status = "COMPLETED" WHERE id = ?', [manifestId]);

        // Auto-print store copy to laser printer (if enabled)
        if (shouldPrint) {
            try {
                await printTruckReceivingReport(req.pool, manifestId, req.storeId);
            } catch (printErr) { console.error('[Laser Print] Failed:', printErr.message); }
        }

        res.json({ success: true, message: `Master Receive Complete. Manifest processed. Receiving report sent to printer.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/manifest/print-report/:id', async (req, res) => {
    try {
        await printTruckReceivingReport(req.pool, req.params.id, req.storeId, 'STORE COPY', false);
        res.json({ success: true, message: 'Store copy sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/manifest/print-driver/:id', async (req, res) => {
    try {
        await printTruckReceivingReport(req.pool, req.params.id, req.storeId, 'DRIVER COPY', true);
        res.json({ success: true, message: 'Driver copy sent to printer.' });
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
        const [items] = await req.pool.query('SELECT ri.*, COALESCE(i.name, ri.sku) AS name, COALESCE(i.pack_size, 6) AS pack_size FROM rolltainer_items ri LEFT JOIN inventory i ON ri.sku = i.sku WHERE ri.rolltainer_id = ?', [req.params.id]);

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
            ESC.BARCODE(rt.barcode),
            ESC.FEED_3, ESC.CUT
        ]);
        
        await sendToPrinter(receiptData);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/bopis/receive/:id', async (req, res) => {
    if (!req.body.sku) return res.json({ success: false, message: 'No SKU provided.' });

    try {
        // 1. Find the item in local inventory to get pack_size
        const inv = await resolveScannedItem(req.pool, req.body.sku);
        if (!inv) return res.json({ success: false, message: `Item [${req.body.sku}] not in local inventory.` });

        const itemSku = inv.sku;
        const packSize = inv.pack_size || 1;

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
        // Defensive: if caller ever passes a raw warehouse label instead of a resolved SKU,
        // extract the UPC. Matches the detection used on the HHT and in cleanScannedCode.
        if (typeof sku === 'string' && sku.length === 18 && sku.startsWith('0000') && sku.endsWith('00') && /^\d+$/.test(sku)) {
            sku = sku.substring(4, 16);
        }

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
            SELECT ri.*, COALESCE(i.name, ri.sku) AS name, COALESCE(i.pack_size, 6) AS pack_size
            FROM rolltainer_items ri
            LEFT JOIN inventory i ON ri.sku = i.sku
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

const BOPIS_SHORT_REASONS = ['OOS', 'DAMAGED', 'NOT_FOUND', 'SUB_OFFERED'];

app.post('/api/bopis/finalize/:id', async (req, res) => {
    const shortReasons = req.body && req.body.short_reasons && typeof req.body.short_reasons === 'object' ? req.body.short_reasons : {};
    for (const v of Object.values(shortReasons)) {
        if (!BOPIS_SHORT_REASONS.includes(v)) {
            return res.status(400).json({ success: false, message: `Invalid short_reason '${v}'.` });
        }
    }
    let order, items, subtotal, tax, total;
    const conn = await req.pool.getConnection();
    try {
        const [orderRows] = await conn.query('SELECT * FROM online_orders WHERE id = ?', [req.params.id]);
        if (orderRows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        order = orderRows[0];
        [items] = await conn.query('SELECT * FROM online_order_items WHERE order_id = ?', [req.params.id]);

        const shortItems = items.filter(it => it.qty_picked < it.qty_ordered);
        const missing = shortItems.filter(it => !shortReasons[it.sku]).map(it => it.sku);
        if (shortItems.length > 0 && missing.length > 0) {
            return res.status(400).json({ success: false, message: `Short-pick reason required for: ${missing.join(', ')}.`, missing_skus: missing });
        }

        subtotal = 0;
        for (const item of items) {
            subtotal += item.qty_picked * item.price;
        }
        tax = subtotal * 0.055;
        total = subtotal + tax;

        await conn.beginTransaction();
        try {
            for (const sku of Object.keys(shortReasons)) {
                await conn.query('UPDATE online_order_items SET short_reason = ? WHERE order_id = ? AND sku = ?', [shortReasons[sku], req.params.id, sku]);
            }
            if (!order.is_mock) {
                for (const item of items) {
                    if (item.qty_picked > 0) {
                        await conn.query('UPDATE inventory SET quantity = quantity - ? WHERE sku = ?', [item.qty_picked, item.sku]);
                    }
                }
            }
            await conn.query('UPDATE online_orders SET status = "COMPLETED", subtotal = ?, tax = ?, total = ? WHERE id = ?', [subtotal, tax, total, req.params.id]);
            await conn.commit();
        } catch (txErr) {
            await conn.rollback();
            throw txErr;
        }
    } catch (err) {
        conn.release();
        return res.status(500).json({ success: false, message: err.message });
    }
    conn.release();

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
        const sets = [];
        const params = [];
        if (title !== undefined) { sets.push('title=?'); params.push(title); }
        if (discount !== undefined) { sets.push('discount=?'); params.push(discount); }
        if (minimum !== undefined) { sets.push('minimum=?'); params.push(minimum); }
        if (fine_print !== undefined) { sets.push('fine_print=?'); params.push(fine_print || null); }
        if (valid_date !== undefined) { sets.push('valid_date=?'); params.push(valid_date || null); }
        if (active !== undefined) { sets.push('active=?'); params.push(active ? 1 : 0); }
        if (sets.length === 0) return res.status(400).json({ success: false, message: 'No fields to update.' });
        params.push(req.params.id);
        await req.pool.query(`UPDATE promotions SET ${sets.join(', ')} WHERE id=?`, params);
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
        // Attach POG children to any POG_RESET rows so the client can render progress inline.
        const resetIds = rows.filter(r => r.task_type === 'POG_RESET').map(r => r.id);
        if (resetIds.length > 0) {
            const [children] = await req.pool.query(
                `SELECT task_id, pog_id, pog_name, pog_dimensions, pog_suffix, scanned_at, scanned_by_eid, scanned_by_name
                 FROM task_pog_items WHERE task_id IN (${resetIds.map(() => '?').join(',')})
                 ORDER BY id ASC`,
                resetIds
            );
            const byTask = new Map();
            for (const c of children) {
                if (!byTask.has(c.task_id)) byTask.set(c.task_id, []);
                byTask.get(c.task_id).push(c);
            }
            for (const r of rows) {
                if (r.task_type === 'POG_RESET') r.pog_items = byTask.get(r.id) || [];
            }
        }
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/tasks/:id/pog-items', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT pog_id, pog_name, pog_dimensions, pog_suffix, scanned_at, scanned_by_eid, scanned_by_name
             FROM task_pog_items WHERE task_id = ? ORDER BY id ASC`,
            [req.params.id]
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
        // POG_RESET tasks can only be completed via a physical-tag scan.
        const [rows] = await req.pool.query('SELECT task_type FROM tasks WHERE id = ?', [req.params.id]);
        if (rows.length > 0 && rows[0].task_type === 'POG_RESET') {
            return res.status(400).json({ success: false, message: 'Planogram reset tasks can only be completed by scanning each POG tag.' });
        }
        await req.pool.query(
            "UPDATE tasks SET status = ?, completed_at = CASE WHEN ? = 'DONE' THEN NOW() ELSE NULL END WHERE id = ?",
            [status, status, req.params.id]
        );
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

app.post('/api/print_location_barcode', async (req, res) => {
    const { pog_id, name, dims, suffix } = req.body;
    if (!pog_id) return res.status(400).json({ success: false, message: 'pog_id required' });
    try {
        const barcodeData = `CYCL_${pog_id}`;
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
            Buffer.from(`\n${pog_id} ${name} ${dims} ${suffix}\n`),
            ESC.BOLD_OFF,
            ESC.FEED_3
        ]);
        await sendToPrinter(data);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Resolve a CYCL_ barcode — server figures out if it's a standard POG+section or location-only
app.get('/api/cyclecount/resolve/:barcode', async (req, res) => {
    try {
        const barcode = req.params.barcode;

        // Try full barcode as a pog_id first (location-only POGs)
        let [pogRows] = await enterprisePool().query('SELECT * FROM planograms WHERE pog_id = ?', [barcode]);
        let pog = pogRows[0];
        let section = null;

        if (!pog) {
            // Not a direct pog_id match — split at last underscore for standard POG+section
            const lastUnder = barcode.lastIndexOf('_');
            if (lastUnder > 0) {
                const possiblePogId = barcode.substring(0, lastUnder);
                section = barcode.substring(lastUnder + 1);
                [pogRows] = await enterprisePool().query('SELECT * FROM planograms WHERE pog_id = ?', [possiblePogId]);
                pog = pogRows[0];
            }
        }

        if (!pog) return res.status(404).json({ success: false, message: `No planogram found for: ${barcode}` });

        const isLocation = pog.pog_type === 'location';
        let items;
        if (isLocation || !section) {
            [items] = await enterprisePool().query(
                `SELECT pi.sku, pi.section, pi.shelf, pi.faces, pi.position, m.name, m.upc
                 FROM planogram_items pi JOIN master_inventory m ON pi.sku = m.sku
                 WHERE pi.planogram_id = ? ORDER BY pi.position`, [pog.id]);
            section = null;
        } else {
            [items] = await enterprisePool().query(
                `SELECT pi.sku, pi.section, pi.shelf, pi.faces, pi.position, m.name, m.upc
                 FROM planogram_items pi JOIN master_inventory m ON pi.sku = m.sku
                 WHERE pi.planogram_id = ? AND pi.section = ? ORDER BY pi.shelf, pi.position`, [pog.id, section]);
        }

        const skus = items.map(i => i.sku);
        const quantities = {};
        if (skus.length > 0) {
            const [invRows] = await req.pool.query(
                `SELECT sku, quantity FROM inventory WHERE sku IN (${skus.map(() => '?').join(',')})`, skus);
            invRows.forEach(r => { quantities[r.sku] = r.quantity; });
        }

        res.json({
            success: true,
            pog_id: pog.pog_id,
            pog_name: pog.name,
            pog_type: pog.pog_type || 'standard',
            section,
            items: items.map(i => ({ ...i, quantity: quantities[i.sku] ?? 0 }))
        });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/cyclecount/section/:pogId/:section', async (req, res) => {
    try {
        const { pogId, section } = req.params;
        const [pogRows] = await enterprisePool().query('SELECT * FROM planograms WHERE pog_id = ?', [pogId]);
        if (pogRows.length === 0) return res.status(404).json({ success: false, message: `POG ${pogId} not found` });
        const pog = pogRows[0];
        const isLocation = pog.pog_type === 'location';

        let items;
        if (isLocation) {
            // Location-only POG — ignore section, return all items
            [items] = await enterprisePool().query(
                `SELECT pi.sku, pi.section, pi.shelf, pi.faces, pi.position, m.name, m.upc
                 FROM planogram_items pi
                 JOIN master_inventory m ON pi.sku = m.sku
                 WHERE pi.planogram_id = ?
                 ORDER BY pi.position`,
                [pog.id]
            );
        } else {
            [items] = await enterprisePool().query(
                `SELECT pi.sku, pi.section, pi.shelf, pi.faces, pi.position, m.name, m.upc
                 FROM planogram_items pi
                 JOIN master_inventory m ON pi.sku = m.sku
                 WHERE pi.planogram_id = ? AND pi.section = ?
                 ORDER BY pi.shelf, pi.position`,
                [pog.id, section]
            );
        }

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
            pog_type: pog.pog_type || 'standard',
            section: isLocation ? null : section,
            items: items.map(i => ({ ...i, quantity: quantities[i.sku] ?? 0 }))
        });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/cyclecount/submit', async (req, res) => {
    const { counts, counted_by } = req.body;
    if (!Array.isArray(counts) || counts.length === 0)
        return res.status(400).json({ success: false, message: 'counts array required' });
    try {
        for (const { sku, counted_qty } of counts) {
            // Get current system quantity before updating
            const [existing] = await req.pool.query('SELECT quantity FROM inventory WHERE sku = ?', [sku]);
            const system_qty = existing.length > 0 ? existing[0].quantity : 0;
            const variance = counted_qty - system_qty;
            // Log the count
            await req.pool.query(
                'INSERT INTO cycle_count_logs (sku, system_qty, counted_qty, variance, counted_by) VALUES (?, ?, ?, ?, ?)',
                [sku, system_qty, counted_qty, variance, counted_by || 'UNKNOWN']);
            // Update inventory
            await req.pool.query('UPDATE inventory SET quantity = ? WHERE sku = ?', [counted_qty, sku]);
        }
        res.json({ success: true, updated: counts.length });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- REPORT ENDPOINTS ---

app.post('/api/reports/price-change', async (req, res) => {
    const { eventId } = req.body;
    try {
        const [eventData] = await enterprisePool().query('SELECT * FROM pricing_events WHERE id = ?', [eventId]);
        if (eventData.length === 0) return res.status(404).json({ success: false, message: 'Event not found.' });
        const [items] = await enterprisePool().query('SELECT * FROM event_items WHERE event_id = ?', [eventId]);
        const skuList = items.map(i => i.sku);
        const placeholders = skuList.map(() => '?').join(',');
        // After a push, inventory.price holds the sale price and the true regular price lives in reg_price.
        // Use COALESCE(reg_price, price) so the "old price" column is correct in DRAFT, PUSHED, and REVERTED states.
        const [invRows] = await req.pool.query(
            `SELECT sku, name, COALESCE(reg_price, price) AS price FROM inventory WHERE sku IN (${placeholders})`,
            skuList
        );
        const oldPriceMap = {};
        invRows.forEach(r => { oldPriceMap[r.sku] = { name: r.name, price: r.price }; });
        const pdfBuffer = await buildPriceChangeReportPDF(eventData[0], items, oldPriceMap, req.storeId);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Price change report sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reports/schedule/weekly', async (req, res) => {
    try {
        const dateStr = req.body.date || new Date().toISOString().slice(0, 10);
        const d = new Date(dateStr + 'T00:00:00');
        const dayOfWeek = d.getDay();
        const diffToMon = (dayOfWeek === 0) ? -6 : 1 - dayOfWeek;
        const monday = new Date(d); monday.setDate(d.getDate() + diffToMon);
        const sunday = new Date(monday); sunday.setDate(monday.getDate() + 6);
        const monStr = monday.toISOString().slice(0, 10);
        const sunStr = sunday.toISOString().slice(0, 10);
        const toDateStr = (v) => {
            if (v instanceof Date) return `${v.getFullYear()}-${String(v.getMonth()+1).padStart(2,'0')}-${String(v.getDate()).padStart(2,'0')}`;
            return String(v).slice(0, 10);
        };

        const [shiftRows] = await req.pool.query(
            `SELECT s.*, u.name AS employee_name FROM shifts s LEFT JOIN users u ON s.eid = u.eid WHERE s.shift_date BETWEEN ? AND ? ORDER BY s.shift_date, s.start_time`, [monStr, sunStr]);
        const shifts = shiftRows.map(s => ({ ...s, shift_date: toDateStr(s.shift_date) }));
        const [employees] = await req.pool.query('SELECT eid, name, role FROM users');
        const pdfBuffer = await buildWeeklySchedulePDF(shifts, employees, req.storeId, monStr, sunStr);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Weekly schedule sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reports/schedule/individual', async (req, res) => {
    const { eid, date } = req.body;
    if (!eid) return res.status(400).json({ success: false, message: 'eid required' });
    try {
        const dateStr = date || new Date().toISOString().slice(0, 10);
        const d = new Date(dateStr + 'T00:00:00');
        const dayOfWeek = d.getDay();
        const diffToMon = (dayOfWeek === 0) ? -6 : 1 - dayOfWeek;
        const monday = new Date(d); monday.setDate(d.getDate() + diffToMon);
        const sunday = new Date(monday); sunday.setDate(monday.getDate() + 6);
        const monStr = monday.toISOString().slice(0, 10);
        const sunStr = sunday.toISOString().slice(0, 10);
        const toDateStr = (v) => {
            if (v instanceof Date) return `${v.getFullYear()}-${String(v.getMonth()+1).padStart(2,'0')}-${String(v.getDate()).padStart(2,'0')}`;
            return String(v).slice(0, 10);
        };

        const [shiftRows] = await req.pool.query(
            'SELECT s.*, u.name AS employee_name FROM shifts s LEFT JOIN users u ON s.eid = u.eid WHERE s.eid = ? AND s.shift_date BETWEEN ? AND ? ORDER BY s.shift_date', [eid, monStr, sunStr]);
        const shifts = shiftRows.map(s => ({ ...s, shift_date: toDateStr(s.shift_date) }));
        const [empRows] = await req.pool.query('SELECT name FROM users WHERE eid = ?', [eid]);
        const empName = empRows.length > 0 ? empRows[0].name : eid;
        const pdfBuffer = await buildIndividualSchedulePDF(empName, eid, shifts, req.storeId, monStr, sunStr);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Individual schedule sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Cycle count schedule CRUD (enterprise DB, bypasses storeContext)
app.get('/api/cyclecount/schedule', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query(
            `SELECT ccs.*, p.pog_id, p.name AS pog_name FROM cycle_count_schedule ccs
             JOIN planograms p ON ccs.planogram_id = p.id ORDER BY ccs.day_of_week, p.name, ccs.section`);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/cyclecount/schedule', async (req, res) => {
    const { planogram_id, section, day_of_week } = req.body;
    if (planogram_id == null || !section || day_of_week == null) return res.status(400).json({ success: false, message: 'planogram_id, section, day_of_week required' });
    try {
        await enterprisePool().query(
            'INSERT INTO cycle_count_schedule (planogram_id, section, day_of_week) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE id=id',
            [planogram_id, section, day_of_week]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/cyclecount/schedule/:id', async (req, res) => {
    try {
        await enterprisePool().query('DELETE FROM cycle_count_schedule WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reports/cyclecount/print', async (req, res) => {
    const storeId = req.body.storeId || req.headers['x-store-id'] || req.session?.currentStoreId;
    if (!storeId) return res.status(400).json({ success: false, message: 'storeId required' });
    try {
        const date = req.body.date || new Date().toISOString().slice(0, 10);
        const dayOfWeek = new Date(date + 'T00:00:00').getDay();
        const [schedule] = await enterprisePool().query(
            `SELECT ccs.*, p.pog_id, p.name AS pog_name FROM cycle_count_schedule ccs
             JOIN planograms p ON ccs.planogram_id = p.id WHERE ccs.day_of_week = ? ORDER BY p.name, ccs.section`, [dayOfWeek]);

        if (schedule.length === 0) return res.json({ success: false, message: 'No sections scheduled for this day.' });

        const pool = await getStorePool(storeId);
        const sections = [];
        for (const entry of schedule) {
            const [pogItems] = await enterprisePool().query(
                `SELECT pi.sku, pi.section, pi.shelf, pi.faces, pi.position, mi.name, mi.upc
                 FROM planogram_items pi JOIN master_inventory mi ON pi.sku = mi.sku
                 WHERE pi.planogram_id = ? AND pi.section = ? ORDER BY pi.shelf, pi.position`, [entry.planogram_id, entry.section]);

            // Get current quantities from store inventory
            for (const item of pogItems) {
                const [inv] = await pool.query('SELECT quantity FROM inventory WHERE sku = ?', [item.sku]);
                item.quantity = inv.length > 0 ? inv[0].quantity : 0;
            }
            sections.push({ pog_name: entry.pog_name, section: entry.section, items: pogItems });
        }

        const pdfBuffer = await buildCycleCountPDF(sections, storeId, date);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: `Cycle count sheets sent to printer (${sections.length} sections).` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reports/daily/opening', async (req, res) => {
    try {
        const date = req.body.date || new Date().toISOString().slice(0, 10);
        const toDateStr = (v) => {
            if (v instanceof Date) return `${v.getFullYear()}-${String(v.getMonth()+1).padStart(2,'0')}-${String(v.getDate()).padStart(2,'0')}`;
            return String(v).slice(0, 10);
        };

        const [empRows] = await req.pool.query(
            `SELECT s.*, u.name AS employee_name FROM shifts s LEFT JOIN users u ON s.eid = u.eid WHERE s.shift_date = ? ORDER BY s.start_time`, [date]);
        const employees = empRows.map(s => ({ ...s, shift_date: toDateStr(s.shift_date) }));

        const [tasks] = await req.pool.query(
            `SELECT t.*, u.name AS assigned_name FROM tasks t LEFT JOIN users u ON t.assigned_eid = u.eid WHERE t.status = 'OPEN' ORDER BY t.priority DESC`);

        const [lowStock] = await req.pool.query(
            `SELECT sku, name, (quantity + quantity_backstock) AS total_qty, reorder_min FROM inventory WHERE auto_reorder_enabled = TRUE AND (quantity + quantity_backstock) < reorder_min ORDER BY (quantity + quantity_backstock) ASC`);

        const [manifests] = await req.pool.query(
            `SELECT * FROM truck_manifests WHERE status = 'PENDING' ORDER BY created_at DESC`);

        const pdfBuffer = await buildOpeningReportPDF({ employees, tasks, lowStock, manifests }, req.storeId, date);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Opening report sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reports/daily/closing', async (req, res) => {
    try {
        const date = req.body.date || new Date().toISOString().slice(0, 10);
        const now = new Date();

        // Employee hours from time punches
        const [punches] = await req.pool.query(
            `SELECT tp.eid, tp.action, tp.timestamp, u.name FROM time_punches tp LEFT JOIN users u ON tp.eid = u.eid WHERE DATE(tp.timestamp) = ? ORDER BY tp.eid, tp.timestamp`, [date]);

        const empMap = {};
        punches.forEach(p => {
            if (!empMap[p.eid]) empMap[p.eid] = { eid: p.eid, name: p.name || p.eid, clockIn: null, clockOut: null, hours: 0, stillClockedIn: false };
            if (p.action === 'CLOCK_IN' && !empMap[p.eid].clockIn) {
                empMap[p.eid].clockIn = new Date(p.timestamp).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
                empMap[p.eid]._inTime = new Date(p.timestamp);
            }
            if (p.action === 'CLOCK_OUT') {
                empMap[p.eid].clockOut = new Date(p.timestamp).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
                empMap[p.eid]._outTime = new Date(p.timestamp);
            }
        });
        const employeeHours = Object.values(empMap).map(e => {
            if (e._inTime && e._outTime) {
                e.hours = (e._outTime - e._inTime) / 3600000;
            } else if (e._inTime && !e._outTime) {
                e.hours = (now - e._inTime) / 3600000;
                e.stillClockedIn = true;
                e.clockOut = 'Now (' + now.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) + ')';
            }
            delete e._inTime; delete e._outTime;
            return e;
        });

        const [trucksReceived] = await req.pool.query(
            `SELECT tm.*, (SELECT COUNT(*) FROM manifest_items WHERE manifest_id = tm.id) AS item_count FROM truck_manifests tm WHERE tm.status = 'COMPLETED' AND DATE(tm.created_at) = ?`, [date]);

        const [completedTasks] = await req.pool.query(
            `SELECT t.*, u.name AS assigned_name FROM tasks t LEFT JOIN users u ON t.assigned_eid = u.eid
             WHERE t.status = 'DONE' AND DATE(t.completed_at) = ? AND t.task_type != 'POG_RESET'`, [date]);

        const [reorders] = await req.pool.query('SELECT * FROM auto_reorders ORDER BY created_at DESC LIMIT 50');

        const pdfBuffer = await buildClosingReportPDF({ employeeHours, trucksReceived, completedTasks, reorders }, req.storeId, date);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Closing report sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- CSV EXPORT ENDPOINTS ---

function escapeCsv(val) {
    if (val == null) return '';
    const s = String(val);
    if (s.includes(',') || s.includes('"') || s.includes('\n')) return '"' + s.replace(/"/g, '""') + '"';
    return s;
}

function csvResponse(res, filename, headers, rows) {
    const lines = [headers.join(',')];
    rows.forEach(r => lines.push(r.map(escapeCsv).join(',')));
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
    res.send(lines.join('\r\n'));
}

app.get('/api/export/inventory', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM inventory ORDER BY department, name');
        const headers = ['SKU','UPC','Name','Department','Floor Qty','Backstock Qty','Pack Size','Price'];
        const data = rows.map(r => [r.sku, r.upc, r.name, r.department, r.quantity, r.quantity_backstock, r.pack_size, r.price]);
        csvResponse(res, `inventory_store${req.storeId}.csv`, headers, data);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/export/reorders', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM auto_reorders ORDER BY created_at DESC');
        const headers = ['SKU','Name','Current Qty','Order Qty','Pack Size','Status','Created'];
        const data = rows.map(r => [r.sku, r.name, r.current_qty, r.order_qty, r.pack_size, r.status, r.created_at]);
        csvResponse(res, `reorders_store${req.storeId}.csv`, headers, data);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/export/manifests', async (req, res) => {
    try {
        const [manifests] = await req.pool.query('SELECT * FROM truck_manifests ORDER BY created_at DESC');
        const headers = ['Manifest #','BOL #','Status','Created'];
        const data = manifests.map(r => [r.manifest_number, r.bol_number, r.status, r.created_at]);
        csvResponse(res, `manifests_store${req.storeId}.csv`, headers, data);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/export/schedule', async (req, res) => {
    try {
        const dateStr = req.query.date || new Date().toISOString().slice(0, 10);
        const d = new Date(dateStr + 'T00:00:00');
        const dayOfWeek = d.getDay();
        const diffToMon = (dayOfWeek === 0) ? -6 : 1 - dayOfWeek;
        const monday = new Date(d); monday.setDate(d.getDate() + diffToMon);
        const sunday = new Date(monday); sunday.setDate(monday.getDate() + 6);
        const monStr = monday.toISOString().slice(0, 10);
        const sunStr = sunday.toISOString().slice(0, 10);
        const [rows] = await req.pool.query(
            `SELECT s.shift_date, s.start_time, s.end_time, u.name AS employee_name, s.eid FROM shifts s LEFT JOIN users u ON s.eid = u.eid WHERE s.shift_date BETWEEN ? AND ? ORDER BY s.shift_date, s.start_time`, [monStr, sunStr]);
        const headers = ['Date','Employee','EID','Start','End'];
        const toDateStr = (v) => v instanceof Date ? v.toISOString().slice(0,10) : String(v).slice(0,10);
        const data = rows.map(r => [toDateStr(r.shift_date), r.employee_name || r.eid, r.eid, r.start_time, r.end_time]);
        csvResponse(res, `schedule_${monStr}_to_${sunStr}.csv`, headers, data);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- SHRINK / VARIANCE REPORT ---

function buildShrinkReportPDF(logs, storeId, date) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);
        const pageBottom = 732 - 40;

        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('SHRINK / VARIANCE REPORT', { align: 'center' });
        doc.fontSize(10).font('Helvetica').text(date, { align: 'center' });
        doc.moveDown(0.5);

        // Summary stats
        const totalItems = logs.length;
        const totalVariance = logs.reduce((s, l) => s + (l.counted_qty - l.system_qty), 0);
        const shrinkItems = logs.filter(l => l.counted_qty < l.system_qty);
        const overItems = logs.filter(l => l.counted_qty > l.system_qty);
        doc.fontSize(9).font('Helvetica');
        doc.text(`Total Items Counted: ${totalItems}    |    Net Variance: ${totalVariance >= 0 ? '+' : ''}${totalVariance}    |    Shrink Items: ${shrinkItems.length}    |    Over Items: ${overItems.length}`, { align: 'center' });
        doc.moveDown(0.5);

        // Table header
        const drawHeader = () => {
            doc.fontSize(8).font('Helvetica-Bold');
            const hY = doc.y;
            doc.text('SKU', 50, hY, { width: 80 });
            doc.text('Item Name', 135, hY, { width: 160 });
            doc.text('System', 300, hY, { width: 50, align: 'right' });
            doc.text('Counted', 355, hY, { width: 50, align: 'right' });
            doc.text('Variance', 410, hY, { width: 50, align: 'right' });
            doc.text('Counted By', 470, hY, { width: 80 });
            doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);
            doc.font('Helvetica').fontSize(8);
        };

        drawHeader();

        const rowHeight = 13;
        logs.forEach(log => {
            if (doc.y > pageBottom - rowHeight) { doc.addPage(); doc.x = 50; drawHeader(); }
            const rY = doc.y;
            const variance = log.counted_qty - log.system_qty;
            doc.text(log.sku || '', 50, rY, { width: 80 });
            doc.text((log.name || '').substring(0, 22), 135, rY, { width: 160 });
            doc.text(String(log.system_qty), 300, rY, { width: 50, align: 'right' });
            doc.text(String(log.counted_qty), 355, rY, { width: 50, align: 'right' });
            // Flag large variances with asterisk
            const vStr = (variance >= 0 ? '+' : '') + variance;
            doc.text(Math.abs(variance) >= 5 ? vStr + ' ***' : vStr, 410, rY, { width: 50, align: 'right' });
            doc.text(log.counted_by || 'N/A', 470, rY, { width: 80 });
            doc.y = rY + rowHeight;
        });

        if (logs.length === 0) {
            doc.fontSize(11).font('Helvetica').text('No cycle count logs found for this date.', { align: 'center' });
        }

        doc.moveDown(1);
        doc.fontSize(7).font('Helvetica').text('*** = variance of 5+ units — investigate immediately', { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(8).text(`Generated: ${new Date().toLocaleString()}`, { align: 'center' });
        doc.end();
    });
}

app.post('/api/reports/shrink', async (req, res) => {
    const date = req.body.date || new Date().toISOString().slice(0, 10);
    try {
        const [logs] = await req.pool.query(
            `SELECT cl.*, i.name FROM cycle_count_logs cl LEFT JOIN inventory i ON cl.sku = i.sku WHERE DATE(cl.counted_at) = ? ORDER BY cl.sku`, [date]);
        const pdfBuffer = await buildShrinkReportPDF(logs, req.storeId, date);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: 'Shrink report sent to printer.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- DEPARTMENT INVENTORY REPORT ---

function buildDepartmentReportPDF(items, department, storeId) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);
        const pageBottom = 732 - 40;

        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text(`DEPARTMENT INVENTORY: ${department.toUpperCase()}`, { align: 'center' });
        doc.fontSize(10).font('Helvetica').text(new Date().toISOString().slice(0, 10), { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(9).text(`Total SKUs: ${items.length}    |    Total Floor Units: ${items.reduce((s,i) => s + (i.quantity || 0), 0)}    |    Total Backstock: ${items.reduce((s,i) => s + (i.quantity_backstock || 0), 0)}`, { align: 'center' });
        doc.moveDown(0.5);

        const drawHeader = () => {
            doc.fontSize(8).font('Helvetica-Bold');
            const hY = doc.y;
            doc.text('SKU', 50, hY, { width: 70 });
            doc.text('Item Name', 125, hY, { width: 170 });
            doc.text('Floor', 300, hY, { width: 40, align: 'right' });
            doc.text('Back', 345, hY, { width: 40, align: 'right' });
            doc.text('Pack', 390, hY, { width: 35, align: 'right' });
            doc.text('Price', 430, hY, { width: 50, align: 'right' });
            doc.text('Location', 490, hY, { width: 70 });
            doc.moveDown(0.2); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.2);
            doc.font('Helvetica').fontSize(8);
        };

        drawHeader();

        const rowHeight = 13;
        items.forEach(item => {
            if (doc.y > pageBottom - rowHeight) { doc.addPage(); doc.x = 50; drawHeader(); }
            const rY = doc.y;
            doc.text(item.sku || '', 50, rY, { width: 70 });
            doc.text((item.name || '').substring(0, 25), 125, rY, { width: 170 });
            doc.text(String(item.quantity || 0), 300, rY, { width: 40, align: 'right' });
            doc.text(String(item.quantity_backstock || 0), 345, rY, { width: 40, align: 'right' });
            doc.text(String(item.pack_size || 6), 390, rY, { width: 35, align: 'right' });
            doc.text(item.price != null ? '$' + Number(item.price).toFixed(2) : '', 430, rY, { width: 50, align: 'right' });
            doc.text(item.location || '', 490, rY, { width: 70 });
            doc.y = rY + rowHeight;
        });

        if (items.length === 0) {
            doc.fontSize(11).font('Helvetica').text(`No items found in department "${department}".`, { align: 'center' });
        }

        doc.moveDown(1);
        doc.fontSize(8).font('Helvetica').text(`Generated: ${new Date().toLocaleString()}`, { align: 'center' });
        doc.end();
    });
}

app.post('/api/reports/department', async (req, res) => {
    const { department } = req.body;
    if (!department) return res.status(400).json({ success: false, message: 'department required' });
    try {
        const [items] = await req.pool.query('SELECT * FROM inventory WHERE department = ? ORDER BY name', [department]);
        const pdfBuffer = await buildDepartmentReportPDF(items, department, req.storeId);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: `Department report for "${department}" sent to printer.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- AUTO-REORDER SYSTEM ---

async function checkAutoReorders(storeId, pool) {
    const [lowStock] = await pool.query(`
        SELECT sku, name, (quantity + quantity_backstock) AS total_qty, reorder_min, reorder_max, pack_size
        FROM inventory
        WHERE auto_reorder_enabled = TRUE AND (quantity + quantity_backstock) < reorder_min
    `);
    if (lowStock.length === 0) return 0;

    const MAX_REORDERS_PER_CYCLE = 50;
    let newOrders = 0;
    for (const item of lowStock) {
        if (newOrders >= MAX_REORDERS_PER_CYCLE) break;

        // Skip if a PENDING reorder already exists for this SKU
        const [existing] = await pool.query('SELECT id FROM auto_reorders WHERE sku = ? AND status = ?', [item.sku, 'PENDING']);
        if (existing.length > 0) continue;

        const deficit = item.reorder_max - item.total_qty;
        const packSize = item.pack_size || 6;
        const orderQty = Math.ceil(deficit / packSize) * packSize;

        await pool.query(
            'INSERT INTO auto_reorders (sku, name, current_qty, order_qty, pack_size) VALUES (?, ?, ?, ?, ?)',
            [item.sku, item.name, item.total_qty, orderQty, packSize]
        );
        newOrders++;
    }

    if (newOrders > 0) {
        const summary = lowStock.slice(0, 10).map(i => `${i.name} (${i.sku})`).join(', ');
        const more = lowStock.length > 10 ? ` and ${lowStock.length - 10} more` : '';
        await enterprisePool().query(
            'INSERT INTO enterprise_messages (sender_eid, subject, body, target_type, target_id) VALUES (?, ?, ?, ?, ?)',
            ['SYSTEM', `Auto-Reorder: ${newOrders} items for Store ${storeId}`,
             `Low stock detected. Reorders created for: ${summary}${more}`,
             'STORE', storeId]
        );
    }
    return newOrders;
}

app.get('/api/reorders', async (req, res) => {
    const page = Math.max(1, parseInt(req.query.page) || 1);
    const limit = Math.min(100, Math.max(1, parseInt(req.query.limit) || 25));
    const offset = (page - 1) * limit;
    const status = req.query.status;
    try {
        let where = '';
        const params = [];
        if (status) { where = 'WHERE status = ?'; params.push(status.toUpperCase()); }
        const [[{ total }]] = await req.pool.query(`SELECT COUNT(*) AS total FROM auto_reorders ${where}`, params);
        const [rows] = await req.pool.query(`SELECT * FROM auto_reorders ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`, [...params, limit, offset]);
        res.json({ rows, page, limit, total, totalPages: Math.ceil(total / limit) });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/reorders/:id', async (req, res) => {
    try {
        await req.pool.query('DELETE FROM auto_reorders WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reorders/:id/status', async (req, res) => {
    const { status } = req.body;
    if (!['ORDERED', 'RECEIVED', 'CANCELLED'].includes(status))
        return res.status(400).json({ success: false, message: 'Invalid status' });
    try {
        await req.pool.query('UPDATE auto_reorders SET status = ? WHERE id = ?', [status, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/inventory/local/reorder-settings', async (req, res) => {
    const { sku, reorder_min, reorder_max, auto_reorder_enabled } = req.body;
    if (!sku) return res.status(400).json({ success: false, message: 'SKU required' });
    try {
        await req.pool.query(
            'UPDATE inventory SET reorder_min = ?, reorder_max = ?, auto_reorder_enabled = ? WHERE sku = ?',
            [reorder_min ?? 5, reorder_max ?? 10, auto_reorder_enabled ?? true, sku]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reorders/check-now', async (req, res) => {
    try {
        const count = await checkAutoReorders(req.storeId, req.pool);
        res.json({ success: true, new_reorders: count });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/reorders/generate-manifest', async (req, res) => {
    try {
        const [pending] = await req.pool.query('SELECT * FROM auto_reorders WHERE status = ?', ['PENDING']);
        if (pending.length === 0) return res.json({ success: false, message: 'No pending reorders to fulfill.' });

        const manifest_number = 'REORD-' + Date.now();
        const bol_number = '1' + Math.floor(Math.random() * 100000000000000).toString().padStart(14, '0');

        const [result] = await req.pool.query('INSERT INTO truck_manifests (manifest_number, bol_number) VALUES (?, ?)', [manifest_number, bol_number]);
        const manifestId = result.insertId;

        for (const r of pending) {
            const packSize = (await req.pool.query('SELECT pack_size FROM inventory WHERE sku = ?', [r.sku]))[0][0]?.pack_size || 6;
            const expectedPacks = Math.ceil(r.order_qty / packSize);
            await req.pool.query('INSERT INTO manifest_items (manifest_id, sku, expected_packs) VALUES (?, ?, ?)', [manifestId, r.sku, expectedPacks]);
            await req.pool.query('DELETE FROM auto_reorders WHERE id = ?', [r.id]);
        }

        res.json({ success: true, manifest_number, bol_number, items_count: pending.length, manifest_id: manifestId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- PRP (PRODUCT RETURN PROCESS) ---
// Defective / recall / vendor-return items bundled for vendor pickup.
// Adding an item decrements inventory; removing credits back. Closing locks the batch.
const PRP_REASON_CODES = ['DEFECTIVE','RECALL','EXPIRED','MFG_DEFECT','VENDOR_RETURN','CUSTOMER_RETURN'];

function buildPrpManifestPDF(batch, items, storeId) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);

        const pageW = 612 - 100;
        const pageBottom = 732 - 40;
        const created = batch.created_at ? new Date(batch.created_at).toLocaleString() : '';
        const closed = batch.closed_at ? new Date(batch.closed_at).toLocaleString() : '—';
        const totalUnits = items.reduce((s, i) => s + Number(i.quantity || 0), 0);

        // Header
        doc.fontSize(18).font('Helvetica-Bold').text('DOLLAR GENERAL', { align: 'center' });
        doc.fontSize(12).text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(14).text('PRP RETURN MANIFEST', { align: 'center' });
        doc.moveDown(0.4);

        // Batch barcode (Code 128 of batch id)
        try {
            const canvas = createCanvas();
            JsBarcode(canvas, `PRP-${batch.id}`, { format: 'CODE128', width: 2, height: 45, displayValue: true, fontSize: 12, margin: 5 });
            const png = canvas.toBuffer('image/png');
            const imgWidth = 220;
            doc.image(png, (612 - imgWidth) / 2, doc.y, { width: imgWidth });
            doc.y += 65;
        } catch (_) {}
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.4);

        // Batch info
        doc.fontSize(10).font('Helvetica-Bold').text('Batch #:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${batch.id}`);
        doc.font('Helvetica-Bold').text('Status:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${batch.status}`);
        doc.font('Helvetica-Bold').text('Vendor:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${batch.vendor || '—'}`);
        doc.font('Helvetica-Bold').text('Carrier:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${batch.carrier || '—'}`);
        doc.font('Helvetica-Bold').text('Tracking #:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${batch.tracking_number || '—'}`);
        doc.font('Helvetica-Bold').text('Opened:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${created}`);
        doc.font('Helvetica-Bold').text('Closed:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${closed}`);
        if (batch.notes) {
            doc.font('Helvetica-Bold').text('Notes:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${batch.notes}`);
        }
        doc.moveDown(0.5);

        // Items table — explicit row height so columns can't collide regardless of wrapping
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);
        const COL = { sku: 50, name: 125, qty: 340, reason: 385, notes: 470 };
        const W = { sku: 70, name: 210, qty: 40, reason: 80, notes: 92 };
        const ROW_H = 16; // points per row — safely larger than 10pt text
        function drawItemsHeader() {
            doc.fontSize(9).font('Helvetica-Bold');
            const y = doc.y;
            doc.text('SKU',      COL.sku,    y, { width: W.sku });
            doc.text('Item Name',COL.name,   y, { width: W.name });
            doc.text('Qty',      COL.qty,    y, { width: W.qty, align: 'right' });
            doc.text('Reason',   COL.reason, y, { width: W.reason });
            doc.text('Notes',    COL.notes,  y, { width: W.notes });
            doc.y = y + 14;
            doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
            doc.moveDown(0.15);
        }
        drawItemsHeader();
        doc.font('Helvetica').fontSize(9);
        items.forEach(it => {
            if (doc.y + ROW_H > pageBottom) {
                doc.addPage(); doc.x = 50; drawItemsHeader(); doc.font('Helvetica').fontSize(9);
            }
            const y = doc.y;
            doc.text(it.sku || '',                        COL.sku,    y, { width: W.sku,    lineBreak: false });
            doc.text((it.name || '').substring(0, 38),    COL.name,   y, { width: W.name,   lineBreak: false, ellipsis: true });
            doc.text(String(it.quantity),                 COL.qty,    y, { width: W.qty,    align: 'right', lineBreak: false });
            doc.text(it.reason_code || '',                COL.reason, y, { width: W.reason, lineBreak: false });
            doc.text((it.notes || '').substring(0, 22),   COL.notes,  y, { width: W.notes,  lineBreak: false, ellipsis: true });
            doc.y = y + ROW_H; // force consistent spacing
        });

        // Totals
        doc.moveDown(0.2);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);
        doc.fontSize(9).font('Helvetica-Bold');
        doc.text(`TOTAL LINES: ${items.length}`, 50, doc.y, { continued: true });
        doc.text(`     TOTAL UNITS: ${totalUnits}`);
        doc.moveDown(1);

        // Signatures
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('SIGNATURES', 50, doc.y, { width: pageW, align: 'center' });
        doc.moveDown(0.8);
        doc.fontSize(10).font('Helvetica');
        doc.text('Store Manager: ___________________________________   Date: _______________', 50);
        doc.moveDown(0.8);
        doc.text('Driver / Carrier: __________________________________   Date: _______________', 50);
        doc.moveDown(0.8);
        doc.text('Driver License / ID #: ______________________________________________________', 50);
        doc.moveDown(1);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);
        doc.fontSize(8).font('Helvetica-Bold').text('RETAIN A COPY FOR STORE RECORDS', 50, doc.y, { width: pageW, align: 'center' });

        doc.end();
    });
}

// List all batches for current store with item counts
app.get('/api/prp/batches', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT b.*, u1.name AS opened_by_name, u2.name AS closed_by_name,
                    (SELECT COUNT(*) FROM prp_batch_items WHERE batch_id = b.id) AS item_count,
                    (SELECT COALESCE(SUM(quantity),0) FROM prp_batch_items WHERE batch_id = b.id) AS unit_count
             FROM prp_batches b
             LEFT JOIN users u1 ON b.opened_by_eid = u1.eid
             LEFT JOIN users u2 ON b.closed_by_eid = u2.eid
             ORDER BY b.created_at DESC LIMIT 200`);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Batch detail + items
app.get('/api/prp/batches/:id', async (req, res) => {
    try {
        const [batches] = await req.pool.query(
            `SELECT b.*, u1.name AS opened_by_name, u2.name AS closed_by_name
             FROM prp_batches b
             LEFT JOIN users u1 ON b.opened_by_eid = u1.eid
             LEFT JOIN users u2 ON b.closed_by_eid = u2.eid
             WHERE b.id = ?`, [req.params.id]);
        if (batches.length === 0) return res.status(404).json({ success: false, message: 'Batch not found.' });
        const [items] = await req.pool.query(
            `SELECT it.*, i.name, u.name AS scanned_by_name
             FROM prp_batch_items it
             LEFT JOIN inventory i ON it.sku = i.sku
             LEFT JOIN users u ON it.scanned_by_eid = u.eid
             WHERE it.batch_id = ? ORDER BY it.id ASC`, [req.params.id]);
        res.json({ success: true, batch: batches[0], items });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Create a new OPEN batch. Refuses if one is already open.
app.post('/api/prp/batches', async (req, res) => {
    const { vendor, notes, eid } = req.body || {};
    try {
        const [open] = await req.pool.query(`SELECT id FROM prp_batches WHERE status = 'OPEN' LIMIT 1`);
        if (open.length > 0) {
            return res.status(409).json({ success: false, message: `Batch #${open[0].id} is already open. Close it first.`, batch_id: open[0].id });
        }
        const [r] = await req.pool.query(
            'INSERT INTO prp_batches (vendor, notes, opened_by_eid) VALUES (?, ?, ?)',
            [vendor || null, notes || null, eid || null]
        );
        res.json({ success: true, id: r.insertId, message: `PRP batch #${r.insertId} opened.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Add an item to a batch. Looks up by sku or upc, decrements inventory.
app.post('/api/prp/batches/:id/item', async (req, res) => {
    const batchId = Number(req.params.id);
    const { sku, quantity, reason_code, notes, eid } = req.body || {};
    const qty = Number(quantity);
    if (!sku || !reason_code || !qty || qty <= 0) {
        return res.status(400).json({ success: false, message: 'sku, positive quantity, and reason_code required.' });
    }
    if (!PRP_REASON_CODES.includes(reason_code)) {
        return res.status(400).json({ success: false, message: 'Invalid reason_code.' });
    }
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        const [batches] = await conn.query('SELECT status FROM prp_batches WHERE id = ?', [batchId]);
        if (batches.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Batch not found.' }); }
        if (batches[0].status !== 'OPEN') { await conn.rollback(); return res.status(400).json({ success: false, message: `Batch is ${batches[0].status}; cannot add items.` }); }
        const [items] = await conn.query('SELECT sku, name, quantity FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (items.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: `Item ${sku} not in local inventory.` }); }
        const item = items[0];
        await conn.query('UPDATE inventory SET quantity = GREATEST(0, quantity - ?) WHERE sku = ?', [qty, item.sku]);
        const [r] = await conn.query(
            'INSERT INTO prp_batch_items (batch_id, sku, quantity, reason_code, notes, scanned_by_eid) VALUES (?, ?, ?, ?, ?, ?)',
            [batchId, item.sku, qty, reason_code, notes || null, eid || null]
        );
        await conn.commit();
        const [after] = await req.pool.query('SELECT quantity FROM inventory WHERE sku = ?', [item.sku]);
        res.json({
            success: true,
            id: r.insertId,
            sku: item.sku,
            name: item.name,
            new_quantity: after[0].quantity,
            message: `Added ${qty} × ${item.name} to batch #${batchId}.`
        });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally { conn.release(); }
});

// Remove an item from a batch. Credits inventory back.
app.delete('/api/prp/batches/:id/item/:itemId', async (req, res) => {
    const batchId = Number(req.params.id);
    const itemId = Number(req.params.itemId);
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        const [batches] = await conn.query('SELECT status FROM prp_batches WHERE id = ?', [batchId]);
        if (batches.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Batch not found.' }); }
        if (batches[0].status !== 'OPEN') { await conn.rollback(); return res.status(400).json({ success: false, message: `Batch is ${batches[0].status}; cannot modify items.` }); }
        const [rows] = await conn.query('SELECT sku, quantity FROM prp_batch_items WHERE id = ? AND batch_id = ?', [itemId, batchId]);
        if (rows.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Item not found on this batch.' }); }
        const it = rows[0];
        await conn.query('UPDATE inventory SET quantity = quantity + ? WHERE sku = ?', [it.quantity, it.sku]);
        await conn.query('DELETE FROM prp_batch_items WHERE id = ?', [itemId]);
        await conn.commit();
        res.json({ success: true, message: `Removed item; ${it.quantity} × ${it.sku} credited back to inventory.` });
    } catch (err) {
        await conn.rollback();
        res.status(500).json({ success: false, message: err.message });
    } finally { conn.release(); }
});

// Close a batch (locks it).
app.post('/api/prp/batches/:id/close', async (req, res) => {
    const batchId = Number(req.params.id);
    const { eid } = req.body || {};
    try {
        const [batches] = await req.pool.query('SELECT status FROM prp_batches WHERE id = ?', [batchId]);
        if (batches.length === 0) return res.status(404).json({ success: false, message: 'Batch not found.' });
        if (batches[0].status !== 'OPEN') return res.status(400).json({ success: false, message: `Batch already ${batches[0].status}.` });
        const [items] = await req.pool.query('SELECT COUNT(*) AS n FROM prp_batch_items WHERE batch_id = ?', [batchId]);
        if (items[0].n === 0) return res.status(400).json({ success: false, message: 'Cannot close an empty batch. Add items or cancel it.' });
        await req.pool.query(
            `UPDATE prp_batches SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, closed_by_eid = ? WHERE id = ?`,
            [eid || null, batchId]
        );
        res.json({ success: true, message: `Batch #${batchId} closed.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Mark shipped (vendor pickup).
app.post('/api/prp/batches/:id/ship', async (req, res) => {
    const batchId = Number(req.params.id);
    const { carrier, tracking_number } = req.body || {};
    try {
        const [batches] = await req.pool.query('SELECT status FROM prp_batches WHERE id = ?', [batchId]);
        if (batches.length === 0) return res.status(404).json({ success: false, message: 'Batch not found.' });
        if (batches[0].status !== 'CLOSED') return res.status(400).json({ success: false, message: `Batch must be CLOSED before shipping (currently ${batches[0].status}).` });
        await req.pool.query(
            `UPDATE prp_batches SET status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP, carrier = ?, tracking_number = ? WHERE id = ?`,
            [carrier || null, tracking_number || null, batchId]
        );
        res.json({ success: true, message: `Batch #${batchId} marked shipped.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Print manifest PDF to the laser printer.
app.post('/api/prp/batches/:id/print', async (req, res) => {
    const batchId = Number(req.params.id);
    try {
        const [batches] = await req.pool.query('SELECT * FROM prp_batches WHERE id = ?', [batchId]);
        if (batches.length === 0) return res.status(404).json({ success: false, message: 'Batch not found.' });
        const [items] = await req.pool.query(
            `SELECT it.*, i.name FROM prp_batch_items it
             LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.batch_id = ? ORDER BY it.id ASC`, [batchId]);
        const pdfBuffer = await buildPrpManifestPDF(batches[0], items, req.storeId);
        await sendToLaserPrinter(pdfBuffer);
        res.json({ success: true, message: `PRP manifest for batch #${batchId} sent to printer.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- VENDORS (enterprise-level master data, pushed/read by stores) ---
app.get('/api/vendors', async (req, res) => {
    try {
        const [rows] = await enterprisePool().query(
            `SELECT v.*, (SELECT COUNT(*) FROM master_inventory WHERE vendor_id = v.id) AS sku_count
             FROM vendors v ORDER BY v.active DESC, v.name ASC`);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendors', async (req, res) => {
    const { code, name, contact_name, contact_phone, contact_email, delivery_schedule, notes } = req.body || {};
    if (!code || !name) return res.status(400).json({ success: false, message: 'code and name required.' });
    try {
        const [r] = await enterprisePool().query(
            'INSERT INTO vendors (code, name, contact_name, contact_phone, contact_email, delivery_schedule, notes) VALUES (?, ?, ?, ?, ?, ?, ?)',
            [code.trim().toUpperCase(), name, contact_name || null, contact_phone || null, contact_email || null, delivery_schedule || null, notes || null]
        );
        res.json({ success: true, id: r.insertId });
    } catch (err) {
        if (err.code === 'ER_DUP_ENTRY') return res.status(409).json({ success: false, message: `Vendor code "${code}" already exists.` });
        res.status(500).json({ success: false, message: err.message });
    }
});

// Tag a master SKU with a vendor (or NULL to untag)
// Must be registered BEFORE `/api/vendors/:id` — otherwise the :id route matches "master-tag" first and shadows this handler.
app.put('/api/vendors/master-tag', async (req, res) => {
    const { sku, vendor_id } = req.body || {};
    if (!sku) return res.status(400).json({ success: false, message: 'sku required.' });
    try {
        const [upd] = await enterprisePool().query('UPDATE master_inventory SET vendor_id = ? WHERE sku = ?', [vendor_id || null, sku]);
        res.json({ success: true, affectedRows: upd.affectedRows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/vendors/:id', async (req, res) => {
    const { code, name, contact_name, contact_phone, contact_email, delivery_schedule, notes, active } = req.body || {};
    try {
        await enterprisePool().query(
            `UPDATE vendors SET code = ?, name = ?, contact_name = ?, contact_phone = ?, contact_email = ?,
                                delivery_schedule = ?, notes = ?, active = ? WHERE id = ?`,
            [code, name, contact_name || null, contact_phone || null, contact_email || null,
             delivery_schedule || null, notes || null, active ? 1 : 0, req.params.id]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/vendors/:id', async (req, res) => {
    try {
        // Soft delete — deactivate so historic returns/orders keep their FK intact
        await enterprisePool().query('UPDATE vendors SET active = 0 WHERE id = ?', [req.params.id]);
        res.json({ success: true, message: 'Vendor deactivated.' });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- VENDOR RETURNS (DG Respond — vendor-in-store returns; no shipping) ---
// Like PRP: add decrements inventory, remove credits back, close locks the row.
const VENDOR_RETURN_REASONS = ['DEFECTIVE','EXPIRED','MFG_DEFECT','STALE','DAMAGED_PKG','RECALL'];

app.get('/api/vendor-returns', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT vr.*,
                    (SELECT COUNT(*) FROM vendor_return_items WHERE return_id = vr.id) AS item_count,
                    (SELECT COALESCE(SUM(quantity),0) FROM vendor_return_items WHERE return_id = vr.id) AS unit_count
             FROM vendor_returns vr
             ORDER BY vr.created_at DESC LIMIT 200`);
        // Enrich with vendor name (vendors table lives in enterprise DB)
        const vendorIds = [...new Set(rows.map(r => r.vendor_id).filter(Boolean))];
        let vendorMap = {};
        if (vendorIds.length) {
            const [vs] = await enterprisePool().query(`SELECT id, code, name FROM vendors WHERE id IN (?)`, [vendorIds]);
            vendorMap = Object.fromEntries(vs.map(v => [v.id, v]));
        }
        res.json(rows.map(r => ({ ...r, vendor_code: vendorMap[r.vendor_id]?.code, vendor_name: vendorMap[r.vendor_id]?.name })));
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/vendor-returns/:id', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_returns WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Return not found.' });
        const [items] = await req.pool.query(
            `SELECT it.*, i.name FROM vendor_return_items it LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.return_id = ? ORDER BY it.id ASC`, [req.params.id]);
        const [[vendor]] = await enterprisePool().query('SELECT * FROM vendors WHERE id = ?', [rows[0].vendor_id]);
        res.json({ success: true, return: rows[0], vendor: vendor || null, items });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-returns', async (req, res) => {
    const { vendor_id, rep_name, notes, credit_memo_number, eid } = req.body || {};
    if (!vendor_id) return res.status(400).json({ success: false, message: 'vendor_id required.' });
    try {
        const [r] = await req.pool.query(
            'INSERT INTO vendor_returns (vendor_id, rep_name, notes, credit_memo_number, opened_by_eid) VALUES (?, ?, ?, ?, ?)',
            [vendor_id, rep_name || null, notes || null, credit_memo_number || null, eid || null]
        );
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-returns/:id/item', async (req, res) => {
    const returnId = Number(req.params.id);
    const { sku, quantity, reason_code, notes, eid } = req.body || {};
    const qty = Number(quantity);
    if (!sku || !reason_code || !qty || qty <= 0) return res.status(400).json({ success: false, message: 'sku, positive quantity, and reason_code required.' });
    if (!VENDOR_RETURN_REASONS.includes(reason_code)) return res.status(400).json({ success: false, message: 'Invalid reason_code.' });
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        const [vr] = await conn.query('SELECT status FROM vendor_returns WHERE id = ?', [returnId]);
        if (vr.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Return not found.' }); }
        if (vr[0].status !== 'OPEN') { await conn.rollback(); return res.status(400).json({ success: false, message: `Return is ${vr[0].status}.` }); }
        const [items] = await conn.query('SELECT sku, name, quantity FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (items.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: `Item ${sku} not in local inventory.` }); }
        const item = items[0];
        await conn.query('UPDATE inventory SET quantity = GREATEST(0, quantity - ?) WHERE sku = ?', [qty, item.sku]);
        const [r] = await conn.query(
            'INSERT INTO vendor_return_items (return_id, sku, quantity, reason_code, notes, scanned_by_eid) VALUES (?, ?, ?, ?, ?, ?)',
            [returnId, item.sku, qty, reason_code, notes || null, eid || null]
        );
        await conn.commit();
        const [after] = await req.pool.query('SELECT quantity FROM inventory WHERE sku = ?', [item.sku]);
        res.json({ success: true, id: r.insertId, sku: item.sku, name: item.name, new_quantity: after[0].quantity });
    } catch (err) { await conn.rollback(); res.status(500).json({ success: false, message: err.message }); }
    finally { conn.release(); }
});

app.delete('/api/vendor-returns/:id/item/:itemId', async (req, res) => {
    const returnId = Number(req.params.id), itemId = Number(req.params.itemId);
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        const [vr] = await conn.query('SELECT status FROM vendor_returns WHERE id = ?', [returnId]);
        if (vr.length === 0 || vr[0].status !== 'OPEN') { await conn.rollback(); return res.status(400).json({ success: false, message: 'Return not open.' }); }
        const [rows] = await conn.query('SELECT sku, quantity FROM vendor_return_items WHERE id = ? AND return_id = ?', [itemId, returnId]);
        if (rows.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Item not found.' }); }
        await conn.query('UPDATE inventory SET quantity = quantity + ? WHERE sku = ?', [rows[0].quantity, rows[0].sku]);
        await conn.query('DELETE FROM vendor_return_items WHERE id = ?', [itemId]);
        await conn.commit();
        res.json({ success: true });
    } catch (err) { await conn.rollback(); res.status(500).json({ success: false, message: err.message }); }
    finally { conn.release(); }
});

// Cancel a vendor return — credits every item's qty back to inventory, then flips status.
// Only valid while OPEN. Differs from `close` which keeps inventory decremented.
app.post('/api/vendor-returns/:id/cancel', async (req, res) => {
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        const [rows] = await conn.query('SELECT status FROM vendor_returns WHERE id = ?', [req.params.id]);
        if (rows.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Return not found.' }); }
        if (rows[0].status !== 'OPEN') { await conn.rollback(); return res.status(400).json({ success: false, message: `Return is ${rows[0].status}; only OPEN returns can be cancelled.` }); }
        const [items] = await conn.query('SELECT sku, quantity FROM vendor_return_items WHERE return_id = ?', [req.params.id]);
        for (const it of items) {
            await conn.query('UPDATE inventory SET quantity = quantity + ? WHERE sku = ?', [it.quantity, it.sku]);
        }
        await conn.query(`UPDATE vendor_returns SET status = 'CANCELLED', closed_at = CURRENT_TIMESTAMP WHERE id = ?`, [req.params.id]);
        await conn.commit();
        res.json({ success: true, credited_back: items.length });
    } catch (err) { await conn.rollback(); res.status(500).json({ success: false, message: err.message }); }
    finally { conn.release(); }
});

app.post('/api/vendor-returns/:id/close', async (req, res) => {
    const { eid, credit_memo_number } = req.body || {};
    try {
        const [rows] = await req.pool.query('SELECT status FROM vendor_returns WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Return not found.' });
        if (rows[0].status !== 'OPEN') return res.status(400).json({ success: false, message: `Already ${rows[0].status}.` });
        const [items] = await req.pool.query('SELECT COUNT(*) AS n FROM vendor_return_items WHERE return_id = ?', [req.params.id]);
        if (items[0].n === 0) return res.status(400).json({ success: false, message: 'Cannot close an empty return.' });
        await req.pool.query(
            `UPDATE vendor_returns SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP, closed_by_eid = ?,
                                        credit_memo_number = COALESCE(?, credit_memo_number) WHERE id = ?`,
            [eid || null, credit_memo_number || null, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Credit memo PDF
async function buildVendorReturnPDF(ret, vendor, items, storeId) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c)); doc.on('end', () => resolve(Buffer.concat(chunks))); doc.on('error', reject);
        const pageW = 612 - 100;
        doc.fontSize(18).font('Helvetica-Bold').text('VENDOR CREDIT MEMO', { align: 'center' });
        doc.fontSize(11).font('Helvetica').text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.4);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.4);
        doc.fontSize(10).font('Helvetica-Bold').text('Vendor:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${vendor?.name || '—'} (${vendor?.code || '—'})`);
        doc.font('Helvetica-Bold').text('Return #:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${ret.id}`);
        doc.font('Helvetica-Bold').text('Rep:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${ret.rep_name || '—'}`);
        doc.font('Helvetica-Bold').text('Credit Memo #:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${ret.credit_memo_number || '—'}`);
        doc.font('Helvetica-Bold').text('Date:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${new Date(ret.created_at).toLocaleString()}`);
        if (ret.notes) { doc.font('Helvetica-Bold').text('Notes:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${ret.notes}`); }
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.3);
        doc.fontSize(9).font('Helvetica-Bold');
        const hY = doc.y;
        doc.text('SKU',    50,  hY, { width: 80 });
        doc.text('Item',   135, hY, { width: 250 });
        doc.text('Qty',    390, hY, { width: 40, align: 'right' });
        doc.text('Reason', 435, hY, { width: 120 });
        doc.y = hY + 14;
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.15);
        doc.font('Helvetica').fontSize(9);
        const VR_ROW_H = 16;
        items.forEach(it => {
            const y = doc.y;
            doc.text(it.sku,                              50,  y, { width: 80,  lineBreak: false });
            doc.text((it.name || '').substring(0, 42),    135, y, { width: 250, lineBreak: false, ellipsis: true });
            doc.text(String(it.quantity),                 390, y, { width: 40,  align: 'right', lineBreak: false });
            doc.text(it.reason_code,                      435, y, { width: 120, lineBreak: false });
            doc.y = y + VR_ROW_H;
        });
        doc.moveDown(0.5);
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.5);
        doc.fontSize(12).font('Helvetica-Bold').text('SIGNATURES', 50, doc.y, { width: pageW, align: 'center' });
        doc.moveDown(0.8);
        doc.fontSize(10).font('Helvetica');
        doc.text('Store Manager: ___________________________________   Date: _______________', 50);
        doc.moveDown(0.8);
        doc.text(`${vendor?.name || 'Vendor'} Rep: __________________________________   Date: _______________`, 50);
        doc.moveDown(1);
        doc.fontSize(8).font('Helvetica-Bold').text('ONE COPY TO VENDOR, ONE COPY RETAINED BY STORE', 50, doc.y, { width: pageW, align: 'center' });
        doc.end();
    });
}

app.post('/api/vendor-returns/:id/print', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_returns WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Return not found.' });
        const [[vendor]] = await enterprisePool().query('SELECT * FROM vendors WHERE id = ?', [rows[0].vendor_id]);
        const [items] = await req.pool.query(
            `SELECT it.*, i.name FROM vendor_return_items it LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.return_id = ? ORDER BY it.id ASC`, [req.params.id]);
        const pdf = await buildVendorReturnPDF(rows[0], vendor, items, req.storeId);
        await sendToLaserPrinter(pdf);
        res.json({ success: true, message: `Credit memo for return #${req.params.id} sent to printer.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- VENDOR ORDERS (DSD scan-to-order) ---
app.get('/api/vendor-orders', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT vo.*,
                    (SELECT COUNT(*) FROM vendor_order_items WHERE order_id = vo.id) AS line_count,
                    (SELECT COALESCE(SUM(quantity_requested),0) FROM vendor_order_items WHERE order_id = vo.id) AS unit_count
             FROM vendor_orders vo ORDER BY vo.created_at DESC LIMIT 200`);
        const vendorIds = [...new Set(rows.map(r => r.vendor_id).filter(Boolean))];
        let vendorMap = {};
        if (vendorIds.length) {
            const [vs] = await enterprisePool().query('SELECT id, code, name FROM vendors WHERE id IN (?)', [vendorIds]);
            vendorMap = Object.fromEntries(vs.map(v => [v.id, v]));
        }
        res.json(rows.map(r => ({ ...r, vendor_code: vendorMap[r.vendor_id]?.code, vendor_name: vendorMap[r.vendor_id]?.name })));
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Resolve a scanned order code (e.g. "VO-123" or "123") to a SUBMITTED order + items.
// Used by HHT Vendor Delivery to kick off a receiving session.
app.get('/api/vendor-orders/resolve/:code', async (req, res) => {
    const raw = String(req.params.code || '').trim().toUpperCase();
    const m = raw.match(/^(?:VO-?)?(\d+)$/);
    if (!m) return res.status(400).json({ success: false, message: 'Expected "VO-123" or a numeric order id.' });
    const id = Number(m[1]);
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_orders WHERE id = ?', [id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: `Order #${id} not found.` });
        const o = rows[0];
        if (o.status !== 'SUBMITTED') {
            return res.status(400).json({ success: false, message: `Order #${id} is ${o.status}, not SUBMITTED. Only submitted orders can be received.` });
        }
        const [items] = await req.pool.query(
            `SELECT it.*, i.name, i.quantity AS on_hand
             FROM vendor_order_items it LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.order_id = ? ORDER BY it.id ASC`, [id]);
        const [[vendor]] = await enterprisePool().query('SELECT * FROM vendors WHERE id = ?', [o.vendor_id]);

        // Also pull any in-progress delivery for this order so we can resume (rather than duplicate)
        const [existing] = await req.pool.query(
            `SELECT id FROM vendor_deliveries WHERE order_id = ? AND status = 'OPEN' ORDER BY id DESC LIMIT 1`, [id]);

        res.json({ success: true, order: o, vendor: vendor || null, items, existing_delivery_id: existing[0]?.id || null });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/vendor-orders/:id', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        const [items] = await req.pool.query(
            `SELECT it.*, i.name, i.quantity AS on_hand, i.reorder_min, i.reorder_max
             FROM vendor_order_items it LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.order_id = ? ORDER BY it.id ASC`, [req.params.id]);
        const [[vendor]] = await enterprisePool().query('SELECT * FROM vendors WHERE id = ?', [rows[0].vendor_id]);
        res.json({ success: true, order: rows[0], vendor: vendor || null, items });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-orders', async (req, res) => {
    const { vendor_id, rep_name, notes, eid } = req.body || {};
    if (!vendor_id) return res.status(400).json({ success: false, message: 'vendor_id required.' });
    try {
        const [r] = await req.pool.query(
            'INSERT INTO vendor_orders (vendor_id, rep_name, notes, created_by_eid) VALUES (?, ?, ?, ?)',
            [vendor_id, rep_name || null, notes || null, eid || null]);
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-orders/:id/item', async (req, res) => {
    const { sku, quantity_requested, notes } = req.body || {};
    const qty = Number(quantity_requested);
    if (!sku || !qty || qty <= 0) return res.status(400).json({ success: false, message: 'sku and positive quantity required.' });
    try {
        const [rows] = await req.pool.query('SELECT status FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        if (rows[0].status !== 'OPEN') return res.status(400).json({ success: false, message: `Order is ${rows[0].status}.` });
        const [items] = await req.pool.query('SELECT sku, name FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (items.length === 0) return res.status(404).json({ success: false, message: `Item ${sku} not in local inventory.` });
        const [r] = await req.pool.query(
            'INSERT INTO vendor_order_items (order_id, sku, quantity_requested, notes) VALUES (?, ?, ?, ?)',
            [req.params.id, items[0].sku, qty, notes || null]);
        res.json({ success: true, id: r.insertId, sku: items[0].sku, name: items[0].name });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/vendor-orders/:id/item/:itemId', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT status FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0 || rows[0].status !== 'OPEN') return res.status(400).json({ success: false, message: 'Order not open.' });
        await req.pool.query('DELETE FROM vendor_order_items WHERE id = ? AND order_id = ?', [req.params.itemId, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-orders/:id/submit', async (req, res) => {
    const { eid } = req.body || {};
    try {
        const [rows] = await req.pool.query('SELECT status FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        if (rows[0].status !== 'OPEN') return res.status(400).json({ success: false, message: `Order is ${rows[0].status}.` });
        const [items] = await req.pool.query('SELECT COUNT(*) AS n FROM vendor_order_items WHERE order_id = ?', [req.params.id]);
        if (items[0].n === 0) return res.status(400).json({ success: false, message: 'Cannot submit an empty order.' });
        await req.pool.query(
            `UPDATE vendor_orders SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP, submitted_by_eid = ? WHERE id = ?`,
            [eid || null, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

async function buildVendorOrderPDF(order, vendor, items, storeId) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 40, bottom: 40, left: 50, right: 50 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c)); doc.on('end', () => resolve(Buffer.concat(chunks))); doc.on('error', reject);
        doc.fontSize(18).font('Helvetica-Bold').text('DSD ORDER REQUEST', { align: 'center' });
        doc.fontSize(11).font('Helvetica').text(`Store #${storeId}`, { align: 'center' });
        doc.moveDown(0.4);
        // Code128 barcode of VO-{id} — HHT scans this on delivery to open the receiving session
        try {
            const canvas = createCanvas();
            JsBarcode(canvas, `VO-${order.id}`, { format: 'CODE128', width: 2, height: 45, displayValue: true, fontSize: 13, margin: 5 });
            const png = canvas.toBuffer('image/png');
            const imgWidth = 230;
            doc.image(png, (612 - imgWidth) / 2, doc.y, { width: imgWidth });
            doc.y += 64;
        } catch (_) {}
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.4);
        doc.fontSize(10).font('Helvetica-Bold').text('Vendor:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${vendor?.name || '—'} (${vendor?.code || '—'})`);
        doc.font('Helvetica-Bold').text('Order #:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${order.id}`);
        doc.font('Helvetica-Bold').text('Rep:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${order.rep_name || '—'}`);
        doc.font('Helvetica-Bold').text('Date:', 50, doc.y, { continued: true }).font('Helvetica').text(`  ${new Date(order.created_at).toLocaleString()}`);
        doc.moveDown(0.5); doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke(); doc.moveDown(0.3);
        doc.fontSize(9).font('Helvetica-Bold');
        const hY = doc.y;
        doc.text('SKU',  50,  hY, { width: 90 });
        doc.text('Item', 145, hY, { width: 300 });
        doc.text('Qty',  450, hY, { width: 50, align: 'right' });
        doc.y = hY + 14;
        doc.moveTo(50, doc.y).lineTo(562, doc.y).stroke();
        doc.moveDown(0.15);
        doc.font('Helvetica').fontSize(9);
        const VO_ROW_H = 16;
        items.forEach(it => {
            const y = doc.y;
            doc.text(it.sku,                              50,  y, { width: 90,  lineBreak: false });
            doc.text((it.name || '').substring(0, 48),    145, y, { width: 300, lineBreak: false, ellipsis: true });
            doc.text(String(it.quantity_requested),       450, y, { width: 50,  align: 'right', lineBreak: false });
            doc.y = y + VO_ROW_H;
        });
        doc.moveDown(0.5);
        doc.text(`Total units requested: ${items.reduce((s, i) => s + i.quantity_requested, 0)}`, 50);
        doc.end();
    });
}

// Cancel a vendor order — orders don't touch inventory, so just flip the status.
// Valid while OPEN or SUBMITTED. A FULFILLED order is sealed.
app.post('/api/vendor-orders/:id/cancel', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT status FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        if (rows[0].status === 'FULFILLED') return res.status(400).json({ success: false, message: 'Cannot cancel a fulfilled order.' });
        if (rows[0].status === 'CANCELLED') return res.status(400).json({ success: false, message: 'Order is already cancelled.' });
        await req.pool.query(`UPDATE vendor_orders SET status = 'CANCELLED' WHERE id = ?`, [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Update metadata on an existing order (PO#, carrier, driver, truck, dates).
// Works regardless of status so the SM can fill this in after submit.
app.put('/api/vendor-orders/:id/meta', async (req, res) => {
    const { po_number, carrier, driver_name, driver_phone, truck_number, expected_delivery, actual_delivery, notes } = req.body || {};
    try {
        const [rows] = await req.pool.query('SELECT id FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        await req.pool.query(
            `UPDATE vendor_orders SET
                po_number = ?, carrier = ?, driver_name = ?, driver_phone = ?, truck_number = ?,
                expected_delivery = ?, actual_delivery = ?, notes = COALESCE(?, notes)
             WHERE id = ?`,
            [
                po_number || null, carrier || null, driver_name || null, driver_phone || null, truck_number || null,
                expected_delivery || null, actual_delivery || null, notes ?? null,
                req.params.id
            ]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Mark a submitted order as fulfilled (vendor delivered it).
app.post('/api/vendor-orders/:id/fulfill', async (req, res) => {
    const { actual_delivery } = req.body || {};
    try {
        const [rows] = await req.pool.query('SELECT status FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        if (rows[0].status !== 'SUBMITTED') return res.status(400).json({ success: false, message: `Order is ${rows[0].status}, not SUBMITTED.` });
        await req.pool.query(
            `UPDATE vendor_orders SET status = 'FULFILLED', fulfilled_at = CURRENT_TIMESTAMP, actual_delivery = COALESCE(?, actual_delivery) WHERE id = ?`,
            [actual_delivery || null, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Cheap search across master_inventory for the Tag Items tab (paging via limit/offset + q).
app.get('/api/inventory/master-search', async (req, res) => {
    const q = (req.query.q || '').toString().trim();
    const limit = Math.min(200, Number(req.query.limit) || 50);
    const offset = Math.max(0, Number(req.query.offset) || 0);
    try {
        // Data query joins vendors, so column refs MUST be table-qualified (mi.name is ambiguous with v.name)
        const dataParams = [];
        let dataWhere = '1=1';
        if (q) {
            dataWhere += ' AND (mi.sku LIKE ? OR mi.upc LIKE ? OR mi.name LIKE ? OR mi.department LIKE ?)';
            dataParams.push(`%${q}%`, `%${q}%`, `%${q}%`, `%${q}%`);
        }
        const [rows] = await enterprisePool().query(
            `SELECT mi.sku, mi.upc, mi.name, mi.department, mi.std_price, mi.vendor_id,
                    v.code AS vendor_code, v.name AS vendor_name
             FROM master_inventory mi LEFT JOIN vendors v ON mi.vendor_id = v.id
             WHERE ${dataWhere}
             ORDER BY mi.name ASC
             LIMIT ? OFFSET ?`, [...dataParams, limit, offset]);

        // Count query doesn't join, so bare column names are fine
        const countParams = [];
        let countWhere = '1=1';
        if (q) {
            countWhere += ' AND (sku LIKE ? OR upc LIKE ? OR name LIKE ? OR department LIKE ?)';
            countParams.push(`%${q}%`, `%${q}%`, `%${q}%`, `%${q}%`);
        }
        const [[{ total }]] = await enterprisePool().query(
            `SELECT COUNT(*) AS total FROM master_inventory WHERE ${countWhere}`, countParams);
        res.json({ rows, total, limit, offset });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-orders/:id/print', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_orders WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Order not found.' });
        const [[vendor]] = await enterprisePool().query('SELECT * FROM vendors WHERE id = ?', [rows[0].vendor_id]);
        const [items] = await req.pool.query(
            `SELECT it.*, i.name FROM vendor_order_items it LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.order_id = ? ORDER BY it.id ASC`, [req.params.id]);
        const pdf = await buildVendorOrderPDF(rows[0], vendor, items, req.storeId);
        await sendToLaserPrinter(pdf);
        res.json({ success: true, message: `Order #${req.params.id} sent to printer.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- VENDOR DELIVERIES (HHT scan-in on vendor drop-off) ---
app.get('/api/vendor-deliveries', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT vd.*,
                    (SELECT COUNT(*) FROM vendor_delivery_items WHERE delivery_id = vd.id) AS line_count,
                    (SELECT COALESCE(SUM(quantity_received),0) FROM vendor_delivery_items WHERE delivery_id = vd.id) AS unit_count
             FROM vendor_deliveries vd ORDER BY vd.created_at DESC LIMIT 200`);
        const vendorIds = [...new Set(rows.map(r => r.vendor_id).filter(Boolean))];
        let vendorMap = {};
        if (vendorIds.length) {
            const [vs] = await enterprisePool().query('SELECT id, code, name FROM vendors WHERE id IN (?)', [vendorIds]);
            vendorMap = Object.fromEntries(vs.map(v => [v.id, v]));
        }
        res.json(rows.map(r => ({ ...r, vendor_code: vendorMap[r.vendor_id]?.code, vendor_name: vendorMap[r.vendor_id]?.name })));
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/vendor-deliveries/:id', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_deliveries WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Delivery not found.' });
        const [items] = await req.pool.query(
            `SELECT it.*, i.name FROM vendor_delivery_items it LEFT JOIN inventory i ON it.sku = i.sku
             WHERE it.delivery_id = ? ORDER BY it.id ASC`, [req.params.id]);
        const [[vendor]] = await enterprisePool().query('SELECT * FROM vendors WHERE id = ?', [rows[0].vendor_id]);
        res.json({ success: true, delivery: rows[0], vendor: vendor || null, items });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/vendor-deliveries', async (req, res) => {
    const { vendor_id, order_id, rep_name, invoice_number, notes, eid } = req.body || {};
    if (!vendor_id) return res.status(400).json({ success: false, message: 'vendor_id required.' });
    try {
        const [r] = await req.pool.query(
            'INSERT INTO vendor_deliveries (vendor_id, order_id, rep_name, invoice_number, notes, received_by_eid) VALUES (?, ?, ?, ?, ?, ?)',
            [vendor_id, order_id || null, rep_name || null, invoice_number || null, notes || null, eid || null]);
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Scan an item onto a delivery — increments inventory immediately.
// If the delivery is linked to an order, reject SKUs not on that order.
app.post('/api/vendor-deliveries/:id/scan', async (req, res) => {
    const { sku, quantity, eid } = req.body || {};
    const qty = Number(quantity);
    if (!sku || !qty || qty <= 0) return res.status(400).json({ success: false, message: 'sku and positive quantity required.' });
    const conn = await req.pool.getConnection();
    try {
        await conn.beginTransaction();
        const [d] = await conn.query('SELECT status, order_id FROM vendor_deliveries WHERE id = ?', [req.params.id]);
        if (d.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: 'Delivery not found.' }); }
        if (d[0].status !== 'OPEN') { await conn.rollback(); return res.status(400).json({ success: false, message: `Delivery is ${d[0].status}.` }); }
        const [items] = await conn.query('SELECT sku, name, quantity FROM inventory WHERE sku = ? OR upc = ?', [sku, sku]);
        if (items.length === 0) { await conn.rollback(); return res.status(404).json({ success: false, message: `Item ${sku} not in local inventory.` }); }
        const item = items[0];
        // Strict: if linked to an order, the scanned SKU must be on that order
        if (d[0].order_id) {
            const [onOrder] = await conn.query(
                'SELECT id FROM vendor_order_items WHERE order_id = ? AND sku = ? LIMIT 1',
                [d[0].order_id, item.sku]);
            if (onOrder.length === 0) {
                await conn.rollback();
                return res.status(400).json({ success: false, message: `"${item.name}" isn't on this order. Have the vendor reconcile, or start a separate delivery for extras.` });
            }
        }
        await conn.query('UPDATE inventory SET quantity = quantity + ? WHERE sku = ?', [qty, item.sku]);
        const [r] = await conn.query(
            'INSERT INTO vendor_delivery_items (delivery_id, sku, quantity_received, scanned_by_eid) VALUES (?, ?, ?, ?)',
            [req.params.id, item.sku, qty, eid || null]);
        await conn.commit();
        const [after] = await req.pool.query('SELECT quantity FROM inventory WHERE sku = ?', [item.sku]);
        res.json({ success: true, id: r.insertId, sku: item.sku, name: item.name, new_quantity: after[0].quantity });
    } catch (err) { await conn.rollback(); res.status(500).json({ success: false, message: err.message }); }
    finally { conn.release(); }
});

app.post('/api/vendor-deliveries/:id/complete', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT status, order_id FROM vendor_deliveries WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Delivery not found.' });
        if (rows[0].status !== 'OPEN') return res.status(400).json({ success: false, message: `Delivery is ${rows[0].status}.` });
        const [items] = await req.pool.query('SELECT COUNT(*) AS n FROM vendor_delivery_items WHERE delivery_id = ?', [req.params.id]);
        if (items[0].n === 0) return res.status(400).json({ success: false, message: 'Cannot complete an empty delivery.' });
        await req.pool.query(
            `UPDATE vendor_deliveries SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?`,
            [req.params.id]);
        // Cascade: if linked to a SUBMITTED order, mark that order FULFILLED
        let orderFulfilled = false;
        if (rows[0].order_id) {
            const [r] = await req.pool.query(
                `UPDATE vendor_orders
                 SET status = 'FULFILLED', fulfilled_at = CURRENT_TIMESTAMP, actual_delivery = COALESCE(actual_delivery, NOW())
                 WHERE id = ? AND status = 'SUBMITTED'`,
                [rows[0].order_id]);
            orderFulfilled = r.affectedRows > 0;
        }
        res.json({ success: true, order_fulfilled: orderFulfilled });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// List vendor SKUs — for DG Respond's "scan to order" suggestions (low stock for a given vendor)
app.get('/api/vendors/:id/inventory', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT sku, name, quantity, reorder_min, reorder_max, price
             FROM inventory WHERE vendor_id = ? ORDER BY (quantity <= reorder_min) DESC, name ASC`,
            [req.params.id]);
        res.json(rows);
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- RECURRING TASKS ---
// Checks every row in task_recurrence against today's date and materializes a row
// in `tasks` if today matches the recurrence rule AND last_generated_date != today.
// Returns number of tasks created for this store. Called hourly by the background loop
// and on-demand by POST /api/task-recurrence/run-now (useful for tests).
async function generateRecurringTasks(pool) {
    const today = new Date();
    const y = today.getFullYear(), m = today.getMonth() + 1, d = today.getDate();
    const todayStr = `${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
    const daysInMonth = new Date(y, m, 0).getDate();
    const dow = today.getDay();

    const [rules] = await pool.query(
        `SELECT * FROM task_recurrence WHERE active = 1 AND (last_generated_date IS NULL OR last_generated_date < ?)`,
        [todayStr]
    );
    let created = 0;
    for (const r of rules) {
        let matches = false;
        if (r.recurrence_type === 'DAILY') matches = true;
        else if (r.recurrence_type === 'WEEKLY') matches = (r.day_of_week === dow);
        else if (r.recurrence_type === 'MONTHLY') {
            const target = Math.min(r.day_of_month || 1, daysInMonth);
            matches = (d === target);
        }
        if (!matches) continue;
        await pool.query(
            'INSERT INTO tasks (title, description, due_date, priority, task_type) VALUES (?, ?, ?, ?, ?)',
            [r.title, r.description || null, todayStr, r.priority || 'NORMAL', r.task_type || 'GENERAL']
        );
        await pool.query('UPDATE task_recurrence SET last_generated_date = ? WHERE id = ?', [todayStr, r.id]);
        created++;
    }
    return created;
}

app.get('/api/task-recurrence', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM task_recurrence ORDER BY active DESC, title ASC');
        res.json({ success: true, rules: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/task-recurrence', async (req, res) => {
    const { title, description, priority, task_type, recurrence_type, day_of_week, day_of_month, active } = req.body || {};
    if (!title || !recurrence_type) return res.status(400).json({ success: false, message: 'title and recurrence_type required.' });
    if (!['DAILY','WEEKLY','MONTHLY'].includes(recurrence_type)) return res.status(400).json({ success: false, message: 'Invalid recurrence_type.' });
    if (recurrence_type === 'WEEKLY' && (day_of_week == null || day_of_week < 0 || day_of_week > 6)) {
        return res.status(400).json({ success: false, message: 'day_of_week (0-6) required for WEEKLY.' });
    }
    if (recurrence_type === 'MONTHLY' && (day_of_month == null || day_of_month < 1 || day_of_month > 31)) {
        return res.status(400).json({ success: false, message: 'day_of_month (1-31) required for MONTHLY.' });
    }
    try {
        const [r] = await req.pool.query(
            `INSERT INTO task_recurrence (title, description, priority, task_type, recurrence_type, day_of_week, day_of_month, active)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
            [title, description || null, priority || 'NORMAL', task_type || 'GENERAL', recurrence_type,
             recurrence_type === 'WEEKLY' ? day_of_week : null,
             recurrence_type === 'MONTHLY' ? day_of_month : null,
             active === false ? 0 : 1]
        );
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Run-now endpoint — triggers the generator for this store. Useful after creating a rule
// that should fire today.
app.post('/api/task-recurrence/run-now', async (req, res) => {
    try {
        const created = await generateRecurringTasks(req.pool);
        res.json({ success: true, created });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/task-recurrence/:id', async (req, res) => {
    const { title, description, priority, task_type, recurrence_type, day_of_week, day_of_month, active } = req.body || {};
    try {
        await req.pool.query(
            `UPDATE task_recurrence SET
                title = COALESCE(?, title),
                description = COALESCE(?, description),
                priority = COALESCE(?, priority),
                task_type = COALESCE(?, task_type),
                recurrence_type = COALESCE(?, recurrence_type),
                day_of_week = ?,
                day_of_month = ?,
                active = COALESCE(?, active)
             WHERE id = ?`,
            [title || null, description || null, priority || null, task_type || null, recurrence_type || null,
             day_of_week ?? null, day_of_month ?? null,
             active == null ? null : (active ? 1 : 0),
             req.params.id]
        );
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/task-recurrence/:id', async (req, res) => {
    try {
        await req.pool.query('DELETE FROM task_recurrence WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- VENDOR VISITS (check-in log) ---
// List recent visits (optionally filter by vendor or active status).
// Route order: /active before /:id so the static route isn't shadowed.
app.get('/api/vendor-visits/active', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT * FROM vendor_visits WHERE checked_out_at IS NULL ORDER BY checked_in_at DESC`
        );
        const vendorIds = [...new Set(rows.map(r => r.vendor_id).filter(Boolean))];
        let vendorMap = {};
        if (vendorIds.length) {
            const [vs] = await enterprisePool().query('SELECT id, code, name FROM vendors WHERE id IN (?)', [vendorIds]);
            vendorMap = Object.fromEntries(vs.map(v => [v.id, v]));
        }
        res.json({ success: true, visits: rows.map(r => ({ ...r, vendor_code: vendorMap[r.vendor_id]?.code, vendor_name: vendorMap[r.vendor_id]?.name })) });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/vendor-visits', async (req, res) => {
    const vendorId = req.query.vendor_id ? Number(req.query.vendor_id) : null;
    const limit = Math.min(200, Math.max(1, parseInt(req.query.limit) || 50));
    try {
        let where = '1=1';
        const params = [];
        if (vendorId) { where += ' AND vendor_id = ?'; params.push(vendorId); }
        const [rows] = await req.pool.query(
            `SELECT * FROM vendor_visits WHERE ${where} ORDER BY checked_in_at DESC LIMIT ?`,
            [...params, limit]
        );
        const vendorIds = [...new Set(rows.map(r => r.vendor_id).filter(Boolean))];
        let vendorMap = {};
        if (vendorIds.length) {
            const [vs] = await enterprisePool().query('SELECT id, code, name FROM vendors WHERE id IN (?)', [vendorIds]);
            vendorMap = Object.fromEntries(vs.map(v => [v.id, v]));
        }
        res.json({ success: true, visits: rows.map(r => ({ ...r, vendor_code: vendorMap[r.vendor_id]?.code, vendor_name: vendorMap[r.vendor_id]?.name })) });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Detail view — includes activity (returns/orders/deliveries) touched during the visit window.
app.get('/api/vendor-visits/:id', async (req, res) => {
    try {
        const [rows] = await req.pool.query('SELECT * FROM vendor_visits WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Visit not found.' });
        const visit = rows[0];
        const endAt = visit.checked_out_at || new Date();
        const [[vendor]] = await enterprisePool().query('SELECT id, code, name FROM vendors WHERE id = ?', [visit.vendor_id]);
        const [returns] = await req.pool.query(
            `SELECT id, status, credit_memo_number, created_at FROM vendor_returns
             WHERE vendor_id = ? AND created_at BETWEEN ? AND ?`,
            [visit.vendor_id, visit.checked_in_at, endAt]);
        const [orders] = await req.pool.query(
            `SELECT id, status, po_number, created_at FROM vendor_orders
             WHERE vendor_id = ? AND created_at BETWEEN ? AND ?`,
            [visit.vendor_id, visit.checked_in_at, endAt]);
        const [deliveries] = await req.pool.query(
            `SELECT id, status, invoice_number, created_at FROM vendor_deliveries
             WHERE vendor_id = ? AND created_at BETWEEN ? AND ?`,
            [visit.vendor_id, visit.checked_in_at, endAt]);
        res.json({ success: true, visit, vendor: vendor || null, activity: { returns, orders, deliveries } });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Check in a vendor rep. Body: { vendor_id, rep_name, eid, notes }.
// Refuses if this vendor already has an active (un-checked-out) visit.
app.post('/api/vendor-visits', async (req, res) => {
    const { vendor_id, rep_name, eid, notes } = req.body || {};
    if (!vendor_id) return res.status(400).json({ success: false, message: 'vendor_id required.' });
    try {
        const [active] = await req.pool.query(
            'SELECT id FROM vendor_visits WHERE vendor_id = ? AND checked_out_at IS NULL LIMIT 1',
            [vendor_id]);
        if (active.length > 0) {
            return res.status(409).json({ success: false, message: `Vendor already checked in (visit #${active[0].id}).`, visit_id: active[0].id });
        }
        const [r] = await req.pool.query(
            'INSERT INTO vendor_visits (vendor_id, rep_name, checked_in_by_eid, notes) VALUES (?, ?, ?, ?)',
            [vendor_id, rep_name || null, eid || null, notes || null]);
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Check out an active vendor visit.
app.post('/api/vendor-visits/:id/checkout', async (req, res) => {
    const { notes } = req.body || {};
    try {
        const [rows] = await req.pool.query('SELECT checked_out_at, notes FROM vendor_visits WHERE id = ?', [req.params.id]);
        if (rows.length === 0) return res.status(404).json({ success: false, message: 'Visit not found.' });
        if (rows[0].checked_out_at) return res.status(400).json({ success: false, message: 'Visit already checked out.' });
        const mergedNotes = notes ? (rows[0].notes ? `${rows[0].notes}\n${notes}` : notes) : rows[0].notes;
        await req.pool.query(
            'UPDATE vendor_visits SET checked_out_at = CURRENT_TIMESTAMP, notes = ? WHERE id = ?',
            [mergedNotes, req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- REFRIGERATION UNITS ---
// CRUD over the store's refrigeration unit inventory. Drives the HHT "Refrigeration
// Maintenance" screen (IMG_1565) where SMs log cooler/freezer/ice-cream units,
// flip OOS, and remove decommissioned equipment.
app.get('/api/refrigeration/units', async (req, res) => {
    const category = req.query.category;
    try {
        const params = [];
        let where = '';
        if (category) { where = 'WHERE category = ?'; params.push(category); }
        const [rows] = await req.pool.query(
            `SELECT * FROM refrigeration_units ${where} ORDER BY oos ASC, unit_number ASC`, params);
        res.json({ success: true, units: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.post('/api/refrigeration/units', async (req, res) => {
    const { unit_number, description, category } = req.body || {};
    if (!unit_number) return res.status(400).json({ success: false, message: 'unit_number required.' });
    try {
        const [r] = await req.pool.query(
            'INSERT INTO refrigeration_units (unit_number, description, category) VALUES (?, ?, ?)',
            [unit_number, description || null, category || null]);
        res.json({ success: true, id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.put('/api/refrigeration/units/:id', async (req, res) => {
    const { unit_number, description, category, oos } = req.body || {};
    try {
        await req.pool.query(
            `UPDATE refrigeration_units SET
                unit_number = COALESCE(?, unit_number),
                description = COALESCE(?, description),
                category = COALESCE(?, category),
                oos = COALESCE(?, oos)
             WHERE id = ?`,
            [unit_number || null, description || null, category || null,
             oos == null ? null : (oos ? 1 : 0), req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.delete('/api/refrigeration/units/:id', async (req, res) => {
    try {
        await req.pool.query('DELETE FROM refrigeration_units WHERE id = ?', [req.params.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// ---------- TILL & CASH (Store Menu) ----------
// dgPOS is the source of truth for till activity; it POSTs here on each money-touching event.
// Reconciliation, graphs, and manager force-closes live purely on the dashboard side.
// Ingest endpoints are session-less (dgPOS can't log in). Manager-facing actions require
// a logged-in SM/ASM session. Store selection is via X-Store-ID header from dgPOS.

// Recompute a session's running totals + expected_cash from its events. Call after any
// event insert so the Active Sessions view stays live.
async function recomputeTillSession(pool, sessionId) {
    const [[s]] = await pool.query('SELECT starting_bank FROM till_sessions WHERE id = ?', [sessionId]);
    if (!s) return;
    const [[sums]] = await pool.query(
        `SELECT
            COALESCE(SUM(CASE WHEN event_type='SALE' THEN amount ELSE 0 END), 0) AS sales,
            COALESCE(SUM(CASE WHEN event_type='REFUND' THEN amount ELSE 0 END), 0) AS refunds,
            COALESCE(SUM(CASE WHEN event_type='PICKUP' THEN amount ELSE 0 END), 0) AS pickups
         FROM till_session_events WHERE session_id = ?`, [sessionId]);
    const expected = Number(s.starting_bank) + Number(sums.sales) - Number(sums.refunds) - Number(sums.pickups);
    await pool.query(
        `UPDATE till_sessions SET cash_sales = ?, cash_refunds = ?, pickups_total = ?, expected_cash = ?
         WHERE id = ?`, [sums.sales, sums.refunds, sums.pickups, expected.toFixed(2), sessionId]);
}

// --- INGEST (from dgPOS) ---

// Open a new till session when a cashier logs into a register.
app.post('/api/till/sessions/start', async (req, res) => {
    const { register_id, eid, eid_name, starting_bank, opened_at } = req.body || {};
    if (!register_id || !eid) return res.status(400).json({ success: false, message: 'register_id + eid required.' });
    try {
        // Close any stale OPEN sessions on this register (data hygiene).
        await req.pool.query(
            `UPDATE till_sessions SET status = 'HELD' WHERE register_id = ? AND status = 'OPEN'`,
            [register_id]);
        const [r] = await req.pool.query(
            `INSERT INTO till_sessions (register_id, eid, eid_name, opened_at, starting_bank, expected_cash, status)
             VALUES (?, ?, ?, ?, ?, ?, 'OPEN')`,
            [register_id, eid, eid_name || null, opened_at || new Date(), starting_bank || 0, starting_bank || 0]);
        res.json({ success: true, session_id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Record a till event. Auto-links to the currently-open session on that register.
app.post('/api/till/events', async (req, res) => {
    const { register_id, eid, event_type, amount, authorized_by, receipt_id, note, occurred_at } = req.body || {};
    if (!register_id || !event_type) return res.status(400).json({ success: false, message: 'register_id + event_type required.' });
    try {
        const [open] = await req.pool.query(
            `SELECT id FROM till_sessions WHERE register_id = ? AND status = 'OPEN' ORDER BY opened_at DESC LIMIT 1`,
            [register_id]);
        const sessionId = open.length > 0 ? open[0].id : null;
        await req.pool.query(
            `INSERT INTO till_session_events (session_id, register_id, eid, event_type, amount, authorized_by, receipt_id, note, occurred_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
            [sessionId, register_id, eid || '', event_type, amount || 0, authorized_by || null, receipt_id || null, note || null, occurred_at || new Date()]);
        if (sessionId) await recomputeTillSession(req.pool, sessionId);
        res.json({ success: true, session_id: sessionId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Close a session with an actual cash count (the cashier's Z-Out).
app.post('/api/till/sessions/close', async (req, res) => {
    const { session_id, register_id, actual_cash, closed_at, notes } = req.body || {};
    if (!session_id && !register_id) return res.status(400).json({ success: false, message: 'session_id or register_id required.' });
    try {
        let sid = session_id;
        if (!sid) {
            const [open] = await req.pool.query(
                `SELECT id FROM till_sessions WHERE register_id = ? AND status = 'OPEN' ORDER BY opened_at DESC LIMIT 1`,
                [register_id]);
            if (open.length === 0) return res.status(404).json({ success: false, message: 'No open session for register.' });
            sid = open[0].id;
        }
        await recomputeTillSession(req.pool, sid);
        const [[s]] = await req.pool.query('SELECT expected_cash FROM till_sessions WHERE id = ?', [sid]);
        const actual = Number(actual_cash || 0);
        const overShort = actual - Number(s.expected_cash);
        await req.pool.query(
            `UPDATE till_sessions SET status='CLOSED', closed_at=?, actual_cash=?, over_short=?, notes=COALESCE(?, notes)
             WHERE id = ?`,
            [closed_at || new Date(), actual, overShort.toFixed(2), notes || null, sid]);
        res.json({ success: true, session_id: sid, over_short: overShort.toFixed(2) });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// dgPOS polls this to see if any force-close is pending for its register. Static route
// is registered BEFORE any /:id wildcards below.
app.get('/api/till/pending-commands', async (req, res) => {
    const { register_id } = req.query;
    if (!register_id) return res.status(400).json({ success: false, message: 'register_id required.' });
    try {
        const [rows] = await req.pool.query(
            `SELECT id, session_id, register_id, actual_cash, note, requested_by_eid, requested_at
             FROM force_close_commands WHERE register_id = ? AND status = 'PENDING' ORDER BY requested_at ASC`,
            [register_id]);
        res.json({ success: true, commands: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// dgPOS acks that it applied a force-close command. Backoffice marks the session FORCE_CLOSED.
app.post('/api/till/commands/:id/applied', async (req, res) => {
    try {
        const [[cmd]] = await req.pool.query('SELECT * FROM force_close_commands WHERE id = ?', [req.params.id]);
        if (!cmd) return res.status(404).json({ success: false, message: 'Command not found.' });
        if (cmd.status !== 'PENDING') return res.json({ success: true, already: true });
        await recomputeTillSession(req.pool, cmd.session_id);
        const [[s]] = await req.pool.query('SELECT expected_cash FROM till_sessions WHERE id = ?', [cmd.session_id]);
        const actual = cmd.actual_cash == null ? null : Number(cmd.actual_cash);
        const overShort = actual == null ? null : (actual - Number(s.expected_cash)).toFixed(2);
        await req.pool.query(
            `UPDATE till_sessions SET status='FORCE_CLOSED', closed_at=CURRENT_TIMESTAMP,
                actual_cash=?, over_short=?,
                notes = CONCAT(COALESCE(notes,''), IF(notes IS NULL OR notes='', '', '\n'), ?)
             WHERE id = ?`,
            [actual, overShort, `Force-closed by ${cmd.requested_by_eid}${cmd.note ? ': ' + cmd.note : ''}`, cmd.session_id]);
        await req.pool.query(
            `UPDATE force_close_commands SET status='APPLIED', applied_at=CURRENT_TIMESTAMP WHERE id = ?`,
            [cmd.id]);
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- QUERY (for dashboard) ---

// Static routes FIRST.

app.get('/api/till/sessions/active', async (req, res) => {
    try {
        const [rows] = await req.pool.query(
            `SELECT * FROM till_sessions WHERE status IN ('OPEN','HELD') ORDER BY opened_at DESC`);
        for (const r of rows) await recomputeTillSession(req.pool, r.id);
        const [refreshed] = await req.pool.query(
            `SELECT * FROM till_sessions WHERE status IN ('OPEN','HELD') ORDER BY opened_at DESC`);
        res.json({ success: true, sessions: refreshed });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

app.get('/api/till/sessions', async (req, res) => {
    const days = Math.min(parseInt(req.query.days || '30', 10), 365);
    const eid = req.query.eid || null;
    const register = req.query.register_id || null;
    const status = req.query.status || null;
    try {
        const where = ['opened_at >= DATE_SUB(NOW(), INTERVAL ? DAY)'];
        const params = [days];
        if (eid) { where.push('eid = ?'); params.push(eid); }
        if (register) { where.push('register_id = ?'); params.push(register); }
        if (status) { where.push('status = ?'); params.push(status); }
        const [rows] = await req.pool.query(
            `SELECT * FROM till_sessions WHERE ${where.join(' AND ')} ORDER BY opened_at DESC LIMIT 500`, params);
        res.json({ success: true, sessions: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Over/short aggregation for graphs. group_by: cashier | day | register.
app.get('/api/till/reports/over-short', async (req, res) => {
    const days = Math.min(parseInt(req.query.days || '30', 10), 365);
    const groupBy = ['cashier', 'day', 'register'].includes(req.query.group_by) ? req.query.group_by : 'day';
    try {
        let select;
        if (groupBy === 'cashier') select = `eid AS key_val, COALESCE(MAX(eid_name), eid) AS label`;
        else if (groupBy === 'register') select = `register_id AS key_val, register_id AS label`;
        else select = `DATE(closed_at) AS key_val, DATE_FORMAT(closed_at, '%Y-%m-%d') AS label`;
        const [rows] = await req.pool.query(
            `SELECT ${select},
                SUM(over_short) AS total_over_short,
                AVG(over_short) AS avg_over_short,
                COUNT(*) AS session_count
             FROM till_sessions
             WHERE status IN ('CLOSED','FORCE_CLOSED')
               AND closed_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
               AND over_short IS NOT NULL
             GROUP BY key_val, label ORDER BY key_val ASC`, [days]);
        res.json({ success: true, group_by: groupBy, rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Roster of everyone who's had a till session, with quick stats.
app.get('/api/till/employees', async (req, res) => {
    const days = Math.min(parseInt(req.query.days || '30', 10), 365);
    try {
        const [rows] = await req.pool.query(
            `SELECT eid, MAX(eid_name) AS eid_name,
                    COUNT(*) AS sessions,
                    SUM(over_short) AS total_over_short,
                    AVG(over_short) AS avg_over_short,
                    MAX(closed_at) AS last_session
             FROM till_sessions
             WHERE status IN ('CLOSED','FORCE_CLOSED')
               AND closed_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
               AND over_short IS NOT NULL
             GROUP BY eid ORDER BY total_over_short ASC`, [days]);
        res.json({ success: true, employees: rows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Detail view for one employee: per-session over/short + punch history.
app.get('/api/till/employees/:eid', async (req, res) => {
    const days = Math.min(parseInt(req.query.days || '30', 10), 365);
    try {
        const [sessions] = await req.pool.query(
            `SELECT id, register_id, opened_at, closed_at, expected_cash, actual_cash, over_short, status
             FROM till_sessions
             WHERE eid = ? AND opened_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
             ORDER BY opened_at DESC`, [req.params.eid, days]);
        const [punches] = await req.pool.query(
            `SELECT action, timestamp FROM time_punches
             WHERE eid = ? AND timestamp >= DATE_SUB(NOW(), INTERVAL ? DAY)
             ORDER BY timestamp DESC LIMIT 200`, [req.params.eid, days]);
        res.json({ success: true, eid: req.params.eid, sessions, punches });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// /:id wildcards LAST.

app.get('/api/till/sessions/:id', async (req, res) => {
    try {
        await recomputeTillSession(req.pool, req.params.id);
        const [[session]] = await req.pool.query('SELECT * FROM till_sessions WHERE id = ?', [req.params.id]);
        if (!session) return res.status(404).json({ success: false, message: 'Session not found.' });
        const [events] = await req.pool.query(
            'SELECT * FROM till_session_events WHERE session_id = ? ORDER BY occurred_at ASC',
            [req.params.id]);
        const [commands] = await req.pool.query(
            'SELECT * FROM force_close_commands WHERE session_id = ? ORDER BY requested_at ASC',
            [req.params.id]);
        res.json({ success: true, session, events, commands });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- MANAGER ACTIONS (SM/ASM only) ---

// Role gate. Accepts SM, ASM, super_admin, admin. SAs and unknowns are blocked.
const requireManagerRole = (req, res, next) => {
    if (!req.session.userId) return res.status(401).json({ success: false, message: 'Login required.' });
    const role = (req.session.role || '').toLowerCase();
    const allowed = ['sm', 'asm', 'super_admin', 'admin'];
    if (!allowed.includes(role)) return res.status(403).json({ success: false, message: 'Manager role required.' });
    next();
};

// Queue a force-close command for dgPOS to apply.
app.post('/api/till/sessions/:id/force-close', requireManagerRole, async (req, res) => {
    const { actual_cash, note } = req.body || {};
    try {
        const [[session]] = await req.pool.query('SELECT register_id, status FROM till_sessions WHERE id = ?', [req.params.id]);
        if (!session) return res.status(404).json({ success: false, message: 'Session not found.' });
        if (session.status === 'CLOSED' || session.status === 'FORCE_CLOSED') {
            return res.status(400).json({ success: false, message: `Session already ${session.status}.` });
        }
        const [existing] = await req.pool.query(
            `SELECT id FROM force_close_commands WHERE session_id = ? AND status = 'PENDING' LIMIT 1`,
            [req.params.id]);
        if (existing.length > 0) return res.status(409).json({ success: false, message: 'Force-close already pending.', command_id: existing[0].id });
        const [r] = await req.pool.query(
            `INSERT INTO force_close_commands (session_id, register_id, actual_cash, note, requested_by_eid)
             VALUES (?, ?, ?, ?, ?)`,
            [req.params.id, session.register_id, actual_cash == null ? null : Number(actual_cash), note || null, req.session.eid || 'unknown']);
        res.json({ success: true, command_id: r.insertId });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Cancel a still-pending force-close.
app.post('/api/till/commands/:id/cancel', requireManagerRole, async (req, res) => {
    try {
        const [r] = await req.pool.query(
            `UPDATE force_close_commands SET status='CANCELLED' WHERE id = ? AND status = 'PENDING'`,
            [req.params.id]);
        res.json({ success: true, changed: r.affectedRows });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// Build + print a one-page shift report PDF to the HP4155 CUPS queue.
function buildShiftReportPDF(session, events, storeId) {
    return new Promise((resolve, reject) => {
        const doc = new PDFDocument({ size: 'LETTER', margins: { top: 50, bottom: 50, left: 60, right: 60 } });
        const chunks = [];
        doc.on('data', c => chunks.push(c));
        doc.on('end', () => resolve(Buffer.concat(chunks)));
        doc.on('error', reject);
        const fmt = (n) => (n == null ? '—' : `$${Number(n).toFixed(2)}`);
        const fmtDt = (d) => (d ? new Date(d).toLocaleString() : '—');
        doc.fontSize(18).text('SHIFT RECONCILE REPORT', { align: 'center' });
        doc.moveDown(0.3);
        doc.fontSize(10).fillColor('#555').text(`Store #${storeId}   ·   Session #${session.id}   ·   Printed ${new Date().toLocaleString()}`, { align: 'center' });
        doc.moveDown(1).fillColor('#000');
        doc.fontSize(12);
        doc.text(`Cashier:      ${session.eid_name || session.eid} (${session.eid})`);
        doc.text(`Register:     ${session.register_id}`);
        doc.text(`Opened:       ${fmtDt(session.opened_at)}`);
        doc.text(`Closed:       ${fmtDt(session.closed_at)}`);
        doc.text(`Status:       ${session.status}`);
        doc.moveDown(0.5);
        doc.fontSize(13).text('Cash Math', { underline: true });
        doc.fontSize(12);
        doc.text(`Starting bank:       ${fmt(session.starting_bank)}`);
        doc.text(`+ Cash sales:        ${fmt(session.cash_sales)}`);
        doc.text(`− Cash refunds:      ${fmt(session.cash_refunds)}`);
        doc.text(`− Pickups to safe:   ${fmt(session.pickups_total)}`);
        doc.text(`= Expected cash:     ${fmt(session.expected_cash)}`);
        doc.moveDown(0.2);
        doc.text(`Actual counted:      ${fmt(session.actual_cash)}`);
        const os = session.over_short == null ? null : Number(session.over_short);
        const osLabel = os == null ? '—' : (os > 0 ? `OVER ${fmt(os)}` : os < 0 ? `SHORT ${fmt(Math.abs(os))}` : 'EVEN');
        doc.fillColor(os == null ? '#000' : (Math.abs(os) < 0.01 ? '#16a34a' : '#dc2626'))
           .fontSize(14).text(`Over / short:        ${osLabel}`).fillColor('#000').fontSize(12);
        doc.moveDown(0.8);
        doc.fontSize(13).text('Events', { underline: true });
        doc.fontSize(10);
        if (events.length === 0) doc.fillColor('#666').text('(none)').fillColor('#000');
        for (const e of events) {
            const amt = (e.event_type === 'SALE' ? '+' : '−') + `$${Number(e.amount).toFixed(2)}`;
            doc.text(`${fmtDt(e.occurred_at).padEnd(22)}  ${e.event_type.padEnd(10)}  ${amt.padStart(10)}  ${e.note || e.receipt_id || ''}`);
        }
        if (session.notes) {
            doc.moveDown(0.6).fontSize(11).fillColor('#555').text('Notes:').fillColor('#000').text(session.notes);
        }
        doc.moveDown(2);
        doc.fontSize(11);
        doc.text('Cashier signature: ____________________________      Manager signature: ____________________________');
        doc.end();
    });
}

app.post('/api/till/sessions/:id/print-shift-report', requireAuth, async (req, res) => {
    try {
        const [[session]] = await req.pool.query('SELECT * FROM till_sessions WHERE id = ?', [req.params.id]);
        if (!session) return res.status(404).json({ success: false, message: 'Session not found.' });
        await recomputeTillSession(req.pool, session.id);
        const [[refreshed]] = await req.pool.query('SELECT * FROM till_sessions WHERE id = ?', [req.params.id]);
        const [events] = await req.pool.query(
            'SELECT * FROM till_session_events WHERE session_id = ? ORDER BY occurred_at ASC', [req.params.id]);
        const pdf = await buildShiftReportPDF(refreshed, events, req.storeId);
        await sendToLaserPrinter(pdf);
        res.json({ success: true, message: `Shift report sent to ${LASER_PRINTER_QUEUE}.` });
    } catch (err) { res.status(500).json({ success: false, message: err.message }); }
});

// --- DG RESPOND ENTRY PAGE ---
app.get('/respond', (req, res) => {
    if (!req.session.userId) return res.redirect('/');
    res.sendFile(path.join(__dirname, 'respond.html'));
});

// --- UTILITY ---
(async () => {
    try {
        await initEnterpriseDatabase();
        console.log("District Control Center - Utility Ready.");

        // Auto-reorder background check every 15 minutes
        setInterval(async () => {
            try {
                const [stores] = await enterprisePool().query('SELECT id FROM stores');
                for (const store of stores) {
                    try {
                        const pool = await getStorePool(store.id);
                        const count = await checkAutoReorders(store.id, pool);
                        if (count > 0) console.log(`[Auto-Reorder] Store ${store.id}: ${count} new reorders`);
                    } catch (e) { console.error(`[Auto-Reorder] Store ${store.id} error:`, e.message); }
                }
            } catch (e) { console.error('[Auto-Reorder] Failed to fetch stores:', e.message); }
        }, 15 * 60 * 1000);
        console.log("[Auto-Reorder] Background check enabled (every 15 min).");

        // Recurring task generator — hourly. Idempotent per (rule, date).
        setInterval(async () => {
            try {
                const [stores] = await enterprisePool().query('SELECT id FROM stores');
                for (const store of stores) {
                    try {
                        const pool = await getStorePool(store.id);
                        const created = await generateRecurringTasks(pool);
                        if (created > 0) console.log(`[Recurring Tasks] Store ${store.id}: ${created} new task(s)`);
                    } catch (e) { console.error(`[Recurring Tasks] Store ${store.id} error:`, e.message); }
                }
            } catch (e) { console.error('[Recurring Tasks] Failed to fetch stores:', e.message); }
        }, 60 * 60 * 1000);
        console.log("[Recurring Tasks] Background generator enabled (hourly).");

    } catch (e) { console.error("Initialization failed:", e.message); }
})();

app.get('/dashboard', (req, res) => { if (!req.session.userId) return res.redirect('/'); res.sendFile(path.join(__dirname, 'dashboard.html')); });
app.get('/store-menu', (req, res) => { if (!req.session.userId) return res.redirect('/'); res.sendFile(path.join(__dirname, 'store-menu.html')); });
app.get('*', (req, res) => res.sendFile(path.join(__dirname, 'index.html')));

app.listen(PORT, () => {
    console.log(`====================================================`);
    console.log(`   StoreNET DISTRICT CONTROL CENTER - ONLINE       `);
    console.log(`   Access it at: http://localhost:${PORT}          `);
    console.log(`====================================================`);
});
