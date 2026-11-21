package despia.ext.recorder;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bridge entry for the background recorder. The page passes an upload URL and gets a recording
 * id and state back; audio never crosses the bridge, the service records to app storage and the
 * worker uploads from disk.
 */
public final class RecorderExtension implements NativeExtension {
    private static volatile ExtContext policy;
    private ExtContext ctx;

    /**
     * This extension's context once the runtime created it (Application.onCreate, before any
     * activity or WebView, also in a process WorkManager started); null before that. The worker
     * re-checks a stored upload URL against the declared route through it.
     */
    static ExtContext policy() {
        return policy;
    }

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        policy = context;
        RecorderStore.setListener(snapshot -> ctx.emit("state", snapshot));
        if (!recoverLaunch()) {
            // Unreadable now: left untouched, and every route reports storage_unavailable until it
            // reads. The first load that succeeds in this process runs the recovery (tombstones,
            // stale recordings) before anything builds on that index.
            RecorderStore.armRecovery(new Runnable() {
                @Override
                public void run() {
                    if (!recoverLaunch()) RecorderStore.armRecovery(this);
                }
            });
        }
    }

    /** False, changing nothing, when the index cannot be read yet. */
    private boolean recoverLaunch() {
        // A process killed mid-recording leaves a "recording" or "starting" entry behind whose file
        // was never finalized: report it as terminated, never upload it.
        JSONObject latest;
        try {
            latest = RecorderStore.get(ctx.app(), null);
        } catch (RecorderStore.StorageException e) {
            ctx.log("recorder index unreadable: " + e.reason);
            return false;
        }
        // Discards a previous process did not finish (a tombstone, its file possibly still on disk).
        try {
            for (String id : RecorderStore.recoverDiscards(ctx.app())) ctx.log("recorder discard finished: " + id);
        } catch (RecorderStore.StorageException e) {
            ctx.log("recorder discard recovery not saved: " + e.reason);
        }
        String s = latest == null ? null : latest.optString("state");
        if (("recording".equals(s) || "starting".equals(s)) && !RecorderService.isRunning()) {
            String id = latest.optString("recordingId");
            try {
                RecorderStore.update(ctx.app(), id, "terminated", null);
            } catch (RecorderStore.StorageException e) {
                RecorderStore.notSaved(id, "terminated", e);
            }
        }
        // Posted, never inline: recovery can run inside the store's lock (an armed late load), and
        // reconciliation reads WorkManager asynchronously. Leaves no finalized recording stranded.
        final Context app = ctx.app();
        ContextCompat.getMainExecutor(app).execute(() -> UploadWorker.reconcile(app));
        return true;
    }

    /** Every ok reply carries indexRecovered while the index was set aside on this install. */
    private static ExtResult ok(Context app, JSONObject data) throws JSONException {
        String recovered;
        try {
            recovered = RecorderStore.recovered(app);
        } catch (RecorderStore.StorageException e) {
            // The reply already happened (a discard whose removal was not saved says so itself).
            recovered = null;
        }
        if (recovered != null && !data.has(RecorderStore.RECOVERED)) data.put(RecorderStore.RECOVERED, recovered);
        return ExtResult.ok(data);
    }

    /**
     * An id the index does not hold. After the index was set aside, the recording may be listed
     * only in the aside copy, so the reply says that instead of a plain not_found.
     */
    private static ExtResult unknown(Context app) throws RecorderStore.StorageException {
        if (RecorderStore.recovered(app) != null) {
            return ExtResult.error("index_recovered",
                    "unknown recording; the recording index could not be read and was replaced, so it may be listed only in the set-aside copy");
        }
        return ExtResult.error("not_found", "unknown recording");
    }

    private static ExtResult storageFailed(RecorderStore.StorageException e, String what) {
        // Same codes as iOS: an index that cannot be read yet is unavailable, any other failure is failed.
        String code = "index_unreadable".equals(e.reason) ? "storage_unavailable" : "storage_failed";
        return ExtResult.error(code, "the recording index could not be " + what + " (" + e.reason + ")");
    }

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        Context app = ctx.app();
        try {
            switch (call.route) {
                case "start": {
                    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        reply.reply(ExtResult.error("permission_denied", "RECORD_AUDIO is not granted; request it from the page first"));
                        return;
                    }
                    String id = RecorderStore.newId();
                    // Atomic reservation: two quick starts cannot both pass.
                    if (RecorderService.isRunning() || !RecorderService.reserve(id)) {
                        reply.reply(ExtResult.error("busy", "a recording is already running"));
                        return;
                    }
                    try {
                        RecorderStore.create(app, id, "starting",
                                new JSONObject().put("uploadUrl", call.params.get("upload")).put("urlRoute", "start"));
                    } catch (RecorderStore.StorageException e) {
                        RecorderService.release(id);
                        reply.reply(storageFailed(e, "saved; nothing was started"));
                        return;
                    }
                    Intent i = new Intent(app, RecorderService.class).setAction(RecorderService.ACTION_START).putExtra("id", id);
                    // Microphone foreground services may only start while the app is visible
                    // (Android 14+); the page call is that moment.
                    try {
                        ContextCompat.startForegroundService(app, i);
                    } catch (RuntimeException e) {
                        RecorderService.release(id);
                        try {
                            // A capture that never began: record_failed, never captureFinal.
                            RecorderStore.update(app, id, "failed", RecorderStore.captureFailed());
                        } catch (RecorderStore.StorageException se) {
                            // Left "starting" on disk, which the next launch reports as terminated.
                            RecorderStore.notSaved(id, "failed", se);
                        }
                        reply.reply(ExtResult.error("record_failed", "the recording service could not start"));
                        return;
                    }
                    reply.reply(ok(app, new JSONObject().put("recordingId", id).put("state", "starting")));
                    return;
                }
                case "stop": {
                    String id = (String) call.params.get("id");
                    JSONObject r = RecorderStore.get(app, id);
                    if (r == null) {
                        reply.reply(unknown(app));
                        return;
                    }
                    // Only the recording that owns the service stops it, also while it is still
                    // "starting": the service handles its commands in order, so this stop follows
                    // the start and either ends the recording or cancels it before the microphone
                    // opens. Requiring a running service here dropped a stop sent during "starting".
                    if (id.equals(RecorderService.owner())) {
                        app.startService(new Intent(app, RecorderService.class).setAction(RecorderService.ACTION_STOP).putExtra("id", id));
                    }
                    JSONObject now = RecorderStore.get(app, id);
                    reply.reply(now == null ? unknown(app) : ok(app, RecorderStore.snapshot(now)));
                    return;
                }
                case "upload": {
                    // Re-upload a saved recording with a fresh URL (an expired presigned URL is the
                    // usual reason). The stored URL is replaced; neither URL is logged or emitted.
                    String id = (String) call.params.get("id");
                    // The URL is bound to the route whose https_url rule approved it; the worker re-checks both.
                    String url = (String) call.params.get("upload");
                    try {
                        // The state check, the URL, the generation and "uploading" are one store step:
                        // a discard can never land between the check and the write. The reply waits
                        // for WorkManager to commit the request (heard on the main thread), so an ok
                        // "uploading" always has work behind it.
                        JSONObject queued = UploadWorker.requeue(app, id, url, "upload", (committed, saved) -> {
                            try {
                                if (committed) {
                                    reply.reply(ok(app, saved));
                                } else {
                                    reply.reply(ExtResult.error("storage_failed", "the upload could not be scheduled; no upload was queued"));
                                }
                            } catch (JSONException e) {
                                reply.reply(ExtResult.error("handler_error", e.getMessage()));
                            }
                        });
                        if (queued == null) {
                            reply.reply(unknown(app));
                            return;
                        }
                        if (queued.optBoolean("busy")) {
                            reply.reply(ExtResult.error("busy", "only a stopped recording whose upload failed or finished can be uploaded again"));
                            return;
                        }
                        if (queued.optBoolean("notFinal")) {
                            reply.reply(ExtResult.error("record_failed", "the recording was never finalized, so there is no audio to upload; discard it"));
                            return;
                        }
                    } catch (RecorderStore.StorageException e) {
                        reply.reply(storageFailed(e, "saved; no upload was queued"));
                    }
                    return;
                }
                case "status": {
                    String id = (String) call.params.get("id");
                    JSONObject r = RecorderStore.get(app, id);
                    // A named recording the index does not hold is unknown, never "idle".
                    if (r == null && id != null) {
                        reply.reply(unknown(app));
                        return;
                    }
                    reply.reply(ok(app, r == null ? new JSONObject().put("state", "idle") : RecorderStore.snapshot(r)));
                    return;
                }
                case "discard": {
                    String id = (String) call.params.get("id");
                    JSONObject r = RecorderStore.get(app, id);
                    if (r == null) {
                        reply.reply(unknown(app));
                        return;
                    }
                    String state = r.optString("state");
                    if ("recording".equals(state) || "starting".equals(state) || "uploading".equals(state)
                            || id.equals(RecorderService.owner())) {
                        reply.reply(ExtResult.error("busy", "stop the recording or wait for the upload"));
                        return;
                    }
                    JSONObject gone;
                    try {
                        gone = RecorderStore.discard(app, id);
                    } catch (RecorderStore.StorageException e) {
                        reply.reply(storageFailed(e, "saved; nothing was discarded"));
                        return;
                    }
                    if (gone != null && gone.optBoolean("busy")) {
                        reply.reply(ExtResult.error("busy", "stop the recording or wait for the upload"));
                        return;
                    }
                    if (gone == null) {
                        reply.reply(ExtResult.error("discard_failed",
                                "the recording could not be deleted or its removal could not be confirmed; it stays listed as discarding and is deleted by the next discard or launch"));
                        return;
                    }
                    reply.reply(ok(app, gone));
                    return;
                }
                default:
                    reply.reply(ExtResult.error("not_registered", call.route));
            }
        } catch (RecorderStore.StorageException e) {
            reply.reply(storageFailed(e, "read"));
        } catch (JSONException e) {
            reply.reply(ExtResult.error("handler_error", e.getMessage()));
        }
    }
}
