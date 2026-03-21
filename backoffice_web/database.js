const mysql = require('mysql2/promise');
const bcrypt = require('bcrypt');

const dbConfig = {
    host: '192.168.0.192',
    user: 'root',
    password: 'jjgh5879921pomn##',
    database: 'dgpos',
    waitForConnections: true,
    connectionLimit: 10,
    queueLimit: 0
};

const pool = mysql.createPool(dbConfig);

// Helper for random time generation
const rndTime = (startH, endH) => {
    const h = Math.floor(Math.random() * (endH - startH + 1)) + startH;
    const m = Math.random() < 0.5 ? '00' : (Math.random() < 0.5 ? '15' : '45');
    return `${h.toString().padStart(2, '0')}:${m}`;
};

async function initDatabase() {
    const connection = await pool.getConnection();
    try {
        await connection.query(`CREATE TABLE IF NOT EXISTS users (
            id INT AUTO_INCREMENT PRIMARY KEY,
            eid VARCHAR(50) UNIQUE NOT NULL,
            name VARCHAR(100),
            username VARCHAR(50) UNIQUE,
            password VARCHAR(255),
            pin VARCHAR(50) DEFAULT '0000',
            is_first_login TINYINT(1) DEFAULT 1,
            role VARCHAR(20) DEFAULT 'manager'
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS employees (
            id INT AUTO_INCREMENT PRIMARY KEY,
            first_name VARCHAR(100),
            last_name VARCHAR(100),
            position VARCHAR(100),
            status VARCHAR(20) DEFAULT 'Active',
            wage DECIMAL(10,2) DEFAULT 10.00
        )`);
        
        await connection.query(`CREATE TABLE IF NOT EXISTS schedule (
            id INT AUTO_INCREMENT PRIMARY KEY,
            employee_id INT,
            date DATE,
            start_time VARCHAR(10),
            end_time VARCHAR(10),
            FOREIGN KEY(employee_id) REFERENCES employees(id)
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS time_punches (
            id INT AUTO_INCREMENT PRIMARY KEY,
            employee_id INT,
            date DATE,
            punch_in VARCHAR(10),
            punch_out VARCHAR(10),
            approved TINYINT(1) DEFAULT 0,
            exception_type VARCHAR(50),
            FOREIGN KEY(employee_id) REFERENCES employees(id)
        )`);
        
        await connection.query(`CREATE TABLE IF NOT EXISTS inventory (
            id INT AUTO_INCREMENT PRIMARY KEY,
            sku VARCHAR(50) UNIQUE NOT NULL,
            name VARCHAR(100),
            quantity INT DEFAULT 0,
            price DECIMAL(10,2),
            department VARCHAR(50),
            status VARCHAR(20) DEFAULT 'Active'
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS vendor_deliveries (
            id INT AUTO_INCREMENT PRIMARY KEY,
            vendor_name VARCHAR(100),
            delivery_date DATE,
            status VARCHAR(20) DEFAULT 'Pending'
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS damages_returns (
            id INT AUTO_INCREMENT PRIMARY KEY,
            sku VARCHAR(50),
            quantity INT,
            reason TEXT,
            type VARCHAR(20),
            date_processed DATETIME
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS price_changes (
            id INT AUTO_INCREMENT PRIMARY KEY,
            sku VARCHAR(50),
            old_price DECIMAL(10,2),
            new_price DECIMAL(10,2),
            date DATE,
            status VARCHAR(20) DEFAULT 'Pending'
        )`);
        
        await connection.query(`CREATE TABLE IF NOT EXISTS messages (
            id INT AUTO_INCREMENT PRIMARY KEY,
            sender VARCHAR(100),
            subject VARCHAR(255),
            body TEXT,
            date DATE,
            \`read\` TINYINT(1) DEFAULT 0
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS tasks (
            id INT AUTO_INCREMENT PRIMARY KEY,
            title VARCHAR(255),
            description TEXT,
            due_date DATE,
            status VARCHAR(20) DEFAULT 'Pending'
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS tickets (
            id INT AUTO_INCREMENT PRIMARY KEY,
            category VARCHAR(100),
            description TEXT,
            status VARCHAR(20) DEFAULT 'Open',
            created_at DATETIME,
            updated_at DATETIME,
            resolution TEXT
        )`);
        
        await connection.query(`CREATE TABLE IF NOT EXISTS sales_data (
            id INT AUTO_INCREMENT PRIMARY KEY,
            date DATE UNIQUE,
            net_sales DECIMAL(10,2),
            transaction_count INT,
            cash_over_short DECIMAL(10,2)
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS audit_logs (
            id INT AUTO_INCREMENT PRIMARY KEY,
            user_eid VARCHAR(50),
            action TEXT,
            timestamp DATETIME
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS training_modules (
            id INT AUTO_INCREMENT PRIMARY KEY,
            title VARCHAR(255),
            required_for VARCHAR(100),
            duration VARCHAR(50)
        )`);

        // Seed Users
        const [users] = await connection.query('SELECT * FROM users WHERE eid = ?', ['3756772']);
        if (users.length === 0) {
            const hashedPassword = await bcrypt.hash('3063', 10);
            await connection.query('INSERT INTO users (eid, name, username, password, pin, is_first_login, role) VALUES (?, ?, ?, ?, ?, ?, ?)', 
                ['3756772', 'Tyke', '3063', hashedPassword, '3063', 0, 'admin']);
        }

        // Seed Employees
        const [empCount] = await connection.query('SELECT count(*) as count FROM employees');
        if (empCount[0].count === 0) {
            const emps = [
                ['Coleton', 'Gran-Rolson', 'Store Manager', 22.50], 
                ['Ean', 'Cummings', 'Assistant Store Manager', 18.00], 
                ['Amanda', 'Brown', 'Lead Sales Associate', 14.50], 
                ['Dakota', 'Nielson', 'Lead Sales Associate', 14.50],
                ['Jenna', 'Healey', 'Sales Associate', 12.00],
                ['Holly', 'DenHarthog', 'Sales Associate', 12.00],
                ['Sarah', 'Jenkins', 'Sales Associate', 11.50],
                ['Michael', 'Scott', 'Sales Associate', 11.50]
            ];
            await connection.query('INSERT INTO employees (first_name, last_name, position, wage) VALUES ?', [emps]);
        }

        // Seed Inventory
        const [invCount] = await connection.query('SELECT count(*) as count FROM inventory');
        if (invCount[0].count === 0) {
            const inv = [
                ['10001', 'DG Home Paper Towels 6ct', 45, 5.50, 'Paper'],
                ['10002', 'Clover Valley Spring Water 24pk', 120, 3.65, 'Grocery'],
                ['10003', 'Tide Pods 42ct', 24, 12.95, 'Chemical'],
                ['10004', 'Folgers Classic Roast 30.2oz', 15, 8.95, 'Grocery'],
                ['10005', 'Sweet Smile Gummy Bears 9oz', 50, 1.25, 'Candy'],
                ['10006', 'StoreNET POS Receipt Paper', 10, 0.00, 'Supplies'],
                ['10007', 'Spider Wrap Alarms Large', 50, 0.00, 'LP Equipment'],
                ['10008', 'Clover Valley Cashews Halves & Pieces', 30, 4.50, 'Grocery'],
                ['10009', 'Milk 1 Gal Whole (Local Dairy)', 15, 3.25, 'Fresh'],
                ['10010', 'Wonder Bread White 20oz', 20, 2.85, 'Fresh'],
                ['10011', 'True Living Bath Tissue 12 Mega', 36, 10.00, 'Paper'],
                ['10012', 'Rexall Ibuprofen 200mg 100ct', 15, 4.25, 'HBA'],
                ['10013', 'Gentle Steps Baby Wipes Unscented', 40, 2.00, 'Baby'],
                ['10014', 'Believe Beauty Liquid Foundation', 25, 5.50, 'Cosmetics'],
                ['10015', 'Root to End Replenishing Shampoo', 18, 5.00, 'HBA'],
                ['10016', 'Clover Valley Macaroni & Cheese', 85, 0.50, 'Grocery'],
                ['10017', 'DG Office Copy Paper 500ct', 20, 4.50, 'Stationery'],
                ['10018', 'Gain Fireworks Scent Beads', 12, 7.50, 'Chemical'],
                ['10019', 'Armor All Wipes 30ct', 15, 5.50, 'Auto'],
                ['10020', 'Pedigree Dry Dog Food 18lb', 10, 16.95, 'Pet'],
                ['10021', 'Clover Valley Purified Water 32pk', 180, 4.25, 'Grocery'],
                ['10022', 'DG Health Cough Drops Menthol', 30, 1.50, 'HBA'],
                ['10023', 'Lay\'s Classic Potato Chips 8oz', 25, 3.50, 'Snacks'],
                ['10024', 'Cheetos Puffs 8oz', 20, 3.99, 'Snacks'],
                ['10025', 'Good & Smart Trail Mix', 18, 2.50, 'Snacks'],
                ['10026', 'DG Home Bleach 121oz', 45, 4.50, 'Chemical'],
                ['10027', 'Bounty Select-a-Size 2ct', 24, 6.00, 'Paper'],
                ['10028', 'Kellogg\'s Frosted Flakes', 15, 3.85, 'Grocery'],
                ['10029', 'Coca-Cola 12pk Cans', 60, 6.50, 'Grocery'],
                ['10030', 'Mountain Dew 20oz Bottle', 48, 2.10, 'Grocery']
            ];
            await connection.query('INSERT INTO inventory (sku, name, quantity, price, department) VALUES ?', [inv]);
        }

    } catch (err) {
        console.error('Database Initialization Error:', err);
    } finally {
        connection.release();
    }
}

