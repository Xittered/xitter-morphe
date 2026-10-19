package app.xitter.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A repost. The instance answers CreateRetweet with the new repost's id, as it does the web
 * client; X's app stores the repost itself (its own post by the viewer, with the original inside)
 * and shows the original as reposted only once it has it. The repost is built here as the
 * instance builds reposts in timelines: "RT @author: text" by the viewer, around the original as
 * the instance now has it (reposted, with its new count).
 */
final class Reposts {
    private Reposts() {}

    static boolean handles(String operation) {
        return "CreateRetweet".equals(operation);
    }

    /** The instance's answer, completed with the repost (web shape, before Shapes). */
    static String complete(Interceptor.Chain chain, Request request, HttpUrl target, String answer) throws IOException {
        try {
            JSONObject json = new JSONObject(answer);
            JSONObject data = json.optJSONObject("data");
            JSONObject created = data == null ? null : data.optJSONObject("create_retweet");
            JSONObject result = created == null ? null : dig(created, "retweet_results", "result");
            if (result == null) return answer;
            String repostId = result.optString("rest_id");
            String originalId = originalId(request);
            if (repostId.isEmpty() || originalId == null) return answer;

            JSONObject original = dig(query(chain, request, target, "TweetResultByRestId", new JSONObject().put("tweetId", originalId)), "tweetResult", "result");
            JSONObject viewer = dig(query(chain, request, target, "Viewer", new JSONObject()), "viewer", "user_results", "result");
            if (original == null || viewer == null) return answer;
            created.put("retweet_results", new JSONObject().put("result", repost(repostId, viewer, original)));
            return json.toString();
        } catch (JSONException e) {
            return answer;
        }
    }

    private static JSONObject repost(String id, JSONObject viewer, JSONObject original) throws JSONException {
        JSONObject legacy = original.optJSONObject("legacy");
        if (legacy == null) legacy = new JSONObject();
        JSONObject author = dig(original, "core", "user_results", "result");
        String screenName = screenName(author);
        String text = "RT @" + screenName + ": " + legacy.optString("full_text");
        SimpleDateFormat date = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject mention = new JSONObject()
                .put("id_str", author == null ? "" : author.optString("rest_id"))
                .put("name", author == null ? "" : author.optJSONObject("core") != null ? author.getJSONObject("core").optString("name") : "")
                .put("screen_name", screenName)
                .put("indices", new JSONArray().put(3).put(4 + screenName.length()));
        JSONObject out = new JSONObject()
                .put("bookmark_count", legacy.optInt("bookmark_count"))
                .put("bookmarked", legacy.optBoolean("bookmarked"))
                .put("conversation_id_str", id)
                .put("created_at", date.format(new Date()))
                .put("display_text_range", new JSONArray().put(0).put(text.codePointCount(0, text.length())))
                .put("entities", new JSONObject().put("user_mentions", new JSONArray().put(mention)))
                .put("favorite_count", legacy.optInt("favorite_count"))
                .put("favorited", legacy.optBoolean("favorited"))
                .put("full_text", text)
                .put("id_str", id)
                .put("is_quote_status", false)
                .put("lang", "und")
                .put("quote_count", legacy.optInt("quote_count"))
                .put("reply_count", legacy.optInt("reply_count"))
                .put("retweet_count", legacy.optInt("retweet_count"))
                .put("retweeted", true)
                .put("retweeted_status_result", new JSONObject().put("result", original))
                .put("user_id_str", viewer.optString("rest_id"));
        return new JSONObject()
                .put("__typename", "Tweet")
                .put("rest_id", id)
                .put("core", new JSONObject().put("user_results", new JSONObject().put("result", viewer)))
                .put("edit_control", new JSONObject().put("edit_tweet_ids", new JSONArray().put(id))
                        .put("editable_until_msecs", String.valueOf(System.currentTimeMillis())).put("edits_remaining", "0").put("is_edit_eligible", false))
                .put("legacy", out)
                .put("views", new JSONObject().put("state", "Enabled"));
    }

    private static String screenName(JSONObject user) {
        if (user == null) return "";
        JSONObject core = user.optJSONObject("core");
        if (core != null && core.has("screen_name")) return core.optString("screen_name");
        JSONObject legacy = user.optJSONObject("legacy");
        return legacy == null ? "" : legacy.optString("screen_name");
    }

    // the reposted post: "tweet_id" in the request's variables
    private static String originalId(Request request) throws IOException, JSONException {
        String vars = request.url().queryParameter("variables");
        if (vars == null && request.body() != null) {
            JSONObject body = new JSONObject(new String(Bodies.read(request.body()), java.nio.charset.StandardCharsets.UTF_8));
            Object v = body.opt("variables");
            vars = v == null ? null : v.toString();
        }
        if (vars == null) return null;
        String id = new JSONObject(vars).optString("tweet_id");
        return id.isEmpty() ? null : id;
    }

    // one of the instance's GraphQL queries, as this request (its session); its "data", or null
    private static JSONObject query(Interceptor.Chain chain, Request request, HttpUrl target, String operation, JSONObject variables) throws IOException, JSONException {
        HttpUrl url = target.newBuilder().encodedPath("/graphql/xitter/" + operation).query(null)
                .addQueryParameter("variables", variables.toString()).build();
        try (Response response = chain.proceed(request.newBuilder().url(url).get().header("Accept-Encoding", "identity").build())) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return new JSONObject(response.body().string()).optJSONObject("data");
        }
    }

    private static JSONObject dig(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o == null) return null;
            o = o.optJSONObject(k);
        }
        return o;
    }
}
