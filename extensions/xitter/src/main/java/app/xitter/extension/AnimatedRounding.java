package app.xitter.extension;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.lang.reflect.Method;

/**
 * Rounded images (avatars) that animate: the app rounds them with Fresco's bitmap-only rounding,
 * which leaves an animated image (a GIF avatar from the instance) square. The image view is
 * also clipped to its corner radii, read when the outline is drawn; a still image is already
 * rounded the same way, so it looks no different.
 */
public final class AnimatedRounding {
    private AnimatedRounding() {}

    private static final ViewOutlineProvider ROUNDED = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            float[] radii = radii(view);
            int w = view.getWidth(), h = view.getHeight();
            if (radii == null || w == 0 || h == 0) {
                ViewOutlineProvider.BACKGROUND.getOutline(view, outline);
                return;
            }
            // one radius for the outline (avatars are round or evenly rounded), at most a circle
            float r = Math.min(radii[0], Math.min(w, h) / 2f);
            outline.setRoundRect(0, 0, w, h, r);
        }
    };

    private static Method cornerRadii;

    /** Called by the patched view when its rounding is set. */
    public static void clip(View view) {
        if (view.getOutlineProvider() != ROUNDED) {
            view.setOutlineProvider(ROUNDED);
            view.setClipToOutline(true);
        } else {
            view.invalidateOutline();
        }
    }

    private static float[] radii(View view) {
        try {
            if (cornerRadii == null) cornerRadii = view.getClass().getMethod("getCornerRadii");
            float[] r = (float[]) cornerRadii.invoke(view);
            if (r == null || r.length < 8) return null;
            for (float v : r) if (v > 0) return r;
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
