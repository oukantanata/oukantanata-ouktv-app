package com.ouktv.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * Ported from server.ts. Same routes, same room-state logic, but backed by
 * SQLite instead of Postgres, and running as an in-process WebSocket relay
 * on the phone itself instead of a remote VM. Reachable from other devices
 * on the same WiFi network at http://<phone-lan-ip>:PORT/ktv/.
 */
public class KtvHttpServer extends NanoWSD {

    private final Context ctx;
    private final Db dbHelper;
    private final Map<String, Set<KtvSocket>> rooms = new ConcurrentHashMap<>();
    private String cachedHtml;

    public KtvHttpServer(Context ctx, int port) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.dbHelper = new Db(this.ctx);
    }

    // ---------- WebSocket ----------

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        String room = "";
        List<String> roomParams = handshake.getParameters().get("room");
        if (roomParams != null && !roomParams.isEmpty()) room = roomParams.get(0).toUpperCase();
        return new KtvSocket(handshake, room);
    }

    private class KtvSocket extends WebSocket {
        final String room;

        KtvSocket(IHTTPSession handshake, String room) {
            super(handshake);
            this.room = room;
        }

        @Override
        protected void onOpen() {
            rooms.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(this);
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            Set<KtvSocket> set = rooms.get(room);
            if (set != null) set.remove(this);
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            // Clients only listen; they never push state over the socket.
        }

        @Override
        protected void onPong(WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            Set<KtvSocket> set = rooms.get(room);
            if (set != null) set.remove(this);
        }
    }

    private void broadcast(String code, JSONObject payload) {
        Set<KtvSocket> set = rooms.get(code);
        if (set == null) return;
        String msg = payload.toString();
        for (KtvSocket ws : set) {
            try {
                if (ws.isOpen()) ws.send(msg);
            } catch (Exception ignored) {}
        }
    }

    // ---------- HTTP routing ----------

    private static boolean isWsRequested(IHTTPSession session) {
        String upgrade = session.getHeaders().get("upgrade");
        String connection = session.getHeaders().get("connection");
        boolean hasUpgrade = upgrade != null && upgrade.toLowerCase().contains("websocket");
        boolean hasConnectionUpgrade = connection != null && connection.toLowerCase().contains("upgrade");
        return hasUpgrade && hasConnectionUpgrade;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (isWsRequested(session)) {
            return super.serve(session);
        }
        String uri = session.getUri();
        String publicUrl = resolvePublicUrl(session);
        try {
            if (uri.startsWith("/api")) {
                return apiRoute(session, uri.substring(4).isEmpty() ? "/" : uri.substring(4), publicUrl);
            }
            if (uri.startsWith("/ext/")) {
                return serveAsset("www" + uri);
            }
            if (uri.equals("/") || uri.equals("/ktv") || uri.equals("/ktv/") || uri.startsWith("/ktv/")) {
                return serveHtml(publicUrl);
            }
            return jsonError("Not found: " + uri, 404);
        } catch (Exception e) {
            return jsonError(e.getMessage() == null ? "Internal error" : e.getMessage(), 500);
        }
    }

    /** Builds PUBLIC_URL from whatever Host header the client actually used
     *  to reach us — works correctly for 127.0.0.1 on the host device AND
     *  the LAN IP guests use, without hardcoding either. */
    private String resolvePublicUrl(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        if (host == null || host.isEmpty()) host = "127.0.0.1:" + getListeningPort();
        return "http://" + host;
    }

    /** QR codes must always point guests at the host's real LAN address —
     *  never at 127.0.0.1, even if the request that generated the QR image
     *  itself came from the host device's own WebView (which always talks
     *  to 127.0.0.1 internally). */
    private String resolveLanUrl() {
        String ip = NetUtils.getLocalIpAddress();
        if (ip != null && !ip.isEmpty()) {
            return "http://" + ip + ":" + getListeningPort();
        }
        return "http://127.0.0.1:" + getListeningPort();
    }

    // ---------- static / html ----------

    private Response serveAsset(String assetPath) {
        try {
            AssetManager am = ctx.getAssets();
            InputStream is = am.open(assetPath);
            String mime = mimeFor(assetPath);
            return newFixedLengthResponse(Response.Status.OK, mime, is, is.available());
        } catch (IOException e) {
            return jsonError("Not found", 404);
        }
    }

    private String mimeFor(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    private Response serveHtml(String publicUrl) {
        try {
            if (cachedHtml == null) {
                AssetManager am = ctx.getAssets();
                InputStream is = am.open("www/ktv-app.html");
                byte[] buf = new byte[is.available()];
                int off = 0;
                while (off < buf.length) {
                    int n = is.read(buf, off, buf.length - off);
                    if (n <= 0) break;
                    off += n;
                }
                is.close();
                cachedHtml = new String(buf, StandardCharsets.UTF_8);
            }
            String html = cachedHtml
                    .replace("__PUBLIC_URL__", publicUrl)
                    .replace("__ANONKEY__", "self-hosted-anon-key");
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        } catch (IOException e) {
            return jsonError("Could not load app", 500);
        }
    }

    // ---------- JSON helpers ----------

    private Response jsonResponse(Object data, int status) {
        Response.IStatus st = statusFor(status);
        Response r = newFixedLengthResponse(st, "application/json", data.toString());
        addCors(r);
        return r;
    }

    private Response jsonError(String msg, int status) {
        JSONObject o = new JSONObject();
        try { o.put("error", msg); } catch (Exception ignored) {}
        return jsonResponse(o, status);
    }

    private void addCors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "authorization,apikey,content-type,x-client-info");
    }

    private Response.IStatus statusFor(int code) {
        for (Response.Status s : Response.Status.values()) {
            if (s.getRequestStatus() == code) return s;
        }
        return Response.Status.OK;
    }

    private JSONObject readJsonBody(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String raw = files.get("postData");
            if (raw == null) return new JSONObject();
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---------- API ----------

    private Response apiRoute(IHTTPSession session, String path, String publicUrl) throws Exception {
        if (session.getMethod() == Method.OPTIONS) {
            Response r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
            addCors(r);
            return r;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String authHeader = session.getHeaders().get("authorization");

        // ===== Public =====
        if (path.equals("/config") && session.getMethod() == Method.GET) {
            JSONObject c = getConfigRow(db);
            if (c == null) return jsonError("config not found", 500);
            return jsonResponse(configToPublic(c), 200);
        }

        if (path.equals("/popular") && session.getMethod() == Method.GET) {
            return jsonResponse(YoutubeSearch.popular(), 200);
        }

        if (path.startsWith("/search") && session.getMethod() == Method.GET) {
            String q = firstParam(session, "q", "");
            String type = firstParam(session, "type", "karaoke");
            String genre = firstParam(session, "genre", "");
            if (q.isEmpty()) return jsonResponse(new JSONArray(), 200);
            return jsonResponse(YoutubeSearch.search(q, type, genre), 200);
        }

        if (path.startsWith("/qr-image/") && session.getMethod() == Method.GET) {
            String code = path.substring("/qr-image/".length()).toUpperCase();
            JSONObject cfg = getConfigRow(db);
            String base = cfg.optString("base_path", "ktv");
            String joinUrl = resolveLanUrl() + "/" + base + "#ktv=" + code + "&remote=1";
            byte[] png = QrUtil.renderPng(joinUrl, 300);
            return newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(png), png.length);
        }

        if (path.startsWith("/qr/") && session.getMethod() == Method.GET) {
            String code = path.substring("/qr/".length()).toUpperCase();
            JSONObject cfg = getConfigRow(db);
            String base = cfg.optString("base_path", "ktv");
            String joinUrl = resolveLanUrl() + "/" + base + "#ktv=" + code + "&remote=1";
            JSONObject o = new JSONObject();
            o.put("qr", resolveLanUrl() + "/api/qr-image/" + code);
            o.put("url", joinUrl);
            return jsonResponse(o, 200);
        }

        if (path.equals("/login") && session.getMethod() == Method.POST) {
            JSONObject body = readJsonBody(session);
            String email = body.optString("email", "").trim().toLowerCase();
            if (email.isEmpty() || !email.contains("@")) return jsonError("Invalid email", 400);
            String now = Db.nowIso();
            db.execSQL("INSERT INTO ktv_users (email,last_seen,created_at) VALUES (?,?,?) " +
                    "ON CONFLICT(email) DO UPDATE SET last_seen=excluded.last_seen", new Object[]{email, now, now});
            db.execSQL("INSERT INTO ktv_logins (email, ip, login_at) VALUES (?,?,?)",
                    new Object[]{email, session.getHeaders().get("http-client-ip") == null ? "" : session.getHeaders().get("http-client-ip"), now});
            String token = Db.sha256(email + "::ktv-self-hosted-salt");
            JSONObject o = new JSONObject();
            o.put("token", token);
            o.put("email", email);
            return jsonResponse(o, 200);
        }

        if (path.equals("/ktvs") && session.getMethod() == Method.POST) {
            String code = Db.genCode();
            try {
                db.execSQL("INSERT INTO ktv_rooms (code, created_at) VALUES (?,?)", new Object[]{code, Db.nowIso()});
            } catch (Exception e) {
                code = Db.genCode();
                db.execSQL("INSERT INTO ktv_rooms (code, created_at) VALUES (?,?)", new Object[]{code, Db.nowIso()});
            }
            JSONObject o = new JSONObject();
            o.put("code", code);
            return jsonResponse(o, 200);
        }

        if (path.startsWith("/ktv/check/") && session.getMethod() == Method.GET) {
            String code = path.substring("/ktv/check/".length()).toUpperCase();
            Cursor c = db.rawQuery("SELECT 1 FROM ktv_rooms WHERE code=?", new String[]{code});
            boolean exists = c.moveToFirst();
            c.close();
            JSONObject o = new JSONObject();
            o.put("exists", exists);
            return jsonResponse(o, 200);
        }

        // ===== Room actions =====
        if (path.startsWith("/room/") && path.endsWith("/action") && session.getMethod() == Method.POST) {
            String code = path.substring("/room/".length(), path.length() - "/action".length()).toUpperCase();
            JSONObject action = readJsonBody(session);
            return handleRoomAction(db, code, action);
        }

        // ===== Admin =====
        if (path.startsWith("/admin")) {
            return adminRoute(db, session, path, authHeader);
        }

        return jsonError("Not found: " + path, 404);
    }

    private String firstParam(IHTTPSession session, String key, String def) {
        List<String> vals = session.getParameters().get(key);
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : def;
    }

    // ---------- config ----------

    private JSONObject getConfigRow(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT * FROM ktv_config WHERE id=1", null);
        try {
            if (!c.moveToFirst()) return null;
            JSONObject o = new JSONObject();
            for (String col : c.getColumnNames()) {
                int idx = c.getColumnIndex(col);
                switch (c.getType(idx)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        o.put(col, c.getInt(idx));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        o.put(col, c.getDouble(idx));
                        break;
                    default:
                        o.put(col, c.isNull(idx) ? "" : c.getString(idx));
                }
            }
            return o;
        } catch (Exception e) {
            return null;
        } finally {
            c.close();
        }
    }

    private JSONObject configToPublic(JSONObject c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("app_name", c.optString("app_name"));
        o.put("app_tagline", c.optString("app_tagline"));
        o.put("base_path", c.optString("base_path"));
        o.put("email_login_enabled", c.optInt("email_login_enabled") != 0);
        o.put("default_theme", c.optString("default_theme"));
        o.put("default_skin", c.optString("default_skin"));
        o.put("refresh_animation", c.optString("refresh_animation"));
        o.put("video_player", c.optString("video_player"));
        o.put("show_live_music_button", c.optInt("show_live_music_button") != 0);
        o.put("show_search_button", c.optInt("show_search_button") != 0);
        o.put("show_queue_button", c.optInt("show_queue_button") != 0);
        o.put("show_popular_button", c.optInt("show_popular_button") != 0);
        o.put("show_mysongs_button", c.optInt("show_mysongs_button") != 0);
        o.put("footer_credit", c.optString("footer_credit"));
        o.put("footer_support", c.optString("footer_support"));
        o.put("footer_link1_text", c.optString("footer_link1_text"));
        o.put("footer_link1_url", c.optString("footer_link1_url"));
        o.put("footer_link2_text", c.optString("footer_link2_text"));
        o.put("footer_link2_url", c.optString("footer_link2_url"));
        o.put("footer_link3_text", c.optString("footer_link3_text"));
        o.put("footer_link3_url", c.optString("footer_link3_url"));
        o.put("footer_qr", c.optString("footer_qr"));
        o.put("background_style", c.optString("background_style", "default"));
        o.put("landing_headline", c.optString("landing_headline"));
        o.put("landing_subtext", c.optString("landing_subtext"));

        JSONObject buttons = new JSONObject();
        buttons.put("live_music", c.optInt("show_live_music_button") != 0);
        buttons.put("search", c.optInt("show_search_button") != 0);
        buttons.put("queue", c.optInt("show_queue_button") != 0);
        buttons.put("popular", c.optInt("show_popular_button") != 0);
        buttons.put("my_songs", c.optInt("show_mysongs_button") != 0);
        o.put("buttons", buttons);

        JSONObject footer = new JSONObject();
        footer.put("credit", c.optString("footer_credit"));
        footer.put("support", c.optString("footer_support"));
        footer.put("link1_text", c.optString("footer_link1_text"));
        footer.put("link1_url", c.optString("footer_link1_url"));
        footer.put("link2_text", c.optString("footer_link2_text"));
        footer.put("link2_url", c.optString("footer_link2_url"));
        footer.put("link3_text", c.optString("footer_link3_text"));
        footer.put("link3_url", c.optString("footer_link3_url"));
        footer.put("qr", c.optString("footer_qr"));
        o.put("footer", footer);
        return o;
    }

    // ---------- room snapshot ----------

    private JSONObject buildSnapshot(SQLiteDatabase db, String code) throws Exception {
        Cursor rc = db.rawQuery("SELECT * FROM ktv_rooms WHERE code=?", new String[]{code});
        JSONObject room = null;
        if (rc.moveToFirst()) {
            room = new JSONObject();
            room.put("now_playing_youtube_id", Db.s(rc, "now_playing_youtube_id"));
            room.put("now_playing_title", Db.s(rc, "now_playing_title"));
            room.put("now_playing_requester", Db.s(rc, "now_playing_requester"));
            room.put("is_paused", Db.b(rc, "is_paused"));
            room.put("position_sec", Db.d(rc, "position_sec"));
            room.put("position_at", Db.s(rc, "position_at"));
        }
        rc.close();
        if (room == null) return null;

        JSONArray queue = new JSONArray();
        Cursor sc = db.rawQuery("SELECT youtube_id,title,requester,status FROM ktv_songs WHERE code=? ORDER BY added_at ASC", new String[]{code});
        while (sc.moveToNext()) {
            JSONObject s = new JSONObject();
            s.put("youtube_id", Db.s(sc, "youtube_id"));
            s.put("title", Db.s(sc, "title"));
            s.put("requester", Db.s(sc, "requester"));
            s.put("status", Db.s(sc, "status"));
            queue.put(s);
        }
        sc.close();

        long cutoffMillis = System.currentTimeMillis() - 60_000;
        int guestCount = 0, remoteCount = 0, hostCount = 0;
        Cursor pc = db.rawQuery("SELECT is_remote,is_host,last_seen FROM ktv_presence WHERE code=?", new String[]{code});
        while (pc.moveToNext()) {
            String lastSeen = Db.s(pc, "last_seen");
            if (lastSeen != null && isoToMillis(lastSeen) >= cutoffMillis) {
                guestCount++;
                if (Db.b(pc, "is_remote")) remoteCount++;
                if (Db.b(pc, "is_host")) hostCount++;
            }
        }
        pc.close();

        JSONArray activity = new JSONArray();
        Cursor ac = db.rawQuery("SELECT message,at FROM ktv_activity WHERE code=? ORDER BY at DESC LIMIT 20", new String[]{code});
        while (ac.moveToNext()) {
            JSONObject a = new JSONObject();
            a.put("message", Db.s(ac, "message"));
            a.put("at", Db.s(ac, "at"));
            activity.put(a);
        }
        ac.close();

        JSONObject snap = new JSONObject();
        snap.put("type", "ktv");
        snap.put("code", code);
        snap.put("queue", queue);
        Object npId = room.opt("now_playing_youtube_id");
        if (npId != null && !npId.equals(JSONObject.NULL) && !"".equals(npId)) {
            JSONObject np = new JSONObject();
            np.put("youtube_id", room.opt("now_playing_youtube_id"));
            np.put("title", room.opt("now_playing_title"));
            np.put("requester", room.opt("now_playing_requester"));
            snap.put("nowPlaying", np);
        } else {
            snap.put("nowPlaying", JSONObject.NULL);
        }
        snap.put("isPaused", room.optBoolean("is_paused"));
        snap.put("guestCount", guestCount);
        snap.put("remoteCount", remoteCount);
        snap.put("hostCount", hostCount);
        snap.put("activity", activity);
        return snap;
    }

    private long isoToMillis(String iso) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf.parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private void pushSnapshot(SQLiteDatabase db, String code) throws Exception {
        JSONObject snap = buildSnapshot(db, code);
        if (snap != null) broadcast(code, snap);
    }

    private void logActivity(SQLiteDatabase db, String code, String message) {
        db.execSQL("INSERT INTO ktv_activity (code, message, at) VALUES (?,?,?)", new Object[]{code, message, Db.nowIso()});
    }

    private Response handleRoomAction(SQLiteDatabase db, String code, JSONObject action) throws Exception {
        Cursor rc = db.rawQuery("SELECT * FROM ktv_rooms WHERE code=?", new String[]{code});
        boolean roomExists = rc.moveToFirst();
        boolean songLimitEnabled = roomExists && Db.b(rc, "song_limit_enabled");
        rc.close();
        if (!roomExists) return jsonError("Room not found", 404);

        String type = action.optString("type", "");
        switch (type) {
            case "join": {
                String deviceId = action.optString("requester", "anon");
                boolean isRemote = action.optBoolean("isRemote", false);
                String now = Db.nowIso();
                db.execSQL("INSERT INTO ktv_presence (code,device_id,is_remote,is_host,display_name,last_seen) VALUES (?,?,?,?,?,?) " +
                                "ON CONFLICT(code,device_id) DO UPDATE SET is_remote=excluded.is_remote,is_host=excluded.is_host,display_name=excluded.display_name,last_seen=excluded.last_seen",
                        new Object[]{code, deviceId, isRemote ? 1 : 0, isRemote ? 0 : 1, deviceId, now});
                pushSnapshot(db, code);
                break;
            }
            case "add_song": {
                JSONObject snap = buildSnapshot(db, code);
                String requester = action.optString("requester", "guest");
                if (songLimitEnabled && snap != null && snap.optInt("remoteCount") > 2 && !requester.equals("guest")) {
                    Cursor cc = db.rawQuery("SELECT 1 FROM ktv_songs WHERE code=? AND requester=? AND status IN ('queued','playing')", new String[]{code, requester});
                    int cnt = cc.getCount();
                    cc.close();
                    if (cnt >= 3) {
                        JSONObject rej = new JSONObject();
                        rej.put("type", "song_rejected");
                        rej.put("reason", "limit");
                        rej.put("max", 3);
                        broadcast(code, rej);
                        JSONObject o = new JSONObject();
                        o.put("ok", true);
                        o.put("rejected", true);
                        return jsonResponse(o, 200);
                    }
                }
                db.execSQL("INSERT INTO ktv_songs (code,youtube_id,title,requester,status,added_at) VALUES (?,?,?,?,'queued',?)",
                        new Object[]{code, action.optString("youtube_id"), action.optString("title"), requester, Db.nowIso()});
                logActivity(db, code, requester + " added \"" + action.optString("title") + "\"");

                // Auto-start playback if nothing is currently playing.
                Cursor npCheck = db.rawQuery("SELECT now_playing_youtube_id FROM ktv_rooms WHERE code=?", new String[]{code});
                boolean nothingPlaying = true;
                if (npCheck.moveToFirst()) {
                    nothingPlaying = Db.s(npCheck, "now_playing_youtube_id") == null;
                }
                npCheck.close();
                if (nothingPlaying) {
                    Cursor nextSong = db.rawQuery("SELECT youtube_id,title,requester FROM ktv_songs WHERE code=? AND status='queued' ORDER BY added_at ASC LIMIT 1", new String[]{code});
                    if (nextSong.moveToFirst()) {
                        String yid = Db.s(nextSong, "youtube_id");
                        String title = Db.s(nextSong, "title");
                        String req = Db.s(nextSong, "requester");
                        nextSong.close();
                        String now = Db.nowIso();
                        db.execSQL("UPDATE ktv_rooms SET now_playing_youtube_id=?,now_playing_title=?,now_playing_requester=?,is_paused=0,position_sec=0,position_at=? WHERE code=?",
                                new Object[]{yid, title, req, now, code});
                        db.execSQL("UPDATE ktv_songs SET status='playing' WHERE code=? AND youtube_id=? AND status='queued'", new Object[]{code, yid});
                        logActivity(db, code, "Now playing: " + title);
                    } else {
                        nextSong.close();
                    }
                }
                pushSnapshot(db, code);
                break;
            }
            case "next": {
                db.execSQL("UPDATE ktv_songs SET status='played' WHERE code=? AND status='playing'", new Object[]{code});
                Cursor nc = db.rawQuery("SELECT youtube_id,title,requester FROM ktv_songs WHERE code=? AND status='queued' ORDER BY added_at ASC LIMIT 1", new String[]{code});
                if (nc.moveToFirst()) {
                    String yid = Db.s(nc, "youtube_id");
                    String title = Db.s(nc, "title");
                    String requester = Db.s(nc, "requester");
                    nc.close();
                    String now = Db.nowIso();
                    db.execSQL("UPDATE ktv_rooms SET now_playing_youtube_id=?,now_playing_title=?,now_playing_requester=?,is_paused=0,position_sec=0,position_at=? WHERE code=?",
                            new Object[]{yid, title, requester, now, code});
                    db.execSQL("UPDATE ktv_songs SET status='playing' WHERE code=? AND youtube_id=? AND status='queued'", new Object[]{code, yid});
                    logActivity(db, code, "Now playing: " + title);
                } else {
                    nc.close();
                    db.execSQL("UPDATE ktv_rooms SET now_playing_youtube_id=NULL,now_playing_title=NULL,now_playing_requester=NULL,is_paused=0 WHERE code=?", new Object[]{code});
                }
                pushSnapshot(db, code);
                break;
            }
            case "play":
            case "pause": {
                boolean isPaused = type.equals("pause");
                db.execSQL("UPDATE ktv_rooms SET is_paused=?,position_at=? WHERE code=?", new Object[]{isPaused ? 1 : 0, Db.nowIso(), code});
                pushSnapshot(db, code);
                break;
            }
            case "stop": {
                db.execSQL("UPDATE ktv_rooms SET now_playing_youtube_id=NULL,now_playing_title=NULL,now_playing_requester=NULL,is_paused=0 WHERE code=?", new Object[]{code});
                pushSnapshot(db, code);
                break;
            }
            case "like": {
                JSONObject cheer = new JSONObject();
                cheer.put("type", "cheer");
                broadcast(code, cheer);
                break;
            }
            case "leave": {
                String deviceId = action.optString("requester", "anon");
                db.execSQL("DELETE FROM ktv_presence WHERE code=? AND device_id=?", new Object[]{code, deviceId});
                pushSnapshot(db, code);
                break;
            }
            case "sync_position": {
                if (action.has("position")) {
                    double position = action.optDouble("position", 0);
                    boolean isPaused = action.optBoolean("isPaused", false);
                    String now = Db.nowIso();
                    db.execSQL("UPDATE ktv_rooms SET position_sec=?,position_at=?,is_paused=? WHERE code=?",
                            new Object[]{position, now, isPaused ? 1 : 0, code});
                    Cursor r2 = db.rawQuery("SELECT now_playing_youtube_id,now_playing_title,now_playing_requester,is_paused,position_sec,position_at FROM ktv_rooms WHERE code=?", new String[]{code});
                    if (r2.moveToFirst()) {
                        JSONObject sync = new JSONObject();
                        sync.put("type", "ktv_sync");
                        sync.put("isPaused", Db.b(r2, "is_paused"));
                        String npId = Db.s(r2, "now_playing_youtube_id");
                        if (npId != null) {
                            JSONObject np = new JSONObject();
                            np.put("youtube_id", npId);
                            np.put("title", Db.s(r2, "now_playing_title"));
                            np.put("requester", Db.s(r2, "now_playing_requester"));
                            sync.put("nowPlaying", np);
                        } else {
                            sync.put("nowPlaying", JSONObject.NULL);
                        }
                        JSONObject playback = new JSONObject();
                        playback.put("position", Db.d(r2, "position_sec"));
                        String posAt = Db.s(r2, "position_at");
                        playback.put("serverTime", posAt != null ? isoToMillis(posAt) : System.currentTimeMillis());
                        sync.put("playback", playback);
                        broadcast(code, sync);
                    }
                    r2.close();
                }
                break;
            }
            default:
                return jsonError("Unknown action: " + type, 400);
        }
        JSONObject ok = new JSONObject();
        ok.put("ok", true);
        return jsonResponse(ok, 200);
    }

    // ---------- admin ----------

    private Response adminRoute(SQLiteDatabase db, IHTTPSession session, String path, String authHeader) throws Exception {
        JSONObject body = session.getMethod() == Method.POST ? readJsonBody(session) : new JSONObject();

        if (path.equals("/admin/login") && session.getMethod() == Method.POST) {
            String pw = body.optString("password", "");
            JSONObject c = getConfigRow(db);
            String hash = Db.sha256(pw);
            String existingHash = c.optString("admin_password_hash", "");
            if (existingHash.isEmpty()) {
                db.execSQL("UPDATE ktv_config SET admin_password_hash=? WHERE id=1", new Object[]{hash});
                JSONObject o = new JSONObject();
                o.put("token", hash);
                return jsonResponse(o, 200);
            }
            if (!hash.equals(existingHash)) return jsonError("Invalid admin password", 401);
            JSONObject o = new JSONObject();
            o.put("token", hash);
            return jsonResponse(o, 200);
        }

        if (!verifyAdmin(db, authHeader)) return jsonError("Unauthorized", 401);

        if (path.equals("/admin/config") && session.getMethod() == Method.GET) {
            JSONObject c = getConfigRow(db);
            JSONObject pub = configToPublic(c);
            pub.put("base_path", c.optString("base_path"));
            pub.put("admin_password", "");
            return jsonResponse(pub, 200);
        }

        if (path.equals("/admin/config") && session.getMethod() == Method.POST) {
            String[] allowed = {"app_name", "app_tagline", "base_path", "default_theme", "default_skin",
                    "refresh_animation", "video_player", "footer_credit", "footer_support",
                    "footer_link1_text", "footer_link1_url", "footer_link2_text", "footer_link2_url",
                    "footer_link3_text", "footer_link3_url", "background_style", "landing_headline",
                    "landing_subtext"};
            String[] boolKeys = {"show_live_music_button", "show_search_button", "show_queue_button",
                    "show_popular_button", "show_mysongs_button", "email_login_enabled"};
            StringBuilder sql = new StringBuilder("UPDATE ktv_config SET updated_at=?");
            java.util.List<Object> vals = new java.util.ArrayList<>();
            vals.add(Db.nowIso());
            for (String k : allowed) {
                if (body.has(k)) { sql.append(", ").append(k).append("=?"); vals.add(body.optString(k)); }
            }
            for (String k : boolKeys) {
                if (body.has(k)) { sql.append(", ").append(k).append("=?"); vals.add(body.optBoolean(k) ? 1 : 0); }
            }
            if (body.has("admin_password") && !body.optString("admin_password").isEmpty()) {
                sql.append(", admin_password_hash=?");
                vals.add(Db.sha256(body.optString("admin_password")));
            }
            sql.append(" WHERE id=1");
            db.execSQL(sql.toString(), vals.toArray());
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return jsonResponse(o, 200);
        }

        if (path.equals("/admin/upload-qr") && session.getMethod() == Method.POST) {
            db.execSQL("UPDATE ktv_config SET footer_qr=?, updated_at=? WHERE id=1",
                    new Object[]{body.optString("image", ""), Db.nowIso()});
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return jsonResponse(o, 200);
        }

        if (path.equals("/admin/logins") && session.getMethod() == Method.GET) {
            JSONArray out = new JSONArray();
            Cursor c = db.rawQuery("SELECT email,ip,login_at FROM ktv_logins ORDER BY login_at DESC LIMIT 100", null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("email", Db.s(c, "email"));
                o.put("ip", Db.s(c, "ip"));
                o.put("login_at", Db.s(c, "login_at"));
                out.put(o);
            }
            c.close();
            return jsonResponse(out, 200);
        }

        if (path.equals("/admin/users") && session.getMethod() == Method.GET) {
            JSONArray out = new JSONArray();
            Cursor c = db.rawQuery("SELECT email,display_name,banned,last_seen FROM ktv_users ORDER BY last_seen DESC LIMIT 100", null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("email", Db.s(c, "email"));
                o.put("display_name", Db.s(c, "display_name"));
                o.put("banned", Db.b(c, "banned"));
                o.put("last_seen", Db.s(c, "last_seen"));
                out.put(o);
            }
            c.close();
            return jsonResponse(out, 200);
        }

        if (path.equals("/admin/rooms") && session.getMethod() == Method.GET) {
            JSONArray out = new JSONArray();
            Cursor c = db.rawQuery("SELECT * FROM ktv_rooms ORDER BY created_at DESC LIMIT 50", null);
            while (c.moveToNext()) {
                String code = Db.s(c, "code");
                JSONObject snap = buildSnapshot(db, code);
                Cursor qc = db.rawQuery("SELECT 1 FROM ktv_songs WHERE code=? AND status='queued'", new String[]{code});
                int queueLen = qc.getCount();
                qc.close();
                JSONObject o = new JSONObject();
                o.put("code", code);
                o.put("guestCount", snap != null ? snap.optInt("guestCount") : 0);
                o.put("hostCount", snap != null ? snap.optInt("hostCount") : 0);
                o.put("remoteCount", snap != null ? snap.optInt("remoteCount") : 0);
                o.put("queueLength", queueLen);
                o.put("nowPlaying", Db.s(c, "now_playing_title") == null ? "" : Db.s(c, "now_playing_title"));
                o.put("songLimitEnabled", Db.b(c, "song_limit_enabled"));
                out.put(o);
            }
            c.close();
            return jsonResponse(out, 200);
        }

        if (path.equals("/admin/room/limit") && session.getMethod() == Method.POST) {
            String code = body.optString("code", "").toUpperCase();
            boolean enabled = body.optBoolean("enabled", false);
            db.execSQL("UPDATE ktv_rooms SET song_limit_enabled=? WHERE code=?", new Object[]{enabled ? 1 : 0, code});
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return jsonResponse(o, 200);
        }

        return jsonError("Unknown admin route", 404);
    }

    private boolean verifyAdmin(SQLiteDatabase db, String authHeader) {
        if (authHeader == null) return false;
        String token = authHeader.replaceFirst("(?i)^Bearer\\s+", "");
        JSONObject c = getConfigRow(db);
        if (c == null) return false;
        String hash = c.optString("admin_password_hash", "");
        return !hash.isEmpty() && token.equals(hash);
    }
}
