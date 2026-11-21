import AVFAudio
import BackgroundTasks
import Foundation
import UIKit
import WidgetKit

/// Background microphone recorder. Native code owns capture, the files and the upload; the page
/// only ever sees a recording id and its state, never audio and never the upload URL. State lives in
/// an index file (RecorderStore) so `status` answers correctly after suspension and termination.
///
/// Upload (RecorderUploader): an in-process ephemeral URLSession that refuses every HTTP redirect, so
/// the recording reaches only the approved host of the URL the page supplied; the stored URL is
/// re-checked against the approved hosts before every attempt. Uploads run only while this process
/// runs: in the foreground, during the limited background time iOS may grant after backgrounding,
/// and during a background processing launch if and when iOS grants one. iOS guarantees neither
/// when nor whether those grants happen. A suspended or terminated upload keeps its file and queue
/// entry and restarts from the beginning when the app next runs; until then the state stays
/// `uploading`.
///
/// States: recording, interrupted (paused by the system, still owns the recorder), uploading,
/// uploaded, failed (upload failed; `reason` says why; `upload` with a fresh URL retries the saved
/// file), terminated (the app died while recording; the file was never finalized, so it is never
/// uploaded), discarding (a discard tombstone: its file is being deleted, then the record goes).
///
/// Main actor, as NativeExtension requires: every route, callback and uploader call runs there.
@MainActor
final class RecorderExtension: NSObject, NativeExtension {
    private var ctx: NativeExtContext!
    private var recorder: AVAudioRecorder?
    /// The record that owns `recorder`, and a reservation taken before any await in start().
    private var activeId: String?
    private var starting = false
    private var store: RecorderStore!
    private var uploader: RecorderUploader!
    /// The UIKit background-time assertion held while an upload attempt runs; finite and not guaranteed.
    private var assertion: UIBackgroundTaskIdentifier = .invalid
    private var loggedFailure: String?
    /// This fixture's claim on the process-wide audio session (RecorderUploader.swift).
    private var session = RecorderSessionLease(record: RecorderExtension.recordConfig)
    private static let recordConfig = RecorderSessionConfig(category: AVAudioSession.Category.record.rawValue,
                                                            mode: AVAudioSession.Mode.default.rawValue, options: 0)
    private static let sessionPort = RecorderSessionPort(
        config: {
            let s = AVAudioSession.sharedInstance()
            return RecorderSessionConfig(category: s.category.rawValue, mode: s.mode.rawValue, options: s.categoryOptions.rawValue)
        },
        setConfig: { c in
            try AVAudioSession.sharedInstance().setCategory(AVAudioSession.Category(rawValue: c.category),
                                                            mode: AVAudioSession.Mode(rawValue: c.mode),
                                                            options: AVAudioSession.CategoryOptions(rawValue: c.options))
        },
        setActive: { active, notifyOthers in
            try AVAudioSession.sharedInstance().setActive(active, options: notifyOthers ? .notifyOthersOnDeactivation : [])
        })
    /// Records whose last event said durable:false; re-emitted once, durable, after the next good write.
    private var durableEvents = RecorderDurableEvents()

    required override init() { super.init() }

    func didLaunch(_ context: NativeExtContext) {
        ctx = context
        // A recording read back from disk did not survive its process, and its file was never
        // finalized (a "starting" one may not even have audio). The load that first reads the index,
        // now or at any later save once it becomes readable, finishes the tombstones an earlier
        // process left before that load returns, so nothing builds on an index that still lists them.
        store = RecorderStore(directory: context.dataDirectory, adopt: { r in
            if r.state == "starting" || r.state == "recording" || r.state == "interrupted" { r.state = "terminated" }
        }, justLoaded: { [weak self] s in
            let finished = s.recoverDiscards()
            // After this load returns: the events and the upload restart build on the finished index.
            Task { @MainActor [weak self] in self?.indexLoaded(finished) }
        })
        save()
        if let aside = store.movedAside { ctx.log("recorder index did not decode; copied aside to \(aside.lastPathComponent)") }
        uploader = RecorderUploader(
            store: store,
            isApproved: { [weak self] url, route in
                self?.ctx.isApprovedURL(url, route: route, param: "upload") ?? false
            },
            changed: { [weak self] id in self?.changed(id) },
            inBackground: UIApplication.shared.applicationState == .background)
        uploader.onIdle = { [weak self] in self?.endAssertion() }
        NotificationCenter.default.addObserver(self, selector: #selector(interruption(_:)),
                                               name: AVAudioSession.interruptionNotification, object: nil)
        // Any record left uploading by a previous process has no live task: resume it now.
        resumeUploads()
    }

