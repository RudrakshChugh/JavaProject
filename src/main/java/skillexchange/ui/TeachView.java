package skillexchange.ui;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import skillexchange.exception.ModuleFormatException;
import skillexchange.exception.ValidationException;
import skillexchange.io.ModuleFileParser;
import skillexchange.model.Exercise;
import skillexchange.model.LearningModule;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.ModuleDraft;
import skillexchange.model.ScoreRow;
import skillexchange.model.SessionMode;
import skillexchange.model.TimeSlot;
import skillexchange.validation.Validator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Teach: create a listing, then add the times you are free and exercises for your learners. */
final class TeachView implements View {
    private static final String MODULE_TEMPLATE = """
            MODULE: Title of the module
            SUMMARY: One line about what it covers.

            MCQ: Type a question with several choices here
            - A wrong choice
            * The right choice (its line starts with a star)
            - Another wrong choice

            TF: Type a statement that is true or false. => true

            FILL: The answer to this is ____. => answer | another accepted answer
            POINTS: 2
            """;

    /** One explanation per listing type. An EnumMap is a map whose keys are the constants of one enum. */
    private static final Map<ListingType, String> TYPE_HELP = new EnumMap<>(ListingType.class);
    static {
        TYPE_HELP.put(ListingType.SWAP, "Learners pay in credits. The credits are held when they book and reach you once they confirm the session took place.");
        TYPE_HELP.put(ListingType.PAID, "Learners pay you the fee at the session. Nothing is charged in the app.");
        TYPE_HELP.put(ListingType.FREE, "A volunteer session. Learners pay nothing.");
    }

    /** What the page shows, fetched together. A nested record. */
    private record Loaded(List<Listing> listings, List<TimeSlot> slots, List<LearningModule> modules) { }

    private final AppContext ctx;
    private final VBox listingsBox = new VBox(16);
    private final VBox root = new VBox(14);

    TeachView(AppContext ctx) {
        this.ctx = ctx;
        Node form = newListingForm();
        HBox split = new HBox(24, form, listingsBox);
        HBox.setHgrow(listingsBox, Priority.ALWAYS);
        root.getChildren().addAll(
                Ui.pageHead("Teach", "Create a listing for something you can teach, then add the times you are free."),
                split);
    }

    @Override public String title() { return "Teach"; }
    @Override public String subtitle() { return ""; }
    @Override public Node node() { return root; }

    @Override
    public void refresh() {
        ctx.call(() -> new Loaded(ctx.api().myListings(), ctx.api().mySlots(), ctx.api().myModules()), this::paint);
    }

    private void paint(Loaded data) {
        listingsBox.getChildren().clear();
        if (data.listings().isEmpty()) {
            listingsBox.getChildren().add(Ui.empty("plus", "You are not teaching anything yet",
                    "Create your first listing, then add a time slot so students can book it."));
            return;
        }
        listingsBox.getChildren().add(Ui.row(8, Ui.heading("Your listings", "h2"), Ui.label(String.valueOf(data.listings().size()), "subtle", "h2")));
        for (Listing listing : data.listings()) {
            listingsBox.getChildren().add(listingCard(listing,
                    data.slots().stream().filter(s -> s.getListing().equals(listing)).toList(),
                    data.modules().stream().filter(m -> m.listing().equals(listing)).toList()));
        }
    }

    // ---------- The form on the left ----------

