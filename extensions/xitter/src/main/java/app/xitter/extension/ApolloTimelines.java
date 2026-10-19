package app.xitter.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Locale;

/**
 * Some of the classic app's newer screens (the followers and following tabs) read timelines
 * through Apollo: the schema's own field names (entry_id, sort_index, cursor_type...) where the
 * web client's queries alias them (entryId, sortIndex, cursorType), and strict about required
 * fields. A web timeline of users is turned into that form here.
 */
final class ApolloTimelines {
    private ApolloTimelines() {}

    /** A web timeline ({instructions}) of users as the app's TimelineByIdQuery reads it. */
    static JSONObject userTimeline(String id, JSONObject web) throws JSONException {
        JSONArray instructions = new JSONArray();
        JSONArray in = web.optJSONArray("instructions");
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject ins = in.getJSONObject(i);
            String type = ins.optString("__typename", ins.optString("type"));
            if (!type.equals("TimelineAddEntries")) continue;
            JSONArray entries = new JSONArray();
            JSONArray from = ins.getJSONArray("entries");
            for (int e = 0; e < from.length(); e++) {
                JSONObject entry = entry(from.getJSONObject(e));
                if (entry != null) entries.put(entry);
            }
            instructions.put(new JSONObject().put("__typename", "TimelineAddEntries").put("entries", entries));
        }
        JSONObject timeline = new JSONObject().put("id", id).put("instructions", instructions).put("response_objects", JSONObject.NULL);
        return new JSONObject().put("id", id).put("timeline", timeline);
    }

    private static JSONObject entry(JSONObject entry) throws JSONException {
        JSONObject content = entry.getJSONObject("content");
        String kind = content.optString("__typename", content.optString("entryType"));
        JSONObject out;
        if (kind.equals("TimelineTimelineCursor")) {
            out = new JSONObject().put("__typename", "TimelineTimelineCursor")
                    .put("cursor_type", content.optString("cursorType", content.optString("cursor_type")))
                    .put("value", content.optString("value"))
                    .put("display_treatment", JSONObject.NULL);
        } else if (kind.equals("TimelineTimelineItem")) {
            JSONObject item = content.has("content") ? content.getJSONObject("content") : content.optJSONObject("itemContent");
            if (item == null || !"TimelineUser".equals(item.optString("__typename"))) return null;
            JSONObject results = item.has("userResult") ? item.getJSONObject("userResult") : item.optJSONObject("user_results");
            JSONObject user = results == null ? null : results.optJSONObject("result");
            if (user == null) return null;
            JSONObject timelineUser = new JSONObject().put("__typename", "TimelineUser")
                    .put("user_results", new JSONObject().put("rest_id", user.optString("rest_id")).put("result", user(user)))
                    .put("social_context", JSONObject.NULL)
                    .put("promoted_metadata", JSONObject.NULL)
                    .put("display_type", "User");
            out = new JSONObject().put("__typename", "TimelineTimelineItem")
                    .put("client_event_info", JSONObject.NULL)
                    .put("content", timelineUser)
                    .put("feedback_info", JSONObject.NULL);
        } else {
            return null;
        }
        return new JSONObject().put("content", out)
                .put("sort_index", entry.optString("sortIndex", entry.optString("sort_index")))
                .put("entry_id", entry.optString("entryId", entry.optString("entry_id")));
    }

    /** A web user with the fields the app's user fragment requires and the web shape leaves out. */
    private static JSONObject user(JSONObject web) throws JSONException {
        JSONObject u = copy(web);
        u.put("__typename", "User");
        JSONObject core = copy(u.optJSONObject("core"));
        if (!core.has("created_at_ms")) core.put("created_at_ms", millis(core.optString("created_at")));
        u.put("core", core);
        if (!u.has("dm_permissions")) u.put("dm_permissions", new JSONObject().put("can_dm", false));
        JSONObject rel = copy(u.optJSONObject("relationship_perspectives"));
        for (String k : new String[] {"blocked_by", "blocking", "followed_by", "following", "live_following", "muted_by", "muting"}) {
            if (!rel.has(k)) rel.put(k, false);
        }
        u.put("relationship_perspectives", rel);
        JSONObject legacy = copy(u.optJSONObject("legacy"));
        if (!legacy.has("want_retweets")) legacy.put("want_retweets", true);
        if (!legacy.has("profile_interstitial_type")) legacy.put("profile_interstitial_type", "");
        u.put("legacy", legacy);
        JSONObject pinned = copy(u.optJSONObject("pinned_items"));
        if (!pinned.has("tweet_ids_str")) pinned.put("tweet_ids_str", new JSONArray());
        u.put("pinned_items", pinned);
        JSONObject privacy = copy(u.optJSONObject("privacy"));
        if (!privacy.has("protected")) privacy.put("protected", false);
        if (!privacy.has("suspended")) privacy.put("suspended", false);
        u.put("privacy", privacy);
        JSONObject verification = copy(u.optJSONObject("verification"));
        if (!verification.has("is_blue_verified")) verification.put("is_blue_verified", u.optBoolean("is_blue_verified"));
        if (!verification.has("is_verified_organization")) verification.put("is_verified_organization", false);
        if (!verification.has("is_verified_organization_affiliate")) verification.put("is_verified_organization_affiliate", false);
        if (!verification.has("verified")) verification.put("verified", false);
        u.put("verification", verification);
        if (!u.has("reply_device_following_v2")) u.put("reply_device_following_v2", false);
        if (!u.has("has_graduated_access")) u.put("has_graduated_access", true);
        return u;
    }

    private static long millis(String twitterDate) {
        try {
            return new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).parse(twitterDate).getTime();
        } catch (ParseException | NullPointerException e) {
            return 0;
        }
    }

    private static JSONObject copy(JSONObject o) throws JSONException {
        JSONObject out = new JSONObject();
        if (o == null) return out;
        for (Iterator<String> k = o.keys(); k.hasNext(); ) {
            String key = k.next();
            out.put(key, o.get(key));
        }
        return out;
    }
}
