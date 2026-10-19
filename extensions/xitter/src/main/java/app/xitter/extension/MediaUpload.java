package app.xitter.extension;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Photos: X's app uploads a post's image in one request, the whole file as the multipart "media" part
 * and no command (the older "simple" upload). The instance takes the web client's chunked
 * upload (INIT, APPEND, FINALIZE), so the one request is made those three here; the app gets
 * FINALIZE's answer, the media id it attaches to the post.
 */
final class MediaUpload {
    private MediaUpload() {}

    /** An upload request without a command, with its file. */
    static boolean handles(HttpUrl url, Request request) {
        if (!"POST".equals(request.method()) || request.body() == null) return false;
        String path = url.encodedPath();
        if (!path.endsWith("/media/upload.json") && !path.endsWith("/media/upload2.json")) return false;
        if (url.queryParameter("command") != null) return false;
        MediaType type = request.body().contentType();
        return type != null && "multipart".equals(type.type());
    }

    static Response handle(Interceptor.Chain chain, Request request, HttpUrl target) throws IOException {
        MediaType type = request.body().contentType();
        String boundary = type == null ? null : type.parameter("boundary");
        byte[] body = Bodies.read(request.body());
        Part media = boundary == null ? null : part(body, boundary, "media");
        if (media == null) return chain.proceed(request.newBuilder().url(target).build());
        Uploaded up = upload(chain, request, target.newBuilder().query(null).build(), media.data, media.type, "tweet_image");
        return up.response;
    }

    /**
     * An avatar or banner: X's app sends the image itself to update_profile_image.json or
     * update_profile_banner.json (as "image" or "banner"); the instance takes the media_id of an
     * upload, as the web client sends it. When the image was picked as a GIF, the GIF is uploaded
     * (ProfileMedia), so it stays animated.
     */
    static boolean handlesProfileImage(HttpUrl url, Request request) {
        if (!"POST".equals(request.method()) || request.body() == null) return false;
        String path = url.encodedPath();
        if (!path.endsWith("/account/update_profile_image.json") && !path.endsWith("/account/update_profile_banner.json")) return false;
        MediaType type = request.body().contentType();
        return type != null && "multipart".equals(type.type());
    }

    static Response handleProfileImage(Interceptor.Chain chain, Request request, HttpUrl target) throws IOException {
        MediaType type = request.body().contentType();
        String boundary = type == null ? null : type.parameter("boundary");
        byte[] body = Bodies.read(request.body());
        boolean banner = target.encodedPath().endsWith("/update_profile_banner.json");
        Part image = boundary == null ? null : part(body, boundary, banner ? "banner" : "image");
        if (image == null) return chain.proceed(request.newBuilder().url(target).build());
        byte[] gif = ProfileMedia.takeRecentGif();
        byte[] data = gif != null ? gif : image.data;
        String mime = gif != null ? "image/gif" : image.type;
        String path = target.encodedPath();
        HttpUrl upload = target.newBuilder().encodedPath(path.substring(0, path.lastIndexOf("/account/")) + "/media/upload.json").query(null).build();
        Uploaded up = upload(chain, request, upload, data, mime, gif != null ? "tweet_gif" : "tweet_image");
        if (up.mediaId == null) return up.response;
        up.response.close();
        RequestBody form = new okhttp3.FormBody.Builder().add("media_id", up.mediaId).build();
        return chain.proceed(request.newBuilder().url(target).post(form).build());
    }

    private static final class Uploaded {
        final Response response;
        final String mediaId;

        Uploaded(Response response, String mediaId) {
            this.response = response;
            this.mediaId = mediaId;
        }
    }

    // the instance's chunked upload of one file: INIT, APPEND (one segment), FINALIZE
    private static Uploaded upload(Interceptor.Chain chain, Request request, HttpUrl base, byte[] data, String type, String category) throws IOException {
        Response init = chain.proceed(request.newBuilder().url(base.newBuilder()
                .addQueryParameter("command", "INIT")
                .addQueryParameter("total_bytes", String.valueOf(data.length))
                .addQueryParameter("media_type", type)
                .addQueryParameter("media_category", category)
                .build()).post(RequestBody.create(new byte[0], null)).build());
        if (!init.isSuccessful() || init.body() == null) return new Uploaded(init, null);
        String id;
        try {
            id = new JSONObject(init.body().string()).getString("media_id_string");
        } catch (JSONException e) {
            throw new IOException("media upload: no media id", e);
        }
        RequestBody file = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("media", "blob", RequestBody.create(data, MediaType.parse(type)))
                .build();
        Response append = chain.proceed(request.newBuilder().url(base.newBuilder()
                .addQueryParameter("command", "APPEND")
                .addQueryParameter("media_id", id)
                .addQueryParameter("segment_index", "0")
                .build()).post(file).build());
        if (!append.isSuccessful()) return new Uploaded(append, null);
        append.close();
        Response finalize = chain.proceed(request.newBuilder().url(base.newBuilder()
                .addQueryParameter("command", "FINALIZE")
                .addQueryParameter("media_id", id)
                .build()).post(RequestBody.create(new byte[0], null)).build());
        return new Uploaded(finalize, finalize.isSuccessful() ? id : null);
    }

    private static final class Part {
        final String type;
        final byte[] data;

        Part(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    // a named part of a multipart body: its content type and bytes
    private static Part part(byte[] body, String boundary, String name) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(body, delimiter, 0);
        while (at >= 0) {
            int headersStart = at + delimiter.length + 2;
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headersStart);
            if (headersEnd < 0) return null;
            int next = indexOf(body, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), headersEnd + 4);
            if (next < 0) return null;
            String headers = new String(body, headersStart, headersEnd - headersStart, StandardCharsets.ISO_8859_1);
            if (headers.contains("name=\"" + name + "\"")) {
                String type = "application/octet-stream";
                for (String line : headers.split("\r\n")) {
                    if (line.regionMatches(true, 0, "Content-Type:", 0, 13)) type = line.substring(13).split(";")[0].trim();
                }
                byte[] data = new byte[next - (headersEnd + 4)];
                System.arraycopy(body, headersEnd + 4, data, 0, data.length);
                return new Part(type, data);
            }
            at = next + 2;
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
