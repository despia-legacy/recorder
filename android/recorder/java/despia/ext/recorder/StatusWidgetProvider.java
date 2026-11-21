package despia.ext.recorder;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Home-screen (and, where the device supports it, keyguard) status widget. */
public final class StatusWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        JSONObject r;
        try {
            r = RecorderStore.get(context, null);
        } catch (RecorderStore.StorageException e) {
            // Unknown is not idle: keep what the widget shows until the index reads again.
            return;
        }
        String state = r == null ? "idle" : r.optString("state", "idle");
        for (int id : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.nx_recorder_widget);
            v.setTextViewText(R.id.nx_recorder_state, label(state));
            manager.updateAppWidget(id, v);
        }
    }

    static String label(String state) {
        switch (state) {
            case "recording": return "Recording";
            case "interrupted": return "Paused";
            case "uploading": return "Uploading";
            case "uploaded": return "Uploaded";
            case "failed": return "Upload failed";
            case "stopped": return "Stopped";
            default: return "Idle";
        }
    }
}
