import Foundation

/// Shared by the app and the lock-screen widget through the App Group the build provisions
/// (logical name "nativeext", expanded into DESPIA_NX_APP_GROUPS in each bundle's Info.plist).
struct RecorderStatus: Codable, Equatable {
    var recordingId: String?
    var state: String
    var durationMs: Int
    var updatedAt: Date

    static let idle = RecorderStatus(recordingId: nil, state: "idle", durationMs: 0, updatedAt: Date(timeIntervalSince1970: 0))
    static let defaultsKey = "nx.recorder.status"

    static func groupIdentifier() -> String? {
        (Bundle.main.object(forInfoDictionaryKey: "DESPIA_NX_APP_GROUPS") as? [String: String])?["nativeext"]
    }

    static func load() -> RecorderStatus {
        guard let id = groupIdentifier(), let defaults = UserDefaults(suiteName: id),
              let data = defaults.data(forKey: defaultsKey),
              let status = try? JSONDecoder().decode(RecorderStatus.self, from: data) else { return .idle }
        return status
    }

    func save() {
        guard let id = Self.groupIdentifier(), let defaults = UserDefaults(suiteName: id),
              let data = try? JSONEncoder().encode(self) else { return }
        defaults.set(data, forKey: Self.defaultsKey)
    }
}
