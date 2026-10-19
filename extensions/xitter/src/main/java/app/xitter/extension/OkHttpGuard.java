package app.xitter.extension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.CertificatePinner;
import okhttp3.ConnectionSpec;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.internal.tls.OkHostnameVerifier;

/**
 * Fits an OkHttp client being built with the guard: the interceptor that rewrites and
 * authenticates requests, a DNS and proxy selection that refuse what isn't allowed (they also
 * catch redirects, which interceptors don't see), and the system's own TLS in place of X's
 * pinned certificates, which an instance's certificate would never match.
 */
final class OkHttpGuard {
    private OkHttpGuard() {}

    static void install(OkHttpClient.Builder builder) {
        // last in both lists: after X's own interceptors, which sign requests (some only for
        // X's hosts), so the guard sees and rewrites requests as they would leave
        addLast(builder.interceptors(), XitterInterceptor.class, new XitterInterceptor());
        addLast(builder.networkInterceptors(), XitterInterceptor.Auth.class, new XitterInterceptor.Auth());

        builder.dns(GUARD_DNS);
        // an explicit proxy would resolve hosts itself, out of the DNS guard's reach
        builder.proxy(null);
        builder.proxySelector(new GuardProxySelector(ProxySelector.getDefault()));
        // sessions are sent by XitterInterceptor.Auth; a cookie jar would overwrite them
        builder.cookieJar(CookieJar.NO_COOKIES);

        // an instance on plain http (a LAN or dev server): X's clients allow only TLS
        if ("http".equals(XitterNetwork.instance().scheme)) {
            builder.connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT));
        }

        // the system's trust store and hostname checks instead of X's
        builder.setSslSocketFactoryOrNull$okhttp(null);
        builder.setX509TrustManagerOrNull$okhttp(null);
        builder.setCertificateChainCleaner$okhttp(null);
        builder.hostnameVerifier(OkHostnameVerifier.INSTANCE);
        builder.certificatePinner(CertificatePinner.DEFAULT);
    }

    private static void addLast(List<Interceptor> list, Class<?> type, Interceptor interceptor) {
        // a client made from another (newBuilder()) already has it, possibly no longer last
        for (int i = list.size() - 1; i >= 0; i--) {
            if (type.isInstance(list.get(i))) list.remove(i);
        }
        list.add(interceptor);
    }

    static final Dns GUARD_DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            XitterNetwork.check(hostname);
            return Arrays.asList(InetAddress.getAllByName(hostname));
        }
    };

    static final class GuardProxySelector extends ProxySelector {
        private final ProxySelector system;

        GuardProxySelector(ProxySelector system) {
            this.system = system;
        }

        @Override
        public List<Proxy> select(URI uri) {
            // a refused host goes direct, so the DNS guard sees it and fails the call
            if (system == null || uri == null || !XitterNetwork.isAllowed(uri.getHost())) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            List<Proxy> proxies = system.select(uri);
            return proxies == null || proxies.isEmpty() ? Collections.singletonList(Proxy.NO_PROXY) : proxies;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (system != null) system.connectFailed(uri, sa, ioe);
        }
    }
}
