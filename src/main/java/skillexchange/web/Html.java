package skillexchange.web;

import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Building blocks for the pages: escaping, icons, and the small pieces every page repeats.
 * Each method returns a piece of HTML as a String.
 */
final class Html {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    /** The line icons, as the inside of an SVG element drawn in a 24 by 24 box. */
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("search", "<circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"m20 20-3.5-3.5\"/>"),
            Map.entry("calendar", "<rect x=\"3\" y=\"4.5\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M3 9.5h18M8 2.5v4M16 2.5v4\"/>"),
            Map.entry("clock", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v5l3 2\"/>"),
            Map.entry("sun", "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4\"/>"),
            Map.entry("moon", "<path d=\"M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z\"/>"),
            Map.entry("check", "<path d=\"M5 12.5l4.5 4.5L19 7.5\"/>"),
            Map.entry("x", "<path d=\"M6 6l12 12M18 6 6 18\"/>"),
            Map.entry("plus", "<path d=\"M12 5v14M5 12h14\"/>"),
            Map.entry("lock", "<rect x=\"5\" y=\"11\" width=\"14\" height=\"9\" rx=\"2\"/><path d=\"M8 11V8a4 4 0 0 1 8 0v3\"/>"),
            Map.entry("wallet", "<rect x=\"3\" y=\"6\" width=\"18\" height=\"13\" rx=\"2\"/><path d=\"M3 10h18M16 14.5h2\"/>"),
            Map.entry("up", "<path d=\"M3 17l6-6 4 4 8-8M15 7h6v6\"/>"),
            Map.entry("undo", "<path d=\"M4 9h10a5 5 0 0 1 0 10H9M4 9l4-4M4 9l4 4\"/>"),
            Map.entry("info", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 11v5.5M12 7.5v.01\"/>"),
            Map.entry("alert", "<path d=\"M12 3.5 2.5 20h19L12 3.5zM12 10v4.5M12 17.2v.01\"/>"),
            Map.entry("play", "<path d=\"M7 5l12 7-12 7z\"/>"),
            Map.entry("chevron", "<path d=\"M6 9l6 6 6-6\"/>"),
            Map.entry("dice", "<rect x=\"4\" y=\"4\" width=\"16\" height=\"16\" rx=\"3\"/><path d=\"M9 9v.01M15 9v.01M12 12v.01M9 15v.01M15 15v.01\"/>"),
            Map.entry("chat", "<path d=\"M4 5h16v11H10l-5 4v-4H4z\"/>"),
            Map.entry("pin", "<path d=\"M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z\"/><circle cx=\"12\" cy=\"10\" r=\"2.5\"/>"),
            Map.entry("book", "<path d=\"M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zM4 21V5M9 8h6\"/>"),
            Map.entry("download", "<path d=\"M12 4v11M7 11l5 5 5-5M5 20h14\"/>"));

    private Html() { }

    /**
     * Makes text safe to put inside HTML. Everything a user typed goes through this, so a name
     * such as {@code <script>} is shown as those characters instead of being run by the browser.
     */
    static String esc(Object value) {
        if (value == null) return "";
        String s = value.toString();
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Makes text safe to put inside a URL's query, for links such as /?q=... */
    static String url(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String icon(String name) {
        return "<svg class=\"icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">" + ICONS.getOrDefault(name, ICONS.get("info")) + "</svg>";
    }

    /** A round badge with the first letter of the name, in one of five colours chosen by the id. */
    static String avatar(Student s, String size) {
        return avatar(s.getName(), s.getId(), size);
    }

    static String avatar(String name, long tone, String size) {
        String initial = name.isBlank() ? "?" : name.strip().substring(0, 1).toUpperCase();
        return "<span class=\"avatar " + size + " av-" + Math.floorMod(tone, 5) + "\" aria-hidden=\"true\">" + esc(initial) + "</span>";
    }

    static String badge(String text, String kind, boolean dot) {
        return "<span class=\"badge b-" + kind + (dot ? " dot" : "") + "\">" + esc(text) + "</span>";
    }

    static String typeBadge(ListingType type) {
        return badge(type.getLabel(), type.name().toLowerCase(), false);
    }

    static String head(String title, String subtitle) {
        return "<div class=\"page-head\"><h1>" + esc(title) + "</h1><p>" + esc(subtitle) + "</p></div>";
    }

    /** The dashed box shown where a list has nothing in it. {@code actionHtml} is already HTML. */
    static String empty(String icon, String title, String text, String actionHtml) {
        return "<div class=\"empty\"><span class=\"empty-icon\">" + icon(icon) + "</span><h3>" + esc(title) + "</h3>"
                + (text.isEmpty() ? "" : "<p>" + esc(text) + "</p>") + actionHtml + "</div>";
    }

    static String note(String icon, String text, boolean warn) {
        return "<p class=\"note" + (warn ? " warn" : "") + "\">" + icon(icon) + "<span>" + esc(text) + "</span></p>";
    }

    /** The two hidden fields every form carries: the secret of the visit, and where to go back to. */
    static String hidden(Visit visit, String back) {
        return "<input type=\"hidden\" name=\"csrf\" value=\"" + esc(visit.csrf) + "\">"
                + "<input type=\"hidden\" name=\"back\" value=\"" + esc(back) + "\">";
    }

    /** A form that is nothing but one button, for actions such as "remove this slot". */
    static String postButton(Visit visit, String action, String back, String fieldName, long fieldValue,
                             String buttonClass, String labelHtml, String title) {
        return "<form class=\"inline\" method=\"post\" action=\"" + action + "\">" + hidden(visit, back)
                + "<input type=\"hidden\" name=\"" + fieldName + "\" value=\"" + fieldValue + "\">"
                + "<button class=\"" + buttonClass + "\" title=\"" + esc(title) + "\" aria-label=\"" + esc(title) + "\">" + labelHtml + "</button></form>";
    }

    // ---------- Text ----------

    static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    static String timeRange(TimeSlot slot) {
        return slot.getStart().format(TIME) + " – " + slot.getEnd().format(TIME);
    }

    static String dayWord(LocalDateTime time) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), time.toLocalDate());
        return days == 0 ? "Today" : days == 1 ? "Tomorrow" : days == -1 ? "Yesterday" : null;
    }

    static String shortDay(LocalDateTime time) {
        String word = dayWord(time);
        return word == null ? time.format(DAY) : word;
    }

    static String dayLabel(LocalDateTime time) {
        String word = dayWord(time);
        return word == null ? time.format(DAY) : word + " · " + time.format(DAY);
    }

    static String timeLeft(LocalDateTime until) {
        long minutes = ChronoUnit.MINUTES.between(LocalDateTime.now(), until);
        if (minutes <= 0) return "started";
        if (minutes >= 1440) return "in " + minutes / 1440 + "d " + minutes % 1440 / 60 + "h";
        return "in " + minutes / 60 + "h " + minutes % 60 + "m";
    }

    static String ago(LocalDateTime time) {
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(time, LocalDateTime.now()));
        if (minutes < 1) return "just now";
        if (minutes < 60) return plural((int) minutes, "minute") + " ago";
        if (minutes < 1440) return plural((int) (minutes / 60), "hour") + " ago";
        return plural((int) (minutes / 1440), "day") + " ago";
    }

    static String price(Listing listing) {
        return listing.getPriceDescription();
    }
}
