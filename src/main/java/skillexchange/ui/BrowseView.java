package skillexchange.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import skillexchange.api.Event;
import skillexchange.model.Activity;
import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.Stats;
import skillexchange.model.TimeSlot;
import skillexchange.model.TutorProfile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Browse, the home page: a banner with the numbers, the skills on offer, and every open session
 * as a card. Visitors can look around; booking asks them to sign in.
 */
final class BrowseView implements View {

    /** Everything the page shows, fetched together in one background task. A nested record. */
    private record Home(Stats stats, List<TimeSlot> slots, List<Activity> activity, List<Booking> mine, int credits) { }

    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final VBox intro = new VBox(12);
    private final GridPane stats = new GridPane();
    private final HBox pulse = Ui.row(10);
    private final VBox rail = new VBox();
    private final VBox results = new VBox();
    private final TextField search = new TextField();
    private final HBox chips = new HBox();
    private final ToggleGroup typeGroup = new ToggleGroup();
    private final Label count = Ui.label("", "subtle");
    private final Label countdown = Ui.label("", "countdown");
    private final Timeline everySecond;
    private final Timeline pulseTimer;

    private List<TimeSlot> slots = List.of();
    private List<Activity> activity = List.of();
    private Booking next;
    private ListingType type;           // null means every type
    private int credits;
    private int pulseIndex;
    private boolean statsShown;

    BrowseView(AppContext ctx) {
        this.ctx = ctx;

        stats.setHgap(12);
        stats.setVgap(12);
        pulse.getStyleClass().add("pill-card");
        pulse.setMinHeight(46);
        VBox side = new VBox(12, stats, pulse);
        HBox heroRow = new HBox(32, intro, side);
        heroRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(intro, Priority.ALWAYS);
        side.setMinWidth(430);
        side.setMaxWidth(430);

        Button surprise = Ui.iconBtn("dice", "Surprise me", "sm");
        surprise.setOnAction(e -> surprise());

        search.setPromptText("Search by skill or tutor");
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox searchBox = Ui.row(0, Icons.of("search", 16), search);
        searchBox.getStyleClass().add("search-box");
        searchBox.setPrefWidth(380);
        searchBox.setMaxWidth(380);
        // Filtering happens here in the client, on the list already loaded, so it is instant.
        search.textProperty().addListener((property, old, text) -> paintSlots());
        chips.getStyleClass().add("chips");
        typeGroup.selectedToggleProperty().addListener((property, old, chosen) -> {
            if (chosen == null) {
                old.setSelected(true);
                return;
            }
            type = (ListingType) chosen.getUserData();
            paintSlots();
        });

        Circle dot = new Circle(4);
        dot.getStyleClass().add("live-dot");
        HBox live = Ui.row(7, dot, Ui.label("Live", "live"));

        root.getChildren().addAll(hero(heroRow),
                Ui.sectionHead("Explore skills", surprise), rail,
                Ui.sectionHead("Open sessions", live, count),
                Ui.row(12, searchBox, chips), results);

        // A Timeline that fires once a second keeps the countdown to the next session ticking.
        everySecond = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        everySecond.setCycleCount(Animation.INDEFINITE);
        everySecond.play();
        pulseTimer = new Timeline(new KeyFrame(Duration.seconds(4), e -> nextPulse()));
        pulseTimer.setCycleCount(Animation.INDEFINITE);
        pulseTimer.play();
    }

    @Override public String title() { return "Browse"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }
    @Override public boolean caresAbout(Event.Topic topic) { return topic != Event.Topic.MODULES; }

    @Override
    public void dispose() {
        everySecond.stop();
        pulseTimer.stop();
    }

    /** Puts a word in the search box; used by the search dialog. */
    void searchFor(String text) {
        search.setText(text);
    }

