package app.xitter.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Followers and following. The app first asks for the account's "connection page" (which tabs
 * there are, each with the id of its timeline) and then for each tab's timeline by that id.
 * The instance has no such page; it answers the web client's Followers and Following. The page
 * is answered here with those two tabs, under ids that name them, and a timeline asked for by
 * one of those ids is asked of the instance as Followers or Following.
 */
final class Connections {
    private Connections() {}

    private static final String PREFIX = "xitter-";

    static boolean isPage(String operation) {
        return "GetProfileConnectionPage".equals(operation);
    }

    /** The page: Followers and Following, for the account in the app's variables. */
    static String page(String variables) throws JSONException {
        JSONObject vars = new JSONObject(variables == null ? "{}" : variables);
        String userId = vars.optString("rest_id");
        JSONArray tabs = new JSONArray()
                .put(tab("followers", "Followers", userId))
                .put(tab("following", "Following", userId));
        JSONObject page = new JSONObject().put("page_body", new JSONObject().put("__typename", "SegmentedTimelines").put("timelines", tabs));
        JSONObject user = new JSONObject().put("__typename", "User").put("rest_id", userId).put("profile_connection_page", page);
        return new JSONObject().put("data", new JSONObject().put("user_result",
                new JSONObject().put("result", user).put("rest_id", userId))).toString();
    }

    private static JSONObject tab(String kind, String label, String userId) throws JSONException {
        return new JSONObject()
                .put("id", kind)
                .put("label_text", label)
                .put("timeline", new JSONObject().put("id", PREFIX + kind + "-" + userId))
                .put("scribe_config", new JSONObject().put("page", kind).put("section", ""));
    }

    /**
     * For TimelineByIdQuery with one of the page's ids: the instance's operation and its
     * variables, or null for any other timeline.
     */
    static String[] timeline(String operation, String variables) throws JSONException {
        if (!"TimelineByIdQuery".equals(operation) || variables == null) return null;
        JSONObject vars = new JSONObject(variables);
        String id = vars.optString("timelineId", vars.optString("id"));
        if (!id.startsWith(PREFIX)) return null;
        String[] parts = id.substring(PREFIX.length()).split("-", 2);
        if (parts.length != 2) return null;
        String server = parts[0].equals("followers") ? "Followers" : parts[0].equals("following") ? "Following" : null;
        if (server == null) return null;
        JSONObject out = new JSONObject().put("userId", parts[1]).put("count", vars.optInt("count", 20));
        if (!vars.isNull("cursor") && vars.has("cursor")) out.put("cursor", vars.get("cursor"));
        return new String[] {server, out.toString()};
    }
}
