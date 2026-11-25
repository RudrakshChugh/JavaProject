package skillexchange.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import skillexchange.api.Event;
import skillexchange.model.Conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The window: a bar across the top with the navigation, the chosen page below it in a scrolling
 * area, and three layers on top of everything for toasts and dialogs.
 */
final class MainView extends StackPane {
    private static final double PAGE_WIDTH = 1112;
    /** Pages a visitor can open without signing in. */
    private static final Set<String> OPEN_PAGES = Set.of("browse", "how");

    private final AppContext ctx;
    // LinkedHashMap keeps the pages in the order they were added, which is the order in the bar.
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, ToggleButton> tabs = new LinkedHashMap<>();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final VBox pageHolder = new VBox();
    private final ScrollPane scroll = new ScrollPane();
    private final Modal modal = new Modal();
    private final VBox toasts = new VBox(8);
    private final Label creditCount = Ui.label("", "credit-count");
    private final Label unreadBadge = Ui.badge("", "swap");
    private final MessagesView messages;
    private String active;
    private int unread;

    MainView(AppContext ctx, String firstTab) {
        this.ctx = ctx;
        ctx.setMain(this);

        HBox nav = new HBox(2);
        add(nav, "browse", "Browse", new BrowseView(ctx));
        if (ctx.signedIn()) {
            messages = new MessagesView(ctx);
            add(nav, "bookings", "My bookings", new BookingsView(ctx));
            add(nav, "teach", "Teach", new TeachView(ctx));
            add(nav, "messages", "Messages", messages);
            add(nav, "learn", "Learn", new LearnView(ctx));
            add(nav, "credits", "Credits", new CreditsView(ctx));
        } else {
            messages = null;
            // A visitor sees the same bar. These pages ask them to sign in first.
            for (String[] page : new String[][] {{"bookings", "My bookings"}, {"teach", "Teach"},
                    {"messages", "Messages"}, {"learn", "Learn"}, {"credits", "Credits"}}) {
                add(nav, page[0], page[1], null);
            }
        }
        add(nav, "how", "How it works", new HowView(ctx));
        unreadBadge.setVisible(false);
        unreadBadge.setManaged(false);
        tabs.get("messages").setGraphic(unreadBadge);
        tabs.get("messages").setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);

        BorderPane frame = new BorderPane();
        frame.setTop(topBar(nav));
        frame.setCenter(page());

        toasts.setAlignment(Pos.BOTTOM_RIGHT);
        toasts.setPadding(new Insets(0, 24, 24, 0));
        toasts.setPickOnBounds(false);          // empty parts of this layer let clicks through to the page
        toasts.setMouseTransparent(true);
        getChildren().addAll(frame, modal, toasts);

        // When another tab is chosen, show its page. A deselected group gets its button back.
        navGroup.selectedToggleProperty().addListener((property, old, selected) -> {
            if (selected == null) {
                old.setSelected(true);
                return;
            }
            String key = (String) selected.getUserData();
            if (views.get(key) == null) {           // a visitor chose a page that needs an account
                tabs.get(active).setSelected(true);
                SignIn.open(ctx, key);
            } else {
                show(key);
            }
        });

