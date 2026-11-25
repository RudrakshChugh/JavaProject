package skillexchange.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import skillexchange.io.SessionCsv;
import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;
import skillexchange.model.TimeSlot;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** My bookings: sessions the student is learning in and sessions they are teaching. */
final class BookingsView implements View {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE");

    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final HBox chips = new HBox();
    private final VBox list = new VBox(16);
    private final ToggleGroup statusGroup = new ToggleGroup();
    // Countdown labels and the time each one counts down to, refreshed once a second.
    private final Map<Label, LocalDateTime> countdowns = new HashMap<>();
    private final Timeline everySecond;
    private List<Booking> all = new ArrayList<>();
    private BookingStatus filter;       // null means all

    BookingsView(AppContext ctx) {
        this.ctx = ctx;
        chips.getStyleClass().add("chips");
        statusGroup.selectedToggleProperty().addListener((property, old, chosen) -> {
            if (chosen == null) {
                old.setSelected(true);
                return;
            }
            filter = (BookingStatus) chosen.getUserData();
            paintList();
        });
        Button export = Ui.iconBtn("download", "Export as CSV", "sm");
        export.setOnAction(e -> export());
        root.getChildren().addAll(
                Ui.pageHead("My bookings", "Sessions you are learning in and sessions you are teaching."),
                Ui.row(12, chips, Ui.spacer(), export), list);

        everySecond = new Timeline(new KeyFrame(Duration.seconds(1),
                e -> countdowns.forEach((label, until) -> label.setText(Ui.timeLeft(until)))));
        everySecond.setCycleCount(Animation.INDEFINITE);
        everySecond.play();
    }

    @Override public String title() { return "My bookings"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }
    @Override public void dispose() { everySecond.stop(); }

    @Override
    public void refresh() {
        ctx.call(() -> ctx.api().myBookings(), bookings -> {
            all = bookings;
            paintChips();
            paintList();
        });
    }

    private void paintChips() {
        chips.getChildren().setAll(chip("All", null), chip("Confirmed", BookingStatus.CONFIRMED),
                chip("Completed", BookingStatus.COMPLETED), chip("Cancelled", BookingStatus.CANCELLED));
    }

    private ToggleButton chip(String text, BookingStatus status) {
        long n = all.stream().filter(b -> status == null || b.getStatus() == status).count();
        ToggleButton chip = new ToggleButton(text + "  " + n);
        chip.getStyleClass().setAll("chip");
        chip.setUserData(status);
        chip.setToggleGroup(statusGroup);
        chip.setSelected(status == filter);
        return chip;
    }

    private void paintList() {
        countdowns.clear();
        list.getChildren().clear();
        if (all.isEmpty()) {
            Button browse = Ui.btn("Browse open sessions", "primary");
            browse.setOnAction(e -> ctx.main().go("browse"));
            list.getChildren().add(Ui.empty("calendar", "No bookings yet",
                    "When you book a session, or someone books one of yours, it shows up here.", browse));
            return;
        }
        List<Booking> visible = all.stream().filter(b -> filter == null || b.getStatus() == filter).toList();
        if (visible.isEmpty()) {
            list.getChildren().add(Ui.empty("calendar", "No " + filter.getLabel().toLowerCase() + " bookings", ""));
            return;
        }
        visible.forEach(b -> list.getChildren().add(bookingCard(b)));
    }

