/*
 کامێرا — Live Activity views (lock screen + Dynamic Island)
 Design & Development Hemn A.Muhammad
*/

import ActivityKit
import SwiftUI
import WidgetKit

@main
struct KameraWidgetBundle: WidgetBundle {
    var body: some Widget {
        KameraLiveActivity()
    }
}

private let green = Color(red: 0.086, green: 0.639, blue: 0.290)   // #16A34A
private let red = Color(red: 0.863, green: 0.149, blue: 0.149)     // #DC2626
private let amber = Color(red: 0.961, green: 0.620, blue: 0.043)   // #F59E0B

private func stateColor(_ s: KameraActivityAttributes.ContentState) -> Color {
    if s.over { return red }
    if s.zoneLimit > 0 && s.zoneAvg > s.zoneLimit { return amber }
    return green
}

private func distText(_ m: Int) -> String {
    m >= 1000 ? String(format: "%.1f کم", Double(m) / 1000) : "\(m) م"
}

/// The main line in Kurdish, right to left.
private func headline(_ s: KameraActivityAttributes.ContentState) -> String {
    if s.dist >= 0 { return s.over ? "خێراییت کەم بکەرەوە!" : "کامێرا لە پێشەوەیە" }
    if s.zoneLimit > 0 { return "ناوچەی تێکڕای خێرایی" }
    return "ئاگاداری کامێرا چالاکە"
}

private func detail(_ s: KameraActivityAttributes.ContentState) -> String {
    if s.dist >= 0 {
        return s.limit > 0 ? "\(distText(s.dist)) — سنوور \(s.limit) km/h" : distText(s.dist)
    }
    if s.zoneLimit > 0 { return "تێکڕا \(s.zoneAvg) — سنوور \(s.zoneLimit) km/h" }
    return "TCK – Traffic Camera Kurdistan"
}

struct KameraLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: KameraActivityAttributes.self) { context in
            LockScreenView(s: context.state)
                .activityBackgroundTint(Color.black.opacity(0.75))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    SpeedBadge(s: s).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if s.limit > 0 || s.zoneLimit > 0 {
                        LimitSign(limit: s.limit > 0 ? s.limit : s.zoneLimit).padding(.trailing, 4)
                    } else {
                        Image(systemName: "camera.fill").foregroundColor(stateColor(s)).font(.title2).padding(.trailing, 4)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 2) {
                        Text(headline(s)).font(.headline).foregroundColor(stateColor(s))
                        Text(detail(s)).font(.subheadline).foregroundColor(.white.opacity(0.85))
                    }
                    .environment(\.layoutDirection, .rightToLeft)
                    .frame(maxWidth: .infinity)
                }
            } compactLeading: {
                Image(systemName: "camera.fill").foregroundColor(stateColor(s))
            } compactTrailing: {
                Text(s.dist >= 0 ? distText(s.dist) : "\(s.kmh)")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundColor(stateColor(s))
            } minimal: {
                Image(systemName: "camera.fill").foregroundColor(stateColor(s))
            }
        }
    }
}

private struct LockScreenView: View {
    let s: KameraActivityAttributes.ContentState
    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .trailing, spacing: 4) {
                Text(headline(s)).font(.headline).foregroundColor(stateColor(s))
                Text(detail(s)).font(.subheadline).foregroundColor(.white.opacity(0.9))
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            if s.limit > 0 || s.zoneLimit > 0 { LimitSign(limit: s.limit > 0 ? s.limit : s.zoneLimit) }
            SpeedBadge(s: s)
        }
        .environment(\.layoutDirection, .rightToLeft)
        .padding(14)
    }
}

/// Current speed in a coloured ring.
private struct SpeedBadge: View {
    let s: KameraActivityAttributes.ContentState
    var body: some View {
        VStack(spacing: 0) {
            Text("\(s.kmh)").font(.title2.weight(.bold).monospacedDigit()).foregroundColor(.white)
            Text("km/h").font(.system(size: 9, weight: .medium)).foregroundColor(.white.opacity(0.7))
        }
        .frame(width: 56, height: 56)
        .overlay(Circle().stroke(stateColor(s), lineWidth: 4))
    }
}

/// Round speed-limit sign (red ring, number in the middle).
private struct LimitSign: View {
    let limit: Int
    var body: some View {
        Text("\(limit)")
            .font(.headline.weight(.heavy).monospacedDigit())
            .foregroundColor(.black)
            .frame(width: 44, height: 44)
            .background(Circle().fill(Color.white))
            .overlay(Circle().stroke(red, lineWidth: 5))
    }
}