        // An event filter sees key presses before any control does: Escape closes a dialog,
        // Ctrl+K (Cmd+K on a Mac) opens the search.
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && modal.isOpen()) {
                modal.close();
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.K) {
                openPalette();
                e.consume();
            }
        });

        ctx.app().setEventSink(this::onEvent);
        String start = views.get(firstTab) != null ? firstTab : "browse";
        active = start;
        tabs.get(start).setSelected(true);
        refreshCredits();
    }

    Modal modal() { return modal; }

    private void add(HBox nav, String key, String text, View view) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(navGroup);
        button.setUserData(key);
        button.getStyleClass().setAll("nav-link");
        nav.getChildren().add(button);
        tabs.put(key, button);
        views.put(key, view);
    }

    // ---------- Layout ----------

    private Node topBar(HBox nav) {
        Label brand = Ui.heading("Skill Exchange", "brand-name");
        brand.setOnMouseClicked(e -> go("browse"));

        // The search button shows its keyboard shortcut: Cmd+K on a Mac, Ctrl+K elsewhere.
        Label key = Ui.label(System.getProperty("os.name", "").startsWith("Mac") ? "\u2318K" : "Ctrl K", "kbd");
        Button search = new Button();
        search.setGraphic(Ui.row(8, Icons.of("search", 15), Ui.label("Search", "subtle", "small"), key));
        search.getStyleClass().setAll("search-btn");
        search.setOnAction(e -> openPalette());

        Button theme = Ui.iconButton(ctx.app().isDark() ? "sun" : "moon", "Switch colour theme");
        theme.setOnAction(e -> {
            ctx.app().toggleTheme();
            theme.setGraphic(Icons.of(ctx.app().isDark() ? "sun" : "moon", 18));
        });

        HBox inner = Ui.row(24, brand, nav, Ui.spacer(), Ui.row(6, search, theme, account()));
        inner.setMaxWidth(PAGE_WIDTH + 48);
        inner.setPadding(new Insets(0, 24, 0, 24));
        StackPane bar = new StackPane(inner);
        bar.getStyleClass().add("topbar");
        return bar;
    }

    /** Signed in: a chip with the name and the credit balance. Otherwise a Sign in button. */
    private Node account() {
        if (!ctx.signedIn()) {
            Button signIn = Ui.btn("Sign in", "primary", "sm");
            signIn.setOnAction(e -> SignIn.open(ctx, active));
            return signIn;
        }
        Button chip = new Button();
        chip.setGraphic(Ui.row(8, Ui.avatar(ctx.me(), 30), Ui.label(ctx.me().getName(), "semi"), creditCount, Icons.of("chevron", 13)));
        chip.getStyleClass().setAll("account-chip");
        chip.setOnAction(e -> openProfile());
        return chip;
    }

    private Node page() {
        pageHolder.setMaxWidth(PAGE_WIDTH);
        pageHolder.setPadding(new Insets(32, 0, 72, 0));
        StackPane centred = new StackPane(pageHolder);
        centred.setPadding(new Insets(0, 24, 0, 24));
        VBox.setVgrow(centred, Priority.ALWAYS);

        HBox footerInner = Ui.row(12, Ui.label("Peer Tutoring and Skill Exchange Booking System"), Ui.spacer(),
                Ui.label("JavaFX · Java sockets · JDBC · No frameworks"));
        footerInner.setMaxWidth(PAGE_WIDTH);
        StackPane footer = new StackPane(footerInner);
        footer.getStyleClass().add("footer");

        VBox content = new VBox(centred, footer);
        scroll.setContent(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // The page is at least as tall as the window, so the footer sits at the bottom of short pages.
        content.minHeightProperty().bind(scroll.heightProperty());
        return scroll;
    }

    // ---------- Navigation ----------

    /** Switches to a page, asking a visitor to sign in first if the page needs an account. */
    void go(String key) {
        if (views.get(key) == null) {
            SignIn.open(ctx, key);
        } else if (key.equals(active)) {
            show(key);
        } else {
            tabs.get(key).setSelected(true);
        }
    }

    private void show(String key) {
        active = key;
        View view = views.get(key);
        pageHolder.getChildren().setAll(view.node());
        scroll.setVvalue(0);
        if (view == messages) {
            unread = 0;
            unreadBadge.setVisible(false);
            unreadBadge.setManaged(false);
        }
        view.refresh();
    }

    /** Switches to the Messages page with this conversation open. */
    void openChat(Conversation conversation) {
        modal.close();
        messages.select(conversation);
        go("messages");
    }

    void dispose() {
        views.values().stream().filter(v -> v != null).forEach(View::dispose);
    }

    // ---------- Account ----------

    private void openProfile() {
        VBox names = new VBox(2, Ui.heading(ctx.me().getName(), "h2"), Ui.label(ctx.me().getEmail(), "muted", "small"));
        Button credits = Ui.btn("See my credits");
        credits.setOnAction(e -> {
            modal.close();
            go("credits");
        });
        Button signOut = Ui.btn("Sign out", "quiet", "warn");
        signOut.setOnAction(e -> ctx.run(() -> ctx.api().logout(), () -> {
            ctx.app().showMain(null, "browse");
            ctx.app().main().toast("Signed out", false);
        }));
        modal.show("Your profile", Ui.row(14, Ui.avatar(ctx.me(), 60), names),
                Ui.note("info", "Signed in on this window. Other windows keep their own sign-in."),
                Modal.actions(signOut, credits));
    }

    private void refreshCredits() {
        if (!ctx.signedIn()) return;
        ctx.call(() -> ctx.api().credits(), summary -> creditCount.setText(Ui.plural(summary.balance(), "credit")));
    }

    // ---------- Search ----------

    /** One thing the search can jump to. A nested record. */
    private record Command(String label, String hint, Runnable action) { }

    /** The search dialog: type to filter pages and skills, press Enter to go to the first result. */
    void openPalette() {
        List<Command> commands = new ArrayList<>();
        tabs.forEach((key, button) -> commands.add(new Command(button.getText(), "Page", () -> go(key))));
        TextField input = new TextField();
        input.setPromptText("Search pages and skills");
        VBox results = new VBox(2);
        Runnable paint = () -> {
            String q = input.getText().strip().toLowerCase();
            results.getChildren().clear();
            commands.stream().filter(c -> c.label().toLowerCase().contains(q)).limit(9).forEach(c -> {
                Button item = new Button(c.label(), Ui.label(c.hint(), "subtle", "small"));
                item.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
                item.setGraphicTextGap(12);
                item.getStyleClass().setAll("palette-item");
                item.setMaxWidth(Double.MAX_VALUE);
                item.setOnAction(e -> {
                    modal.close();
                    c.action().run();
                });
                results.getChildren().add(item);
            });
            if (results.getChildren().isEmpty()) results.getChildren().add(Ui.label("Nothing matches", "muted"));
        };
        input.textProperty().addListener((property, old, text) -> paint.run());
        input.setOnAction(e -> {
            if (!results.getChildren().isEmpty() && results.getChildren().get(0) instanceof Button first) first.fire();
        });
        paint.run();
        modal.show("Search", input, results);
        input.requestFocus();

        // Skills with open sessions are added once the server has answered.
        BrowseView browse = (BrowseView) views.get("browse");
        ctx.call(() -> ctx.api().openSlots("", null), slots -> {
            slots.stream().map(s -> s.getListing().getSkill()).distinct().forEach(skill ->
                    commands.add(new Command(skill, "Skill", () -> {
                        browse.searchFor(skill);
                        go("browse");
                    })));
            paint.run();
        });
    }

    // ---------- Toasts and events ----------

    /** A short message that slides in at the bottom right, stays for a moment and fades out. */
    void toast(String text, boolean error) {
        Label label = Ui.wrapped(text);
        label.setMaxWidth(340);
        HBox box = Ui.row(10, Icons.of(error ? "alert" : "check", 17), label);
        box.getStyleClass().add("toast");
        if (error) box.getStyleClass().add("err");
        box.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        toasts.getChildren().add(box);

        FadeTransition in = new FadeTransition(Duration.millis(180), box);
        in.setFromValue(0);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(400), box);
        out.setFromValue(1);
        out.setToValue(0);
        SequentialTransition sequence = new SequentialTransition(in, new PauseTransition(Duration.seconds(error ? 5 : 3.4)), out);
        sequence.setOnFinished(e -> toasts.getChildren().remove(box));
        sequence.play();
    }

    /** Called on the JavaFX thread whenever the server pushes an event. */
    private void onEvent(Event event) {
        if (event instanceof Event.NewMessage incoming) {
            if (messages == null) return;
            messages.onMessage(incoming.message());
            boolean fromSomeoneElse = !ctx.isMe(incoming.message().sender());
            if (!"messages".equals(active) && fromSomeoneElse) {
                unread++;
                unreadBadge.setText(String.valueOf(unread));
                unreadBadge.setVisible(true);
                unreadBadge.setManaged(true);
                toast("New message from " + incoming.message().sender().getName(), false);
            }
        } else if (event instanceof Event.DataChanged changed) {
            if (changed.topic() == Event.Topic.CREDITS) refreshCredits();
            View view = views.get(active);
            // While a dialog is open the page is left alone, so it does not change under the user's hands.
            if (view != null && view.caresAbout(changed.topic()) && !modal.isOpen()) view.refresh();
        }
    }
}
