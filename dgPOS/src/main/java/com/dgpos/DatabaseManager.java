package com.dgpos;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

public class DatabaseManager {
    private static Properties config = new Properties();
    private static Connection connection = null;

    static {
        try (InputStream input = DatabaseManager.class.getResourceAsStream("/config.properties")) {
            if (input != null) {
                config.load(input);
            } else {
                System.err.println("Warning: config.properties not found. Using defaults.");
                config.setProperty("db.ip", "localhost");
                config.setProperty("db.port", "3306");
                config.setProperty("db.name", "dgpos");
                config.setProperty("db.user", "root");
                config.setProperty("db.password", "root");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                String host = config.getProperty("db.ip", "localhost");
                String port = config.getProperty("db.port", "3306");
                String user = config.getProperty("db.user", "root");
                String pass = config.getProperty("db.password", "root");
                String dbName = config.getProperty("db.name", "dgpos");

                String baseUrl = "jdbc:mysql://" + host + ":" + port + "/?useSSL=false&allowPublicKeyRetrieval=true";
                
                try {
                    // Try to connect to create the database if it doesn't exist
                    Connection tempConn = DriverManager.getConnection(baseUrl, user, pass);
                    tempConn.createStatement().execute("CREATE DATABASE IF NOT EXISTS " + dbName);
                    tempConn.close();
                } catch (Exception e) {
                    // If this fails, the DB might already exist or server might be down, handled below
                    System.err.println("DB Creation check failed: " + e.getMessage());
                }

                String dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true";
                connection = DriverManager.getConnection(dbUrl, user, pass);
                
                setupTables();
            }
            return connection;
        } catch (Exception e) {
            System.err.println("CRITICAL: Database connection failed: " + e.getMessage());
            return null;
        }
    }

