package com.ouktv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ported from server.ts's ytInnertubeSearch(). Still requires internet —
 * songs are fetched live from YouTube, the same as the original hosted
 * version. Everything else (rooms, queue, playback state) works purely
 * over the local network with no internet needed.
 */
public class YoutubeSearch {

    private static final String KEY = "AIzaSyAO_FJ2Uqky0-A8a9X5d2q3QpF2Z1Y6Z2Y";

    // Priority channels searched first for the general genre buttons
    // (All, Duet, Rock, K-Pop, R&B).
    private static final String[] GENERAL_PRIORITY_CHANNELS = {
            "Atomic Karaoke", "Covers PH", "Pro Music Cover", "Karaoke All Stars",
            "Global Karaoke TV", "KaraokeyTV", "Top Hits Karaoke"
    };

    // Priority channels for the Medley genre button.
    private static final String[] MEDLEY_PRIORITY_CHANNELS = {
            "Sing Along", "Ibara Music", "AP Music", "Cones Studio Karaoke",
            "Zoom Karaoke", "Lariel Station"
    };

    // Priority channels for the OPM genre button.
    private static final String[] OPM_PRIORITY_CHANNELS = {
            "Sing Star Karaoke", "AJ Karaoke Cover", "Atomic Karaoke",
            "Pro Music Cover", "Karaoke All-Star", "Top Hits Karaoke"
    };

    private static class CacheEntry {
        long ts;
        JSONArray data;
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    public static JSONArray search(String query, String type) {
        return search(query, type, null);
    }

    public static JSONArray search(String query, String type, String genre) {
        String suffix = "live".equals(type) ? " live" : " karaoke";
        String fullQuery = query + suffix;

        String[] priorityChannels = pickPriorityChannels(genre);

        List<JSONObject> merged = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();

        // Fire all priority-channel searches AND the general fallback search
        // at the same time instead of one-by-one — this is the difference
        // between ~1-2s total and 10-20s total for a genre search.
        List<Future<JSONArray>> futures = new ArrayList<>();
        if (priorityChannels != null) {
            List<String> shuffled = new ArrayList<>(Arrays.asList(priorityChannels));
            Collections.shuffle(shuffled);
            for (String ch : shuffled) {
                final String q = query + " " + ch + suffix;
                futures.add(POOL.submit((Callable<JSONArray>) () -> innertubeSearch(q, 3)));
            }
        }
        Future<JSONArray> generalFuture = POOL.submit((Callable<JSONArray>) () -> innertubeSearch(fullQuery, 20));

        for (Future<JSONArray> f : futures) {
            if (merged.size() >= 20) break;
            try {
                JSONArray chResults = f.get(6, TimeUnit.SECONDS);
                for (int i = 0; i < chResults.length() && merged.size() < 20; i++) {
                    JSONObject r = chResults.optJSONObject(i);
                    if (r == null) continue;
                    String id = r.optString("id", "");
                    if (id.isEmpty() || seenIds.contains(id)) continue;
                    seenIds.add(id);
                    merged.add(r);
                }
            } catch (Exception ignored) {}
        }

        if (merged.size() < 20) {
            try {
                JSONArray general = generalFuture.get(6, TimeUnit.SECONDS);
                for (int i = 0; i < general.length() && merged.size() < 20; i++) {
                    JSONObject r = general.optJSONObject(i);
                    if (r == null) continue;
                    String id = r.optString("id", "");
                    if (id.isEmpty() || seenIds.contains(id)) continue;
                    seenIds.add(id);
                    merged.add(r);
                }
            } catch (Exception ignored) {}
        } else {
            generalFuture.cancel(true);
        }

        JSONArray out = new JSONArray();
        for (JSONObject r : merged) out.put(r);
        return out;
    }

