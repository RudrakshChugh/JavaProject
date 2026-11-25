package skillexchange.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import skillexchange.api.Event;
import skillexchange.model.CreditSummary;
import skillexchange.model.CreditTransaction;

import java.util.List;

/** Credits: the balance, what is held in escrow, and every movement behind them. */
final class CreditsView implements View {
    private final AppContext ctx;
    private final VBox tiles = new VBox();
    private final VBox ledger = new VBox();
    private final Label entries = Ui.label("", "subtle");
    private final VBox root = new VBox(14);

    CreditsView(AppContext ctx) {
        this.ctx = ctx;
        ledger.getStyleClass().addAll("card", "ledger");
        ledger.setSpacing(0);

        VBox flow = Ui.card(Ui.grid(3, 16, List.of(
                step(1, "tone-1", "Book", "The learner's credits are held. They leave the balance but are not paid yet."),
                step(2, "tone-3", "Confirm", "When the learner confirms the session took place, the held credits go to the tutor."),
                step(3, "tone-4", "Or cancel", "If the booking is cancelled instead, the held credits go back to the learner."))));

        root.getChildren().addAll(
                Ui.pageHead("Credits", "Earn credits by teaching a swap session. Spend them to learn something new."),
                tiles, Ui.sectionHead("How a swap is paid"), flow, Ui.sectionHead("History", entries), ledger);
    }

    private static Node step(int number, String tone, String title, String text) {
        Label no = Ui.label(String.valueOf(number), "step-no", "tone-ink");
        StackPane circle = new StackPane(no);
        circle.getStyleClass().addAll("step-no", tone);
        return new VBox(6, circle, Ui.label(title, "semi"), Ui.wrapped(text, "muted"));
    }

    @Override public String title() { return "Credits"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }
    @Override public boolean caresAbout(Event.Topic topic) { return topic == Event.Topic.CREDITS; }

    @Override
    public void refresh() {
        ctx.call(() -> ctx.api().credits(), this::show);
    }

    private void show(CreditSummary summary) {
        // Streams with a filter and a sum: total credits earned by teaching.
        int earned = summary.history().stream()
                .filter(t -> t.type() == CreditTransaction.Type.RELEASE)
                .mapToInt(CreditTransaction::amount)
                .sum();
        tiles.getChildren().setAll(Ui.grid(3, 16, List.of(
                money("balance", "wallet", "Available balance", summary.balance(),
                        (summary.balance() == 1 ? "credit" : "credits") + " ready to spend"),
                money("held", "lock", "Held in escrow", summary.held(), "for sessions you booked and have not confirmed yet"),
                money("earned", "up", "Earned by teaching", earned, "released to you by your learners"))));

        entries.setText(summary.history().size() + (summary.history().size() == 1 ? " entry" : " entries"));
        ledger.getChildren().clear();
        for (CreditTransaction t : summary.history()) {
            ledger.getChildren().add(row(t, !ledger.getChildren().isEmpty()));
        }
    }

    private static Node money(String kind, String icon, String label, int number, String hint) {
        Label big = Ui.heading(String.valueOf(number), "big");
        Ui.countUp(big, number);
        VBox box = new VBox(2, Ui.row(8, Icons.of(icon, 16), Ui.label(label, "tone-ink", "small")), big, Ui.wrapped(hint, "small"));
        box.getStyleClass().addAll("money", kind, "lift");
        return box;
    }

    private static Node row(CreditTransaction t, boolean separator) {
        String skill = t.skill() == null ? "a session" : t.skill();
        String booking = t.bookingId() == null ? "" : "Booking #B" + t.bookingId() + (t.other() == null ? "" : "  ·  with " + t.other());
        // A switch expression that yields an array: title, detail and icon for each kind of entry.
        String[] text = switch (t.type()) {
            case INITIAL -> new String[] {"Starting balance", "Credits every new profile begins with", "wallet"};
            case HOLD -> new String[] {"Held for " + skill, booking, "lock"};
            case RELEASE -> new String[] {"Earned teaching " + skill, booking, "up"};
            case REFUND -> new String[] {"Refunded for " + skill, booking, "undo"};
        };
        StackPane icon = new StackPane(Icons.of(text[2], 16));
        icon.getStyleClass().addAll("tx-icon", t.type().name().toLowerCase());
        VBox words = new VBox(1, Ui.label(text[0], "semi"), Ui.label(text[1], "muted", "small"));
        HBox.setHgrow(words, Priority.ALWAYS);
        Label amount = Ui.label((t.amount() > 0 ? "+" : t.amount() < 0 ? "−" : "") + Math.abs(t.amount()), "amt");
        if (t.amount() > 0) amount.getStyleClass().add("pos");
        HBox row = new HBox(icon, words, Ui.label(t.at().format(Ui.STAMP), "subtle", "small"), amount);
        row.getStyleClass().add("tx");
        if (separator) row.getStyleClass().add("sep");
        return row;
    }
}
