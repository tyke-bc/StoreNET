const fs = require('fs');

let code = fs.readFileSync('src/main/java/com/dgpos/MainTransactionScreen.java', 'utf8');

// Update ScannedItem Class
code = code.replace(/public static class ScannedItem \{[\s\S]*?\}/, `public static class ScannedItem {
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
    }`);

// Update completeTransaction Method
code = code.replace(/private static void completeTransaction.*?\{[\s\S]*?Platform\.runLater\(\(\) -> \{[\s\S]*?\}\);\s*\}/, `private static void completeTransaction(Stage owner, TableView<ScannedItem> table, VBox totalsBlock, String eid, String tenderType) {
        DatabaseManager.logAction(eid, "TENDER_" + tenderType, "ALL");
        
        double subtotal = 0;
        for (ScannedItem item : table.getItems()) subtotal += item.getPriceValue();
        double tax = subtotal * 0.055;
        double total = subtotal + tax;

        long receiptId = DatabaseManager.createReceipt(eid, tenderType, total);
        String barcode = String.format("%020d", receiptId);

        // 1. Print Receipt
        PrinterService.printReceipt(new java.util.ArrayList<>(table.getItems()), eid, tenderType, barcode);
        
        // 2. Clear UI immediately
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
        
        Platform.runLater(() -> {
            boolean couponPrinted = false;
            java.util.Optional<javafx.scene.control.ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == yes) {
                PrinterService.printCoupon();
                couponPrinted = true;
            }
            DatabaseManager.finalizeReceipt(receiptId, lastItems, couponPrinted);
        });
    }`);

// Update Add Item Calls
code = code.replace(/table\.getItems\(\)\.add\(new ScannedItem\(data\.upc, finalName, finalPrice, 1\)\);/g, 'table.getItems().add(new ScannedItem(data.upc, finalName, finalPrice, data.price, 1));');

// Update Discount Call
code = code.replace(/table\.getItems\(\)\.set\(index, new ScannedItem\(selected\.getUpc\(\), selected\.getName\(\) \+ " \(-10%\)", newPrice, qty\)\);/g, 'table.getItems().set(index, new ScannedItem(selected.getUpc(), selected.getName() + " (-10%)", newPrice, selected.getOriginalPrice(), qty));');

fs.writeFileSync('src/main/java/com/dgpos/MainTransactionScreen.java', code);
console.log('MainTransactionScreen.java patched');
