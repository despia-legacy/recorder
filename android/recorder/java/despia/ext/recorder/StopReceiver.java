package despia.ext.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification "Stop" action. Not exported: only this app's PendingIntent can reach it. */
public final class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (RecorderService.isRunning()) {
            context.startService(new Intent(context, RecorderService.class).setAction(RecorderService.ACTION_STOP)
                    .putExtra("id", RecorderService.owner()));
        }
    }
}
