package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Continue watching" cards' pictures, decoded in the background once, at the card's size on screen (16:9,
 * centre-cropped), and kept in memory. Drawing only reuses them. Only pictures already on the TV are read (content:// and
 * android.resource://, from the apps themselves): the app never downloads an address another app wrote (http, https),
 * since it only talks to TMDB, Open-Meteo and Binance, and never opens files (file://). Anything else, or a picture that
 * can't be read, falls back to a picture from a trusted source only (see get(Item)): TMDB, looked up by title with the
 * user's own TMDB key, or YouTube's own public thumbnail for a video id parsed from the item's intent. When neither
 * applies, or the fallback fails, the app's TV banner shows instead, and isn't tried again for a while.
 */
final class WatchArt {
    /** The card in the 1920×1080 layout; the pictures are made at this times the UI's scale (setScale). */
    static final int W = 520, H = 292;
    private static final int MAX_BYTES = 8 << 20;
    /** At most this many pixels decoded (~8 MB): a 60000×300 picture is sampled down before it's decoded. */
    private static final long MAX_PIXELS = 2_000_000;

    private final Context app;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** One thread for the whole process: a home screen started over (new settings) doesn't leave one behind. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile int pw = W, ph = H;   // the pictures' size in pixels
    private static final long RETRY_MS = 10 * 60_000L;
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failed = new HashMap<>();
    /** Fallback image URL already resolved per item (title + season/episode, or YouTube id): a re-draw after the
     * bitmap was evicted re-downloads the picture, it does not search TMDB again. */
    private final Map<String, String> resolved = new HashMap<>();
    private final Map<String, Integer> colors = new HashMap<>();   // each picture's colour, for the background
    private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();   // for the rounded corners, made with the picture
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= 16) return false;   // ~600 KB each at 1080p: the row and a little more
            shaders.remove(eldest.getValue());
            return true;
        }
    };

    WatchArt(Context c, Runnable onLoaded) {
        app = c.getApplicationContext();
        this.onLoaded = onLoaded;
    }

    /**
     * The card's picture, or the app's banner if it has none or it failed; null while loading (in the background;
     * onLoaded runs on the main thread when it's in).
     */
    Bitmap get(Uri uri, String key, String bannerKey, String pkg) {
        if (uri != null) {
            Bitmap b = fetch(key, uri, pkg);
            if (b != null || !failedRecently(key)) return b;
        }
        return fetch(bannerKey, null, pkg);
    }

    /**
     * The card's picture for a whole Watch Next item, in this order, and nothing else changes: the item's own
     * thumbnail first, then its poster (both already handled by Item: thumbnail wins, and only content:// /
     * android.resource:// reach here), then the trusted fallback below, then the app's banner. While the item's own
     * picture is still loading, null (the grey placeholder) — the fallback is not started until that picture is
     * known to be missing or unreadable, so a card never flashes banner-then-picture. While the fallback is loading,
     * the banner shows (it is replaced by the fallback picture when it arrives, via onLoaded).
     */
    Bitmap get(WatchNext.Item it) {
        if (it.pictureUri != null) {
            Bitmap b = fetch(it.pictureKey, it.pictureUri, it.pkg);
            if (b != null) return b;
            if (!failedRecently(it.pictureKey)) return null;   // the item's own picture is still loading
        }
        // No usable picture of its own (null, an http URL Item dropped, or one that failed to read): fallback.
        String fbKey = fallbackKey(it);
        if (fbKey != null) {
            Bitmap fb = fallback(fbKey, it);
            if (fb != null) return fb;
        }
        return fetch(it.bannerKey, null, it.pkg);
    }

    /** The colour of a whole item's card picture (its fallback picture's, when that is what shows). */
    Integer color(WatchNext.Item it) {
        String fbKey = fallbackKey(it);
        if (fbKey != null) {
            synchronized (cache) {
                Integer c = colors.get(fbKey);
                if (c != null) return c;
            }
        }
        return color(it.pictureKey, it.bannerKey);
    }

    /** The fallback's cache key for an item, or null when no fallback applies to it. */
    private static String fallbackKey(WatchNext.Item it) {
        if (it == null || it.title == null || it.title.trim().isEmpty()) return null;
        if (isYouTube(it.pkg)) {
            String id = parseYouTubeId(it.intent);
            return id == null ? null : "yt:" + id;
        }
        return "tmdb:" + it.title.trim().toLowerCase(java.util.Locale.ROOT)
                + "|" + (it.seasonNum == null ? "" : it.seasonNum)
                + "|" + (it.episodeNum == null ? "" : it.episodeNum);
    }

    /** The item's fallback picture, or null while it loads / when it failed (then the caller shows the banner). */
    private Bitmap fallback(String fbKey, WatchNext.Item it) {
        synchronized (cache) {
            Bitmap b = cache.get(fbKey);
            if (b != null || pending.contains(fbKey)) return b;
            Long at = failed.get(fbKey);
            if (at != null && System.currentTimeMillis() - at < RETRY_MS) return null;
            pending.add(fbKey);
        }
        IO.execute(() -> {
            if (closed) return;
            Bitmap b = null;
            try {
                String url = resolveFallbackUrl(fbKey, it);
                if (url != null) b = loadBytes(download(url));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("continue watching fallback: " + e.getClass().getSimpleName());
            }
            Integer color = b == null ? null : colorOf(b);
            BitmapShader shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            synchronized (cache) {
                pending.remove(fbKey);
                if (closed) return;
                if (b != null) {
                    shaders.put(b, shader);
                    cache.put(fbKey, b);
                    colors.put(fbKey, color);
                } else {
                    failed.put(fbKey, System.currentTimeMillis());
                }
            }
            main.post(() -> { if (!closed) onLoaded.run(); });
        });
        return null;
    }

    /**
     * The trusted picture URL for a fallback item. YouTube: the video's own public thumbnail, from the id in the
     * intent — no key, no search. Anything else: TMDB, with the user's own key (Prefs.tmdbKey wins over the built-in
     * one, read through Recs.key()); with no key the TMDB fallback is silently off (null here, so the banner shows)
     * and nothing is searched. Never logs the key or a URL containing it.
     */
    private String resolveFallbackUrl(String fbKey, WatchNext.Item it) throws IOException {
        if (fbKey.startsWith("yt:")) {
            return "https://i.ytimg.com/vi/" + fbKey.substring(3) + "/hqdefault.jpg";
        }
        synchronized (cache) {
            String known = resolved.get(fbKey);
            if (known != null) return known;
        }
        String key;
        try {
            key = Recs.key();
        } catch (RuntimeException e) {
            return null;
        }
        if (key == null || key.isEmpty()) return null;   // no TMDB key yet: fallback stays off, banner remains
        String url = tmdbImage(it, key);
        if (url != null) {
            synchronized (cache) {
                resolved.put(fbKey, url);
            }
        }
        return url;
    }

    /** A YouTube package: the TV app, Fire TV's, and any other package with "youtube" in its name (Music, Kids). */
    private static boolean isYouTube(String pkg) {
        return pkg != null && (pkg.equals("com.google.android.youtube.tv")
                || pkg.equals("com.amazon.firetv.youtube") || pkg.contains("youtube"));
    }

    /**
     * A YouTube video id from an intent URI, or null. Forms seen in intents: watch?v= (including
     * youtube.com/tv#/watch?v=), youtu.be/, /shorts/, /embed/, /live/, /v/, vnd.youtube:. The id is exactly 11
     * characters of [A-Za-z0-9_-] and must not be the prefix of a longer token.
     */
    static String parseYouTubeId(String intent) {
        if (intent == null) return null;
        String[] markers = {"v=", "youtu.be/", "/shorts/", "/embed/", "/live/", "/v/", "vnd.youtube:"};
        for (String m : markers) {
            int i = intent.indexOf(m);
            while (i >= 0) {
                int s = i + m.length();
                if (s + 11 <= intent.length()) {
                    String id = intent.substring(s, s + 11);
                    boolean ok = true;
                    for (int j = 0; j < 11; j++) {
                        char ch = id.charAt(j);
                        if (!(ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z'
                                || ch >= '0' && ch <= '9' || ch == '-' || ch == '_')) { ok = false; break; }
                    }
                    if (ok && (s + 11 == intent.length() || !isIdChar(intent.charAt(s + 11)))) return id;
                }
                i = intent.indexOf(m, i + 1);
            }
        }
        return null;
    }

    private static boolean isIdChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_';
    }

    // MARK: - TMDB fallback (search by title; backdrop preferred for the 16:9 card, poster as fallback)

    private static final String TMDB_API = "https://api.tmdb.org/3";
    private static final String TMDB_IMG = "https://image.tmdb.org/t/p/";

    /** The 16:9 image for a title: an episode still when season/episode are known, else the show/film backdrop, else its poster. Null when TMDB has no match. */
    private String tmdbImage(WatchNext.Item it, String key) throws IOException {
        org.json.JSONObject tv = null, movie = null;
        try {
            tv = tmdbSearch("/search/tv", it.title, key);
            movie = tmdbSearch("/search/movie", it.title, key);
        } catch (org.json.JSONException e) {
            throw new IOException("TMDB search unreadable");
        }
        int tvScore = score(tv, it.title), movieScore = score(movie, it.title);
        boolean hasEpisode = it.seasonNum != null && !it.seasonNum.isEmpty()
                && it.episodeNum != null && !it.episodeNum.isEmpty();
        // An episode number means a show, not a film; otherwise the better title match wins, films first on a tie.
        boolean useTv = hasEpisode ? (tv != null && tvScore > 0 || movie == null)
                : (tv != null && tvScore > movieScore);
        if (useTv && tv != null) {
            if (hasEpisode) {
                try {
                    String still = tmdbEpisodeStill(tv.optInt("id"), it.seasonNum, it.episodeNum, key);
                    if (still != null) return TMDB_IMG + "w780" + still;
                } catch (org.json.JSONException e) { /* fall through to the show's backdrop */ }
            }
            String path = firstNonEmpty(tv.optString("backdrop_path", ""), tv.optString("poster_path", ""));
            if (!path.isEmpty()) return TMDB_IMG + (path.equals(tv.optString("backdrop_path", "")) ? "w780" : "w500") + path;
        }
        if (movie != null) {
            String path = firstNonEmpty(movie.optString("backdrop_path", ""), movie.optString("poster_path", ""));
            if (!path.isEmpty()) return TMDB_IMG + (path.equals(movie.optString("backdrop_path", "")) ? "w780" : "w500") + path;
        }
        return null;
    }

    /** The best-matching search result for a title (first on a tie), or null when nothing matches at all. */
    private org.json.JSONObject tmdbSearch(String path, String title, String key) throws IOException, org.json.JSONException {
        String q = URLEncoder.encode(title, "UTF-8");
        org.json.JSONObject res = tmdbGet(path + "?query=" + q + "&include_adult=false", key);
        org.json.JSONArray results = res.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        org.json.JSONObject best = null;
        int bestScore = 0;
        for (int i = 0; i < results.length(); i++) {
            org.json.JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            int s = score(r, title);
            if (s > bestScore) { bestScore = s; best = r; }
        }
        return best;
    }

    /** An episode's still (16:9), or null. */
    private String tmdbEpisodeStill(int showId, String season, String episode, String key) throws IOException, org.json.JSONException {
        org.json.JSONObject ep = tmdbGet("/tv/" + showId + "/season/" + season + "/episode/" + episode + "/images", key);
        org.json.JSONArray stills = ep.optJSONArray("stills");
        if (stills == null || stills.length() == 0) return null;
        String path = stills.optJSONObject(0).optString("file_path", "");
        return path.isEmpty() ? null : path;
    }

    /** How well a TMDB result's title matches the Watch Next title: exact 100, normalised-equal 90, prefix 50, contains 30, else 0. */
    private static int score(org.json.JSONObject r, String title) {
        if (r == null) return 0;
        String name = r.optString("name", "");
        if (name.isEmpty()) name = r.optString("title", "");
        if (name.isEmpty()) return 0;
        if (name.equals(title)) return 100;
        String a = normalise(name), b = normalise(title);
        if (a.equals(b)) return 90;
        if (a.startsWith(b) || b.startsWith(a)) return 50;
        if (a.contains(b) || b.contains(a)) return 30;
        return 0;
    }

    private static String normalise(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    /** One TMDB request, in Recs' own key style (Bearer for a read-access token, api_key for a v3 key). Never logs the key. */
    private org.json.JSONObject tmdbGet(String path, String key) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");
        String url = TMDB_API + path + (bearer ? "" : (path.contains("?") ? "&" : "?")
                + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new org.json.JSONObject(new String(readAll(c.getInputStream()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** A trusted picture downloaded over https: TMDB's own image host, or YouTube's thumbnail host, and nothing else. */
    private byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "image/*");
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new IOException("picture download HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** The UI's scale (width / 1920): pictures already made at another size are made again. */
    void setScale(float k) {
        int width = Math.max(1, Math.round(W * k)), height = Math.max(1, Math.round(H * k));
        if (width == pw && height == ph) return;
        pw = width;
        ph = height;
        synchronized (cache) {
            cache.clear();
            shaders.clear();
        }
    }

    /** The picture's shader (get's pictures only). */
    BitmapShader shader(Bitmap b) {
        synchronized (cache) {
            return shaders.get(b);
        }
    }

    /** The colour of the card's picture (or of the banner in its place), or null until it's decoded. */
    Integer color(String key, String bannerKey) {
        synchronized (cache) {
            if (key != null) {
                Integer c = colors.get(key);
                Long at = failed.get(key);
                if (c != null || at == null || System.currentTimeMillis() - at >= RETRY_MS) return c;
            }
            return colors.get(bannerKey);
        }
    }

    /** The home screen went away: nothing more is posted to it, and the pictures go. */
    void close() {
        closed = true;
        synchronized (cache) {
            cache.clear();
            shaders.clear();
            colors.clear();
        }
    }

    private boolean failedRecently(String uri) {
        synchronized (cache) {
            Long at = failed.get(uri);
            return at != null && System.currentTimeMillis() - at < RETRY_MS;
        }
    }

    private Bitmap fetch(String key, Uri uri, String pkg) {
        if (closed) return null;
        synchronized (cache) {
            Bitmap b = cache.get(key);
            if (b != null || pending.contains(key)) return b;
            Long at = failed.get(key);
            if (at != null && System.currentTimeMillis() - at < RETRY_MS) return null;
            pending.add(key);
        }
        IO.execute(() -> {
            if (closed) return;
            Bitmap b = null;
            try {
                b = uri == null ? banner(pkg) : load(uri);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("continue watching picture: " + e.getMessage());
            }
            Integer color = b == null ? null : colorOf(b);
            BitmapShader shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            synchronized (cache) {
                pending.remove(key);
                if (closed) return;
                if (b != null) {
                    shaders.put(b, shader);
                    cache.put(key, b);
                    colors.put(key, color);
                } else {
                    failed.put(key, System.currentTimeMillis());
                }
            }
            main.post(() -> { if (!closed) onLoaded.run(); });   // the picture, or the banner in its place
        });
        return null;
    }

    private Bitmap load(Uri uri) throws IOException {
        return loadBytes(read(uri));
    }

    /** The same decode as load(Uri), for bytes already downloaded from a trusted host (TMDB / YouTube). */
    private Bitmap loadBytes(byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable picture");
        int cw = pw, ch = ph, bw = bounds.outWidth, bh = bounds.outHeight;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while both sides stay at least the card's, or while it's still too many pixels (either side too big).
        for (int s = 1; bw / (s * 2) >= cw && bh / (s * 2) >= ch || (long) (bw / s) * (bh / s) > MAX_PIXELS; s *= 2) {
            opts.inSampleSize = s * 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new IOException("unreadable picture");
        // Centre crop to 16:9.
        int sw = src.getWidth(), sh = src.getHeight();
        Rect from = (long) sw * ch > (long) sh * cw ? new Rect((sw - sh * cw / ch) / 2, 0, (sw + sh * cw / ch) / 2, sh)
                : new Rect(0, (sh - sw * ch / cw) / 2, sw, (sh + sw * ch / cw) / 2);
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, cw, ch), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    /**
     * One colour for the whole picture, worked out once here in the background: the average of a tiny copy, with its
     * saturation lifted (a plain average comes out greyish) and its brightness kept in the middle.
     */
    private static int colorOf(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 8, 5, true);
        int[] px = new int[40];
        tiny.getPixels(px, 0, 8, 0, 0, 8, 5);
        tiny.recycle();
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV((int) (r / 40), (int) (g / 40), (int) (bl / 40), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.08f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** The app's TV banner (or its icon) on a dark card, as the shelf shows apps without a logo. */
    private Bitmap banner(String pkg) throws IOException {
        PackageManager pm = app.getPackageManager();
        Drawable d;
        try {
            d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("no app " + pkg);
        }
        int cw = pw, ch = ph;
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF15161C);
        // TV banners are 16:9 already: edge to edge; an icon goes in the middle.
        boolean wide = d.getIntrinsicWidth() > d.getIntrinsicHeight();
        int s = Math.round(ch * 0.5f);
        if (wide) d.setBounds(0, 0, cw, ch);
        else d.setBounds((cw - s) / 2, (ch - s) / 2, (cw + s) / 2, (ch + s) / 2);
        d.draw(c);
        return out;
    }

    /** Only content:// and android.resource:// get here (checked when the item is read). */
    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no picture there");
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("picture too large");
        }
        return out.toByteArray();
    }
}
