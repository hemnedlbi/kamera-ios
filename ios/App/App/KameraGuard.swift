/*
 کامێرا — ئاگادارکردنەوەی ناوخۆیی (native)
 Design & Development Hemn A.Muhammad

 The web page (camera.4chra.com) draws the map and plays beeps while it is on screen.
 Everything that must keep working in the background — reading GPS, checking the
 distance to each camera, sending the notification and playing the alert sound —
 runs here in Swift, so it no longer depends on iOS keeping the WebView's JavaScript awake.

 The page is not changed: a small script injected into it reports Start / Stop and
 the settings to this class, and stops the page from sending its own notifications
 (this class sends them instead, so they never arrive twice).
*/

import UIKit
import WebKit
import Capacitor
import CoreLocation
import UserNotifications
import AVFoundation

// MARK: - Bridge view controller that wires the page to KameraGuard

class KameraBridgeViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        super.capacitorDidLoad()
        guard let ucc = webView?.configuration.userContentController else { return }
        ucc.add(KameraMessageProxy(), name: "kameraGuard")
        ucc.addUserScript(WKUserScript(source: KameraGuard.hookJS, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
    }
}

/// Separate object so the WKUserContentController does not retain the view controller.
final class KameraMessageProxy: NSObject, WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let cmd = body["cmd"] as? String else { return }
        DispatchQueue.main.async {
            switch cmd {
            case "start": KameraGuard.shared.start(body)
            case "stop": KameraGuard.shared.stop()
            case "settings": KameraGuard.shared.apply(body)
            default: break
            }
        }
    }
}

// MARK: - Native camera alerts

final class KameraGuard: NSObject, CLLocationManagerDelegate, AVAudioPlayerDelegate {
    static let shared = KameraGuard()
    private static let site = "https://camera.4chra.com"
    private static let armKmh = 40.0
    private static let armKmhRelaunch = 25.0   // after a background relaunch the car is already moving

    struct Cam { let id: String; let lat: Double; let lon: Double; let speed: Int? }
    final class Rec { var stage = 0; var passed = false; var urgentSent = false }
    final class Zone {
        let limit: Int; let t0: Date; var dist = 0.0; var last: (Double, Double); var warnedAt = Date.distantPast
        init(limit: Int, t0: Date, last: (Double, Double)) { self.limit = limit; self.t0 = t0; self.last = last }
    }

    private let lm = CLLocationManager()
    private var running = false
    private var armed = false
    private var cams: [Cam] = []        // official list + approved community cameras
    private var userCams: [Cam] = []    // cameras the user added on this phone
    private var alertDist = 500.0
    private var margin = 5.0
    private var soundOn = true

    // per-drive state
    private var prev: (lat: Double, lon: Double, t: Date)?
    private var heading: Double?
    private var kmh = 0.0
    private var alerted: [String: Rec] = [:]
    private var zone: Zone?
    private var lastPassed: Cam?
    private var lastUrgentSound = Date.distantPast
    private var player: AVAudioPlayer?

    // relaunch by iOS after the app was closed or the phone restarted
    private static let kEnabled = "kg.enabled", kSettings = "kg.settings", kUserCams = "kg.userCams"
    private static let idleSleep: TimeInterval = 10 * 60
    private var resumedInBackground = false   // started by iOS, not by the page
    private var sleeping = false              // GPS paused while parked; wakes on the next significant change
    private var lastMoving = Date()
    private var bgSession: AnyObject?         // CLBackgroundActivitySession on iOS 17+
    private var startedFromCar = false        // switched on from the CarPlay screen, not the page

