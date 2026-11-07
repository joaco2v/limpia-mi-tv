package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * live_preview (config.xml): while the Live TV tile stays focused for a moment, the last antenna channel plays inside
 * the tile, with sound. A TvView sits exactly over the zoomed tile with a cover on top that first shows the tile's own
 * artwork, then fades out once the tuner reports video, so the banner dissolves into the picture. Focus leaving the
 * tile, or Home going to the background, stops it at once and frees the tuner for the Live TV app.
 *
 * The channel: the one TCL's middleware is on (com.tcl.tvmanager, read by reflection, so other TVs simply skip it),
 * matched to the TV provider's channel list; else the channel numbered live_preview_fallback; else the first one.
 */
final class LivePreview {
    private static final String TAG = "LivePreview";

    private final Activity activity;
    private final FrameLayout root;
    private final String livePkg;
    private final String fallbackNumber;
    private final int delayMs;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout frame;
    private TvView tv;
    private Cover cover;
    private View card;             // the Live TV tile the preview belongs to, while one is pending or showing
    private boolean tuned;
    private int generation;        // bumps on every stop, so a late channel lookup does not tune a stale preview
    private ValueAnimator fade;

    LivePreview(Activity activity, FrameLayout root, String livePkg, String fallbackNumber, int delayMs, float density) {
        this.activity = activity;
        this.root = root;
        this.livePkg = livePkg;
        this.fallbackNumber = fallbackNumber;
        this.delayMs = delayMs;
        this.density = density;
    }

    /** A tile took focus: start the countdown for the Live TV tile, stop for any other. */
    void onTileFocused(View tile, String pkg, Drawable artwork, float scale) {
        if (livePkg == null || !livePkg.equals(pkg)) {
            stop();
            return;
        }
        if (tile == card) return;          // the same tile again (a re-layout): keep what is running
        stop();
        card = tile;
        final int gen = generation;
        main.postDelayed(() -> {
            if (gen == generation && card == tile && tile.isFocused()) show(tile, artwork, scale, gen);
        }, delayMs);
    }

    /** Focus moved anywhere: anything but the Live TV tile ends the preview. */
    void onFocusMoved(View now) {
        if (card != null && now != card) stop();
    }

    void stop() {
        generation++;
        card = null;
        main.removeCallbacksAndMessages(null);
        if (fade != null) fade.cancel();
        if (tv != null && tuned) tv.reset();   // releases the tuner session
        tuned = false;
        if (frame != null) frame.setVisibility(View.GONE);
    }