    private static void setupTables() {
        try {
            String createInventoryTable = "CREATE TABLE IF NOT EXISTS inventory (" +
                                      "sku VARCHAR(50) PRIMARY KEY, " +
                                      "upc VARCHAR(50) UNIQUE, " +
                                      "name VARCHAR(100) NOT NULL, " +
                                      "brand VARCHAR(100), " +
                                      "variant VARCHAR(100), " +
                                      "size VARCHAR(50), " +
                                      "department VARCHAR(50), " +
                                      "price DECIMAL(10,2) NOT NULL, " +
                                      "unit_price_unit VARCHAR(50) DEFAULT 'per each', " +
                                      "taxable BOOLEAN DEFAULT TRUE, " +
                                      "pog_date VARCHAR(20), " +
                                      "location VARCHAR(50), " +
                                      "faces VARCHAR(10) DEFAULT 'F1', " +
                                      "quantity INT DEFAULT 0)";
            connection.createStatement().execute(createInventoryTable);

            // Patch existing inventory table for new columns
            String[] newCols = {
                "ALTER TABLE inventory ADD COLUMN brand VARCHAR(100)",
                "ALTER TABLE inventory ADD COLUMN variant VARCHAR(100)",
                "ALTER TABLE inventory ADD COLUMN size VARCHAR(50)",
                "ALTER TABLE inventory ADD COLUMN unit_price_unit VARCHAR(50) DEFAULT 'per each'",
                "ALTER TABLE inventory ADD COLUMN taxable BOOLEAN DEFAULT TRUE",
                "ALTER TABLE inventory ADD COLUMN pog_date VARCHAR(20)",
                "ALTER TABLE inventory ADD COLUMN location VARCHAR(50)",
                "ALTER TABLE inventory ADD COLUMN faces VARCHAR(10) DEFAULT 'F1'",
                "ALTER TABLE inventory ADD COLUMN pog_info VARCHAR(255)"
            };
            for (String col : newCols) {
                try {
                    connection.createStatement().execute(col);
                } catch (Exception ignored) {}
            }

            String createLogsTable = "CREATE TABLE IF NOT EXISTS transaction_logs (" +
                                     "id INT AUTO_INCREMENT PRIMARY KEY, " +
                                     "eid VARCHAR(50) NOT NULL, " +
                                     "action VARCHAR(50) NOT NULL, " +
                                     "upc VARCHAR(50), " +
                                     "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
            connection.createStatement().execute(createLogsTable);
            
            String createReceiptsTable = "CREATE TABLE IF NOT EXISTS receipts (" +
                                         "id INT AUTO_INCREMENT PRIMARY KEY, " +
                                         "barcode VARCHAR(20) UNIQUE, " +
                                         "eid VARCHAR(50), " +
                                         "tender_type VARCHAR(20), " +
                                         "total DECIMAL(10,2), " +
                                         "coupon_printed BOOLEAN DEFAULT FALSE, " +
                                         "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
            connection.createStatement().execute(createReceiptsTable);
            
            // Patch receipts table for barcode column if it was missing
            try {
                connection.createStatement().execute("ALTER TABLE receipts ADD COLUMN barcode VARCHAR(20) UNIQUE");
            } catch (Exception ignored) {}
            
            String createReceiptItemsTable = "CREATE TABLE IF NOT EXISTS receipt_items (" +
                                             "id INT AUTO_INCREMENT PRIMARY KEY, " +
                                             "receipt_id INT, " +
                                             "upc VARCHAR(50), " +
                                             "name VARCHAR(100), " +
                                             "price DECIMAL(10,2), " +
                                             "original_price DECIMAL(10,2), " +
                                             "quantity INT, " +
                                             "FOREIGN KEY (receipt_id) REFERENCES receipts(id) ON DELETE CASCADE)";
            connection.createStatement().execute(createReceiptItemsTable);
            
            // Add columns to existing users table if they don't exist (patch for upgrading)
            try {
                connection.createStatement().execute("ALTER TABLE users ADD COLUMN pin VARCHAR(50) NOT NULL DEFAULT '0000'");
                connection.createStatement().execute("ALTER TABLE users ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'SA'");
            } catch (Exception ignored) {
                // Columns likely already exist
            }

            String createUsersTable = "CREATE TABLE IF NOT EXISTS users (" +
                                      "eid VARCHAR(50) PRIMARY KEY, " +
                                      "name VARCHAR(100) NOT NULL, " +
                                      "pin VARCHAR(50) NOT NULL, " +
                                      "role VARCHAR(10) NOT NULL DEFAULT 'SA')";
            connection.createStatement().execute(createUsersTable);
            
            // Seed Dummy Data into inventory
            ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM inventory");
            if (rs.next() && rs.getInt(1) < 4) { // Increased to 4 to include Maybelline
                String seedSql = "INSERT IGNORE INTO inventory (sku, upc, name, brand, variant, size, department, price, pog_date, location, faces) VALUES " +
                    "('1001', '123456789012', 'COCA-COLA 20OZ', 'COCA-COLA', 'ORIGINAL', '20OZ', 'BEVERAGE', 2.25, '03/26', 'A-1', 'F2'), " +
                    "('1002', '028400047685', 'DORITOS NACHO CHS', 'FRITO LAY', 'NACHO', '9.25OZ', 'SNACK', 4.50, '03/26', 'B-2', 'F3'), " +
                    "('1003', '037000874514', 'TIDE PODS 16CT', 'P&G', 'ORIGINAL', '16CT', 'CLEANING', 6.00, '03/26', 'C-1', 'F1'), " +
                    "('54590517', '041554590517', 'Masc 800 LSens', 'Maybelline', 'SkyHigh-BlkBlk', '1ct', 'BEAUTY', 12.25, '03/26', 'A-P', 'F1')";
                connection.createStatement().execute(seedSql);
            }

            // Seed User Data
            ResultSet rsUsers = connection.createStatement().executeQuery("SELECT COUNT(*) FROM users");
            if (rsUsers.next() && rsUsers.getInt(1) == 0) {
                String seedUsersSql = "INSERT IGNORE INTO users (eid, name, pin, role) VALUES " +
                                      "('3756772', 'Tyke', '3063', 'SM'), " +
                                      "('3780722', 'Amanda', '2781', 'SA')";
                connection.createStatement().execute(seedUsersSql);
            } else {
                // In case Tyke already exists without pin, update him, and insert Amanda
                connection.createStatement().execute("UPDATE users SET pin='3063', role='SM' WHERE eid='3756772' AND pin='0000'");
                connection.createStatement().execute("INSERT IGNORE INTO users (eid, name, pin, role) VALUES ('3780722', 'Amanda', '2781', 'SA')");
            }
        } catch (Exception e) {
            System.err.println("Failed to setup tables: " + e.getMessage());
        }
    }

