/*
 کامێرا — CarPlay (Driving Task app)
 Design & Development Hemn A.Muhammad

 Shows TCK on the car screen: whether camera alerts are on, the speed, the next camera ahead
 with its distance and limit, and the average-speed zone. The alert sound already plays through
 the car speakers (the phone's audio goes to the car), and the alert banners also appear on the
 car screen because KameraGuard asks for the .carPlay notification permission.

 KameraGuard is the single source of truth; it reports every change to KameraCar.update(...),
 and the CarPlay scene redraws its information template at most once a second.

 Driving Task apps may only use Apple's templates (no free map drawing), so this is a
 CPInformationTemplate with Start / Stop buttons.
*/

import UIKit
import CarPlay

// MARK: - Live state shared between KameraGuard and the CarPlay screen

enum KameraCar {
    struct State: Equatable {
        var enabled = false     // alerts on (the person has not pressed Stop)
        var armAt = 0           // > 0: alerts arm when the car reaches this speed
        var kmh = 0
        var dist: Int?          // metres to the next camera ahead (nil = none within alert distance)
        var limit: Int?
        var over = false
        var zoneAvg = 0
        var zoneLimit = 0
    }

    static let changed = Notification.Name("KameraCar.changed")
    private(set) static var state = State()

    static func update(dist: Double?, limit: Int?, kmh: Double, over: Bool, zoneAvg: Int, zoneLimit: Int, armAt: Int) {
        var s = state
        s.enabled = KameraGuard.shared.isEnabled
        s.kmh = Int(kmh.rounded())
        s.dist = dist.map { max(0, Int(($0 / 10).rounded()) * 10) }
        s.limit = limit
        s.over = over
        s.zoneAvg = zoneAvg
        s.zoneLimit = zoneLimit
        s.armAt = armAt
        set(s)
    }

    /// Alerts were switched on or off (from the phone or from the car).
    static func refreshEnabled() {
        var s = state
        s.enabled = KameraGuard.shared.isEnabled
        if !s.enabled { s = State() }
        set(s)
    }

    private static func set(_ s: State) {
        guard s != state else { return }
        state = s
        NotificationCenter.default.post(name: changed, object: nil)
    }
}

// MARK: - CarPlay scene

final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interface: CPInterfaceController?
    private var template: CPInformationTemplate?
    private var lastDraw = Date.distantPast
    private var pendingDraw = false
    private var shownOver = false

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController) {
        interface = interfaceController
        let t = CPInformationTemplate(title: "TCK", layout: .leading, items: [], actions: [])
        template = t
        draw()
        interfaceController.setRootTemplate(t, animated: false, completion: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(stateChanged),
                                               name: KameraCar.changed, object: nil)
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        NotificationCenter.default.removeObserver(self)
        interface = nil
        template = nil
    }

    @objc private func stateChanged() {
        DispatchQueue.main.async { [weak self] in self?.scheduleDraw() }
    }

    /// Redraw at most once a second (GPS can report several positions a second).
    private func scheduleDraw() {
        let wait = 1 - Date().timeIntervalSince(lastDraw)
        if wait <= 0 { draw(); return }
        guard !pendingDraw else { return }
        pendingDraw = true
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            self?.pendingDraw = false
            self?.draw()
        }
    }

    private func draw() {
        guard let t = template else { return }
        lastDraw = Date()
        let s = KameraCar.state
        var items: [CPInformationItem] = []

        if !s.enabled {
            t.title = "TCK — ئاگادارکردنەوە کوژاوەیە"
            items.append(CPInformationItem(title: "ئاگادارکردنەوەی کامێرا", detail: "کوژاوەیە — «دەستپێکردن» دابگرە"))
            t.items = items
            t.actions = [CPTextButton(title: "دەستپێکردن", textStyle: .confirm) { _ in
                KameraGuard.shared.startFromCar()
            }]
            return
        }

        t.title = s.over ? "⚠️ خێراییت کەم بکەرەوە!" : "TCK — ئاگادارکردنەوە چالاکە"
        items.append(CPInformationItem(title: "خێرایی", detail: "\(s.kmh) km/h"))
        if s.armAt > 0 {
            items.append(CPInformationItem(title: "چاوەڕوانی", detail: "لە \(s.armAt) km/h ئاگادارکردنەوە دەست پێدەکات"))
        } else if let d = s.dist {
            let dText = d >= 1000 ? String(format: "%.1f کم", Double(d) / 1000) : "\(d) م"
            items.append(CPInformationItem(title: "📷 کامێرا لە پێشەوە", detail: dText))
            if let l = s.limit { items.append(CPInformationItem(title: "سنووری خێرایی", detail: "\(l) km/h")) }
        } else {
            items.append(CPInformationItem(title: "کامێرا", detail: "هیچ کامێرایەک لە نزیکەوە نییە"))
        }
        if s.zoneLimit > 0 {
            let avg = s.zoneAvg > 0 ? "\(s.zoneAvg) km/h" : "…"
            items.append(CPInformationItem(title: "تێکڕای خێرایی", detail: "\(avg) — سنوور \(s.zoneLimit) km/h"))
        }
        t.items = items
        t.actions = [CPTextButton(title: "ڕاگرتن", textStyle: .cancel) { _ in
            KameraGuard.shared.stop()
        }]

        // A short alert on the car screen the moment the driver goes over the limit near a camera.
        if s.over && !shownOver {
            shownOver = true
            showOverAlert(s)
        } else if !s.over {
            shownOver = false
        }
    }

    private func showOverAlert(_ s: KameraCar.State) {
        guard let ui = interface, ui.presentedTemplate == nil else { return }
        let limit = s.limit.map { "سنوور \($0) km/h" } ?? "خێراییت کەم بکەرەوە"
        let alert = CPAlertTemplate(titleVariants: ["⚠️ خێراییت کەم بکەرەوە! \(limit)", "⚠️ \(limit)"],
                                    actions: [CPAlertAction(title: "باشە", style: .default) { [weak ui] _ in
                                        ui?.dismissTemplate(animated: true, completion: nil)
                                    }])
        ui.presentTemplate(alert, animated: true, completion: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak ui] in
            guard let ui, ui.presentedTemplate === alert else { return }
            ui.dismissTemplate(animated: true, completion: nil)
        }
    }
}
