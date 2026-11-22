/*
 کامێرا — ئاگادارکردنەوەی ناوخۆیی (native) بۆ ئەندرۆید
 Design & Development Hemn A.Muhammad

 Same idea as KameraGuard.swift on iPhone: the page (camera.4chra.com) draws the map,
 while reading GPS, checking the distance to each camera, the notification and the
 alert sound run here natively, so they keep working with the screen off.

 A small script injected into the page reports Start / Stop and the settings to this
 plugin, and stops the page from sending its own notifications (no duplicates).
 The background-geolocation plugin started by the page keeps the app alive in the
 background with its foreground-service notification.
*/
package com.chra4.camera;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@CapacitorPlugin(name = "KameraGuard")
public class KameraGuardPlugin extends Plugin implements LocationListener {

    private static final String SITE = "https://camera.4chra.com";
    private static final double ARM_KMH = 40;
    private static final String CHANNEL = "kamera_alerts";

    static final class Cam {
        final String id; final double lat, lon; final Integer speed;
        Cam(String id, double lat, double lon, Integer speed) { this.id = id; this.lat = lat; this.lon = lon; this.speed = speed; }
    }
    static final class Rec { int stage = 0; boolean passed = false; boolean urgentSent = false; }
    static final class Zone {
        final int limit; final long t0; double dist = 0; double lastLat, lastLon; long warnedAt = 0;
        Zone(int limit, long t0, double lat, double lon) { this.limit = limit; this.t0 = t0; lastLat = lat; lastLon = lon; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private boolean running = false, listening = false, armed = false;
    private List<Cam> cams = new ArrayList<>();
    private List<Cam> userCams = new ArrayList<>();
    private double alertDist = 500, margin = 5;
    private boolean soundOn = true, vibrateOn = true;

    // per-drive state
    private Double prevLat, prevLon; private long prevT;
    private Double heading;
    private double kmh = 0;
    private final Map<String, Rec> alerted = new HashMap<>();
    private Zone zone;
    private long lastUrgentSound = 0;
    private int notifId = 1000;
    private MediaPlayer player;

    @Override
    public void load() {
        lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        cams = new ArrayList<>();
        cams.addAll(parseCams(readCache("cams_official.json")));
        cams.addAll(parseCams(readCache("cams_community.json")));
        createChannel();
        // inject the small bridge script into camera.4chra.com after each page load
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView webView) {
                webView.evaluateJavascript(HOOK_JS, null);
            }
        });
    }

    // ---------- commands from the page ----------

    @PluginMethod
    public void start(PluginCall call) {
        apply(call.getData());
        resetDrive();
        armed = call.getBoolean("armed", false);
        running = true;
        refreshCams();
        startLocation(0);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        running = false;
        stopLocation();
        resetDrive();
        call.resolve();
    }

    @PluginMethod
    public void settings(PluginCall call) {
        apply(call.getData());
        call.resolve();
    }

    private void apply(JSObject msg) {
        JSONObject s = msg.optJSONObject("settings");
        if (s != null) {
            double d = s.optDouble("alertDist", 0); if (d > 0) alertDist = d;
            if (s.has("margin")) { double m = s.optDouble("margin", 5); if (m >= 0) margin = m; }
            if (s.has("sound")) soundOn = s.optBoolean("sound", true);
            if (s.has("vibrate")) vibrateOn = s.optBoolean("vibrate", true);
        }
        JSONArray u = msg.optJSONArray("userCams");
        if (u != null) userCams = parseCamList(u);
    }

    private void resetDrive() {
        prevLat = null; prevLon = null; heading = null; kmh = 0; alerted.clear(); zone = null;
    }

    // ---------- location ----------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The page asks for permission at the same moment it starts, so wait for it (up to ~2 minutes). */
    @SuppressWarnings("MissingPermission")
    private void startLocation(int attempt) {
        if (!running || listening || lm == null) return;
        if (!hasLocationPermission()) {
            if (attempt < 60) main.postDelayed(() -> startLocation(attempt + 1), 2000);
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5, this, Looper.getMainLooper());
            listening = true;
        } catch (Exception ignored) { }
    }

    private void stopLocation() {
        if (lm != null && listening) { try { lm.removeUpdates(this); } catch (Exception ignored) { } }
        listening = false;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    @Override
    public void onLocationChanged(Location loc) {
        if (!running || loc == null) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 100) return;
        onPosition(loc);
    }

    // ---------- camera list (downloaded and cached) ----------

    private File cacheFile(String name) { return new File(getContext().getCacheDir(), name); }

    private String readCache(String name) {
        try (InputStream in = new java.io.FileInputStream(cacheFile(name))) { return readAll(in); } catch (Exception e) { return null; }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private void refreshCams() {
        new Thread(() -> {
            String official = fetch("/cameras.json"), community = fetch("/api/cameras");
            if (official != null && !parseCams(official).isEmpty()) writeCache("cams_official.json", official); else official = readCache("cams_official.json");
            if (community != null) writeCache("cams_community.json", community); else community = readCache("cams_community.json");
            List<Cam> list = new ArrayList<>(parseCams(official));
            list.addAll(parseCams(community));
            main.post(() -> { if (!list.isEmpty()) cams = list; });
        }).start();
    }

    private static String fetch(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(SITE + path).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Exception e) { return null; } finally { if (c != null) c.disconnect(); }
    }

    private void writeCache(String name, String data) {
        try (OutputStream o = new FileOutputStream(cacheFile(name))) { o.write(data.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
    }

    private static List<Cam> parseCams(String json) {
        if (json == null) return new ArrayList<>();
        try { JSONArray a = new JSONObject(json).optJSONArray("cameras"); return a == null ? new ArrayList<>() : parseCamList(a); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private static List<Cam> parseCamList(JSONArray a) {
        List<Cam> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i); if (c == null) continue;
            if (!(c.opt("lat") instanceof Number) || !(c.opt("lon") instanceof Number)) continue;
            double lat = c.optDouble("lat"), lon = c.optDouble("lon");
            String id = c.has("id") && !c.isNull("id") ? String.valueOf(c.opt("id")) : lat + "," + lon;
            Integer sp = null;
            if (c.opt("speed") instanceof Number) { int v = c.optInt("speed"); if (v > 0) sp = v; }
            out.add(new Cam(id, lat, lon, sp));
        }
        return out;
    }

    private List<Cam> allCams() { List<Cam> l = new ArrayList<>(cams); l.addAll(userCams); return l; }

    // ---------- geo helpers (same formulas as the web page) ----------

    private static final double RAD = Math.PI / 180;
    private static double dist(double a, double b, double c, double d) {
        double dLat = (c - a) * RAD, dLon = (d - b) * RAD;
        double x = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(a * RAD) * Math.cos(c * RAD) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(x)));
    }
    private static double bearing(double a, double b, double c, double d) {
        double y = Math.sin((d - b) * RAD) * Math.cos(c * RAD);
        double x = Math.cos(a * RAD) * Math.sin(c * RAD) - Math.sin(a * RAD) * Math.cos(c * RAD) * Math.cos((d - b) * RAD);
        return (Math.atan2(y, x) / RAD + 360) % 360;
    }
    private static double angDiff(double a, double b) { double d = Math.abs(a - b) % 360; return d > 180 ? 360 - d : d; }
    private static String fmtDist(double d) {
        return d >= 1000 ? String.format(Locale.US, "%.1f کم", d / 1000) : Math.max(0, Math.round(d / 10) * 10) + " م";
    }

    // ---------- position handling ----------

    private void onPosition(Location loc) {
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        long ts = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        double acc = loc.hasAccuracy() ? loc.getAccuracy() : 50;
        Double k = loc.hasSpeed() ? (double) loc.getSpeed() * 3.6 : null;
        if (prevLat != null) {
            double d = dist(prevLat, prevLon, lat, lon), dt = (ts - prevT) / 1000.0;
            if (k == null && dt > 0.5) k = d / dt * 3.6;
            if (d >= 12 && acc < 60) { heading = bearing(prevLat, prevLon, lat, lon); prevLat = lat; prevLon = lon; prevT = ts; }
        } else { prevLat = lat; prevLon = lon; prevT = ts; }
        if (loc.hasBearing() && k != null && k > 8) heading = (double) loc.getBearing();
        if (k != null) kmh = kmh * 0.3 + k * 0.7;
        if (kmh < 1) kmh = 0;
        if (!armed && kmh >= ARM_KMH) armed = true;

        if (zone != null) {
            double step = dist(zone.lastLat, zone.lastLon, lat, lon);
            if (acc < 60 && step < 600) zone.dist += step;
            zone.lastLat = lat; zone.lastLon = lon;
            if (zone.dist > 8000 || ts - zone.t0 > 15 * 60 * 1000) zone = null;
        }
        processCams(lat, lon, ts);
    }

    private void markGroup(Cam c, Rec rec) {
        // two cameras at the same spot (one per direction) alert only once
        for (Cam o : allCams()) if (dist(c.lat, c.lon, o.lat, o.lon) < 60) alerted.put(o.id, rec);
    }

    private void processCams(double lat, double lon, long ts) {
        Cam best = null; double bestD = Double.MAX_VALUE;
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
            if (d <= alertDist && d < bestD) { best = c; bestD = d; }
        }
        checkZone(ts);

        if (best == null || kmh < 10 || !armed) return;
        Rec rec = alerted.get(best.id);
        if (rec == null) { rec = new Rec(); markGroup(best, rec); }
        boolean over = best.speed != null && kmh > best.speed + margin;
        String limitText = best.speed != null ? " — سنوور " + best.speed + " km/h" : "";
        long now = System.currentTimeMillis();
        if (rec.stage < 1) {
            rec.stage = 1;
            if (over) {
                rec.urgentSent = true; lastUrgentSound = now;
                notify("⚠️ خێراییت کەم بکەرەوە!", "کامێرا " + fmtDist(bestD) + " لە پێشەوە" + limitText, "urgent");
            } else {
                notify("📷 کامێرا لە پێشەوەیە", fmtDist(bestD) + limitText, "warn");
            }
        } else if (over) {
            if (!rec.urgentSent) {
                rec.urgentSent = true; lastUrgentSound = now;
                notify("⚠️ خێراییت کەم بکەرەوە!", "کامێرا " + fmtDist(bestD) + " لە پێشەوە" + limitText, "urgent");
            } else if (now - lastUrgentSound > 6000) {
                lastUrgentSound = now; playSound("urgent"); vibrate(new long[]{0, 150, 80, 150, 80, 150});
            }
        }
    }

    private void checkZone(long ts) {
        if (zone == null) return;
        double secs = (ts - zone.t0) / 1000.0;
        if (secs <= 10 || zone.dist < 100) return;
        double avg = zone.dist / secs * 3.6;
        long now = System.currentTimeMillis();
        if (avg > zone.limit + margin && now - zone.warnedAt > 20000) {
            zone.warnedAt = now;
            notify("⚠️ تێکڕای خێراییت زیاترە", "تێکڕا " + Math.round(avg) + " — سنوور " + zone.limit + " km/h", "avg");
        }
    }

    // ---------- alert output ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "ئاگاداری کامێرا", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("ئاگادارکردنەوە پێش گەیشتن بە کامێرای خێرایی");
        ch.setSound(null, null);          // the app plays its own alert sound
        ch.enableVibration(true);
        NotificationManager nm = getContext().getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @SuppressWarnings("MissingPermission")
    private void notify(String title, String body, String sound) {
        playSound(sound);
        vibrate("urgent".equals(sound) ? new long[]{0, 150, 80, 150, 80, 150} : new long[]{0, 300, 120, 300});
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setTimeoutAfter(60000);
        try { NotificationManagerCompat.from(ctx).notify(notifId++, b.build()); } catch (Exception ignored) { }
    }

    private void vibrate(long[] pattern) {
        if (!vibrateOn) return;
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)); else v.vibrate(pattern, -1);
        } catch (Exception ignored) { }
    }

    /** Plays through the navigation-guidance stream (not muted by silent mode) and lowers music meanwhile. */
    private void playSound(String name) {
        if (!soundOn) return;
        try {
            File f = new File(getContext().getCacheDir(), name + ".wav");
            if (!f.exists()) {
                try (InputStream in = getContext().getAssets().open("public/" + name + ".wav"); OutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            final AudioFocusRequest focus;
            if (am != null && Build.VERSION.SDK_INT >= 26) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build();
                am.requestAudioFocus(focus);
            } else focus = null;
            if (player != null) { try { player.release(); } catch (Exception ignored) { } }
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(attrs);
            p.setDataSource(f.getAbsolutePath());
            p.setOnCompletionListener(mp -> {
                mp.release(); if (player == mp) player = null;
                if (am != null && focus != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(focus);
            });
            p.prepare();
            p.start();
            player = p;
        } catch (Exception ignored) { }
    }

    // ---------- script injected into camera.4chra.com (same as the iPhone version) ----------

    static final String HOOK_JS =
        "(function () {\n" +
        "  if (window.__kgInstalled) return; window.__kgInstalled = true;\n" +
        "  var C0 = null, np0 = null;\n" +
        "  function post(m) {\n" +
        "    try { if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.kameraGuard) { window.webkit.messageHandlers.kameraGuard.postMessage(m); return; } } catch (e) {}\n" +
        "    try { if (np0) np0.call(C0, 'KameraGuard', m.cmd, m).catch(function () {}); } catch (e) {}\n" +
        "  }\n" +
        "  function read(k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }\n" +
        "  function state(cmd) {\n" +
        "    var kc = window.__kc;\n" +
        "    return { cmd: cmd, settings: read('kc_settings', {}), userCams: read('kc_user_cams', []),\n" +
        "             armed: !!(kc && kc.S && kc.S.armed) };\n" +
        "  }\n" +
        "  function hook() {\n" +
        "    var C = window.Capacitor;\n" +
        "    if (!C || typeof C.nativePromise !== 'function' || typeof C.nativeCallback !== 'function') return false;\n" +
        "    if (C.__kg) return true; C.__kg = true;\n" +
        "    var np = C.nativePromise, nc = C.nativeCallback; C0 = C; np0 = np;\n" +
        "    C.nativePromise = function (p, m) {\n" +
        "      if (p === 'LocalNotifications' && m === 'schedule') return Promise.resolve({ notifications: [] });\n" +
        "      if (p === 'BackgroundGeolocation' && m === 'removeWatcher') post({ cmd: 'stop' });\n" +
        "      return np.apply(C, arguments);\n" +
        "    };\n" +
        "    C.nativeCallback = function (p, m) {\n" +
        "      if (p === 'BackgroundGeolocation' && m === 'addWatcher') post(state('start'));\n" +
        "      return nc.apply(C, arguments);\n" +
        "    };\n" +
        "    var kc = window.__kc; if (kc && kc.S && kc.S.running && !kc.S.sim) post(state('start'));\n" +
        "    return true;\n" +
        "  }\n" +
        "  if (!hook()) { var n = 0, t = setInterval(function () { if (hook() || ++n > 200) clearInterval(t); }, 50); }\n" +
        "  var si = Storage.prototype.setItem;\n" +
        "  Storage.prototype.setItem = function (k) {\n" +
        "    var r = si.apply(this, arguments);\n" +
        "    if (k === 'kc_settings' || k === 'kc_user_cams') post(state('settings'));\n" +
        "    return r;\n" +
        "  };\n" +
        "})();\n";
}