    private Node newListingForm() {
        ToggleGroup typeGroup = new ToggleGroup();
        HBox seg = new HBox();
        seg.getStyleClass().add("seg");
        for (ListingType t : new ListingType[] {ListingType.SWAP, ListingType.PAID, ListingType.FREE}) {
            ToggleButton option = new ToggleButton(t.getLabel());
            option.getStyleClass().setAll("chip");
            option.setUserData(t);
            option.setToggleGroup(typeGroup);
            option.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(option, Priority.ALWAYS);
            seg.getChildren().add(option);
        }
        Label help = Ui.wrapped("", "hint");

        FormField skill = new FormField("Skill", prompt("e.g. Python for beginners"),
                text -> Validator.errorOf(() -> Validator.skill(text)));
        FormField description = new FormField("Short description (optional)", prompt("What will you cover?"),
                text -> Validator.errorOf(() -> Validator.description(text)));

        Spinner<Integer> minutes = new Spinner<>(Validator.MIN_DURATION, Validator.MAX_DURATION, 60, 15);
        minutes.setMaxWidth(Double.MAX_VALUE);
        VBox minutesField = new VBox(4, Ui.label("Length (minutes)", "field-label"), minutes);

        FormField fee = new FormField("Fee (Rs.)", new TextField("200"),
                text -> Validator.errorOf(() -> Validator.fee(Validator.amount("fee", "Fee", text))));
        Spinner<Integer> credits = new Spinner<>(1, Validator.MAX_CREDIT_COST, 2);
        credits.setMaxWidth(Double.MAX_VALUE);
        VBox creditsField = new VBox(4, Ui.label("Credits per session", "field-label"), credits);
        VBox priceHolder = new VBox();

        // Choosing a type shows the price field that belongs to it and the matching explanation.
        typeGroup.selectedToggleProperty().addListener((property, old, chosen) -> {
            if (chosen == null) {
                old.setSelected(true);
                return;
            }
            ListingType t = (ListingType) chosen.getUserData();
            help.setText(TYPE_HELP.get(t));
            priceHolder.getChildren().clear();
            if (t == ListingType.PAID) priceHolder.getChildren().add(fee);
            if (t == ListingType.SWAP) priceHolder.getChildren().add(creditsField);
        });
        typeGroup.selectToggle(typeGroup.getToggles().get(0));

        HBox failure = SignIn.errorBox();
        Button create = Ui.btn("Create listing", "primary");
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> {
            ListingType t = (ListingType) typeGroup.getSelectedToggle().getUserData();
            skill.touch();
            description.touch();
            if (t == ListingType.PAID) fee.touch();
            boolean ok = skill.validProperty().get() && description.validProperty().get()
                    && (t != ListingType.PAID || fee.validProperty().get());
            SignIn.showError(failure, null);
            if (!ok) return;
            double rupees = t == ListingType.PAID ? Double.parseDouble(fee.text().strip()) : 0;
            Ui.async(() -> ctx.api().createListing(t, skill.text(), description.text(), minutes.getValue(), rupees, credits.getValue()),
                    created -> {
                        skill.clear();
                        description.clear();
                        ctx.toast("Listing created. Now add a time slot.");
                        refresh();
                    }, error -> SignIn.showError(failure, Ui.message(error)));
        });

