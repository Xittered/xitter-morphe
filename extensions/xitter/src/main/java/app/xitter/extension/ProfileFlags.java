package app.xitter.extension;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The instance's pride flag picker, in the app's Edit profile under Website, as the web client
 * has it: the flags and the account's own choice come from the instance's page (its
 * window.__XITTER__). A choice is saved when it is picked (account/update_flag.json): the app
 * sends nothing on Save when no field of its own changed.
 */
public final class ProfileFlags {
    private ProfileFlags() {}

    private static final String TAG = XitterNetwork.TAG;
    private static final Pattern PAGE_DATA = Pattern.compile("window\\.__XITTER__=(\\{.*?\\});");

    private static OkHttpClient client;

    /** Called by the patched Edit profile screen once its views are in place. */
    public static void attach(Activity activity) {
        new Thread(() -> {
            try {
                Data data = load();
                if (data == null) return;
                new Handler(Looper.getMainLooper()).post(() -> show(activity, data));
            } catch (Exception e) {
                Log.w(TAG, "flag picker", e);
            }
        }, "XitterFlags").start();
    }

    private static final class Flag {
        final String id, name;
        Bitmap image;

        Flag(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Data {
        final List<String> chosen = new ArrayList<>();
        int max = 1;
        final List<Flag> flags = new ArrayList<>();
    }

    private static synchronized OkHttpClient client() {
        if (client == null) client = new OkHttpClient();
        return client;
    }

    private static void save(Activity activity, String flag) {
        Session session = XitterInterceptor.Auth.lastSession;
        if (session == null) return;
        XitterNetwork.Instance i = XitterNetwork.instance();
        Request request = new Request.Builder().url(i.origin + i.base + "/1.1/account/update_flag.json")
                .header("Authorization", "OAuth oauth_token=\"" + session.oauthToken() + "\"")
                .post(new okhttp3.FormBody.Builder().add("flag", flag).build())
                .build();
        new Thread(() -> {
            boolean ok;
            try (Response r = client().newCall(request).execute()) {
                ok = r.isSuccessful();
            } catch (Exception e) {
                ok = false;
            }
            boolean saved = ok;
            new Handler(Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(activity,
                    saved ? "Flag saved" : "Couldn't save the flag", android.widget.Toast.LENGTH_SHORT).show());
        }, "XitterFlags").start();
    }

    private static Data load() throws Exception {
        Session session = XitterInterceptor.Auth.lastSession;
        if (session == null) return null;
        XitterNetwork.Instance i = XitterNetwork.instance();
        String oauth = "OAuth oauth_token=\"" + session.oauthToken() + "\"";
        String page;
        try (Response r = client().newCall(new Request.Builder().url(i.origin + i.base + "/home").header("Authorization", oauth).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            page = r.body().string();
        }
        Matcher m = PAGE_DATA.matcher(page);
        if (!m.find()) return null;
        JSONObject json = new JSONObject(m.group(1));
        Data data = new Data();
        // a list since accounts wear up to maxFlags flags ("a,b")
        for (String f : json.optString("flag", "").split(",")) if (!f.trim().isEmpty()) data.chosen.add(f.trim());
        data.max = Math.max(1, json.optInt("maxFlags", 1));
        JSONArray flags = json.optJSONArray("flags");
        for (int f = 0; flags != null && f < flags.length(); f++) {
            JSONObject o = flags.getJSONObject(f);
            Flag flag = new Flag(o.getString("id"), o.optString("name", o.getString("id")));
            // drawn as the badges are (the instance serves them as SVG)
            try (Response r = client().newCall(new Request.Builder().url(i.origin + i.base + "/public/flag-" + flag.id + ".svg").build()).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    byte[] png = r.body().bytes();
                    flag.image = BitmapFactory.decodeByteArray(png, 0, png.length);
                }
            }
            data.flags.add(flag);
        }
        return data;
    }

    private static void show(Activity activity, Data data) {
        if (activity.isFinishing()) return;
        ViewGroup info = activity.findViewById(id(activity, "profile_information"));
        View website = activity.findViewById(id(activity, "edit_web_url"));
        if (info == null || info.findViewWithTag("xitter-flags") != null) return;

        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setTag("xitter-flags");
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));

        TextView title = new TextView(activity);
        title.setText(data.max > 1 ? "Flags next to your name, up to " + data.max : "Flag next to your name");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(0xFF71767B);
        box.addView(title);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = (int) (8 * dp);
        box.addView(scroll, scrollParams);

        List<View> chips = new ArrayList<>();
        List<String> choices = new ArrayList<>();
        choices.add("");
        for (Flag f : data.flags) choices.add(f.id);
        for (int c = 0; c < choices.size(); c++) {
            String id = choices.get(c);
            Flag flag = c == 0 ? null : data.flags.get(c - 1);
            View chip;
            if (flag != null && flag.image != null) {
                ImageView image = new ImageView(activity);
                image.setImageBitmap(flag.image);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
                chip = image;
            } else {
                TextView none = new TextView(activity);
                none.setText(flag == null ? "none" : flag.name);
                none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                none.setTextColor(0xFF71767B);
                none.setGravity(Gravity.CENTER);
                chip = none;
            }
            chip.setContentDescription(flag == null ? "No flag" : flag.name + " flag");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp));
            p.rightMargin = (int) (6 * dp);
            row.addView(chip, p);
            chips.add(chip);
            chip.setOnClickListener(v -> {
                // "none" clears; a flag toggles, while there is room for it
                if (id.isEmpty()) data.chosen.clear();
                else if (data.chosen.contains(id)) data.chosen.remove(id);
                else if (data.chosen.size() < data.max) data.chosen.add(id);
                else if (data.max == 1) data.chosen.set(0, id);
                else return;
                select(chips, choices, data.chosen, dp);
                save(activity, String.join(",", data.chosen));
            });
        }
        select(chips, choices, data.chosen, dp);

        int at = website == null ? -1 : info.indexOfChild(website);
        info.addView(box, at < 0 ? info.getChildCount() : at + 1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // a view id of the app's by name (its R classes are gone): under the package the resources
    // were built with, which a renamed package keeps
    private static int id(Activity activity, String name) {
        android.content.res.Resources res = activity.getResources();
        int id = res.getIdentifier(name, "id", activity.getPackageName());
        if (id == 0) id = res.getIdentifier(name, "id", "com.twitter.android");
        return id == 0 ? View.NO_ID : id;
    }

    private static void select(List<View> chips, List<String> choices, List<String> chosen, float dp) {
        for (int c = 0; c < chips.size(); c++) {
            String id = choices.get(c);
            boolean on = id.isEmpty() ? chosen.isEmpty() : chosen.contains(id);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(6 * dp);
            border.setStroke((int) (2 * dp), on ? 0xFF1D9BF0 : 0x59808080);
            chips.get(c).setBackground(border);
        }
    }
}
