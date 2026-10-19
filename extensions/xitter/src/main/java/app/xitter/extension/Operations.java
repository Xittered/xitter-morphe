package app.xitter.extension;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * X's Android app asks for the same data as the web client under its own GraphQL operations:
 * some under another name, some with the result under other keys (its queries alias them).
 * Each Android operation is sent to the web operation the instance answers, and the result is
 * moved to where the app reads it.
 */
final class Operations {
    private Operations() {}

    private static final class Op {
        final String server;
        /** where the instance puts the result, under "data" (null: nowhere else to move it) */
        final String[] from;
        /** where the app reads it, under "data" */
        final String[] to;

        Op(String server, String from, String to) {
            this.server = server;
            this.from = from == null ? null : from.split("\\.");
            this.to = to == null ? null : to.split("\\.");
        }
    }

    private static final Map<String, Op> OPS = new HashMap<>();

    static {
        // "For you"; "Following" (HomeLatestTimeline) reads the web's keys as they are
        OPS.put("HomeTimeline", new Op("HomeTimeline", "home.home_timeline_urt", "timeline_response.timeline"));
        OPS.put("ViewerUserQuery", new Op("Viewer", null, null));
    }

    /** The operation the instance answers for one of the app's. */
    static String serverName(String operation) {
        Op op = OPS.get(operation);
        return op == null ? operation : op.server;
    }

    /** The response with its result where the app reads it. */
    static JSONObject moveResult(String operation, JSONObject response) throws JSONException {
        Op op = OPS.get(operation);
        JSONObject data = response.optJSONObject("data");
        if (op == null || op.from == null || data == null) return response;
        Object result = data;
        for (String k : op.from) {
            if (!(result instanceof JSONObject) || !((JSONObject) result).has(k)) return response;
            result = ((JSONObject) result).get(k);
        }
        JSONObject moved = new JSONObject(), at = moved;
        for (int i = 0; i < op.to.length - 1; i++) {
            JSONObject next = new JSONObject();
            at.put(op.to[i], next);
            at = next;
        }
        at.put(op.to[op.to.length - 1], result);
        response.put("data", moved);
        return response;
    }
}
