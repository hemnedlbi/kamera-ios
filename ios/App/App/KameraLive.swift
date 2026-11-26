/*
 کامێرا — Live Activity on the lock screen and in the Dynamic Island
 Design & Development Hemn A.Muhammad

 Started when alerts start (iOS only allows that while the app is on screen), updated from
 KameraGuard as the car moves, ended on Stop. iOS 16.1+; does nothing on older iPhones.
*/

import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

enum KameraLive {
    static func start() {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *) { Impl.start() }
        #endif
    }

    static func update(dist: Double?, limit: Int?, kmh: Double, over: Bool, zoneAvg: Int, zoneLimit: Int) {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *) {
            Impl.update(dist: dist, limit: limit, kmh: kmh, over: over, zoneAvg: zoneAvg, zoneLimit: zoneLimit)
        }
        #endif
    }

    static func end() {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *) { Impl.end() }
        #endif
    }

    #if canImport(ActivityKit)
    @available(iOS 16.2, *)
    private enum Impl {
        typealias State = KameraActivityAttributes.ContentState
        static var activity: Activity<KameraActivityAttributes>?
        static var last: State?
        static var lastAt = Date.distantPast

        static func start() {
            guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
            // an activity left over from an earlier run (app closed) is replaced
            for a in Activity<KameraActivityAttributes>.activities where a.id != activity?.id {
                Task { await a.end(nil, dismissalPolicy: .immediate) }
            }
            if activity != nil { return }
            let s = State(dist: -1, limit: 0, kmh: 0, over: false, zoneAvg: 0, zoneLimit: 0)
            do {
                activity = try Activity.request(attributes: KameraActivityAttributes(),
                                                content: ActivityContent(state: s, staleDate: nil),
                                                pushType: nil)
                last = s
                lastAt = Date()
            } catch {
                activity = nil   // not allowed right now (e.g. app not on screen): alerts still work
            }
        }

        static func update(dist: Double?, limit: Int?, kmh: Double, over: Bool, zoneAvg: Int, zoneLimit: Int) {
            guard let act = activity else { return }
            let d = dist.map { Int(($0 / 10).rounded()) * 10 } ?? -1
            let s = State(dist: d, limit: limit ?? 0, kmh: max(0, Int(kmh.rounded())), over: over,
                          zoneAvg: zoneAvg, zoneLimit: zoneLimit)
            guard s != last else { return }
            // important changes at once; small ones (speed, a few metres) at most every 3 s
            let important = last == nil || (s.dist < 0) != (last!.dist < 0) || s.over != last!.over
                || (s.zoneLimit > 0) != (last!.zoneLimit > 0)
            guard important || Date().timeIntervalSince(lastAt) >= 3 else { return }
            last = s
            lastAt = Date()
            Task { await act.update(ActivityContent(state: s, staleDate: Date().addingTimeInterval(120))) }
        }

        static func end() {
            let acts = Activity<KameraActivityAttributes>.activities
            activity = nil
            last = nil
            for a in acts { Task { await a.end(nil, dismissalPolicy: .immediate) } }
        }
    }
    #endif
}
