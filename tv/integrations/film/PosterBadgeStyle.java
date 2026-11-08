package mov.film;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import belowevolve.shared.design.PosterBadgeSpec;

/** Two child labels preserve film.mov's binding contract; visual metrics match Air Home. */
public final class PosterBadgeStyle {
    private PosterBadgeStyle() {}

    public static ViewGroup create(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, PosterBadgeSpec.PADDING_X_DP), dp(context, PosterBadgeSpec.PADDING_Y_DP),
            dp(context, PosterBadgeSpec.PADDING_X_DP), dp(context, PosterBadgeSpec.PADDING_Y_DP));
        GradientDrawable background = new GradientDrawable();
        // Filled badges use the dark accent, just like film.mov's existing "Сегодня" chip.
        background.setColor(DynamicTheme.color(0xff8b5cf6));
        background.setCornerRadius(dp(context, PosterBadgeSpec.CORNER_DP));
        row.setBackground(background);
        row.addView(label(new SeasonText(context)));
        row.addView(label(new EpisodeText(context)));
        return row;
    }

    private static TextView label(TextView view) {
        Type.badgeFixed(view);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, PosterBadgeSpec.TEXT_SP);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28)
            view.setLineHeight(Math.round(PosterBadgeSpec.LINE_SP * view.getResources().getDisplayMetrics().scaledDensity));
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static final class SeasonText extends TextView {
        public SeasonText(Context context) { super(context); }
        @Override public void setText(CharSequence value, BufferType type) {
            super.setText(PosterBadgeSpec.season(value == null ? "" : value.toString()), type);
        }
    }

    public static final class EpisodeText extends TextView {
        public EpisodeText(Context context) { super(context); }
        @Override public void setText(CharSequence value, BufferType type) {
            String episode = value == null ? "" : value.toString();
            boolean season = getParent() instanceof ViewGroup
                && ((ViewGroup)getParent()).getChildCount() > 0
                && ((TextView)((ViewGroup)getParent()).getChildAt(0)).length() > 0;
            super.setText(season && !episode.isEmpty() ? " · " + episode : episode, type);
        }
    }
}
