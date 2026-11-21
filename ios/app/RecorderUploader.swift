import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One recording in `index.json`. The fields after `httpStatus` were added for the upload queue;
/// they are optional so an index written before them decodes unchanged, and nil fields are not
/// encoded, so the file stays readable by the older format.
struct RecorderRecord: Codable, Equatable {
    var id: String
    /// starting (indexed before any audio exists), recording, interrupted, uploading, uploaded, failed,
    /// terminated, discarding (a tombstone)
    var state: String
    var startedAt: Date
    var durationMs: Int
    var bytes: Int64
    var uploadURL: String
    var httpStatus: Int?
    /// The route that supplied `uploadURL` ("start" or "upload"); nil means "start".
    var urlRoute: String?
    /// Counted attempts; nil means 0.
    var attempts: Int?
    var nextAttemptAt: Date?
    /// Why a record is failed: redirect_refused, http, network, url_not_approved, no_file.
    var reason: String?
}

/// `index.json`: the records keyed by id (the format every earlier version wrote and reads), plus,
/// once an index was set aside on this install, the reserved key `"indexRecovered": "moved_aside"`
/// (ids are UUIDs, so they never collide with it). Without the flag the file is in the earlier
/// format; with it an earlier version would itself set the file aside, which loses nothing. The
/// format is iOS's own (Android keeps `{"records": ..., "indexRecovered": ...}`); the flag's meaning
/// and every route code are the same on both.
struct RecorderIndexFile: Codable {
    static let recoveredKey = "indexRecovered"

    var records: [String: RecorderRecord] = [:]
    var indexRecovered: String?

    init(records: [String: RecorderRecord] = [:], indexRecovered: String? = nil) {
        self.records = records
        self.indexRecovered = indexRecovered
    }

    private struct Key: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        for key in c.allKeys {
            if key.stringValue == RecorderIndexFile.recoveredKey {
                indexRecovered = try c.decode(String.self, forKey: key)
            } else {
                records[key.stringValue] = try c.decode(RecorderRecord.self, forKey: key)
            }
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        for (id, r) in records { try c.encode(r, forKey: Key(stringValue: id)) }
        if let flag = indexRecovered { try c.encode(flag, forKey: Key(stringValue: RecorderIndexFile.recoveredKey)) }
    }
}

/// Which records' last `state` event said `durable: false`. When a later write of the index
/// succeeds, `saved()` hands each of them back exactly once, so the owner emits that record's
/// state again, now durable (no `durable` field). An event that already reports a record's current
/// state supersedes its pending one (`willEmit` before the write, `emitted` after it).
struct RecorderDurableEvents {
    private(set) var pending: Set<String> = []

    /// Before the write that precedes an event for `id`: that event will report the current state.
    mutating func willEmit(_ id: String) {
        pending.remove(id)
    }

    /// After the event for `id` went out, with the durability it reported.
    mutating func emitted(_ id: String, durable: Bool) {
        if durable { pending.remove(id) } else { pending.insert(id) }
    }

    /// After a write succeeded: the ids whose now-durable state must be emitted, once.
    mutating func saved() -> [String] {
        let ids = pending.sorted()
        pending = []
        return ids
    }
}

/// The durable index. Every `uploading` record in it is queued or in flight.
///
/// Durability is reported, never assumed: `durable` is true only while the records in memory are
/// the ones last written to `index.json`. A failed write keeps the records in memory (so the
/// upload and the page state go on in this process), sets `durable` to false with `failure`, and
/// the next `persist()` writes everything again. An index that exists but cannot be read is never
/// overwritten: nothing is written until it has been read and merged (records changed in this
/// process win). One that reads but is not an index is copied aside byte for byte to
/// `index.unreadable-<uuid>.json` (an identical earlier copy is reused; copies are never deleted) and
/// only then replaced, atomically, by a fresh index that carries `indexRecovered: "moved_aside"`.
/// The flag is part of every later write and is never cleared, so it survives a crash after the
/// copy and every relaunch: the aside copy may list recordings this index no longer knows.
///
/// `justLoaded` runs once, inside the load that first reads the index (at launch, or at any later
/// save or read once the index becomes readable), after the records were merged and adopted and
/// before that load returns: the owner finishes leftover tombstones there, before anything builds
/// on the index.
final class RecorderStore {
    static let movedAsideFlag = "moved_aside"

