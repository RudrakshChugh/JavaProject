package skillexchange.ui;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;
import javafx.util.Duration;
import skillexchange.service.DemoData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Developer tool, started with {@code --shots=folder}: drives the real application like a user
 * would (opens the sign-in dialog, types into the forms, signs in with the demo account, visits
 * every page, takes a quiz, runs the race, switches to the dark theme) and saves a picture after
 * each step, then closes the application.
 * The pictures in docs/screenshots are made this way, so they always show the real application.
 */
final class Screenshots {
    private static final Logger LOG = Logger.getLogger(Screenshots.class.getName());

    /** One thing to do, and how long to let the screen settle afterwards. */
    private record Step(Runnable action, int waitMillis) { }

    private final App app;
    private final Path folder;
    // A Deque used as a queue: steps are added at the end and taken from the front.
    private final Deque<Step> steps = new ArrayDeque<>();

    Screenshots(App app, Path folder) {
        this.app = app;
        this.folder = folder;
    }

    private void then(Runnable action) {
        steps.add(new Step(action, 1000));
    }

    void run() {
        Scene main = app.scene();
        then(() -> { });                            // give the first page time to load its data
        then(() -> snap("01-home-visitor", main));

        // The sign-in dialog, with values in the create-profile form that break the rules.
        then(() -> button(main, "Sign in").ifPresent(Button::fire));
        then(() -> snap("02-sign-in", main));
        then(() -> {
            // Fields of the dialog in order: sign-in email and password, then the four of the new profile.
            List<TextInputControl> fields = visibleInputs(main);
            String[] bad = {"", "", "R2-D2", "asha@uni", "short", "different"};
            for (int i = 2; i < fields.size() && i < bad.length; i++) {
                fields.get(i).setText(bad[i]);
            }
            scrollAll(main, 1);
        });
        then(() -> snap("03-validation", main));

        then(() -> Ui.async(() -> app.connection().api().login(DemoData.DEMO_EMAIL, DemoData.DEMO_PASSWORD.toCharArray()),
                student -> app.showMain(student, "browse"), error -> LOG.log(Level.SEVERE, "Demo sign-in failed", error)));
        then(() -> snap("04-home", main));
        then(() -> scrollAll(main, 0.62));
        then(() -> snap("05-open-sessions", main));
        then(() -> button(main, "Book session").ifPresent(Button::fire));
        then(() -> snap("06-book-dialog", main));
        then(() -> button(main, "Not now").ifPresent(Button::fire));

        String[] names = {"07-my-bookings", "08-teach", "09-messages", "10-learn", "13-credits", "14-how-it-works"};
        for (int i = 0; i < names.length; i++) {
            int index = i + 1;                      // tab 0 is Browse
            then(() -> toggles(main, ".nav-link").get(index).setSelected(true));
            then(() -> snap(names[index - 1], main));
            if (names[i].equals("10-learn")) quizSteps(main);
        }
        then(() -> button(main, "Run the race").ifPresent(Button::fire));
        steps.add(new Step(() -> { }, 6000));
        then(() -> scrollAll(main, 0.55));
        steps.add(new Step(() -> { }, 1800));
        then(() -> snap("15-race-result", main));

        // The same home page in the dark theme, then back to light so the choice is not remembered.
        then(() -> toggles(main, ".nav-link").get(0).setSelected(true));
        then(app::toggleTheme);
        then(() -> snap("16-home-dark", main));
        then(app::toggleTheme);
        then(() -> app.stage().close());            // closing the last window ends the application
        next();
    }

    /** Opens the first module, answers every question, submits, and photographs both stages. */
    private void quizSteps(Scene main) {
        then(() -> button(main, "Start").ifPresent(Button::fire));
        then(() -> quizScene().ifPresent(quiz -> {
            Set<Object> answeredGroups = new HashSet<>();
            for (Node n : quiz.getRoot().lookupAll(".radio-button")) {
                RadioButton radio = (RadioButton) n;
                // Set.add returns false if the group is already there: one choice per question.
                if (answeredGroups.add(radio.getToggleGroup())) radio.setSelected(true);
            }
            quiz.getRoot().lookupAll(".text-field").forEach(f -> ((TextInputControl) f).setText("print"));
        }));
        then(() -> quizScene().ifPresent(quiz -> snap("11-quiz", quiz)));
        then(() -> quizScene().flatMap(quiz -> button(quiz, "Submit answers")).ifPresent(Button::fire));
        then(() -> quizScene().ifPresent(quiz -> snap("12-quiz-result", quiz)));
        then(() -> quizScene().flatMap(quiz -> button(quiz, "Done")).ifPresent(Button::fire));
    }

    /** The scene of the quiz window: the one showing window that is not the main window. */
    private Optional<Scene> quizScene() {
        return Window.getWindows().stream()
                .filter(w -> w != app.stage() && w.isShowing())
                .map(Window::getScene)
                .findFirst();
    }

    private static List<ToggleButton> toggles(Scene scene, String styleClass) {
        return scene.getRoot().lookupAll(styleClass).stream().map(n -> (ToggleButton) n).toList();
    }

    private static Optional<Button> button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".btn").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()) && isShown(n) && !n.isDisabled())
                .map(n -> (Button) n)
                .findFirst();
    }

    private static List<TextInputControl> visibleInputs(Scene scene) {
        return scene.getRoot().lookupAll(".modal .text-input").stream()
                .filter(Screenshots::isShown)
                .map(n -> (TextInputControl) n)
                .toList();
    }

    /** Scrolls every visible scroll area to the same position, from 0 (top) to 1 (bottom). */
    private static void scrollAll(Scene scene, double position) {
        scene.getRoot().lookupAll(".scroll-pane").stream().filter(Screenshots::isShown)
                .forEach(n -> ((ScrollPane) n).setVvalue(position));
    }

    /** A node is on screen only if it and all its parents are visible. */
    private static boolean isShown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    /** Runs one step, waits for the screen to load and settle, then runs the next. */
    private void next() {
        Step step = steps.poll();
        if (step == null) return;
        try {
            step.action().run();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "A walkthrough step failed", e);
        }
        PauseTransition wait = new PauseTransition(Duration.millis(step.waitMillis()));
        wait.setOnFinished(e -> next());
        wait.play();
    }

    private void snap(String name, Scene scene) {
        WritableImage image = scene.snapshot(null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                copy.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            Files.createDirectories(folder);
            ImageIO.write(copy, "png", folder.resolve(name + ".png").toFile());
            LOG.info("Saved " + name + ".png");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save " + name, e);
        }
    }
}
