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
    public static final int LOWERCASE = 2;
    public static final int UPPERCASE = 3;
    public static final int MULTIPLY = 4;
    public static final int PADDED_MULTIPLY = 5;
    public static final String[] FORMATS = {
        "⑤ 1 серия", "1 серия", "s01e01", "S01E01", "5×05", "05×05"
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

    /** The circle is rendered separately, inside the existing text line height. */
    public static String circleSeason(String season, String episode, int format) {
        return format == CIRCLE && !episodeLabel(episode).isEmpty() ? seasonNumber(season) : "";
    }

    public static String format(String season, String episode, int format) {
        String s = seasonNumber(season);
        String e = episodeNumber(episode);
        String label = episodeLabel(episode);
        if (format == EPISODE) return label;
        if (format == CIRCLE && !s.isEmpty() && !label.isEmpty()) return label;
        if (!s.isEmpty() && !e.isEmpty()) {
            switch (format) {
                case LOWERCASE: return "s" + padded(s) + "e" + padded(e);
                case UPPERCASE: return "S" + padded(s) + "E" + padded(e);
                case MULTIPLY: return s + "×" + padded(e);
                case PADDED_MULTIPLY: return padded(s) + "×" + padded(e);
            }
        }
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

    private static String padded(String number) { return number.length() < 2 ? "0" + number : number; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
