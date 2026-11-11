package mov.film;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import java.util.Arrays;
import java.util.WeakHashMap;

/** Injected local palette client. Only screen entry refreshes the palette; drawing reads an int array. */
public final class DynamicTheme {
  private static final String[] KEYS = {"accent", "accent_hot", "accent_soft", "back", "surface", "text", "muted"};
  private static volatile int[] palette = FALLBACK;
  private static final WeakHashMap<Activity, Integer> screens = new WeakHashMap<>();

  public static void init(Activity activity) {
    SharedPreferences saved = activity.getSharedPreferences("airhome_theme", 0);
    int[] cached = palette.clone();
    for (int i = 0; i < KEYS.length; i++) cached[i] = saved.getInt(KEYS[i], cached[i]);
    palette = cached;
    refresh(activity);
    screens.put(activity, Arrays.hashCode(palette));
  }

  private static void refresh(Context context) {
    long started = android.os.SystemClock.elapsedRealtime();
    try {
      Bundle result = readPalette(context);
      if (result == null || result.getInt("v") != 1) return;
      int[] next = new int[KEYS.length];
      for (int i = 0; i < next.length; i++) {
        if (!result.containsKey(KEYS[i])) return;
        next[i] = result.getInt(KEYS[i]);
        if (i != 2 && (next[i] >>> 24) != 255) return;
      }
      SharedPreferences saved = context.getSharedPreferences("airhome_theme", 0);
      if (!Arrays.equals(next, palette) || !saved.contains("accent_hot")) {
        palette = next;
        SharedPreferences.Editor e = saved.edit();
        for (int i = 0; i < next.length; i++) e.putInt(KEYS[i], next[i]);
        e.apply();
      }
      android.util.Log.i("FilmTheme", "palette=" + Integer.toHexString(Arrays.hashCode(next))
          + " read_ms=" + (android.os.SystemClock.elapsedRealtime() - started));
    } catch (RuntimeException unavailable) {
      // Launcher absent, permission denied or provider unavailable: retain the local palette.
    }
  }

  private static Bundle readPalette(Context context) {
    PALETTE_SOURCE
  }

  public static void resume(Activity activity) {
    // Never recreate a player, including on a return from PiP or a system dialog.
    if (activity.getClass().getName().equals("mov.film.PlayerActivity")) return;
    refresh(activity);
    Integer before = screens.get(activity);
    int now = Arrays.hashCode(palette);
    if (before != null && before != now && !activity.isFinishing()) {
      screens.put(activity, now);
      android.util.Log.i("FilmTheme", "refresh_screen=" + activity.getClass().getSimpleName());
      activity.recreate();
    }
  }

  private static int blend(int surface, int accent) {
    int out = 0xff000000;
    for (int s = 0; s <= 16; s += 8) {
      out |= Math.round(((surface >>> s) & 255) * .88f + ((accent >>> s) & 255) * .12f) << s;
    }
    return out;
  }

  public static int color(int original) {
    int[] p = palette;
    int opaque = original | 0xff000000;
    int mapped;
    switch (opaque) {
      COLOR_CASES
      default: return original;
    }
    return (original & 0xff000000) | (mapped & 0xffffff);
  }

  public static android.content.res.ColorStateList shapeColors() {
    return new android.content.res.ColorStateList(
        new int[][]{{android.R.attr.state_focused}, {}},
        new int[]{android.graphics.Color.WHITE, color(0xffa855f7)});
  }

  public static void progress(android.widget.ProgressBar view) {
    int[] p = palette;
    android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(p[1]);
    android.content.res.ColorStateList track = android.content.res.ColorStateList.valueOf(p[2]);
    view.setIndeterminateTintList(accent);
    view.setProgressTintList(accent);
    view.setSecondaryProgressTintList(track);
    view.setProgressBackgroundTintList(track);
  }

  public static void background(View view, int id) {
    view.setBackground(id == 0 ? null : drawable(view.getContext(), id));
  }

  public static void image(android.widget.ImageView view, int id) {
    view.setImageDrawable(id == 0 ? null : drawable(view.getContext(), id));
  }

  public static Drawable drawable(Context c, int id) {
    return drawable(c.getResources(), id, c.getTheme());
  }

  public static Drawable drawable(Resources r, int id) {
    return drawable(r, id, null);
  }

  public static Drawable drawable(Resources r, int id, Resources.Theme theme) {
    Drawable generated = DynamicDrawables.create(r, id);
    return generated == null ? r.getDrawable(id, theme) : generated;
  }
}
