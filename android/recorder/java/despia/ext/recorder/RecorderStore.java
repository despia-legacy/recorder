package despia.ext.recorder;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Index of recordings, shared by the extension, the service, the worker and the widget.
 *
 * <p>Durability contract. An absent index reads as empty. An index that cannot be read (I/O error,
 * permission, not a file) is never overwritten: reads and writes throw {@link StorageException}
 * with reason {@code index_unreadable}. An index that reads but is not a JSON index is copied
 * aside byte for byte to {@code index.unreadable-<uuid>.json} (synced) and only then replaced by a
 * fresh index that carries {@code indexRecovered: "moved_aside"}, so the fact survives a crash and
 * every later launch: the aside copy may list recordings this index no longer knows, and callers
 * report that ({@link #recovered}) instead of a plain "idle" or "not found". The flag is never
 * cleared by the app. java.io only (minSdk 24 has no java.nio.file). Every
 * write is a read-modify-write of the index just read, under the class lock, through a synced temp
 * file and a rename; {@link #update} returns a snapshot only once that rename succeeded and throws
 * otherwise ({@code index_write_failed}, {@code index_encode_failed}).
 *
 * <p>Callers surface a failure instead of claiming the state: bridge routes reply {@code
 * storage_failed}; the service, the worker and launch recovery emit the state they could not save
 * with {@code durable:false} and {@code storage:<reason>} ({@link #notSaved}); the worker retries.
 *
 * <p>One process (no component sets android:process, WorkManager runs in the default process), so
 * the class lock serializes every caller and no file lock is needed.
 *
 * <p>Records are created only by {@link #create} (the start route). {@link #update} of an id the
 * index does not hold returns null and writes nothing, so a late writer (a stopped worker, a
 * service finishing after a discard) can never bring a discarded recording back.
 *
 * <p>Uploads. {@link #claimUpload} is the only way a worker run gets a record: the record must
 * exist, must not be a tombstone, and must carry the generation its work request was enqueued with
 * ({@code uploadGen}, written by every enqueue). The run is then the record's only writer
 * ({@link #settleUpload}) until a later run of the same work claims the record or an enqueue
 * replaces the generation; a run whose claim was taken writes nothing. A run WorkManager stopped
 * keeps its claim until then, so it may still settle a response it actually received (delivery is
 * at least once; a rerun then finds the record no longer uploading and claims nothing). Run claims
 * are in memory: a process death ends every thread that could hold one.
 *
 * <p>Re-upload ({@link #requeue}) checks that the record is still failed or uploaded and moves it to
 * uploading with the new URL, its route and a new generation in one step under the class lock, so
 * a discard that lands in between can never be overwritten.
 */
final class RecorderStore {
    interface Listener {
        void changed(JSONObject snapshot);
    }

    static final class StorageException extends Exception {
        final String reason;

        StorageException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }
    }

    private static final String TAG = "NXRecorder";
    private static volatile Listener listener;

    static void setListener(Listener l) {
        listener = l;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "nativeext/recorder");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The recording's file, without creating its directory (workers and recovery only read). */
    static File existingAudioFile(Context c, String id) {
        return new File(new File(dir(c), id), "audio.m4a");
    }

    /** The recording's file; creates its directory. The service only, for the recording it owns. */
    static File audioFile(Context c, String id) {
        File d = new File(dir(c), id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, "audio.m4a");
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    static final String RECOVERED = "indexRecovered";

    /**
     * Internal record field, written true only together with "stopped" after MediaRecorder.stop()
     * returned (the container is final). Never written false, never in a snapshot. Upload eligibility.
     */
    static final String CAPTURE_FINAL = "captureFinal";

    /** True when the recording's capture finalized; legacy records witness it by durationMs. */
    static boolean captureFinal(JSONObject r) {
        // Before captureFinal existed, durationMs was written only with "stopped" after a successful
        // stop(); no failure path wrote it. A record with neither is refused (fail closed).
        return r != null && (r.optBoolean(CAPTURE_FINAL) || r.has("durationMs"));
    }

    /** The extra for a capture that failed: "failed" with reason record_failed, never captureFinal. */
    static JSONObject captureFailed() {
        try {
            return new JSONObject().put("reason", "record_failed");
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Launch recovery that could not read the index yet; run once by the next successful load. */
    private static Runnable pendingRecovery;

    static synchronized void armRecovery(Runnable r) {
        pendingRecovery = r;
    }

    static synchronized JSONObject load(Context c) throws StorageException {
        JSONObject all = loadOnce(c);
        Runnable r = pendingRecovery;
        if (r == null) return all;
        pendingRecovery = null;
        r.run();
        // Re-read: the caller must build on what recovery wrote, never on the copy read before it.
        return loadOnce(c);
    }

    private static JSONObject loadOnce(Context c) throws StorageException {
        File d = dir(c);
        File f = new File(d, "index.json");
        byte[] bytes;
        try {
            bytes = read(d, f);
        } catch (IOException | SecurityException e) {
            throw new StorageException("index_unreadable", e);
        }
        if (bytes == null) return new JSONObject();
        try {
            JSONObject all = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!all.has("records") || all.optJSONObject("records") != null) return all;
        } catch (JSONException e) {
            // Falls through to set it aside.
        }
        File aside = asideCopy(d, bytes);
        JSONObject fresh;
        try {
            fresh = new JSONObject().put(RECOVERED, "moved_aside");
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        write(d, fresh.toString().getBytes(StandardCharsets.UTF_8));
        Log.w(TAG, "recorder index did not parse; moved aside to " + aside.getName() + ", starting a fresh index");
        return fresh;
    }

    /** The index bytes; null only when the directory lists no index.json (a dangling link is listed). */
    private static byte[] read(File d, File f) throws IOException {
        if (!f.exists()) {
            String[] names = d.list();
            if (names == null) throw new IOException("the recorder directory cannot be listed");
            if (Arrays.asList(names).contains("index.json")) throw new IOException("index.json is listed but cannot be read");
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** A synced byte-for-byte copy of an index that is not an index; an identical earlier copy is reused. */
    private static File asideCopy(File d, byte[] bytes) throws StorageException {
        String[] names = d.list();
        if (names != null) {
            for (String n : names) {
                if (!n.startsWith("index.unreadable-") || !n.endsWith(".json")) continue;
                File old = new File(d, n);
                try {
                    if (Arrays.equals(bytes, read(d, old))) return old;
                } catch (IOException | SecurityException e) {
                    // Not comparable; a new copy is written.
                }
            }
        }
        File aside = new File(d, "index.unreadable-" + UUID.randomUUID() + ".json");
        try (FileOutputStream out = new FileOutputStream(aside)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException | SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            aside.delete();
            throw new StorageException("index_unreadable", e);
        }
        return aside;
    }

    /** "moved_aside" when an unreadable index was set aside on this install; otherwise null. */
    static synchronized String recovered(Context c) throws StorageException {
        JSONObject all = load(c);
        return all.has(RECOVERED) ? all.optString(RECOVERED) : null;
    }

    static synchronized JSONObject get(Context c, String id) throws StorageException {
        JSONObject all = load(c);
        String key = id != null ? id : all.optString("latest", null);
        if (key == null) return null;
        JSONObject recs = all.optJSONObject("records");
        return recs == null ? null : recs.optJSONObject(key);
    }

    /** A new record (the start route). Returns the saved snapshot; throws when nothing was saved. */
    static synchronized JSONObject create(Context c, String id, String state, JSONObject extra) throws StorageException {
        return apply(c, id, state, extra, true);
    }

    /**
     * A null state removes the record. Returns the saved snapshot, or null when the index does not
     * hold {@code id} (nothing is written); throws when nothing was saved. A value of
     * {@link JSONObject#NULL} in {@code extra} removes that field.
     */
    static synchronized JSONObject update(Context c, String id, String state, JSONObject extra) throws StorageException {
        return apply(c, id, state, extra, false);
    }

    private static JSONObject apply(Context c, String id, String state, JSONObject extra, boolean create) throws StorageException {
        JSONObject all = load(c);
        JSONObject snap;
        try {
            JSONObject recs = all.optJSONObject("records");
            if (recs == null) {
                recs = new JSONObject();
                all.put("records", recs);
            }
            if (state == null) {
                recs.remove(id);
                if (id.equals(all.optString("latest", null))) all.remove("latest");
                snap = new JSONObject().put("recordingId", id).put("state", "discarded");
            } else {
                JSONObject r = recs.optJSONObject(id);
                if (r == null) {
                    if (!create) return null;
                    r = new JSONObject().put("recordingId", id).put("startedAt", System.currentTimeMillis());
                    recs.put(id, r);
                    all.put("latest", id);
                }
                r.put("state", state);
                if (extra != null) {
                    for (java.util.Iterator<String> it = extra.keys(); it.hasNext(); ) {
                        String k = it.next();
                        Object v = extra.get(k);
                        if (v == JSONObject.NULL) r.remove(k);
                        else r.put(k, v);
                    }
                }
                snap = snapshot(r);
            }
            if (all.has(RECOVERED)) snap.put(RECOVERED, all.optString(RECOVERED));
        } catch (JSONException e) {
            throw new StorageException("index_encode_failed", e);
        }
        write(dir(c), all.toString().getBytes(StandardCharsets.UTF_8));
        Listener l = listener;
        if (l != null) l.changed(snap);
        refreshWidget(c);
        return snap;
    }

    private static void write(File d, byte[] data) throws StorageException {
        File f = new File(d, "index.json");
        File tmp = new File(d, "index.json.tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        } catch (IOException | SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            if (tmp.isFile()) tmp.delete();
            throw new StorageException("index_write_failed", e);
        }
    }

    private static final java.util.Map<String, String> RUNS = new java.util.HashMap<>();

    /**
     * A 2xx this process received for a record of generation {@code gen} whose "uploaded" write
     * failed (id -> generation and HTTP status). The next run of that generation saves "uploaded"
     * from it instead of sending the body again, so the page's non-durable "uploaded" is followed by
     * the durable one rather than by a second transfer that could fail. In memory only: after a
     * process death the record is still "uploading" on disk and is uploaded again (at least once).
     */
    private static final java.util.Map<String, String[]> RECEIVED = new java.util.HashMap<>();

    static synchronized void receivedUnsaved(String id, String gen, int status) {
        RECEIVED.put(id, new String[]{gen, Integer.toString(status)});
    }

    /** The HTTP status of an unsaved 2xx for this record and generation, or -1. */
    static synchronized int receivedFor(String id, String gen) {
        String[] g = RECEIVED.get(id);
        return g != null && g[0].equals(String.valueOf(gen)) ? Integer.parseInt(g[1]) : -1;
    }

    static synchronized void clearReceived(String id) {
        RECEIVED.remove(id);
    }

    /**
     * Claims {@code id} for one worker run. Returns the record, or null when this run may not
     * upload it (nothing is written): the record is gone or a tombstone, its {@code uploadGen} is not
     * {@code gen} (the work was replaced), or it is not waiting for an upload. A null {@code gen} is a
     * request with no generation (enqueued before generations existed, or scheduled by the service
     * after the "uploading" write failed); it may claim only a record that has none either, and a
     * "stopped" or "recording" one is moved to "uploading" here. A later claim of the same record
     * takes it from an earlier run.
     */
    static synchronized JSONObject claimUpload(Context c, String id, String gen, String run) throws StorageException {
        JSONObject r = get(c, id);
        if (r == null) return null;
        String state = r.optString("state");
        String has = r.has("uploadGen") ? r.optString("uploadGen") : null;
        if (has == null ? gen != null : !has.equals(gen)) return null;
        if (!"uploading".equals(state)) {
            if (gen != null || !("stopped".equals(state) || "recording".equals(state))) return null;
            // Null-generation work is scheduled only after a successful stop() (the service's
            // fallbacks when its own index write failed): the capture is final, and the claim says so.
            JSONObject fin;
            try {
                fin = new JSONObject().put(CAPTURE_FINAL, true);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            r = update(c, id, "uploading", fin);
            if (r == null) return null;
            r = get(c, id);
        }
        RUNS.put(id, run);
        return r;
    }

    /**
     * Saves a state for the run that holds the claim. Returns the snapshot, or null (nothing written)
     * when the claim is gone: a later run took it, the work was replaced, the record was discarded
     * or is a tombstone, or it is no longer "uploading". Throws when nothing was saved.
     */
    static synchronized JSONObject settleUpload(Context c, String id, String gen, String run, String state, JSONObject extra)
            throws StorageException {
        if (!run.equals(RUNS.get(id))) return null;
        JSONObject r = get(c, id);
        if (r == null || !"uploading".equals(r.optString("state"))) return null;
        String has = r.has("uploadGen") ? r.optString("uploadGen") : null;
        if (has == null ? gen != null : !has.equals(gen)) return null;
        return update(c, id, state, extra);
    }

    /**
     * The upload route's transition: only a record that is still "failed" or "uploaded" becomes
     * "uploading" with {@code fields} (the new URL, its route and a new generation), in one step
     * under the class lock. Returns the snapshot; null when the index does not hold the id; the
     * record's snapshot with {@code busy: true} when it is in any other state (a tombstone included),
     * in which case nothing is written; the record's snapshot with {@code notFinal: true} when its
     * capture never finalized (a failed start or stop: there is no audio to send), nothing written.
     * Throws when nothing was saved.
     */
    static synchronized JSONObject requeue(Context c, String id, JSONObject fields) throws StorageException {
        JSONObject r = get(c, id);
        if (r == null) return null;
        String s = r.optString("state");
        try {
            if (!"failed".equals(s) && !"uploaded".equals(s)) return snapshot(r).put("busy", true);
            if (!captureFinal(r)) return snapshot(r).put("notFinal", true);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return update(c, id, "uploading", fields);
    }

    /**
     * The service's and reconciliation's transition to "uploading" with generation {@code gen}: only
     * a finalized record that is "stopped", or "uploading" without a generation (a null-generation
     * claim), moves; anything else (a tombstone, a terminal state, a newer generation) returns null
     * and writes nothing. Throws when nothing was saved.
     */
    static synchronized JSONObject beginUpload(Context c, String id, String gen) throws StorageException {
        JSONObject r = get(c, id);
        if (r == null || !captureFinal(r)) return null;
        String s = r.optString("state");
        boolean noGen = !r.has("uploadGen");
        if (!("stopped".equals(s) || ("uploading".equals(s) && noGen))) return null;
        try {
            return update(c, id, "uploading", new JSONObject().put("uploadGen", gen));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * WorkManager refused to enqueue generation {@code gen}: the still-"uploading" record of that
     * generation becomes "failed" with reason not_scheduled (retryable by upload, discardable), so it
     * is never stranded as uploading. Anything else is left alone. Throws when nothing was saved.
     */
    static synchronized JSONObject failUnscheduled(Context c, String id, String gen) throws StorageException {
        JSONObject r = get(c, id);
        if (r == null || !"uploading".equals(r.optString("state")) || !gen.equals(r.optString("uploadGen", null))) return null;
        try {
            return update(c, id, "failed", new JSONObject().put("reason", "not_scheduled"));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A copy of every record (id -> record) for launch reconciliation. */
    static synchronized java.util.Map<String, JSONObject> records(Context c) throws StorageException {
        java.util.Map<String, JSONObject> out = new java.util.LinkedHashMap<>();
        JSONObject recs = load(c).optJSONObject("records");
        if (recs == null) return out;
        for (java.util.Iterator<String> it = recs.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject r = recs.optJSONObject(k);
            if (r == null) continue;
            try {
                out.put(k, new JSONObject(r.toString()));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return out;
    }

    static synchronized void releaseUpload(String id, String run) {
        if (run.equals(RUNS.get(id))) RUNS.remove(id);
    }

    /**
     * Crash-consistent discard. The record is first saved as a "discarding" tombstone, then the
     * audio file and its directory are deleted, and only then is the record removed. A process
     * death at any point leaves either the recording intact, or a tombstone that
     * {@link #recoverDiscards} finishes; audio is never left on disk without an index entry.
     *
     * @return the discarded snapshot (marked not durable when the final removal could not be
     *     saved; the tombstone then stays for recovery), or null when removal was not established:
     *     the directory could not be deleted, or its absence could not be confirmed because the
     *     recorder directory cannot be listed. The tombstone stays, and discard or recovery retries. A record that
     *     became busy (recording, starting, uploading) since the caller looked is not touched: the
     *     reply is its snapshot with {@code busy: true}.
     * @throws StorageException when the tombstone could not be saved: nothing changed.
     */
    static JSONObject discard(Context c, String id) throws StorageException {
        synchronized (RecorderStore.class) {
            JSONObject r = get(c, id);
            String s = r == null ? null : r.optString("state");
            if ("recording".equals(s) || "starting".equals(s) || "uploading".equals(s)) {
                try {
                    return snapshot(r).put("busy", true);
                } catch (JSONException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
            if (r != null && !"discarding".equals(s)) update(c, id, "discarding", null);
        }
        File parent = dir(c);
        File d = new File(parent, id);
        // The tombstoned recording's own directory only: its files, then the directory.
        File[] own = d.listFiles();
        if (own != null) {
            for (File f : own) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (!d.delete()) {
            // Not deleted by this call. Absence counts only when the recorder directory was listed
            // and does not name it: File.exists() is also false when the lookup itself fails, so it
            // never decides. Unlistable or still listed: removal not established, tombstone kept.
            String[] names = parent.list();
            if (names == null || Arrays.asList(names).contains(id)) return null;
        }
        try {
            return update(c, id, null, null);
        } catch (StorageException e) {
            notSaved(id, "discarded", e);
            try {
                return new JSONObject().put("recordingId", id).put("state", "discarded")
                        .put("durable", false).put("storage", e.reason);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** Finishes every discard a previous process left as a tombstone; returns the ids finished. */
    static java.util.List<String> recoverDiscards(Context c) throws StorageException {
        JSONObject recs = load(c).optJSONObject("records");
        java.util.List<String> done = new java.util.ArrayList<>();
        if (recs == null) return done;
        for (java.util.Iterator<String> it = recs.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject r = recs.optJSONObject(id);
            if (r != null && "discarding".equals(r.optString("state"))) done.add(id);
        }
        java.util.List<String> finished = new java.util.ArrayList<>();
        for (String id : done) {
            if (discard(c, id) != null) finished.add(id);
        }
        return finished;
    }

    /** Logs and emits a state the index does not hold, marked not durable. */
    static void notSaved(String id, String state, StorageException e) {
        Log.w(TAG, "recorder index not saved: " + e.reason);
        Listener l = listener;
        if (l == null) return;
        try {
            l.changed(new JSONObject().put("recordingId", id).put("state", state)
                    .put("durable", false).put("storage", e.reason));
        } catch (JSONException ignored) {
            // put() with non-null keys and string or boolean values cannot throw.
        }
    }

    static JSONObject snapshot(JSONObject r) throws JSONException {
        JSONObject s = new JSONObject();
        s.put("recordingId", r.optString("recordingId"));
        s.put("state", r.optString("state", "idle"));
        s.put("durationMs", r.optLong("durationMs", 0));
        s.put("bytes", r.optLong("bytes", 0));
        if (r.has("httpStatus")) s.put("httpStatus", r.optInt("httpStatus"));
        // Why a record failed (url_not_approved, no_file, http, network, policy_unavailable, and on Android
        // record_failed for a capture that never finalized and not_scheduled for an upload WorkManager refused).
        if ("failed".equals(r.optString("state")) && r.has("reason")) s.put("reason", r.optString("reason"));
        return s;
    }

    static void refreshWidget(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, StatusWidgetProvider.class));
        if (ids.length == 0) return;
        Intent i = new Intent(c, StatusWidgetProvider.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        c.sendBroadcast(i);
    }

    private RecorderStore() {
    }
}
