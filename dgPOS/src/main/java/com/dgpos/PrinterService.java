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
            }
            baos.write("\n".getBytes());

            baos.write(RIGHT);
            double tax = subtotal * 0.055;
            double total = subtotal + tax;
            baos.write(String.format("Tax:  $%.2f @ 5.5%%   $%.2f\n", subtotal, tax).getBytes());
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

    public static void printCoupon() {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(INIT);
            baos.write(CENTER);
            baos.write("------ CUT HERE ------\n\n".getBytes());
            
            baos.write(BOLD_ON);
            baos.write("SATURDAY MAR. 28TH!\n".getBytes());
            baos.write("Valid 3/28/2026\n".getBytes());
            baos.write(DOUBLE_SIZE);
            baos.write("$5 OFF $25\n".getBytes());
            baos.write(NORMAL_SIZE);
            baos.write("$5 off your purchase of\n".getBytes());
            baos.write("$25 or more (pretax)\n".getBytes());
            baos.write("OR SHOP ONLINE AT DOLLARGENERAL.COM\n".getBytes());
            baos.write(BOLD_OFF);

            baos.write(LEFT);
            baos.write(new byte[]{0x1b, 0x33, 0x18}); // Set line spacing
            baos.write("$25 or more (pretax) after all other DG\ndiscounts. Limit one DG $2, $3, or $5\noff store coupon per customer.\nExcludes: phone, gift and prepaid\nfinancial cards, prepaid wireless\nhandsets, Rug Doctor rental, milk,\npropane, tobacco and alcohol.\n".getBytes());
            baos.write(new byte[]{0x1b, 0x32}); // Reset line spacing

            // Barcode (Code 128 mixed Subset B and C) - Fits 58mm paper at Width 2
            baos.write(CENTER);
            baos.write(new byte[]{0x1d, 0x68, 0x60}); // Height 96
            baos.write(new byte[]{0x1d, 0x77, 0x02}); // Width 2
            baos.write(new byte[]{0x1d, 0x48, 0x00}); // HRI disabled
            
            byte[] couponBarcode = new byte[]{
                123, 66, // {B (Start Subset B)
                88,      // 'X'
                123, 67, // {C (Switch to Subset C)
                5, 41, 53, 22, 41, 40, 4, 31 // Numeric pairs for "0541532241400431"
            };
            
            baos.write(new byte[]{0x1d, 0x6b, 0x49});
            baos.write((byte) couponBarcode.length);
            baos.write(couponBarcode);
            
            baos.write(("\n*X0541532241400431*\n\n").getBytes());

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
