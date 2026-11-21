package despia.ext.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONException;
import org.json.JSONObject;

/** Foreground microphone service. Its notification is the recorder's lock-screen presence. */
public final class RecorderService extends Service {
    static final String ACTION_START = "despia.ext.recorder.START";
    static final String ACTION_STOP = "despia.ext.recorder.STOP";
    private static final String CHANNEL = "nx_recorder";
    private static final int NOTIFICATION_ID = 0x4e58;
    private static volatile boolean running;
    /** The recording that owns the service, reserved by start() before the service exists. */
    private static final RecorderOwner OWNER = new RecorderOwner();

    private MediaRecorder recorder;
    private String id;
    private long startedAt;

    static boolean isRunning() {
        return running;
    }

    /** False when another recording owns or is about to own the microphone. */
    static boolean reserve(String id) {
        return OWNER.reserve(id);
    }

    /** Gives back the matching reservation, including after a service start failure. */
    static void release(String id) {
        OWNER.release(id);
    }

    static String owner() {
        return OWNER.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action) && !running) {
            id = intent.getStringExtra("id");
            if (id == null || !id.equals(OWNER.get())) {
                // Started with startForegroundService(): enter the foreground before stopping even
                // here (no owner, e.g. a stop that overtook this start), or Android 8+ kills the
                // app with ForegroundServiceDidNotStartInTimeException.
                try {
                    startInForeground();
                } catch (RuntimeException ignored) {
                    // A refused foreground start leaves nothing to clean up beyond stopping.
                }
                id = null;
                stopEverything(startId);
                return START_NOT_STICKY;
            }
            try {
                // Inside the failure path: a refused foreground start must not leave "starting" behind.
                startInForeground();
                recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioChannels(1);
                recorder.setOutputFile(RecorderStore.audioFile(this, id).getAbsolutePath());
                recorder.prepare();
                recorder.start();
                running = true;
                startedAt = SystemClock.elapsedRealtime();
            } catch (Exception e) {
                // Capture never finalized: "record_failed" (never captureFinal), so upload refuses it.
                save(id, "failed", RecorderStore.captureFailed());
                stopEverything(startId);
                return START_NOT_STICKY;
            }
            // Recording either way: an unsaved "recording" is reported, and the stop saves over it.
            save(id, "recording", null);
        } else if (ACTION_STOP.equals(action) && running && id != null && id.equals(intent.getStringExtra("id"))) {
            finishRecording(startId);
        } else if (ACTION_STOP.equals(action) && !running) {
            // A stop for the owner that reached the service before its start did: cancel it, so the
            // start that follows finds no owner and never opens the microphone.
            String stopId = intent.getStringExtra("id");
            if (OWNER.release(stopId)) {
                save(stopId, "failed", null);
            }
            stopEverything(startId);
        } else if (!running) {
            stopEverything(startId);
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent stop = PendingIntent.getBroadcast(this, 0, new Intent(this, StopReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Recording")
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, "Stop", stop)
                .build();
        int type = Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);
    }

    /** {@code startId} is the command being handled, or {@link #DESTROYING} from onDestroy. */
    private void finishRecording(int startId) {
        long duration = SystemClock.elapsedRealtime() - startedAt;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // stop() throws when no audio was captured; the file is unusable either way, and it is
            // never marked captureFinal, so neither the upload route nor the worker sends it.
            save(id, "failed", RecorderStore.captureFailed());
            stopEverything(startId);
            return;
        }
        JSONObject extra = new JSONObject();
        try {
            // captureFinal only here, in the same write as "stopped": stop() returned, the container is final.
            extra.put("durationMs", duration).put("bytes", RecorderStore.audioFile(this, id).length())
                    .put(RecorderStore.CAPTURE_FINAL, true);
        } catch (JSONException ignored) {
            // put() with a non-null key and a long cannot throw.
        }
        if (save(id, "stopped", extra)) {
            try {
                UploadWorker.enqueue(this, id);
            } catch (RecorderStore.StorageException e) {
                RecorderStore.notSaved(id, "uploading", e);
                UploadWorker.schedule(this, id, null);
            }
        } else {
            // The file is final: the worker uploads it and retries its own index write until
            // storage recovers, rather than stranding it as "recording".
            UploadWorker.schedule(this, id, null);
        }
        stopEverything(startId);
    }

    /** False, after reporting it as not durable, when the state could not be saved. */
    private boolean save(String recordingId, String state, JSONObject extra) {
        try {
            // Null: the recording is no longer in the index (discarded); nothing was written.
            return RecorderStore.update(this, recordingId, state, extra) != null;
        } catch (RecorderStore.StorageException e) {
            RecorderStore.notSaved(recordingId, state, e);
            return false;
        }
    }

    /** onDestroy's finish: the service is already being destroyed, so no stop request is made. */
    private static final int DESTROYING = Integer.MIN_VALUE;

    /**
     * Ends this command's work. Stops the service only by {@code stopSelfResult} with this command's id: when
     * a newer command (a START for the next recording) is already delivered, the service stays for
     * it, so cleanup of an older command can never end a newer recording.
     */
    private void stopEverything(int startId) {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        running = false;
        release(id);
        id = null;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        if (startId != DESTROYING) stopSelfResult(startId);
    }

    @Override
    public void onDestroy() {
        if (running) finishRecording(DESTROYING);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
