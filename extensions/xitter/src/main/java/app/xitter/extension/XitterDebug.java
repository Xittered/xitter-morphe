package app.xitter.extension;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * X's app reports the errors it recovers from (a response it couldn't parse, a screen that failed
 * to load) to its own crash service, which the guard blocks; nothing reaches the log. With
 * `adb shell setprop log.tag.XitterErrors DEBUG` they are written to logcat instead.
 */
@SuppressWarnings("unused")
public final class XitterDebug {
    private static final String TAG = "XitterErrors";

    private XitterDebug() {}

    /** Called with each report X's error reporter receives (an object holding the Throwable). */
    public static void report(Object report) {
        try {
            if (report == null || !Log.isLoggable(TAG, Log.DEBUG)) return;
            if (report instanceof Throwable) {
                Log.d(TAG, "X reported", (Throwable) report);
                return;
            }
            for (Class<?> c = report.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Throwable.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Throwable t = (Throwable) f.get(report);
                    if (t != null) Log.d(TAG, "X reported", t);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
