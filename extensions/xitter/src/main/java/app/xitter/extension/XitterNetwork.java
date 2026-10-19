package app.xitter.extension;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;

/**
 * Where the app may connect. Every address of X (x.com, twitter.com, twimg.com, t.co and the
 * rest) is rewritten to the Xitter instance; with "Block third-party services" on, any other
 * host is refused. The patch routes every OkHttp client, URL connection, DNS lookup and WebView
 * load in the app through here, so nothing reaches X even when one layer is bypassed.
 */
@SuppressWarnings("unused")
public final class XitterNetwork {
    static final String TAG = "Xitter";

    private XitterNetwork() {}

    // X's own domains: rewritten to the instance, never contacted
    private static final String[] X_DOMAINS = {
            "x.com", "twitter.com", "twimg.com", "t.co", "twttr.com", "twitter.co",
            "pscp.tv", "periscope.tv", "tweetdeck.com", "vine.co", "twitpic.com",
    };
    // xAI's: Grok in the app; nothing on the instance answers it, so it is refused
    private static final String[] REFUSED_DOMAINS = {"x.ai", "grok.com", "grok.x.com"};

    private static volatile Instance instance;

    static final class Instance {
        final String scheme;
        final String host;
        final int port;
        /** A path the instance lives under, without a trailing slash ("" for the root). */
        final String base;
        final String origin;
        final String[] allowed;

        Instance(String url) {
            URI u = URI.create(url.trim());
            scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
            host = u.getHost() == null ? "localhost" : u.getHost().toLowerCase(Locale.ROOT);
            port = u.getPort();
            String p = u.getRawPath() == null ? "" : u.getRawPath();
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            base = p;
            origin = scheme + "://" + host + (port == -1 ? "" : ":" + port);
            String extra = XitterConfig.allowedHosts();
            allowed = extra == null || extra.trim().isEmpty() ? new String[0] : extra.toLowerCase(Locale.ROOT).split("\\s*,\\s*");
        }
    }

    static Instance instance() {
        Instance i = instance;
        if (i == null) instance = i = new Instance(XitterConfig.serverUrl());
        return i;
    }

    private static boolean under(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** One of X's hosts, which is answered by the instance instead. */
    public static boolean isXHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (isInstanceHost(host)) return false;
        for (String d : X_DOMAINS) if (under(host, d)) return true;
        return false;
    }

    public static boolean isInstanceHost(String host) {
        return host != null && host.equalsIgnoreCase(instance().host);
    }

    /** Whether a connection to this host (after rewriting) may be made at all. */
    public static boolean isAllowed(String host) {
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (isInstanceHost(host)) return true;
        for (String d : X_DOMAINS) if (under(host, d)) return false;
        for (String d : REFUSED_DOMAINS) if (under(host, d)) return false;
        for (String d : instance().allowed) if (!d.isEmpty() && under(host, d)) return true;
        if (isLocal(host)) return true;
        return !XitterConfig.blockThirdParty();
    }

    // the device itself and literal private addresses (an instance on the LAN, a dev server)
    private static boolean isLocal(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (!isIpLiteral(host)) return false;
        try {
            InetAddress a = InetAddress.getByName(host); // a literal: parsed, never looked up
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || (host.contains(":") && host.matches("[0-9a-fA-F:.%]+"));
    }

    /** The path an X address has on the instance (the instance serves X's paths from its root). */
    /** One of the key backup realm addresses X's app trusts; requests to it go to the instance's realm. */
    static final String PINNED_REALM = "https://realm-a.x.com/";

    static String mapPath(String host, String path) {
        if (path == null || path.isEmpty()) path = "/";
        host = host.toLowerCase(Locale.ROOT);
        // the client's own files, which the instance keeps under /abs
        if (host.equals("abs.twimg.com")) return "/abs" + path;
        // chat: its live socket, its manifest and the key backup realm
        if (under(host, "chat-ws.x.com") && path.startsWith("/ws")) return "/xchat" + path;
        if (host.equals("chat.x.com") || host.equals("chat-staging.x.com")) return "/xchat" + path;
        if (host.startsWith("realm-") && under(host, "x.com")) return "/juicebox" + path;
        // the live updates stream, which the instance serves without the version
        if (path.equals("/1.1/live_pipeline/events")) return "/live_pipeline/events";
        return path;
    }

    /**
     * An address rewritten for the instance, unchanged if it may be used as it is, or null when
     * it must not be contacted. Accepts http(s) and ws(s).
     */
    public static String rewrite(String url) {
        if (url == null) return null;
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return url; // not an address the network would take
        }
        String scheme = u.getScheme();
        if (scheme == null) return url;
        scheme = scheme.toLowerCase(Locale.ROOT);
        boolean web = scheme.equals("http") || scheme.equals("https");
        boolean socket = scheme.equals("ws") || scheme.equals("wss");
        if (!web && !socket) return url; // data:, file:, content:, about:, intents...
        String host = u.getHost();
        if (host == null) return null;
        if (isXHost(host)) {
            Instance i = instance();
            String s = socket ? (i.scheme.equals("https") ? "wss" : "ws") : i.scheme;
            StringBuilder b = new StringBuilder(s).append("://").append(i.host);
            if (i.port != -1) b.append(':').append(i.port);
            b.append(i.base).append(mapPath(host, u.getRawPath()));
            if (u.getRawQuery() != null) b.append('?').append(u.getRawQuery());
            if (u.getRawFragment() != null) b.append('#').append(u.getRawFragment());
            return b.toString();
        }
        return isAllowed(host) ? url : null;
    }

