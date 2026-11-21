package despia.ext.recorder;

import java.util.concurrent.atomic.AtomicReference;

/** The single microphone reservation shared by the bridge and the foreground service. */
final class RecorderOwner {
    private final AtomicReference<String> current = new AtomicReference<>();

    boolean reserve(String id) {
        return current.compareAndSet(null, id);
    }

    String get() {
        return current.get();
    }

    boolean release(String id) {
        String observed = current.get();
        // Intent delivery preserves the ID value, but may construct a different String.
        return observed != null && observed.equals(id) && current.compareAndSet(observed, null);
    }
}
