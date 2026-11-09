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
        row.setBaselineAligned(false);
        GradientDrawable background = new GradientDrawable();
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
        view.setGravity(view instanceof SeasonText ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        view.setEllipsize(TextUtils.TruncateAt.END);
        int line = sp(view.getContext(), PosterBadgeSpec.LINE_SP);
        int height = badgeHeight(view.getContext());
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height));
        int horizontal = dp(view.getContext(), view instanceof SeasonText ? 4 : PosterBadgeSpec.PADDING_X_DP);
        int vertical = dp(view.getContext(), PosterBadgeSpec.PADDING_Y_DP);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        if (view instanceof SeasonText) {
            view.setMinWidth(height);
        }
        if (Build.VERSION.SDK_INT >= 28) view.setLineHeight(line);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int sp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().scaledDensity);
    }

    private static int badgeHeight(Context context) {
        return sp(context, PosterBadgeSpec.LINE_SP) + 2 * dp(context, PosterBadgeSpec.PADDING_Y_DP);
    }

    public static final class SeasonText extends TextView {
        public SeasonText(Context context) {
            super(context);
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(0x33ffffff);
            float corner = dp(context, PosterBadgeSpec.CORNER_DP);
            surface.setCornerRadii(new float[] {corner, corner, 0, 0, 0, 0, corner, corner});
            setBackground(surface);
        }
        @Override public void setText(CharSequence value, BufferType type) {
            String raw = value == null ? "" : value.toString().trim();
            String number = PosterBadgeSpec.seasonNumber(raw);
            super.setText(number.isEmpty() ? raw : number, type);
        }
        @Override protected void onMeasure(int width, int height) {
            if (length() == 0) setMeasuredDimension(0, badgeHeight(getContext()));
            else super.onMeasure(width, height);
        }
    }

    public static final class EpisodeText extends TextView {
        public EpisodeText(Context context) { super(context); }
        @Override public void setText(CharSequence value, BufferType type) {
            super.setText(PosterBadgeSpec.episodeLabel(value == null ? "" : value.toString()), type);
        }
        @Override protected void onMeasure(int width, int height) {
            if (length() == 0) setMeasuredDimension(0, badgeHeight(getContext()));
            else super.onMeasure(width, height);
        }
    }
}
