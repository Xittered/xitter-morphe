package app.xitter.extension;

import android.util.Log;

import java.io.IOException;

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

        // what the instance reads: uncompressed bodies
        request = withFormFields(Bodies.uncompressed(request), path);
        HttpUrl target = x ? HttpUrl.get(XitterNetwork.rewrite(url.toString())) : url;
        // a photo sent in one request: the instance's chunked upload (MediaUpload)
        if (MediaUpload.handles(target, request)) return log(request, target, MediaUpload.handle(chain, request, target), "upload");
        // the app's GraphQL operation, and the one the instance answers for it
        String operation = null;
        if (target.encodedPath().contains("/graphql/")) {
            java.util.List<String> segments = target.pathSegments();
            operation = segments.get(segments.size() - 1);
            String server = Operations.serverName(operation);
            // followers and following: their page, and their timelines by id (Connections)
            try {
                if (Connections.isPage(operation)) {
                    return log(request, target, json(request, 200, Connections.page(target.queryParameter("variables"))), "local");
                }
                String[] byId = Connections.timeline(operation, target.queryParameter("variables"));
                if (byId != null) {
                    server = byId[0];
                    target = target.newBuilder().setQueryParameter("variables", byId[1]).build();
                }
            } catch (org.json.JSONException e) {
                Log.w(XitterNetwork.TAG, "followers page", e);
            }
            if (!server.equals(operation)) target = target.newBuilder().setPathSegment(segments.size() - 1, server).build();
            // and its variables, named as the web operation names them
            String vars = target.queryParameter("variables");
            if (vars != null) target = target.newBuilder().setQueryParameter("variables", Operations.variables(operation, vars)).build();
            request = withBodyVariables(request, operation);
        }
        Request.Builder rewritten = request.newBuilder().url(target);
        boolean graphql = target.encodedPath().contains("/graphql/");
        // GraphQL answers are reshaped (Shapes), so they must be readable here: gzip, not
        // the brotli X asks for; the debug log wants them plain
        if (Log.isLoggable(XitterNetwork.TAG + "Body", Log.DEBUG)) rewritten.header("Accept-Encoding", "identity");
        else if (graphql) rewritten.header("Accept-Encoding", "gzip");
        Request out = rewritten.build();
        if (Log.isLoggable(XitterNetwork.TAG + "Body", Log.DEBUG) && out.body() != null) {
            try {
                Log.d(XitterNetwork.TAG + "Body", "request " + target.encodedPath() + " " + new String(Bodies.read(out.body()), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }

        // the notifications tab and search, from the instance's GraphQL timelines
        V2Timelines.Source v2 = V2Timelines.source(target);
        if (v2 != null) return log(request, target, V2Timelines.handle(chain, request, target, v2), v2.operation);

        // X's sign-in and sign-up screens, answered on the device with the instance's sign-in
        if (LoginShim.handles(url)) return LoginShim.handle(chain, request, out);

        Response response;
        try {
            response = chain.proceed(out);
        } catch (IOException e) {
            if (Log.isLoggable(XitterNetwork.TAG, Log.DEBUG)) Log.d(XitterNetwork.TAG, out.method() + " " + target.encodedPath() + " failed: " + e);
            throw e;
        }
        if (graphql) response = reshaped(chain, out, target, operation, response);
        return logBody(target, response);
    }

    private static Response reshaped(Chain chain, Request request, HttpUrl target, String operation, Response response) throws IOException {
        ResponseBody body = response.body();
        MediaType type = body == null ? null : body.contentType();
        if (body == null || type == null || !"json".equals(type.subtype())) return response;
        byte[] bytes = body.bytes();
        if ("gzip".equalsIgnoreCase(response.header("Content-Encoding"))) bytes = Bodies.gunzip(bytes);
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // a repost: the app stores it, so it gets the repost itself (Reposts)
        if (response.isSuccessful() && Reposts.handles(operation)) json = Reposts.complete(chain, request, target, json);
        // chat key backup: the app only trusts X's own realm addresses; the instance's realm is
        // named as one of them, and requests to it come back to the instance (XitterNetwork.mapPath)
        XitterNetwork.Instance instance = XitterNetwork.instance();
        json = json.replace(instance.origin + instance.base + "/juicebox/", XitterNetwork.PINNED_REALM);
        try {
            json = Shapes.reshape(operation, json);
        } catch (Exception e) {
            Log.w(XitterNetwork.TAG, "could not reshape " + response.request().url().encodedPath(), e);
        }
        return response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(ResponseBody.create(json, type))
                .build();
    }

    // adb shell setprop log.tag.XitterBody DEBUG: the start of each response, decompressed
    private static Response logBody(HttpUrl target, Response response) {
        if (Log.isLoggable(XitterNetwork.TAG + "Body", Log.DEBUG)) {
            try {
                Request q = response.request();
                Log.d(XitterNetwork.TAG + "Body", target.encodedPath() + " accept=" + q.header("Accept") + " accept-encoding=" + q.header("Accept-Encoding")
                        + " type=" + response.header("Content-Type") + " encoding=" + response.header("Content-Encoding")
                        + " " + response.peekBody(4000).string());
            } catch (IOException ignored) {
            }
        }
        return response;
    }

    /**
     * Network interceptor, last of each client's: turns X's OAuth signature into the Xitter
     * session the token stands for (its session cookie and csrf header).
     */
    static final class Auth implements Interceptor {
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
            Session s = Session.fromAuthorization(authorization);
            if (s != null) {
                out.header("Cookie", "auth_token=" + s.authToken + "; ct0=" + s.csrf);
                out.header("x-csrf-token", s.csrf);
                out.header("x-twitter-auth-type", "OAuth2Session");
            }
            String app = XitterConfig.clientToken();
            if (app != null && !app.trim().isEmpty()) out.header("Authorization", "Bearer " + app.trim());
        }
    }

    // form fields the app names otherwise than the web client: a password change's new password
    private static Request withFormFields(Request request, String path) throws IOException {
        okhttp3.RequestBody body = request.body();
        if (body == null || !path.endsWith("/account/change_password.json")) return request;
        String form = new String(Bodies.read(body), java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String field : form.split("&")) {
            if (out.length() > 0) out.append('&');
            out.append(field.startsWith("password=") ? "new_" + field : field);
        }
        return request.newBuilder().method(request.method(), okhttp3.RequestBody.create(out.toString(), body.contentType())).build();
    }

    // a POST's variables, in its JSON body
    private static Request withBodyVariables(Request request, String operation) throws IOException {
        okhttp3.RequestBody body = request.body();
        MediaType type = body == null ? null : body.contentType();
        if (body == null || type == null || !"json".equals(type.subtype())) return request;
        try {
            org.json.JSONObject json = new org.json.JSONObject(new String(Bodies.read(body), java.nio.charset.StandardCharsets.UTF_8));
            Object vars = json.opt("variables");
            if (vars == null) return request;
            String renamed = Operations.variables(operation, vars.toString());
            if (renamed.equals(vars.toString())) return request;
            json.put("variables", vars instanceof String ? renamed : new org.json.JSONObject(renamed));
            return request.newBuilder().method(request.method(), okhttp3.RequestBody.create(json.toString(), type)).build();
        } catch (org.json.JSONException e) {
            return request;
        }
    }

    // adb shell setprop log.tag.Xitter DEBUG, then adb logcat -s Xitter
    static Response log(Request request, HttpUrl target, Response response, String how) {
        if (Log.isLoggable(XitterNetwork.TAG, Log.DEBUG)) {
            String query = target.encodedQuery();
            if (query != null && query.length() > 300) {
                // GraphQL: the variables in full, not the long list of features
                String vars = target.queryParameter("variables");
                query = vars != null ? "variables=" + vars : query.substring(0, 300) + "…";
            }
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
        // X's feature switches for the app: its built-in defaults, but for screens the classic
        // app has an older version of that reads what the instance answers
        if (path.endsWith("/1.1/help/settings.json")) {
            return empty(request, "{\"config\":" + FEATURE_SWITCHES + ",\"impressions\":[]}");
        }
        // whether to show the Communities home tab's memberships; the instance has no such query
        if (path.endsWith("/CommunitiesMembership")) {
            return empty(request, "{\"data\":{\"viewer\":{\"hasCommunityMemberShips\":true},\"communitiesViewer\":{\"unreadIndicatorActive\":false}}}");
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

    private static final String FEATURE_SWITCHES = "{"
            // followers and following: the timeline screens (the new page is an Apollo query)
            + "\"android_follower_timelines_stack_enabled\":{\"value\":false}"
            + "}";

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