    public static UserData authenticateUser(String eid, String pin) {
        Connection conn = getConnection();
        if (conn == null) return null;
        try (PreparedStatement stmt = conn.prepareStatement("SELECT name, role FROM users WHERE eid = ? AND pin = ?")) {
            stmt.setString(1, eid);
            stmt.setString(2, pin);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new UserData(eid, rs.getString("name"), rs.getString("role"));
            }
        } catch (Exception e) {
            System.err.println("Auth failed: " + e.getMessage());
        }
        return null;
    }

    public static class UserData {
        public final String eid;
        public final String name;
        public final String role;
        public UserData(String eid, String name, String role) {
            this.eid = eid; this.name = name; this.role = role;
        }
    }

    public static String getUserRole(String eid) {
        Connection conn = getConnection();
        if (conn == null) return "SA";
        try (PreparedStatement stmt = conn.prepareStatement("SELECT role FROM users WHERE eid = ?")) {
            stmt.setString(1, eid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            }
        } catch (Exception e) {
            System.err.println("User role lookup failed: " + e.getMessage());
        }
        return "SA";
    }

    public static String getUserName(String eid) {
        Connection conn = getConnection();
        if (conn == null) return eid;
        try (PreparedStatement stmt = conn.prepareStatement("SELECT name FROM users WHERE eid = ?")) {
            stmt.setString(1, eid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        } catch (Exception e) {
            System.err.println("User lookup failed: " + e.getMessage());
        }
        return eid;
    }

    public static ScannedItemData lookupItem(String input) {
        Connection conn = getConnection();
        if (conn == null) return new ScannedItemData(input, "OFFLINE ITEM (" + input + ")", 0.00);
        
        try (PreparedStatement stmt = conn.prepareStatement("SELECT sku, upc, name, price FROM inventory WHERE upc = ? OR sku = ?")) {
            stmt.setString(1, input);
            stmt.setString(2, input);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new ScannedItemData(
                    rs.getString("upc"), 
                    rs.getString("name"), 
                    rs.getDouble("price")
                );
            } else if (input.length() == 12) {
                // Fallback: If 12 digits scanned, try looking up just the first 11
                String shortUpc = input.substring(0, 11);
                stmt.setString(1, shortUpc);
                stmt.setString(2, shortUpc);
                ResultSet rs2 = stmt.executeQuery();
                if (rs2.next()) {
                    return new ScannedItemData(
                        rs2.getString("upc"), 
                        rs2.getString("name"), 
                        rs2.getDouble("price")
                    );
                }
            }
        } catch (Exception e) {
            System.err.println("Lookup failed: " + e.getMessage());
        }
        return new ScannedItemData(input, "UNKNOWN: " + input, 0.00);
    }

    public static void logAction(String eid, String action, String upc) {
        // Run logging in a background thread to prevent UI lag on slow network DBs
        new Thread(() -> {
            Connection conn = getConnection();
            if (conn == null) return;
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO transaction_logs (eid, action, upc) VALUES (?, ?, ?)")) {
                stmt.setString(1, eid);
                stmt.setString(2, action);
                stmt.setString(3, upc);
                stmt.executeUpdate();
            } catch (Exception e) {
                System.err.println("Logging failed: " + e.getMessage());
            }
        }).start();
    }
    
    
    public static long createReceipt(String eid, String tenderType, double total) {
        Connection conn = getConnection();
        if (conn == null) return 1;
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO receipts (eid, tender_type, total) VALUES (?, ?, ?)", 
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, eid);
            stmt.setString(2, tenderType);
            stmt.setDouble(3, total);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            System.err.println("Receipt log failed: " + e.getMessage());
        }
        return System.currentTimeMillis() % 100000;
    }
    
    public static void finalizeReceipt(long receiptId, java.util.List<MainTransactionScreen.ScannedItem> items, boolean couponPrinted) {
        Connection conn = getConnection();
        if (conn == null) return;
        try {
            String barcode = String.format("%020d", receiptId);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE receipts SET coupon_printed = ?, barcode = ? WHERE id = ?")) {
                stmt.setBoolean(1, couponPrinted);
                stmt.setString(2, barcode);
                stmt.setLong(3, receiptId);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO receipt_items (receipt_id, upc, name, price, original_price, quantity) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (MainTransactionScreen.ScannedItem item : items) {
                    stmt.setLong(1, receiptId);
                    stmt.setString(2, item.getUpc());
                    stmt.setString(3, item.getName());
                    stmt.setDouble(4, item.getBasePrice());
                    stmt.setDouble(5, item.getOriginalPrice());
                    stmt.setInt(6, item.getQuantity());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        } catch (Exception e) {
            System.err.println("Finalize receipt failed: " + e.getMessage());
        }
    }
    
    public static ReceiptData lookupReceipt(String barcode) {
        Connection conn = getConnection();
        if (conn == null) return null;
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM receipts WHERE barcode = ?")) {
            stmt.setString(1, barcode);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long id = rs.getLong("id");
                String tenderType = rs.getString("tender_type");
                double total = rs.getDouble("total");
                
                java.util.List<ScannedItemData> items = new java.util.ArrayList<>();
                try (PreparedStatement itemStmt = conn.prepareStatement("SELECT * FROM receipt_items WHERE receipt_id = ?")) {
                    itemStmt.setLong(1, id);
                    ResultSet irs = itemStmt.executeQuery();
                    while (irs.next()) {
                        items.add(new ScannedItemData(
                            irs.getString("upc"),
                            irs.getString("name"),
                            irs.getDouble("price")
                        ));
                    }
                }
                return new ReceiptData(id, barcode, tenderType, total, items);
            }
        } catch (Exception e) {
            System.err.println("Receipt lookup failed: " + e.getMessage());
        }
        return null;
    }

    public static class ReceiptData {
        public final long id;
        public final String barcode;
        public final String tenderType;
        public final double total;
        public final java.util.List<ScannedItemData> items;

        public ReceiptData(long id, String barcode, String tenderType, double total, java.util.List<ScannedItemData> items) {
            this.id = id; this.barcode = barcode; this.tenderType = tenderType; this.total = total; this.items = items;
        }
    }

    public static class ScannedItemData {
        public final String upc;
        public final String name;
        public final double price;

        public ScannedItemData(String upc, String name, double price) {
            this.upc = upc; this.name = name; this.price = price;
        }
    }
}