    private override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        lm.distanceFilter = 5
        lm.activityType = .automotiveNavigation
        lm.pausesLocationUpdatesAutomatically = false
        lm.allowsBackgroundLocationUpdates = true
        lm.showsBackgroundLocationIndicator = true
        cams = Self.parseCams(try? Data(contentsOf: Self.cacheURL("cams_official.json")))
             + Self.parseCams(try? Data(contentsOf: Self.cacheURL("cams_community.json")))
        installNotificationSounds()
        NotificationCenter.default.addObserver(self, selector: #selector(appDidBecomeActive),
                                               name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    // MARK: commands from the page

    func start(_ msg: [String: Any]) {
        apply(msg)
        resetDrive()
        armed = (msg["armed"] as? Bool) ?? false
        running = true
        resumedInBackground = false
        startedFromCar = false
        sleeping = false
        lastMoving = Date()
        UserDefaults.standard.set(true, forKey: Self.kEnabled)
        // .carPlay: the alert banners also show on the car screen
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .carPlay]) { _, _ in }
        refreshCams()
        beginBackgroundSession()
        startLocationIfAllowed()
        startSignificantChanges()
        KameraLive.start()   // lock screen + Dynamic Island (only possible while the app is on screen)
        KameraCar.refreshEnabled()
    }

    func stop() {
        running = false
        resumedInBackground = false
        sleeping = false
        UserDefaults.standard.set(false, forKey: Self.kEnabled)
        lm.stopUpdatingLocation()
        lm.stopMonitoringSignificantLocationChanges()
        endBackgroundSession()
        KameraLive.end()
        resetDrive()
        startedFromCar = false
        KameraCar.refreshEnabled()
    }

    func apply(_ msg: [String: Any]) {
        if let s = msg["settings"] as? [String: Any] {
            if let v = (s["alertDist"] as? NSNumber)?.doubleValue, v > 0 { alertDist = v }
            if let v = (s["margin"] as? NSNumber)?.doubleValue, v >= 0 { margin = v }
            if let v = s["sound"] as? Bool { soundOn = v }
            if let d = try? JSONSerialization.data(withJSONObject: s) { UserDefaults.standard.set(d, forKey: Self.kSettings) }
        }
        if let u = msg["userCams"] as? [[String: Any]] {
            userCams = Self.parseCamList(u)
            if let d = try? JSONSerialization.data(withJSONObject: u) { UserDefaults.standard.set(d, forKey: Self.kUserCams) }
        }
    }

    /// Called by AppDelegate when iOS relaunches the app in the background for a location event
    /// (after the app was swiped away or the phone restarted). Alerts carry on without the page.
    func resumeIfEnabled() {
        guard UserDefaults.standard.bool(forKey: Self.kEnabled), !running else { return }
        var msg: [String: Any] = [:]
        if let d = UserDefaults.standard.data(forKey: Self.kSettings),
           let s = try? JSONSerialization.jsonObject(with: d) as? [String: Any] { msg["settings"] = s }
        if let d = UserDefaults.standard.data(forKey: Self.kUserCams),
           let u = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] { msg["userCams"] = u }
        apply(msg)
        resetDrive()
        armed = false            // alerts arm at 40 km/h, as with auto-start in the page
        running = true
        resumedInBackground = true
        sleeping = false
        lastMoving = Date()
        beginBackgroundSession()
        refreshCams()
        startLocationIfAllowed()
        startSignificantChanges()
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "kg.lastRelaunch")
        KameraCar.refreshEnabled()
        // TestFlight builds only: say when iOS really relaunched the app in the background
        // (checked after a few seconds, because at launch the state can read "background" even
        // when the person opened the app)
        if Self.isTestFlight {
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
                guard let self, self.resumedInBackground, UIApplication.shared.applicationState == .background else { return }
                Self.postNow(id: "kg.relaunch", title: "TCK (تاقیکردنەوە)",
                             body: "ئایفۆن ئەپەکەی لە پشتەوە کردەوە — ئاگادارکردنەوەی کامێرا چالاکە.")
            }
        }
    }

    /// The driver pressed Start on the CarPlay screen: run the native engine with the last saved
    /// settings, exactly as after a background relaunch. Stop on the car screen switches it off again.
    func startFromCar() {
        UserDefaults.standard.set(true, forKey: Self.kEnabled)
        resumeIfEnabled()
        startedFromCar = running
        KameraCar.refreshEnabled()
    }

    /// When the person opens the app after a background relaunch, the page takes over again
    /// (its auto-start sends "start"), so the page and the alerts never disagree.
    /// Alerts stay "on" (kEnabled) until the person presses Stop in the page.
    @objc private func appDidBecomeActive() {
        // started from the car: keep alerts running when the phone app is opened
        guard resumedInBackground, !startedFromCar else { return }
        running = false
        resumedInBackground = false
        sleeping = false
        lm.stopUpdatingLocation()
        resetDrive()
    }

    /// True while camera alerts are on (the person has not pressed Stop).
    var isEnabled: Bool { UserDefaults.standard.bool(forKey: Self.kEnabled) }

    /// The person swiped the app away: iOS may not relaunch it, so say clearly that alerts stopped.
    func appWillTerminate() {
        guard isEnabled else { return }
        KameraLive.end()
        Self.postNow(id: "kg.stopped", title: "TCK — ئاگادارکردنەوەی کامێرا ڕاوەستا",
                     body: "ئەپەکەت داخست، بۆیە ئاگادارکردنەوە ڕاوەستا. دەست لێرە بدە بۆ دەستپێکردنەوە. جاری داهاتوو ئەپەکە دامەخە، تەنها بگەڕێوە بۆ Home Screen.")
        Thread.sleep(forTimeInterval: 0.4)   // give the notification time to be handed to the system
    }

    private static var isTestFlight: Bool {
        Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    }

    private static func postNow(id: String, title: String, body: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = .default
        let req = UNNotificationRequest(identifier: id, content: c,
                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        UNUserNotificationCenter.current().add(req)
    }

    private func startSignificantChanges() {
        guard CLLocationManager.significantLocationChangeMonitoringAvailable() else { return }
        lm.startMonitoringSignificantLocationChanges()
    }

    private func beginBackgroundSession() {
        if #available(iOS 17.0, *), bgSession == nil { bgSession = CLBackgroundActivitySession() }
    }

    private func endBackgroundSession() {
        if #available(iOS 17.0, *) { (bgSession as? CLBackgroundActivitySession)?.invalidate() }
        bgSession = nil
    }

    private func resetDrive() {
        prev = nil; heading = nil; kmh = 0; alerted.removeAll(); zone = nil; lastPassed = nil
    }

    private func startLocationIfAllowed() {
        guard running else { return }
        let st = lm.authorizationStatus
        if st == .authorizedAlways || st == .authorizedWhenInUse { lm.startUpdatingLocation() }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { startLocationIfAllowed() }

    // MARK: camera list (downloaded and cached so it also works with weak signal)

    private static func cacheURL(_ name: String) -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
    }

    private func refreshCams() {
        let group = DispatchGroup()
        var official: Data?, community: Data?
        for (path, file) in [("/cameras.json", "cams_official.json"), ("/api/cameras", "cams_community.json")] {
            guard let url = URL(string: Self.site + path) else { continue }
            group.enter()
            var req = URLRequest(url: url); req.cachePolicy = .reloadIgnoringLocalCacheData; req.timeoutInterval = 15
            URLSession.shared.dataTask(with: req) { data, resp, _ in
                DispatchQueue.main.async {
                    if let data = data, (resp as? HTTPURLResponse)?.statusCode == 200,
                       path != "/cameras.json" || !Self.parseCams(data).isEmpty {
                        try? data.write(to: Self.cacheURL(file), options: .atomic)
                        if path == "/cameras.json" { official = data } else { community = data }
                    }
                    group.leave()
                }
            }.resume()
        }
        group.notify(queue: .main) { [weak self] in
            guard let self = self, official != nil || community != nil else { return }
            self.cams = Self.parseCams(official ?? (try? Data(contentsOf: Self.cacheURL("cams_official.json"))))
                      + Self.parseCams(community ?? (try? Data(contentsOf: Self.cacheURL("cams_community.json"))))
        }
    }

    private static func parseCams(_ data: Data?) -> [Cam] {
        guard let data = data, let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = obj["cameras"] as? [[String: Any]] else { return [] }
        return parseCamList(list)
    }

    private static func parseCamList(_ list: [[String: Any]]) -> [Cam] {
        list.compactMap { c in
            guard let lat = (c["lat"] as? NSNumber)?.doubleValue, let lon = (c["lon"] as? NSNumber)?.doubleValue else { return nil }
            let id = (c["id"] as? String) ?? (c["id"] as? NSNumber)?.stringValue ?? "\(lat),\(lon)"
            let sp = (c["speed"] as? NSNumber)?.intValue
            return Cam(id: id, lat: lat, lon: lon, speed: (sp ?? 0) > 0 ? sp : nil)
        }
    }

    private var allCams: [Cam] { cams + userCams }

    // MARK: geo helpers (same formulas as the web page)

    private static let rad = Double.pi / 180
    private static func dist(_ a: Double, _ b: Double, _ c: Double, _ d: Double) -> Double {
        let dLat = (c - a) * rad, dLon = (d - b) * rad
        let x = pow(sin(dLat / 2), 2) + cos(a * rad) * cos(c * rad) * pow(sin(dLon / 2), 2)
        return 2 * 6371000 * asin(min(1, sqrt(x)))
    }
    private static func bearing(_ a: Double, _ b: Double, _ c: Double, _ d: Double) -> Double {
        let y = sin((d - b) * rad) * cos(c * rad)
        let x = cos(a * rad) * sin(c * rad) - sin(a * rad) * cos(c * rad) * cos((d - b) * rad)
        return (atan2(y, x) / rad + 360).truncatingRemainder(dividingBy: 360)
    }
    private static func angDiff(_ a: Double, _ b: Double) -> Double {
        let d = abs(a - b).truncatingRemainder(dividingBy: 360); return d > 180 ? 360 - d : d
    }
    private static func fmtDist(_ d: Double) -> String {
        d >= 1000 ? String(format: "%.1f کم", d / 1000) : "\(max(0, Int((d / 10).rounded()) * 10)) م"
    }

    // MARK: position handling

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard running else { return }
        if sleeping {
            // a significant change while parked: the car is moving again
            sleeping = false
            lastMoving = Date()
            resetDrive()
            startLocationIfAllowed()
        }
        for loc in locations where loc.horizontalAccuracy >= 0 && loc.horizontalAccuracy < 100 { onPosition(loc) }
        // after a background relaunch, pause precise GPS while parked to save battery
        if resumedInBackground && !sleeping {
            if kmh >= 10 { lastMoving = Date() }
            else if Date().timeIntervalSince(lastMoving) > Self.idleSleep {
                sleeping = true
                lm.stopUpdatingLocation()
            }
        }
    }

    private func onPosition(_ loc: CLLocation) {
        let lat = loc.coordinate.latitude, lon = loc.coordinate.longitude, ts = loc.timestamp, acc = loc.horizontalAccuracy
        var k: Double? = loc.speed >= 0 ? loc.speed * 3.6 : nil
        if let p = prev {
            let d = Self.dist(p.lat, p.lon, lat, lon), dt = ts.timeIntervalSince(p.t)
            if k == nil && dt > 0.5 { k = d / dt * 3.6 }
            if d >= 12 && acc < 60 { heading = Self.bearing(p.lat, p.lon, lat, lon); prev = (lat, lon, ts) }
        } else { prev = (lat, lon, ts) }
        if loc.course >= 0, let kk = k, kk > 8 { heading = loc.course }
        if let kk = k { kmh = kmh * 0.3 + kk * 0.7 }
        if kmh < 1 { kmh = 0 }
        if !armed && kmh >= armAt { armed = true; announceArmed() }

        if let z = zone {
            let step = Self.dist(z.last.0, z.last.1, lat, lon)
            if acc < 60 && step < 600 { z.dist += step }
            z.last = (lat, lon)
            if z.dist > 8000 || ts.timeIntervalSince(z.t0) > 15 * 60 { zone = nil }
        }
        processCams(lat, lon, ts)
    }

    private var armAt: Double { resumedInBackground ? Self.armKmhRelaunch : Self.armKmh }

    /// Alerts just switched on. On screen the page shows its own message; in the background
    /// (app on the Home Screen or phone locked) say so with a quiet notification, so the
    /// driver knows TCK is working even before the first camera.
    private func announceArmed() {
        guard UIApplication.shared.applicationState != .active else { return }
        let c = UNMutableNotificationContent()
        c.title = "✅ TCK — ئاگادارکردنەوەی کامێرا چالاک بوو"
        c.body = "خێرایی \(Int(kmh.rounded())) km/h. کە لە کامێرایەک نزیک بوویتەوە ئاگادارت دەکەمەوە."
        if #available(iOS 15.0, *) { c.interruptionLevel = .passive }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "kg.armed", content: c, trigger: nil))
    }

    private func markGroup(_ c: Cam, _ rec: Rec) {
        // two cameras at the same spot (one per direction) alert only once
        for o in allCams where Self.dist(c.lat, c.lon, o.lat, o.lon) < 60 { alerted[o.id] = rec }
    }

    private func processCams(_ lat: Double, _ lon: Double, _ ts: Date) {
        var best: (c: Cam, d: Double)?
        for c in allCams {
            let d = Self.dist(lat, lon, c.lat, c.lon)
            let rec = alerted[c.id]
            if d > alertDist + 300 && rec != nil { alerted[c.id] = nil }
            if d > 6000 { continue }
            let ahead = heading == nil || d < 35 || Self.angDiff(Self.bearing(lat, lon, c.lat, c.lon), heading ?? 0) <= 50
            if !ahead {
                if let r = rec, !r.passed, d < 300 {
                    r.passed = true
                    if let sp = c.speed { lastPassed = c; if d < 150 { zone = Zone(limit: sp, t0: ts, last: (lat, lon)) } }
                }
                continue
            }
            if rec?.passed == true { continue }
            if d <= alertDist && d < (best?.d ?? .infinity) { best = (c, d) }
        }
        checkZone(ts)
        updateLive(best)

        guard let hit = best, kmh >= 10, armed else { return }
        let c = hit.c, d = hit.d
        let rec: Rec
        if let r = alerted[c.id] { rec = r } else { rec = Rec(); markGroup(c, rec) }
        let over = c.speed.map { kmh > Double($0) + margin } ?? false
        let limitText = c.speed.map { " — سنوور \($0) km/h" } ?? ""
        if rec.stage < 1 {
            rec.stage = 1
            if over {
                rec.urgentSent = true; lastUrgentSound = Date()
                notify("⚠️ خێراییت کەم بکەرەوە!", "کامێرا \(Self.fmtDist(d)) لە پێشەوە\(limitText)", sound: "urgent")
            } else {
                notify("📷 کامێرا لە پێشەوەیە", Self.fmtDist(d) + limitText, sound: "warn")
            }
        } else if over {
            if !rec.urgentSent {
                rec.urgentSent = true; lastUrgentSound = Date()
                notify("⚠️ خێراییت کەم بکەرەوە!", "کامێرا \(Self.fmtDist(d)) لە پێشەوە\(limitText)", sound: "urgent")
            } else if Date().timeIntervalSince(lastUrgentSound) > 6 {
                lastUrgentSound = Date(); _ = playSound("urgent")   // keep reminding while still too fast
            }
        }
    }

    /// Lock screen / Dynamic Island: next camera ahead, its limit, speed, average-speed zone.
    private func updateLive(_ best: (c: Cam, d: Double)?) {
        let show = armed && kmh >= 10 ? best : nil
        let over = show.flatMap { b in b.c.speed.map { kmh > Double($0) + margin } } ?? false
        var zAvg = 0, zLim = 0
        if let z = zone {
            zLim = z.limit
            let secs = Date().timeIntervalSince(z.t0)
            if secs > 10 && z.dist >= 100 { zAvg = Int((z.dist / secs * 3.6).rounded()) }
        }
        KameraLive.update(dist: show?.d, limit: show?.c.speed, kmh: kmh, over: over, zoneAvg: zAvg, zoneLimit: zLim,
                          armAt: armed ? 0 : Int(armAt))
        KameraCar.update(dist: show?.d, limit: show?.c.speed, kmh: kmh, over: over, zoneAvg: zAvg, zoneLimit: zLim,
                         armAt: armed ? 0 : Int(armAt))
    }

    private func checkZone(_ ts: Date) {
        guard let z = zone else { return }
        let secs = ts.timeIntervalSince(z.t0)
        guard secs > 10, z.dist >= 100 else { return }
        let avg = z.dist / secs * 3.6
        if avg > Double(z.limit) + margin && Date().timeIntervalSince(z.warnedAt) > 20 {
            z.warnedAt = Date()
            notify("⚠️ تێکڕای خێراییت زیاترە", "تێکڕا \(Int(avg.rounded())) — سنوور \(z.limit) km/h", sound: "avg")
        }
    }

    // MARK: alert output

    private func notify(_ title: String, _ body: String, sound: String) {
        let played = playSound(sound)
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        // if the app already played the sound itself, the banner stays silent (no double sound)
        if !played && soundOn { content.sound = UNNotificationSound(named: UNNotificationSoundName(sound + ".wav")) }
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        let req = UNNotificationRequest(identifier: "kamera-" + UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }

    /// Plays the alert through the speaker even when the silent switch is on, and lowers music meanwhile.
    @discardableResult
    private func playSound(_ name: String) -> Bool {
        guard soundOn, let url = Bundle.main.url(forResource: name, withExtension: "wav", subdirectory: "public") else { return false }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try session.setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.volume = 1
            p.prepareToPlay()
            guard p.play() else { return false }
            player = p
            return true
        } catch {
            return false
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Notification sounds must sit in Library/Sounds (or the bundle root) — copy them there once.
    private func installNotificationSounds() {
        let fm = FileManager.default
        guard let lib = fm.urls(for: .libraryDirectory, in: .userDomainMask).first else { return }
        let dir = lib.appendingPathComponent("Sounds")
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        for n in ["warn", "urgent", "avg"] {
            guard let src = Bundle.main.url(forResource: n, withExtension: "wav", subdirectory: "public") else { continue }
            let dst = dir.appendingPathComponent(n + ".wav")
            try? fm.removeItem(at: dst)
            try? fm.copyItem(at: src, to: dst)
        }
    }

    // MARK: script injected into camera.4chra.com

    static let hookJS = """
    (function () {
      if (window.__kgInstalled) return; window.__kgInstalled = true;
      // In the iPhone app the alert sounds are played natively (KameraGuard.playSound), so the
      // page's "tap the screen to turn on sound" hint (Web Audio unlock) is not needed here.
      try { var st = document.createElement('style'); st.textContent = '#tapSound{display:none!important}';
            (document.head || document.documentElement).appendChild(st); } catch (e) {}
      function post(m) { try { window.webkit.messageHandlers.kameraGuard.postMessage(m); } catch (e) {} }
      function read(k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
      function state(cmd) {
        var kc = window.__kc;
        return { cmd: cmd, settings: read('kc_settings', {}), userCams: read('kc_user_cams', []),
                 armed: !!(kc && kc.S && kc.S.armed) };
      }
      function hook() {
        var C = window.Capacitor;
        if (!C || typeof C.nativePromise !== 'function' || typeof C.nativeCallback !== 'function') return false;
        if (C.__kg) return true; C.__kg = true;
        var np = C.nativePromise, nc = C.nativeCallback;
        C.nativePromise = function (p, m) {
          if (p === 'LocalNotifications' && m === 'schedule') return Promise.resolve({ notifications: [] });
          if (p === 'BackgroundGeolocation' && m === 'removeWatcher') post({ cmd: 'stop' });
          return np.apply(C, arguments);
        };
        C.nativeCallback = function (p, m) {
          if (p === 'BackgroundGeolocation' && m === 'addWatcher') post(state('start'));
          return nc.apply(C, arguments);
        };
        return true;
      }
      if (!hook()) { var n = 0, t = setInterval(function () { if (hook() || ++n > 200) clearInterval(t); }, 50); }
      var si = Storage.prototype.setItem;
      Storage.prototype.setItem = function (k) {
        var r = si.apply(this, arguments);
        if (k === 'kc_settings' || k === 'kc_user_cams') post(state('settings'));
        return r;
      };
    })();
    """
}