    private Node bookingCard(Booking b) {
        TimeSlot slot = b.getSlot();
        boolean learning = b.isLearner(ctx.me());
        boolean live = b.getStatus() == BookingStatus.CONFIRMED;
        boolean upcoming = live && slot.getStart().isAfter(LocalDateTime.now());

        VBox date = new VBox(0, Ui.label(slot.getStart().format(MONTH).toUpperCase(), "mon"),
                Ui.label(String.valueOf(slot.getStart().getDayOfMonth()), "day"),
                Ui.label(slot.getStart().format(WEEKDAY), "dow"));
        date.getStyleClass().add("date-tile");
        date.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        HBox badges = Ui.row(8, Ui.badge(learning ? "Learning" : "Teaching", learning ? "learning" : "teaching"),
                Ui.typeBadge(slot.getListing().getType()));
        if (upcoming) {
            Label countdown = Ui.label(Ui.timeLeft(slot.getStart()), "countdown");
            countdowns.put(countdown, slot.getStart());
            badges.getChildren().add(countdown);
        }
        HBox title = new HBox(6, Ui.heading(slot.getListing().getSkill(), "h3"),
                Ui.label((learning ? "with " : "for ") + b.otherParty(ctx.me()).getName(), "muted", "h3"));
        title.getStyleClass().add("booking-title");
        VBox middle = new VBox(6, badges, title,
                Ui.label(Ui.shortDay(slot.getStart()) + "  ·  " + Ui.timeRange(slot) + "  ·  " + Ui.price(slot.getListing())
                        + "  ·  #B" + b.getId(), "muted", "small"),
                Ui.row(6, Icons.of("pin", 15), Ui.label(slot.getMode().getLabel() + ": " + slot.getPlace(), "muted", "small")));
        String escrow = escrowNote(b, learning);
        if (escrow != null) {
            String icon = switch (b.getStatus()) {
                case CONFIRMED -> "lock";
                case COMPLETED -> "check";
                case CANCELLED -> "undo";
            };
            middle.getChildren().add(Ui.row(6, Icons.of(icon, 15), Ui.label(escrow, "muted", "small")));
        }
        HBox.setHgrow(middle, Priority.ALWAYS);

        VBox side = new VBox(10, Ui.badge("●  " + statusLabel(b.getStatus()), b.getStatus().name().toLowerCase()));
        side.setAlignment(Pos.TOP_RIGHT);
        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button chat = Ui.iconBtn("chat", "Message", "quiet", "sm");
        chat.setOnAction(e -> ctx.call(() -> ctx.api().openBookingConversation(b.getId()), ctx.main()::openChat));
        actions.getChildren().add(chat);
        if (live) {
            if (learning && !upcoming) {
                Button complete = Ui.btn("Confirm it took place", "primary", "sm");
                complete.setOnAction(e -> confirm(b, true));
                actions.getChildren().add(complete);
            }
            Button cancel = Ui.btn("Cancel", "quiet", "warn", "sm");
            cancel.setOnAction(e -> confirm(b, false));
            actions.getChildren().add(cancel);
        }
        side.getChildren().add(actions);
        if (learning && upcoming) side.getChildren().add(Ui.label("You can confirm it once it starts.", "hint"));

        HBox card = new HBox(18, date, middle, side);
        card.getStyleClass().add("card");
        if (!live) card.getStyleClass().add("off");
        return card;
    }

    private static String statusLabel(BookingStatus status) {
        return status == BookingStatus.CONFIRMED ? "Confirmed" : status.getLabel();
    }

    /** What is happening to the credits of a swap booking, from the viewer's side. Null for paid and free sessions. */
    private String escrowNote(Booking b, boolean learning) {
        int cost = b.getHeldCredits();
        if (cost == 0) return null;
        String credits = Ui.plural(cost, "credit");
        String is = cost == 1 ? " is" : " are";
        String was = cost == 1 ? " was" : " were";
        String learner = b.getLearner().getName();
        return switch (b.getStatus()) {
            case CONFIRMED -> learning ? credits + is + " held until you confirm the session took place."
                    : credits + is + " held for you until " + learner + " confirms the session took place.";
            case COMPLETED -> learning ? credits + was + " paid to " + b.getTutor().getName() + "." : "You earned " + credits + ".";
            case CANCELLED -> learning ? credits + was + " refunded to you." : credits + was + " refunded to " + learner + ".";
        };
    }

    /** The "are you sure?" dialog for confirming or cancelling a booking. */
    private void confirm(Booking b, boolean complete) {
        boolean learning = b.isLearner(ctx.me());
        int cost = b.getHeldCredits();
        String credits = Ui.plural(cost, "credit");
        String session = b.getSlot().getListing().getSkill() + " on " + Ui.shortDay(b.getSlot().getStart()) + ", "
                + Ui.timeRange(b.getSlot()) + (learning ? ", with " : ", for ") + b.otherParty(ctx.me()).getName() + ".";
        String consequence = complete
                ? (cost > 0 ? "The " + credits + " held for this booking will be paid to " + b.getTutor().getName() + "."
                        : "The booking will be marked as completed.") + " This cannot be undone."
                : "The time slot opens up again for other students."
                        + (cost > 0 ? " The " + credits + " held for it will be refunded to " + (learning ? "you" : b.getLearner().getName()) + "." : "");

        Button back = Ui.btn(complete ? "Not yet" : "Keep booking");
        back.setOnAction(e -> ctx.modal().close());
        Button go = Ui.btn(complete ? "Yes, it took place" : "Cancel booking", complete ? "primary" : "danger");
        go.setOnAction(e -> {
            ctx.modal().close();
            ctx.run(() -> {
                if (complete) ctx.api().complete(b.getId());
                else ctx.api().cancel(b.getId());
            }, () -> {
                ctx.toast(complete ? "Session confirmed. Thank you!" : "Booking cancelled");
                refresh();
            });
        });
        ctx.modal().show(complete ? "Confirm the session took place?" : "Cancel this booking?",
                Ui.wrapped(session, "muted"),
                Ui.note(complete ? "info" : "alert", consequence, complete ? "plain" : "warn"),
                Modal.actions(back, go));
    }

    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export my sessions");
        chooser.setInitialFileName("my-sessions.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file", "*.csv"));
        File file = chooser.showSaveDialog(ctx.stage());
        if (file == null) return;                   // the user pressed Cancel
        List<Booking> rows = List.copyOf(all);
        ctx.run(() -> SessionCsv.write(file.toPath(), ctx.me(), rows),
                () -> ctx.toast("Saved " + Ui.plural(rows.size(), "session") + " to " + file.getName()));
    }
}
