package app.xitter.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Screens X's classic app reads from REST in the older timeline format (the notifications tab,
 * search): the timeline only names notifications, posts and users by id, and they come
 * separately in "globalObjects". The instance answers the web client's GraphQL timelines, where
 * each item carries them inline; the GraphQL answer is asked for with the same session and
 * turned into the older format here.
 */
final class V2Timelines {
    private V2Timelines() {}

    /** The GraphQL request one of these REST requests stands for, or null if it isn't one. */
    static Source source(HttpUrl target) {
        String path = target.encodedPath();
        try {
            switch (path) {
                case "/2/notifications/all.json": return notifications(target, "All");
                case "/2/notifications/mentions.json": return notifications(target, "Mentions");
                case "/2/notifications/verified.json": return notifications(target, "Verified");
                case "/2/search/adaptive.json": return search(target);
                default: return null;
            }
        } catch (JSONException e) {
            return null;
        }
    }

    static final class Source {
        final String operation;
        final JSONObject variables;
        /** where the timeline (with "instructions") is, under "data" */
        final String[] path;

        Source(String operation, JSONObject variables, String path) {
            this.operation = operation;
            this.variables = variables;
            this.path = path.split("\\.");
        }
    }

    private static JSONObject paging(HttpUrl target, int count) throws JSONException {
        JSONObject vars = new JSONObject();
        String c = target.queryParameter("count");
        try {
            vars.put("count", c != null ? Integer.parseInt(c) : count);
        } catch (NumberFormatException e) {
            vars.put("count", count);
        }
        String cursor = target.queryParameter("cursor");
        if (cursor != null) vars.put("cursor", cursor);
        return vars;
    }

    private static Source notifications(HttpUrl target, String type) throws JSONException {
        return new Source("NotificationsTimeline", paging(target, 40).put("timeline_type", type),
                "viewer_v2.user_results.result.notification_timeline.timeline");
    }

    private static Source search(HttpUrl target) throws JSONException {
        String filter = target.queryParameter("result_filter"), mode = target.queryParameter("tweet_search_mode");
        String product = "user".equals(filter) ? "People"
                : ("image".equals(filter) || "video".equals(filter) || "media".equals(filter)) ? "Media"
                : "live".equals(mode) ? "Latest" : "Top";
        String q = target.queryParameter("q");
        return new Source("SearchTimeline", paging(target, 20).put("rawQuery", q == null ? "" : q).put("product", product)
                .put("querySource", "typed_query"), "search_by_raw_query.search_timeline.timeline");
    }

    static Response handle(Interceptor.Chain chain, Request request, HttpUrl target, Source source) throws IOException {
        HttpUrl graphql = target.newBuilder()
                .encodedPath("/graphql/xitter/" + source.operation)
                .query(null)
                .addQueryParameter("variables", source.variables.toString())
                .build();
        // the same request otherwise: its OAuth header is turned into the session (Auth)
        Response response = chain.proceed(request.newBuilder().url(graphql).get().header("Accept-Encoding", "identity").build());
        if (!response.isSuccessful() || response.body() == null) return response;
        String body = response.body().string();
        try {
            String v2 = toV2(new JSONObject(Shapes.reshape(source.operation, body)), source.path);
            return XitterInterceptor.json(request, 200, v2);
        } catch (JSONException e) {
            throw new IOException("could not convert " + source.operation, e);
        }
    }

    // ---- GraphQL timeline -> REST timeline + globalObjects ----

