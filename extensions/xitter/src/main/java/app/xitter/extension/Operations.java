package app.xitter.extension;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * X's Android app asks for the same data as the web client under its own GraphQL operations:
 * some under another name or with other variable names, some with the result under other keys
 * (its queries alias them). Each Android operation is sent to the web operation the instance
 * answers, and the result is moved to where the app reads it.
 */
final class Operations {
    private Operations() {}

    private static final class Op {
        final String server;
        /** where the instance puts the result, under "data" (null: nowhere else to move it) */
        final String[] from;
        /** where the app reads it, under "data" */
        final String[] to;
        /** the app's variable names to the web operation's */
        final Map<String, String> variables = new HashMap<>();

        Op(String server, String from, String to, String... renames) {
            this.server = server;
            this.from = from == null ? null : from.split("\\.");
            this.to = to == null ? null : to.split("\\.");
            for (int i = 0; i + 1 < renames.length; i += 2) variables.put(renames[i], renames[i + 1]);
        }
    }

    private static final Map<String, Op> OPS = new HashMap<>();

    private static void op(String android, Op op) {
        OPS.put(android, op);
    }

    static {
        // home: "For you"; "Following" (HomeLatestTimeline) reads the web's keys as they are
        op("HomeTimeline", new Op("HomeTimeline", "home.home_timeline_urt", "timeline_response.timeline"));
        op("ViewerUserQuery", new Op("Viewer", "viewer.user_results", "viewer.userResult"));

        // posting: the app reads the new post from "tweet_result"
        op("CreateTweet", new Op("CreateTweet", "create_tweet.tweet_results", "create_tweet.tweet_result"));
        op("CreateNoteTweet", new Op("CreateNoteTweet", "notetweet_create.tweet_results", "notetweet_create.tweet_result"));

        // bookmarks
        op("BookmarkTimelineV2", new Op("Bookmarks", "bookmark_timeline_v2.timeline", "timeline_response.timeline"));
        op("BookmarkAdd", new Op("CreateBookmark", null, null));
        op("BookmarkDelete", new Op("DeleteBookmark", null, null));

        // Explore: one timeline, inline in the page
        op("Explore", new Op("ExplorePage", "explore_page.body", "explore_page.pageBody"));

        // communities
        op("CommunitiesExploreTimelineQuery", new Op("CommunitiesExploreTimeline", "viewer.explore_communities_timeline.timeline", "viewer.timeline_response.timeline"));
        op("CommunitiesTimelineQuery", new Op("CommunitiesMainPageTimeline", "viewer.communities_timeline.timeline", "viewer.timeline_response.timeline"));
        op("CommunityInfoQuery", new Op("CommunityByRestId", "communityResults", "community_results_by_rest_id", "community_rest_id", "communityId"));
        op("CommunityRankedTimelineQuery", new Op("CommunityTweetsRankedTimeline", "communityResults.result.ranked_community_timeline.timeline",
                "community_by_rest_id.timeline_response.timeline", "community_rest_id", "communityId"));
        op("CommunityMediaTimelineQuery", new Op("CommunityMediaTimeline", "communityResults.result.community_media_timeline.timeline",
                "community_by_rest_id.timeline_response.timeline", "community_rest_id", "communityId"));
        op("CommunityJoinQuery", new Op("JoinCommunity", null, null, "community_rest_id", "communityId"));
        op("CommunityLeaveQuery", new Op("LeaveCommunity", null, null, "community_rest_id", "communityId"));
        op("CommunityJoinRequestCreate", new Op("RequestToJoinCommunity", null, null, "community_rest_id", "communityId"));
        op("CommunityMembershipsSlice", new Op("CommunitiesMembershipsSlice", "viewer.user_results.result.community_memberships_slice", "viewer.user.community_memberships_slice"));

        // lists
        op("ViewerListManagementTimeline", new Op("ListsManagementPageTimeline", "viewer.list_management_timeline.timeline", "viewer.timeline_response.timeline"));
        op("ListByIdQuery", new Op("ListByRestId", null, null, "rest_id", "listId"));
        op("ListTimeline", new Op("ListLatestTweetsTimeline", "list.tweets_timeline.timeline", "list.timeline_response.timeline", "rest_id", "listId"));
        op("ListMembersTimelineQuery", new Op("ListMembers", "list.members_timeline.timeline", "list.timeline_response.timeline", "rest_id", "listId"));
        op("ListSubscribersTimelineQuery", new Op("ListSubscribers", "list.subscribers_timeline.timeline", "list.timeline_response.timeline", "rest_id", "listId"));
        op("ListCreate", new Op("CreateList", null, null));
        op("ListUpdate", new Op("UpdateList", null, null, "list_id", "listId"));
        op("ListDelete", new Op("DeleteList", null, null, "list_id", "listId"));
        op("ListMemberAdd", new Op("ListAddMember", null, null, "list_id", "listId", "user_id", "userId"));
        op("ListMemberRemove", new Op("ListRemoveMember", null, null, "list_id", "listId", "user_id", "userId"));
        op("UserListSubscribe", new Op("ListSubscribe", null, null, "list_id", "listId"));
        op("UserListUnsubscribe", new Op("ListUnsubscribe", null, null, "list_id", "listId"));
        op("UserListMute", new Op("MuteList", null, null, "list_id", "listId"));
        op("UserListUnmute", new Op("UnmuteList", null, null, "list_id", "listId"));

        // a post and its replies
        op("ConversationTimelineV2", new Op("TweetDetail", "threaded_conversation_with_injections_v2", "timeline_response"));

        // profiles
        String profileTimeline = "user.result.timeline.timeline", androidProfileTimeline = "user_result.result.timeline_response.timeline";
        op("UserWithProfileTweetsQueryV2", new Op("UserTweets", profileTimeline, androidProfileTimeline, "rest_id", "userId"));
        op("UserWithProfileTweetsAndRepliesQueryV2", new Op("UserTweetsAndReplies", profileTimeline, androidProfileTimeline, "rest_id", "userId"));
        op("MediaTimelineV2", new Op("UserMedia", profileTimeline, androidProfileTimeline, "rest_id", "userId"));
        op("FavoritesByTimeTimelineV2", new Op("Likes", profileTimeline, androidProfileTimeline, "rest_id", "userId"));
        op("UserResultByIdQuery", new Op("UserByRestId", "user", "user_result", "rest_id", "userId"));
        op("UserResultByScreenNameQuery", new Op("UserByScreenName", "user", "user_result"));
    }

