package com.chra4.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
 کامێرا — ئاگادارکردنەوەی ناوخۆیی (native) بۆ ئەندرۆید
 Design & Development Hemn A.Muhammad

 Same logic and wording as ios/App/App/KameraGuard.swift. While the app is on screen the
 web page shows and sounds the alerts; when the app is in the background or the screen is
 off, this class reads GPS, checks the distance to each camera, posts the notification and
 plays the alert sound. Background location keeps working because KameraService (the app's own
 foreground service of type "location") runs while alerts are on, also after the app is swiped
 away. After a restart, BootReceiver offers a one-tap notification that turns the alerts back on.
*/
public final class KameraGuard implements LocationListener {

    private static final String SITE = "https://camera.4chra.com";
    private static final double ARM_KMH = 40.0;
    private static final String CHANNEL = "kamera_alerts";

    private static KameraGuard instance;

    public static synchronized KameraGuard get(Context c) {
        if (instance == null) instance = new KameraGuard(c.getApplicationContext());
        return instance;
    }

    static final class Cam {
        final String id; final double lat, lon; final Integer speed;
        Cam(String id, double lat, double lon, Integer speed) { this.id = id; this.lat = lat; this.lon = lon; this.speed = speed; }
    }
    static final class Rec { int stage = 0; boolean passed = false; boolean urgentSent = false; }
    static final class Zone {
        final int limit; final long t0; double dist = 0; double lastLat, lastLon; long warnedAt = 0;
        Zone(int limit, long t0, double lat, double lon) { this.limit = limit; this.t0 = t0; this.lastLat = lat; this.lastLon = lon; }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocationManager lm;

    private volatile boolean visible = true;
    private boolean running = false;
    private boolean listening = false;
    private boolean armed = false;
    private List<Cam> cams = new ArrayList<>();      // official list + approved community cameras
    private List<Cam> userCams = new ArrayList<>();  // cameras the user added on this phone
    private double alertDist = 500;
    private double margin = 5;
    private boolean soundOn = true;
    private boolean vibrateOn = true;

    // per-drive state
    private double prevLat, prevLon; private long prevT; private boolean hasPrev = false;
    private Double heading = null;
    private double kmh = 0;
    private final Map<String, Rec> alerted = new HashMap<>();
    private Zone zone = null;
    private long lastUrgentSound = 0;
    private int notifId = 1000;
    private MediaPlayer player;
    private AudioFocusRequest focusReq;

    private KameraGuard(Context ctx) {
        this.ctx = ctx;
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        List<Cam> all = new ArrayList<>(parseCams(readCache("cams_official.json")));
        all.addAll(parseCams(readCache("cams_community.json")));
        cams = all;
        createChannel();
    }

    public boolean isVisible() { return visible; }

    public void setVisible(boolean v) {
        visible = v;
        // if the service could not start earlier (app was in the background), start it now
        if (v && running && listening) KameraService.start(ctx);
    }

    // ---------------------------------------------------------------- remembered state (for restarts)

    private static final String PREFS = "kamera_guard", K_ON = "enabled", K_SETTINGS = "settings", K_USER = "userCams";

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** True when the person turned camera alerts on and has not pressed Stop. */
    public static boolean isEnabled(Context c) { return prefs(c).getBoolean(K_ON, false); }

    /** Started by KameraService after a restart (the person tapped the notification): alerts run without the page. */
    public void resumeFromPrefs() {
        main.post(() -> {
            if (running) return;
            SharedPreferences p = prefs(ctx);
            if (!p.getBoolean(K_ON, false)) { KameraService.stop(ctx); return; }
            try {
                JSONObject msg = new JSONObject();
                String st = p.getString(K_SETTINGS, null), uc = p.getString(K_USER, null);
                if (st != null) msg.put("settings", new JSONObject(st));
                if (uc != null) msg.put("userCams", new JSONArray(uc));
                apply(msg);
            } catch (Exception ignored) { }
            visible = false;      // the app is not on screen: the native side sounds the alerts
            resetDrive();
            armed = false;        // alerts arm at 40 km/h, like auto-start in the page
            running = true;
            refreshCams();
            startLocationIfAllowed();
        });
    }