    private static String toV2(JSONObject graphql, String[] path) throws JSONException {
        JSONObject timeline = graphql.getJSONObject("data");
        for (String k : path) timeline = timeline.getJSONObject(k);
        JSONObject users = new JSONObject(), tweets = new JSONObject(), notifications = new JSONObject();
        JSONArray instructions = new JSONArray();
        JSONArray in = timeline.getJSONArray("instructions");
        for (int i = 0; i < in.length(); i++) {
            JSONObject ins = in.getJSONObject(i);
            String type = ins.optString("__typename", ins.optString("type"));
            switch (type) {
                case "TimelineClearCache":
                    instructions.put(new JSONObject().put("clearCache", new JSONObject()));
                    break;
                case "TimelineMarkEntriesUnreadGreaterThanSortIndex":
                    instructions.put(new JSONObject().put("markEntriesUnreadGreaterThanSortIndex",
                            new JSONObject().put("sortIndex", ins.optString("sort_index", ins.optString("sortIndex")))));
                    break;
                case "TimelineAddEntries": {
                    JSONArray entries = new JSONArray();
                    JSONArray from = ins.getJSONArray("entries");
                    for (int e = 0; e < from.length(); e++) {
                        JSONObject entry = entry(from.getJSONObject(e), users, tweets, notifications);
                        if (entry != null) entries.put(entry);
                    }
                    instructions.put(new JSONObject().put("addEntries", new JSONObject().put("entries", entries)));
                    break;
                }
                default:
                    break;
            }
        }
        JSONObject globals = new JSONObject().put("users", users).put("tweets", tweets).put("notifications", notifications);
        return new JSONObject()
                .put("globalObjects", globals)
                .put("timeline", new JSONObject().put("id", timeline.optString("id", "timeline")).put("instructions", instructions))
                .toString();
    }

    private static JSONObject entry(JSONObject entry, JSONObject users, JSONObject tweets, JSONObject notifications) throws JSONException {
        JSONObject content = entry.getJSONObject("content");
        String kind = content.optString("__typename", content.optString("entryType"));
        JSONObject out;
        if (kind.equals("TimelineTimelineCursor")) {
            out = new JSONObject().put("operation", new JSONObject().put("cursor", new JSONObject()
                    .put("value", content.optString("value"))
                    .put("cursorType", content.optString("cursorType", content.optString("cursor_type")))));
        } else if (kind.equals("TimelineTimelineItem")) {
            JSONObject item = item(content, users, tweets, notifications);
            if (item == null) return null;
            out = new JSONObject().put("item", item);
        } else if (kind.equals("TimelineTimelineModule")) {
            JSONArray items = new JSONArray();
            JSONArray from = content.optJSONArray("items");
            for (int i = 0; from != null && i < from.length(); i++) {
                JSONObject moduleItem = from.getJSONObject(i);
                JSONObject item = item(moduleItem.optJSONObject("item"), users, tweets, notifications);
                if (item != null) items.put(new JSONObject().put("entryId", moduleItem.optString("entryId")).put("item", item));
            }
            if (items.length() == 0) return null;
            JSONObject module = new JSONObject().put("items", items).put("displayType", content.optString("displayType", "Vertical"));
            if (content.has("header")) module.put("header", content.get("header"));
            if (content.has("footer")) module.put("footer", content.get("footer"));
            if (content.has("clientEventInfo")) module.put("clientEventInfo", content.get("clientEventInfo"));
            out = new JSONObject().put("timelineModule", module);
        } else {
            return null;
        }
        return new JSONObject().put("entryId", entry.getString("entryId")).put("sortIndex", entry.getString("sortIndex")).put("content", out);
    }

    /** A timeline item (or module item) with its object moved to globalObjects. */
    private static JSONObject item(JSONObject holder, JSONObject users, JSONObject tweets, JSONObject notifications) throws JSONException {
        if (holder == null) return null;
        JSONObject item = holder.has("content") ? holder.getJSONObject("content") : holder.optJSONObject("itemContent");
        if (item == null) return null;
        JSONObject itemContent;
        switch (item.optString("__typename")) {
            case "TimelineNotification": {
                String id = item.getString("id");
                notifications.put(id, notification(item, users, tweets));
                JSONObject n = new JSONObject().put("id", id);
                if (item.has("notification_url")) n.put("url", item.get("notification_url"));
                itemContent = new JSONObject().put("notification", n);
                break;
            }
            case "TimelineTweet": {
                JSONObject results = item.has("tweetResult") ? item.getJSONObject("tweetResult") : item.optJSONObject("tweet_results");
                String id = addTweet(results, users, tweets);
                if (id == null) return null;
                itemContent = new JSONObject().put("tweet", new JSONObject().put("id", id).put("displayType", "Tweet"));
                break;
            }
            case "TimelineUser": {
                JSONObject results = item.has("userResult") ? item.getJSONObject("userResult") : item.optJSONObject("user_results");
                String id = addUser(results, users);
                if (id == null) return null;
                itemContent = new JSONObject().put("user", new JSONObject().put("id", id).put("displayType", "User"));
                break;
            }
            default:
                return null;
        }
        JSONObject wrapped = new JSONObject().put("content", itemContent);
        if (holder.has("clientEventInfo")) wrapped.put("clientEventInfo", holder.get("clientEventInfo"));
        return wrapped;
    }

