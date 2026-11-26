/*
 کامێرا — Live Activity data (shared by the app and the widget extension)
 Design & Development Hemn A.Muhammad
*/

import Foundation
#if canImport(ActivityKit)
import ActivityKit

@available(iOS 16.1, *)
struct KameraActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        /// metres to the next camera ahead within the alert distance, -1 when none
        var dist: Int
        /// speed limit of that camera (km/h), 0 when unknown
        var limit: Int
        /// current speed (km/h)
        var kmh: Int
        /// faster than the limit (+ margin)
        var over: Bool
        /// average-speed zone: current average and limit (km/h), 0 when not in a zone
        var zoneAvg: Int
        var zoneLimit: Int
    }
}
#endif