    let directory: URL
    private(set) var records: [String: RecorderRecord] = [:]
    var latest: String?
    private(set) var durable = false
    /// Why the records are not durable: index_unreadable, index_encode_failed or index_write_failed.
    private(set) var failure: String?
    /// "moved_aside" once an index that was not an index was set aside on this install (persisted).
    private(set) var recovered: String?
    /// Where this process set an index aside (for the log only; `recovered` is the durable fact).
    private(set) var movedAside: URL?
    private var loaded = false
    /// False while the index exists but has not been read: no record is known to be absent.
    var isLoaded: Bool { loaded }
    private let adopt: (inout RecorderRecord) -> Void
    private let justLoaded: ((RecorderStore) -> Void)?
    private let readIndex: (URL) throws -> Data
    private let writeIndex: (Data, URL) throws -> Void

    /// `adopt` runs once on every record read from disk, before it is merged: those records were
    /// written by an earlier process. `readIndex` and `writeIndex` are injected by tests only.
    init(directory: URL,
         adopt: @escaping (inout RecorderRecord) -> Void = { _ in },
         justLoaded: ((RecorderStore) -> Void)? = nil,
         readIndex: @escaping (URL) throws -> Data = { try Data(contentsOf: $0) },
         writeIndex: @escaping (Data, URL) throws -> Void = {
             try $0.write(to: $1, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
         }) {
        self.directory = directory
        self.adopt = adopt
        self.justLoaded = justLoaded
        self.readIndex = readIndex
        self.writeIndex = writeIndex
    }

    private var indexURL: URL { directory.appendingPathComponent("index.json") }

    /// Reads the index once. Returns false, and leaves the index untouched, when it exists but
    /// cannot be read now (for example while the device has not been unlocked since boot), or when
    /// an index that is not an index could not yet be copied aside and replaced.
    @discardableResult
    func load() -> Bool {
        if loaded { return true }
        let data: Data
        do {
            data = try readIndex(indexURL)
        } catch {
            guard RecorderStore.isMissing(error) else {
                failure = "index_unreadable"
                durable = false
                return false
            }
            return loadedIndex(RecorderIndexFile())
        }
        do {
            return loadedIndex(try JSONDecoder().decode(RecorderIndexFile.self, from: data))
        } catch {
            // Not an index: set aside below.
        }
        // Not an index: the aside copy first, then the fresh index that records the fact. Either
        // failing changes nothing; the next load repeats it (reusing an identical copy).
        guard let aside = asideCopy(data) else {
            failure = "index_unreadable"
            durable = false
            return false
        }
        let fresh = RecorderIndexFile(indexRecovered: RecorderStore.movedAsideFlag)
        do {
            try writeIndex(try JSONEncoder().encode(fresh), indexURL)
        } catch {
            failure = "index_write_failed"
            durable = false
            return false
        }
        movedAside = aside
        return loadedIndex(fresh)
    }

    private func loadedIndex(_ file: RecorderIndexFile) -> Bool {
        for (id, record) in file.records where records[id] == nil {
            var r = record
            adopt(&r)
            records[id] = r
        }
        if recovered == nil { recovered = file.indexRecovered }
        if latest == nil { latest = records.values.max { $0.startedAt < $1.startedAt }?.id }
        loaded = true
        durable = false
        justLoaded?(self)
        return true
    }

    /// A byte-for-byte copy of an index that is not an index; an identical earlier copy is reused.
    /// The directory is listed only to find such a copy, never to delete anything.
    private func asideCopy(_ data: Data) -> URL? {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names.sorted() where name.hasPrefix("index.unreadable-") && name.hasSuffix(".json") {
            let old = directory.appendingPathComponent(name)
            if let bytes = try? readIndex(old), bytes == data { return old }
        }
        let aside = directory.appendingPathComponent("index.unreadable-\(UUID().uuidString.lowercased()).json")
        do {
            try writeIndex(data, aside)
        } catch {
            return nil
        }
        return aside
    }

    /// Writes every record. Returns whether `index.json` now holds exactly the records in memory.
    @discardableResult
    func persist() -> Bool {
        guard load() else { return false }
        let data: Data
        do {
            data = try JSONEncoder().encode(RecorderIndexFile(records: records, indexRecovered: recovered))
        } catch {
            failure = "index_encode_failed"
            durable = false
            return false
        }
        do {
            try writeIndex(data, indexURL)
        } catch {
            failure = "index_write_failed"
            durable = false
            return false
        }
        failure = nil
        durable = true
        return true
    }

    subscript(id: String) -> RecorderRecord? { records[id] }

    func set(_ r: RecorderRecord) {
        records[r.id] = r
        durable = false
    }

    func remove(_ id: String) {
        records[id] = nil
        if latest == id { latest = nil }
        durable = false
    }

    enum DiscardOutcome: Equatable {
        /// The file is gone and the record removed (durable only when `durable` is true after it).
        case discarded
        /// The tombstone could not be saved: nothing changed.
        case notSaved
        /// Removal was not established (the directory is still listed, or the recorder directory
        /// could not be listed to confirm its absence): the saved tombstone stays for a later
        /// discard or recovery.
        case fileKept
    }

    /// Crash-consistent discard: the record is saved as a `discarding` tombstone, then its
    /// directory is deleted, and only then is the record removed. A process death at any point
    /// leaves either the recording intact or a tombstone that `recoverDiscards` finishes; audio is
    /// never left on disk without an index entry. `removeItem` and `listDirectory` are injected by
    /// tests only. Absence is confirmed only by listing the recorder directory: `fileExists` is also
    /// false when the lookup itself fails, so it never decides.
    func discard(_ id: String,
                 removeItem: (URL) throws -> Void = { try FileManager.default.removeItem(at: $0) },
                 listDirectory: (URL) throws -> [String] = RecorderStore.listNames) -> DiscardOutcome {
        guard let r = records[id] else { return .discarded }
        if r.state != "discarding" {
            var t = r
            t.state = "discarding"
            set(t)
            guard persist() else {
                set(r)
                return .notSaved
            }
        }
        let dir = fileURL(id).deletingLastPathComponent()
        do {
            try removeItem(dir)
        } catch {
            // Not removed by this call: confirmed absent only when the parent lists and lacks it.
            guard let names = try? listDirectory(directory), !names.contains(id) else { return .fileKept }
        }
        remove(id)
        persist()
        return .discarded
    }

    /// The names in a directory, only to confirm a tombstoned recording's directory is absent.
    static func listNames(_ url: URL) throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: url.path)
    }

    /// Finishes every discard an earlier process left as a tombstone; returns the ids finished.
    func recoverDiscards(removeItem: (URL) throws -> Void = { try FileManager.default.removeItem(at: $0) },
                         listDirectory: (URL) throws -> [String] = RecorderStore.listNames) -> [String] {
        records.values.filter { $0.state == "discarding" }.map { $0.id }.sorted()
            .filter { discard($0, removeItem: removeItem, listDirectory: listDirectory) == .discarded }
    }

    func fileURL(_ id: String) -> URL {
        directory.appendingPathComponent(id, isDirectory: true).appendingPathComponent("audio.m4a")
    }

    private static func isMissing(_ error: Error) -> Bool {
        let e = error as NSError
        if e.domain == NSCocoaErrorDomain && e.code == NSFileReadNoSuchFileError { return true }
        if e.domain == NSPOSIXErrorDomain && e.code == Int(ENOENT) { return true }
        if let u = e.userInfo[NSUnderlyingErrorKey] as? NSError, u.domain == NSPOSIXErrorDomain, u.code == Int(ENOENT) {
            return true
        }
        return false
    }
}

