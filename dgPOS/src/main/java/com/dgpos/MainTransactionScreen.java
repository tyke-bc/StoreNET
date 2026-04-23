package com.dgpos;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import java.util.function.Consumer;

public class MainTransactionScreen {

    private static boolean isReturnMode = false;

    public static void show(Stage primaryStage, String eid) {
        isReturnMode = false; // Always reset on new session — prevents bleed-across after logout
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // Log the initial login action
        DatabaseManager.logAction(eid, "LOGIN", "");

        // --- Bottom Status Bar ---
        HBox bottomBar = new HBox(20);
        bottomBar.getStyleClass().add("bottom-bar");
        bottomBar.setAlignment(Pos.CENTER_LEFT);

        Label statusLabel = new Label("🟢 Online (DB Linked)");
        statusLabel.getStyleClass().add("status-text");

        Label tillLabel = new Label("Till: 01");
        tillLabel.getStyleClass().add("status-text");
        
        Label storeLabel = new Label("🏪 Store #" + StoreConfig.storeId()
                + " | " + StoreConfig.street() + " " + StoreConfig.city() + " " + StoreConfig.state() + " " + StoreConfig.zip());
        storeLabel.getStyleClass().add("status-text");

        Label cashierLabel = new Label("👤 Cashier EID: " + eid);
        cashierLabel.getStyleClass().add("status-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bottomBar.getChildren().addAll(statusLabel, tillLabel, storeLabel, spacer, cashierLabel);
        root.setBottom(bottomBar);

        // --- Left Section: Receipt Tape ---
        VBox receiptTape = new VBox();
        receiptTape.setPrefWidth(420); // Width increased back to fit names and prices
        receiptTape.getStyleClass().add("receipt-tape");
        
        TableView<ScannedItem> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getStyleClass().add("receipt-table");
        
        TableColumn<ScannedItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getName()));
        nameCol.setCellFactory(col -> new javafx.scene.control.TableCell<ScannedItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    ScannedItem scannedItem = getTableView().getItems().get(getIndex());
                    if (scannedItem.getSaleId() != null && !scannedItem.getSaleId().isEmpty()) {
                        VBox box = new VBox(2);
                        Label nameLbl = new Label(item);
                        nameLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #1e293b;");
                        Label promoLbl = new Label("DG PROMOTION: " + scannedItem.getSaleId());
                        promoLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #a855f7; -fx-font-weight: bold;");
                        box.getChildren().addAll(nameLbl, promoLbl);
                        setGraphic(box);
                    } else {
                        Label nameLbl = new Label(item);
                        nameLbl.setStyle("-fx-font-size: 16px; -fx-text-fill: #1e293b;");
                        setGraphic(nameLbl);
                    }
                    setText(null);
                }
            }
        });
        
        TableColumn<ScannedItem, String> qtyCol = new TableColumn<>("Qty");
        qtyCol.setCellValueFactory(cellData -> new SimpleStringProperty(String.valueOf(cellData.getValue().getQuantity())));
        qtyCol.setPrefWidth(50);
        qtyCol.setMaxWidth(50);
        qtyCol.setMinWidth(50);
        qtyCol.getStyleClass().add("col-qty");

        TableColumn<ScannedItem, String> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getPriceStr()));
        priceCol.setPrefWidth(90);
        priceCol.setMaxWidth(90);
        priceCol.setMinWidth(90);
        priceCol.getStyleClass().add("col-price");

        table.getColumns().addAll(nameCol, qtyCol, priceCol);
        
        VBox.setVgrow(table, Priority.ALWAYS);

        // Totals Block
        VBox totalsBlock = new VBox(5);
        totalsBlock.getStyleClass().add("totals-block");
        totalsBlock.setPadding(new Insets(15));
        
        HBox subtotalRow = createTotalRow("SUBTOTAL", "$0.00", false);
        HBox taxRow = createTotalRow("TAX", "$0.00", false);
        HBox totalRow = createTotalRow("TOTAL", "$0.00", true);
        
        totalsBlock.getChildren().addAll(subtotalRow, taxRow, totalRow);
        
        receiptTape.getChildren().addAll(table, totalsBlock);
        root.setLeft(receiptTape);

        // --- Right Section: Action Grid & Numpad ---
        VBox rightSection = new VBox(15);
        rightSection.setAlignment(Pos.CENTER);
        rightSection.setPadding(new Insets(20));
        
        // Scanner / Manual Entry Input Field
        TextField scannerInput = new TextField();
        scannerInput.setPromptText("Scan or Enter UPC...");
        scannerInput.getStyleClass().add("employee-input");
        scannerInput.setMaxWidth(400);

        scannerInput.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                String upc = scannerInput.getText();
                if (upc != null && !upc.trim().isEmpty()) {
                    processUpc(primaryStage, upc.trim(), table, totalsBlock, eid);
                    scannerInput.clear();
                }
            }
        });

        GridPane actionGrid = new GridPane();
        actionGrid.setAlignment(Pos.CENTER);
        actionGrid.setHgap(10);
        actionGrid.setVgap(10);

        // Numpad in the center (columns 1, 2, 3)
        String[][] keys = {
            {"7", "8", "9"},
            {"4", "5", "6"},
            {"1", "2", "3"},
            {"0", "00", "."}
        };

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 3; col++) {
                String keyText = keys[row][col];
                Button btn = createButton(keyText, "number-btn");
                btn.setOnAction(e -> {
                    scannerInput.appendText(keyText);
                    scannerInput.requestFocus();
                });
                actionGrid.add(btn, col + 1, row + 1);
            }
        }

        // Action Buttons (Column 0)
        Button itemVoidBtn = createButton("Item\nVoid", "void-btn");
        itemVoidBtn.setOnAction(e -> {
            requireKeyHolder(primaryStage, eid, () -> {
                ScannedItem selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    table.getItems().remove(selected);
                    DatabaseManager.logAction(eid, "ITEM_VOID", selected.getUpc());
                    BackofficeBridge.logEvent("VOID", Math.abs(selected.getPriceValue()), "Item void: " + selected.getName(), null, null, eid);
                    updateTotals(table, totalsBlock);
                    table.getSelectionModel().clearSelection();
                }
            });
            scannerInput.requestFocus();
        });

        Button transVoidBtn = createButton("Trans\nVoid", "danger-btn");
        transVoidBtn.setOnAction(e -> {
            requireKeyHolder(primaryStage, eid, () -> {
                if (!table.getItems().isEmpty()) {
                    double voidedTotal = 0;
                    for (ScannedItem it : table.getItems()) voidedTotal += Math.abs(it.getPriceValue());
                    table.getItems().clear();
                    DatabaseManager.logAction(eid, "TRANS_VOID", "ALL");
                    BackofficeBridge.logEvent("VOID", voidedTotal, "Transaction voided", null, null, eid);
                    updateTotals(table, totalsBlock);
                }
            });
            scannerInput.requestFocus();
        });

        Button priceInqBtn = createButton("Price\nInquiry", "info-btn");
        priceInqBtn.setOnAction(e -> {
            String upc = scannerInput.getText().trim();
            if (!upc.isEmpty()) {
                if (upc.startsWith("A")) upc = upc.substring(1);
                else if (upc.startsWith("QR")) upc = upc.substring(2);
                
                DatabaseManager.ScannedItemData data = DatabaseManager.lookupItem(upc);
                showPriceInquiryResult(primaryStage, data);
                scannerInput.clear();
            }
            scannerInput.requestFocus();
        });

        Button qtyBtn = createButton("Quantity", "action-btn");
        qtyBtn.setOnAction(e -> {
            requireKeyHolder(primaryStage, eid, () -> {
                ScannedItem selected = table.getSelectionModel().getSelectedItem();
                if (selected == null && !table.getItems().isEmpty()) {
                    selected = table.getItems().get(table.getItems().size() - 1);
                }
                
                if (selected != null) {
                    showQuantityModal(primaryStage, selected, table, totalsBlock, eid);
                }
                scannerInput.clear();
            });
            scannerInput.requestFocus();
        });

        Button managerMenuBtn = createButton("Manager\nMenu", "void-btn");
        managerMenuBtn.setOnAction(e -> {
            requireKeyHolder(primaryStage, eid, () -> {
                showManagerMenu(primaryStage, eid, scannerInput);
            });
            scannerInput.requestFocus();
        });
        
        actionGrid.add(itemVoidBtn, 0, 0);
        actionGrid.add(transVoidBtn, 0, 1);
        actionGrid.add(priceInqBtn, 0, 2);
        actionGrid.add(qtyBtn, 0, 3);
        actionGrid.add(managerMenuBtn, 0, 4);

        // Scanner state — declared early so secure overlay lambda can capture them
        StringBuilder scannerBuffer = new StringBuilder();
        boolean[] scannerBlocked = {false}; // true while secure overlay is active

        // Top Row: Secure button (spans 2 cols where Return + Discount used to be)
        Button secureBtn = createButton("Secure", "void-btn");
        secureBtn.setOnAction(e -> {
            showSecureOverlay(primaryStage, eid, DatabaseManager.getUserName(eid), scannerBlocked, scannerBuffer,
                    root, receiptTape, rightSection, scannerInput);
        });
        actionGrid.add(secureBtn, 1, 0, 2, 1);

        // Action Buttons (Column 4 - right of numpad)
        Button clearBtn = createButton("Clear", "danger-btn");
        clearBtn.setOnAction(e -> {
            scannerInput.clear();
            scannerInput.requestFocus();
        });

        Button enterBtn = createButton("Enter", "enter-button");
        enterBtn.setPrefHeight(150); // Spans 2 rows
        enterBtn.setOnAction(e -> {
            String upc = scannerInput.getText();
            if (upc != null && !upc.trim().isEmpty()) {
                processUpc(primaryStage, upc.trim(), table, totalsBlock, eid);
                scannerInput.clear();
            }
            scannerInput.requestFocus();
        });
        
        actionGrid.add(clearBtn, 4, 1);
        actionGrid.add(enterBtn, 4, 2, 1, 2); 

        // Tender Buttons (Far right - Column 5)
        Button exactCashBtn = createButton("Exact\nCash", "tender-cash-btn");
        exactCashBtn.setPrefHeight(150);
        exactCashBtn.setOnAction(e -> {
             if (!table.getItems().isEmpty()) {
                 completeTransaction(primaryStage, table, totalsBlock, eid, "CASH");
             }
             scannerInput.requestFocus();
        });
        
        Button cardBtn = createButton("Card", "tender-card-btn");
        cardBtn.setPrefHeight(150);
        cardBtn.setOnAction(e -> {
             if (!table.getItems().isEmpty()) {
                 completeTransaction(primaryStage, table, totalsBlock, eid, "CARD");
             }
             scannerInput.requestFocus();
        });
        
        actionGrid.add(exactCashBtn, 5, 1, 1, 2);
        actionGrid.add(cardBtn, 5, 3, 1, 2);
        
        // Log out button at the top right
        Button logoutBtn = createButton("Log Out", "danger-btn");
        logoutBtn.setOnAction(e -> showLogoutDialog(primaryStage, eid));
        actionGrid.add(logoutBtn, 5, 0);

        rightSection.getChildren().addAll(scannerInput, actionGrid);
        root.setCenter(rightSection);

        Scene scene = new Scene(root, 1024, 768);
        try {
            String cssPath = MainTransactionScreen.class.getResource("/style.css").toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (NullPointerException e) {
            System.err.println("Warning: style.css not found in resources. Running without styling.");
        }
        
        // --- Bulletproof Scanner Listener (Scene Level) ---
        scene.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (scannerBlocked[0]) return; // never process keystrokes during secure screen
            if (!scannerInput.isFocused()) {
                String charTyped = event.getCharacter();
                if (charTyped.equals("\r") || charTyped.equals("\n")) {
                    String upc = scannerBuffer.toString().trim();
                    if (!upc.isEmpty()) {
                        processUpc(primaryStage, upc, table, totalsBlock, eid);
                        scannerBuffer.setLength(0);
                    }
                } else {
                    scannerBuffer.append(charTyped);
                }
            }
        });

        primaryStage.setScene(scene);
        primaryStage.setFullScreen(true); 
        primaryStage.setFullScreenExitHint(""); 

        // Initial focus
        Platform.runLater(scannerInput::requestFocus);
        
        // Focus the input field any time the background is clicked
        scene.setOnMouseClicked(e -> scannerInput.requestFocus());
    }

    private static void completeTransaction(Stage owner, TableView<ScannedItem> table, VBox totalsBlock, String eid, String tenderType) {
        DatabaseManager.logAction(eid, "TENDER_" + tenderType, "ALL");

        double subtotal = 0;
        double taxableSubtotal = 0;
        for (ScannedItem item : table.getItems()) {
            double val = item.getPriceValue();
            subtotal += val;
            if (item.isTaxable()) taxableSubtotal += val;
        }
        double tax = taxableSubtotal * 0.055;
        double total = subtotal + tax;

        long receiptId = DatabaseManager.createReceipt(eid, tenderType, total);
        String barcode = String.format("%020d", receiptId);

        // Cash sales add to the drawer — the reconcile view uses this to build expected_cash.
        if ("CASH".equals(tenderType)) {
            BackofficeBridge.logEvent("SALE", total, null, String.valueOf(receiptId), null, eid);
        }

        // 1. Print Receipt
        PrinterService.printReceipt(new java.util.ArrayList<>(table.getItems()), eid, tenderType, barcode);
        
        // 2. Clear UI immediately (like a real POS)
        java.util.List<ScannedItem> lastItems = new java.util.ArrayList<>(table.getItems());
        table.getItems().clear();
        updateTotals(table, totalsBlock);

        // 3. Prompt for Coupon
        Platform.runLater(() -> {
            showConfirmation(owner, "Coupon Prompt", "Transaction Complete", 
                "Tear off receipt. Print Saturday Coupon?", "Print Coupon", "No Coupon",
                () -> {
                    PrinterService.printCoupon();
                    DatabaseManager.finalizeReceipt(receiptId, lastItems, true);
                },
                () -> {
                    DatabaseManager.finalizeReceipt(receiptId, lastItems, false);
                }
            );
        });
    }

    private static void processUpc(Stage stage, String input, TableView<ScannedItem> table, VBox totalsBlock, String eid) {
        String upc = input.trim();
        // Strip scanner-specific prefixes (DataLogic '§', 'A', or 'QR')
        if (upc.startsWith("§")) upc = upc.substring(1);
        if (upc.startsWith("A")) upc = upc.substring(1);
        else if (upc.startsWith("QR")) upc = upc.substring(2);

        // --- RECEIPT SCAN DETECTOR ---
        // Receipts use a 20-digit barcode string
        if (upc.length() == 20 && upc.matches("\\d+")) {
            System.out.println("Receipt barcode detected: " + upc);
            DatabaseManager.ReceiptData receipt = DatabaseManager.lookupReceipt(upc);
            if (receipt != null) {
                if (receipt.total <= 0) {
                    showNotification(stage, "Refund Blocked", "Transaction Already Refunded", 
                        "This receipt appears to be a refund or a zero-total transaction and cannot be returned again.");
                    return;
                }
                showRefundSelectionModal(stage, receipt, table, totalsBlock, eid);
                return;
            } else {
                System.out.println("No receipt record found in database for: " + upc);
            }
        }
        
        DatabaseManager.ScannedItemData data = DatabaseManager.lookupItem(upc);
        double finalPrice = isReturnMode ? -data.price : data.price;
        String finalName = isReturnMode ? "RTN: " + data.name : data.name;

        table.getItems().add(new ScannedItem(data.upc, data.sku, finalName, finalPrice, data.regPrice, data.saleId, data.taxable, 1));
        DatabaseManager.logAction(eid, isReturnMode ? "RETURN_MANUAL" : "SCAN", upc);
        isReturnMode = false;
        updateTotals(table, totalsBlock);
        table.scrollTo(table.getItems().size() - 1);
    }

    private static void showRefundSelectionModal(Stage owner, DatabaseManager.ReceiptData receipt, TableView<ScannedItem> mainTable, VBox totalsBlock, String eid) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #3b82f6; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("REFUND SELECTION (TRANS #" + receipt.barcode.substring(14) + ")");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");

        Label info = new Label("ORIGINAL TENDER: " + receipt.tenderType + " | TOTAL: $" + String.format("%.2f", receipt.total));
        info.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");

        TableView<DatabaseManager.ScannedItemData> itemTable = new TableView<>();
        itemTable.setPrefHeight(300);
        itemTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        
        TableColumn<DatabaseManager.ScannedItemData, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name));
        TableColumn<DatabaseManager.ScannedItemData, String> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(d -> new SimpleStringProperty(String.format("$%.2f", d.getValue().price)));
        
        itemTable.getColumns().addAll(nameCol, priceCol);
        itemTable.getItems().addAll(receipt.items);

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER);

        Button selectBtn = new Button("Select");
        selectBtn.setPrefSize(120, 50);
        selectBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold;");
        selectBtn.setOnAction(e -> {
            var selected = itemTable.getSelectionModel().getSelectedItems();
            processRefunds(owner, selected, receipt.tenderType, mainTable, totalsBlock, eid);
            stage.close();
        });

        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setPrefSize(120, 50);
        selectAllBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold;");
        selectAllBtn.setOnAction(e -> itemTable.getSelectionModel().selectAll());

        Button deselectBtn = new Button("Deselect");
        deselectBtn.setPrefSize(120, 50);
        deselectBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold;");
        deselectBtn.setOnAction(e -> itemTable.getSelectionModel().clearSelection());

        Button closeBtn = new Button("Close");
        closeBtn.setPrefSize(120, 50);
        closeBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold;");
        closeBtn.setOnAction(e -> stage.close());

        btnRow.getChildren().addAll(selectBtn, selectAllBtn, deselectBtn, closeBtn);
        root.getChildren().addAll(title, info, itemTable, btnRow);

        Scene scene = new Scene(root, 600, 550);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void processRefunds(Stage owner, java.util.List<DatabaseManager.ScannedItemData> items, String originalTender, TableView<ScannedItem> mainTable, VBox totalsBlock, String eid) {
        if (items.isEmpty()) return;

        double refundTotal = 0;
        for (DatabaseManager.ScannedItemData item : items) {
            mainTable.getItems().add(new ScannedItem(item.upc, item.sku, "REFUND: " + item.name, -item.price, item.price, item.saleId, item.taxable, 1));
            DatabaseManager.logAction(eid, "REFUND_ITEM", item.upc);
            refundTotal += Math.abs(item.price);
        }
        updateTotals(mainTable, totalsBlock);

        // Refund Type Logic
        if (originalTender.equals("CARD")) {
            showNotification(owner, "Card Refund Required", "Insert Customer Card",
                "Original purchase was CARD. Refund must go back to the original card. Please have the customer insert or swipe their card now.");
        } else {
            // CASH — drawer decreases, so tell the backoffice reconcile view.
            BackofficeBridge.logEvent("REFUND", refundTotal, "Cash refund", null, null, eid);
            System.out.println("CASH DRAWER OPENED FOR REFUND");
            showNotification(owner, "Refund Cash from Drawer", "Open Cash Drawer",
                "Original purchase was CASH. Please provide the indicated total back to the customer.");
        }
    }
    
    private static void requireKeyHolder(Stage owner, String currentEid, Runnable onSuccess) {
        String currentRole = DatabaseManager.getUserRole(currentEid);
        if (currentRole.equals("LSA") || currentRole.equals("ASM") || currentRole.equals("SM")) {
            onSuccess.run();
            return;
        }

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #ef4444; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("KEY CARRIER REQUIRED");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        TextField eidField = new TextField();
        eidField.setPromptText("Manager EID");
        eidField.setStyle("-fx-font-size: 20px; -fx-alignment: center;");
        eidField.setMaxWidth(200);

        javafx.scene.control.PasswordField pinField = new javafx.scene.control.PasswordField();
        pinField.setPromptText("PIN");
        pinField.setStyle("-fx-font-size: 20px; -fx-alignment: center;");
        pinField.setMaxWidth(200);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 16px;");

        Button submitBtn = new Button("Authorize");
        submitBtn.setPrefSize(200, 50);
        submitBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setPrefSize(200, 50);
        cancelBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");

        Runnable attemptAuth = () -> {
            String eid = eidField.getText();
            String pin = pinField.getText();
            DatabaseManager.UserData user = DatabaseManager.authenticateUser(eid, pin);
            if (user != null && (user.role.equals("LSA") || user.role.equals("ASM") || user.role.equals("SM"))) {
                stage.close();
                onSuccess.run();
            } else {
                errorLabel.setText("Invalid Manager Credentials");
                pinField.clear();
            }
        };

        submitBtn.setOnAction(e -> attemptAuth.run());
        pinField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) attemptAuth.run();
        });
        eidField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) pinField.requestFocus();
        });
        cancelBtn.setOnAction(e -> stage.close());

        root.getChildren().addAll(title, eidField, pinField, submitBtn, cancelBtn, errorLabel);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void showPriceInquiryResult(Stage owner, DatabaseManager.ScannedItemData data) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #3b82f6; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("PRICE INQUIRY");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        VBox details = new VBox(10);
        details.setAlignment(Pos.CENTER);
        Label nameLabel = new Label(data.name);
        nameLabel.setStyle("-fx-text-fill: #eab308; -fx-font-size: 22px; -fx-font-weight: bold;");
        Label upcLabel = new Label("UPC: " + data.upc);
        upcLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 18px;");
        Label priceLabel = new Label("Price: $" + String.format("%.2f", data.price));
        priceLabel.setStyle("-fx-text-fill: white; -fx-font-size: 32px; -fx-font-weight: bold;");

        details.getChildren().addAll(nameLabel, upcLabel, priceLabel);

        Button closeBtn = new Button("Close");
        closeBtn.setPrefSize(300, 50);
        closeBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> stage.close());

        root.getChildren().addAll(title, details, closeBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void showSecureOverlay(Stage owner, String eid, String name,
            boolean[] scannerBlocked, StringBuilder scannerBuffer,
            BorderPane root, VBox receiptTape, VBox rightSection, TextField scannerInput) {

        // Block scanner event filter and flush any buffered junk
        scannerBlocked[0] = true;
        scannerBuffer.setLength(0);

        VBox securePane = new VBox(15);
        securePane.setAlignment(Pos.CENTER);
        securePane.setMaxWidth(Double.MAX_VALUE);
        securePane.setStyle("-fx-background-color: #0f172a;");

        Label lockLabel = new Label("TILL SECURED");
        lockLabel.setStyle("-fx-text-fill: white; -fx-font-size: 36px; -fx-font-weight: bold;");

        Label cashierLabel = new Label(name.toUpperCase());
        cashierLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 20px;");

        // Fields
        javafx.scene.control.TextField overrideEidField = new javafx.scene.control.TextField();
        overrideEidField.setPromptText("Key-Holder EID");
        overrideEidField.getStyleClass().add("employee-input");
        overrideEidField.setMaxWidth(250);
        overrideEidField.setVisible(false);
        overrideEidField.setManaged(false);

        javafx.scene.control.PasswordField pinField = new javafx.scene.control.PasswordField();
        pinField.setPromptText("Enter PIN to unlock");
        pinField.getStyleClass().add("employee-input");
        pinField.setMaxWidth(250);

        Label statusLabel = new Label("Enter your PIN or use Key-Holder Override");
        statusLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");

        boolean[] overrideMode = {false};

        Runnable doUnlock = () -> {
            scannerBlocked[0] = false;
            scannerBuffer.setLength(0);
            root.setLeft(receiptTape);
            root.setCenter(rightSection);
            Platform.runLater(() -> scannerInput.requestFocus());
        };

        Button unlockBtn = new Button("Unlock");
        unlockBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-cursor: hand;");
        unlockBtn.setPrefSize(200, 50);
        unlockBtn.setOnAction(e -> {
            if (!overrideMode[0]) {
                DatabaseManager.UserData user = DatabaseManager.authenticateUser(eid, pinField.getText().trim());
                if (user != null) {
                    doUnlock.run();
                } else {
                    statusLabel.setText("Incorrect PIN.");
                    statusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 14px;");
                    pinField.clear();
                }
            } else {
                String overrideEid = overrideEidField.getText().trim();
                DatabaseManager.UserData kh = DatabaseManager.authenticateUser(overrideEid, pinField.getText().trim());
                if (kh != null && !kh.role.equals("SA")) {
                    DatabaseManager.logAction(eid, "SECURE_OVERRIDE", overrideEid);
                    doUnlock.run();
                } else if (kh != null) {
                    statusLabel.setText("Not a key carrier.");
                    statusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 14px;");
                    pinField.clear();
                } else {
                    statusLabel.setText("Invalid credentials.");
                    statusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 14px;");
                    pinField.clear();
                }
            }
        });

        Button overrideBtn = new Button("Key-Holder Override");
        overrideBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: 12px; -fx-cursor: hand; -fx-border-color: #334155; -fx-border-width: 1;");
        overrideBtn.setPrefSize(200, 35);
        overrideBtn.setOnAction(e -> {
            overrideMode[0] = !overrideMode[0];
            overrideEidField.setVisible(overrideMode[0]);
            overrideEidField.setManaged(overrideMode[0]);
            pinField.clear();
            overrideEidField.clear();
            if (overrideMode[0]) {
                pinField.setPromptText("Key-Holder PIN");
                statusLabel.setText("Enter Key-Holder EID, then PIN");
                statusLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");
                overrideBtn.setText("Cancel Override");
                Platform.runLater(() -> overrideEidField.requestFocus());
            } else {
                pinField.setPromptText("Enter PIN to unlock");
                statusLabel.setText("Enter your PIN or use Key-Holder Override");
                statusLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");
                overrideBtn.setText("Key-Holder Override");
                Platform.runLater(() -> pinField.requestFocus());
            }
        });

        GridPane pinNumpad = buildNumpad(pinField);

        pinField.setOnKeyPressed(ev -> { if (ev.getCode() == javafx.scene.input.KeyCode.ENTER) unlockBtn.fire(); });

        securePane.getChildren().addAll(lockLabel, cashierLabel, overrideEidField, pinField, pinNumpad, statusLabel, unlockBtn, overrideBtn);
        root.setLeft(null);
        root.setCenter(securePane);
        Platform.runLater(() -> pinField.requestFocus());
    }

    private static void showLogoutDialog(Stage owner, String eid) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #ef4444; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("Remove Cash Drawer?");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");

        Label subtitle = new Label("Selecting 'Yes' will Z-out your register and print an EOD report.");
        subtitle.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(320);
        subtitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        HBox buttons = new HBox(15);
        buttons.setAlignment(Pos.CENTER);

        Button yesBtn = new Button("Yes");
        yesBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-cursor: hand;");
        yesBtn.setPrefSize(100, 50);
        yesBtn.setOnAction(e -> {
            stage.close();
            // Prompt the cashier to count the drawer — this is the "actual cash" the
            // backoffice reconcile view compares against expected_cash.
            showAmountDialog(owner, eid, "COUNT DRAWER",
                "Enter the total cash in the drawer (including the starting bank).",
                "#3b82f6", actualCash -> {
                    DatabaseManager.ZOutData data = DatabaseManager.getZOutData(eid);
                    PrinterService.printZOutReport(data);
                    DatabaseManager.logAction(eid, "ZOUT", String.format("$%.2f", actualCash));
                    BackofficeBridge.closeSession(actualCash, null);
                    DatabaseManager.clearHeldDrawer();
                    DatabaseManager.logAction(eid, "LOGOUT", "");
                    PosLogin loginScreen = new PosLogin();
                    loginScreen.start(owner);
                });
        });

        Button noBtn = new Button("No");
        noBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-cursor: hand;");
        noBtn.setPrefSize(100, 50);
        noBtn.setOnAction(e -> {
            stage.close();
            DatabaseManager.setHeldDrawer(eid, DatabaseManager.getUserName(eid));
            DatabaseManager.logAction(eid, "LOGOUT", "");
            PosLogin loginScreen = new PosLogin();
            loginScreen.start(owner);
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-cursor: hand; -fx-border-color: #334155; -fx-border-width: 1;");
        cancelBtn.setPrefSize(90, 40);
        cancelBtn.setOnAction(e -> stage.close());

        buttons.getChildren().addAll(yesBtn, noBtn, cancelBtn);
        root.getChildren().addAll(title, subtitle, buttons);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    // Generic touchscreen-friendly amount entry dialog.
    // borderColor sets the accent color; onConfirm receives the validated amount.
    private static void showAmountDialog(Stage owner, String eid, String titleText,
                                         String subtitleText, String borderColor,
                                         java.util.function.Consumer<Double> onConfirm) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: " + borderColor +
                "; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label(titleText);
        title.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");

        Label subtitle = new Label(subtitleText);
        subtitle.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");

        TextField amountField = new TextField();
        amountField.setPromptText("0.00");
        amountField.setStyle("-fx-font-size: 32px; -fx-alignment: center; -fx-background-color: #0f172a; " +
                "-fx-text-fill: white; -fx-border-color: " + borderColor + "; -fx-border-width: 2; " +
                "-fx-background-radius: 6; -fx-border-radius: 6;");
        amountField.setMaxWidth(270);

        GridPane numpad = buildNumpad(amountField);

        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 13px;");

        Button confirmBtn = new Button("Confirm");
        confirmBtn.setStyle("-fx-background-color: " + borderColor +
                "; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6;");
        confirmBtn.setPrefSize(270, 55);
        confirmBtn.setOnAction(ev -> {
            try {
                double amount = Double.parseDouble(amountField.getText().trim());
                if (amount <= 0) { statusLabel.setText("Amount must be greater than zero."); return; }
                stage.close();
                onConfirm.accept(amount);
            } catch (NumberFormatException nfe) {
                statusLabel.setText("Invalid amount.");
            }
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-font-size: 14px; -fx-cursor: hand; -fx-background-radius: 6;");
        cancelBtn.setPrefSize(270, 45);
        cancelBtn.setOnAction(ev -> stage.close());

        amountField.setOnKeyPressed(ev -> { if (ev.getCode() == javafx.scene.input.KeyCode.ENTER) confirmBtn.fire(); });

        root.getChildren().addAll(title, subtitle, amountField, numpad, statusLabel, confirmBtn, cancelBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void showManagerMenu(Stage owner, String eid, TextField scannerInput) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #eab308; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("MANAGER MENU");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold;");

        HBox columns = new HBox(30);
        columns.setAlignment(Pos.CENTER);

        // Money Operations Column
        VBox moneyOps = new VBox(15);
        moneyOps.setAlignment(Pos.CENTER);
        Label moneyTitle = new Label("Money Operations");
        moneyTitle.setStyle("-fx-text-fill: #10b981; -fx-font-size: 20px; -fx-font-weight: bold;");
        
        Button startingBankBtn = createMenuButton("Starting Bank");
        startingBankBtn.setOnAction(e -> {
            stage.close();
            showAmountDialog(owner, eid, "SET STARTING BANK",
                "Enter the starting bank amount for this till.",
                "#eab308", amount -> {
                    DatabaseManager.setStartingBank(amount);
                    DatabaseManager.logAction(eid, "STARTING_BANK", String.format("$%.2f", amount));
                    showNotification(owner, "Starting Bank Set", "Success",
                        String.format("Starting bank set to $%.2f", amount));
                });
        });
        Button pickupBtn = createMenuButton("Pickup");
        pickupBtn.setOnAction(e -> {
            stage.close();
            showAmountDialog(owner, eid, "RECORD PICKUP",
                "Enter the cash amount being removed from the drawer.",
                "#10b981", amount -> {
                    DatabaseManager.recordPickup(eid, amount, eid);
                    DatabaseManager.logAction(eid, "PICKUP", String.format("$%.2f", amount));
                    BackofficeBridge.logEvent("PICKUP", amount, null, null, eid, eid);
                    showNotification(owner, "Pickup Recorded", "Success",
                        String.format("$%.2f removed from drawer.", amount));
                });
        });
        Button cashOutBtn = createMenuButton("Cash Out");
        cashOutBtn.setOnAction(e -> { logManagerAction(owner, eid, "CASH_OUT", stage); });
        Button cashInBtn = createMenuButton("Cash In");
        cashInBtn.setOnAction(e -> { logManagerAction(owner, eid, "CASH_IN", stage); });

        moneyOps.getChildren().addAll(moneyTitle, startingBankBtn, pickupBtn, cashOutBtn, cashInBtn);

        // Till Operations Column
        VBox tillOps = new VBox(15);
        tillOps.setAlignment(Pos.CENTER);
        Label tillTitle = new Label("Till & Store Operations");
        tillTitle.setStyle("-fx-text-fill: #3b82f6; -fx-font-size: 20px; -fx-font-weight: bold;");

        Button restartTillBtn = createMenuButton("Restart Till");
        restartTillBtn.setOnAction(e -> { logManagerAction(owner, eid, "RESTART_TILL", stage); });
        
        Button trainingModeBtn = createMenuButton("Training Mode");
        trainingModeBtn.setOnAction(e -> {
            logManagerAction(owner, eid, "TRAINING_MODE", stage);
            showNotification(owner, "Training Mode", "Mode Enabled",
                "Have fun breaking things! Everything is fake now.\n(Just kidding, logging is still real)");
        });

        Button printLabelBtn = createMenuButton("Print Shelf\nLabel");
        printLabelBtn.setOnAction(e -> {
            showUpcInputDialog(stage, upc -> {
                stage.close();
                DatabaseManager.LabelData labelData = DatabaseManager.lookupLabelData(upc);
                if (labelData == null) {
                    showNotification(owner, "Label Error", "Item Not Found", "No inventory record found for:\n" + upc);
                    return;
                }
                PrinterService.printLabel(labelData);
                showNotification(owner, "Label Printed", "Sending to Printer", "Shelf label queued for:\n" + labelData.name);
            });
        });

        tillOps.getChildren().addAll(tillTitle, restartTillBtn, trainingModeBtn, printLabelBtn);

        columns.getChildren().addAll(moneyOps, tillOps);

        Button closeBtn = new Button("Close Menu");
        closeBtn.setPrefSize(250, 50);
        closeBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> stage.close());

        root.getChildren().addAll(title, columns, closeBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    // Shared on-screen numpad for touchscreen use.
    // Appends digits/decimal to target field; backspace removes last char.
    private static GridPane buildNumpad(TextField target) {
        GridPane pad = new GridPane();
        pad.setHgap(8);
        pad.setVgap(8);
        pad.setAlignment(Pos.CENTER);

        String[] labels = { "7","8","9", "4","5","6", "1","2","3", ".","0","⌫" };
        for (int i = 0; i < labels.length; i++) {
            String lbl = labels[i];
            Button btn = new Button(lbl);
            btn.setPrefSize(75, 60);
            btn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
            btn.setOnAction(e -> {
                if (lbl.equals("⌫")) {
                    String t = target.getText();
                    if (!t.isEmpty()) target.setText(t.substring(0, t.length() - 1));
                } else if (lbl.equals(".")) {
                    if (!target.getText().contains(".")) target.appendText(".");
                } else {
                    target.appendText(lbl);
                }
            });
            pad.add(btn, i % 3, i / 3);
        }
        return pad;
    }

    private static Button createMenuButton(String text) {
        Button btn = new Button(text);
        btn.setPrefSize(250, 50);
        btn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-cursor: hand;");
        return btn;
    }

    private static void logManagerAction(Stage owner, String eid, String action, Stage stage) {
        DatabaseManager.logAction(eid, action, "MGR_MENU");
        stage.close();
        showNotification(owner, "Action Completed", "Success", "Successfully processed: " + action);
    }

    private static void showQuantityModal(Stage owner, ScannedItem item, TableView<ScannedItem> table, VBox totalsBlock, String eid) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #3b82f6; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("ENTER QUANTITY");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");
        
        Label itemName = new Label(item.getName());
        itemName.setStyle("-fx-text-fill: #eab308; -fx-font-size: 20px;");

        TextField inputField = new TextField();
        inputField.setPromptText("Qty");
        inputField.setStyle("-fx-font-size: 28px; -fx-alignment: center;");
        inputField.setMaxWidth(200);

        GridPane numpad = new GridPane();
        numpad.setAlignment(Pos.CENTER);
        numpad.setHgap(10);
        numpad.setVgap(10);

        String[][] keys = {
            {"7", "8", "9"},
            {"4", "5", "6"},
            {"1", "2", "3"},
            {"C", "0", "Enter"}
        };

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 3; col++) {
                String keyText = keys[row][col];
                Button btn = new Button(keyText);
                btn.setPrefSize(80, 70);
                btn.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-cursor: hand;");
                
                if (keyText.equals("C")) {
                    btn.setStyle(btn.getStyle() + "-fx-background-color: #ef4444; -fx-text-fill: white;");
                    btn.setOnAction(e -> inputField.clear());
                } else if (keyText.equals("Enter")) {
                    btn.setStyle(btn.getStyle() + "-fx-background-color: #10b981; -fx-text-fill: white;");
                    btn.setOnAction(e -> {
                        try {
                            int q = Integer.parseInt(inputField.getText());
                            if (q > 0) {
                                item.setQuantity(q);
                                table.refresh();
                                updateTotals(table, totalsBlock);
                                DatabaseManager.logAction(eid, "QUANTITY_CHANGE", item.getUpc() + " x" + q);
                                stage.close();
                            }
                        } catch (Exception ex) {}
                    });
                } else {
                    btn.setOnAction(e -> inputField.appendText(keyText));
                }
                numpad.add(btn, col, row);
            }
        }
        
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setPrefSize(260, 50);
        cancelBtn.setStyle("-fx-background-color: #64748b; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> stage.close());

        root.getChildren().addAll(title, itemName, inputField, numpad, cancelBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        
        // Support keyboard enter
        inputField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                try {
                    int q = Integer.parseInt(inputField.getText());
                    if (q > 0) {
                        item.setQuantity(q);
                        table.refresh();
                        updateTotals(table, totalsBlock);
                        DatabaseManager.logAction(eid, "QUANTITY_CHANGE", item.getUpc() + " x" + q);
                        stage.close();
                    }
                } catch (Exception ex) {}
            }
        });
        
        stage.showAndWait();
    }

    private static void showUpcInputDialog(Stage owner, Consumer<String> onSubmit) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #10b981; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label("PRINT SHELF LABEL");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label prompt = new Label("Scan or enter UPC / SKU:");
        prompt.setStyle("-fx-text-fill: #10b981; -fx-font-size: 18px;");

        TextField input = new TextField();
        input.setPrefWidth(300);
        input.setStyle("-fx-font-size: 18px; -fx-padding: 8;");

        HBox btnRow = new HBox(15);
        btnRow.setAlignment(Pos.CENTER);

        Button printBtn = new Button("Print Label");
        printBtn.setPrefSize(180, 50);
        printBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        printBtn.setOnAction(e -> {
            String value = input.getText().trim();
            if (!value.isEmpty()) {
                stage.close();
                onSubmit.accept(value);
            }
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setPrefSize(180, 50);
        cancelBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> stage.close());

        input.setOnAction(e -> printBtn.fire());

        btnRow.getChildren().addAll(printBtn, cancelBtn);
        root.getChildren().addAll(title, prompt, input, btnRow);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        Platform.runLater(input::requestFocus);
        stage.showAndWait();
    }

    private static void showNotification(Stage owner, String titleText, String headerText, String contentText) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #3b82f6; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label(titleText.toUpperCase());
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label header = new Label(headerText);
        header.setStyle("-fx-text-fill: #eab308; -fx-font-size: 20px; -fx-font-weight: bold;");

        Label content = new Label(contentText);
        content.setStyle("-fx-text-fill: white; -fx-font-size: 16px;");
        content.setWrapText(true);
        content.setTextAlignment(TextAlignment.CENTER);
        content.setMaxWidth(400);

        Button okBtn = new Button("OK");
        okBtn.setPrefSize(200, 50);
        okBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        okBtn.setOnAction(e -> stage.close());

        root.getChildren().addAll(title, header, content, okBtn);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void showConfirmation(Stage owner, String titleText, String headerText, String contentText, String yesLabel, String noLabel, Runnable onYes, Runnable onNo) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #1e293b; -fx-border-color: #eab308; -fx-border-width: 4; -fx-background-radius: 10; -fx-border-radius: 10;");

        Label title = new Label(titleText.toUpperCase());
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label header = new Label(headerText);
        header.setStyle("-fx-text-fill: #eab308; -fx-font-size: 20px; -fx-font-weight: bold;");

        Label content = new Label(contentText);
        content.setStyle("-fx-text-fill: white; -fx-font-size: 16px;");
        content.setWrapText(true);
        content.setTextAlignment(TextAlignment.CENTER);
        content.setMaxWidth(400);

        HBox btnRow = new HBox(15);
        btnRow.setAlignment(Pos.CENTER);

        Button yesBtn = new Button(yesLabel);
        yesBtn.setPrefSize(180, 50);
        yesBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        yesBtn.setOnAction(e -> {
            stage.close();
            if (onYes != null) onYes.run();
        });

        Button noBtn = new Button(noLabel);
        noBtn.setPrefSize(180, 50);
        noBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        noBtn.setOnAction(e -> {
            stage.close();
            if (onNo != null) onNo.run();
        });

        btnRow.getChildren().addAll(yesBtn, noBtn);
        root.getChildren().addAll(title, header, content, btnRow);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.showAndWait();
    }

    private static void updateTotals(TableView<ScannedItem> table, VBox totalsBlock) {
        double subtotal = 0;
        double taxableSubtotal = 0;
        for (ScannedItem item : table.getItems()) {
            double val = item.getPriceValue();
            subtotal += val;
            if (item.isTaxable()) taxableSubtotal += val;
        }
        double tax = taxableSubtotal * 0.055;
        double total = subtotal + tax;

        // update the labels inside totalsBlock
        ((Label)((HBox)totalsBlock.getChildren().get(0)).getChildren().get(2)).setText(String.format("$%.2f", subtotal));
        ((Label)((HBox)totalsBlock.getChildren().get(1)).getChildren().get(2)).setText(String.format("$%.2f", tax));
        ((Label)((HBox)totalsBlock.getChildren().get(2)).getChildren().get(2)).setText(String.format("$%.2f", total));
    }

    private static HBox createTotalRow(String labelText, String amountText, boolean isTotal) {
        HBox row = new HBox();
        Label label = new Label(labelText);
        Label amount = new Label(amountText);
        
        if (isTotal) {
            label.getStyleClass().add("total-label");
            amount.getStyleClass().add("total-amount");
        } else {
            label.getStyleClass().add("subtotal-label");
            amount.getStyleClass().add("subtotal-amount");
        }
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        row.getChildren().addAll(label, spacer, amount);
        return row;
    }

    private static Button createButton(String text, String styleClass) {
        Button btn = new Button(text);
        btn.getStyleClass().addAll("numpad-btn", styleClass);
        btn.setPrefSize(105, 70);
        btn.setTextAlignment(TextAlignment.CENTER);
        btn.setWrapText(true);
        return btn;
    }
    
    // Item class matching the DB data
    public static class ScannedItem {
        private final String upc;
        private final String sku;
        private final String name;
        private final double price;
        private final double originalPrice;
        private final String saleId;
        private final boolean taxable;
        private int quantity;

        public ScannedItem(String upc, String sku, String name, double price, double originalPrice, String saleId, boolean taxable, int quantity) {
            this.upc = upc;
            this.sku = sku != null ? sku : upc;
            this.name = name;
            this.price = price;
            this.originalPrice = originalPrice;
            this.saleId = saleId;
            this.taxable = taxable;
            this.quantity = quantity;
        }

        public String getUpc() { return upc; }
        public String getSku() { return sku; }
        public String getName() { return name; }
        public double getBasePrice() { return price; }
        public double getOriginalPrice() { return originalPrice; }
        public String getSaleId() { return saleId; }
        public boolean isTaxable() { return taxable; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public double getPriceValue() { return price * quantity; }
        public String getPriceStr() { return String.format("$%.2f", price * quantity); }
    }
}
