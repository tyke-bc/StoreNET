package com.dgpos;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

public class PosLogin extends Application {

    private TextField employeeIdField;
    private Label warningLabel;
    private Button clockInOutBtn;

    private VBox centerContent;
    private GridPane numpad;
    private VBox clockActionMenu;
    private VBox overrideConfirmMenu = null;

    private Stage primaryStageRef;

    private enum State {
        LOGIN_EID,
        LOGIN_PASSWORD,
        CLOCK_EID,
        CLOCK_PASSWORD,
        CLOCK_ACTION,
        MESSAGE_DISPLAY,
        HELD_OVERRIDE_CONFIRM
    }

    private State currentState = State.LOGIN_EID;
    private String currentEid = "";
    private DatabaseManager.UserData pendingLoginUser = null;
    private String heldOverrideName = "";

    @Override
    public void start(Stage primaryStage) {
        this.primaryStageRef = primaryStage;

        // Start the backoffice force-close poller once per app lifetime. When a manager
        // queues a force-close via the Store Menu, dgPOS clears its held-drawer flag so
        // this register becomes immediately usable again.
        BackofficeBridge.startForceClosePoller((cmdId, sessionId) -> {
            DatabaseManager.clearHeldDrawer();
            System.out.println("[BackofficeBridge] Applied force-close command " + cmdId + " for session " + sessionId);
        });

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // --- Bottom Status Bar ---
        HBox bottomBar = new HBox(20);
        bottomBar.getStyleClass().add("bottom-bar");
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(10, 20, 10, 20));

        Label statusLabel = new Label("Online");
        statusLabel.getStyleClass().add("status-text");

        Label locationLabel = new Label(StoreConfig.statusLabel());
        locationLabel.getStyleClass().add("status-text");

        Label tillLabel = new Label("Till Id: 1");
        tillLabel.getStyleClass().add("status-text");