/// Uploads queued recordings with an in-process ephemeral session whose delegate refuses every
/// HTTP redirect, so the body and `X-Recording-Id` reach only the host of the validated URL.
/// Uploads run only while this process runs; a terminated or suspended process leaves the record
/// `uploading` with its file, and the next `drain()` restarts it from byte 0.
///
/// Main thread only: every method, the delegate queue (`.main`) and the backoff timer share it.
final class RecorderUploader: NSObject, URLSessionTaskDelegate, URLSessionDataDelegate {
    private struct Attempt {
        let id: String
        let token: String
        let task: URLSessionTask
        var spansBackground: Bool
    }

    private let store: RecorderStore
    private let isApproved: (_ url: String, _ route: String) -> Bool
    private let changed: (_ id: String) -> Void
    private let configuration: URLSessionConfiguration
    private let now: () -> Date
    private let backoffBase: TimeInterval
    private let backoffCap: TimeInterval
    private let maxAttempts: Int
    private var current: Attempt?
    /// The token of the attempt whose redirect this delegate refused; only that makes a 3xx
    /// `redirect_refused`.
    private var refusedToken: String?
    /// Between didEnterBackground() and becameActive(); every attempt started then spans background.
    private var inBackground: Bool
    private var timer: Timer?
    private var invalidated = false
    private lazy var session: URLSession = URLSession(configuration: self.configuration, delegate: self, delegateQueue: .main)

