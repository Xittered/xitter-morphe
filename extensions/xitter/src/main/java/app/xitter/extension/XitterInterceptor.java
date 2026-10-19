package app.xitter.extension;

import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Last application interceptor of every OkHttp client in the app, so it sees requests as X's
 * own interceptors left them (some sign only requests for X's hosts). Requests for X go to the
 * instance; what only X's servers would answer is answered here; requests the guard refuses
 * fail before any connection is made. Signing is left to {@link Auth}, a network interceptor,
 * since X adds some signatures at that level.
 */
final class XitterInterceptor implements Interceptor {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        HttpUrl url = request.url();
        String host = url.host();
        boolean x = XitterNetwork.isXHost(host);
        if (!x && !XitterNetwork.isInstanceHost(host)) {
            if (!XitterNetwork.isAllowed(host)) {
                XitterNetwork.blocked(url.scheme() + "://" + host + url.encodedPath());
                throw new IOException("Blocked by Xitter: " + host);
            }
            return chain.proceed(request); // a third-party host the options allow
        }

        String path = url.encodedPath();
        // analytics; nothing on the instance wants them
        if (isTelemetry(path)) return empty(request, "{}");
        Response local = localAnswer(request, path);
        if (local != null) return log(request, url, local, "local");

        HttpUrl target = x ? HttpUrl.get(XitterNetwork.rewrite(url.toString())) : url;
        Request out = request.newBuilder().url(target).build();

        // X's sign-in and sign-up screens, answered on the device with the instance's sign-in
        if (LoginShim.handles(url)) return LoginShim.handle(chain, request, out);

        return chain.proceed(out);
    }

    /**
     * Network interceptor, last of each client's: turns X's OAuth signature into the Xitter
     * session the token stands for (its session cookie and csrf header).
     */
    static final class Auth implements Interceptor {
        private static final Pattern OAUTH_TOKEN = Pattern.compile("oauth_token=\"([^\"]*)\"");

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            if (!XitterNetwork.isInstanceHost(request.url().host())) return chain.proceed(request);
            Request.Builder out = request.newBuilder();
            sign(request, out);
            Request signed = out.build();
            return log(signed, signed.url(), chain.proceed(signed), signed.header("x-csrf-token") != null ? "session" : "signed out");
        }

        private static void sign(Request request, Request.Builder out) {
            String authorization = request.header("Authorization");
            out.removeHeader("Authorization");
            out.removeHeader("X-Guest-Token");
            out.removeHeader("Cookie");
            out.removeHeader("x-csrf-token");
            String token = null;
            if (authorization != null && authorization.startsWith("OAuth ")) {
                Matcher m = OAUTH_TOKEN.matcher(authorization);
                if (m.find()) token = m.group(1);
            }
            Session s = Session.fromOAuthToken(token);
            if (s != null) {
                out.header("Cookie", "auth_token=" + s.authToken + "; ct0=" + s.csrf);
                out.header("x-csrf-token", s.csrf);
                out.header("x-twitter-auth-type", "OAuth2Session");
            }
            String app = XitterConfig.clientToken();
            if (app != null && !app.trim().isEmpty()) out.header("Authorization", "Bearer " + app.trim());
        }
    }

    // adb shell setprop log.tag.Xitter DEBUG, then adb logcat -s Xitter
    static Response log(Request request, HttpUrl target, Response response, String how) {
        if (Log.isLoggable(XitterNetwork.TAG, Log.DEBUG)) {
            String query = target.encodedQuery();
            if (query != null && query.length() > 300) query = query.substring(0, 300) + "…";
            Log.d(XitterNetwork.TAG, request.method() + " " + target.encodedPath() + (query == null ? "" : "?" + query)
                    + " -> " + response.code() + " [" + how + "]");
        }
        return response;
    }

    /**
     * What only X's servers would answer and the instance has no use for: the app and guest
     * tokens a signed-out app fetches, and Play Integrity and key attestation, which X checks
     * before signing in. Every check passes with a token nothing reads.
     */
    private static Response localAnswer(Request request, String path) {
        if (path.endsWith("/oauth2/token")) {
            return empty(request, "{\"token_type\":\"bearer\",\"access_token\":\"xitter\"}");
        }
        if (path.endsWith("/1.1/guest/activate.json")) {
            return empty(request, "{\"guest_token\":\"1\"}");
        }
        // X's feature switches for the app; none set, so the app keeps its built-in defaults
        if (path.endsWith("/1.1/help/settings.json")) {
            return empty(request, "{\"config\":{},\"impressions\":[]}");
        }
        if (path.endsWith("/GenerateAttestationNonce")) {
            return empty(request, "{\"data\":{\"generate_attestation_nonce\":{\"nonce\":\"eGl0dGVy\"}}}");
        }
        if (path.endsWith("/VerifyAndroidAttestationKeysMutation")) {
            return empty(request, "{\"data\":{\"verify_android_key_attestation\":true}}");
        }
        if (path.endsWith("/GenerateAttestationTokenV2")) {
            return empty(request, "{\"data\":{\"verify_play_integrity_attestation_v2\":{\"token\":\"xitter\",\"expires_in\":86400}}}");
        }
        if (path.endsWith("/ExchangeClientDataForAttestationTokenMutation")) {
            return empty(request, "{\"data\":{\"exchange_android_attestation_for_token\":{\"token\":\"xitter\",\"expires_in\":86400}}}");
        }
        return null;
    }

    private static boolean isTelemetry(String path) {
        return path.startsWith("/1.1/jot/") || path.startsWith("/i/jot") || path.contains("/scribe")
                || path.endsWith("/client_event.json") || path.startsWith("/1.1/attribution/");
    }

    static Response empty(Request request, String json) {
        return json(request, 200, json);
    }

    static Response json(Request request, int code, String json) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Error")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(ResponseBody.create(json, JSON))
                .build();
    }
}