        VBox card = Ui.card(Ui.heading("New listing", "h2"), new VBox(6, seg, help), skill, description,
                SignIn.two(minutesField, priceHolder), failure, create);
        card.setSpacing(16);
        card.setMinWidth(380);
        card.setMaxWidth(380);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return card;
    }

    private static TextField prompt(String text) {
        TextField field = new TextField();
        field.setPromptText(text);
        return field;
    }

    // ---------- One listing ----------

    private Node listingCard(Listing listing, List<TimeSlot> slots, List<LearningModule> modules) {
        LocalDateTime now = LocalDateTime.now();
        VBox slotList = new VBox();
        slotList.getStyleClass().add("slots-list");
        if (slots.isEmpty()) slotList.getChildren().add(BrowseView.slotRow(Ui.label("No time slots yet", "subtle"), false));
        for (int i = 0; i < slots.size(); i++) {
            TimeSlot s = slots.get(i);
            // An open slot whose start time has passed can no longer be booked.
            Label status = s.isBooked() ? Ui.badge("●  Booked", "booked")
                    : !s.getStart().isAfter(now) ? Ui.badge("●  Expired", "expired") : Ui.badge("●  Open", "open");
            HBox row = BrowseView.slotRow(Ui.label(Ui.shortDay(s.getStart()) + "  ·  " + Ui.timeRange(s) + "  ·  "
                    + s.getMode().getLabel() + ": " + s.getPlace(), "small"), i > 0, status);
            if (!s.isBooked()) {
                Button remove = Ui.iconButton("x", "Remove this slot", "remove");
                remove.setOnAction(e -> ctx.run(() -> ctx.api().removeSlot(s.getId()), () -> {
                    ctx.toast("Slot removed");
                    refresh();
                }));
                row.getChildren().add(remove);
            }
            slotList.getChildren().add(row);
        }

        VBox moduleList = new VBox();
        moduleList.getStyleClass().add("slots-list");
        if (modules.isEmpty()) moduleList.getChildren().add(BrowseView.slotRow(Ui.label("No exercise modules yet", "subtle"), false));
        for (int i = 0; i < modules.size(); i++) {
            LearningModule m = modules.get(i);
            Button view = Ui.btn("Exercises", "quiet", "sm");
            view.setOnAction(e -> showExercises(m));
            Button scores = Ui.btn("Scores", "quiet", "sm");
            scores.setOnAction(e -> showScores(m));
            Button remove = Ui.iconButton("x", "Delete this module", "remove");
            remove.setOnAction(e -> deleteModule(m));
            moduleList.getChildren().add(BrowseView.slotRow(new VBox(0, Ui.label(m.title(), "semi", "small"),
                    Ui.label(Ui.plural(m.exercises().size(), "exercise") + "  ·  " + Ui.plural(m.totalPoints(), "point"), "subtle", "small")),
                    i > 0, view, scores, remove));
        }
        Button write = Ui.iconBtn("plus", "Write a module", "sm");
        write.setOnAction(e -> writeModule(listing));
        Button importFile = Ui.iconBtn("download", "Import from file", "sm");
        importFile.setOnAction(e -> importModule(listing));

        Button delete = Ui.btn("Delete listing", "quiet", "warn", "sm");
        delete.setOnAction(e -> deleteListing(listing, slots.size()));

        VBox card = Ui.card(
                Ui.row(8, Ui.typeBadge(listing.getType()), Ui.spacer(), Ui.label(Ui.price(listing), "price")),
                Ui.heading(listing.getSkill(), "h3"),
                Ui.wrapped(listing.getDurationMinutes() + " min  ·  #L" + listing.getId()
                        + (listing.getDescription().isBlank() ? "" : "  ·  " + listing.getDescription()), "muted", "small"),
                Ui.label("TIME SLOTS", "sub-head"), slotList, addSlotForm(listing),
                Ui.label("EXERCISE MODULES", "sub-head"), moduleList, Ui.row(8, write, importFile),
                Ui.row(0, delete));
        return card;
    }

    private Node addSlotForm(Listing listing) {
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1));
        date.setPrefWidth(132);
        ComboBox<LocalTime> time = new ComboBox<>();
        for (LocalTime t = LocalTime.of(6, 0); t.isBefore(LocalTime.of(23, 0)); t = t.plusMinutes(30)) {
            time.getItems().add(t);
        }
        time.setValue(LocalTime.of(10, 0));
        time.setPrefWidth(96);
        ChoiceBox<SessionMode> mode = new ChoiceBox<>(FXCollections.observableArrayList(SessionMode.values()));
        mode.setValue(SessionMode.IN_PERSON);
        TextField place = prompt(SessionMode.IN_PERSON.getPlaceLabel());
        HBox.setHgrow(place, Priority.ALWAYS);
        Label problem = Ui.wrapped("", "field-error");
        problem.setManaged(false);
        // The prompt follows the mode: a link for online, a room for in person.
        mode.valueProperty().addListener((property, old, chosen) ->
                place.setPromptText(chosen.getPlaceLabel() + (chosen == SessionMode.ONLINE ? ", https://..." : "")));

        Button add = Ui.iconBtn("plus", "Add slot", "sm");
        add.setOnAction(e -> {
            LocalDateTime start = date.getValue() == null ? null : LocalDateTime.of(date.getValue(), time.getValue());
            // The same rules the server applies, checked here first so the answer is immediate.
            Optional<String> invalid = Validator.errorOf(() -> {
                Validator.slotStart(start, LocalDateTime.now());
                Validator.place(mode.getValue(), place.getText());
            });
            showProblem(problem, invalid.orElse(null));
            if (invalid.isPresent()) return;
            Ui.async(() -> ctx.api().addSlot(listing.getId(), start, mode.getValue(), place.getText()), slot -> {
                ctx.toast("Slot added: " + Ui.shortDay(slot.getStart()) + ", " + Ui.timeRange(slot));
                refresh();
            }, error -> showProblem(problem, Ui.message(error)));
        });
        return new VBox(4, Ui.row(8, date, time, mode, place, add), problem);
    }

    private static void showProblem(Label label, String message) {
        label.setText(message == null ? "" : message);
        label.setManaged(message != null);
    }

    private void deleteListing(Listing listing, int slotCount) {
        Button keep = Ui.btn("Keep listing");
        keep.setOnAction(e -> ctx.modal().close());
        Button delete = Ui.btn("Delete listing", "danger");
        delete.setOnAction(e -> {
            ctx.modal().close();
            ctx.run(() -> ctx.api().deleteListing(listing.getId()), () -> {
                ctx.toast("Listing deleted");
                refresh();
            });
        });
        ctx.modal().show("Delete " + listing.getSkill() + "?",
                Ui.wrapped("Listing #L" + listing.getId() + ", " + Ui.price(listing) + ", with " + Ui.plural(slotCount, "time slot") + ".", "muted"),
                Ui.note("alert", "The listing, its open time slots and its exercise modules are removed. Sessions that already "
                        + "took place stay in your bookings and credit history. A listing with a confirmed booking cannot be "
                        + "deleted: cancel the booking first.", "warn"),
                Modal.actions(keep, delete));
    }

    // ---------- Exercise modules ----------

    /** A dialog with a text area. The text is read by the same parser that reads module files. */
    private void writeModule(Listing listing) {
        TextArea editor = new TextArea(MODULE_TEMPLATE);
        editor.getStyleClass().add("code-area");
        editor.setPrefRowCount(15);
        HBox failure = SignIn.errorBox();
        Button cancel = Ui.btn("Cancel");
        cancel.setOnAction(e -> ctx.modal().close());
        Button save = Ui.btn("Save module", "primary");
        save.setOnAction(e -> {
            List<ModuleDraft> drafts = new ArrayList<>();
            // Multi-catch: three different failures, each leaving the dialog open with a message.
            try (BufferedReader reader = new BufferedReader(new StringReader(editor.getText()))) {
                for (ModuleDraft draft : ModuleFileParser.parse(reader)) {
                    Validator.moduleTitle(draft.title());
                    Validator.moduleSummary(draft.summary());
                    Validator.exercises(draft.exercises());
                    drafts.add(draft);
                }
            } catch (ModuleFormatException | ValidationException | IOException invalid) {
                SignIn.showError(failure, invalid.getMessage());
                return;
            }
            ctx.modal().close();
            saveModules(listing, drafts);
        });
        ctx.modal().show(640, "New module for " + listing.getSkill(),
                Ui.wrapped("One question per block. MCQ for multiple choice, TF for true or false, FILL for fill in the "
                        + "blank. The answer comes after =>.", "muted"),
                editor, failure, Modal.actions(cancel, save));
    }

    private void importModule(Listing listing) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import a module file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File file = chooser.showOpenDialog(ctx.stage());
        if (file == null) return;
        // Reading the file happens on a background thread, like every other slow thing.
        ctx.call(() -> ModuleFileParser.parse(file.toPath()), drafts -> saveModules(listing, drafts));
    }

    private void saveModules(Listing listing, List<ModuleDraft> drafts) {
        ctx.run(() -> {
            for (ModuleDraft draft : drafts) {
                ctx.api().createModule(listing.getId(), draft);
            }
        }, () -> {
            ctx.toast(Ui.plural(drafts.size(), "module") + " added to " + listing.getSkill());
            refresh();
        });
    }

    private void deleteModule(LearningModule m) {
        Button keep = Ui.btn("Keep module");
        keep.setOnAction(e -> ctx.modal().close());
        Button delete = Ui.btn("Delete module", "danger");
        delete.setOnAction(e -> {
            ctx.modal().close();
            ctx.run(() -> ctx.api().deleteModule(m.id()), () -> {
                ctx.toast("Module deleted");
                refresh();
            });
        });
        ctx.modal().show("Delete \"" + m.title() + "\"?",
                Ui.note("alert", "Its exercises and every learner's scores on it are deleted too.", "warn"),
                Modal.actions(keep, delete));
    }

    private void showExercises(LearningModule m) {
        VBox list = new VBox(10);
        int number = 1;
        for (Exercise e : m.exercises()) {
            VBox block = new VBox(4, Ui.row(8, Ui.label("QUESTION " + number++, "eyebrow"), Ui.badge(e.kind().getLabel(), "plain"),
                    Ui.spacer(), Ui.label(Ui.plural(e.points(), "point"), "subtle", "small")), Ui.wrapped(e.prompt(), "semi"));
            if (!e.options().isEmpty()) block.getChildren().add(Ui.wrapped("Options: " + String.join("  /  ", e.options()), "muted", "small"));
            block.getChildren().add(Ui.wrapped("Answer: " + e.correctAnswer(), "good-text"));
            block.getStyleClass().add("question");
            list.getChildren().add(block);
        }
        ctx.modal().show(600, m.title(), Ui.wrapped(m.summary().isBlank() ? "Exercises and answers, as only you can see them."
                : m.summary(), "muted"), list);
    }

    private void showScores(LearningModule m) {
        ctx.call(() -> ctx.api().moduleScores(m.id()), rows -> {
            VBox list = new VBox();
            list.getStyleClass().add("slots-list");
            if (rows.isEmpty()) list.getChildren().add(BrowseView.slotRow(Ui.label("Nobody has taken this module yet", "subtle"), false));
            for (int i = 0; i < rows.size(); i++) {
                ScoreRow r = rows.get(i);
                list.getChildren().add(BrowseView.slotRow(Ui.row(10, Ui.avatar(r.student(), 28), new VBox(0, Ui.label(r.student().getName(), "semi", "small"),
                                Ui.label(Ui.plural(r.attempts(), "attempt") + "  ·  last " + Ui.shortDay(r.lastAt()), "subtle", "small"))),
                        i > 0, Ui.badge(r.bestScore() + " / " + r.total(), "brand")));
            }
            ctx.modal().show("Scores for \"" + m.title() + "\"", list);
        });
    }
}