    // ---------------------------------------------------------------- commands from the page

    public void onMessage(String json) {
        main.post(() -> {
            try {
                JSONObject msg = new JSONObject(json);
                String cmd = msg.optString("cmd");
                if ("start".equals(cmd)) start(msg);
                else if ("stop".equals(cmd)) stop();
                else if ("settings".equals(cmd)) apply(msg);
            } catch (Exception ignored) { }
        });
    }

    private void start(JSONObject msg) {
        apply(msg);
        resetDrive();
        armed = msg.optBoolean("armed", false);
        running = true;
        prefs(ctx).edit().putBoolean(K_ON, true).apply();
        refreshCams();
        startLocationIfAllowed();
    }

    private void stop() {
        running = false;
        prefs(ctx).edit().putBoolean(K_ON, false).apply();
        main.removeCallbacks(permissionRetry);
        if (listening) { try { lm.removeUpdates(this); } catch (Exception ignored) { } listening = false; }
        KameraService.stop(ctx);
        resetDrive();
    }

    private void apply(JSONObject msg) {
        JSONObject s = msg.optJSONObject("settings");
        if (s != null) {
            double v = s.optDouble("alertDist", Double.NaN); if (!Double.isNaN(v) && v > 0) alertDist = v;
            v = s.optDouble("margin", Double.NaN); if (!Double.isNaN(v) && v >= 0) margin = v;
            if (s.has("sound")) soundOn = s.optBoolean("sound", true);
            if (s.has("vibrate")) vibrateOn = s.optBoolean("vibrate", true);
            prefs(ctx).edit().putString(K_SETTINGS, s.toString()).apply();
        }
        JSONArray u = msg.optJSONArray("userCams");
        if (u != null) {
            userCams = parseCamList(u);
            prefs(ctx).edit().putString(K_USER, u.toString()).apply();
        }
    }

    private void resetDrive() {
        hasPrev = false; heading = null; kmh = 0; alerted.clear(); zone = null;
    }

    private final Runnable permissionRetry = this::startLocationIfAllowed;

    @SuppressLint("MissingPermission")
    private void startLocationIfAllowed() {
        if (!running || listening) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // the page asks for permission at the same moment; try again shortly
            main.postDelayed(permissionRetry, 2000);
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5f, this, Looper.getMainLooper());
            listening = true;
            // our own foreground service keeps alerts alive in the background and after a swipe-away
            KameraService.start(ctx);
        } catch (Exception e) {
            main.postDelayed(permissionRetry, 5000);
        }
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    // ---------------------------------------------------------------- camera list (cached, works with weak signal)

    private File cacheFile(String name) { return new File(ctx.getCacheDir(), name); }

    private String readCache(String name) {
        try (InputStream in = new FileInputStream(cacheFile(name))) { return readAll(in); } catch (Exception e) { return null; }
    }