    static void blocked(String what) {
        Log.w(TAG, "blocked " + what);
    }

    // ---- java.net (the patch replaces the app's calls to these with the methods below) ----

    public static URLConnection openConnection(URL url) throws IOException {
        URL to = guard(url);
        return asRequested(url, to, to.openConnection());
    }

    public static URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        URL to = guard(url);
        return asRequested(url, to, to.openConnection(proxy));
    }

    // an https address of X's on an http instance: callers that expect HTTPS (the chat key
    // backup's client) get the plain connection as an HttpsURLConnection
    private static URLConnection asRequested(URL requested, URL to, URLConnection connection) {
        if ("https".equalsIgnoreCase(requested.getProtocol()) && "http".equalsIgnoreCase(to.getProtocol())
                && connection instanceof java.net.HttpURLConnection) {
            return new CleartextHttpsConnection((java.net.HttpURLConnection) connection);
        }
        return connection;
    }

    public static InputStream openStream(URL url) throws IOException {
        return guard(url).openConnection().getInputStream();
    }

    private static URL guard(URL url) throws IOException {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) return url;
        String to = rewrite(url.toString());
        if (to == null) {
            blocked(url.getHost());
            throw new IOException("Blocked by Xitter: " + url.getHost());
        }
        if (to.equals(url.toString())) return url;
        try {
            return new URL(to);
        } catch (MalformedURLException e) {
            throw new IOException(e);
        }
    }

    public static InputStream openInputStream(android.content.ContentResolver resolver, android.net.Uri uri) throws java.io.FileNotFoundException {
        return ProfileMedia.openInputStream(resolver, uri);
    }

    // DNS: the last line of defence. A lookup of X's hosts means something wasn't rewritten.
    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        check(host);
        return InetAddress.getAllByName(host);
    }

    public static InetAddress getByName(String host) throws UnknownHostException {
        check(host);
        return InetAddress.getByName(host);
    }

    static void check(String host) throws UnknownHostException {
        if (host == null || host.isEmpty()) return; // the local host
        if (!isAllowed(host)) {
            blocked("lookup of " + host);
            throw new UnknownHostException("Blocked by Xitter: " + host);
        }
    }

    // ---- WebView ----
    // X's pages in the app's WebViews (settings pages, help) are the instance's web pages. The
    // app opens them through x.com/account/authenticate_web_view?redirect_url=..., signed with
    // the account's OAuth header: that hand-off is done here, with the session's cookies put in
    // the WebView's cookie store for the instance. X's app signs its shared WebView in through
    // a hidden preload page, answered here with an empty page (the instance's page there would
    // be handed to the browser); Grok has no counterpart on the instance and isn't loaded.

    private static final String[] NO_WEBVIEW = {"/i/webview-preload", "/i/grok"};
    private static final String PRELOAD = "https://x.com/i/webview-preload";

    // the preload is waited for until a page finishes at its address: an empty page under that
    // address, loaded from here, with nothing asked of X
    private static boolean isPreload(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null || !isXHost(uri.getHost())) return false;
            if ("/account/authenticate_web_view".equals(uri.getPath())) {
                String redirect = android.net.Uri.parse(url.trim()).getQueryParameter("redirect_url");
                if (redirect == null) return false;
                uri = URI.create("https://x.com/").resolve(redirect);
            }
            return "/i/webview-preload".equals(uri.getPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String webViewTarget(String url, Map<String, String> headers) {
        if (url == null) return null;
        // adb shell setprop log.tag.Xitter DEBUG also opens the app's WebViews to chrome://inspect
        if (Log.isLoggable(TAG, Log.DEBUG)) android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || !isXHost(host)) return rewrite(url);
            if ("/account/authenticate_web_view".equals(uri.getPath())) {
                // the session goes to the WebView's cookies even when the page itself isn't loaded
                if (headers != null) signIn(headers.get("Authorization"));
                String redirect = android.net.Uri.parse(url.trim()).getQueryParameter("redirect_url");
                if (redirect == null) return null;
                URI to = URI.create("https://x.com/").resolve(redirect);
                if (to.getHost() == null || !isXHost(to.getHost())) return null;
                uri = to;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            for (String p : NO_WEBVIEW) if (path.equals(p) || path.startsWith(p + "/")) return null;
            String to = rewrite(uri.toString());
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "web view " + url + " -> " + to);
            return to;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** For the app's WebViewClients: a navigation (URL or request) to one of the instance's pages. */
    public static boolean isInstancePage(Object navigation) {
        Object url = navigation instanceof android.webkit.WebResourceRequest ? ((android.webkit.WebResourceRequest) navigation).getUrl() : navigation;
        if (url == null) return false;
        android.net.Uri uri = android.net.Uri.parse(url.toString());
        String scheme = uri.getScheme();
        return ("http".equals(scheme) || "https".equals(scheme)) && isInstanceHost(uri.getHost());
    }

    // the session's account id by session cookie: the web client also reads it from a cookie
    private static final Map<String, String> ACCOUNT_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void signIn(String authorization) {
        Session s = Session.fromAuthorization(authorization);
        if (s == null) return;
        setWebCookie("auth_token", s.authToken, true);
        setWebCookie("ct0", s.csrf, false);
        String id = ACCOUNT_IDS.get(s.authToken);
        if (id != null) {
            setWebCookie("twid", "u%3D" + id, false);
        } else {
            // the web client reloads its page until "twid" names the signed-in account
            new Thread(() -> {
                String found = accountId(s);
                if (found == null) return;
                ACCOUNT_IDS.put(s.authToken, found);
                setWebCookie("twid", "u%3D" + found, false);
            }, "XitterWebSignIn").start();
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "web view signed in to " + instance().origin);
    }

    private static void setWebCookie(String name, String value, boolean httpOnly) {
        android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
        String secure = "https".equals(instance().scheme) ? "; Secure" : "";
        cookies.setCookie(instance().origin, name + "=" + value + "; Path=/; Max-Age=31536000; SameSite=Lax" + (httpOnly ? "; HttpOnly" : "") + secure);
        cookies.flush();
    }

    private static okhttp3.OkHttpClient webSignInClient;

    // the session's account, from the instance's Viewer query (XitterInterceptor signs it)
    private static String accountId(Session s) {
        try {
            synchronized (XitterNetwork.class) {
                if (webSignInClient == null) webSignInClient = new okhttp3.OkHttpClient();
            }
            Instance i = instance();
            okhttp3.Request r = new okhttp3.Request.Builder()
                    .url(i.origin + i.base + "/graphql/xitter/Viewer?variables=%7B%7D&features=%7B%7D")
                    .header("Authorization", "OAuth oauth_token=\"" + s.oauthToken() + "\"")
                    .build();
            try (okhttp3.Response response = webSignInClient.newCall(r).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                org.json.JSONObject result = new org.json.JSONObject(response.body().string()).getJSONObject("data")
                        .getJSONObject("viewer").getJSONObject("user_results").getJSONObject("result");
                String id = result.optString("rest_id");
                return id.isEmpty() ? null : id;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not look up the web view's account", e);
            return null;
        }
    }

    // the app's own headers (its OAuth signature) are for X, not for a web page
    private static Map<String, String> webViewHeaders(Map<String, String> headers) {
        if (headers == null) return null;
        Map<String, String> out = new java.util.HashMap<>(headers);
        out.remove("Authorization");
        return out;
    }

    public static void loadUrl(android.webkit.WebView view, String url) {
        String to = webViewTarget(url, null);
        if (isPreload(url)) {
            view.loadDataWithBaseURL(PRELOAD, "<html></html>", "text/html", "utf-8", null);
        } else if (to == null) {
            blocked(url);
            view.loadUrl("about:blank");
        } else {
            view.loadUrl(to);
        }
    }

    public static void loadUrl(android.webkit.WebView view, String url, Map<String, String> headers) {
        String to = webViewTarget(url, headers);
        if (isPreload(url)) {
            view.loadDataWithBaseURL(PRELOAD, "<html></html>", "text/html", "utf-8", null);
        } else if (to == null) {
            blocked(url);
            view.loadUrl("about:blank");
        } else {
            view.loadUrl(to, webViewHeaders(headers));
        }
    }

    public static void postUrl(android.webkit.WebView view, String url, byte[] body) {
        String to = webViewTarget(url, null);
        if (to == null) {
            blocked(url);
            view.loadUrl("about:blank");
        } else {
            view.postUrl(to, body);
        }
    }

    // ---- links handed to other apps (the browser, Custom Tabs) ----

    public static void startActivity(android.content.Context context, android.content.Intent intent) {
        android.content.Intent out = outbound(context, intent);
        if (out != intent && Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "link " + describe(intent) + " -> " + describe(out));
        context.startActivity(out);
    }

    public static void startActivity(android.content.Context context, android.content.Intent intent, android.os.Bundle options) {
        context.startActivity(outbound(context, intent), options);
    }

    /**
     * An intent opening an address of X's or of the instance: kept in the app when one of its
     * screens takes it (a profile, a post, a hashtag; no network involved), otherwise an X
     * address goes to the instance instead, so a browser never loads X. A share sheet's
     * ("chooser") link is looked at too.
     */
    static android.content.Intent outbound(android.content.Context context, android.content.Intent intent) {
        return outbound(context, intent, false);
    }

    private static android.content.Intent outbound(android.content.Context context, android.content.Intent intent, boolean elsewhere) {
        try {
            if (intent == null) return null;
            if (android.content.Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                android.content.Intent inner = intent.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
                if (inner == null) return intent;
                // "open with another app" (the app excluded from the choice) stays that way
                boolean excludesApp = false;
                android.os.Parcelable[] excluded = intent.getParcelableArrayExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS);
                for (int i = 0; excluded != null && i < excluded.length; i++) {
                    if (excluded[i] instanceof android.content.ComponentName
                            && context.getPackageName().equals(((android.content.ComponentName) excluded[i]).getPackageName())) excludesApp = true;
                }
                android.content.Intent handled = outbound(context, inner, excludesApp);
                // one of the app's own screens: no need to ask which app
                if (context.getPackageName().equals(handled.getPackage())) return handled;
                intent.putExtra(android.content.Intent.EXTRA_INTENT, handled);
                return intent;
            }
            android.net.Uri data = intent.getData();
            String scheme = data == null ? null : data.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return intent;
            String host = data.getHost();
            boolean x = isXHost(host), ours = isInstanceHost(host);
            if (!x && !ours) return intent;
            String own = context.getPackageName();
            boolean targeted = intent.getPackage() != null || intent.getComponent() != null;
            boolean toUs = own.equals(intent.getPackage()) || (intent.getComponent() != null && own.equals(intent.getComponent().getPackageName()));
            if (toUs) return intent;
            if (!targeted && !elsewhere) {
                // the app's screens take x.com addresses; the instance's are the same paths
                android.net.Uri asX = ours ? android.net.Uri.parse("https://x.com" + instancePath(data)) : data;
                android.content.Intent inApp = new android.content.Intent(intent).setData(asX).setPackage(own);
                if (inApp.resolveActivity(context.getPackageManager()) != null) return inApp;
            }
            if (x) {
                String to = rewrite(data.toString());
                if (to != null) intent.setData(android.net.Uri.parse(to));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not check an outgoing link", e);
        }
        return intent;
    }

    private static String describe(android.content.Intent intent) {
        android.content.Intent inner = android.content.Intent.ACTION_CHOOSER.equals(intent.getAction()) ? intent.getParcelableExtra(android.content.Intent.EXTRA_INTENT) : null;
        return intent.toUri(0) + (inner == null ? "" : " [" + inner.toUri(0) + "]");
    }

    // an instance address's path (and query) as X has it: without the path the instance lives under
    private static String instancePath(android.net.Uri uri) {
        String path = uri.getEncodedPath() == null ? "/" : uri.getEncodedPath();
        String base = instance().base;
        if (!base.isEmpty() && path.startsWith(base)) path = path.substring(base.length());
        if (path.isEmpty()) path = "/";
        return path + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery());
    }

    // ---- OkHttp ----

    /** Called at the start of every OkHttpClient.Builder.build() in the app. */
    public static void onBuild(okhttp3.OkHttpClient.Builder builder) {
        try {
            OkHttpGuard.install(builder);
        } catch (Throwable t) {
            // a client without the guard must not be built
            Log.e(TAG, "could not guard an OkHttp client", t);
            throw new IllegalStateException("Xitter: could not guard an OkHttp client", t);
        }
    }
}