    private static JSONObject notification(JSONObject item, JSONObject users, JSONObject tweets) throws JSONException {
        JSONObject n = new JSONObject().put("id", item.getString("id"));
        if (item.has("notification_icon")) n.put("icon", new JSONObject().put("id", item.getString("notification_icon")));
        JSONObject rich = item.optJSONObject("rich_message");
        if (rich != null) {
            JSONArray entities = new JSONArray();
            JSONArray from = rich.optJSONArray("entities");
            for (int i = 0; from != null && i < from.length(); i++) {
                JSONObject e = from.getJSONObject(i);
                JSONObject ref = e.optJSONObject("ref");
                String userId = ref == null ? null : addUser(ref.optJSONObject("user_results"), users);
                if (userId == null) continue;
                entities.put(new JSONObject().put("fromIndex", e.optInt("fromIndex")).put("toIndex", e.optInt("toIndex"))
                        .put("ref", new JSONObject().put("user", new JSONObject().put("id", userId))));
            }
            n.put("message", new JSONObject().put("text", rich.optString("text")).put("entities", entities).put("rtl", rich.optBoolean("rtl")));
        }
        JSONObject template = item.optJSONObject("template");
        if (template != null) {
            JSONArray targets = new JSONArray(), fromUsers = new JSONArray();
            JSONArray t = template.optJSONArray("target_objects");
            for (int i = 0; t != null && i < t.length(); i++) {
                String id = addTweet(t.getJSONObject(i).optJSONObject("tweet_results"), users, tweets);
                if (id != null) targets.put(new JSONObject().put("tweet", new JSONObject().put("id", id)));
            }
            JSONArray u = template.optJSONArray("from_users");
            for (int i = 0; u != null && i < u.length(); i++) {
                String id = addUser(u.getJSONObject(i).optJSONObject("user_results"), users);
                if (id != null) fromUsers.put(new JSONObject().put("user", new JSONObject().put("id", id)));
            }
            n.put("template", new JSONObject().put("aggregateUserActionsV1",
                    new JSONObject().put("targetObjects", targets).put("fromUsers", fromUsers)));
        }
        return n;
    }

    /** A GraphQL user result into globalObjects.users (REST form); its id. */
    private static String addUser(JSONObject results, JSONObject users) throws JSONException {
        JSONObject result = results == null ? null : results.optJSONObject("result");
        if (result == null || !result.has("rest_id")) return null;
        String id = result.getString("rest_id");
        JSONObject legacy = copy(result.optJSONObject("legacy"));
        legacy.put("id_str", id).put("id", Long.parseLong(id));
        users.put(id, legacy);
        return id;
    }

    /** A GraphQL post result into globalObjects.tweets (REST form), with its author; its id. */
    private static String addTweet(JSONObject results, JSONObject users, JSONObject tweets) throws JSONException {
        JSONObject result = results == null ? null : results.optJSONObject("result");
        if (result == null) return null;
        if (result.has("tweet")) result = result.getJSONObject("tweet"); // TweetWithVisibilityResults
        if (!result.has("rest_id")) return null;
        String id = result.getString("rest_id");
        JSONObject legacy = copy(result.optJSONObject("legacy"));
        legacy.put("id_str", id).put("id", Long.parseLong(id));
        JSONObject core = result.optJSONObject("core");
        String author = core == null ? null : addUser(core.optJSONObject("user_results"), users);
        if (author != null) legacy.put("user_id_str", author).put("user_id", Long.parseLong(author));
        tweets.put(id, legacy);
        return id;
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
