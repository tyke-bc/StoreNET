package com.dgpos;

import java.io.OutputStream;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class PrinterService {

    // Printer Configuration
    private static final String PRINTER_IP = "192.168.0.179";
    private static final int PRINTER_PORT = 9100;

    // ESC/POS Commands
    private static final byte[] INIT = {0x1b, 0x40};
    private static final byte[] LEFT = {0x1b, 0x61, 0x00};
    private static final byte[] CENTER = {0x1b, 0x61, 0x01};
    private static final byte[] RIGHT = {0x1b, 0x61, 0x02};
    private static final byte[] BOLD_ON = {0x1b, 0x45, 0x01};
    private static final byte[] BOLD_OFF = {0x1b, 0x45, 0x00};
    private static final byte[] DOUBLE_SIZE = {0x1d, 0x21, 0x11};
    private static final byte[] NORMAL_SIZE = {0x1d, 0x21, 0x00};
    private static final byte[] FEED_3 = {0x1b, 0x64, 0x03};
    private static final byte[] CUT = {0x1d, 0x56, 0x41, 0x03};

    private static void sendToPrinter(byte[] data) {
        new Thread(() -> {
            try (Socket socket = new Socket(PRINTER_IP, PRINTER_PORT);
                 OutputStream out = socket.getOutputStream()) {
                out.write(data);
                out.flush();
                System.out.println("Sent " + data.length + " bytes to printer at " + PRINTER_IP);
            } catch (Exception e) {
                System.err.println("Printer Error: " + e.getMessage());
            }
        }).start();
    }

    public static void printReceipt(List<MainTransactionScreen.ScannedItem> items, String eid, String tenderType, String barcode) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(INIT);
            baos.write(CENTER);
            baos.write(BOLD_ON);
            baos.write("DOLLAR GENERAL STORE #14302\n".getBytes());
            baos.write("216 BELKNAP ST\n".getBytes());
            baos.write("SUPERIOR, WI 54880\n".getBytes());
            baos.write("(715) 718-6650\n".getBytes());
            baos.write("SALE TRANSACTION\n\n".getBytes());
            baos.write(BOLD_OFF);

            baos.write(LEFT);
            double subtotal = 0;
            double taxableSubtotal = 0;
            for (MainTransactionScreen.ScannedItem item : items) {
                String name = item.getName();
                String upc = item.getUpc();
                double price = item.getPriceValue();
                double origPriceTotal = item.getOriginalPrice() * item.getQuantity();

                // Layer 1: Name
                baos.write((name + "\n").getBytes());
                
                // Layer 2: UPC (and qty if multiple)
                if (item.getQuantity() > 1) {
                    baos.write(("  " + upc + "   Qty: " + item.getQuantity() + "\n").getBytes());
                } else {
                    baos.write(("  " + upc + "\n").getBytes());
                }

                // Layer 3: Price and Savings
                String priceStr = String.format("$%.2f", price);
                if (origPriceTotal > price && price >= 0) { // Don't show savings on returns
                    double savings = origPriceTotal - price;
                    double percentOff = (savings / origPriceTotal) * 100.0;
                    String saveStr = String.format("Was $%.2f | %.0f%% OFF", origPriceTotal, percentOff);
                    String line = String.format("  %-20s %9s\n", saveStr, priceStr);
                    baos.write(line.getBytes());
                } else {
                    String line = String.format("  %-20s %9s\n", "", priceStr);
                    baos.write(line.getBytes());
                }
                
                subtotal += price;
                if (item.isTaxable()) taxableSubtotal += price;
            }
            baos.write("\n".getBytes());

            baos.write(RIGHT);
            double tax = taxableSubtotal * 0.055;
            double total = subtotal + tax;
            baos.write(String.format("Tax (5.5%%)         $%.2f\n", tax).getBytes());
            baos.write(BOLD_ON);
            baos.write(String.format("Balance to pay       $%.2f\n", total).getBytes());
            baos.write(BOLD_OFF);
            baos.write(String.format("%-20s $%.2f\n\n", tenderType, total).getBytes());

            baos.write(LEFT);
            String dateStr = new SimpleDateFormat("MM-dd-yy hh:mm a").format(new Date());
            baos.write(("STORE 14302   TILL 1   TRANS. " + barcode.substring(Math.max(0, barcode.length() - 6)) + "\n").getBytes());
            baos.write(("DATE " + dateStr + "\n\n").getBytes());
            baos.write(("Your cashier was: " + DatabaseManager.getUserName(eid).toUpperCase() + "\n\n").getBytes());

            // Barcode (Code 128 Subset C) - Maximum numeric compression for 58mm (2.25in) paper
            baos.write(CENTER);
            baos.write(new byte[]{0x1d, 0x68, 0x60}); // Height 96
            baos.write(new byte[]{0x1d, 0x77, 0x02}); // Width 2 (Thick enough for DataLogic scanner)
            baos.write(new byte[]{0x1d, 0x48, 0x00}); // HRI disabled
            
            // Format B Command: [1D 6B 49] [Length] [Data]
            // Subset C pairs digits into single bytes (e.g. "20" -> byte 20)
            byte[] barcodeData = new byte[2 + (barcode.length() / 2)];
            barcodeData[0] = 123; // '{'
            barcodeData[1] = 67;  // 'C'
            for (int i = 0; i < barcode.length() / 2; i++) {
                barcodeData[2 + i] = (byte) Integer.parseInt(barcode.substring(i * 2, i * 2 + 2));
            }
            
            baos.write(new byte[]{0x1d, 0x6b, 0x49}); 
            baos.write((byte) barcodeData.length);
            baos.write(barcodeData);
            // Note: No trailing 0x00 needed for Format B
            
            baos.write(("\n*" + barcode + "*\n\n").getBytes());

            baos.write(FEED_3);
            baos.write(CUT);

            sendToPrinter(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void printLabel(DatabaseManager.LabelData data) {
        new Thread(() -> {
            try {
                final int WIDTH = 384, HEIGHT = 240;
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(WIDTH, HEIGHT, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();

                // White background
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, WIDTH, HEIGHT);
                g.setColor(java.awt.Color.BLACK);

                // --- LEFT COLUMN: item text ---
                String fullText = (s(data.brand) + " " + data.name + " " + s(data.variant) + " " + s(data.size)).trim().replaceAll("\\s+", " ");
                List<String> lines = wrapText(fullText, 13);
                java.awt.Font itemFont = new java.awt.Font("Arial", java.awt.Font.PLAIN, 18);
                g.setFont(itemFont);
                java.awt.FontMetrics fm = g.getFontMetrics();
                int yPos = 10 + fm.getAscent();
                for (int i = 0; i < Math.min(lines.size(), 4); i++) {
                    g.drawString(lines.get(i), 10, yPos);
                    yPos += 28;
                }

                // Barcode (Code128 via ZXing)
                try {
                    com.google.zxing.oned.Code128Writer writer = new com.google.zxing.oned.Code128Writer();
                    String cleanUpc = data.upc.replaceAll("[^0-9A-Za-z]", "");
                    java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
                    hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
                    com.google.zxing.common.BitMatrix matrix = writer.encode(cleanUpc, com.google.zxing.BarcodeFormat.CODE_128, 170, 35, hints);
                    java.awt.image.BufferedImage bcImg = com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix);
                    g.drawImage(bcImg, 5, 125, null);
                } catch (Exception e2) {
                    g.drawString("[BARCODE ERR]", 5, 145);
                }

                // Unit price (bottom left)
                java.awt.Font tinyFont = new java.awt.Font("Arial", java.awt.Font.PLAIN, 12);
                java.awt.Font tinyBoldFont = new java.awt.Font("Arial", java.awt.Font.BOLD, 12);
                java.awt.Font unitPriceFont = new java.awt.Font("Arial", java.awt.Font.BOLD, 17);
                g.setFont(tinyFont);
                java.awt.FontMetrics fmTiny = g.getFontMetrics();
                g.drawString("Unit", 10, 185 + fmTiny.getAscent());
                g.drawString("Price", 10, 205 + fmTiny.getAscent());
                g.setFont(unitPriceFont);
                java.awt.FontMetrics fmUp = g.getFontMetrics();
                g.drawString(String.format("$%.2f", data.price), 50, 180 + fmUp.getAscent());
                g.setFont(tinyFont);
                g.drawString(data.unitPriceUnit, 50, 210 + fmTiny.getAscent());

                // --- RIGHT COLUMN: price box ---
                final int BOX_X = 185, BOX_Y = 5, BOX_W = 190, BOX_H = 125;
                g.setStroke(new java.awt.BasicStroke(3));
                g.drawRect(BOX_X, BOX_Y, BOX_W, BOX_H);
                g.setStroke(new java.awt.BasicStroke(1));

                g.setFont(tinyFont);
                g.drawString("Item Price", BOX_X + 40, BOX_Y + 5 + fmTiny.getAscent());

                int dollars = (int) data.price;
                int cents = (int) Math.round((data.price - dollars) * 100);

                java.awt.Font centsFont = new java.awt.Font("Arial", java.awt.Font.BOLD, 28);
                java.awt.Font dollarsFont = new java.awt.Font("Arial", java.awt.Font.BOLD, 68);

                g.setFont(centsFont);
                java.awt.FontMetrics fmCents = g.getFontMetrics();
                g.drawString("$", BOX_X + 10, BOX_Y + 40 + fmCents.getAscent());

                g.setFont(dollarsFont);
                java.awt.FontMetrics fmDollars = g.getFontMetrics();
                g.drawString(String.valueOf(dollars), BOX_X + 35, BOX_Y + 20 + fmDollars.getAscent());

                g.setFont(centsFont);
                g.drawString(String.format("%02d", cents), BOX_X + 130, BOX_Y + 25 + fmCents.getAscent());

                if (data.taxable) {
                    g.setFont(tinyBoldFont);
                    java.awt.FontMetrics fmTb = g.getFontMetrics();
                    g.drawString("T", BOX_X + 20, BOX_Y + 100 + fmTb.getAscent());
                }

                // Data stack below price box
                int dataY = BOX_Y + BOX_H + 10;
                g.setFont(tinyFont);
                g.drawString(data.upc, BOX_X + 25, dataY + fmTiny.getAscent());
                g.drawString(data.pogDate + "       E", BOX_X + 25, dataY + 22 + fmTiny.getAscent());

                // Location/faces box
                int locX = BOX_X + 110, locY = dataY - 2;
                g.drawRect(locX, locY, 80, 50);
                g.setFont(tinyBoldFont);
                java.awt.FontMetrics fmTbold = g.getFontMetrics();
                g.drawString(data.location, locX + 8, locY + 5 + fmTbold.getAscent());
                g.drawString(data.faces, locX + 25, locY + 25 + fmTbold.getAscent());

                g.dispose();

                // Assemble ESC/POS payload and send
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                baos.write(INIT);
                baos.write(LEFT);
                baos.write(imageToEscPos(img));
                baos.write(new byte[]{0x1d, 0x56, 0x41, 0x00}); // Partial cut

                try (Socket socket = new Socket(PRINTER_IP, PRINTER_PORT);
                     OutputStream out = socket.getOutputStream()) {
                    out.write(baos.toByteArray());
                    out.flush();
                    System.out.println("Label sent: " + baos.size() + " bytes to printer at " + PRINTER_IP);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static byte[] imageToEscPos(java.awt.image.BufferedImage src) throws Exception {
        int w = src.getWidth(), h = src.getHeight();
        int paddedW = (w % 8 == 0) ? w : w + (8 - w % 8);
        int bytesWide = paddedW / 8;

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // GS v 0: raster bit image transfer
        out.write(new byte[]{
            0x1d, 0x76, 0x30, 0x00,
            (byte)(bytesWide & 0xFF), (byte)((bytesWide >> 8) & 0xFF),
            (byte)(h & 0xFF), (byte)((h >> 8) & 0xFF)
        });

        for (int y = 0; y < h; y++) {
            for (int xByte = 0; xByte < bytesWide; xByte++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = xByte * 8 + bit;
                    if (x < w) {
                        int rgb = src.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int gv = (rgb >> 8) & 0xFF;
                        int bv = rgb & 0xFF;
                        if ((r + gv + bv) / 3 < 128) { // darker than mid-gray = black dot
                            b |= (1 << (7 - bit));
                        }
                    }
                }
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static List<String> wrapText(String text, int maxChars) {
        List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() > 0 && current.length() + 1 + word.length() > maxChars) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(" ");
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private static String s(String val) { return val != null ? val : ""; }

    public static void printZOutReport(DatabaseManager.ZOutData data) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(INIT);
            baos.write(CENTER);
            baos.write(BOLD_ON);
            baos.write("DOLLAR GENERAL STORE #14302\n".getBytes());
            baos.write("216 BELKNAP ST\n".getBytes());
            baos.write("SUPERIOR, WI 54880\n".getBytes());
            baos.write(BOLD_OFF);
            baos.write("\n".getBytes());
            baos.write(BOLD_ON);
            baos.write("*** Z-OUT REPORT ***\n".getBytes());
            baos.write(BOLD_OFF);
            String dateStr = new SimpleDateFormat("MM/dd/yy hh:mm a").format(new Date());
            baos.write((dateStr + "\n").getBytes());
            baos.write(("Cashier: " + data.cashierName.toUpperCase() + " (" + data.cashierEid + ")\n").getBytes());

            baos.write(LEFT);
            baos.write("--------------------------------\n".getBytes());
            baos.write(String.format("%-20s %10s\n", "CARD SALES:", String.format("$%.2f", data.cardSales)).getBytes());
            baos.write(String.format("%-20s %10s\n", "CASH SALES:", String.format("$%.2f", data.cashSales)).getBytes());
            baos.write(BOLD_ON);
            baos.write(String.format("%-20s %10s\n", "TOTAL SALES:", String.format("$%.2f", data.cashSales + data.cardSales)).getBytes());
            baos.write(BOLD_OFF);
            baos.write(String.format("%-20s %10s\n", "SALE SAVINGS:", String.format("$%.2f", data.totalSavings)).getBytes());
            baos.write("\n".getBytes());

            if (!data.pickups.isEmpty()) {
                baos.write("PICKUPS:\n".getBytes());
                for (int i = 0; i < data.pickups.size(); i++) {
                    baos.write(String.format("  Pickup #%d           $%.2f\n", i + 1, data.pickups.get(i)).getBytes());
                }
                baos.write(String.format("%-20s %10s\n", "TOTAL PICKUPS:", String.format("$%.2f", data.getTotalPickups())).getBytes());
            } else {
                baos.write("NO PICKUPS THIS SESSION\n".getBytes());
            }
            baos.write("\n".getBytes());

            baos.write("--------------------------------\n".getBytes());
            baos.write(String.format("%-20s %10s\n", "STARTING BANK:", String.format("$%.2f", data.startingBank)).getBytes());
            baos.write(String.format("%-20s %10s\n", "+ CASH SALES:", String.format("$%.2f", data.cashSales)).getBytes());
            baos.write(String.format("%-20s %10s\n", "- PICKUPS:", String.format("$%.2f", data.getTotalPickups())).getBytes());
            baos.write("================================\n".getBytes());
            baos.write(BOLD_ON);
            baos.write(String.format("%-20s %10s\n", "EXPECTED CASH:", String.format("$%.2f", data.getExpectedCash())).getBytes());
            baos.write(BOLD_OFF);

            baos.write(CENTER);
            baos.write("\nKeep this receipt in the\n".getBytes());
            baos.write("manager safe.\n".getBytes());
            baos.write(FEED_3);
            baos.write(CUT);

            sendToPrinter(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void printCoupon() {
        // Fetch active promotions from DB (weekly deal + Saturday special)
        DatabaseManager.PromotionData weekly   = DatabaseManager.getActivePromotion("WEEKLY");
        DatabaseManager.PromotionData saturday = DatabaseManager.getActivePromotion("SATURDAY");

        // Nothing to print if both are inactive
        if (weekly == null && saturday == null) {
            System.out.println("No active promotions — skipping coupon print.");
            return;
        }

        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(INIT);
            baos.write(CENTER);
            baos.write("------ CUT HERE ------\n\n".getBytes());

            // Saturday special (printed first if active)
            if (saturday != null) {
                baos.write(BOLD_ON);
                baos.write((saturday.title + "\n").getBytes());
                if (!saturday.validDate.isEmpty()) {
                    baos.write(("Valid " + saturday.validDate + "\n").getBytes());
                }
                baos.write(DOUBLE_SIZE);
                baos.write(String.format("$%.0f OFF $%.0f\n", saturday.discount, saturday.minimum).getBytes());
                baos.write(NORMAL_SIZE);
                baos.write(String.format("$%.0f off your purchase of $%.0f or more (pretax)\n",
                        saturday.discount, saturday.minimum).getBytes());
                baos.write(BOLD_OFF);
                if (!saturday.finePrint.isEmpty()) {
                    baos.write(LEFT);
                    baos.write(new byte[]{0x1b, 0x33, 0x18});
                    baos.write((saturday.finePrint + "\n").getBytes());
                    baos.write(new byte[]{0x1b, 0x32});
                    baos.write(CENTER);
                }
                baos.write("\n".getBytes());
            }

            // Weekly deal (printed below)
            if (weekly != null) {
                baos.write(BOLD_ON);
                baos.write((weekly.title + "\n").getBytes());
                if (!weekly.validDate.isEmpty()) {
                    baos.write(("Valid " + weekly.validDate + "\n").getBytes());
                }
                baos.write(DOUBLE_SIZE);
                baos.write(String.format("$%.0f OFF $%.0f\n", weekly.discount, weekly.minimum).getBytes());
                baos.write(NORMAL_SIZE);
                baos.write(String.format("$%.0f off your purchase of $%.0f or more (pretax)\n",
                        weekly.discount, weekly.minimum).getBytes());
                baos.write("OR SHOP ONLINE AT DOLLARGENERAL.COM\n".getBytes());
                baos.write(BOLD_OFF);
                if (!weekly.finePrint.isEmpty()) {
                    baos.write(LEFT);
                    baos.write(new byte[]{0x1b, 0x33, 0x18});
                    baos.write((weekly.finePrint + "\n").getBytes());
                    baos.write(new byte[]{0x1b, 0x32});
                    baos.write(CENTER);
                }
            }

            baos.write("\n".getBytes());
            baos.write("NEXT TIME TRY\n".getBytes());
            baos.write(BOLD_ON);
            baos.write("SAME DAY DELIVERY\n".getBytes());
            baos.write(BOLD_OFF);
            baos.write("FIRST ORDER FREE\nWITH MYDG. SIGN UP NOW.\n\n".getBytes());

            baos.write("DOLLAR GENERAL\n".getBytes());
            baos.write("******************************\n".getBytes());
            baos.write("1421-1000-8993-113\n".getBytes());
            baos.write("******************************\n".getBytes());

            baos.write(FEED_3);
            baos.write(CUT);

            sendToPrinter(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
