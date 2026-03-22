const express = require('express');
const path = require('path');
const session = require('express-session');
const bcrypt = require('bcrypt');
const { pool, initDatabase, generateDailyData } = require('./database');

const app = express();
const PORT = 3000;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use(session({
    secret: 'storenet_secret_key_123',
    resave: false,
    saveUninitialized: false,
    cookie: { secure: false }
}));

app.use(express.static(__dirname));

// --- GLOBAL SYSTEM STATE ---
let systemDate = new Date(); // Start at real today

app.post('/api/system/next-day', async (req, res) => {
    systemDate.setDate(systemDate.getDate() + 1);
    const dateStr = systemDate.toISOString().split('T')[0];
    await generateDailyData(dateStr);
    
    // Log the advance
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Advanced system date to ${dateStr}`, new Date()]);
    
    res.json({ success: true, newDate: dateStr });
});

app.get('/api/system/date', (req, res) => {
    res.json({ date: systemDate.toISOString().split('T')[0] });
});

// INITIALIZE TODAY'S DATA ON STARTUP (wrapped in async)
(async () => {
    try {
        await initDatabase();
        await generateDailyData(systemDate.toISOString().split('T')[0]);
    } catch (e) {
        console.error("Initial data gen failed:", e.message);
    }
})();


// --- AUTH ---
app.post('/api/login', async (req, res) => {
    const { username, password } = req.body;
    try {
        const [rows] = await pool.query('SELECT * FROM users WHERE eid = ? OR username = ?', [username, username]);
        const user = rows[0];
        if (!user) return res.status(401).json({ success: false, message: 'Invalid EID or Password' });
        
        const match = await bcrypt.compare(password, user.password);
        if (match) {
            req.session.userId = user.id;
            req.session.eid = user.eid;
            req.session.role = user.role;
            if (user.is_first_login) {
                return res.json({ success: true, requirePasswordChange: true, message: 'First login detected.' });
            }
            return res.json({ success: true, requirePasswordChange: false, message: 'Login successful' });
        } else {
            return res.status(401).json({ success: false, message: 'Invalid EID or Password' });
        }
    } catch (err) {
        console.error("Login Error:", err);
        return res.status(500).json({ success: false, message: 'Database error' });
    }
});

app.get('/dashboard', (req, res) => {
    if (!req.session.userId) return res.redirect('/');
    res.sendFile(path.join(__dirname, 'dashboard.html'));
});

app.get('/logout', (req, res) => {
    req.session.destroy();
    res.redirect('/');
});


// --- APIS ---

// TASKS (START)
app.get('/api/tasks', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM tasks WHERE status = "Pending" ORDER BY id DESC');
    res.json(rows);
});
app.post('/api/tasks/complete', async (req, res) => {
    await pool.query('UPDATE tasks SET status = "Complete" WHERE id = ?', [req.body.id]);
    res.json({success:true});
});

// TICKETS (ERC)
app.get('/api/tickets', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM tickets ORDER BY id DESC');
    res.json(rows);
});
app.post('/api/tickets', async (req, res) => {
    const { category, description } = req.body;
    await pool.query('INSERT INTO tickets (category, description, created_at) VALUES (?, ?, ?)', [category, description, new Date()]);
    res.json({success:true});
});
app.post('/api/tickets/reopen', async (req, res) => {
    await pool.query('UPDATE tickets SET status = "Open", resolution = NULL, updated_at = ? WHERE id = ?', [new Date(), req.body.id]);
    res.json({success:true});
});

// EMPLOYEES
app.get('/api/employees', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM employees');
    res.json(rows);
});

// PAYROLL & PUNCHES
app.get('/api/punches', async (req, res) => {
    const [rows] = await pool.query(`SELECT p.*, e.first_name, e.last_name FROM time_punches p JOIN employees e ON p.employee_id = e.id ORDER BY p.date DESC, p.id DESC`);
    res.json(rows);
});
app.post('/api/punches/add', async (req, res) => {
    const { employee_id, date, punch_in, punch_out } = req.body;
    const [result] = await pool.query('INSERT INTO time_punches (employee_id, date, punch_in, punch_out, approved, exception_type) VALUES (?, ?, ?, ?, 1, NULL)', [employee_id, date, punch_in, punch_out]);
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Added new punch for Emp ${employee_id}.`, new Date()]);
    res.json({ success: true, id: result.insertId });
});
app.post('/api/punches/edit', async (req, res) => {
    const { id, employee_id, date, punch_in, punch_out } = req.body;
    await pool.query('UPDATE time_punches SET employee_id = ?, date = ?, punch_in = ?, punch_out = ? WHERE id = ?', [employee_id, date, punch_in, punch_out, id]);
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Edited punch ID ${id}.`, new Date()]);
    res.json({ success: true });
});
app.post('/api/punches/delete', async (req, res) => {
    const { id } = req.body;
    await pool.query('DELETE FROM time_punches WHERE id = ?', [id]);
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Deleted punch ID ${id}.`, new Date()]);
    res.json({ success: true });
});
app.post('/api/punches/fix', async (req, res) => {
    const { id, punch_out, reason } = req.body;
    if (punch_out && punch_out !== 'N/A') {
        await pool.query('UPDATE time_punches SET punch_out = ?, exception_type = NULL, approved = 1 WHERE id = ?', [punch_out, id]);
    } else {
        await pool.query('UPDATE time_punches SET exception_type = NULL, approved = 1 WHERE id = ?', [id]);
    }
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Fixed punch ID ${id}. Reason: ${reason}`, new Date()]);
    res.json({ success: true });
});

// SCHEDULE BUILDER
app.get('/api/schedule', async (req, res) => {
    const [rows] = await pool.query('SELECT s.*, e.first_name, e.last_name FROM schedule s JOIN employees e ON s.employee_id = e.id');
    res.json(rows);
});
app.post('/api/schedule', async (req, res) => {
    const { shifts } = req.body; // Array of {emp_id, date, start, end}
    for (const s of shifts) {
        await pool.query('INSERT INTO schedule (employee_id, date, start_time, end_time) VALUES (?, ?, ?, ?)', [s.emp_id, s.date, s.start, s.end]);
    }
    res.json({success: true});
});

// INVENTORY & RECEIVING
app.get('/api/inventory', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM inventory');
    res.json(rows);
});
app.get('/api/inventory/:sku', async (req, res) => {
    const { sku } = req.params;
    const [rows] = await pool.query('SELECT * FROM inventory WHERE sku = ?', [sku]);
    if (rows.length === 0) return res.status(404).json({ success: false, message: 'Item not found' });
    res.json(rows[0]);
});
app.get('/api/deliveries', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM vendor_deliveries WHERE status = "Pending"');
    res.json(rows);
});
app.post('/api/deliveries/approve', async (req, res) => {
    await pool.query('UPDATE vendor_deliveries SET status = "Approved" WHERE id = ?', [req.body.id]);
    res.json({ success: true });
});
app.post('/api/damages', async (req, res) => {
    const { sku, quantity, reason, type } = req.body;
    await pool.query('INSERT INTO damages_returns (sku, quantity, reason, type, date_processed) VALUES (?, ?, ?, ?, ?)', [sku, quantity, reason, type, new Date()]);
    await pool.query('UPDATE inventory SET quantity = quantity - ? WHERE sku = ?', [quantity, sku]);
    res.json({ success: true });
});

// PRICE CHANGES
app.get('/api/prices', async (req, res) => {
    const [rows] = await pool.query('SELECT p.*, i.name, i.department FROM price_changes p JOIN inventory i ON p.sku = i.sku WHERE p.status = "Pending"');
    res.json(rows);
});
app.post('/api/prices/commit', async (req, res) => {
    const { ids } = req.body; 
    if (!ids || ids.length === 0) return res.json({success: true});
    
    const [rows] = await pool.query(`SELECT sku, new_price FROM price_changes WHERE id IN (${ids.map(()=>'?').join(',')})`, ids);
    for (const r of rows) {
        await pool.query('UPDATE inventory SET price = ? WHERE sku = ?', [r.new_price, r.sku]);
    }
    await pool.query(`UPDATE price_changes SET status = "Complete" WHERE id IN (${ids.map(()=>'?').join(',')})`, ids);
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Committed ${ids.length} price changes.`, new Date()]);
    res.json({success: true});
});

