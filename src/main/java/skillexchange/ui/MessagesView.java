package skillexchange.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import skillexchange.api.Event;
import skillexchange.model.Conversation;
import skillexchange.model.Message;
import skillexchange.model.Student;
import skillexchange.validation.Validator;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages: chat between a learner and a tutor. A message sent in one window appears in the
 * other person's window at once, because the server pushes it (see MainView.onEvent).
 */
final class MessagesView implements View {
    private static final double HEIGHT = 560;

    private final AppContext ctx;
    private final VBox people = new VBox(8);
    private final VBox bubbles = new VBox(8);
    private final ScrollPane bubbleScroll = new ScrollPane(bubbles);
    private final HBox chatHead = Ui.row(12);
    private final TextField input = new TextField();
    private final StackPane right = new StackPane();
    private final VBox chat;
    private final VBox root = new VBox(14);
    private List<Conversation> conversations = new ArrayList<>();
    private final List<Message> messages = new ArrayList<>();
    private Conversation open;
    private long wantedId = -1;

    MessagesView(AppContext ctx) {
        this.ctx = ctx;

        VBox left = Ui.card(Ui.label("CONVERSATIONS", "sub-head"), people);
        left.setMinWidth(330);
        left.setMaxWidth(330);
        left.setPrefHeight(HEIGHT);

        bubbleScroll.setFitToWidth(true);
        bubbleScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(bubbleScroll, Priority.ALWAYS);
        bubbles.setPadding(new Insets(14, 6, 14, 0));
        chatHead.getStyleClass().add("chat-head");

        input.setPromptText("Type a message and press Enter");
        HBox.setHgrow(input, Priority.ALWAYS);
        Button send = Ui.iconBtn("send", "Send", "primary");
        send.setOnAction(e -> send());
        // A key event handler: Enter sends the message.
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) send();
        });
        // The Send button is disabled while the box is empty: a binding to the text property.
        send.disableProperty().bind(input.textProperty().isEmpty());
        HBox foot = Ui.row(10, input, send);
        foot.getStyleClass().add("chat-foot");

        chat = Ui.card(chatHead, bubbleScroll, foot);
        chat.setSpacing(0);
        chat.setPrefHeight(HEIGHT);
        right.getChildren().add(placeholder());
        HBox.setHgrow(right, Priority.ALWAYS);

        root.getChildren().addAll(
                Ui.pageHead("Messages", "Talk to your tutor or learner about what you will cover, how, and where you will meet."),
                new HBox(16, left, right));
    }

    private Node placeholder() {
        VBox box = Ui.empty("chat", "Choose a conversation", "Press the message button on a session in Browse to start one.");
        box.setPrefHeight(HEIGHT);
        return box;
    }

    @Override public String title() { return "Messages"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }
    @Override public boolean caresAbout(Event.Topic topic) { return false; }    // chat has its own event

    @Override
    public void refresh() {
        ctx.call(() -> ctx.api().conversations(), loaded -> {
            conversations = loaded;
            long id = wantedId >= 0 ? wantedId : (open != null ? open.id() : -1);
            wantedId = -1;
            // Optional: the wanted conversation if it is in the list, otherwise the first one, otherwise nothing.
            loaded.stream().filter(c -> c.id() == id).findFirst()
                    .or(() -> open == null ? loaded.stream().findFirst() : java.util.Optional.empty())
                    .ifPresent(c -> {
                        if (open == null || open.id() != c.id()) open(c);
                    });
            paintPeople();
        });
    }

    /** Asks for this conversation to be shown; used when coming from another page. */
    void select(Conversation conversation) {
        wantedId = conversation.id();
        open = null;
    }

    private void paintPeople() {
        people.getChildren().clear();
        if (conversations.isEmpty()) {
            people.getChildren().add(Ui.wrapped("No conversations yet. Message a tutor from a session card in Browse.", "muted"));
            return;
        }
        for (Conversation c : conversations) {
            Student other = c.other(ctx.me());
            Label last = Ui.label(c.lastText() == null ? "No messages yet" : c.lastText(), "muted", "small");
            last.setMaxWidth(210);
            HBox top = Ui.row(6, Ui.label(other.getName(), "semi"), Ui.spacer(),
                    Ui.label(c.lastText() == null ? "" : Ui.shortDay(c.lastAt()), "subtle", "small"));
            VBox text = new VBox(1, top, Ui.label(c.listing().getSkill(), "small"), last);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button person = new Button();
            person.setGraphic(Ui.row(10, Ui.avatar(other, 38), text));
            person.getStyleClass().setAll("person");
            if (open != null && open.id() == c.id()) person.getStyleClass().add("on");
            person.setMaxWidth(Double.MAX_VALUE);
            person.setOnAction(e -> {
                open(c);
                paintPeople();
            });
            people.getChildren().add(person);
        }
    }

    private void open(Conversation conversation) {
        open = conversation;
        Student other = conversation.other(ctx.me());
        VBox names = new VBox(1, Ui.heading(other.getName(), "h3"), Ui.label(conversation.listing().getSkill() + "  ·  "
                + (ctx.isMe(conversation.tutor()) ? "you are the tutor" : "tutor: " + conversation.tutor().getName()), "muted", "small"));
        chatHead.getChildren().setAll(Ui.avatar(other, 40), names);
        right.getChildren().setAll(chat);
        messages.clear();
        bubbles.getChildren().clear();
        ctx.call(() -> ctx.api().messages(conversation.id()), loaded -> {
            if (open == null || open.id() != conversation.id()) return;     // the user moved on meanwhile
            messages.clear();
            messages.addAll(loaded);
            bubbles.getChildren().clear();
            if (loaded.isEmpty()) bubbles.getChildren().add(Ui.wrapped("Say hello. Agree what you will cover, how, and where you will meet.", "muted"));
            loaded.forEach(m -> bubbles.getChildren().add(new Bubble(m)));
            scrollToEnd();
            input.requestFocus();
        });
    }

    /**
     * One chat message on screen. An inner class (not static): every Bubble belongs to a
     * MessagesView and can use its fields, here ctx, to know whether the message is mine.
     */
    private final class Bubble extends HBox {
        Bubble(Message m) {
            boolean mine = ctx.isMe(m.sender());
            Label text = Ui.wrapped(m.text());
            text.setMaxWidth(440);
            VBox bubble = new VBox(3, text, Ui.label(Ui.shortDay(m.sentAt()) + " " + m.sentAt().format(Ui.TIME), "time"));
            bubble.getStyleClass().add("bubble");
            if (mine) bubble.getStyleClass().add("mine");
            // My messages sit on the right, the other person's on the left.
            if (mine) getChildren().addAll(Ui.spacer(), bubble);
            else getChildren().addAll(bubble, Ui.spacer());
            setAlignment(Pos.TOP_LEFT);
        }
    }

    private void send() {
        if (open == null) return;
        String text = input.getText();
        if (Validator.errorOf(() -> Validator.message(text)).isPresent()) return;
        input.clear();
        // Nothing is added to the list here: the server pushes the stored message to both people,
        // the sender included, and onMessage() below shows it. Both windows show exactly what was saved.
        long conversationId = open.id();
        ctx.call(() -> ctx.api().sendMessage(conversationId, text), sent -> { });
    }

    /** Called by the main window, on the JavaFX thread, when the server pushes a chat message. */
    void onMessage(Message message) {
        if (open != null && open.id() == message.conversationId()
                && messages.stream().noneMatch(m -> m.id() == message.id())) {
            if (messages.isEmpty()) bubbles.getChildren().clear();      // remove the "say hello" hint
            messages.add(message);
            bubbles.getChildren().add(new Bubble(message));
            scrollToEnd();
        }
        refresh();      // moves the conversation to the top of the list with its new last line
    }

    /** Scrolls after the new bubble has been laid out, which happens on the next pulse. */
    private void scrollToEnd() {
        Platform.runLater(() -> bubbleScroll.setVvalue(1));
    }
}
