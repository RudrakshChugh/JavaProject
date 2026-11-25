package skillexchange.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import skillexchange.exception.ConnectionLostException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Small helpers shared by all screens: background work, building blocks and text formats. */
final class Ui {
    private static final Logger LOG = Logger.getLogger(Ui.class.getName());

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    /** Threads for talking to the server. Daemon threads, so they never keep the application open. */
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ui-worker");
        t.setDaemon(true);
        return t;
    });

    private Ui() { }

    /** Work that returns nothing but may fail. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    /**
     * Runs the work on a background thread and hands the result back on the JavaFX thread.
     *
     * JavaFX is single-threaded: only the JavaFX Application Thread may touch the screen, and
     * anything slow on that thread freezes the window. A Task does the slow part (a call to the
     * server) elsewhere, and its onSucceeded and onFailed handlers run on the JavaFX thread again.
     */
    static <T> void async(Callable<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
        // An anonymous inner class: a one-off subclass of Task written where it is used.
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onDone.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        WORKERS.execute(task);
    }

    /**
     * Starts work on a background thread straight away and returns a handle to its future result.
     * Several of these started together run at the same time, so three questions to the server
     * take as long as the slowest one, not as long as all three added up.
     */
    static <T> CompletableFuture<T> start(Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        WORKERS.execute(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Waits for a result begun with {@link #start}. A failure comes out as the exception the work threw. */
    static <T> T await(CompletableFuture<T> result) throws Exception {
        try {
            return result.get();
        } catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    /** The text to show a user for any exception. */
    static String message(Throwable t) {
        if (t instanceof SkillExchangeException) return t.getMessage();
        if (t instanceof ConnectionLostException) return t.getMessage() + ". Check that the server is running.";
        if (t instanceof java.io.IOException) return t.getMessage();
        LOG.log(Level.WARNING, "Unexpected error in the user interface", t);
        return "Something unexpected went wrong: " + t;
    }

    /** A system alert, kept for failures that are not about a rule: a lost connection, a file that cannot be written. */
    static void alert(Window owner, String header, String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        alert.setHeaderText(header);
        alert.setTitle("Skill Exchange");
        if (owner != null) alert.initOwner(owner);
        alert.getDialogPane().getStylesheets().add(App.STYLESHEET);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.showAndWait();
    }

    // ---------- Building blocks ----------

    static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    static Label wrapped(String text, String... styleClasses) {
        Label l = label(text, styleClasses);
        l.setWrapText(true);
        return l;
    }

    /** A heading in the display style. */
    static Label heading(String text, String size) {
        return label(text, "display", size);
    }

    static Button btn(String text, String... styleClasses) {
        Button b = new Button(text);
        b.getStyleClass().setAll("btn");
        b.getStyleClass().addAll(styleClasses);
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    /** A button with an icon before its text. */
    static Button iconBtn(String icon, String text, String... styleClasses) {
        Button b = btn(text, styleClasses);
        b.setGraphic(Icons.of(icon, 16));
        return b;
    }

    static Button iconButton(String icon, String tooltip, String... styleClasses) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 18));
        b.getStyleClass().setAll("icon-btn");
        b.getStyleClass().addAll(styleClasses);
        b.setAccessibleText(tooltip);
        b.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        return b;
    }

    static Label badge(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("badge", kind);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    static Label typeBadge(ListingType type) {
        return badge(type.getLabel(), type.name().toLowerCase());
    }

    /** A round badge with the first letter of the name, in one of four colours chosen by the student's id. */
    static StackPane avatar(Student student, double size) {
        return avatar(student.getName(), student.getId(), size);
    }

    static StackPane avatar(String name, long tone, double size) {
        Label letter = new Label(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        letter.setStyle("-fx-font-size: " + Math.round(size * 0.4) + "px;");
        StackPane circle = new StackPane(letter);
        circle.getStyleClass().addAll("avatar", "av-" + (Math.floorMod(tone, 4) + 1));
        circle.setMinSize(size, size);
        circle.setMaxSize(size, size);
        return circle;
    }

    /** An invisible node that takes all spare room, pushing its neighbours apart. */
    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    static HBox row(double gap, Node... children) {
        HBox box = new HBox(gap, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    static VBox card(Node... children) {
        VBox box = new VBox(10, children);
        box.getStyleClass().add("card");
        return box;
    }

    /** The title and one-line description at the top of a page. */
    static VBox pageHead(String title, String subtitle) {
        Label sub = wrapped(subtitle, "muted");
        sub.setMaxWidth(620);
        VBox box = new VBox(6, heading(title, "h1"), sub);
        box.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));
        return box;
    }

    static HBox sectionHead(String title, Node... side) {
        HBox box = row(12, heading(title, "h2"), spacer());
        box.getChildren().addAll(side);
        box.setPadding(new javafx.geometry.Insets(22, 0, 0, 0));
        return box;
    }

    /** The dashed box shown where a list has nothing in it. */
    static VBox empty(String icon, String title, String text, Node... action) {
        StackPane circle = new StackPane(Icons.of(icon, 20));
        circle.getStyleClass().add("empty-icon");
        Label body = wrapped(text, "muted");
        body.setMaxWidth(420);
        body.setAlignment(Pos.CENTER);
        body.setStyle("-fx-text-alignment: center;");
        VBox box = new VBox(circle, heading(title, "h3"));
        if (!text.isBlank()) box.getChildren().add(body);
        box.getChildren().addAll(action);
        box.getStyleClass().add("empty");
        return box;
    }

    /** A note with an icon: an explanation, or with "warn" a warning. */
    static HBox note(String icon, String text, String... styleClasses) {
        Label body = wrapped(text);
        HBox.setHgrow(body, Priority.ALWAYS);
        HBox box = new HBox(Icons.of(icon, 17), body);
        box.getStyleClass().add("note");
        box.getStyleClass().addAll(styleClasses);
        return box;
    }

    /** A thin horizontal line. */
    static Region rule() {
        Region r = new Region();
        r.getStyleClass().add("rule");
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /**
     * Lays nodes out in rows of equal-width columns. Nodes in one row get the same height.
     * A generic bounded wildcard: any list of things that are Nodes will do.
     */
    static GridPane grid(int columns, double gap, List<? extends Node> nodes) {
        GridPane grid = new GridPane();
        grid.setHgap(gap);
        grid.setVgap(gap);
        for (int c = 0; c < columns; c++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            grid.getColumnConstraints().add(constraint);
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
                region.setMaxHeight(Double.MAX_VALUE);
            }
            GridPane.setFillWidth(node, true);
            GridPane.setFillHeight(node, true);
            grid.add(node, i % columns, i / columns);
        }
        return grid;
    }

    /** Counts a number up from zero in half a second, like the old home page did. */
    static void countUp(Label label, int target) {
        IntegerProperty value = new SimpleIntegerProperty(0);
        label.textProperty().bind(value.asString());
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(550),
                new KeyValue(value, target, Interpolator.EASE_OUT)));
        timeline.setOnFinished(e -> {
            label.textProperty().unbind();
            label.setText(String.valueOf(target));
        });
        timeline.play();
    }

    // ---------- Text ----------

    static String timeRange(TimeSlot slot) {
        return slot.getStart().format(TIME) + " – " + slot.getEnd().format(TIME);
    }

    /** "Tomorrow · Thu 26 Nov" for the next two days, otherwise "Fri 27 Nov". */
    static String dayLabel(LocalDateTime time) {
        String word = dayWord(time);
        return word == null ? time.format(DAY) : word + " · " + time.format(DAY);
    }

    static String dayWord(LocalDateTime time) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), time.toLocalDate());
        return days == 0 ? "Today" : days == 1 ? "Tomorrow" : days == -1 ? "Yesterday" : null;
    }

    static String shortDay(LocalDateTime time) {
        String word = dayWord(time);
        return word == null ? time.format(DAY) : word;
    }

    /** "in 19h 38m 11s", or "in 3d 4h" when it is more than a day away. */
    static String timeLeft(LocalDateTime until) {
        long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), until);
        if (seconds <= 0) return "started";
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        if (days > 0) return "in " + days + "d " + hours + "h";
        return "in " + hours + "h " + String.format("%02dm %02ds", minutes, seconds % 60);
    }

    static String ago(LocalDateTime time) {
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(time, LocalDateTime.now()));
        if (minutes < 1) return "just now";
        if (minutes < 60) return plural((int) minutes, "minute") + " ago";
        if (minutes < 1440) return plural((int) (minutes / 60), "hour") + " ago";
        return plural((int) (minutes / 1440), "day") + " ago";
    }

    static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    static String price(Listing listing) {
        return listing.getPriceDescription();
    }
}
