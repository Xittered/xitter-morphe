package app.xitter.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.zip.GZIPInputStream;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Request bodies, read without naming okio: X's build renames okio, so a compiled reference to
 * okio.Buffer or BufferedSource would not resolve. An empty ResponseBody's source is an okio
 * Buffer, which is also a sink; the body is written into it and read back as another
 * ResponseBody. The methods taking okio types are called by reflection.
 */
final class Bodies {
    private Bodies() {}

    private static Method writeTo, wrap;

    static byte[] read(RequestBody body) throws IOException {
        try {
            Object buffer = ResponseBody.class.getMethod("source").invoke(ResponseBody.create(new byte[0], null));
            if (writeTo == null || wrap == null) {
                for (Method m : RequestBody.class.getMethods()) {
                    if (m.getName().equals("writeTo") && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0].isInstance(buffer)) writeTo = m;
                }
                for (Method m : ResponseBody.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("create") && p.length == 3
                            && p[0].isInstance(buffer) && p[1] == MediaType.class && p[2] == long.class) wrap = m;
                }
                if (writeTo == null || wrap == null) throw new IOException("no okio buffer");
            }
            writeTo.invoke(body, buffer);
            return ((ResponseBody) wrap.invoke(null, buffer, null, -1L)).bytes();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not read a request body", e);
        }
    }

    /**
     * X compresses larger request bodies (gzip, asked for by an Optimize-Body header); the
     * instance reads bodies as they come, so they are sent uncompressed.
     */
    static Request uncompressed(Request request) throws IOException {
        RequestBody body = request.body();
        if (body == null || !"gzip".equalsIgnoreCase(request.header("Content-Encoding"))) return request;
        byte[] plain = gunzip(read(body));
        return request.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Optimize-Body")
                .method(request.method(), RequestBody.create(plain, body.contentType()))
                .build();
    }

    static byte[] gunzip(byte[] bytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length * 4);
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
            return out.toByteArray();
        }
    }
}
