package skillexchange.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The dialog that opens over the page: a dimmed backdrop with a card in the middle. It is drawn
 * inside the main window (a layer of the root StackPane), so it follows the theme and the window.
 */
final class Modal extends StackPane {
    private final VBox card = new VBox(16);
    private Runnable onClose;

    Modal() {
        Region scrim = new Region();
        scrim.getStyleClass().add("scrim");
        scrim.setOnMouseClicked(e -> close());          // clicking outside the card closes it
        card.getStyleClass().add("modal");
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        getChildren().addAll(scrim, card);
        setVisible(false);
        setManaged(false);
    }

    boolean isOpen() {
        return isVisible();
    }

    /** Shows a dialog with this title and content. Any dialog already open is replaced. */
    void show(String title, Node... body) {
        show(520, title, body);
    }

    void show(double width, String title, Node... body) {
        Label heading = Ui.heading(title, "modal-title");
        heading.setWrapText(true);
        Button close = Ui.iconButton("x", "Close");
        close.setOnAction(e -> close());
        HBox top = new HBox(12, heading, Ui.spacer(), close);
        top.setAlignment(Pos.TOP_LEFT);

        VBox content = new VBox(16, body);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Never taller than the window: a long dialog scrolls inside the card.
        scroll.maxHeightProperty().bind(heightProperty().subtract(150));

        card.setMaxWidth(width);
        card.getChildren().setAll(top, scroll);
        onClose = null;
        setVisible(true);
        setManaged(true);

        FadeTransition fade = new FadeTransition(Duration.millis(140), this);
        fade.setFromValue(0);
        fade.setToValue(1);
        ScaleTransition pop = new ScaleTransition(Duration.millis(160), card);
        pop.setFromX(0.96);
        pop.setFromY(0.96);
        pop.setToX(1);
        pop.setToY(1);
        new ParallelTransition(fade, pop).play();
    }

    /** Something to run when this dialog closes, however it is closed. */
    void onClose(Runnable action) {
        onClose = action;
    }

    void close() {
        if (!isVisible()) return;
        setVisible(false);
        setManaged(false);
        card.getChildren().clear();
        Runnable action = onClose;
        onClose = null;
        if (action != null) action.run();
    }

    /** The row of buttons at the bottom of a dialog, pushed to the right. */
    static HBox actions(Node... buttons) {
        HBox box = new HBox(10, buttons);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }
}
