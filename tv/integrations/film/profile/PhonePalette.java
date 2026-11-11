package mov.film;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

/** The same Android dynamic dark roles used by Air Remote's Material 3 theme. No IPC. */
final class PhonePalette {
  static Bundle read(Context c) {
    if (Build.VERSION.SDK_INT < 31) return Bundle.EMPTY;
    Bundle colors = new Bundle();
    if (Build.VERSION.SDK_INT >= 34) {
      colors.putInt("accent", color(c, "system_primary_light"));
      colors.putInt("accent_hot", color(c, "system_primary_dark"));
      colors.putInt("back", color(c, "system_background_dark"));
      colors.putInt("surface", color(c, "system_surface_container_dark"));
      colors.putInt("text", color(c, "system_on_surface_dark"));
      colors.putInt("muted", color(c, "system_on_surface_variant_dark"));
    } else {
      // Older Android versions publish tonal ramps rather than full semantic roles.
      colors.putInt("accent", color(c, "system_accent1_600"));
      colors.putInt("accent_hot", color(c, "system_accent1_200"));
      colors.putInt("back", color(c, "system_neutral1_900"));
      colors.putInt("surface", color(c, "system_neutral1_900"));
      colors.putInt("text", color(c, "system_neutral1_100"));
      colors.putInt("muted", color(c, "system_neutral2_200"));
    }
    colors.putInt("accent_soft", (colors.getInt("accent") & 0xffffff) | 0x66000000);
    colors.putInt("v", 1);
    return colors;
  }

  private static int foreground(int color) {
    return color == DynamicTheme.color(0xff8b5cf6) ? DynamicTheme.color(0xffa855f7) : color;
  }

  public static void text(android.widget.TextView view, int color) {
    view.setTextColor(foreground(color));
  }

  public static void tint(android.widget.ImageView view, int color) {
    view.setColorFilter(foreground(color));
  }

  public static void tint(
      android.widget.ImageView view, int color, android.graphics.PorterDuff.Mode mode) {
    view.setColorFilter(foreground(color), mode);
  }

  private static int color(Context c, String name) {
    int id = c.getResources().getIdentifier(name, "color", "android");
    if (id == 0) throw new android.content.res.Resources.NotFoundException(name);
    return c.getResources().getColor(id, c.getTheme());
  }
}