async function generateDailyData(targetDateStr) {
    const connection = await pool.getConnection();
    try {
        const tasks = [
            ['Verify Milk Expiration', 'Check all fresh coolers for expired milk. Process damages via HHT and dispose in outside dumpster.'],
            ['Recall: Clover Valley Cashews', 'URGENT: Pull all stock of CV Cashews Halves from shelf immediately. Do not sell. Await return auth.'],
            ['Print Super Tuesday Ad Tags', 'Ensure all endcap tags are printed from StoreNET and hung before EOD.'],
            ['Reset Endcap G14', 'Follow MAG (Merchandising Action Guide) for seasonal candy transition. Set POG.'],
            ['Spider Wrap Electronics', 'High shrink alert in district. Ensure all electronics over $15 are wrapped.'],
            ['Work Rolltainers (Chem)', 'Focus on Chemical/Paper rolltainers in backroom. Target: 1 hour per rolltainer.'],
            ['Recover Aisles 4-6', 'Front face all grocery items, pull forward, ensure correct price tags are visible.'],
            ['U-Boat: Pet Food', 'Work the U-Boat of heavy pet food and litter to the sales floor before 5PM.'],
            ['EOD Safe Count', 'Perform final safe count and prep the bank deposit bag.'],
            ['Process Totes', 'Break down 5 mixed totes (HBA/Cosmetics) and put away on sky shelves if overstock.']
        ];
        
        const numTasks = Math.floor(Math.random() * 4) + 3;
        for(let i=0; i<numTasks; i++) {
            const t = tasks[Math.floor(Math.random() * tasks.length)];
            await connection.query('INSERT INTO tasks (title, description, due_date, status) VALUES (?, ?, ?, ?)', [t[0], t[1], targetDateStr, 'Pending']);
        }

        const vendors = ['Frito Lay', 'Coca-Cola', 'PepsiCo', 'Core-Mark', 'Red Bull', 'Nash Finch', 'Fresh Truck', 'Schwans'];
        const numVendors = Math.floor(Math.random() * 3) + 1;
        for(let i=0; i<numVendors; i++) {
            await connection.query('INSERT INTO vendor_deliveries (vendor_name, delivery_date, status) VALUES (?, ?, ?)', [vendors[Math.floor(Math.random() * vendors.length)], targetDateStr, 'Pending']);
        }

        const [invRows] = await connection.query('SELECT sku, price FROM inventory ORDER BY RAND() LIMIT 7');
        for (const r of invRows) {
            const newPrice = (r.price * (Math.random() * 0.3 + 0.9)).toFixed(2);
            if(newPrice != r.price) {
                await connection.query('INSERT INTO price_changes (sku, old_price, new_price, date, status) VALUES (?, ?, ?, ?, ?)', [r.sku, r.price, newPrice, targetDateStr, 'Pending']);
            }
        }

        const prevDate = new Date(new Date(targetDateStr).getTime() - 86400000).toISOString().split('T')[0];
        const [employees] = await connection.query('SELECT id FROM employees');
        const working = employees.sort(() => 0.5 - Math.random()).slice(0, 4);
        for (const emp of working) {
            const inH = Math.floor(Math.random() * 8) + 6;
            const inTime = rndTime(inH, inH);
            const outTime = rndTime(inH + 4, inH + 8);
            let exception = null;
            let outActual = outTime;
            const rand = Math.random();
            if(rand < 0.15) { exception = "Missed Punch"; outActual = "ERR"; }
            else if (rand < 0.25) { exception = "Meal Penalty"; }
            await connection.query('INSERT INTO time_punches (employee_id, date, punch_in, punch_out, approved, exception_type) VALUES (?, ?, ?, ?, ?, ?)', [emp.id, prevDate, inTime, outActual, 0, exception]);
        }

        await connection.query('INSERT IGNORE INTO sales_data (date, net_sales, transaction_count, cash_over_short) VALUES (?, ?, ?, ?)', [prevDate, (Math.random() * 3000 + 4000).toFixed(2), Math.floor(Math.random() * 150 + 250), (Math.random() * 10 - 5).toFixed(2)]);

        const messages = [
            ['District Manager', 'Weekly Conference Call', 'Reminder: Mandatory conference call at 2PM EST. Have your sales metrics ready.'],
            ['Corporate LP', 'High Shrink Alert - Tide Pods', 'We have noticed an uptick in chemical shrink. Please ensure all high value Tide Pods are near register line of sight.'],
            ['Human Resources', 'CBL Compliance Deadline', 'All Store Managers: Ensure your team has completed the new Active Shooter CBL by Friday. Non-compliance will result in write-ups.'],
            ['ERC Support', 'Known Issue: HHT Syncing', 'We are aware of an issue where HHTs are failing to sync inventory adjustments. IT is working on a patch. Do NOT reboot the router.'],
            ['Merchandising', 'Super Tuesday MAG Uploaded', 'The new Merchandising Action Guide for Super Tuesday is now available. Focus on the seasonal drive aisle.'],
            ['Regional VP', 'Great Job Region 12!', 'Just wanted to shout out the amazing recovery efforts seen during my tours last week. Let\'s keep pushing basket size!']
        ];
        if(Math.random() < 0.7) {
            const m = messages[Math.floor(Math.random() * messages.length)];
            await connection.query('INSERT INTO messages (sender, subject, body, date, \`read\`) VALUES (?, ?, ?, ?, ?)', [m[0], m[1], m[2], targetDateStr, 0]);
        }

    } finally {
        connection.release();
    }
}

initDatabase();

module.exports = { pool, generateDailyData };
