const fs = require('fs');

try {
    // 1. Patch DatabaseManager.java
    let db = fs.readFileSync('src/main/java/com/dgpos/DatabaseManager.java', 'utf8');

    db = db.replace(
        'connection.createStatement().execute(createLogsTable);',
        `connection.createStatement().execute(createLogsTable);
            
            String createReceiptsTable = "CREATE TABLE IF NOT EXISTS receipts (" +
                                         "id INT AUTO_INCREMENT PRIMARY KEY, " +
                                         "barcode VARCHAR(20) UNIQUE, " +
                                         "eid VARCHAR(50), " +
                                         "tender_type VARCHAR(20), " +
                                         "total DECIMAL(10,2), " +
                                         "coupon_printed BOOLEAN DEFAULT FALSE, " +
                                         "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
            connection.createStatement().execute(createReceiptsTable);
            
            String createReceiptItemsTable = "CREATE TABLE IF NOT EXISTS receipt_items (" +
                                             "id INT AUTO_INCREMENT PRIMARY KEY, " +
                                             "receipt_id INT, " +
                                             "upc VARCHAR(50), " +
                                             "name VARCHAR(100), " +
                                             "price DECIMAL(10,2), " +
                                             "original_price DECIMAL(10,2), " +
                                             "quantity INT, " +
                                             "FOREIGN KEY (receipt_id) REFERENCES receipts(id) ON DELETE CASCADE)";
            connection.createStatement().execute(createReceiptItemsTable);`
    );

    const dbMethods = `
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
    `;

    db = db.replace('public static class ScannedItemData {', dbMethods + '\n    public static class ScannedItemData {');
    fs.writeFileSync('src/main/java/com/dgpos/DatabaseManager.java', db);

    // 2. Patch PrinterService.java
    let printer = fs.readFileSync('src/main/java/com/dgpos/PrinterService.java', 'utf8');

    const oldPrintReceipt = `public static void printReceipt(List<MainTransactionScreen.ScannedItem> items, String eid, String tenderType) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(INIT);
            baos.write(CENTER);
            baos.write(BOLD_ON);
            baos.write("DOLLAR GENERAL STORE #14302\\n".getBytes());
            baos.write("216 BELKNAP ST\\n".getBytes());
            baos.write("SUPERIOR, WI 54880\\n".getBytes());
            baos.write("(715) 718-6650\\n".getBytes());
            baos.write("SALE TRANSACTION\\n\\n".getBytes());
            baos.write(BOLD_OFF);

            baos.write(LEFT);
            double subtotal = 0;
            for (MainTransactionScreen.ScannedItem item : items) {
                String name = item.getName();
                if (name.length() > 24) name = name.substring(0, 24);
                
                String priceStr = String.format("$%.2f", item.getPriceValue());
                String line = String.format("%-25s %7s\\n", name, priceStr);
                baos.write(line.getBytes());
                subtotal += item.getPriceValue();
            }
            baos.write("\\n".getBytes());

            baos.write(RIGHT);
            double tax = subtotal * 0.055;
            double total = subtotal + tax;
            baos.write(String.format("Tax:  $%.2f @ 5.5%%   $%.2f\\n", subtotal, tax).getBytes());
            baos.write(BOLD_ON);
            baos.write(String.format("Balance to pay       $%.2f\\n", total).getBytes());
            baos.write(BOLD_OFF);
            baos.write(String.format("%-20s $%.2f\\n\\n", tenderType, total).getBytes());

            baos.write(LEFT);
            String dateStr = new SimpleDateFormat("MM-dd-yy hh:mm a").format(new Date());
            baos.write("STORE 14302   TILL 1   TRANS. 120101\\n".getBytes());
            baos.write(("DATE " + dateStr + "\\n\\n").getBytes());
            baos.write(("Your cashier was: " + DatabaseManager.getUserName(eid).toUpperCase() + "\\n\\n").getBytes());

            // Barcode (Code 39) - 1d 6b 04 [data] 00
            baos.write(CENTER);
            baos.write(new byte[]{0x1d, 0x68, 0x50}); // Height 80
            baos.write(new byte[]{0x1d, 0x77, 0x01}); // Width 1
            baos.write(new byte[]{0x1d, 0x48, 0x00}); // HRI disabled
            baos.write(new byte[]{0x1d, 0x6b, 0x04}); // Code 39
            baos.write("99902143020021201013".getBytes());
            baos.write(0x00);
            baos.write(("\\n*99902143020021201013*\\n\\n").getBytes());

            baos.write(FEED_3);
            baos.write(CUT);

            sendToPrinter(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }`;

    const newPrintReceipt = `public static void printReceipt(List<MainTransactionScreen.ScannedItem> items, String eid, String tenderType, String barcode) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(INIT);
            baos.write(CENTER);
            baos.write(BOLD_ON);
            baos.write("DOLLAR GENERAL STORE #14302\\n".getBytes());
            baos.write("216 BELKNAP ST\\n".getBytes());
            baos.write("SUPERIOR, WI 54880\\n".getBytes());
            baos.write("(715) 718-6650\\n".getBytes());
            baos.write("SALE TRANSACTION\\n\\n".getBytes());
            baos.write(BOLD_OFF);

            baos.write(LEFT);
            double subtotal = 0;
            for (MainTransactionScreen.ScannedItem item : items) {
                String name = item.getName();
                String upc = item.getUpc();
                double price = item.getPriceValue();
                double origPriceTotal = item.getOriginalPrice() * item.getQuantity();

                // Layer 1: Name
                baos.write((name + "\\n").getBytes());
                
                // Layer 2: UPC (and qty if multiple)
                if (item.getQuantity() > 1) {
                    baos.write(("  " + upc + "   Qty: " + item.getQuantity() + "\\n").getBytes());
                } else {
                    baos.write(("  " + upc + "\\n").getBytes());
                }

                // Layer 3: Price and Savings
                String priceStr = String.format("$%.2f", price);
                if (origPriceTotal > price && price >= 0) { // Don't show savings on returns
                    double savings = origPriceTotal - price;
                    String saveStr = String.format("SAVING $%.2f!", savings);
                    String line = String.format("  %-20s %9s\\n", saveStr, priceStr);
                    baos.write(line.getBytes());
                } else {
                    String line = String.format("  %-20s %9s\\n", "", priceStr);
                    baos.write(line.getBytes());
                }
                
                subtotal += price;
            }
            baos.write("\\n".getBytes());

            baos.write(RIGHT);
            double tax = subtotal * 0.055;
            double total = subtotal + tax;
            baos.write(String.format("Tax:  $%.2f @ 5.5%%   $%.2f\\n", subtotal, tax).getBytes());
            baos.write(BOLD_ON);
            baos.write(String.format("Balance to pay       $%.2f\\n", total).getBytes());
            baos.write(BOLD_OFF);
            baos.write(String.format("%-20s $%.2f\\n\\n", tenderType, total).getBytes());

            baos.write(LEFT);
            String dateStr = new SimpleDateFormat("MM-dd-yy hh:mm a").format(new Date());
            baos.write(("STORE 14302   TILL 1   TRANS. " + barcode.substring(barcode.length() - 6) + "\\n").getBytes());
            baos.write(("DATE " + dateStr + "\\n\\n").getBytes());
            baos.write(("Your cashier was: " + DatabaseManager.getUserName(eid).toUpperCase() + "\\n\\n").getBytes());

            // Barcode
            baos.write(CENTER);
            baos.write(new byte[]{0x1d, 0x68, 0x50}); // Height 80
            baos.write(new byte[]{0x1d, 0x77, 0x02}); // Width 2 (increased)
            baos.write(new byte[]{0x1d, 0x48, 0x00}); // HRI disabled
            baos.write(new byte[]{0x1d, 0x6b, 0x04}); // Code 39
            baos.write(barcode.getBytes());
            baos.write(0x00);
            baos.write(("\\n*" + barcode + "*\\n\\n").getBytes());

            baos.write(FEED_3);
            baos.write(CUT);

            sendToPrinter(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }`;
    
    let oldIdx = printer.indexOf(oldPrintReceipt);
    if(oldIdx === -1) {
        console.log("Could not find oldPrintReceipt signature!");
    } else {
        printer = printer.substring(0, oldIdx) + newPrintReceipt + printer.substring(oldIdx + oldPrintReceipt.length);
        fs.writeFileSync('src/main/java/com/dgpos/PrinterService.java', printer);
    }

    // 3. Patch MainTransactionScreen.java
    let mainScr = fs.readFileSync('src/main/java/com/dgpos/MainTransactionScreen.java', 'utf8');

    const oldScannedItem = `    // Item class matching the DB data
    public static class ScannedItem {
        private final String upc;
        private final String name;
        private final double price;
        private int quantity;

        public ScannedItem(String upc, String name, double price, int quantity) {
            this.upc = upc;
            this.name = name;
            this.price = price;
            this.quantity = quantity;
        }

        public String getUpc() { return upc; }
        public String getName() { return name; }
        public double getBasePrice() { return price; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public double getPriceValue() { return price * quantity; }
        public String getPriceStr() { return String.format("$%.2f", price * quantity); }
    }`;

    const newScannedItem = `    // Item class matching the DB data
    public static class ScannedItem {
        private final String upc;
        private final String name;
        private final double price;
        private final double originalPrice;
        private int quantity;

        public ScannedItem(String upc, String name, double price, double originalPrice, int quantity) {
            this.upc = upc;
            this.name = name;
            this.price = price;
            this.originalPrice = originalPrice;
            this.quantity = quantity;
        }

        public String getUpc() { return upc; }
        public String getName() { return name; }
        public double getBasePrice() { return price; }
        public double getOriginalPrice() { return originalPrice; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public double getPriceValue() { return price * quantity; }
        public String getPriceStr() { return String.format("$%.2f", price * quantity); }
    }`;
    mainScr = mainScr.replace(oldScannedItem, newScannedItem);

    const oldComplete = `    private static void completeTransaction(Stage owner, TableView<ScannedItem> table, VBox totalsBlock, String eid, String tenderType) {
        DatabaseManager.logAction(eid, "TENDER_" + tenderType, "ALL");
        
        // 1. Print Receipt
        PrinterService.printReceipt(new java.util.ArrayList<>(table.getItems()), eid, tenderType);
        
        // 2. Clear UI immediately (like a real POS)
        java.util.List<ScannedItem> lastItems = new java.util.ArrayList<>(table.getItems());
        table.getItems().clear();
        updateTotals(table, totalsBlock);

        // 3. Prompt for Coupon
        javafx.scene.control.ButtonType yes = new javafx.scene.control.ButtonType("Print Coupon", javafx.scene.control.ButtonBar.ButtonData.YES);
        javafx.scene.control.ButtonType no = new javafx.scene.control.ButtonType("No Coupon", javafx.scene.control.ButtonBar.ButtonData.NO);
        
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Tear off receipt, then select to print coupon.", yes, no);
        alert.initOwner(owner);
        alert.setTitle("Coupon Prompt");
        alert.setHeaderText("Transaction Complete - Print Saturday Coupon?");
        
        // Run prompt on UI thread after a tiny delay to ensure receipt starts printing
        Platform.runLater(() -> {
            alert.showAndWait().ifPresent(type -> {
                if (type == yes) {
                    PrinterService.printCoupon();
                }
            });
        });
    }`;

    const newComplete = `    private static void completeTransaction(Stage owner, TableView<ScannedItem> table, VBox totalsBlock, String eid, String tenderType) {
        DatabaseManager.logAction(eid, "TENDER_" + tenderType, "ALL");
        
        double subtotal = 0;
        for (ScannedItem item : table.getItems()) subtotal += item.getPriceValue();
        double tax = subtotal * 0.055;
        double total = subtotal + tax;

        long receiptId = DatabaseManager.createReceipt(eid, tenderType, total);
        String barcode = String.format("%020d", receiptId);

        // 1. Print Receipt
        PrinterService.printReceipt(new java.util.ArrayList<>(table.getItems()), eid, tenderType, barcode);
        
        // 2. Clear UI immediately (like a real POS)
        java.util.List<ScannedItem> lastItems = new java.util.ArrayList<>(table.getItems());
        table.getItems().clear();
        updateTotals(table, totalsBlock);

        // 3. Prompt for Coupon
        javafx.scene.control.ButtonType yes = new javafx.scene.control.ButtonType("Print Coupon", javafx.scene.control.ButtonBar.ButtonData.YES);
        javafx.scene.control.ButtonType no = new javafx.scene.control.ButtonType("No Coupon", javafx.scene.control.ButtonBar.ButtonData.NO);
        
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Tear off receipt, then select to print coupon.", yes, no);
        alert.initOwner(owner);
        alert.setTitle("Coupon Prompt");
        alert.setHeaderText("Transaction Complete - Print Saturday Coupon?");
        
        // Run prompt on UI thread after a tiny delay to ensure receipt starts printing
        Platform.runLater(() -> {
            boolean couponPrinted = false;
            java.util.Optional<javafx.scene.control.ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == yes) {
                PrinterService.printCoupon();
                couponPrinted = true;
            }
            DatabaseManager.finalizeReceipt(receiptId, lastItems, couponPrinted);
        });
    }`;
    mainScr = mainScr.replace(oldComplete, newComplete);

    const oldProcess = `        double finalPrice = isReturnMode ? -data.price : data.price;
        String finalName = isReturnMode ? "RTN: " + data.name : data.name;
        
        table.getItems().add(new ScannedItem(data.upc, finalName, finalPrice, 1));`;
        
    const newProcess = `        double finalPrice = isReturnMode ? -data.price : data.price;
        String finalName = isReturnMode ? "RTN: " + data.name : data.name;
        
        table.getItems().add(new ScannedItem(data.upc, finalName, finalPrice, data.price, 1));`;
    mainScr = mainScr.replace(oldProcess, newProcess);

    const oldDiscount = `                    double newPrice = selected.getBasePrice() * 0.90;
                    int qty = selected.getQuantity();
                    int index = table.getItems().indexOf(selected);
                    table.getItems().set(index, new ScannedItem(selected.getUpc(), selected.getName() + " (-10%)", newPrice, qty));`;

    const newDiscount = `                    double newPrice = selected.getBasePrice() * 0.90;
                    double origPrice = selected.getOriginalPrice();
                    int qty = selected.getQuantity();
                    int index = table.getItems().indexOf(selected);
                    table.getItems().set(index, new ScannedItem(selected.getUpc(), selected.getName() + " (-10%)", newPrice, origPrice, qty));`;
    mainScr = mainScr.replace(oldDiscount, newDiscount);

    fs.writeFileSync('src/main/java/com/dgpos/MainTransactionScreen.java', mainScr);

    console.log("Patch completed successfully!");
} catch (e) {
    console.error("Error:", e);
}
