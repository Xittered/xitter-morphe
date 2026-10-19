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
        // a community's actions: the app requires the profile-spotlight one, which the instance
        // only sends with some answers
        JSONObject actions = "Community".equals(typename) ? out.optJSONObject("actions") : null;
        if (actions != null && !actions.has("community_spotlight_setup_action_result")) {
            actions.put("community_spotlight_setup_action_result", new JSONObject()
                    .put("__typename", "CommunitySpotlightSetupActionUnavailable").put("reason", "NotSupported").put("message", ""));
        }
        // what the app requires on every community
        if ("Community".equals(typename)) {
            for (String k : new String[] {"member_count", "moderator_count", "new_tweet_count_since_last_viewed"}) {
                if (!out.has(k)) out.put(k, 0);
            }
            // and the other fields it can't do without
            if (!out.has("access")) out.put("access", "Public");
            if (!out.has("default_theme")) out.put("default_theme", "Default");
            if (!out.has("role")) out.put("role", "NonMember");
            if (!out.has("members_facepile_results")) out.put("members_facepile_results", new JSONArray());
            if (!out.has("rules")) out.put("rules", new JSONArray());
        }
        // and the viewer's relationship with it needs its actions and role
        JSONObject relationship = "Community".equals(typename) ? out.optJSONObject("viewer_relationship") : null;
        if (relationship != null) {
            if (!relationship.has("actions")) {
                relationship.put("actions", new JSONObject().put("invite_action_result", new JSONObject()
                        .put("__typename", "UserCommunityInviteActionUnavailable").put("reason", "NotSupported").put("message", "")));
            }
            if (!relationship.has("role")) relationship.put("role", out.optString("role", "NonMember"));
        }
        // a community result names its community beside it
        JSONObject communityResults = out.has("community_results") ? out.optJSONObject("community_results") : out.optJSONObject("communityResults");
        if (communityResults != null && !communityResults.has("rest_id")) {
            JSONObject r = communityResults.optJSONObject("result");
            if (r != null && r.has("rest_id")) communityResults.put("rest_id", r.getString("rest_id"));
        }
        if (out.has("full_text") && out.has("display_text_range")) mediaLinks(out);
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

    /**
     * A post's photos and videos: X puts their link at the end of the text, past what is shown
     * (display_text_range), and the app hides the text the media entity's indices cover. The
     * instance leaves the link out (indices of no width at the end), which the app shows as a
     * link of its own; the link is added after the shown text, as X has it.
     */
    private static void mediaLinks(JSONObject legacy) throws JSONException {
        JSONArray media = legacy.optJSONObject("entities") == null ? null : legacy.getJSONObject("entities").optJSONArray("media");
        if (media == null || media.length() == 0) return;
        JSONObject first = media.optJSONObject(0);
        JSONArray at = first == null ? null : first.optJSONArray("indices");
        if (at == null || at.length() != 2 || at.optInt(0) != at.optInt(1)) return;
        String text = legacy.optString("full_text");
        String link = first.optString("url", first.optString("expanded_url"));
        if (link.isEmpty()) return;
        String joined = text.isEmpty() ? link : text + " " + link;
        int start = joined.codePointCount(0, joined.length()) - link.codePointCount(0, link.length());
        int end = start + link.codePointCount(0, link.length());
        legacy.put("full_text", joined);
        // every photo of the post is that one link
        for (String key : new String[] {"entities", "extended_entities"}) {
            JSONObject entities = legacy.optJSONObject(key);
            JSONArray list = entities == null ? null : entities.optJSONArray("media");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m == null) continue;
                m.put("indices", new JSONArray().put(start).put(end));
                if (!m.has("url")) m.put("url", link);
            }
        }
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
