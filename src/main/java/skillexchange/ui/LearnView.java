package skillexchange.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import skillexchange.api.Event;
import skillexchange.model.ModuleView;

import java.util.Comparator;
import java.util.List;

/** Learn: exercise modules written by tutors, with the student's best score on each. */
final class LearnView implements View {
    private final AppContext ctx;
    private final TextField search = new TextField();
    private final VBox results = new VBox();
    private final Label summary = Ui.label("", "subtle");
    private final VBox root = new VBox(14);
    private List<ModuleView> modules = List.of();

    LearnView(AppContext ctx) {
        this.ctx = ctx;
        search.setPromptText("Search modules by title, skill or tutor");
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox searchBox = Ui.row(0, Icons.of("search", 16), search);
        searchBox.getStyleClass().add("search-box");
        searchBox.setPrefWidth(380);
        searchBox.setMaxWidth(380);
        search.textProperty().addListener((property, old, text) -> paint());

        root.getChildren().addAll(
                Ui.pageHead("Learn", "Practise with exercises written by tutors. The server marks your answers and keeps your best score."),
                Ui.row(12, searchBox, Ui.spacer(), summary), results);
    }

    @Override public String title() { return "Learn"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }
    @Override public boolean caresAbout(Event.Topic topic) { return topic == Event.Topic.MODULES; }

    @Override
    public void refresh() {
        ctx.call(() -> ctx.api().modules(""), loaded -> {
            modules = loaded;
            paint();
        });
    }

    private void paint() {
        String q = search.getText().strip().toLowerCase();
        // A Comparator built from two keys: by skill, then by title, both ignoring case.
        Comparator<ModuleView> order = Comparator
                .comparing((ModuleView m) -> m.listing().getSkill(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ModuleView::title, String.CASE_INSENSITIVE_ORDER);
        List<ModuleView> visible = modules.stream()
                .filter(m -> q.isEmpty() || m.title().toLowerCase().contains(q) || m.listing().getSkill().toLowerCase().contains(q)
                        || m.listing().getTutor().getName().toLowerCase().contains(q))
                .sorted(order)
                .toList();
        long done = modules.stream().filter(ModuleView::attempted).count();
        long open = modules.stream().filter(m -> !m.mine()).count();
        summary.setText(done + " of " + open + " attempted");

        if (visible.isEmpty()) {
            results.getChildren().setAll(Ui.empty("book", modules.isEmpty() ? "No modules yet" : "Nothing matches",
                    modules.isEmpty() ? "Tutors add exercise modules to their listings in Teach." : "Try another word."));
            return;
        }
        results.getChildren().setAll(Ui.grid(3, 16, visible.stream().map(this::moduleCard).toList()));
    }

    private Node moduleCard(ModuleView m) {
        Label summaryText = Ui.wrapped(m.summary().isBlank() ? "No description yet." : m.summary(), "desc");
        summaryText.setMinHeight(40);
        summaryText.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(8,
                Ui.row(8, Ui.badge(m.listing().getSkill(), "brand"), Ui.spacer(), Ui.label(Ui.plural(m.totalPoints(), "point"), "price")),
                Ui.heading(m.title(), "h3"), summaryText, Ui.rule(),
                Ui.row(8, Ui.avatar(m.listing().getTutor(), 24), Ui.label(m.listing().getTutor().getName())),
                Ui.row(8, Icons.of("book", 17), Ui.label(Ui.plural(m.questions(), "question"))),
                Ui.spacer());
        card.getStyleClass().addAll("slot-card", "module");

        if (m.mine()) {
            Button own = Ui.btn("Your own module");
            own.setDisable(true);
            own.setMaxWidth(Double.MAX_VALUE);
            card.getChildren().add(own);
            return card;
        }
        ProgressBar bar = new ProgressBar(m.percent() / 100.0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Label best = Ui.label(m.attempted() ? "Best score " + m.bestScore() + " / " + m.totalPoints() + "  (" + m.percent() + "%)"
                : "Not attempted yet", "muted", "small");
        Button start = Ui.btn(m.attempted() ? "Try again" : "Start", "primary");
        start.setMaxWidth(Double.MAX_VALUE);
        start.setOnAction(e -> new QuizWindow(ctx, m, this::refresh).show());
        card.getChildren().addAll(bar, best, start);
        return card;
    }
}
