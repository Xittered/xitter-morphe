package app.xitter.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Xitter answers GraphQL as the web client reads it; X's Android app reads the same data in two
 * older ways, and the answers are reshaped here before it parses them:
 *
 * - Typed objects (timeline instructions, entries, items) are told apart by their first key,
 *   which must be "__typename"; an object that starts with anything else is dropped. The web
 *   client's instructions name their type in "type" and have no "__typename".
 * - Some timeline fields have other names: an item's "itemContent" is "content", a timeline
 *   tweet's "tweet_results" is "tweetResult", a timeline user's "user_results" is "userResult",
 *   a list's owner ("user_results") is "ownerResult", a trend's "trend_url" is "url".
 * - Users are read only from their "legacy" record; the web shape keeps names, avatar and counts
 *   in "core", "avatar", "relationship_counts" and so on.
 */
final class Shapes {
    private Shapes() {}

    /** `operation`: the app's GraphQL operation, whose result goes where it reads it */
    static String reshape(String operation, String json) throws JSONException {
        Object root = walk(new org.json.JSONTokener(json).nextValue());
        if (root instanceof JSONObject) root = Operations.moveResult(operation, (JSONObject) root);
        return String.valueOf(root);
    }

    private static Object walk(Object value) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray in = (JSONArray) value, out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(walk(in.get(i)));
            return out;
        }
        if (!(value instanceof JSONObject)) return value;
        JSONObject in = (JSONObject) value;

        String typename = in.optString("__typename", null);
        String type = in.optString("type", null);
        if (typename == null && type != null && type.startsWith("Timeline")) typename = type;
        // results some answers leave untyped (a post just created): a post has its text in
        // "legacy", a user its handle in "core"
        if (typename == null && in.has("rest_id")) {
            JSONObject legacy = in.optJSONObject("legacy"), core = in.optJSONObject("core");
            if (legacy != null && legacy.has("full_text")) typename = "Tweet";
            else if (core != null && core.has("screen_name")) typename = "User";
        }

        // org.json keeps insertion order: the type goes in first
        JSONObject out = new JSONObject();
        if (typename != null) out.put("__typename", typename);
        Iterator<String> keys = in.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!k.equals("__typename")) out.put(renamed(typename, k), walk(in.get(k)));
        }
        if ("User".equals(typename) && !out.has("legacy")) out.put("legacy", legacyUser(out));
        // a post's edit state is a typed union too: an edit points at the first version
        JSONObject edit = out.optJSONObject("edit_control");
        if (edit != null && !edit.has("__typename")) {
            JSONObject typed = new JSONObject().put("__typename", edit.has("initial_tweet_id") ? "EditControlEdit" : "EditControlInitial");
            for (Iterator<String> k = edit.keys(); k.hasNext(); ) {
                String key = k.next();
                typed.put(key, edit.get(key));
            }
            out.put("edit_control", typed);
        }
        return out;
    }

    /** Fields the app's timeline queries alias differently from the web client's. */
    private static String renamed(String typename, String key) {
        if (key.equals("itemContent")) return "content";
        if ("TimelineTweet".equals(typename) && key.equals("tweet_results")) return "tweetResult";
        if ("TimelineUser".equals(typename) && key.equals("user_results")) return "userResult";
        if ("List".equals(typename) && key.equals("user_results")) return "ownerResult";
        if ("TimelineTrend".equals(typename) && key.equals("trend_url")) return "url";
        return key;
    }

    /** The REST-style record X's app reads users from, out of the web shape's parts. */
    private static JSONObject legacyUser(JSONObject u) throws JSONException {
        JSONObject core = obj(u, "core"), counts = obj(u, "relationship_counts"), tweets = obj(u, "tweet_counts");
        JSONObject rel = obj(u, "relationship_perspectives"), bio = obj(u, "profile_bio");
        JSONObject l = new JSONObject()
                .put("id_str", u.optString("rest_id"))
                .put("name", core.optString("name"))
                .put("screen_name", core.optString("screen_name"))
                .put("created_at", core.optString("created_at"))
                .put("description", bio.optString("description"))
                .put("entities", bio.has("entities") ? bio.get("entities") : new JSONObject())
                .put("location", obj(u, "location").optString("location"))
                .put("url", obj(u, "website").optString("url"))
                .put("profile_image_url_https", obj(u, "avatar").optString("image_url"))
                .put("profile_image_shape", u.optString("profile_image_shape", "Circle"))
                .put("followers_count", counts.optInt("followers"))
                .put("friends_count", counts.optInt("following"))
                .put("statuses_count", tweets.optInt("tweets"))
                .put("media_count", tweets.optInt("media_tweets"))
                .put("favourites_count", obj(u, "action_counts").optInt("favorites_count"))
                .put("protected", obj(u, "privacy").optBoolean("protected"))
                .put("following", rel.optBoolean("following"))
                .put("followed_by", rel.optBoolean("followed_by"))
                .put("blocking", rel.optBoolean("blocking"))
                .put("blocked_by", rel.optBoolean("blocked_by"))
                .put("muting", rel.optBoolean("muting"))
                .put("follow_request_sent", u.optBoolean("follow_request_sent"))
                .put("notifications", obj(u, "notifications_settings").optBoolean("notifications_enabled"))
                .put("can_media_tag", obj(u, "media_permissions").optBoolean("can_media_tag", true))
                .put("verified", false)
                .put("default_profile", false)
                .put("default_profile_image", false);
        JSONObject banner = u.optJSONObject("banner");
        if (banner != null && banner.has("image_url")) l.put("profile_banner_url", banner.getString("image_url"));
        JSONObject pinned = u.optJSONObject("pinned_items");
        l.put("pinned_tweet_ids_str", pinned != null && pinned.has("tweet_ids_str") ? pinned.get("tweet_ids_str") : new JSONArray());
        String verifiedType = obj(u, "verification").optString("verified_type", null);
        if (verifiedType != null) l.put("verified_type", verifiedType);
        return l;
    }

    private static JSONObject obj(JSONObject o, String key) {
        JSONObject v = o.optJSONObject(key);
        return v != null ? v : new JSONObject();
    }
}