    func sceneDidChange(_ phase: NativeExtScenePhase) {
        switch phase {
        case .didBecomeActive:
            // The user returning is the most dependable opportunity: retry now, not at the backoff time.
            if !store.durable { save() }
            uploader.becameActive()
            if uploader.isBusy { beginAssertion() }
        case .didEnterBackground:
            uploader.didEnterBackground()
            if uploader.isBusy, assertion == .invalid {
                // Not granted (or already ended): requeue rather than be suspended mid-attempt.
                uploader.expireInFlight()
            }
            if uploader.hasWork { scheduleProcessing() }
        default:
            break
        }
    }

    func handleBackgroundTask(name: String, task: BGTask) {
        guard name == "upload" else {
            task.setTaskCompleted(success: false)
            return
        }
        // Completed exactly once, by whichever comes first: the queue going idle or expiry.
        var finished = false
        let previousIdle = uploader.onIdle
        let finish: (Bool) -> Void = { [weak self] success in
            guard !finished else { return }
            finished = true
            self?.uploader.onIdle = previousIdle
            task.setTaskCompleted(success: success)
        }
        ctx.onExpiration(of: task) { [weak self] in
            // Delivered after the task already finished (the hop can trail): nothing of it is left to stop.
            guard !finished, let self = self else { return }
            self.uploader.expireInFlight()
            finish(false)
            if self.uploader.hasWork { self.scheduleProcessing() }
        }
        uploader.onIdle = { [weak self] in
            previousIdle?()
            guard let self = self else { return }
            if !self.uploader.isBusy && !self.uploader.hasWork { finish(true) }
        }
        if !store.durable { save() }
        uploader.drain()
        if !uploader.isBusy && !uploader.hasWork { finish(true) }
    }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        // A recording this process holds (the active one included) is answered from memory; any
        // other lookup needs the index. start needs it too: a recording is indexed before it exists.
        let known = (call.params["id"] as? String).map { store[$0] != nil } ?? (store.latest != nil)
        if call.route == "start" || !known, let e = unavailable() { return e }
        switch call.route {
        case "start": return await start(call)
        case "stop": return stop(call.params["id"] as? String ?? "")
        case "status": return status(call.params["id"] as? String)
        case "discard": return discard(call.params["id"] as? String ?? "")
        case "upload": return upload(call.params["id"] as? String ?? "", call.params["upload"] as? String ?? "")
        default: return .error(code: "not_registered", message: call.route)
        }
    }

    private func start(_ call: NativeExtCall) async -> NativeExtResult {
        // Calls run on the main actor but interleave at the permission await: reserve first.
        guard recorder == nil, !starting else { return .error(code: "busy", message: "a recording is already running") }
        guard let upload = call.params["upload"] as? String else { return .error(code: "bad_request", message: "upload") }
        starting = true
        defer { starting = false }
        let granted = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            AVAudioSession.sharedInstance().requestRecordPermission { c.resume(returning: $0) }
        }
        // A late allow: the route timed out while the permission prompt was up, so the page already
        // has the timeout and would never learn of a recording begun now. Begin nothing; the defer
        // releases the reservation, so the next start is not busy. A recording an earlier start
        // reported is untouched.
        guard call.isActive else { return .error(code: "timeout", message: "start timed out before the microphone was allowed") }
        guard granted else { return .error(code: "permission_denied", message: "microphone access was denied") }
        // Indexed before any audio can exist: a "starting" record is saved before the directory or
        // the recorder is created. Not saved: nothing is started. Every later failure goes through
        // the tombstone path (abandon), and a process death leaves "starting", which the next load
        // adopts as terminated, so audio is never on disk without an index entry.
        let id = UUID().uuidString.lowercased()
        let previousLatest = store.latest
        store.set(RecorderRecord(id: id, state: "starting", startedAt: Date(), durationMs: 0, bytes: 0, uploadURL: upload,
                                 httpStatus: nil, urlRoute: "start", attempts: nil, nextAttemptAt: nil, reason: nil))
        store.latest = id
        guard save() else {
            store.remove(id)
            store.latest = previousLatest
            return .error(code: storageCode(), message: "the recording index could not be saved; nothing was started")
        }
        let url = store.fileURL(id)
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            // Owned only once our own activation returned; a failure before it deactivates nothing.
            try session.acquire(Self.sessionPort)
            let r = try AVAudioRecorder(url: url, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
            ])
            guard r.record() else {
                r.stop()
                return abandon(id, previousLatest, failedStart("the recorder did not start"))
            }
            recorder = r
            activeId = id
        } catch {
            return abandon(id, previousLatest, failedStart(error.localizedDescription))
        }
        if var r = store[id] {
            r.state = "recording"
            store.set(r)
        }
        changed(id)
        return .ok(storageFields(["recordingId": id, "state": "recording"]))
    }

    /// A start that failed after its "starting" record was saved: the record goes through the
    /// tombstone path, so its directory (which may hold a partial file) is deleted before the record
    /// is removed. A tombstone that cannot finish stays listed as discarding for the next discard or
    /// load; one that cannot be saved leaves the record, now terminated, which a discard removes.
    private func abandon(_ id: String, _ previousLatest: String?, _ result: NativeExtResult) -> NativeExtResult {
        if store.discard(id) == .notSaved, var r = store[id] {
            r.state = "terminated"
            store.set(r)
        }
        store.latest = previousLatest
        save()
        // The page never received this id (the reply is an error): a record the failed start leaves
        // behind (a tombstone that could not finish, or terminated) is announced so it can be discarded.
        if store[id] != nil { changed(id) }
        return result
    }

    /// A start that failed: the fixture's own session activation is relinquished (another client's
    /// audio is left alone), the original error is kept, and a cleanup failure is appended and logged.
    private func failedStart(_ message: String) -> NativeExtResult {
        if case .failed(let why) = session.relinquish(Self.sessionPort) {
            ctx.log("recorder audio session not released after a failed start: \(why)")
            return .error(code: "record_failed", message: message + "; the audio session could not be released")
        }
        return .error(code: "record_failed", message: message)
    }

    /// An id the index does not hold. After an index was set aside, the recording may be listed only
    /// in the aside copy, so the reply says that instead of a plain not_found.
    private func unknown() -> NativeExtResult {
        if store.recovered != nil {
            return .error(code: "index_recovered",
                          message: "unknown recording; the recording index could not be read and was replaced, so it may be listed only in the set-aside copy")
        }
        return .error(code: "not_found", message: "unknown recording")
    }

    /// Same codes as Android: an index that cannot be read yet is unavailable, any other failure is failed.
    private func storageCode() -> String {
        store.failure == "index_unreadable" ? "storage_unavailable" : "storage_failed"
    }

    private func stop(_ id: String) -> NativeExtResult {
        guard var r = store[id] else { return unknown() }
        // Stop and release in every state that owns the recorder, so the file is finalized.
        if activeId == id, let active = recorder {
            r.durationMs = Int(active.currentTime * 1000)
            active.stop()
            recorder = nil
            activeId = nil
            // Before the audio session goes inactive: a stop while the app is alive only because of
            // the audio mode would otherwise be suspended at once, before the upload starts.
            beginAssertion()
            switch session.relinquish(Self.sessionPort) {
            case .released:
                break
            case .failed(let why):
                ctx.log("recorder audio session not released after stop: \(why)")
            case let kept:
                // Not an error (another client holds the session), but visible in the device log.
                ctx.log("recorder audio session left active after stop: \(kept)")
            }
        } else if r.state == "recording" || r.state == "interrupted" {
            // No recorder owns it any more (it died with the process): nothing to finalize.
            r.state = "terminated"
            store.set(r)
            changed(id)
            return .ok(snapshot(r))
        } else {
            return .ok(snapshot(r))
        }
        return beginUpload(&r, fromUploadRoute: false)
    }

    /// Re-upload a saved recording with a fresh URL (an expired presigned URL is the usual reason).
    /// The stored URL is replaced; neither URL is ever logged or emitted.
    private func upload(_ id: String, _ url: String) -> NativeExtResult {
        guard var r = store[id] else { return unknown() }
        guard r.state == "failed" || r.state == "uploaded" else {
            return .error(code: "busy", message: "only a stopped recording whose upload failed or finished can be uploaded again")
        }
        r.uploadURL = url
        r.urlRoute = "upload"
        return beginUpload(&r, fromUploadRoute: true)
    }

    private func beginUpload(_ r: inout RecorderRecord, fromUploadRoute: Bool) -> NativeExtResult {
        let id = r.id
        let file = store.fileURL(id)
        r.bytes = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? 0
        r.state = "uploading"
        r.httpStatus = nil
        r.reason = nil
        store.set(r)
        changed(id)
        beginAssertion()
        uploader.enqueue(id, fromUploadRoute: fromUploadRoute)
        return .ok(snapshot(store[id] ?? r))
    }

    private func resumeUploads() {
        guard uploader.hasWork else { return }
        beginAssertion()
        uploader.drain()
    }

    private func status(_ id: String?) -> NativeExtResult {
        // A named recording the index does not hold is unknown, never idle.
        if let id = id, store[id] == nil { return unknown() }
        guard let key = id ?? store.latest, var r = store[key] else { return .ok(storageFields(["state": "idle"])) }
        if r.state == "recording", let active = recorder { r.durationMs = Int(active.currentTime * 1000) }
        return .ok(snapshot(r))
    }

    private func discard(_ id: String) -> NativeExtResult {
        guard let r = store[id] else { return unknown() }
        guard activeId != id, r.state != "starting", r.state != "recording", r.state != "interrupted", r.state != "uploading" else {
            return .error(code: "busy", message: "stop the recording or wait for the upload")
        }
        switch store.discard(id) {
        case .notSaved:
            return .error(code: storageCode(), message: "the recording index could not be saved; nothing was discarded")
        case .fileKept:
            changed(id)
            return .error(code: "discard_failed",
                          message: "the recording file could not be deleted; it stays listed as discarding and is deleted by the next discard or launch")
        case .discarded:
            changed(id)
            return .ok(storageFields(["recordingId": id, "state": "discarded"]))
        }
    }

    /// After the load that first read the index (the store's justLoaded hook already finished the
    /// tombstones an earlier process left): report each discard it finished, and restart uploads.
    private func indexLoaded(_ finished: [String]) {
        for id in finished {
            ctx.log("recorder discard finished at index load")
            changed(id)
        }
        if uploader != nil { resumeUploads() }
    }

    private func snapshot(_ r: RecorderRecord) -> [String: Any] {
        var out: [String: Any] = ["recordingId": r.id, "state": r.state, "durationMs": r.durationMs, "bytes": r.bytes]
        if let code = r.httpStatus { out["httpStatus"] = code }
        if r.state == "failed", let reason = r.reason { out["reason"] = reason }
        return storageFields(out)
    }

    /// Storage conditions every reply and event carries: state held in memory only (after a
    /// termination it is lost and the index on disk applies), and an index that did not decode and
    /// was set aside on this install (persisted, never cleared), so earlier recordings may be listed
    /// only in the aside copy.
    private func storageFields(_ base: [String: Any]) -> [String: Any] {
        var out = base
        if !store.durable {
            out["durable"] = false
            out["storage"] = store.failure ?? "not_saved"
        }
        if let flag = store.recovered { out["indexRecovered"] = flag }
        return out
    }

    /// While the index cannot be read, no recording is known to be absent and none may start: a reply
    /// would wrongly say idle or not_found, and a new recording could not be indexed. Each call
    /// retries the read first; a read that succeeds runs the store's justLoaded recovery.
    private func unavailable() -> NativeExtResult? {
        if !store.isLoaded { save() }
        guard !store.isLoaded else { return nil }
        return .error(code: storageCode(), message: "the recording index cannot be read yet; retry later")
    }

    /// Persists the index; a failure is logged once per kind and stays visible in every snapshot.
    @discardableResult
    private func save() -> Bool {
        if store.persist() {
            loggedFailure = nil
            // Every state the page was told is not durable is durable now: say so, once per record.
            for id in durableEvents.saved() {
                ctx.emit("state", store[id].map(snapshot) ?? storageFields(["recordingId": id, "state": "discarded"]))
            }
            return true
        }
        let failure = store.failure ?? "not_saved"
        if loggedFailure != failure {
            loggedFailure = failure
            ctx.log("recorder index not saved: \(failure)")
        }
        return false
    }

    private func changed(_ id: String) {
        // This event reports the record's current state, so a pending re-emission of it is superseded.
        durableEvents.willEmit(id)
        save()
        let r = store[id]
        RecorderStatus(recordingId: r?.id, state: r?.state ?? "idle", durationMs: r?.durationMs ?? 0, updatedAt: Date()).save()
        WidgetCenter.shared.reloadTimelines(ofKind: "NXRecorderStatus")
        ctx.emit("state", r.map(snapshot) ?? storageFields(["recordingId": id, "state": "discarded"]))
        durableEvents.emitted(id, durable: store.durable)
    }

    // MARK: Background execution (finite, never guaranteed)

    /// One assertion while upload work runs. iOS may grant little or no time, and may end it early;
    /// on expiry the in-flight attempt is cancelled and requeued without counting it.
    private func beginAssertion() {
        guard assertion == .invalid else { return }
        assertion = UIApplication.shared.beginBackgroundTask(withName: "nativeext.recorder.upload") { [weak self] in
            guard let self = self else { return }
            self.uploader.expireInFlight()
            if self.uploader.hasWork { self.scheduleProcessing() }
            self.endAssertion()
        }
    }

    private func endAssertion() {
        guard assertion != .invalid else { return }
        let a = assertion
        assertion = .invalid
        UIApplication.shared.endBackgroundTask(a)
    }

    /// Asks for a background processing launch for queued uploads. The system decides whether and
    /// when it runs (only when the device is idle, possibly hours later); a refused request is logged.
    private func scheduleProcessing() {
        let request = BGProcessingTaskRequest(identifier: "nativeext.\(ctx.extensionId).upload")
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            ctx.log("background processing request not accepted: \(error.localizedDescription)")
        }
    }

    /// NotificationCenter calls a selector observer on the posting thread, which AVAudioSession does
    /// not promise is the main one: hop to the main actor, where the recorder state lives.
    @objc private nonisolated func interruption(_ note: Notification) {
        let info = note.userInfo
        Task { @MainActor [weak self] in self?.interrupted(info) }
    }

    private func interrupted(_ userInfo: [AnyHashable: Any]?) {
        guard let raw = userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw),
              let id = activeId, let active = recorder, var r = store[id] else { return }
        if type == .began, r.state == "recording" {
            r.durationMs = Int(active.currentTime * 1000)
            active.pause()
            // The system deactivated the session: it is no longer this fixture's activation.
            session.lost()
            r.state = "interrupted"
            store.set(r)
            changed(id)
        } else if type == .ended, r.state == "interrupted" {
            let opts = AVAudioSession.InterruptionOptions(rawValue: userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            if opts.contains(.shouldResume), (try? AVAudioSession.sharedInstance().setActive(true)) != nil {
                session.regained()
            }
            if opts.contains(.shouldResume), session.owned, active.record() {
                r.state = "recording"
                store.set(r)
                changed(id)
            }
        }
    }
}
