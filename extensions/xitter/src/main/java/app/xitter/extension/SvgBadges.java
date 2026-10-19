package app.xitter.extension;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The instance's badges drawn as SVG (the pride flags next to a name): X's app decodes PNG,
 * JPEG and the like, not SVG. They are drawn here as PNG. The flags are simple shapes, and that
 * is what is drawn: rect, circle, and path with M, L, H, V and Z, in absolute coordinates,
 * filled or stroked with a #rrggbb colour.
 */
final class SvgBadges {
    private SvgBadges() {}

    private static final int SIZE = 96;
    private static final Pattern ELEMENT = Pattern.compile("<(rect|circle|path)\\b([^>]*)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"");
    private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"\\s*([-\\d.]+)[\\s,]+([-\\d.]+)[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)\\s*\"");
    private static final Pattern PATH_TOKEN = Pattern.compile("[MLHVZmlhvz]|-?\\d*\\.?\\d+(?:e-?\\d+)?");

    static boolean handles(Response response) {
        if (!response.isSuccessful() || response.body() == null) return false;
        MediaType type = response.body().contentType();
        return type != null && "image".equals(type.type()) && type.subtype().startsWith("svg");
    }

    /** The response with its SVG drawn as PNG, or as it was if the SVG isn't one of these. */
    static Response asPng(Response response) throws IOException {
        String svg = response.body().string();
        byte[] png;
        try {
            png = draw(svg);
        } catch (RuntimeException e) {
            png = null;
        }
        ResponseBody body = png != null
                ? ResponseBody.create(png, MediaType.get("image/png"))
                : ResponseBody.create(svg, MediaType.get("image/svg+xml"));
        return response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Encoding")
                .header("Content-Type", png != null ? "image/png" : "image/svg+xml").body(body).build();
    }

    private static byte[] draw(String svg) {
        Matcher box = VIEW_BOX.matcher(svg);
        float bx = 0, by = 0, bw = 24, bh = 24;
        if (box.find()) {
            bx = Float.parseFloat(box.group(1));
            by = Float.parseFloat(box.group(2));
            bw = Float.parseFloat(box.group(3));
            bh = Float.parseFloat(box.group(4));
        }
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(SIZE / bw, SIZE / bh);
        canvas.translate(-bx, -by);
        Matcher m = ELEMENT.matcher(svg);
        int drawn = 0;
        while (m.find()) {
            java.util.Map<String, String> a = new java.util.HashMap<>();
            for (Matcher at = ATTRIBUTE.matcher(m.group(2)); at.find(); ) a.put(at.group(1), at.group(2));
            Paint fill = paint(a.get("fill"), Paint.Style.FILL), stroke = paint(a.get("stroke"), Paint.Style.STROKE);
            if (stroke != null) stroke.setStrokeWidth(num(a.get("stroke-width"), 1));
            // SVG fills black when no fill is given
            if (fill == null && !"none".equals(a.get("fill"))) fill = paint("#000000", Paint.Style.FILL);
            switch (m.group(1)) {
                case "rect": {
                    float x = num(a.get("x"), 0), y = num(a.get("y"), 0);
                    float w = num(a.get("width"), 0), h = num(a.get("height"), 0);
                    if (fill != null) canvas.drawRect(x, y, x + w, y + h, fill);
                    if (stroke != null) canvas.drawRect(x, y, x + w, y + h, stroke);
                    break;
                }
                case "circle": {
                    float cx = num(a.get("cx"), 0), cy = num(a.get("cy"), 0), r = num(a.get("r"), 0);
                    if (fill != null) canvas.drawCircle(cx, cy, r, fill);
                    if (stroke != null) canvas.drawCircle(cx, cy, r, stroke);
                    break;
                }
                default: {
                    Path p = path(a.get("d"));
                    if (fill != null) canvas.drawPath(p, fill);
                    if (stroke != null) canvas.drawPath(p, stroke);
                }
            }
            drawn++;
        }
        if (drawn == 0) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private static Path path(String d) {
        Path p = new Path();
        if (d == null) return p;
        Matcher t = PATH_TOKEN.matcher(d);
        java.util.List<String> tokens = new java.util.ArrayList<>();
        while (t.find()) tokens.add(t.group());
        float x = 0, y = 0;
        char command = 'M';
        for (int i = 0; i < tokens.size(); ) {
            String token = tokens.get(i);
            if (Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                i++;
                if (command == 'Z' || command == 'z') p.close();
                continue;
            }
            switch (command) {
                case 'M':
                    x = Float.parseFloat(tokens.get(i++));
                    y = Float.parseFloat(tokens.get(i++));
                    p.moveTo(x, y);
                    command = 'L';
                    break;
                case 'L':
                    x = Float.parseFloat(tokens.get(i++));
                    y = Float.parseFloat(tokens.get(i++));
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = Float.parseFloat(tokens.get(i++));
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = Float.parseFloat(tokens.get(i++));
                    p.lineTo(x, y);
                    break;
                default:
                    // relative or curve commands: not used by the instance's badges
                    throw new IllegalArgumentException("path command " + command);
            }
        }
        return p;
    }

    private static Paint paint(String color, Paint.Style style) {
        if (color == null || color.equals("none")) return null;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(style);
        paint.setColor(Color.parseColor(color.trim().toLowerCase(Locale.ROOT).length() == 4 ? expand(color.trim()) : color.trim()));
        return paint;
    }

    // #abc -> #aabbcc
    private static String expand(String c) {
        return "#" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
    }

    private static float num(String s, float otherwise) {
        if (s == null) return otherwise;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}
