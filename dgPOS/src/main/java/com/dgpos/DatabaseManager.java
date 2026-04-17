package com.dgpos;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class DatabaseManager {
    private static final Properties config = new Properties();
    private static HikariDataSource dataSource = null;

    static {
        // Step 1: Load config
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

        String host   = config.getProperty("db.ip",       "localhost");
        String port   = config.getProperty("db.port",     "3306");
        String user   = config.getProperty("db.user",     "root");
        String pass   = config.getProperty("db.password", "root");
        String dbName = config.getProperty("db.name",     "dgpos");

        // Step 2: Create the database if it doesn't exist yet
        String baseUrl = "jdbc:mysql://" + host + ":" + port + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection tempConn = DriverManager.getConnection(baseUrl, user, pass);
             Statement st = tempConn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + dbName);
        } catch (Exception e) {
            System.err.println("DB creation check failed: " + e.getMessage());
        }

        // Step 3: Initialize connection pool
        try {
            String dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(dbUrl);
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(pass);
            hikariConfig.setMaximumPoolSize(5);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(5000);
            dataSource = new HikariDataSource(hikariConfig);

            // Step 4: Setup schema on a pooled connection
            try (Connection conn = dataSource.getConnection()) {
                setupTables(conn);
            }
        } catch (Exception e) {
            System.err.println("CRITICAL: DatabaseManager initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Connection getConnection() {
        try {
            if (dataSource == null) return null;
            return dataSource.getConnection();
        } catch (Exception e) {
            System.err.println("CRITICAL: Database connection failed: " + e.getMessage());
            return null;
        }
    }

    private static void setupTables(Connection conn) {
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS inventory (" +
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
                        "quantity INT DEFAULT 0)");
            }

            // Patch existing inventory table for new columns
            String[] inventoryCols = {
                "ALTER TABLE inventory ADD COLUMN brand VARCHAR(100)",
                "ALTER TABLE inventory ADD COLUMN variant VARCHAR(100)",
                "ALTER TABLE inventory ADD COLUMN size VARCHAR(50)",
                "ALTER TABLE inventory ADD COLUMN unit_price_unit VARCHAR(50) DEFAULT 'per each'",
                "ALTER TABLE inventory ADD COLUMN taxable BOOLEAN DEFAULT TRUE",
                "ALTER TABLE inventory ADD COLUMN pog_date VARCHAR(20)",
                "ALTER TABLE inventory ADD COLUMN location VARCHAR(50)",
                "ALTER TABLE inventory ADD COLUMN faces VARCHAR(10) DEFAULT 'F1'",
                "ALTER TABLE inventory ADD COLUMN pog_info VARCHAR(255)",
                "ALTER TABLE inventory ADD COLUMN reg_price DECIMAL(10,2) DEFAULT NULL",
                "ALTER TABLE inventory ADD COLUMN sale_id VARCHAR(50) DEFAULT NULL"
            };
            for (String col : inventoryCols) {
                try (Statement st = conn.createStatement()) {
                    st.execute(col);
                } catch (Exception ignored) {}
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS transaction_logs (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "eid VARCHAR(50) NOT NULL, " +
                        "action VARCHAR(50) NOT NULL, " +
                        "upc VARCHAR(50), " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

                st.execute("CREATE TABLE IF NOT EXISTS receipts (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "barcode VARCHAR(20) UNIQUE, " +
                        "eid VARCHAR(50), " +
                        "tender_type VARCHAR(20), " +
                        "total DECIMAL(10,2), " +
                        "coupon_printed BOOLEAN DEFAULT FALSE, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            }

            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE receipts ADD COLUMN barcode VARCHAR(20) UNIQUE");
            } catch (Exception ignored) {}

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS receipt_items (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "receipt_id INT, " +
                        "upc VARCHAR(50), " +
                        "sku VARCHAR(50), " +
                        "name VARCHAR(100), " +
                        "price DECIMAL(10,2), " +
                        "original_price DECIMAL(10,2), " +
                        "quantity INT, " +
                        "taxable BOOLEAN DEFAULT TRUE, " +
                        "FOREIGN KEY (receipt_id) REFERENCES receipts(id) ON DELETE CASCADE)");
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS time_punches (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "eid VARCHAR(50) NOT NULL, " +
                        "action ENUM('CLOCK_IN','CLOCK_OUT','BREAK_IN','BREAK_OUT') NOT NULL, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS pickups (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "eid VARCHAR(50) NOT NULL, " +
                        "amount DECIMAL(10,2) NOT NULL, " +
                        "authorized_by VARCHAR(50), " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                st.execute("CREATE TABLE IF NOT EXISTS till_config (" +
                        "`key` VARCHAR(50) PRIMARY KEY, " +
                        "value TEXT)");
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS promotions (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "type ENUM('WEEKLY','SATURDAY') NOT NULL, " +
                        "title VARCHAR(100) NOT NULL, " +
                        "discount DECIMAL(10,2) NOT NULL, " +
                        "minimum DECIMAL(10,2) NOT NULL, " +
                        "fine_print TEXT, " +
                        "valid_date VARCHAR(50), " +
                        "active BOOLEAN DEFAULT TRUE, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            }

            // Patch receipt_items for sku and taxable columns if missing
            String[] receiptItemsCols = {
                "ALTER TABLE receipt_items ADD COLUMN sku VARCHAR(50)",
                "ALTER TABLE receipt_items ADD COLUMN taxable BOOLEAN DEFAULT TRUE"
            };
            for (String col : receiptItemsCols) {
                try (Statement st = conn.createStatement()) {
                    st.execute(col);
                } catch (Exception ignored) {}
            }

            // Patch users table before creating it (for upgrades from older schema)
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE users ADD COLUMN pin VARCHAR(50) NOT NULL DEFAULT '0000'");
            } catch (Exception ignored) {}
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE users ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'SA'");
            } catch (Exception ignored) {}

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "eid VARCHAR(50) PRIMARY KEY, " +
                        "name VARCHAR(100) NOT NULL, " +
                        "pin VARCHAR(50) NOT NULL, " +
                        "role VARCHAR(10) NOT NULL DEFAULT 'SA')");
            }

            // Seed inventory if nearly empty
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM inventory")) {
                if (rs.next() && rs.getInt(1) < 4) {
                    try (Statement ins = conn.createStatement()) {
                        ins.execute("INSERT IGNORE INTO inventory " +
                                "(sku, upc, name, brand, variant, size, department, price, pog_date, location, faces) VALUES " +
                                "('1001', '123456789012', 'COCA-COLA 20OZ', 'COCA-COLA', 'ORIGINAL', '20OZ', 'BEVERAGE', 2.25, '03/26', 'A-1', 'F2'), " +
                                "('1002', '028400047685', 'DORITOS NACHO CHS', 'FRITO LAY', 'NACHO', '9.25OZ', 'SNACK', 4.50, '03/26', 'B-2', 'F3'), " +
                                "('1003', '037000874514', 'TIDE PODS 16CT', 'P&G', 'ORIGINAL', '16CT', 'CLEANING', 6.00, '03/26', 'C-1', 'F1'), " +
                                "('54590517', '041554590517', 'Masc 800 LSens', 'Maybelline', 'SkyHigh-BlkBlk', '1ct', 'BEAUTY', 12.25, '03/26', 'A-P', 'F1')");
                    }
                }
            }

            // Seed users
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (Statement ins = conn.createStatement()) {
                        ins.execute("INSERT IGNORE INTO users (eid, name, pin, role) VALUES " +
                                "('3756772', 'Tyke', '3063', 'SM'), " +
                                "('3780722', 'Amanda', '2781', 'SA')");
                    }
                } else {
                    try (Statement upd = conn.createStatement()) {
                        upd.execute("UPDATE users SET pin='3063', role='SM' WHERE eid='3756772' AND pin='0000'");
                        upd.execute("INSERT IGNORE INTO users (eid, name, pin, role) VALUES ('3780722', 'Amanda', '2781', 'SA')");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to setup tables: " + e.getMessage());
        }
    }

    public static UserData authenticateUser(String eid, String pin) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT name, role FROM users WHERE eid = ? AND pin = ?")) {
            stmt.setString(1, eid);
            stmt.setString(2, pin);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new UserData(eid, rs.getString("name"), rs.getString("role"));
                }
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
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT role FROM users WHERE eid = ?")) {
            stmt.setString(1, eid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("role");
            }
        } catch (Exception e) {
            System.err.println("User role lookup failed: " + e.getMessage());
        }
        return "SA";
    }

    public static String getUserName(String eid) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT name FROM users WHERE eid = ?")) {
            stmt.setString(1, eid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("name");
            }
        } catch (Exception e) {
            System.err.println("User lookup failed: " + e.getMessage());
        }
        return eid;
    }

    public static ScannedItemData lookupItem(String input) {
        if (dataSource == null) {
            return new ScannedItemData(input, input, "OFFLINE ITEM (" + input + ")", 0.00, 0.00, null, true);
        }

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT sku, upc, name, price, reg_price, sale_id, taxable FROM inventory WHERE upc = ? OR sku = ?")) {
            stmt.setString(1, input);
            stmt.setString(2, input);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return buildScannedItemData(rs);
                }
            }

            // Fallback: 12-digit scan → try first 11
            if (input.length() == 12) {
                String shortUpc = input.substring(0, 11);
                stmt.setString(1, shortUpc);
                stmt.setString(2, shortUpc);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return buildScannedItemData(rs);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Lookup failed: " + e.getMessage());
        }
        return new ScannedItemData(input, input, "UNKNOWN: " + input, 0.00, 0.00, null, true);
    }

    private static ScannedItemData buildScannedItemData(ResultSet rs) throws Exception {
        double price = rs.getDouble("price");
        double regPrice = rs.getDouble("reg_price");
        if (rs.wasNull()) regPrice = price;
        return new ScannedItemData(
                rs.getString("upc"),
                rs.getString("sku"),
                rs.getString("name"),
                price,
                regPrice,
                rs.getString("sale_id"),
                rs.getBoolean("taxable")
        );
    }

    public static void logAction(String eid, String action, String upc) {
        // Each background thread gets its own connection from the pool — thread-safe
        new Thread(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO transaction_logs (eid, action, upc) VALUES (?, ?, ?)")) {
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
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO receipts (eid, tender_type, total) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
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
        // Fallback: negative value signals offline mode to the caller
        return -1L;
    }

    public static void finalizeReceipt(long receiptId, java.util.List<MainTransactionScreen.ScannedItem> items, boolean couponPrinted) {
        if (receiptId < 0) return; // Offline — nothing to finalize
        try (Connection conn = getConnection()) {
            String barcode = String.format("%020d", receiptId);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE receipts SET coupon_printed = ?, barcode = ? WHERE id = ?")) {
                stmt.setBoolean(1, couponPrinted);
                stmt.setString(2, barcode);
                stmt.setLong(3, receiptId);
                stmt.executeUpdate();
            }

            try (PreparedStatement insertStmt = conn.prepareStatement(
                         "INSERT INTO receipt_items (receipt_id, upc, sku, name, price, original_price, quantity, taxable) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement updateStmt = conn.prepareStatement(
                         "UPDATE inventory SET quantity = quantity - ? WHERE upc = ? OR sku = ?")) {

                for (MainTransactionScreen.ScannedItem item : items) {
                    insertStmt.setLong(1, receiptId);
                    insertStmt.setString(2, item.getUpc());
                    insertStmt.setString(3, item.getSku());
                    insertStmt.setString(4, item.getName());
                    insertStmt.setDouble(5, item.getBasePrice());
                    insertStmt.setDouble(6, item.getOriginalPrice());
                    insertStmt.setInt(7, item.getQuantity());
                    insertStmt.setBoolean(8, item.isTaxable());
                    insertStmt.addBatch();

                    // Returns have "RTN: " or "REFUND: " prefix — invert quantity to add back to stock
                    int q = item.getQuantity();
                    if (item.getName().startsWith("RTN: ") || item.getName().startsWith("REFUND: ")) {
                        q = -q;
                    }
                    updateStmt.setInt(1, q);
                    updateStmt.setString(2, item.getUpc());
                    updateStmt.setString(3, item.getSku()); // Use actual SKU, not UPC again
                    updateStmt.addBatch();
                }
                insertStmt.executeBatch();
                updateStmt.executeBatch();
            }
        } catch (Exception e) {
            System.err.println("Finalize receipt failed: " + e.getMessage());
        }
    }

    public static ReceiptData lookupReceipt(String barcode) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM receipts WHERE barcode = ?")) {
            stmt.setString(1, barcode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    String tenderType = rs.getString("tender_type");
                    double total = rs.getDouble("total");

                    java.util.List<ScannedItemData> items = new java.util.ArrayList<>();
                    // LEFT JOIN inventory to recover the taxable flag for refund processing
                    try (PreparedStatement itemStmt = conn.prepareStatement(
                            "SELECT ri.upc, ri.sku, ri.name, ri.price, ri.original_price, " +
                            "COALESCE(ri.taxable, inv.taxable, 1) AS taxable " +
                            "FROM receipt_items ri " +
                            "LEFT JOIN inventory inv ON ri.upc = inv.upc " +
                            "WHERE ri.receipt_id = ?")) {
                        itemStmt.setLong(1, id);
                        try (ResultSet irs = itemStmt.executeQuery()) {
                            while (irs.next()) {
                                double price = irs.getDouble("price");
                                double originalPrice = irs.getDouble("original_price");
                                if (irs.wasNull()) originalPrice = price;
                                String sku = irs.getString("sku");
                                String upc = irs.getString("upc");
                                items.add(new ScannedItemData(
                                        upc,
                                        sku != null ? sku : upc,
                                        irs.getString("name"),
                                        price,
                                        originalPrice,
                                        null,
                                        irs.getBoolean("taxable")
                                ));
                            }
                        }
                    }
                    return new ReceiptData(id, barcode, tenderType, total, items);
                }
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
        public final String sku;
        public final String name;
        public final double price;
        public final double regPrice;
        public final String saleId;
        public final boolean taxable;

        public ScannedItemData(String upc, String sku, String name, double price, double regPrice, String saleId, boolean taxable) {
            this.upc = upc; this.sku = sku != null ? sku : upc;
            this.name = name; this.price = price; this.regPrice = regPrice;
            this.saleId = saleId; this.taxable = taxable;
        }
    }

    public static void logTimePunch(String eid, String action) {
        new Thread(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO time_punches (eid, action) VALUES (?, ?)")) {
                stmt.setString(1, eid);
                stmt.setString(2, action);
                stmt.executeUpdate();
            } catch (Exception e) {
                System.err.println("Time punch failed: " + e.getMessage());
            }
        }).start();
    }

    public static class PromotionData {
        public final String type, title, validDate, finePrint;
        public final double discount, minimum;
        public PromotionData(String type, String title, double discount, double minimum, String validDate, String finePrint) {
            this.type = type; this.title = title; this.discount = discount;
            this.minimum = minimum; this.validDate = validDate != null ? validDate : "";
            this.finePrint = finePrint != null ? finePrint : "";
        }
    }

    public static PromotionData getActivePromotion(String type) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT type, title, discount, minimum, valid_date, fine_print FROM promotions WHERE type = ? AND active = TRUE ORDER BY id DESC LIMIT 1")) {
            stmt.setString(1, type);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new PromotionData(
                            rs.getString("type"),
                            rs.getString("title"),
                            rs.getDouble("discount"),
                            rs.getDouble("minimum"),
                            rs.getString("valid_date"),
                            rs.getString("fine_print")
                    );
                }
            }
        } catch (Exception e) {
            System.err.println("Promotion lookup failed: " + e.getMessage());
        }
        return null;
    }

    public static LabelData lookupLabelData(String input) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT upc, name, brand, variant, size, price, unit_price_unit, taxable, pog_date, location, faces " +
                     "FROM inventory WHERE upc = ? OR sku = ?")) {
            stmt.setString(1, input);
            stmt.setString(2, input);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new LabelData(
                            rs.getString("upc"), rs.getString("name"),
                            rs.getString("brand"), rs.getString("variant"), rs.getString("size"),
                            rs.getDouble("price"), rs.getString("unit_price_unit"),
                            rs.getBoolean("taxable"),
                            rs.getString("pog_date"), rs.getString("location"), rs.getString("faces")
                    );
                }
            }
        } catch (Exception e) {
            System.err.println("Label lookup failed: " + e.getMessage());
        }
        return null;
    }

    public static class LabelData {
        public final String upc, name, brand, variant, size, unitPriceUnit, pogDate, location, faces;
        public final double price;
        public final boolean taxable;

        public LabelData(String upc, String name, String brand, String variant, String size,
                         double price, String unitPriceUnit, boolean taxable,
                         String pogDate, String location, String faces) {
            this.upc = upc; this.name = name; this.brand = brand; this.variant = variant;
            this.size = size; this.price = price;
            this.unitPriceUnit = unitPriceUnit != null ? unitPriceUnit : "per each";
            this.taxable = taxable;
            this.pogDate = pogDate != null ? pogDate : "";
            this.location = location != null ? location : "";
            this.faces = faces != null ? faces : "F1";
        }
    }

    // --- TILL / DRAWER MANAGEMENT ---

    public static void recordPickup(String eid, double amount, String authorizedBy) {
        new Thread(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO pickups (eid, amount, authorized_by) VALUES (?, ?, ?)")) {
                stmt.setString(1, eid);
                stmt.setDouble(2, amount);
                stmt.setString(3, authorizedBy);
                stmt.executeUpdate();
            } catch (Exception e) {
                System.err.println("Pickup recording failed: " + e.getMessage());
            }
        }).start();
    }

    public static void setHeldDrawer(String eid, String name) {
        try (Connection conn = getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO till_config (`key`, value) VALUES ('held_eid', ?) ON DUPLICATE KEY UPDATE value = ?")) {
                stmt.setString(1, eid); stmt.setString(2, eid); stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO till_config (`key`, value) VALUES ('held_name', ?) ON DUPLICATE KEY UPDATE value = ?")) {
                stmt.setString(1, name); stmt.setString(2, name); stmt.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("Set held drawer failed: " + e.getMessage());
        }
    }

    public static UserData getHeldDrawer() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT `key`, value FROM till_config WHERE `key` IN ('held_eid', 'held_name')")) {
            try (ResultSet rs = stmt.executeQuery()) {
                String eid = null, name = null;
                while (rs.next()) {
                    if ("held_eid".equals(rs.getString("key"))) eid = rs.getString("value");
                    else if ("held_name".equals(rs.getString("key"))) name = rs.getString("value");
                }
                if (eid != null && name != null) return new UserData(eid, name, "");
            }
        } catch (Exception e) {
            System.err.println("Get held drawer failed: " + e.getMessage());
        }
        return null;
    }

    public static void clearHeldDrawer() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM till_config WHERE `key` IN ('held_eid', 'held_name')")) {
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Clear held drawer failed: " + e.getMessage());
        }
    }

    public static void setStartingBank(double amount) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO till_config (`key`, value) VALUES ('starting_bank', ?) ON DUPLICATE KEY UPDATE value = ?")) {
            stmt.setString(1, String.valueOf(amount));
            stmt.setString(2, String.valueOf(amount));
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("Set starting bank failed: " + e.getMessage());
        }
    }

    public static double getStartingBank() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT value FROM till_config WHERE `key` = 'starting_bank'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Double.parseDouble(rs.getString("value"));
            }
        } catch (Exception e) {
            System.err.println("Get starting bank failed: " + e.getMessage());
        }
        return 150.00; // default if never set
    }

    public static class ZOutData {
        public final String cashierEid, cashierName;
        public final double cashSales, cardSales, totalSavings;
        public final java.util.List<Double> pickups;
        public final double startingBank;

        public ZOutData(String cashierEid, String cashierName, double cashSales, double cardSales,
                        double totalSavings, java.util.List<Double> pickups) {
            this.cashierEid = cashierEid;
            this.cashierName = cashierName;
            this.cashSales = cashSales;
            this.cardSales = cardSales;
            this.totalSavings = totalSavings;
            this.pickups = pickups;
            this.startingBank = getStartingBank();
        }

        public double getTotalPickups() {
            return pickups.stream().mapToDouble(Double::doubleValue).sum();
        }

        public double getExpectedCash() {
            return startingBank + cashSales - getTotalPickups();
        }
    }

    public static ZOutData getZOutData(String eid) {
        String name = getUserName(eid);
        double cashSales = 0, cardSales = 0, totalSavings = 0;
        java.util.List<Double> pickups = new java.util.ArrayList<>();

        try (Connection conn = getConnection()) {
            // Find when this session started (most recent LOGIN for this eid)
            java.sql.Timestamp sessionStart = null;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT timestamp FROM transaction_logs WHERE eid = ? AND action = 'LOGIN' " +
                    "ORDER BY timestamp DESC LIMIT 1")) {
                stmt.setString(1, eid);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) sessionStart = rs.getTimestamp("timestamp");
                }
            }

            // Sum cash and card sales since session start
            String salesSql = "SELECT " +
                    "SUM(CASE WHEN tender_type='CASH' AND total > 0 THEN total ELSE 0 END) AS cash_sales, " +
                    "SUM(CASE WHEN tender_type='CARD' AND total > 0 THEN total ELSE 0 END) AS card_sales " +
                    "FROM receipts WHERE eid = ?" +
                    (sessionStart != null ? " AND timestamp >= ?" : "");
            try (PreparedStatement stmt = conn.prepareStatement(salesSql)) {
                stmt.setString(1, eid);
                if (sessionStart != null) stmt.setTimestamp(2, sessionStart);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        cashSales = rs.getDouble("cash_sales");
                        cardSales = rs.getDouble("card_sales");
                    }
                }
            }

            // Sum sale savings from receipt items
            String savingsSql = "SELECT COALESCE(SUM(GREATEST(ri.original_price - ri.price, 0) * ri.quantity), 0) AS savings " +
                    "FROM receipts r JOIN receipt_items ri ON r.id = ri.receipt_id " +
                    "WHERE r.eid = ? AND r.total > 0" +
                    (sessionStart != null ? " AND r.timestamp >= ?" : "");
            try (PreparedStatement stmt = conn.prepareStatement(savingsSql)) {
                stmt.setString(1, eid);
                if (sessionStart != null) stmt.setTimestamp(2, sessionStart);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) totalSavings = rs.getDouble("savings");
                }
            }

            // Get pickups since session start
            String pickupSql = "SELECT amount FROM pickups WHERE eid = ?" +
                    (sessionStart != null ? " AND created_at >= ?" : "") +
                    " ORDER BY created_at";
            try (PreparedStatement stmt = conn.prepareStatement(pickupSql)) {
                stmt.setString(1, eid);
                if (sessionStart != null) stmt.setTimestamp(2, sessionStart);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) pickups.add(rs.getDouble("amount"));
                }
            }
        } catch (Exception e) {
            System.err.println("ZOut data query failed: " + e.getMessage());
        }

        return new ZOutData(eid, name, cashSales, cardSales, totalSavings, pickups);
    }
}
