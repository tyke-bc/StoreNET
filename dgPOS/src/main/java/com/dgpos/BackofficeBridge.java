package com.dgpos;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fire-and-forget HTTP bridge from dgPOS to the StoreNET backoffice. Every money-touching
 * event (pickup, refund, cash sale, Z-Out) gets POSTed here so the backoffice can render a
 * live "Till &amp; Cash" reconcile view. Also polls for manager-initiated force-close commands.
 *
 * Nothing here should ever block the UI thread or throw — failures are logged and swallowed.
 */
public class BackofficeBridge {
    private static final Properties cfg = new Properties();
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static volatile Long currentSessionId = null;
    private static ScheduledExecutorService poller = null;

    private static String backofficeUrl;
    private static String storeId;
    private static String registerId;
    private static boolean enabled = true;

    static {
        try (InputStream in = BackofficeBridge.class.getResourceAsStream("/config.properties")) {
            if (in != null) cfg.load(in);
        } catch (Exception e) { /* ignored — falls through to defaults */ }
        backofficeUrl = cfg.getProperty("backoffice.url", "http://192.168.0.192:3000");
        storeId       = cfg.getProperty("store.id", "1");
        registerId    = cfg.getProperty("register.id", "REG1");
        String flag   = cfg.getProperty("backoffice.enabled", "true");
        enabled = !"false".equalsIgnoreCase(flag.trim());
    }

    // --- public API ---------------------------------------------------------

    public static String registerId() { return registerId; }

    /** POST a session-start. Stashes the returned session_id for subsequent event posts. */
    public static void startSession(String eid, String name, double startingBank) {
        if (!enabled) return;
        String body = json(
                "register_id", registerId,
                "eid", eid,
                "eid_name", name,
                "starting_bank", startingBank,
                "opened_at", nowIso());
        fireAsync("POST", "/api/till/sessions/start", body, (code, response) -> {
            Long id = extractLong(response, "session_id");
            if (id != null) currentSessionId = id;
        });
    }

    /** Record a till event. amount is always positive; the event_type implies the sign. */
    public static void logEvent(String eventType, double amount, String note,
                                String receiptId, String authorizedBy, String eid) {
        if (!enabled) return;
        String body = json(
                "register_id", registerId,
                "eid", eid == null ? "" : eid,
                "event_type", eventType,
                "amount", amount,
                "authorized_by", authorizedBy,
                "receipt_id", receiptId,
                "note", note,
                "occurred_at", nowIso());
        fireAsync("POST", "/api/till/events", body, null);
    }

    /** Close the current session with an actual cash count. */
    public static void closeSession(double actualCash, String notes) {
        if (!enabled) return;
        String body = json(
                "session_id", currentSessionId,
                "register_id", registerId,
                "actual_cash", actualCash,
                "closed_at", nowIso(),
                "notes", notes);
        fireAsync("POST", "/api/till/sessions/close", body, (code, response) -> {
            currentSessionId = null;
        });
    }

    /** Log a clock punch (CLOCK_IN / CLOCK_OUT / BREAK_IN / BREAK_OUT) as a session-less event. */
    public static void logPunch(String eid, String action) {
        if (!enabled) return;
        String body = json(
                "register_id", registerId,
                "eid", eid,
                "event_type", action,
                "amount", 0.0,
                "occurred_at", nowIso());
        fireAsync("POST", "/api/till/events", body, null);
    }

    /**
     * Start polling the backoffice every 60s for force-close commands targeting this register.
     * When a command is found, the callback runs (typically to clear the held drawer locally),
     * then dgPOS POSTs an ack back. Idempotent — multiple calls are no-ops.
     */
    public static synchronized void startForceClosePoller(BiConsumer<Long, Long> onCommand) {
        if (!enabled || poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BackofficeBridge-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backofficeUrl + "/api/till/pending-commands?register_id=" + registerId))
                        .header("X-Store-ID", storeId)
                        .timeout(Duration.ofSeconds(4))
                        .GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) return;
                String body = res.body();
                // Parse each command entry using a simple regex scan; we only need id + session_id.
                Matcher m = Pattern.compile("\"id\":(\\d+)[^}]*?\"session_id\":(\\d+)").matcher(body);
                while (m.find()) {
                    long cmdId = Long.parseLong(m.group(1));
                    long sessionId = Long.parseLong(m.group(2));
                    try { onCommand.accept(cmdId, sessionId); }
                    catch (Exception e) { System.err.println("[BackofficeBridge] force-close handler failed: " + e.getMessage()); continue; }
                    // Ack.
                    fireAsync("POST", "/api/till/commands/" + cmdId + "/applied", "{}", null);
                }
            } catch (Exception e) {
                // Network down / backoffice unreachable — stay silent. Next tick will retry.
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    // --- internals ----------------------------------------------------------

    private static String nowIso() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static void fireAsync(String method, String path, String body,
                                  BiConsumer<Integer, String> onDone) {
        Thread t = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(backofficeUrl + path))
                        .header("Content-Type", "application/json")
                        .header("X-Store-ID", storeId)
                        .timeout(Duration.ofSeconds(4))
                        .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (onDone != null) onDone.accept(res.statusCode(), res.body());
            } catch (Exception e) {
                // Fire-and-forget — never surface to UI.
                System.err.println("[BackofficeBridge] " + method + " " + path + " failed: " + e.getMessage());
            }
        }, "BackofficeBridge-" + path);
        t.setDaemon(true);
        t.start();
    }

    /** Minimal JSON builder. Accepts alternating key/value pairs; values may be String/Number/Boolean/null. */
    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v.toString());
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Long extractLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }
}
