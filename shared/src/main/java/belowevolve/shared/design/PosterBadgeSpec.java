package belowevolve.shared.design;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared with the standalone film.mov adapter; no Android or Kotlin runtime required. */
public final class PosterBadgeSpec {
    public static final int TEXT_SP = 11;
    public static final int LINE_SP = 14;
    public static final int PADDING_X_DP = 5;
    public static final int PADDING_Y_DP = 3;
    public static final int CORNER_DP = 6;
    public static final int CIRCLE = 0;
    public static final int EPISODE = 1;
    public static final int FILLED_CIRCLE = 2;
    public static final int SEASON_TAG = 3;
    public static final int FULL = 4;
    public static final int OUTLINE = 5;
    public static final String[] FORMATS = {
        "Круг", "Только серия", "Залитый круг", "Плашка сезона", "Сезон и серия", "Контурный"
    };

    private static final Pattern SEASON_NUMBER = Pattern.compile(
        "(?iu)^(?:(?:сезон|season|[сs])\\s*)?(\\d+)(?:\\s*(?:сезон|season))?$"
    );
    private static final Pattern EPISODE_NUMBER = Pattern.compile(
        "(?iu)^(?:(?:серия|эпизод|episode|e)\\s*)?(\\d+)(?:\\s*(?:серия|эпизод|episode))?$"
    );

    private PosterBadgeSpec() {}

    public static String season(String value) {
        if (value == null) return "";
        String number = value.trim().replaceFirst("(?iu)^(?:сезон\\s*|season\\s*|[сs]\\s*)(?=\\d)", "");
        return number.matches("\\d+") ? "С" + number : number;
    }

    public static String seasonNumber(String value) { return number(SEASON_NUMBER, value); }
    public static String episodeNumber(String value) { return number(EPISODE_NUMBER, value); }

    public static String seasonLabel(String value) {
        String number = seasonNumber(value);
        return number.isEmpty() ? clean(value) : number + " сезон";
    }

    public static String episodeLabel(String value) {
        String number = episodeNumber(value);
        return number.isEmpty() ? clean(value) : number + " серия";
    }

    /** Decorative prefixes keep the normal badge text size and line height. */
    public static String seasonPrefix(String season, String episode, int format) {
        boolean prefix = format == CIRCLE || format == FILLED_CIRCLE || format == SEASON_TAG;
        return prefix && !episodeLabel(episode).isEmpty() ? seasonNumber(season) : "";
    }

    public static String format(String season, String episode, int format) {
        String label = episodeLabel(episode);
        if (format == EPISODE) return label;
        if (!seasonPrefix(season, episode, format).isEmpty()) return label;
        String first = seasonLabel(season);
        return first.isEmpty() ? label : label.isEmpty() ? first : first + " · " + label;
    }

    private static String number(Pattern pattern, String value) {
        Matcher match = pattern.matcher(clean(value));
        if (!match.matches()) return "";
        String number = match.group(1);
        int start = 0;
        while (start < number.length() - 1 && number.charAt(start) == '0') start++;
        return number.substring(start);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
