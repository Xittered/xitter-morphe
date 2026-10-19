package app.xitter.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.Permission;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * A plain HTTP connection handed out as HTTPS, for an instance served over http (a local or
 * LAN test instance): some of X's clients (the chat key backup's) open an https address of
 * X's and take the connection as an HttpsURLConnection. Everything goes to the real connection;
 * there is no TLS, so there are no certificates.
 */
final class CleartextHttpsConnection extends HttpsURLConnection {
    private final HttpURLConnection c;

    CleartextHttpsConnection(HttpURLConnection connection) {
        super(connection.getURL());
        c = connection;
    }

    @Override public String getCipherSuite() { return null; }
    @Override public Certificate[] getLocalCertificates() { return null; }
    @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }

    @Override public void connect() throws IOException { c.connect(); }
    @Override public void disconnect() { c.disconnect(); }
    @Override public boolean usingProxy() { return c.usingProxy(); }
    @Override public InputStream getInputStream() throws IOException { return c.getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return c.getOutputStream(); }
    @Override public InputStream getErrorStream() { return c.getErrorStream(); }
    @Override public int getResponseCode() throws IOException { return c.getResponseCode(); }
    @Override public String getResponseMessage() throws IOException { return c.getResponseMessage(); }
    @Override public void setRequestMethod(String method) throws java.net.ProtocolException { c.setRequestMethod(method); }
    @Override public String getRequestMethod() { return c.getRequestMethod(); }
    @Override public void setRequestProperty(String key, String value) { c.setRequestProperty(key, value); }
    @Override public void addRequestProperty(String key, String value) { c.addRequestProperty(key, value); }
    @Override public String getRequestProperty(String key) { return c.getRequestProperty(key); }
    @Override public Map<String, List<String>> getRequestProperties() { return c.getRequestProperties(); }
    @Override public String getHeaderField(String name) { return c.getHeaderField(name); }
    @Override public String getHeaderField(int n) { return c.getHeaderField(n); }
    @Override public String getHeaderFieldKey(int n) { return c.getHeaderFieldKey(n); }
    @Override public Map<String, List<String>> getHeaderFields() { return c.getHeaderFields(); }
    @Override public String getContentType() { return c.getContentType(); }
    @Override public int getContentLength() { return c.getContentLength(); }
    @Override public long getContentLengthLong() { return c.getContentLengthLong(); }
    @Override public String getContentEncoding() { return c.getContentEncoding(); }
    @Override public void setDoInput(boolean doInput) { c.setDoInput(doInput); }
    @Override public boolean getDoInput() { return c.getDoInput(); }
    @Override public void setDoOutput(boolean doOutput) { c.setDoOutput(doOutput); }
    @Override public boolean getDoOutput() { return c.getDoOutput(); }
    @Override public void setUseCaches(boolean useCaches) { c.setUseCaches(useCaches); }
    @Override public boolean getUseCaches() { return c.getUseCaches(); }
    @Override public void setConnectTimeout(int timeout) { c.setConnectTimeout(timeout); }
    @Override public int getConnectTimeout() { return c.getConnectTimeout(); }
    @Override public void setReadTimeout(int timeout) { c.setReadTimeout(timeout); }
    @Override public int getReadTimeout() { return c.getReadTimeout(); }
    @Override public void setInstanceFollowRedirects(boolean follow) { c.setInstanceFollowRedirects(follow); }
    @Override public boolean getInstanceFollowRedirects() { return c.getInstanceFollowRedirects(); }
    @Override public void setFixedLengthStreamingMode(int length) { c.setFixedLengthStreamingMode(length); }
    @Override public void setFixedLengthStreamingMode(long length) { c.setFixedLengthStreamingMode(length); }
    @Override public void setChunkedStreamingMode(int chunk) { c.setChunkedStreamingMode(chunk); }
    @Override public Permission getPermission() throws IOException { return c.getPermission(); }
}
