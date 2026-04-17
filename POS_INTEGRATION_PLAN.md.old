# POS & Hardware Integration Plan

This document outlines the strategy for integrating the raw ESC/POS printing logic currently written in Python (`RecieptApp/print_receipt.py`) into the Java-based `dgPOS` system.

## 1. Current State Analysis
*   **Java POS (`dgPOS`):** `PrinterService.java` currently uses the `javax.print` API to send raw ASCII text to a locally installed driver named "PT300". It relies on the OS spooler and cannot easily print complex barcodes or graphics.
*   **Python Script (`RecieptApp`):** `print_receipt.py` bypasses OS drivers entirely, opening a raw TCP socket (`192.168.0.179:9100`) to send native ESC/POS hexadecimal commands. It successfully prints formatted receipts, Code39/Code128 barcodes, QR codes, and rasterized label images.

## 2. Integration Strategy: Native Java ESC/POS via Sockets

**Decision:** We will **Translate the ESC/POS logic to Java**. 
*   *Why not call the Python script?* Managing a Python environment, dependencies (`Pillow`, `python-barcode`), and inter-process communication (IPC) via `ProcessBuilder` adds unnecessary fragility to a Point of Sale terminal.
*   *Why native Java?* Java has excellent, built-in support for TCP Sockets and byte array manipulation. Translating the byte codes from Python to Java is straightforward and guarantees a zero-dependency, standalone POS executable.

## 3. Implementation Steps for Receipt & Coupon Flow

### Step 1: Rewrite `PrinterService.java`
Replace the `PrintServiceLookup` logic with a raw `java.net.Socket` connection.

```java
// Example Java Socket implementation
try (Socket socket = new Socket("192.168.0.179", 9100);
     OutputStream out = socket.getOutputStream()) {
    
    byte[] INIT = new byte[]{0x1b, 0x40};
    byte[] CUT = new byte[]{0x1d, 0x56, 0x41, 0x03};
    // ... define other ESC/POS byte arrays mapping to the python script
    
    out.write(INIT);
    out.write("DOLLAR GENERAL STORE #14302\n".getBytes());
    // ... write receipt data ...
    out.write(CUT);
    out.flush();
}
```

### Step 2: Update `MainTransactionScreen.java` Flow
The current flow prints the receipt and immediately clears the transaction. We need to introduce a blocking prompt for the coupon.

1.  **Tender Button Clicked (Cash/Card):**
2.  Call `PrinterService.printReceipt(items, eid, tenderType)`.
3.  **Show Prompt:** Launch a JavaFX `Alert` (or custom Stage) that says: *"Transaction Complete. Please tear off the receipt."* with buttons **[Print Coupon]** and **[Skip]**.
4.  **Wait for User:** The system waits for the cashier to tear the paper and make a selection.
5.  **Action:**
    *   If `[Print Coupon]`: Call a new method `PrinterService.printCoupon()`, sending the specific ESC/POS bytes for the $5 OFF $25 layout (including the barcode).
    *   If `[Skip]`: Do nothing.
6.  **Cleanup:** Clear the `TableView`, reset totals, and refocus the scanner input.

## 4. Implementation Steps for Label Printing (Warehouse Stickers)

The python script handles warehouse stickers by generating an image using `Pillow`, rendering text and a Code128 barcode, rasterizing it, and sending it via the `GS v 0` ESC/POS command.

### Translation to Java
1.  **Image Generation:** Use Java's native `java.awt.Graphics2D` and `BufferedImage` to draw the sticker layout (rectangles, text, fonts).
2.  **Barcode Generation:** Instead of Python's `python-barcode`, include a lightweight Java barcode library (like `Zxing` or `Barcode4J` via Maven in `pom.xml`) to generate the Code128 `BufferedImage`.
3.  **Rasterization:** Write a helper function in Java to convert the `BufferedImage` pixels into the 1-bit monochrome raster byte array expected by ESC/POS (matching the `image_to_escpos` python function).
4.  **UI Hook:** Add a "Print Shelf Label" button to the `Manager Menu` in `MainTransactionScreen.java` that prompts for a UPC, looks it up in `DatabaseManager`, and triggers the new label printing routine.