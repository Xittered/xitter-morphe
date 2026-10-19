package app.xitter.extension;

import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * A profile header's badge after the name (the instance's pride flags travel as an affiliate
 * badge). X's header only draws it for verified accounts; the patched header calls this at the
 * end, and an unverified account's badge is added the way the header adds it for verified ones
 * (com.twitter.ui.user.j, which loads the image).
 */
public final class HeaderBadge {
    private HeaderBadge() {}

    private static Method factory;
    private static Field span;

    // the header's name method, between its start and its end (the main thread)
    private static TextView pendingName;
    private static Object pendingStatus, pendingBadge;

    /** At the start of the header's name method: its name view, verified status and badge. */
    public static void begin(TextView name, Object verifiedStatus, Object badge) {
        pendingName = name;
        pendingStatus = verifiedStatus;
        pendingBadge = badge;
    }

    /** At its end, once the name is written. */
    public static void end() {
        TextView name = pendingName;
        Object status = pendingStatus, badge = pendingBadge;
        pendingName = null;
        pendingStatus = pendingBadge = null;
        add(name, status, badge);
    }

    private static void add(TextView name, Object verifiedStatus, Object badge) {
        if (name == null || badge == null || verifiedStatus == null) return;
        try {
            // verified: the header has drawn it already. An unverified account's status is
            // VerifiedStatus.None (the getter's name doesn't survive in the app).
            if (!verifiedStatus.getClass().getName().endsWith("$None")) return;
            if (factory == null) find(badge.getClass());
            Object made = factory.invoke(null, name.getRootView(), badge);
            Object image = made == null ? null : span.get(made);
            if (image == null) return;
            SpannableStringBuilder text = new SpannableStringBuilder(name.getText());
            text.append(" ").append(" ");
            text.setSpan(image, text.length() - 1, text.length(), 33);
            name.setText(text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(XitterNetwork.TAG, "profile header badge", e);
        }
    }

    // j's static (View, badge) -> j, and its span field (the one that isn't the description)
    private static void find(Class<?> badgeType) throws ReflectiveOperationException {
        Class<?> j = Class.forName("com.twitter.ui.user.j");
        for (Method m : j.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == j && p.length == 2
                    && p[0] == View.class && p[1].isAssignableFrom(badgeType)) {
                m.setAccessible(true);
                factory = m;
            }
        }
        for (Field f : j.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() != String.class) {
                f.setAccessible(true);
                span = f;
            }
        }
        if (factory == null || span == null) throw new NoSuchMethodException("com.twitter.ui.user.j badge");
    }
}