    /// Called on the main queue when nothing is in flight and nothing is eligible now.
    var onIdle: (() -> Void)?

    /// `configuration` is injected by tests only; nil selects `productionConfiguration()`.
    /// `inBackground` is the application state at creation (true for a background launch).
    init(store: RecorderStore,
         isApproved: @escaping (_ url: String, _ route: String) -> Bool,
         changed: @escaping (_ id: String) -> Void,
         inBackground: Bool = false,
         configuration: URLSessionConfiguration? = nil,
         now: @escaping () -> Date = Date.init,
         backoffBase: TimeInterval = 30,
         backoffCap: TimeInterval = 15 * 60,
         maxAttempts: Int = 5) {
        self.store = store
        self.isApproved = isApproved
        self.changed = changed
        self.inBackground = inBackground
        self.configuration = configuration ?? RecorderUploader.productionConfiguration()
        self.now = now
        self.backoffBase = backoffBase
        self.backoffCap = backoffCap
        self.maxAttempts = maxAttempts
        super.init()
    }

    /// Ephemeral so the redirect delegate is honoured (a daemon-run session follows redirects
    /// without asking). Default system trust: no challenge handling here.
    static func productionConfiguration() -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.waitsForConnectivity = true
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 1800
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.urlCredentialStorage = nil
        c.httpMaximumConnectionsPerHost = 1
        return c
    }

    /// Delay before counted attempt `attempt + 1`: base * 2^(attempt-1), capped.
    static func backoffDelay(attempt: Int, base: TimeInterval, cap: TimeInterval) -> TimeInterval {
        min(cap, base * pow(2, Double(max(attempt, 1) - 1)))
    }

    var hasWork: Bool { store.records.values.contains { $0.state == "uploading" } }

    var isBusy: Bool { current != nil }

    /// The token of the attempt in flight, `<id>#<uuid>`.
    var inFlightToken: String? { current?.token }

    /// The upload route accepts only a failed or uploaded record, which has no attempt in flight.
    func enqueue(_ id: String, fromUploadRoute: Bool) {
        assert(Thread.isMainThread)
        if fromUploadRoute, var r = store[id] {
            r.attempts = 0
            r.nextAttemptAt = nil
            r.reason = nil
            r.httpStatus = nil
            store.set(r)
            changed(id)
        }
        drain()
    }

    func drain() {
        assert(Thread.isMainThread)
        guard current == nil, !invalidated else { return }
        timer?.invalidate()
        timer = nil
        let t = now()
        let queued = store.records.values
            .filter { $0.state == "uploading" }
            .sorted { ($0.startedAt, $0.id) < ($1.startedAt, $1.id) }
        for candidate in queued {
            // `changed` may re-enter drain(); re-read the record and stop once something runs.
            guard current == nil else { return }
            guard let r = store[candidate.id], r.state == "uploading" else { continue }
            if let due = r.nextAttemptAt, due > t { continue }
            if start(r) { return }
        }
        guard current == nil else { return }
        scheduleTimer()
        onIdle?()
    }

    func becameActive() {
        assert(Thread.isMainThread)
        inBackground = false
        for r in store.records.values where r.state == "uploading" && r.nextAttemptAt != nil {
            var u = r
            u.nextAttemptAt = nil
            store.set(u)
            changed(u.id)
        }
        drain()
    }

    /// Background time ran out or was not granted: the attempt is abandoned uncounted and the
    /// record stays queued for the next drain().
    func expireInFlight() {
        assert(Thread.isMainThread)
        guard let a = current else { return }
        current = nil
        a.task.cancel()
        if var r = store[a.id], r.state == "uploading" {
            r.nextAttemptAt = nil
            store.set(r)
        }
        changed(a.id)
    }

    /// A transport error on the attempt now in flight, or on any attempt started before the next
    /// becameActive(), is then not counted.
    func didEnterBackground() {
        assert(Thread.isMainThread)
        inBackground = true
        current?.spansBackground = true
    }

    func invalidate() {
        invalidated = true
        timer?.invalidate()
        timer = nil
        current = nil
        session.invalidateAndCancel()
    }

    /// Returns false when the record was failed without a request.
    private func start(_ r: RecorderRecord) -> Bool {
        let id = r.id
        guard isApproved(r.uploadURL, r.urlRoute ?? "start"),
              let target = RecorderUploader.destination(r.uploadURL) else {
            settle(id, state: "failed", reason: "url_not_approved", status: nil)
            return false
        }
        let file = store.fileURL(id)
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? 0
        guard size > 0 else {
            settle(id, state: "failed", reason: "no_file", status: nil)
            return false
        }
        var request = URLRequest(url: target)
        request.httpMethod = "PUT"
        request.setValue("audio/mp4", forHTTPHeaderField: "Content-Type")
        request.setValue(id, forHTTPHeaderField: "X-Recording-Id")
        let task = session.uploadTask(with: request, fromFile: file)
        let token = id + "#" + UUID().uuidString.lowercased()
        task.taskDescription = token
        refusedToken = nil
        current = Attempt(id: id, token: token, task: task, spansBackground: inBackground)
        var u = r
        u.bytes = size
        u.nextAttemptAt = nil
        if u != r {
            store.set(u)
            changed(id)
        }
        task.resume()
        return true
    }

    /// The URL the request is built from, only when Foundation's parse agrees with the text the
    /// https_url rule approved: the raw text is a strict URL text first (`isStrictURLText`, before
    /// any parse, so nothing is repaired), then https, no user or password, no fragment, the default
    /// port (or an explicit 443), and a parsed host of letters, digits, dots and hyphens equal to the
    /// literal host after "https://". Whether that host is approved is the https_url rule's decision,
    /// made before.
    static func destination(_ s: String) -> URL? {
        guard isStrictURLText(s) else { return nil }
        let hostCharacters = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")
        guard let url = URL(string: s), url.scheme?.lowercased() == "https", url.user == nil, url.password == nil,
              url.fragment == nil, url.port == nil || url.port == 443,
              let host = url.host?.lowercased(), !host.isEmpty,
              host.unicodeScalars.allSatisfy({ hostCharacters.contains($0) }) else { return nil }
        let rest = s.lowercased().dropFirst("https://".count)
        guard s.lowercased().hasPrefix("https://"), rest.hasPrefix(host) else { return nil }
        let after = rest.dropFirst(host.count)
        guard after.isEmpty || after.hasPrefix("/") || after == ":443" || after.hasPrefix(":443/") else { return nil }
        return url
    }

    /// The characters RFC 3986 allows in a URL, plus "%" (checked as an escape separately).
    private static let urlTextCharacters: Set<UInt8> = Set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%".utf8)

    /// The raw text, before any parse: ASCII only, and only characters a URL may contain. Whitespace
    /// (leading, trailing or inside), C0 and C1 controls, DEL, invisible and bidi code points and
    /// every other non-ASCII character are refused, as are characters a parser would percent-encode
    /// (`"<>\^`{|}`). Every "%" must start a two-hex-digit escape, so an existing escape stays
    /// exactly as given and no "%" is re-encoded. Darwin's URL(string:) repairs such input (a
    /// trailing space became "%20"), so this check, not the parse, decides.
    static func isStrictURLText(_ s: String) -> Bool {
        guard !s.isEmpty, s.unicodeScalars.allSatisfy({ $0.isASCII }) else { return false }
        let bytes = Array(s.utf8)
        let hex = Set("0123456789ABCDEFabcdef".utf8)
        var i = 0
        while i < bytes.count {
            let b = bytes[i]
            guard urlTextCharacters.contains(b) else { return false }
            if b == UInt8(ascii: "%") {
                guard i + 2 < bytes.count, hex.contains(bytes[i + 1]), hex.contains(bytes[i + 2]) else { return false }
                i += 3
            } else {
                i += 1
            }
        }
        return true
    }

    /// The outcome of one attempt. Results whose token is not the current one are dropped.
    func complete(token: String?, statusCode: Int?, error: Error?) {
        assert(Thread.isMainThread)
        guard let a = current, token == a.token else { return }
        current = nil
        guard let r = store[a.id], r.state == "uploading" else {
            drain()
            return
        }
        let refused = refusedToken == a.token
        refusedToken = nil
        if let code = statusCode, (300..<400).contains(code) {
            // A 3xx the delegate was never asked about (no Location, 304) is a terminal HTTP failure.
            settle(a.id, state: "failed", reason: refused ? "redirect_refused" : "http", status: code)
        } else if let code = statusCode, (400..<500).contains(code) {
            settle(a.id, state: "failed", reason: "http", status: code)
        } else if error == nil, let code = statusCode, (200..<300).contains(code) {
            settle(a.id, state: "uploaded", reason: nil, status: code)
        } else if error != nil, a.spansBackground {
            var u = r
            u.nextAttemptAt = nil
            store.set(u)
            changed(a.id)
        } else {
            retry(r, reason: error == nil ? "http" : "network", status: error == nil ? statusCode : nil)
        }
        drain()
    }

    private func retry(_ r: RecorderRecord, reason: String, status: Int?) {
        var u = r
        let n = (u.attempts ?? 0) + 1
        u.attempts = n
        u.httpStatus = status
        if n >= maxAttempts {
            u.state = "failed"
            u.reason = reason
            u.nextAttemptAt = nil
        } else {
            u.reason = nil
            u.nextAttemptAt = now().addingTimeInterval(RecorderUploader.backoffDelay(attempt: n, base: backoffBase, cap: backoffCap))
        }
        store.set(u)
        changed(u.id)
    }

    private func settle(_ id: String, state: String, reason: String?, status: Int?) {
        guard var r = store[id] else { return }
        r.state = state
        r.reason = reason
        r.httpStatus = status
        r.nextAttemptAt = nil
        store.set(r)
        changed(id)
    }

    private func scheduleTimer() {
        let t = now()
        guard let due = store.records.values
            .filter({ $0.state == "uploading" })
            .compactMap({ $0.nextAttemptAt })
            .filter({ $0 > t })
            .min() else { return }
        let next = Timer(timeInterval: due.timeIntervalSince(t), repeats: false) { [weak self] _ in self?.drain() }
        RunLoop.main.add(next, forMode: .common)
        timer = next
    }

    /// Every redirect is refused, so the 3xx becomes the task's response. The refusal is
    /// recorded for the current attempt only. The target is never logged.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        if let a = current, task.taskDescription == a.token { refusedToken = a.token }
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        complete(token: task.taskDescription, statusCode: (task.response as? HTTPURLResponse)?.statusCode, error: error)
    }
}