    /** The operation the instance answers for one of the app's. */
    static String serverName(String operation) {
        Op op = OPS.get(operation);
        return op == null ? operation : op.server;
    }

    /**
     * The app's variables as the web operation takes them (JSON text): renamed, and with ids as
     * strings. The app sends some ids as numbers, which JavaScript rounds (ids are 64-bit), so
     * the instance would look up another id.
     */
    static String variables(String operation, String json) {
        if (json == null) return null;
        Op op = OPS.get(operation);
        try {
            JSONObject in = new JSONObject(json), out = new JSONObject();
            for (Iterator<String> k = in.keys(); k.hasNext(); ) {
                String key = k.next();
                String to = op == null ? null : op.variables.get(key);
                out.put(to != null ? to : key, idsAsStrings(in.get(key)));
            }
            return out.toString();
        } catch (JSONException e) {
            return json;
        }
    }

    // also inside objects and lists (a reply's in_reply_to_tweet_id, media ids...)
    private static Object idsAsStrings(Object value) throws JSONException {
        if (value instanceof Long && Math.abs((Long) value) > (1L << 53)) return String.valueOf(value);
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            for (Iterator<String> k = o.keys(); k.hasNext(); ) {
                String key = k.next();
                o.put(key, idsAsStrings(o.get(key)));
            }
        } else if (value instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) value;
            for (int i = 0; i < a.length(); i++) a.put(i, idsAsStrings(a.get(i)));
        }
        return value;
    }

    /** The response with its result where the app reads it. */
    static JSONObject moveResult(String operation, JSONObject response) throws JSONException {
        Op op = OPS.get(operation);
        JSONObject data = response.optJSONObject("data");
        // followers and following by id (Connections): an Apollo timeline of users
        if ("TimelineByIdQuery".equals(operation) && data != null) {
            JSONObject user = data.optJSONObject("user"), result = user == null ? null : user.optJSONObject("result");
            JSONObject timeline = result == null ? null : result.optJSONObject("timeline");
            if (timeline != null && timeline.has("timeline")) {
                response.put("data", new JSONObject().put("timeline", ApolloTimelines.userTimeline("connections", timeline.getJSONObject("timeline"))));
            }
            return response;
        }
        if (op == null || op.from == null || data == null) return response;
        Object result = data;
        for (String k : op.from) {
            if (!(result instanceof JSONObject) || !((JSONObject) result).has(k)) return response;
            result = ((JSONObject) result).get(k);
        }
        // the app's Explore page body names its timeline; the web's has no id
        if ("Explore".equals(operation) && result instanceof JSONObject && !((JSONObject) result).has("id")) {
            ((JSONObject) result).put("id", "explore");
        }
        JSONObject moved = new JSONObject(), at = moved;
        for (int i = 0; i < op.to.length - 1; i++) {
            JSONObject next = new JSONObject();
            at.put(op.to[i], next);
            at = next;
        }
        // a slice of communities: "items", each with its rest_id beside the result
        if (result instanceof JSONObject && ((JSONObject) result).has("items_results")) {
            JSONObject slice = (JSONObject) result;
            org.json.JSONArray from = slice.getJSONArray("items_results"), items = new org.json.JSONArray();
            for (int i = 0; i < from.length(); i++) {
                JSONObject item = from.getJSONObject(i);
                JSONObject r = item.optJSONObject("result");
                if (r != null && !item.has("rest_id")) item.put("rest_id", r.optString("rest_id"));
                items.put(item);
            }
            slice.remove("items_results");
            slice.put("items", items);
        }
        at.put(op.to[op.to.length - 1], result);
        if ("Explore".equals(operation)) moved.getJSONObject("explore_page").put("id", "explore");
        response.put("data", moved);
        return response;
    }
}
