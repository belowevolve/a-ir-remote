package belowevolve.shared.design;

/** Text for the requested or accepted relative offset, independent of Android. */
public final class SeekText {
    private SeekText() {}
    public static String duration(int seconds) {
        long value = Math.abs((long) seconds);
        long hours = value / 3600, minutes = value % 3600 / 60, remainder = value % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) text.append(hours).append(" ч");
        if (minutes > 0) { if (text.length() > 0) text.append(' '); text.append(minutes).append(" мин"); }
        if (remainder > 0 || text.length() == 0) { if (text.length() > 0) text.append(' '); text.append(remainder).append(" сек"); }
        return text.toString();
    }
    public static String direction(int seconds) { return seconds < 0 ? "назад" : "вперёд"; }
    public static String sent(int seconds) { return "Отправлена перемотка на " + duration(seconds) + " " + direction(seconds); }
    public static String accepted(int seconds) { return "Перемотка: " + duration(seconds) + " " + direction(seconds); }
}
