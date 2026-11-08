package belowevolve.shared.design;

/** Shared with the standalone film.mov adapter; no Android or Kotlin runtime required. */
public final class PosterBadgeSpec {
    public static final int TEXT_SP = 11;
    public static final int LINE_SP = 14;
    public static final int PADDING_X_DP = 5;
    public static final int PADDING_Y_DP = 3;
    public static final int CORNER_DP = 6;

    private PosterBadgeSpec() {}

    public static String season(String value) {
        if (value == null) return "";
        String number = value.trim().replaceFirst("(?iu)^(?:сезон\\s*|season\\s*|[сs]\\s*)(?=\\d)", "");
        return number.matches("\\d+") ? "С" + number : number;
    }

    public static String episode(String season, String episode) {
        String first = season(season);
        String second = episode == null ? "" : episode.trim();
        return first.isEmpty() ? second : second.isEmpty() ? first : first + " · " + second;
    }
}