    private static String[] pickPriorityChannels(String genre) {
        if (genre == null || genre.isEmpty()) return GENERAL_PRIORITY_CHANNELS;
        switch (genre.toLowerCase()) {
            case "medley": return MEDLEY_PRIORITY_CHANNELS;
            case "opm": return OPM_PRIORITY_CHANNELS;
            default: return GENERAL_PRIORITY_CHANNELS;
        }
    }

    public static JSONArray popular() {
        String[][] popular = {
                {"8dJyRw2u0Zw", "Bohemian Rhapsody — Queen (Karaoke)"},
                {"kJQP7kiw5Fk", "Despacito — Luis Fonsi (Karaoke)"},
                {"9bZkp7q19f0", "Gangnam Style — PSY (Karaoke)"},
                {"AdNkIo2GYU0", "Don't Stop Believin' — Journey (Karaoke)"},
                {"6ZrO78em0xw", "My Way — Frank Sinatra (Karaoke)"},
                {"h5z5T2_2NxA", "Wonderwall — Oasis (Karaoke)"},
                {"gO59j4QHA1c", "Mr Brightside — The Killers (Karaoke)"},
                {"YQHsXMglC9A", "Hello — Adele (Karaoke)"},
                {"FIGmdcD7u1Y", "Dancing Queen — ABBA (Karaoke)"},
                {"JGwWNGJdvx8", "Shape of You — Ed Sheeran (Karaoke)"},
        };
        JSONArray out = new JSONArray();
        for (String[] p : popular) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", p[0]);
                o.put("title", p[1]);
                o.put("thumb", "https://i.ytimg.com/vi/" + p[0] + "/default.jpg");
            } catch (Exception ignored) {}
            out.put(o);
        }
        return out;
    }

    private static JSONArray innertubeSearch(String query, int limit) {
        String cacheKey = query.toLowerCase();
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.ts < CACHE_TTL_MS) {
            return cached.data;
        }
        try {
            URL url = new URL("https://www.youtube.com/youtubei/v1/search?key=" + KEY);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            JSONObject ctx = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20240501.00.00");
            ctx.put("client", client);
            JSONObject reqBody = new JSONObject();
            reqBody.put("context", ctx);
            reqBody.put("query", query);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(reqBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONObject data = new JSONObject(sb.toString());
            List<JSONObject> results = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            walk(data.opt("contents"), results, seen, limit);

            JSONArray out = new JSONArray();
            for (JSONObject r : results) out.put(r);

            CacheEntry entry = new CacheEntry();
            entry.ts = System.currentTimeMillis();
            entry.data = out;
            CACHE.put(cacheKey, entry);
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void walk(Object node, List<JSONObject> results, LinkedHashSet<String> seen, int limit) {
        if (results.size() >= limit || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && results.size() < limit; i++) {
                walk(arr.opt(i), results, seen, limit);
            }
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("videoRenderer")) {
                JSONObject vr = obj.optJSONObject("videoRenderer");
                if (vr != null) {
                    String vid = vr.optString("videoId", null);
                    String title = "Untitled";
                    JSONObject titleObj = vr.optJSONObject("title");
                    if (titleObj != null) {
                        JSONArray runs = titleObj.optJSONArray("runs");
                        if (runs != null) {
                            StringBuilder t = new StringBuilder();
                            for (int i = 0; i < runs.length(); i++) {
                                t.append(runs.optJSONObject(i).optString("text", ""));
                            }
                            if (t.length() > 0) title = t.toString();
                        }
                    }
                    if (vid != null && !seen.contains(vid)) {
                        seen.add(vid);
                        JSONObject r = new JSONObject();
                        try {
                            r.put("id", vid);
                            r.put("title", title);
                            r.put("thumb", "https://i.ytimg.com/vi/" + vid + "/default.jpg");
                        } catch (Exception ignored) {}
                        results.add(r);
                    }
                }
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext() && results.size() < limit) {
                walk(obj.opt(keys.next()), results, seen, limit);
            }
        }
    }
}
