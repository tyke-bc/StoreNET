package com.dgpos;

import java.io.InputStream;
import java.util.Properties;

/**
 * Per-install store identity read from config.properties at startup. Anything that used to be
 * hardcoded ("DOLLAR GENERAL STORE #14302", "216 BELKNAP ST", printer IP) now lives here so a
 * new store install is just a config edit, not a recompile.
 */
public final class StoreConfig {
    private static final Properties cfg = new Properties();

    static {
        try (InputStream in = StoreConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) cfg.load(in);
        } catch (Exception e) {
            System.err.println("StoreConfig: failed to load config.properties: " + e.getMessage());
        }
    }

    private StoreConfig() {}

    public static String storeId()     { return cfg.getProperty("store.id",     "0"); }
    public static String storeName()   { return cfg.getProperty("store.name",   "DOLLAR GENERAL"); }
    public static String street()      { return cfg.getProperty("store.street", ""); }
    public static String city()        { return cfg.getProperty("store.city",   ""); }
    public static String state()       { return cfg.getProperty("store.state",  ""); }
    public static String zip()         { return cfg.getProperty("store.zip",    ""); }
    public static String phone()       { return cfg.getProperty("store.phone",  ""); }
    public static String printerIp()   { return cfg.getProperty("printer.ip",   "127.0.0.1"); }
    public static int    printerPort() {
        try { return Integer.parseInt(cfg.getProperty("printer.port", "9100")); }
        catch (NumberFormatException e) { return 9100; }
    }
    public static double taxRate() {
        try { return Double.parseDouble(cfg.getProperty("store.tax_rate", "0.055")); }
        catch (NumberFormatException e) { return 0.055; }
    }

    /** "DOLLAR GENERAL STORE #14302" — used as the big bold line on the top of receipts. */
    public static String receiptHeader() {
        return storeName() + " STORE #" + storeId();
    }

    /** "Store #14302 - Superior, WI" — used on the POS login window's status line. */
    public static String statusLabel() {
        StringBuilder sb = new StringBuilder("Store #").append(storeId());
        if (!city().isEmpty() || !state().isEmpty()) {
            sb.append(" - ");
            if (!city().isEmpty()) sb.append(capitalize(city()));
            if (!city().isEmpty() && !state().isEmpty()) sb.append(", ");
            if (!state().isEmpty()) sb.append(state().toUpperCase());
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