    @Override
    public void refresh() {
        ctx.call(() -> {
            // Five questions for the server. They are all started first and collected afterwards,
            // so they travel at the same time instead of one after another.
            CompletableFuture<Stats> stats = Ui.start(() -> ctx.api().stats());
            CompletableFuture<List<TimeSlot>> open = Ui.start(() -> ctx.api().openSlots("", null));
            CompletableFuture<List<Activity>> recent = Ui.start(() -> ctx.api().recentActivity());
            CompletableFuture<List<Booking>> mine = ctx.signedIn()
                    ? Ui.start(() -> ctx.api().myBookings()) : CompletableFuture.completedFuture(List.of());
            CompletableFuture<Integer> balance = ctx.signedIn()
                    ? Ui.start(() -> ctx.api().credits().balance()) : CompletableFuture.completedFuture(0);
            return new Home(Ui.await(stats), Ui.await(open), Ui.await(recent), Ui.await(mine), Ui.await(balance));
        }, this::paint);
    }

    private void paint(Home home) {
        slots = home.slots();
        activity = home.activity();
        credits = home.credits();
        LocalDateTime now = LocalDateTime.now();
        // A stream with filter and min: the confirmed booking that starts soonest.
        next = home.mine().stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED && b.getSlot().getStart().isAfter(now))
                .min(Comparator.comparing(b -> b.getSlot().getStart()))
                .orElse(null);
        paintIntro();
        paintStats(home.stats());
        paintPulse();
        paintRail();
        paintChips();
        paintSlots();
    }

    // ---------- The banner ----------

    /** The banner card with two decorations in its corners, clipped to its rounded shape. */
    private Node hero(HBox content) {
        Circle sun = new Circle(95);
        sun.getStyleClass().add("deco-sun");
        sun.setManaged(false);
        Circle ring = new Circle(52);
        ring.getStyleClass().add("deco-ring");
        ring.setManaged(false);

        StackPane hero = new StackPane(sun, ring, content);
        hero.getStyleClass().add("hero");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(48);
        clip.setArcHeight(48);
        // The clip and the decorations follow the banner's size, whatever the window width.
        hero.layoutBoundsProperty().addListener((property, old, bounds) -> {
            clip.setWidth(bounds.getWidth());
            clip.setHeight(bounds.getHeight());
            sun.setCenterX(bounds.getWidth() - 35);
            sun.setCenterY(25);
            ring.setCenterX(bounds.getWidth() * 0.44);
            ring.setCenterY(bounds.getHeight() + 6);
        });
        hero.setClip(clip);
        return hero;
    }

    private void paintIntro() {
        intro.getChildren().clear();
        if (!ctx.signedIn()) {
            Label title = Ui.heading("Teach what you know.\nLearn what you don't.", "hero-title");
            title.setWrapText(true);
            Button signIn = Ui.btn("Sign in to book", "primary");
            signIn.setOnAction(e -> SignIn.open(ctx, "browse"));
            Button how = Ui.btn("How it works");
            how.setOnAction(e -> ctx.main().go("how"));
            intro.getChildren().addAll(Ui.label("PEER TUTORING FOR STUDENTS, BY STUDENTS", "eyebrow"), title,
                    lede("List a skill, publish the times you are free, and book sessions with each other. "
                            + "Pay a fee, spend credits you earned by teaching, or keep it free."),
                    cta(signIn, how));
            return;
        }
        Label name = Ui.heading(ctx.me().getName(), "hero-title");
        name.getStyleClass().add("hero-name");
        VBox underlined = new VBox(-2, name, wave(name));
        HBox title = new HBox(Ui.heading("Welcome back, ", "hero-title"), underlined, Ui.heading(".", "hero-title"));
        title.setAlignment(Pos.TOP_LEFT);

        Button offer = Ui.iconBtn("plus", "Offer a skill", "primary");
        offer.setOnAction(e -> ctx.main().go("teach"));
        Button bookings = Ui.btn("My bookings");
        bookings.setOnAction(e -> ctx.main().go("bookings"));
        intro.getChildren().addAll(Ui.label("SIGNED IN AS " + ctx.me().getName().toUpperCase(), "eyebrow"), title,
                lede("You have " + Ui.plural(credits, "credit") + " to spend on swap sessions. Book a session below, "
                        + "or offer a skill of your own to earn more."),
                cta(offer, bookings));
        if (next != null) intro.getChildren().add(nextUp());
    }

    private static Label lede(String text) {
        Label l = Ui.wrapped(text, "lede");
        l.setMaxWidth(520);
        return l;
    }

    private static HBox cta(Node... buttons) {
        HBox box = new HBox(10, buttons);
        box.setPadding(new Insets(8, 0, 4, 0));
        return box;
    }

    /** The hand-drawn underline under the name: a wavy path as wide as the name itself. */
    private static Path wave(Label name) {
        Path path = new Path();
        path.getStyleClass().add("wave");
        name.widthProperty().addListener((property, old, width) -> {
            path.getElements().setAll(new MoveTo(2, 4));
            double step = 9;
            boolean up = true;
            for (double x = 2; x + step <= width.doubleValue(); x += step) {
                path.getElements().add(new QuadCurveTo(x + step / 2, up ? -2 : 10, x + step, 4));
                up = !up;
            }
            if (path.getElements().size() == 1) path.getElements().add(new LineTo(width.doubleValue(), 4));
        });
        return path;
    }

    private Node nextUp() {
        boolean learning = next.isLearner(ctx.me());
        VBox text = new VBox(1, Ui.label("Your next session · " + (learning ? "learning" : "teaching"), "subtle", "small"),
                Ui.label(next.getSlot().getListing().getSkill() + (learning ? " with " : " for ")
                        + next.otherParty(ctx.me()).getName(), "semi"));
        HBox.setHgrow(text, Priority.ALWAYS);
        countdown.setText(Ui.timeLeft(next.getSlot().getStart()));
        HBox box = Ui.row(12, Icons.of("clock", 19, "brand"), text, countdown);
        box.getStyleClass().addAll("pill-card", "next-up");
        box.setMaxWidth(460);
        box.setOnMouseClicked(e -> ctx.main().go("bookings"));
        return box;
    }

    private void tick() {
        if (next != null) countdown.setText(Ui.timeLeft(next.getSlot().getStart()));
    }

    private void paintStats(Stats s) {
        int[] numbers = {s.students(), s.listings(), s.openSessions(), s.completedSessions()};
        String[] captions = {"Students", "Skills listed", "Open sessions", "Sessions completed"};
        stats.getChildren().clear();
        for (int i = 0; i < numbers.length; i++) {
            Label number = Ui.heading(String.valueOf(numbers[i]), "number");
            if (!statsShown) Ui.countUp(number, numbers[i]);      // count up only the first time
            VBox tile = new VBox(0, number, Ui.label(captions[i], "caption"));
            tile.getStyleClass().addAll("stat", "tone-" + (i + 1), "lift");
            tile.setPrefWidth(209);
            stats.add(tile, i % 2, i / 2);
        }
        statsShown = true;
    }

    private void paintPulse() {
        pulse.getChildren().clear();
        if (activity.isEmpty()) {
            pulse.getChildren().add(Ui.label("No bookings yet. Be the first.", "subtle", "small"));
            return;
        }
        Activity a = activity.get(pulseIndex % activity.size());
        Label line = Ui.label(a.learner() + " booked " + a.skill() + " with " + a.tutor(), "small");
        HBox.setHgrow(line, Priority.ALWAYS);
        line.setMaxWidth(Double.MAX_VALUE);
        pulse.getChildren().addAll(Ui.avatar(a.learner(), a.learner().length(), 24), line, Ui.label(Ui.ago(a.at()), "subtle", "small"));
    }

    private void nextPulse() {
        if (activity.size() < 2) return;
        pulseIndex++;
        paintPulse();
    }

    // ---------- Skills to explore ----------

    private void paintRail() {
        // Group the open sessions by skill, keeping the order in which the skills first appear.
        Map<String, List<TimeSlot>> bySkill = slots.stream()
                .collect(Collectors.groupingBy(s -> s.getListing().getSkill(), LinkedHashMap::new, Collectors.toList()));
        List<Node> tiles = new ArrayList<>();
        int tone = 0;
        for (Map.Entry<String, List<TimeSlot>> entry : bySkill.entrySet()) {
            if (tiles.size() == 5) break;
            TimeSlot first = entry.getValue().get(0);
            Listing listing = first.getListing();
            Label badge = Ui.badge(listing.getType().getLabel(), "plain");
            badge.getStyleClass().add("tone-ink");
            Label name = Ui.heading(entry.getKey(), "name");
            name.getStyleClass().add("tone-ink");
            name.setWrapText(true);
            Label meta = Ui.wrapped(Ui.plural(entry.getValue().size(), "open session") + " · next "
                    + nextWord(first.getStart()), "meta");
            VBox tile = new VBox(8, Ui.row(8, Ui.avatar(listing.getTutor(), 24), Ui.spacer(), badge), name, meta);
            tile.getStyleClass().addAll("skill-tile", "tone-" + (tone++ % 4 + 1));
            if (entry.getKey().equalsIgnoreCase(search.getText().strip())) tile.getStyleClass().add("on");
            // Clicking a skill filters the sessions below to it; clicking it again clears the filter.
            tile.setOnMouseClicked(e -> search.setText(entry.getKey().equalsIgnoreCase(search.getText().strip()) ? "" : entry.getKey()));
            tiles.add(tile);
        }
        rail.getChildren().setAll(tiles.isEmpty()
                ? Ui.empty("book", "No skills on offer yet", "When a student publishes a time slot, their skill shows up here.")
                : Ui.grid(5, 14, tiles));
    }

    private static String nextWord(LocalDateTime time) {
        String word = Ui.dayWord(time);
        return word == null ? "on " + time.format(Ui.DAY) : word.toLowerCase();
    }

    // ---------- Open sessions ----------

    private List<TimeSlot> matching(ListingType wanted) {
        String q = search.getText().strip().toLowerCase();
        return slots.stream()
                .filter(s -> wanted == null || s.getListing().getType() == wanted)
                .filter(s -> q.isEmpty() || s.getListing().getSkill().toLowerCase().contains(q)
                        || s.getTutor().getName().toLowerCase().contains(q)
                        || s.getListing().getDescription().toLowerCase().contains(q))
                .toList();
    }

    private void paintChips() {
        chips.getChildren().clear();
        chips.getChildren().add(chip("All", null));
        for (ListingType t : ListingType.values()) {
            chips.getChildren().add(chip(t.getLabel(), t));
        }
    }

    private ToggleButton chip(String text, ListingType value) {
        ToggleButton chip = new ToggleButton(text + "  " + matching(value).size());
        chip.getStyleClass().setAll("chip");
        chip.setUserData(value);
        chip.setToggleGroup(typeGroup);
        chip.setSelected(value == type);
        return chip;
    }

    private void paintSlots() {
        List<TimeSlot> visible = matching(type);
        count.setText(Ui.plural(visible.size(), "session"));
        // Keep the numbers on the chips in step with the search text.
        for (Node n : chips.getChildren()) {
            ToggleButton chip = (ToggleButton) n;
            ListingType value = (ListingType) chip.getUserData();
            chip.setText((value == null ? "All" : value.getLabel()) + "  " + matching(value).size());
        }
        results.getChildren().clear();
        if (visible.isEmpty()) {
            results.getChildren().add(Ui.empty("search", slots.isEmpty() ? "No open sessions right now" : "Nothing matches",
                    slots.isEmpty() ? "Offer a skill in Teach and publish a time slot." : "Try another word, or choose All."));
            return;
        }
        // One group of cards per day, in date order.
        Map<LocalDate, List<TimeSlot>> byDay = visible.stream()
                .collect(Collectors.groupingBy(s -> s.getStart().toLocalDate(), LinkedHashMap::new, Collectors.toList()));
        byDay.forEach((day, daySlots) -> {
            HBox head = Ui.row(12, Ui.label(Ui.dayLabel(day.atStartOfDay()), "day-head"), Ui.rule());
            head.setPadding(new Insets(18, 0, 12, 0));
            results.getChildren().addAll(head, Ui.grid(3, 16, daySlots.stream().map(this::slotCard).toList()));
        });
    }

    private Node slotCard(TimeSlot s) {
        Listing listing = s.getListing();
        boolean mine = ctx.isMe(listing.getTutor());
        boolean shortOfCredits = ctx.signedIn() && !mine && listing.getCreditCost() > credits;

        Label desc = Ui.wrapped(listing.getDescription().isBlank() ? "No description yet." : listing.getDescription(), "desc");
        desc.setMinHeight(40);
        desc.setAlignment(Pos.TOP_LEFT);
        Button tutor = new Button(listing.getTutor().getName(), Ui.avatar(listing.getTutor(), 24));
        tutor.getStyleClass().setAll("tutor-link");
        tutor.setOnAction(e -> openTutor(listing.getTutor().getId()));

        VBox card = new VBox(8,
                Ui.row(8, Ui.typeBadge(listing.getType()), Ui.spacer(), Ui.label(Ui.price(listing), "price")),
                Ui.heading(listing.getSkill(), "h3"), desc, Ui.rule(), tutor,
                Ui.row(8, Icons.of("clock", 17), Ui.label(Ui.timeRange(s) + "  ·  " + listing.getDurationMinutes() + " min")),
                Ui.row(8, Icons.of("pin", 17), Ui.label(s.getMode().getLabel())),
                Ui.spacer());
        card.getStyleClass().addAll("slot-card", listing.getType().name().toLowerCase());

        if (mine) {
            card.getChildren().add(blocked("Your own session"));
        } else if (shortOfCredits) {
            card.getChildren().addAll(Ui.label("You need " + Ui.plural(listing.getCreditCost(), "credit") + " and have " + credits + ".", "short"),
                    blocked("Not enough credits"));
        } else {
            Button chat = Ui.iconButton("chat", "Message " + listing.getTutor().getName());
            chat.setOnAction(e -> message(listing));
            Button book = Ui.btn("Book session", "primary");
            book.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(book, Priority.ALWAYS);
            book.setOnAction(e -> openBook(s));
            card.getChildren().add(Ui.row(8, chat, book));
        }
        return card;
    }

    private static Button blocked(String text) {
        Button b = Ui.btn(text);
        b.setDisable(true);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    // ---------- Actions ----------

    /** Opens a random session the student could actually book. */
    private void surprise() {
        List<TimeSlot> options = slots.stream()
                .filter(s -> !ctx.isMe(s.getTutor()) && (!ctx.signedIn() || s.getListing().getCreditCost() <= credits))
                .toList();
        if (options.isEmpty()) {
            ctx.toast("There is nothing you can book right now.");
            return;
        }
        openBook(options.get(new Random().nextInt(options.size())));
    }

    private void message(Listing listing) {
        if (!ctx.signedIn()) {
            SignIn.open(ctx, "browse");
            return;
        }
        ctx.call(() -> ctx.api().openConversation(listing.getId()), ctx.main()::openChat);
    }

    /** The booking dialog: a summary, what will happen to the credits, and the confirm button. */
    private void openBook(TimeSlot s) {
        if (!ctx.signedIn()) {
            SignIn.open(ctx, "browse");
            return;
        }
        Listing listing = s.getListing();
        String tutor = listing.getTutor().getName();
        int cost = listing.getCreditCost();
        boolean shortOfCredits = cost > credits;
        // A switch expression: one note per listing type.
        String note = shortOfCredits
                ? "You need " + Ui.plural(cost, "credit") + " for this session and have " + credits + ". Teach a swap session to earn more."
                : switch (listing.getType()) {
                    case SWAP -> Ui.plural(cost, "credit") + " will be held when you book. " + tutor + " receives them only after "
                            + "you confirm the session took place. If the booking is cancelled, they come back to you.";
                    case PAID -> "You pay the fee to " + tutor + " at the session. Nothing is charged here.";
                    case FREE -> "This is a free session. " + tutor + " is volunteering their time.";
                };

        GridPane summary = summary();
        addRow(summary, "Tutor", tutor);
        addRow(summary, "Date", Ui.dayLabel(s.getStart()));
        addRow(summary, "Time", Ui.timeRange(s) + " (" + listing.getDurationMinutes() + " min)");
        addRow(summary, "Where", s.getMode().getLabel() + ". You get the " + s.getMode().getPlaceLabel().toLowerCase() + " once you book.");
        addRow(summary, "Price", Ui.price(listing));
        if (listing.getType() == ListingType.SWAP && !shortOfCredits)
            addRow(summary, "Your balance after booking", Ui.plural(credits - cost, "credit"));

        Button cancel = Ui.btn("Not now");
        cancel.setOnAction(e -> ctx.modal().close());
        Button confirm = Ui.btn("Confirm booking", "primary");
        confirm.setDisable(shortOfCredits);
        confirm.setOnAction(e -> {
            confirm.setDisable(true);
            Ui.async(() -> ctx.api().book(s.getId()), booking -> {
                ctx.modal().close();
                ctx.toast("Booked " + listing.getSkill() + ". " + booking.getSlot().getMode().getPlaceLabel() + ": " + booking.getSlot().getPlace());
                refresh();
            }, error -> {
                ctx.modal().close();
                ctx.failed(error);
                refresh();
            });
        });
        ctx.modal().show("Book " + listing.getSkill(), summary,
                Ui.note(shortOfCredits ? "alert" : "info", note, shortOfCredits ? "warn" : "plain"),
                Modal.actions(cancel, confirm));
    }

    static GridPane summary() {
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(10);
        grid.getStyleClass().add("summary");
        return grid;
    }

    static void addRow(GridPane grid, String label, String value) {
        Label v = Ui.wrapped(value, "value");
        v.setMaxWidth(300);
        GridPane.setHgrow(v, Priority.ALWAYS);
        GridPane.setHalignment(v, javafx.geometry.HPos.RIGHT);
        v.setStyle("-fx-text-alignment: right;");
        grid.addRow(grid.getRowCount(), Ui.label(label, "muted"), v);
    }

    /** Anyone's public profile: what they teach and when they are free. */
    private void openTutor(long studentId) {
        ctx.call(() -> ctx.api().tutorProfile(studentId), this::showTutor);
    }

    private void showTutor(TutorProfile p) {
        VBox names = new VBox(2, Ui.heading(p.student().getName(), "h2"),
                Ui.label(Ui.plural(p.sessionsTaught(), "session") + " taught  ·  " + Ui.plural(p.listings().size(), "skill") + " on offer", "muted", "small"));
        VBox body = new VBox(14);
        for (Listing listing : p.listings()) {
            List<TimeSlot> open = p.openSlots().stream().filter(s -> s.getListing().equals(listing)).toList();
            VBox times = new VBox();
            times.getStyleClass().add("slots-list");
            if (open.isEmpty()) times.getChildren().add(slotRow(Ui.label("No open times right now", "subtle"), false));
            for (int i = 0; i < open.size(); i++) {
                TimeSlot s = open.get(i);
                times.getChildren().add(slotRow(Ui.label(Ui.shortDay(s.getStart()) + "  ·  " + Ui.timeRange(s) + "  ·  " + s.getMode().getLabel()), i > 0));
            }
            VBox block = new VBox(8, Ui.row(8, Ui.typeBadge(listing.getType()), Ui.spacer(), Ui.label(Ui.price(listing), "price")),
                    Ui.heading(listing.getSkill(), "h3"), times);
            if (ctx.signedIn() && !ctx.isMe(p.student())) {
                Button ask = Ui.iconBtn("chat", "Message about " + listing.getSkill(), "sm");
                ask.setOnAction(e -> message(listing));
                block.getChildren().add(ask);
            }
            body.getChildren().addAll(Ui.rule(), block);
        }
        if (p.listings().isEmpty()) body.getChildren().add(Ui.label("Not teaching anything at the moment.", "muted"));
        ctx.modal().show("Tutor", Ui.row(14, Ui.avatar(p.student(), 60), names), body);
    }

    static HBox slotRow(Node content, boolean separator, Node... trailing) {
        HBox row = new HBox(content, Ui.spacer());
        row.getChildren().addAll(trailing);
        row.getStyleClass().add("slots-row");
        if (separator) row.getStyleClass().add("sep");
        return row;
    }
}
