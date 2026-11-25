package skillexchange.ui;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * The small line icons of the interface. Each one is an SVG path drawn in a 24 by 24 box, shown
 * with a JavaFX SVGPath node and coloured by the stylesheet (style class "icon").
 */
final class Icons {
    // Map.ofEntries builds an immutable map; the icons never change at run time.
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("search", "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14z M16.5 16.5L21 21"),
            Map.entry("moon", "M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"),
            Map.entry("sun", "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M5 19l1.5-1.5 M17.5 6.5L19 5"),
            Map.entry("chevron", "M6 9l6 6l6-6"),
            Map.entry("plus", "M12 5v14 M5 12h14"),
            Map.entry("x", "M6 6l12 12 M18 6L6 18"),
            Map.entry("clock", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z M12 7v5l3 2"),
            Map.entry("pin", "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.800-7 11-7 11z M12 8a2.500 2.500 0 1 0 0 5a2.500 2.500 0 0 0 0-5z"),
            Map.entry("lock", "M7 11V8a5 5 0 0 1 10 0v3 M5 11h14v9H5z"),
            Map.entry("wallet", "M3 6h18v12H3z M3 10h18 M7 15h3"),
            Map.entry("up", "M3 17l6-6l4 4l8-8 M15 7h6v6"),
            Map.entry("undo", "M8 6L3 11l5 5 M3 11h11a5 5 0 0 1 0 10h-3"),
            Map.entry("check", "M4 12l5 5L20 6"),
            Map.entry("alert", "M12 3l10 18H2z M12 10v5 M12 18v0.500"),
            Map.entry("info", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z M12 11v6 M12 7.500v0.500"),
            Map.entry("play", "M7 4l13 8l-13 8z"),
            Map.entry("dice", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M8.500 8.500v0.100 M15.500 8.500v0.100 M12 12v0.100 M8.500 15.500v0.100 M15.500 15.500v0.100"),
            Map.entry("chat", "M4 5h16v11H10l-5 4v-4H4z"),
            Map.entry("calendar", "M4 6h16v14H4z M4 10h16 M8 3v4 M16 3v4"),
            Map.entry("book", "M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z M4 21V5 M9 8h6"),
            Map.entry("download", "M12 4v11 M7 11l5 5l5-5 M5 20h14"),
            Map.entry("send", "M4 12l16-8l-6 16l-3-7z"));

    private Icons() { }

    static StackPane of(String name, double size, String... extraStyleClasses) {
        SVGPath path = new SVGPath();
        path.setContent(PATHS.getOrDefault(name, PATHS.get("info")));
        path.getStyleClass().add("icon");
        path.getStyleClass().addAll(extraStyleClasses);
        double scale = size / 24;
        path.setScaleX(scale);
        path.setScaleY(scale);
        // The Group reports the path's size after scaling, so the StackPane can centre it properly.
        StackPane box = new StackPane(new Group(path));
        box.setMinSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }

    static StackPane of(String name) {
        return of(name, 17);
    }
}
