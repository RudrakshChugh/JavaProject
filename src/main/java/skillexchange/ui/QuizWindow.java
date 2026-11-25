package skillexchange.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.IntegerBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import skillexchange.model.AttemptResult;
import skillexchange.model.Exercise;
import skillexchange.model.ModuleView;
import skillexchange.model.Question;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A second window (a secondary Stage) in which a learner takes one module. It is modal: the main
 * window cannot be used until this one is closed.
 */
final class QuizWindow {
    private final AppContext ctx;
    private final ModuleView module;
    private final Runnable onFinished;
    private final Stage stage = new Stage();
    private final BorderPane root = new BorderPane();
    // What the learner has answered so far, by question id. LinkedHashMap keeps the question order.
    private final Map<Long, StringProperty> answers = new LinkedHashMap<>();

    QuizWindow(AppContext ctx, ModuleView module, Runnable onFinished) {
        this.ctx = ctx;
        this.module = module;
        this.onFinished = onFinished;

        stage.initOwner(ctx.stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(module.title());
        root.setPadding(new Insets(26));
        root.setTop(heading());
        root.setCenter(Ui.label("Loading the questions...", "muted"));
        root.getStyleClass().add("app");
        ctx.app().styleRoot(root);          // same theme and heading font as the main window
        Scene scene = new Scene(root, 700, 760);
        scene.getStylesheets().add(App.STYLESHEET);
        stage.setScene(scene);
    }

    void show() {
        stage.show();
        Ui.async(() -> ctx.api().questions(module.id()), this::showQuestions, error -> {
            Ui.alert(stage, "The module could not be opened", Ui.message(error));
            stage.close();
        });
    }

    private Node heading() {
        VBox box = new VBox(6, Ui.row(8, Ui.badge(module.listing().getSkill(), "brand"),
                Ui.label("by " + module.listing().getTutor().getName(), "muted", "small")),
                Ui.heading(module.title(), "h1"));
        box.setPadding(new Insets(0, 0, 16, 0));
        return box;
    }

    private void showQuestions(List<Question> questions) {
        answers.clear();
        VBox list = new VBox(12);
        list.setPadding(new Insets(0, 8, 0, 0));
        int number = 1;
        for (Question q : questions) {
            list.getChildren().add(questionCard(number++, q));
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        root.setCenter(scroll);

        // A binding that counts the answered questions. It is recomputed whenever any answer changes,
        // and the label and the button below follow it without any further code.
        StringProperty[] all = answers.values().toArray(new StringProperty[0]);
        IntegerBinding answered = Bindings.createIntegerBinding(
                () -> (int) answers.values().stream().filter(a -> !a.get().isBlank()).count(), all);
        Label progress = Ui.label("", "muted");
        progress.textProperty().bind(Bindings.concat("Answered ", answered, " of " + questions.size()));

        Button submit = Ui.btn("Submit answers", "primary");
        submit.disableProperty().bind(answered.isEqualTo(0));
        submit.setOnAction(e -> submit());
        Button cancel = Ui.btn("Close");
        cancel.setOnAction(e -> stage.close());
        HBox footer = Ui.row(10, progress, Ui.spacer(), cancel, submit);
        footer.setPadding(new Insets(16, 0, 0, 0));
        root.setBottom(footer);
    }

    private Node questionCard(int number, Question q) {
        StringProperty answer = new SimpleStringProperty("");
        answers.put(q.id(), answer);
        VBox card = new VBox(10, Ui.row(8, Ui.label("QUESTION " + number, "eyebrow"), Ui.spacer(),
                Ui.label(Ui.plural(q.points(), "point"), "subtle", "small")), Ui.wrapped(q.prompt(), "display", "h3"));
        if (q.kind() == Exercise.Kind.FILL) {
            TextField field = new TextField();
            field.setPromptText("Type your answer");
            // A bidirectional binding: the property always holds what is in the field.
            answer.bindBidirectional(field.textProperty());
            card.getChildren().add(field);
        } else {
            // Radio buttons in one ToggleGroup: choosing one clears the others.
            ToggleGroup group = new ToggleGroup();
            for (String option : q.options()) {
                RadioButton radio = new RadioButton(option);
                radio.setToggleGroup(group);
                radio.setUserData(option);
                radio.setMnemonicParsing(false);    // otherwise "_" in an option such as cool_2 is hidden as a shortcut mark
                radio.setWrapText(true);
                card.getChildren().add(radio);
            }
            group.selectedToggleProperty().addListener((property, old, chosen) ->
                    answer.set(chosen == null ? "" : (String) chosen.getUserData()));
        }
        card.getStyleClass().add("question");
        return card;
    }

    private void submit() {
        Map<Long, String> given = new HashMap<>();
        answers.forEach((id, value) -> given.put(id, value.get()));
        Ui.async(() -> ctx.api().submitAnswers(module.id(), given), this::showResult,
                error -> Ui.alert(stage, "Your answers could not be marked", Ui.message(error)));
    }

    private void showResult(AttemptResult result) {
        onFinished.run();
        String verdict = result.percent() >= 80 ? "Excellent work" : result.percent() >= 50 ? "Good, keep practising" : "Have another go";
        VBox top = new VBox(2, Ui.heading(result.score() + " / " + result.total(), "score-big"),
                Ui.heading(result.percent() + "%  ·  " + verdict, "h2"));
        top.setAlignment(Pos.CENTER);
        top.setPadding(new Insets(4, 0, 12, 0));

        VBox list = new VBox(10, top);
        list.setPadding(new Insets(0, 8, 0, 0));
        int number = 1;
        for (AttemptResult.Feedback f : result.feedback()) {
            Label mark = Ui.label(f.right() ? "Correct  +" + f.points() : "Not quite", f.right() ? "good-text" : "bad-text");
            VBox card = new VBox(6, Ui.row(8, Ui.label("QUESTION " + number++, "eyebrow"), Ui.spacer(), mark),
                    Ui.wrapped(f.prompt(), "display", "h3"), Ui.wrapped("Your answer: " + f.given()));
            if (!f.right()) card.getChildren().add(Ui.wrapped("Right answer: " + f.correct(), "good-text"));
            card.getStyleClass().addAll("question", f.right() ? "right" : "wrong");
            list.getChildren().add(card);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        root.setCenter(scroll);

        Button again = Ui.btn("Try again");
        again.setOnAction(e -> show());
        Button close = Ui.btn("Done", "primary");
        close.setOnAction(e -> stage.close());
        HBox footer = Ui.row(10, Ui.spacer(), again, close);
        footer.setPadding(new Insets(16, 0, 0, 0));
        root.setBottom(footer);
    }
}
