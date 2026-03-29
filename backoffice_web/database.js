const mysql = require('mysql2/promise');
const bcrypt = require('bcrypt');

// Central Enterprise Config
const enterpriseConfig = {
    host: '192.168.0.192',
    user: 'root',
    password: 'jjgh5879921pomn##',
    database: 'storenet_enterprise',
    waitForConnections: true,
    connectionLimit: 5,
    queueLimit: 0
};

let enterprisePool;
const storePools = new Map();

async function initEnterpriseDatabase() {
    const tempConn = await mysql.createConnection({
        host: enterpriseConfig.host,
        user: enterpriseConfig.user,
        password: enterpriseConfig.password
    });
    try {
        await tempConn.query(`CREATE DATABASE IF NOT EXISTS storenet_enterprise`);
    } finally {
        await tempConn.end();
    }

    enterprisePool = mysql.createPool(enterpriseConfig);
    const connection = await enterprisePool.getConnection();
    try {
        await connection.query(`CREATE TABLE IF NOT EXISTS stores (
            id INT PRIMARY KEY,
            name VARCHAR(100),
            ip_address VARCHAR(50),
            db_name VARCHAR(50),
            db_user VARCHAR(50),
            db_password VARCHAR(100),
            status VARCHAR(20) DEFAULT 'Online'
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS global_users (
            id INT AUTO_INCREMENT PRIMARY KEY,
            eid VARCHAR(50) UNIQUE NOT NULL,
            username VARCHAR(50),
            password VARCHAR(255),
            role VARCHAR(20) DEFAULT 'admin'
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS master_inventory (
            sku VARCHAR(50) PRIMARY KEY,
            upc VARCHAR(50) UNIQUE,
            name VARCHAR(100),
            department VARCHAR(50),
            std_price DECIMAL(10,2)
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS pricing_events (
            id INT AUTO_INCREMENT PRIMARY KEY,
            name VARCHAR(100),
            type ENUM('CLEARANCE', 'MOS', 'SALE') DEFAULT 'SALE',
            description TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS event_items (
            id INT AUTO_INCREMENT PRIMARY KEY,
            event_id INT,
            sku VARCHAR(50),
            price DECIMAL(10,2),
            FOREIGN KEY (event_id) REFERENCES pricing_events(id) ON DELETE CASCADE,
            FOREIGN KEY (sku) REFERENCES master_inventory(sku) ON UPDATE CASCADE ON DELETE CASCADE
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS enterprise_messages (
            id INT AUTO_INCREMENT PRIMARY KEY,
            sender_eid VARCHAR(50),
            subject VARCHAR(255),
            body TEXT,
            target_type VARCHAR(20),
            target_id VARCHAR(50),
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS planograms (
            id INT AUTO_INCREMENT PRIMARY KEY,
            pog_id VARCHAR(50) UNIQUE,
            name VARCHAR(100),
            dimensions VARCHAR(20),
            suffix VARCHAR(20),
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )`);

        await connection.query(`CREATE TABLE IF NOT EXISTS planogram_items (
            id INT AUTO_INCREMENT PRIMARY KEY,
            planogram_id INT,
            sku VARCHAR(50),
            section VARCHAR(10),
            shelf VARCHAR(10),
            faces VARCHAR(10) DEFAULT 'F1',
            position INT DEFAULT 1,
            FOREIGN KEY (planogram_id) REFERENCES planograms(id) ON DELETE CASCADE,
            FOREIGN KEY (sku) REFERENCES master_inventory(sku) ON UPDATE CASCADE ON DELETE CASCADE
        )`);

        try {
            await connection.query(`ALTER TABLE planogram_items ADD COLUMN position INT DEFAULT 1`);
        } catch (e) {}

        await connection.query(`CREATE TABLE IF NOT EXISTS push_logs (
            id INT AUTO_INCREMENT PRIMARY KEY,
            store_id INT,
            event_id INT,
            pushed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (store_id) REFERENCES stores(id)
        )`);

        // Seed Super Admin if empty
        const [existingAdmins] = await connection.query('SELECT * FROM global_users WHERE eid = ?', ['3756772']);
        if (existingAdmins.length === 0) {
            const hashedPassword = await bcrypt.hash('3063', 10);
            await connection.query('INSERT INTO global_users (eid, username, password, role) VALUES (?, ?, ?, ?)',
                ['3756772', 'admin', hashedPassword, 'super_admin']);
        }

    } finally {
        connection.release();
    }
}

async function getStorePool(storeId) {
    if (storePools.has(storeId)) return storePools.get(storeId);
    const [rows] = await enterprisePool.query('SELECT * FROM stores WHERE id = ?', [storeId]);
    if (rows.length === 0) throw new Error(`Store #${storeId} not found.`);
    const s = rows[0];
    const newPool = mysql.createPool({
        host: s.ip_address, user: s.db_user, password: s.db_password, database: s.db_name,
        waitForConnections: true, connectionLimit: 10, queueLimit: 0
    });
    storePools.set(storeId, newPool);
    return newPool;
}

module.exports = { enterprisePool: () => enterprisePool, initEnterpriseDatabase, getStorePool };