    private void writeCache(String name, String data) {
        try (FileOutputStream out = new FileOutputStream(cacheFile(name))) { out.write(data.getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static String download(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(SITE + path).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void refreshCams() {
        new Thread(() -> {
            String official = download("/cameras.json");
            if (official != null && parseCams(official).isEmpty()) official = null;
            String community = download("/api/cameras");
            if (official != null) writeCache("cams_official.json", official);
            if (community != null) writeCache("cams_community.json", community);
            if (official == null && community == null) return;
            List<Cam> all = new ArrayList<>(parseCams(official != null ? official : readCache("cams_official.json")));
            all.addAll(parseCams(community != null ? community : readCache("cams_community.json")));
            main.post(() -> cams = all);
        }).start();
    }

    private static List<Cam> parseCams(String data) {
        if (data == null) return new ArrayList<>();
        try {
            JSONArray list = new JSONObject(data).optJSONArray("cameras");
            return list == null ? new ArrayList<>() : parseCamList(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<Cam> parseCamList(JSONArray list) {
        List<Cam> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null || !c.has("lat") || !c.has("lon")) continue;
            double lat = c.optDouble("lat", Double.NaN), lon = c.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            String id = c.has("id") && !c.isNull("id") ? String.valueOf(c.opt("id")) : lat + "," + lon;
            int sp = c.optInt("speed", 0);
            out.add(new Cam(id, lat, lon, sp > 0 ? sp : null));
        }
        return out;
    }

    private List<Cam> allCams() {
        List<Cam> a = new ArrayList<>(cams);
        a.addAll(userCams);
        return a;
    }

    // ---------------------------------------------------------------- geo helpers (same formulas as the web page)

    private static final double RAD = Math.PI / 180;

    private static double dist(double a, double b, double c, double d) {
        double dLat = (c - a) * RAD, dLon = (d - b) * RAD;
        double x = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(a * RAD) * Math.cos(c * RAD) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(x)));
    }

    private static double bearing(double a, double b, double c, double d) {
        double y = Math.sin((d - b) * RAD) * Math.cos(c * RAD);
        double x = Math.cos(a * RAD) * Math.sin(c * RAD) - Math.sin(a * RAD) * Math.cos(c * RAD) * Math.cos((d - b) * RAD);
        return ((Math.atan2(y, x) / RAD) + 360) % 360;
    }

    private static double angDiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static String fmtDist(double d) {
        if (d >= 1000) return String.format(Locale.US, "%.1f کم", d / 1000);
        return Math.max(0, Math.round(d / 10) * 10) + " م";
    }

    // ---------------------------------------------------------------- position handling

    @Override
    public void onLocationChanged(Location loc) {
        if (!running) return;
        if (loc.hasAccuracy() && loc.getAccuracy() >= 100) return;
        onPosition(loc);
    }

    private void onPosition(Location loc) {
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        long ts = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        double acc = loc.hasAccuracy() ? loc.getAccuracy() : 50;
        Double k = loc.hasSpeed() ? (double) loc.getSpeed() * 3.6 : null;
        if (hasPrev) {
            double d = dist(prevLat, prevLon, lat, lon), dt = (ts - prevT) / 1000.0;
            if (k == null && dt > 0.5) k = d / dt * 3.6;
            if (d >= 12 && acc < 60) { heading = bearing(prevLat, prevLon, lat, lon); prevLat = lat; prevLon = lon; prevT = ts; }
        } else { prevLat = lat; prevLon = lon; prevT = ts; hasPrev = true; }
        if (loc.hasBearing() && k != null && k > 8) heading = (double) loc.getBearing();
        if (k != null) kmh = kmh * 0.3 + k * 0.7;
        if (kmh < 1) kmh = 0;
        if (!armed && kmh >= ARM_KMH) armed = true;

        if (zone != null) {
            double step = dist(zone.lastLat, zone.lastLon, lat, lon);
            if (acc < 60 && step < 600) zone.dist += step;
            zone.lastLat = lat; zone.lastLon = lon;
            if (zone.dist > 8000 || ts - zone.t0 > 15 * 60 * 1000L) zone = null;
        }
        processCams(lat, lon, ts);
    }

    private void markGroup(Cam c, Rec rec) {
        // two cameras at the same spot (one per direction) alert only once
        for (Cam o : allCams()) if (dist(c.lat, c.lon, o.lat, o.lon) < 60) alerted.put(o.id, rec);
    }

    private void processCams(double lat, double lon, long ts) {
        Cam bestC = null; double bestD = Double.MAX_VALUE;
        for (Cam c : allCams()) {
            double d = dist(lat, lon, c.lat, c.lon);
            Rec rec = alerted.get(c.id);
            if (d > alertDist + 300 && rec != null) { alerted.remove(c.id); rec = null; }
            if (d > 6000) continue;
            boolean ahead = heading == null || d < 35 || angDiff(bearing(lat, lon, c.lat, c.lon), heading) <= 50;
            if (!ahead) {
                if (rec != null && !rec.passed && d < 300) {
                    rec.passed = true;
                    if (c.speed != null && d < 150) zone = new Zone(c.speed, ts, lat, lon);
                }
                continue;
            }
            if (rec != null && rec.passed) continue;
            if (d <= alertDist && d < bestD) { bestC = c; bestD = d; }
        }
        checkZone(ts);

        if (bestC == null || kmh < 10 || !armed) return;
        Cam c = bestC; double d = bestD;
        Rec rec = alerted.get(c.id);
        if (rec == null) { rec = new Rec(); markGroup(c, rec); }
        boolean over = c.speed != null && kmh > c.speed + margin;
        String limitText = c.speed != null ? " — سنوور " + c.speed + " km/h" : "";
        if (rec.stage < 1) {
            rec.stage = 1;
            if (over) {
                rec.urgentSent = true; lastUrgentSound = System.currentTimeMillis();
                notify("⚠️ خێراییت کەم بکەرەوە!", "کامێرا " + fmtDist(d) + " لە پێشەوە" + limitText, R.raw.urgent);
            } else {
                notify("📷 کامێرا لە پێشەوەیە", fmtDist(d) + limitText, R.raw.warn);
            }
        } else if (over) {
            if (!rec.urgentSent) {
                rec.urgentSent = true; lastUrgentSound = System.currentTimeMillis();
                notify("⚠️ خێراییت کەم بکەرەوە!", "کامێرا " + fmtDist(d) + " لە پێشەوە" + limitText, R.raw.urgent);
            } else if (System.currentTimeMillis() - lastUrgentSound > 6000) {
                lastUrgentSound = System.currentTimeMillis();
                if (!visible) playSound(R.raw.urgent); // keep reminding while still too fast
            }
        }
    }

    private void checkZone(long ts) {
        Zone z = zone;
        if (z == null) return;
        double secs = (ts - z.t0) / 1000.0;
        if (secs <= 10 || z.dist < 100) return;
        double avg = z.dist / secs * 3.6;
        if (avg > z.limit + margin && System.currentTimeMillis() - z.warnedAt > 20000) {
            z.warnedAt = System.currentTimeMillis();
            notify("⚠️ تێکڕای خێراییت زیاترە", "تێکڕا " + Math.round(avg) + " — سنوور " + z.limit + " km/h", R.raw.avg);
        }
    }

    // ---------------------------------------------------------------- alert output

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, ctx.getString(R.string.kamera_channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);          // the app plays the alert sound itself (loud, over music)
        ch.enableVibration(false);        // and vibrates itself when the setting is on
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    /** While the app is on screen the page already beeps and shows the banner, so nothing is sent twice. */
    @SuppressLint("MissingPermission")
    private void notify(String title, String body, int sound) {
        if (visible) return;
        playSound(sound);
        vibrate();
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return;
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_kamera)
                .setColor(sound == R.raw.warn ? 0xFF16A34A : 0xFFDC2626)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(60000)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(ctx).notify(notifId++, b.build());
        } catch (SecurityException ignored) { }
    }

    @SuppressLint("MissingPermission")
    private void vibrate() {
        if (!vibrateOn) return;
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 300, 100, 300};
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception ignored) { }
    }

    /** Plays the alert as navigation guidance: loud, and music is lowered meanwhile. */
    private void playSound(int res) {
        if (!soundOn) return;
        try {
            if (player != null) { player.release(); player = null; }
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build();
                    am.requestAudioFocus(focusReq);
                } else {
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(attrs);
            p.setDataSource(ctx, Uri.parse("android.resource://" + ctx.getPackageName() + "/" + res));
            p.setOnCompletionListener(mp -> {
                mp.release();
                if (player == mp) player = null;
                if (am != null) {
                    if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
                    else am.abandonAudioFocus(null);
                }
            });
            p.prepare();
            p.start();
            player = p;
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- script injected into camera.4chra.com

    static final String HOOK_JS = "(function () {\n"
            + "  if (window.__kgInstalled) return; window.__kgInstalled = true;\n"
            + "  function post(m) { try { window.KameraAndroid.post(JSON.stringify(m)); } catch (e) {} }\n"
            + "  function read(k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }\n"
            + "  function state(cmd) {\n"
            + "    var kc = window.__kc;\n"
            + "    return { cmd: cmd, settings: read('kc_settings', {}), userCams: read('kc_user_cams', []),\n"
            + "             armed: !!(kc && kc.S && kc.S.armed) };\n"
            + "  }\n"
            // In the background the native side sounds the alerts: keep the page's beeps and vibration quiet then.
            + "  var paused = false, ctxs = [];\n"
            + "  ['AudioContext', 'webkitAudioContext'].forEach(function (n) {\n"
            + "    var O = window[n]; if (!O || O.__kg) return;\n"
            + "    var P = new Proxy(O, { construct: function (t, a) { var c = Reflect.construct(t, a); ctxs.push(c); if (paused) { try { c.suspend(); } catch (e) {} } return c; } });\n"
            + "    P.__kg = true; window[n] = P;\n"
            + "    if (!O.prototype.__kgResume) {\n"
            + "      var r = O.prototype.resume; O.prototype.__kgResume = r;\n"
            + "      O.prototype.resume = function () { if (paused) return Promise.resolve(); return r.apply(this, arguments); };\n"
            + "    }\n"
            + "  });\n"
            + "  if (navigator.vibrate) { var vb = navigator.vibrate.bind(navigator); navigator.vibrate = function () { return paused ? false : vb.apply(null, arguments); }; }\n"
            + "  window.__kgSetPaused = function (p) {\n"
            + "    p = !!p; if (p === paused) return; paused = p;\n"
            + "    ctxs.forEach(function (c) { try { if (p) c.suspend(); else c.__proto__.__kgResume.call(c); } catch (e) {} });\n"
            + "    try { document.dispatchEvent(new Event(p ? 'pause' : 'resume')); } catch (e) {}\n"
            + "  };\n"
            + "  function hook() {\n"
            + "    var C = window.Capacitor;\n"
            + "    if (!C || typeof C.nativePromise !== 'function' || typeof C.nativeCallback !== 'function') return false;\n"
            + "    if (C.__kg) return true; C.__kg = true;\n"
            + "    var np = C.nativePromise, nc = C.nativeCallback;\n"
            + "    C.nativePromise = function (p, m) {\n"
            + "      if (p === 'LocalNotifications' && m === 'schedule') return Promise.resolve({ notifications: [] });\n"
            + "      if (p === 'BackgroundGeolocation' && m === 'removeWatcher') post({ cmd: 'stop' });\n"
            + "      return np.apply(C, arguments);\n"
            + "    };\n"
            + "    C.nativeCallback = function (p, m, o) {\n"
            + "      if (p === 'BackgroundGeolocation' && m === 'addWatcher') {\n"
            + "        post(state('start'));\n"
            // KameraService shows the one background notification; the plugin's own would be a second one
            + "        if (o && o.backgroundMessage) { var a = Array.prototype.slice.call(arguments); a[2] = Object.assign({}, o); delete a[2].backgroundMessage; delete a[2].backgroundTitle; return nc.apply(C, a); }\n"
            + "      }\n"
            + "      return nc.apply(C, arguments);\n"
            + "    };\n"
            // alerts may already be on if the page started before this script arrived
            + "    var kc = window.__kc;\n"
            + "    if (kc && kc.S && kc.S.natWatch != null) post(state('start'));\n"
            + "    return true;\n"
            + "  }\n"
            + "  if (!hook()) { var n = 0, t = setInterval(function () { if (hook() || ++n > 400) clearInterval(t); }, 50); }\n"
            // Android draws the page edge to edge: use the inset values Capacitor injects (--safe-area-inset-*)
            + "  function css() { if (document.getElementById('kg-safe')) return; var st = document.createElement('style'); st.id = 'kg-safe';\n"
            + "    st.textContent = 'html:root{--safe-t:var(--safe-area-inset-top,env(safe-area-inset-top,0px));--safe-b:var(--safe-area-inset-bottom,env(safe-area-inset-bottom,0px))}';\n"
            + "    (document.head || document.documentElement).appendChild(st); }\n"
            + "  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', css); else css();\n"
            + "  var si = Storage.prototype.setItem;\n"
            + "  Storage.prototype.setItem = function (k) {\n"
            + "    var r = si.apply(this, arguments);\n"
            + "    if (k === 'kc_settings' || k === 'kc_user_cams') post(state('settings'));\n"
            + "    return r;\n"
            + "  };\n"
            + "})();";
}
