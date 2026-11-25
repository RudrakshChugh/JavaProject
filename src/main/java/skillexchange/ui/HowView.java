package skillexchange.ui;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import skillexchange.api.Event;
import skillexchange.model.RaceReport;
import skillexchange.model.RaceReport.Lane;

import java.util.ArrayList;
import java.util.List;

/** How it works: the main ideas behind the system, and the live concurrency test. */
final class HowView implements View {
    private static final String[][] CONCEPTS = {
            {"Inheritance", "Three listing types", "Paid, credit swap and free listings all extend one abstract Listing class."},
            {"Polymorphism", "One booking path", "Booking asks any listing for its credit cost. Only a swap returns more than zero."},
            {"JDBC", "One transaction per booking", "The booking, the slot and the credit hold are committed together, or rolled back together."},
            {"Exceptions", "Clear failures", "Broken rules throw checked exceptions such as InsufficientCreditsException, which travel to this window."},
            {"Serialization", "Objects on the wire", "Every call and reply crosses a socket as a serialized object. Password hashes are transient and stay on the server."},
            {"Synchronization", "No double booking", "A lock makes checking a slot and booking it one atomic step, with a UNIQUE index behind it."},
    };

    private final AppContext ctx;
    private final VBox raceOut = new VBox(12);
    private final VBox root = new VBox(14);

    HowView(AppContext ctx) {
        this.ctx = ctx;
        List<Node> concepts = new ArrayList<>();
        for (int i = 0; i < CONCEPTS.length; i++) {
            Label eyebrow = Ui.label(CONCEPTS[i][0].toUpperCase(), "eyebrow", "c" + (i % 3 + 1));
            VBox card = new VBox(6, eyebrow, Ui.heading(CONCEPTS[i][1], "h3"), Ui.wrapped(CONCEPTS[i][2], "muted"));
            card.getStyleClass().addAll("card", "concept", "lift");
            concepts.add(card);
        }

        Button run = Ui.iconBtn("play", "Run the race", "primary");
        run.setOnAction(e -> {
            run.setDisable(true);
            run.setText("Racing...");
            Ui.async(() -> ctx.api().raceDemo(), report -> {
                show(report);
                run.setDisable(false);
                run.setText("Run the race");
            }, error -> {
                ctx.failed(error);
                run.setDisable(false);
                run.setText("Run the race");
            });
        });
        VBox raceHead = new VBox(4, Ui.heading("Eight students. One slot. Same instant.", "h2"),
                Ui.label("Runs on the server against a fresh throwaway database, so your real data is never touched.", "muted"));
        HBox.setHgrow(raceHead, Priority.ALWAYS);
        VBox race = new VBox(20, Ui.row(16, raceHead, run), raceOut);
        race.getStyleClass().add("card");
        raceOut.setVisible(false);
        raceOut.setManaged(false);

        root.getChildren().addAll(
                Ui.pageHead("How it works", "A JavaFX client, a Java socket server and a JDBC database, with no frameworks. "
                        + "These are the main ideas behind it, and you can run the concurrency test live."),
                Ui.grid(3, 16, concepts), Ui.sectionHead("Concurrency test"), race);
    }

    @Override public String title() { return "How it works"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }
    @Override public boolean caresAbout(Event.Topic topic) { return false; }
    @Override public void refresh() { }

    private void show(RaceReport r) {
        int won = r.safeBookings();
        long paid = r.credits().stream().filter(Lane::booked).count();
        raceOut.getChildren().setAll(
                Ui.grid(3, 14, List.of(
                        tile("bad", "alert", "Without synchronization", r.unsafeBookings(), r.threads(),
                                "threads booked the same slot. That is the bug."),
                        tile("good", "check", "With the lock and a transaction", won, r.threads(),
                                (won == 1 ? "booking" : "bookings") + " went through. The rest were rejected."),
                        tile("plain", null, "Stress test", r.stressPassed(), r.stressRounds(),
                                "rounds ended with exactly one winner."))),
                Ui.heading("Same slot, " + r.threads() + " threads", "h3"), tracks(r.sameSlot()),
                Ui.heading("One learner with " + Ui.plural(r.creditsStart(), "credit") + " books " + r.threads()
                        + " slots costing " + r.creditCost() + " each", "h3"), tracks(r.credits()),
                Ui.label("Balance after the race: " + Ui.plural(r.balanceAfter(), "credit") + " (" + paid
                        + " booked). It never goes below zero.", "muted"),
                Ui.label("The moving dots are an illustration. The result shown in each lane is the real outcome from the server.", "subtle", "small"));
        raceOut.setVisible(true);
        raceOut.setManaged(true);
    }

    private static Node tile(String kind, String icon, String label, int number, int outOf, String text) {
        Label big = Ui.heading(String.valueOf(number), "big");
        Ui.countUp(big, number);
        HBox head = Ui.row(6, Ui.label(label, "semi", "small"));
        if (icon != null) head.getChildren().add(0, Icons.of(icon, 16));
        HBox value = new HBox(2, big, Ui.label("/" + outOf, "of"));
        value.setAlignment(Pos.BASELINE_LEFT);
        VBox box = new VBox(2, head, value, Ui.wrapped(text));
        box.getStyleClass().addAll("race-tile", kind);
        return box;
    }

    /** One lane per thread: who, a track with a dot that runs along it, and how it ended. */
    private static Node tracks(List<Lane> lanes) {
        VBox box = new VBox(6);
        for (int i = 0; i < lanes.size(); i++) {
            Lane l = lanes.get(i);
            VBox who = new VBox(0, Ui.label(l.student(), "semi", "small"), Ui.label(l.thread() + " → " + l.slot(), "subtle", "small"));
            who.setMinWidth(150);
            who.setMaxWidth(150);

            Circle runner = new Circle(6);
            runner.getStyleClass().add("runner");
            if (l.booked()) runner.getStyleClass().add("win");
            Region line = new Region();
            line.getStyleClass().add("lane");
            StackPane track = new StackPane(line, runner);
            StackPane.setAlignment(runner, Pos.CENTER_LEFT);
            HBox.setHgrow(track, Priority.ALWAYS);
            Region end = new Region();
            end.getStyleClass().add("lane-end");

            Label outcome = Ui.label(l.detail(), "outcome");
            if (l.booked()) outcome.getStyleClass().add("win");
            outcome.setMinWidth(250);
            outcome.setMaxWidth(250);
            box.getChildren().add(Ui.row(12, who, track, end, outcome));

            // The winner's dot reaches the end first; the others stop short. How far behind is only for show.
            double share = l.booked() ? 1.0 : 0.86;
            int millis = l.booked() ? 800 : 1000 + (i * 137) % 700;
            TranslateTransition run = new TranslateTransition(Duration.millis(millis), runner);
            run.setFromX(4);
            run.setInterpolator(Interpolator.EASE_OUT);
            // The track's width is only known after layout, so the distance is set when it is.
            track.widthProperty().addListener((property, old, width) -> {
                run.stop();
                run.setToX(Math.max(4, width.doubleValue() * share - 18));
                run.playFromStart();
            });
        }
        return box;
    }
}