    private void show(View tile, Drawable artwork, float scale, int gen) {
        // The tile's on-screen rect in root coordinates, zoomed around its centre like the focused tile.
        Rect r = new Rect(0, 0, tile.getWidth(), tile.getHeight());
        root.offsetDescendantRectToMyCoords(tile, r);
        int w = Math.round(tile.getWidth() * scale), h = Math.round(tile.getHeight() * scale);
        int x = Math.round(r.exactCenterX() - w / 2f), y = Math.round(r.exactCenterY() - h / 2f);
        if (frame == null) {
            frame = new FrameLayout(activity);
            tv = new TvView(activity);
            cover = new Cover(activity);
            frame.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.setFocusable(false);
            root.addView(frame, new FrameLayout.LayoutParams(w, h));
            tv.setCallback(new TvView.TvInputCallback() {
                @Override public void onVideoAvailable(String inputId) { reveal(); }
                @Override public void onConnectionFailed(String inputId) { Log.w(TAG, "connection failed"); stop(); }
                @Override public void onDisconnected(String inputId) { Log.w(TAG, "disconnected"); stop(); }
            });
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
        lp.width = w;
        lp.height = h;
        frame.setLayoutParams(lp);
        frame.setTranslationX(x);
        frame.setTranslationY(y);
        cover.set(artwork, AppAdapter.cornerRadius(tile.getWidth(), density) * scale);
        frame.setVisibility(View.VISIBLE);
        frame.bringToFront();
        // The channel lookup reads the TV provider and TCL's middleware: off the main thread.
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            String[] target = findChannel(app);
            main.post(() -> {
                if (gen != generation || target == null) return;
                Log.i(TAG, "tuning " + target[0] + " " + target[1]);
                tv.setStreamVolume(1f);
                tv.tune(target[0], Uri.parse(target[1]));
                tuned = true;
            });
        }, "live-preview").start();
    }

    private void reveal() {
        if (cover == null || card == null) return;
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(cover.alpha, 0f);
        fade.setDuration(450);
        fade.addUpdateListener(a -> cover.setCoverAlpha((float) a.getAnimatedValue()));
        fade.start();
    }

    /** The TV's own antenna tuner input (a system app's TYPE_TUNER input), or null. */
    static String findTuner(Context ctx) {
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return null;
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                ApplicationInfo ai = info.getServiceInfo().applicationInfo;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return info.getId();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "no tuner: " + e);
        }
        return null;
    }

    /** {tuner input id, channel uri} for the channel to show, or null when there is no antenna tuner. */
    private String[] findChannel(Context ctx) {
        try {
            String tuner = findTuner(ctx);
            if (tuner == null) return null;

            // What TCL's middleware is tuned to: the last channel watched in its Live TV app.
            int onId = -1, tsId = -1, svcId = -1;
            String name = null;
            try {
                Class<?> cm = Class.forName("com.tcl.tvmanager.TTvChannelManager");
                Object mgr = cm.getMethod("getInstance", Context.class).invoke(null, ctx);
                Object pi = cm.getMethod("getCurrentProgramInfo").invoke(mgr);
                if (pi != null) {
                    Class<?> pc = pi.getClass();
                    onId = pc.getField("onID").getInt(pi);
                    tsId = pc.getField("transportStreamID").getInt(pi);
                    svcId = pc.getField("serviceID").getInt(pi);
                    Object n = pc.getField("programName").get(pi);
                    name = n == null ? null : n.toString().trim();
                }
            } catch (Throwable e) {
                Log.i(TAG, "no TCL channel manager: " + e);
            }

            // Several channels can share the triplet on this network (MEGA, TV+ and Bio Bio all read 1/1/32), so the
            // name decides between them; a lone triplet match is taken as is.
            long byName = -1, byTriplet = -1, byNumber = -1, first = -1;
            int tripletMatches = 0;
            try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI, new String[] {
                    TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                    TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, TvContract.Channels.COLUMN_SERVICE_ID}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!tuner.equals(c.getString(1))) continue;
                    long id = c.getLong(0);
                    if (first < 0) first = id;
                    if (fallbackNumber != null && fallbackNumber.equals(c.getString(2)) && byNumber < 0) byNumber = id;
                    if (c.getInt(4) == onId && c.getInt(5) == tsId && c.getInt(6) == svcId) {
                        tripletMatches++;
                        if (byTriplet < 0) byTriplet = id;
                        String dn = c.getString(3);
                        if (name != null && dn != null && name.equalsIgnoreCase(dn.trim())) byName = id;
                    }
                }
            }
            long pick = byName >= 0 ? byName : tripletMatches == 1 ? byTriplet : byNumber >= 0 ? byNumber : first;
            if (pick < 0) return null;
            return new String[] {tuner, TvContract.buildChannelUri(pick).toString()};
        } catch (Exception e) {
            Log.w(TAG, "channel lookup failed: " + e);
            return null;
        }
    }

    /**
     * Drawn over the video: the tile's artwork (faded out once the picture is up) and, always, the area outside the
     * tile's rounded corners in the dock band's colour, since a video surface cannot be clipped to round corners.
     */
    private final class Cover extends View {
        private Drawable artwork;
        private float radius;
        float alpha = 1f;
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path corners = new Path();
        private final Path clip = new Path();
        private final RectF bounds = new RectF();

        Cover(Context ctx) {
            super(ctx);
            mask.setColor(0xFF141416);
            setWillNotDraw(false);
        }

        void set(Drawable art, float r) {
            // Our own copy: the tile draws the same drawable, and bounds and alpha set here must not reach it.
            Drawable.ConstantState cs = art == null ? null : art.getConstantState();
            artwork = cs != null ? cs.newDrawable(getResources()).mutate() : art;
            radius = r;
            alpha = 1f;
            invalidate();
        }

        void setCoverAlpha(float a) {
            alpha = a;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            bounds.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            if (artwork != null && alpha > 0f) {
                c.save();
                c.clipPath(clip);
                artwork.setBounds(0, 0, getWidth(), getHeight());
                artwork.setAlpha(Math.round(alpha * 255));
                artwork.draw(c);
                artwork.setAlpha(255);
                c.restore();
            }
            corners.reset();
            corners.setFillType(Path.FillType.EVEN_ODD);
            corners.addRect(bounds, Path.Direction.CW);
            corners.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            c.drawPath(corners, mask);
        }
    }
}