// MARK: - Audio session lease

/// The audio session configuration the lease compares: category, mode and options, as raw values.
struct RecorderSessionConfig: Equatable {
    var category: String
    var mode: String
    var options: UInt

    /// Category and mode only: iOS may report category options back differently from how they were
    /// set, so options never decide whose configuration the session holds.
    func sameRoute(_ other: RecorderSessionConfig) -> Bool {
        category == other.category && mode == other.mode
    }
}

/// The three AVAudioSession calls the lease makes; RecorderExtension supplies the real ones, the
/// Swift harness a recording fake.
struct RecorderSessionPort {
    var config: () -> RecorderSessionConfig
    var setConfig: (RecorderSessionConfig) throws -> Void
    var setActive: (_ active: Bool, _ notifyOthers: Bool) throws -> Void
}

enum RecorderSessionRelease: Equatable {
    /// The fixture's own activation was deactivated (other apps notified) and the prior
    /// configuration restored.
    case released
    /// Another client in this process configured or reconfigured the session: nothing deactivated.
    case leftToOtherClient
    /// The fixture never activated: no deactivation was issued.
    case notOwned
    /// A release call threw; the text is for the log.
    case failed(String)
}

/// The recorder's claim on the process-wide audio session. The platform keeps one session per
/// process with no per-client ownership, so the only in-process witness of another client is the
/// configuration: one that is neither the process default nor the recorder's own was set by someone
/// else, and is never deactivated by the recorder. Other apps are no reason to hold the session:
/// releasing with notifyOthers is what lets them resume. Named limit: a co-tenant playing under the
/// default configuration cannot be told from none.
struct RecorderSessionLease {
    static let processDefault = RecorderSessionConfig(category: "AVAudioSessionCategorySoloAmbient",
                                                      mode: "AVAudioSessionModeDefault", options: 0)
    let record: RecorderSessionConfig
    /// True only between the fixture's own setActive(true) returning and its release (or the
    /// system taking the session in an interruption).
    private(set) var owned = false
    private(set) var foreign = false
    private(set) var configChanged = false
    private var prior: RecorderSessionConfig?

