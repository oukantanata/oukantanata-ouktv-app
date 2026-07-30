package com.ouktv.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Local on-device database — replaces the original PostgreSQL backend.
 * Table shapes mirror schema.sql / server.ts from the original project so
 * the same room/song/presence/config logic applies, just running locally.
 */
public class Db extends SQLiteOpenHelper {

    private static final String DB_NAME = "ktv.db";
    private static final int DB_VERSION = 2;

    public Db(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ktv_config (" +
                "id INTEGER PRIMARY KEY CHECK (id=1)," +
                "app_name TEXT NOT NULL DEFAULT 'O|U KTV'," +
                "app_tagline TEXT NOT NULL DEFAULT 'Karaoke Together'," +
                "base_path TEXT NOT NULL DEFAULT 'ktv'," +
                "email_login_enabled INTEGER NOT NULL DEFAULT 1," +
                "default_theme TEXT NOT NULL DEFAULT 'neonpop'," +
                "default_skin TEXT NOT NULL DEFAULT 'default'," +
                "refresh_animation TEXT NOT NULL DEFAULT 'fall'," +
                "video_player TEXT NOT NULL DEFAULT 'youtube'," +
                "show_live_music_button INTEGER NOT NULL DEFAULT 1," +
                "show_search_button INTEGER NOT NULL DEFAULT 1," +
                "show_queue_button INTEGER NOT NULL DEFAULT 1," +
                "show_popular_button INTEGER NOT NULL DEFAULT 1," +
                "show_mysongs_button INTEGER NOT NULL DEFAULT 1," +
                "footer_credit TEXT NOT NULL DEFAULT ''," +
                "footer_support TEXT NOT NULL DEFAULT ''," +
                "footer_link1_text TEXT NOT NULL DEFAULT ''," +
                "footer_link1_url TEXT NOT NULL DEFAULT ''," +
                "footer_link2_text TEXT NOT NULL DEFAULT ''," +
                "footer_link2_url TEXT NOT NULL DEFAULT ''," +
                "footer_link3_text TEXT NOT NULL DEFAULT ''," +
                "footer_link3_url TEXT NOT NULL DEFAULT ''," +
                "footer_qr TEXT NOT NULL DEFAULT ''," +
                "background_style TEXT NOT NULL DEFAULT 'default'," +
                "landing_headline TEXT NOT NULL DEFAULT ''," +
                "landing_subtext TEXT NOT NULL DEFAULT ''," +
                "admin_password_hash TEXT NOT NULL DEFAULT ''," +
                "updated_at TEXT NOT NULL)");

        db.execSQL("CREATE TABLE ktv_rooms (" +
                "code TEXT PRIMARY KEY," +
                "created_at TEXT NOT NULL," +
                "now_playing_youtube_id TEXT," +
                "now_playing_title TEXT," +
                "now_playing_requester TEXT," +
                "is_paused INTEGER NOT NULL DEFAULT 0," +
                "position_sec REAL NOT NULL DEFAULT 0," +
                "position_at TEXT," +
                "song_limit_enabled INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE ktv_songs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "code TEXT NOT NULL," +
                "youtube_id TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "requester TEXT NOT NULL DEFAULT 'guest'," +
                "status TEXT NOT NULL DEFAULT 'queued'," +
                "added_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX ktv_songs_code_idx ON ktv_songs(code, added_at)");

        db.execSQL("CREATE TABLE ktv_presence (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "code TEXT NOT NULL," +
                "device_id TEXT NOT NULL," +
                "is_remote INTEGER NOT NULL DEFAULT 1," +
                "is_host INTEGER NOT NULL DEFAULT 0," +
                "display_name TEXT NOT NULL DEFAULT 'guest'," +
                "last_seen TEXT NOT NULL," +
                "UNIQUE(code, device_id))");
        db.execSQL("CREATE INDEX ktv_presence_code_idx ON ktv_presence(code)");

        db.execSQL("CREATE TABLE ktv_activity (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "code TEXT NOT NULL," +
                "message TEXT NOT NULL," +
                "at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX ktv_activity_code_idx ON ktv_activity(code, at)");

        db.execSQL("CREATE TABLE ktv_users (" +
                "email TEXT PRIMARY KEY," +
                "display_name TEXT NOT NULL DEFAULT ''," +
                "banned INTEGER NOT NULL DEFAULT 0," +
                "last_seen TEXT NOT NULL," +
                "created_at TEXT NOT NULL)");

        db.execSQL("CREATE TABLE ktv_logins (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "email TEXT NOT NULL," +
                "ip TEXT NOT NULL DEFAULT ''," +
                "login_at TEXT NOT NULL)");

        ContentValues cv = new ContentValues();
        cv.put("id", 1);
        cv.put("updated_at", nowIso());
        db.insert("ktv_config", null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE ktv_config ADD COLUMN background_style TEXT NOT NULL DEFAULT 'default'");
            db.execSQL("ALTER TABLE ktv_config ADD COLUMN landing_headline TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE ktv_config ADD COLUMN landing_subtext TEXT NOT NULL DEFAULT ''");
        }
    }

    // ---------- helpers ----------

    public static String nowIso() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date());
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String genCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** Reads a full row of a Cursor's current position into a simple map-like object via caller. */
    public interface RowMapper<T> {
        T map(Cursor c);
    }

    public <T> List<T> query(SQLiteDatabase db, String sql, String[] args, RowMapper<T> mapper) {
        List<T> out = new ArrayList<>();
        Cursor c = db.rawQuery(sql, args);
        try {
            while (c.moveToNext()) out.add(mapper.map(c));
        } finally {
            c.close();
        }
        return out;
    }

    public <T> T queryOne(SQLiteDatabase db, String sql, String[] args, RowMapper<T> mapper) {
        Cursor c = db.rawQuery(sql, args);
        try {
            if (c.moveToFirst()) return mapper.map(c);
            return null;
        } finally {
            c.close();
        }
    }

    static String s(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    static int i(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx < 0 || c.isNull(idx) ? 0 : c.getInt(idx);
    }

    static double d(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx < 0 || c.isNull(idx) ? 0 : c.getDouble(idx);
    }

    static boolean b(Cursor c, String col) {
        return i(c, col) != 0;
    }
}