        // Held drawer banner — shown in amber when a drawer is held
        Label heldBanner = new Label();
        heldBanner.getStyleClass().add("status-text");
        heldBanner.setStyle("-fx-text-fill: #fbbf24; -fx-font-weight: bold;");
        DatabaseManager.UserData held = DatabaseManager.getHeldDrawer();
        if (held != null) {
            heldBanner.setText("DRAWER HELD BY: " + held.name.toUpperCase());
        } else {
            heldBanner.setVisible(false);
            heldBanner.setManaged(false);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bottomBar.getChildren().addAll(statusLabel, locationLabel, heldBanner, spacer, tillLabel);
        root.setBottom(bottomBar);

        // --- Center Content Stack ---
        centerContent = new VBox(25);
        centerContent.setAlignment(Pos.CENTER);
        centerContent.setMaxWidth(800);

        Label banner = new Label("DOLLAR GENERAL POS");
        banner.getStyleClass().add("banner-text");

        warningLabel = new Label("ENTER EMPLOYEE ID OR SCAN BADGE");
        warningLabel.getStyleClass().add("warning-text");

        employeeIdField = new TextField();
        employeeIdField.setPromptText("Employee ID");
        employeeIdField.getStyleClass().add("employee-input");
        employeeIdField.setMaxWidth(350);

        // Numpad Grid
        numpad = createNumpad();
        numpad.setAlignment(Pos.CENTER);

        // Clock Action Menu
        clockActionMenu = createClockActionMenu();
        clockActionMenu.setAlignment(Pos.CENTER);

        centerContent.getChildren().addAll(banner, warningLabel, employeeIdField, numpad);
        root.setCenter(centerContent);

        // --- Bulletproof Scanner Logic ---
        employeeIdField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                processInput(employeeIdField.getText());
            }
        });

        // Scene Setup
        Scene scene = new Scene(root, 1024, 768);

        try {
            String cssPath = getClass().getResource("/style.css").toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (NullPointerException e) {
            System.err.println("Warning: style.css not found in resources. Running without styling.");
        }

        primaryStage.setTitle("dgpOS - Login");
        primaryStage.setScene(scene);
        primaryStage.setFullScreen(true);
        primaryStage.setFullScreenExitHint("");
        primaryStage.show();

        // KIOSK LOCKDOWN: Keep focus permanently on the input field
        Platform.runLater(() -> employeeIdField.requestFocus());
        employeeIdField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal && currentState != State.CLOCK_ACTION
                    && currentState != State.MESSAGE_DISPLAY
                    && currentState != State.HELD_OVERRIDE_CONFIRM) {
                Platform.runLater(() -> employeeIdField.requestFocus());
            }
        });

        updateUIForState();
    }

    private GridPane createNumpad() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setAlignment(Pos.CENTER);

        // Left Column: Clock In/Out
        clockInOutBtn = createActionButton("Clock\nIn / Out");
        clockInOutBtn.setPrefHeight(325);
        grid.add(clockInOutBtn, 0, 0, 1, 4);
        clockInOutBtn.setOnAction(e -> {
            if (currentState == State.LOGIN_EID || currentState == State.LOGIN_PASSWORD) {
                currentState = State.CLOCK_EID;
                currentEid = "";
                updateUIForState();
            }
            employeeIdField.requestFocus();
        });

        // Center 3x4 Grid: Numbers
        String[][] keys = {
            {"7", "8", "9"},
            {"4", "5", "6"},
            {"1", "2", "3"},
            {"0", "00", "."}
        };

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 3; col++) {
                String keyText = keys[row][col];
                Button btn = createNumpadButton(keyText);
                btn.setOnAction(e -> {
                    employeeIdField.appendText(keyText);
                    employeeIdField.requestFocus();
                });
                grid.add(btn, col + 1, row);
            }
        }

        // Right Column: Action Keys
        Button backspaceBtn = createActionButton("Backspace");
        backspaceBtn.setOnAction(e -> {
            String text = employeeIdField.getText();
            if (!text.isEmpty()) {
                employeeIdField.setText(text.substring(0, text.length() - 1));
            }
            employeeIdField.requestFocus();
        });
        grid.add(backspaceBtn, 4, 0);

        Button cancelBtn = createActionButton("Cancel");
        cancelBtn.setOnAction(e -> {
            handleCancel();
            employeeIdField.requestFocus();
        });
        grid.add(cancelBtn, 4, 1);

        Button enterBtn = createActionButton("Enter");
        enterBtn.getStyleClass().add("enter-button");
        enterBtn.setPrefHeight(155);
        enterBtn.setOnAction(e -> processInput(employeeIdField.getText()));
        grid.add(enterBtn, 4, 2, 1, 2);

        return grid;
    }

    private VBox createClockActionMenu() {
        VBox menu = new VBox(15);

        Button clockInBtn = createMenuButton("Clock In");
        Button clockOutBtn = createMenuButton("Clock Out");
        Button breakInBtn = createMenuButton("Break In");
        Button breakOutBtn = createMenuButton("Break Out");
        Button closeBtn = createMenuButton("Close");

        clockInBtn.setOnAction(e -> finishClockAction("Clocked In"));
        clockOutBtn.setOnAction(e -> finishClockAction("Clocked Out"));
        breakInBtn.setOnAction(e -> finishClockAction("Break In Recorded"));
        breakOutBtn.setOnAction(e -> finishClockAction("Break Out Recorded"));
        closeBtn.setOnAction(e -> {
            currentState = State.LOGIN_EID;
            updateUIForState();
        });

        menu.getChildren().addAll(clockInBtn, clockOutBtn, breakInBtn, breakOutBtn, closeBtn);
        return menu;
    }

    private VBox createOverrideMenu() {
        VBox menu = new VBox(15);
        menu.setAlignment(Pos.CENTER);

        Button yesBtn = createMenuButton("Yes - Override & Z-Out");
        Button noBtn = createMenuButton("No - Go Back");

        yesBtn.setOnAction(e -> {
            DatabaseManager.UserData heldUser = DatabaseManager.getHeldDrawer();
            if (heldUser != null) {
                DatabaseManager.ZOutData zData = DatabaseManager.getZOutData(heldUser.eid);
                PrinterService.printZOutReport(zData);
                DatabaseManager.logAction(heldUser.eid, "ZOUT", "");
                DatabaseManager.clearHeldDrawer();
            }
            proceedToTransactionScreen(pendingLoginUser);
        });

        noBtn.setOnAction(e -> {
            pendingLoginUser = null;
            heldOverrideName = "";
            currentState = State.LOGIN_EID;
            currentEid = "";
            updateUIForState();
        });

        menu.getChildren().addAll(yesBtn, noBtn);
        return menu;
    }

    private Button createMenuButton(String text) {
        Button btn = new Button(text);
        btn.getStyleClass().addAll("numpad-btn", "action-btn");
        btn.setPrefSize(300, 70);
        return btn;
    }

    private Button createNumpadButton(String text) {
        Button btn = new Button(text);
        btn.getStyleClass().addAll("numpad-btn", "number-btn");
        btn.setPrefSize(100, 70);
        return btn;
    }

    private Button createActionButton(String text) {
        Button btn = new Button(text);
        btn.getStyleClass().addAll("numpad-btn", "action-btn");
        btn.setPrefSize(140, 70);
        return btn;
    }

    private void handleCancel() {
        employeeIdField.clear();
        currentState = State.LOGIN_EID;
        currentEid = "";
        pendingLoginUser = null;
        heldOverrideName = "";
        updateUIForState();
    }

    private void updateUIForState() {
        employeeIdField.clear();

        // Reset: remove override menu if present, restore numpad
        clockInOutBtn.setStyle("");
        if (overrideConfirmMenu != null && centerContent.getChildren().contains(overrideConfirmMenu)) {
            centerContent.getChildren().remove(overrideConfirmMenu);
            overrideConfirmMenu = null;
        }
        if (!centerContent.getChildren().contains(numpad)) {
            centerContent.getChildren().remove(clockActionMenu);
            centerContent.getChildren().add(numpad);
        }
        employeeIdField.setVisible(true);
        employeeIdField.setDisable(false);

        switch (currentState) {
            case LOGIN_EID:
                warningLabel.setText("ENTER EMPLOYEE ID OR SCAN BADGE");
                employeeIdField.setPromptText("Employee ID");
                break;
            case LOGIN_PASSWORD:
                warningLabel.setText("ENTER PASSWORD FOR EID: " + currentEid);
                employeeIdField.setPromptText("Password");
                break;
            case CLOCK_EID:
                clockInOutBtn.setStyle("-fx-background-color: #7f8c8d; -fx-text-fill: white;");
                warningLabel.setText("CLOCK IN/OUT: ENTER EMPLOYEE ID");
                employeeIdField.setPromptText("Employee ID");
                break;
            case CLOCK_PASSWORD:
                clockInOutBtn.setStyle("-fx-background-color: #7f8c8d; -fx-text-fill: white;");
                warningLabel.setText("CLOCK IN/OUT: ENTER PIN FOR " + currentEid);
                employeeIdField.setPromptText("PIN");
                break;
            case CLOCK_ACTION:
                warningLabel.setText("SELECT CLOCK ACTION");
                employeeIdField.setVisible(false);
                centerContent.getChildren().remove(numpad);
                centerContent.getChildren().add(clockActionMenu);
                break;
            case HELD_OVERRIDE_CONFIRM:
                warningLabel.setText("OVERRIDE TILL USER: " + heldOverrideName.toUpperCase() + "?");
                employeeIdField.setVisible(false);
                centerContent.getChildren().remove(numpad);
                overrideConfirmMenu = createOverrideMenu();
                centerContent.getChildren().add(overrideConfirmMenu);
                break;
            case MESSAGE_DISPLAY:
                employeeIdField.setDisable(true);
                break;
        }

        if (currentState != State.CLOCK_ACTION
                && currentState != State.MESSAGE_DISPLAY
                && currentState != State.HELD_OVERRIDE_CONFIRM) {
            Platform.runLater(() -> employeeIdField.requestFocus());
        }
    }

    private void processInput(String input) {
        if (input == null || input.trim().isEmpty()) return;

        switch (currentState) {
            case LOGIN_EID:
                currentEid = input;
                currentState = State.LOGIN_PASSWORD;
                updateUIForState();
                break;
            case LOGIN_PASSWORD:
                DatabaseManager.UserData user = DatabaseManager.authenticateUser(currentEid, input);
                if (user != null) {
                    DatabaseManager.UserData heldDrawer = DatabaseManager.getHeldDrawer();
                    if (heldDrawer != null) {
                        if ("SA".equals(user.role)) {
                            displayTemporaryMessage("OVERRIDE NOT PERMITTED", State.LOGIN_EID);
                        } else {
                            // Key-holder — offer override
                            pendingLoginUser = user;
                            heldOverrideName = heldDrawer.name;
                            currentState = State.HELD_OVERRIDE_CONFIRM;
                            updateUIForState();
                        }
                    } else {
                        proceedToTransactionScreen(user);
                    }
                } else {
                    displayTemporaryMessage("INVALID CREDENTIALS!", State.LOGIN_EID);
                }
                break;
            case CLOCK_EID:
                currentEid = input;
                currentState = State.CLOCK_PASSWORD;
                updateUIForState();
                break;
            case CLOCK_PASSWORD:
                DatabaseManager.UserData clockUser = DatabaseManager.authenticateUser(currentEid, input);
                if (clockUser != null) {
                    currentState = State.CLOCK_ACTION;
                    updateUIForState();
                } else {
                    displayTemporaryMessage("INVALID CREDENTIALS!", State.CLOCK_EID);
                }
                break;
            case CLOCK_ACTION:
            case HELD_OVERRIDE_CONFIRM:
            case MESSAGE_DISPLAY:
                break;
        }
    }

    private void proceedToTransactionScreen(DatabaseManager.UserData user) {
        currentState = State.MESSAGE_DISPLAY;
        updateUIForState();
        warningLabel.setText("LOGIN SUCCESSFUL!");

        // Inform the backoffice that a till session is opening on this register.
        BackofficeBridge.startSession(user.eid, user.name, DatabaseManager.getStartingBank());

        PauseTransition pause = new PauseTransition(Duration.seconds(1.0));
        pause.setOnFinished(e -> MainTransactionScreen.show(primaryStageRef, user.eid));
        pause.play();
    }

    private void displayTemporaryMessage(String message, State nextState) {
        currentState = State.MESSAGE_DISPLAY;
        updateUIForState();
        warningLabel.setText(message);

        PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
        pause.setOnFinished(e -> {
            currentState = nextState;
            if (nextState == State.LOGIN_EID || nextState == State.CLOCK_EID) {
                currentEid = "";
            }
            updateUIForState();
        });
        pause.play();
    }

    private void finishClockAction(String action) {
        String dbAction = switch (action) {
            case "Clocked In"          -> "CLOCK_IN";
            case "Clocked Out"         -> "CLOCK_OUT";
            case "Break In Recorded"   -> "BREAK_IN";
            case "Break Out Recorded"  -> "BREAK_OUT";
            default -> "CLOCK_IN";
        };
        DatabaseManager.logTimePunch(currentEid, dbAction);
        BackofficeBridge.logPunch(currentEid, dbAction);
        displayTemporaryMessage(action.toUpperCase() + " SUCCESSFUL!", State.LOGIN_EID);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