    init(record: RecorderSessionConfig) {
        self.record = record
    }

    mutating func acquire(_ port: RecorderSessionPort) throws {
        let before = port.config()
        prior = before
        foreign = !before.sameRoute(record) && !before.sameRoute(Self.processDefault)
        try port.setConfig(record)
        configChanged = true
        try port.setActive(true, false)
        owned = true
    }

    /// An interruption began: the system deactivated the session.
    mutating func lost() {
        owned = false
    }

    /// The fixture's own reactivation after an interruption returned.
    mutating func regained() {
        if prior != nil { owned = true }
    }

    /// Ends the claim; synchronous, no await between the read and the calls. The lease is cleared in
    /// every branch.
    mutating func relinquish(_ port: RecorderSessionPort) -> RecorderSessionRelease {
        defer { self = RecorderSessionLease(record: record) }
        guard let prior = prior else { return .notOwned }
        if configChanged && !port.config().sameRoute(record) { return .leftToOtherClient }
        do {
            if owned && !foreign {
                try port.setActive(false, true)
                if prior != record { try port.setConfig(prior) }
                return .released
            }
            if configChanged && prior != record { try port.setConfig(prior) }
            return owned ? .leftToOtherClient : .notOwned
        } catch {
            return .failed(String(describing: error))
        }
    }
}