// REGISTER ADMIN APIS
app.get('/api/pos/logs', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM transaction_logs ORDER BY id DESC LIMIT 100');
    res.json(rows);
});
app.post('/api/pos/users', async (req, res) => {
    const { eid, name, pin, role } = req.body;
    // Update if exists, else insert
    await pool.query('INSERT INTO users (eid, name, pin, role) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), pin = VALUES(pin), role = VALUES(role)', [eid, name, pin, role]);
    await pool.query('INSERT INTO audit_logs (user_eid, action, timestamp) VALUES (?, ?, ?)', [req.session.eid || 'System', `Modified register access for ${eid}`, new Date()]);
    res.json({success: true});
});

// REPORTS & ADMIN
app.get('/api/reports/sales', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM sales_data ORDER BY date DESC LIMIT 7');
    res.json(rows);
});
app.get('/api/admin/users', async (req, res) => {
    const [rows] = await pool.query('SELECT id, eid, username, role, is_first_login FROM users');
    res.json(rows);
});
app.get('/api/admin/logs', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM audit_logs ORDER BY id DESC LIMIT 50');
    res.json(rows);
});
app.get('/api/training', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM training_modules');
    res.json(rows);
});
app.get('/api/messages', async (req, res) => {
    const [rows] = await pool.query('SELECT * FROM messages ORDER BY id DESC');
    res.json(rows);
});


app.get('*', (req, res) => res.sendFile(path.join(__dirname, 'index.html')));

app.listen(PORT, () => {
    console.log(`========================================`);
    console.log(`   StoreNET Enterprise Server ONLINE    `);
    console.log(`   Access it at: http://localhost:${PORT} `);
    console.log(`========================================`);
});
