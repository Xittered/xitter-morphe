package app.xitter.extension;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Animated avatars and banners. X's app crops a picked image and uploads the crop as a JPEG,
 * which loses a GIF's animation. The last image the app opened from the device is noted here;
 * when it was a GIF, the profile image saved from it is the GIF itself (uncropped: the instance
 * draws avatars and banners centre-cropped, as the web client's GIF uploads without a crop).
 */
final class ProfileMedia {
    private ProfileMedia() {}

    // long enough to crop and save, short enough not to apply to a later, unrelated save
    private static final long FRESH_MS = 15 * 60 * 1000;

    // larger than the instance takes for a GIF; such a pick isn't kept
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private static byte[] lastGif;
    private static long openedAt;

    /**
     * Every ContentResolver.openInputStream(Uri) of the app's comes here. A picked GIF is read
     * here and kept: the picker's grant to it doesn't last until the profile is saved.
     */
    static InputStream openInputStream(ContentResolver contentResolver, Uri uri) throws FileNotFoundException {
        String type = null;
        try {
            type = contentResolver.getType(uri);
        } catch (RuntimeException ignored) {
            // a provider that can't say its type: nothing to note
        }
        // the app's own files (its crop of the pick) have no type and change nothing
        if (type == null || !type.startsWith("image/")) return contentResolver.openInputStream(uri);
        if (!type.equals("image/gif")) {
            synchronized (ProfileMedia.class) {
                lastGif = null;
            }
            return contentResolver.openInputStream(uri);
        }
        byte[] data;
        try (InputStream in = contentResolver.openInputStream(uri)) {
            if (in == null) return null;
            data = readAll(in);
        } catch (IOException e) {
            FileNotFoundException f = new FileNotFoundException(e.getMessage());
            f.initCause(e);
            throw f;
        }
        synchronized (ProfileMedia.class) {
            lastGif = data != null && data.length <= MAX_BYTES ? data : null;
            openedAt = SystemClock.elapsedRealtime();
        }
        return new java.io.ByteArrayInputStream(data == null ? new byte[0] : data);
    }

    /** The GIF just picked, once; null when the last picked image wasn't a GIF. */
    static byte[] takeRecentGif() {
        synchronized (ProfileMedia.class) {
            byte[] data = lastGif;
            lastGif = null;
            if (data == null || SystemClock.elapsedRealtime() - openedAt >= FRESH_MS) return null;
            // a GIF by its header, not only by what the provider said
            return data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' ? data : null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
