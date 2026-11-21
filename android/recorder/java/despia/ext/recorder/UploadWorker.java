package despia.ext.recorder;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.despia.nativeext.ExtContext;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uploads a finished recording from disk; survives process death and reboot.
 *
 * <p>Delivery is at least once (the PUT is idempotent). Authority over the record is the
 * generation written by {@link #enqueue} or {@link #requeue} and the run claim in
 * {@link RecorderStore#claimUpload}: WorkManager does not interrupt a stopped Worker's thread and may
 * start the next run, or a replacement, while it is still blocked, so only the run holding the
 * claim writes a state. A stopped run that still holds the claim may settle a response it really
 * received; once a rerun or a replacement takes the claim, the stopped run writes nothing.
 */
public final class UploadWorker extends Worker {
    /** Counted upload attempts (network errors and 5xx), the same budget as iOS. */
    static final int MAX_ATTEMPTS = 5;
    /** Runs that find the extension not yet registered (or failed to register) before failing. */
    static final int MAX_POLICY_WAITS = 10;
    static final int CONNECT_TIMEOUT_MS = 30_000;
    /** Per read, as iOS timeoutIntervalForRequest; a stalled body write ends through onStopped. */
    static final int READ_TIMEOUT_MS = 60_000;

    private volatile HttpURLConnection inFlight;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Tag on every request; with {@link #GEN_TAG} + generation it names the request's generation. */
    static final String TAG = "nx-recorder";
    static final String GEN_TAG = "nx-gen:";

    /**
     * Generations this process wrote and scheduled (or is scheduling). Launch reconciliation leaves
     * them alone; a generation found "uploading" on disk that is not here was written by an earlier
     * process, whose request may never have been committed.
     */
    static final Set<String> LIVE = ConcurrentHashMap.newKeySet();

    /** Told, on the main thread, whether WorkManager committed the request. */
    interface Scheduled {
        void done(boolean ok);
    }

    /** The upload route's answer: whether the request was committed, with the saved snapshot. */
    interface Queued {
        void done(boolean ok, JSONObject snapshot);
    }

    static String uniqueName(String id) {
        return "nx-recorder-" + id;
    }

    /**
     * The service's handoff after a successful stop: a finalized "stopped" record moves to
     * "uploading" with a fresh generation (one guarded store step), then the work is enqueued and its
     * result observed. Returns null, scheduling nothing, when the record is gone or not eligible
     * (a tombstone, a terminal state, not finalized). An enqueue WorkManager refuses settles the
     * record as failed/not_scheduled, never stranded as uploading.
     */
    static JSONObject enqueue(Context c, String id) throws RecorderStore.StorageException {
        String gen = UUID.randomUUID().toString();
        LIVE.add(gen);
        JSONObject snap;
        try {
            snap = RecorderStore.beginUpload(c, id, gen);
        } catch (RecorderStore.StorageException e) {
            LIVE.remove(gen);
            throw e;
        }
        if (snap == null) {
            LIVE.remove(gen);
            return null;
        }
        observe(c, id, gen, schedule(c, id, gen), null);
        return snap;
    }

    /**
     * The upload route: a still failed or uploaded, finalized record moves to uploading with the new
     * URL, the route that approved it and a fresh generation in one store step, and only then is the
     * work enqueued; {@code then} hears, on the main thread, whether WorkManager committed it.
     * Returns null (unknown), a busy snapshot or a notFinal snapshot without scheduling anything (and
     * without calling {@code then}).
     */
    static JSONObject requeue(Context c, String id, String url, String route, Queued then) throws RecorderStore.StorageException {
        String gen = UUID.randomUUID().toString();
        JSONObject fields = fields("uploadUrl", url);
        put(fields, "urlRoute", route);
        put(fields, "uploadGen", gen);
        put(fields, "attempts", JSONObject.NULL);
        put(fields, "reason", JSONObject.NULL);
        put(fields, "httpStatus", JSONObject.NULL);
        LIVE.add(gen);
        JSONObject snap;
        try {
            snap = RecorderStore.requeue(c, id, fields);
        } catch (RecorderStore.StorageException e) {
            LIVE.remove(gen);
            throw e;
        }
        if (snap == null || snap.optBoolean("busy") || snap.optBoolean("notFinal")) {
            LIVE.remove(gen);
            return snap;
        }
        final JSONObject saved = snap;
        observe(c, id, gen, schedule(c, id, gen), ok -> then.done(ok, saved));
        return snap;
    }

    /** A null generation only from the service, after its own index write failed. */
    static Operation schedule(Context c, String id, String gen) {
        Data.Builder input = new Data.Builder().putString("id", id);
        if (gen != null) input.putString("gen", gen);
        OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(input.build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG);
        if (gen != null) b.addTag(GEN_TAG + gen);
        // The newest enqueue owns the record (its generation), so it always replaces.
        return WorkManager.getInstance(c).enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, b.build());
    }

    /**
     * WorkManager's enqueue is asynchronous and can fail. Its result is read on the main executor
     * (never blocking the main thread); a refused generation is settled as failed/not_scheduled.
     */
    static void observe(Context c, String id, String gen, Operation op, Scheduled then) {
        ListenableFuture<Operation.State.SUCCESS> result = op.getResult();
        result.addListener(() -> {
            boolean ok;
            try {
                result.get();
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            if (!ok && gen != null) {
                LIVE.remove(gen);
                try {
                    RecorderStore.failUnscheduled(c, id, gen);
                } catch (RecorderStore.StorageException e) {
                    // Still "uploading" on disk: the next launch's reconciliation schedules it.
                    RecorderStore.notSaved(id, "failed", e);
                }
            }
            if (then != null) then.done(ok);
        }, ContextCompat.getMainExecutor(c));
    }

    /**
     * Launch reconciliation, on the main thread after recovery: no finalized recording is left
     * "stopped" or "uploading" without work for its current generation. Unfinished work tagged with
     * the record's generation is left alone (its claim, attempts and progress untouched); terminal
     * states (failed, uploaded, terminated, discarding) are never touched. Delivery stays at least
     * once: a 2xx whose "uploaded" write was lost to a process death is sent again.
     */
    static void reconcile(Context c) {
        Map<String, JSONObject> recs;
        try {
            recs = RecorderStore.records(c);
        } catch (RecorderStore.StorageException e) {
            return;
        }
        for (Map.Entry<String, JSONObject> e : recs.entrySet()) {
            String id = e.getKey();
            JSONObject r = e.getValue();
            // The service is still finishing it.
            if (id.equals(RecorderService.owner())) continue;
            String state = r.optString("state");
            boolean hasGen = r.has("uploadGen");
            if (("stopped".equals(state) && RecorderStore.captureFinal(r)) || ("uploading".equals(state) && !hasGen)) {
                try {
                    enqueue(c, id);
                } catch (RecorderStore.StorageException se) {
                    RecorderStore.notSaved(id, "uploading", se);
                }
                continue;
            }
            if (!"uploading".equals(state)) continue;
            String gen = r.optString("uploadGen");
            if (LIVE.contains(gen)) continue;
            ListenableFuture<List<WorkInfo>> infos = WorkManager.getInstance(c).getWorkInfosForUniqueWork(uniqueName(id));
            infos.addListener(() -> {
                List<WorkInfo> list;
                try {
                    list = infos.get();
                } catch (Exception failed) {
                    // Unknown: treated as no matching work. A restart is allowed (at least once);
                    // stranding the recording is not.
                    list = java.util.Collections.emptyList();
                }
                JSONObject now;
                try {
                    now = RecorderStore.get(c, id);
                } catch (RecorderStore.StorageException se) {
                    return;
                }
                if (now == null || !"uploading".equals(now.optString("state")) || !gen.equals(now.optString("uploadGen", null))
                        || LIVE.contains(gen)) {
                    return;
                }
                for (WorkInfo w : list) {
                    if (!w.getState().isFinished() && w.getTags().contains(GEN_TAG + gen)) return;
                }
                LIVE.add(gen);
                observe(c, id, gen, schedule(c, id, gen), null);
            }, ContextCompat.getMainExecutor(c));
        }
    }

    /** A state the index could not take is retried, never reported as done or dropped. */
    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        String id = getInputData().getString("id");
        if (id == null) return Result.failure();
        String gen = getInputData().getString("gen");
        String run = UUID.randomUUID().toString();
        JSONObject r;
        try {
            r = RecorderStore.claimUpload(c, id, gen, run);
        } catch (RecorderStore.StorageException e) {
            return Result.retry();
        }
        // Gone, a tombstone, replaced, or not waiting for an upload: nothing to do, nothing written.
        if (r == null) return Result.failure();
        try {
            return upload(c, id, gen, run, r);
        } finally {
            RecorderStore.releaseUpload(id, run);
        }
    }

    /** Unblocks a run WorkManager stopped (cancelled, replaced, constraints lost, timed out). */
    @Override
    public void onStopped() {
        HttpURLConnection conn = inFlight;
        if (conn != null) conn.disconnect();
    }

    private Result upload(Context c, String id, String gen, String run, JSONObject r) {
        // This generation already got a 2xx in this process and only its "uploaded" write failed:
        // save it now, without sending the body again.
        int received = RecorderStore.receivedFor(id, gen);
        if (received >= 0) {
            return finish(c, id, gen, run, "uploaded", put(fields("httpStatus", received), "reason", JSONObject.NULL), Result.success());
        }
        // The declared route table, registered in Application.onCreate before any WebView exists.
        ExtContext policy = RecorderExtension.policy();
        if (policy == null) {
            if (getRunAttemptCount() < MAX_POLICY_WAITS) return Result.retry();
            return finish(c, id, gen, run, "failed", fields("reason", "policy_unavailable"), Result.failure());
        }
        String target = r.optString("uploadUrl", "");
        // The route whose https_url rule approved the stored URL; "start" for records saved before urlRoute existed.
        String route = r.optString("urlRoute", "start");
        URL url = policy.isApprovedUrl(target, route, "upload") ? destination(target) : null;
        if (url == null) return finish(c, id, gen, run, "failed", fields("reason", "url_not_approved"), Result.failure());
        // A capture that never finalized is never sent, not even one already queued (legacy, or
        // requeued before this check existed): refused before any byte, the record says why.
        if (!RecorderStore.captureFinal(r)) return finish(c, id, gen, run, "failed", fields("reason", "record_failed"), Result.failure());
        File f = RecorderStore.existingAudioFile(c, id);
        if (!f.isFile() || f.length() == 0) return finish(c, id, gen, run, "failed", fields("reason", "no_file"), Result.failure());
        if (isStopped()) return Result.retry();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            inFlight = conn;
            if (isStopped()) return Result.retry();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setFixedLengthStreamingMode(f.length());
            conn.setRequestProperty("Content-Type", "audio/mp4");
            conn.setRequestProperty("X-Recording-Id", id);
            try (InputStream in = new FileInputStream(f); OutputStream out = conn.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            int code = conn.getResponseCode();
            JSONObject status = fields("httpStatus", code);
            if (code >= 200 && code < 300) {
                try {
                    if (RecorderStore.settleUpload(c, id, gen, run, "uploaded", put(status, "reason", JSONObject.NULL)) == null) {
                        return Result.failure();
                    }
                    RecorderStore.clearReceived(id);
                    return Result.success();
                } catch (RecorderStore.StorageException e) {
                    // The 2xx is real; only its write failed. Remember it so the rerun saves it
                    // instead of sending the body again.
                    RecorderStore.notSaved(id, "uploaded", e);
                    RecorderStore.receivedUnsaved(id, String.valueOf(gen), code);
                    return Result.retry();
                }
            }
            if (code >= 500) return counted(c, id, gen, run, r, "http", code);
            // 3xx (redirects are never followed) and 4xx are final; "upload" with a fresh URL retries.
            return finish(c, id, gen, run, "failed", put(status, "reason", "http"), Result.failure());
        } catch (IOException e) {
            // Disconnected by onStopped: WorkManager reruns it; not a counted attempt, nothing written.
            if (isStopped()) return Result.retry();
            return counted(c, id, gen, run, r, "network", null);
        } finally {
            inFlight = null;
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * A failed attempt that may be retried. The count lives in the record, so runs retried for a
     * storage failure (or a stop) do not use up the upload budget.
     */
    private Result counted(Context c, String id, String gen, String run, JSONObject r, String reason, Integer code) {
        int attempts = r.optInt("attempts", 0) + 1;
        JSONObject extra = put(fields("attempts", attempts), "httpStatus", code == null ? JSONObject.NULL : code);
        // A terminal state only when no retry follows; a pending retry stays "uploading".
        if (attempts < MAX_ATTEMPTS) return finish(c, id, gen, run, "uploading", extra, Result.retry());
        return finish(c, id, gen, run, "failed", put(extra, "reason", reason), Result.failure());
    }

    /** Saves for this run's claim; a run whose claim is gone writes nothing and ends. */
    private static Result finish(Context c, String id, String gen, String run, String state, JSONObject extra, Result saved) {
        try {
            if (RecorderStore.settleUpload(c, id, gen, run, state, extra) == null) return Result.failure();
            RecorderStore.clearReceived(id);
            return saved;
        } catch (RecorderStore.StorageException e) {
            RecorderStore.notSaved(id, state, e);
            return Result.retry();
        }
    }

    /**
     * The URL the request is built from, only when java.net.URI's parse agrees with the text the
     * https_url rule approved (same rule as iOS RecorderUploader.destination): https, no user
     * info, no fragment, the default port or an explicit 443, and a parsed host of letters, digits,
     * dots and hyphens equal to the literal host after "https://". Whether that host is approved
     * is the https_url rule's decision ({@link ExtContext#isApprovedUrl}), made before.
     */
    static URL destination(String s) {
        if (!strictUrlText(s)) return null;
        try {
            URI u = new URI(s);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null || u.getRawFragment() != null) return null;
            if (u.getPort() != -1 && u.getPort() != 443) return null;
            String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
            if (host == null || host.isEmpty() || !host.matches("[a-z0-9.-]+")) return null;
            String lower = s.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("https://" + host)) return null;
            String after = lower.substring(("https://" + host).length());
            if (!(after.isEmpty() || after.startsWith("/") || after.equals(":443") || after.startsWith(":443/"))) return null;
            return u.toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            return null;
        }
    }

    /** RFC 3986 characters plus "%" (checked as an escape by strictUrlText). */
    private static final String URL_TEXT_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%";
    private static final String HEX_DIGITS = "0123456789ABCDEFabcdef";

    /**
     * The raw text, before any parse: ASCII-only RFC 3986 text whose every "%" starts a two-hex-digit
     * escape (the bridge's own text rule, and iOS RecorderUploader.isStrictURLText). java.net.URI
     * accepts non-ASCII "other" characters such as U+200B and U+202E in a path, so this check, not
     * the parse, decides.
     */
    static boolean strictUrlText(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c > 0x7e || URL_TEXT_CHARS.indexOf(c) < 0) return false;
            if (c == '%') {
                if (i + 2 >= s.length() || HEX_DIGITS.indexOf(s.charAt(i + 1)) < 0 || HEX_DIGITS.indexOf(s.charAt(i + 2)) < 0) {
                    return false;
                }
                i += 3;
            } else {
                i++;
            }
        }
        return true;
    }

    private static JSONObject fields(String k, Object v) {
        return put(new JSONObject(), k, v);
    }

    private static JSONObject put(JSONObject o, String k, Object v) {
        try {
            return o.put(k, v);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
