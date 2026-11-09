package mov.film;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
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
        row.setClipChildren(false);
        row.setPadding(dp(context, 3), dp(context, PosterBadgeSpec.PADDING_Y_DP),
            dp(context, 2), dp(context, PosterBadgeSpec.PADDING_Y_DP));
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
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, line));
        if (view instanceof SeasonText) {
            view.setMinWidth(line);
            view.setPadding(dp(view.getContext(), 1), 0, dp(view.getContext(), 1), 0);
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

    private static void spacing(TextView child) {
        if (!(child.getParent() instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) child.getParent();
        if (row.getChildCount() != 2) return;
        TextView season = (TextView) row.getChildAt(0);
        TextView episode = (TextView) row.getChildAt(1);
        boolean prefix = season.length() > 0;
        SeasonText number = (SeasonText) season;
        season.setBackground(number.numbered ? number.ring : null);
        season.setPadding(dp(child.getContext(), 1), 0, dp(child.getContext(), 1), 0);
        row.setPadding(dp(child.getContext(), prefix ? 3 : PosterBadgeSpec.PADDING_X_DP),
            dp(child.getContext(), PosterBadgeSpec.PADDING_Y_DP),
            dp(child.getContext(), prefix ? (episode.length() > 0 ? 2 : 3) : PosterBadgeSpec.PADDING_X_DP),
            dp(child.getContext(), PosterBadgeSpec.PADDING_Y_DP));
        LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) season.getLayoutParams();
        int gap = prefix && episode.length() > 0 ? dp(child.getContext(), 4) : 0;
        if (layout.getMarginEnd() != gap) {
            layout.setMarginEnd(gap);
            season.setLayoutParams(layout);
        }
    }

    public static final class SeasonText extends TextView {
        private final InsetDrawable ring;
        private boolean numbered;
        public SeasonText(Context context) {
            super(context);
            GradientDrawable outline = new GradientDrawable();
            outline.setShape(GradientDrawable.OVAL);
            outline.setColor(android.graphics.Color.TRANSPARENT);
            outline.setStroke(dp(context, 1), 0xa6ffffff);
            // The outline uses the row's padding without shrinking the season's text.
            ring = new InsetDrawable(outline, -dp(context, 1));
        }
        @Override public void setText(CharSequence value, BufferType type) {
            String raw = value == null ? "" : value.toString().trim();
            String number = PosterBadgeSpec.seasonNumber(raw);
            numbered = !number.isEmpty();
            super.setText(numbered ? number : raw, type);
            spacing(this);
        }
        @Override protected void onMeasure(int width, int height) {
            if (length() == 0) setMeasuredDimension(0, sp(getContext(), PosterBadgeSpec.LINE_SP));
            else super.onMeasure(width, height);
        }
    }

    public static final class EpisodeText extends TextView {
        public EpisodeText(Context context) { super(context); }
        @Override public void setText(CharSequence value, BufferType type) {
            super.setText(PosterBadgeSpec.episodeLabel(value == null ? "" : value.toString()), type);
            spacing(this);
        }
    }
}
