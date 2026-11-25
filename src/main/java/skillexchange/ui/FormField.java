package skillexchange.ui;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Function;

/**
 * A text field with its label above and its validation message below.
 *
 * It listens to the field's text property. Every time the text changes, the check runs again and
 * the message under the field appears or disappears while the user is still typing.
 */
final class FormField extends VBox {
    private final TextInputControl input;
    private final Function<String, Optional<String>> check;
    private final Label error = new Label();
    private final ReadOnlyBooleanWrapper valid = new ReadOnlyBooleanWrapper(false);
    private boolean touched;

    /** @param check returns the problem with the text, or an empty Optional if it is fine */
    FormField(String caption, TextInputControl input, Function<String, Optional<String>> check) {
        super(4);
        this.input = input;
        this.check = check;
        Label label = new Label(caption);
        label.getStyleClass().add("field-label");
        error.getStyleClass().add("field-error");
        error.setWrapText(true);
        error.setManaged(false);        // takes no room in the layout while there is nothing to say
        error.setVisible(false);
        getChildren().addAll(label, input, error);

        // A ChangeListener written as an anonymous class: changed() is called with the old and the new text.
        input.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> property, String oldText, String newText) {
                touched = true;
                revalidate();
            }
        });
        // Leaving the field also counts as "the user is done here", so an empty required field gets its message.
        input.focusedProperty().addListener((property, wasFocused, isFocused) -> {
            if (wasFocused && !isFocused && touched) revalidate();
        });
        valid.set(check.apply(input.getText()).isEmpty());
    }

    /** Runs the check again. Also called when a field it depends on changes (confirm password). */
    void revalidate() {
        Optional<String> problem = check.apply(input.getText());
        valid.set(problem.isEmpty());
        show(touched ? problem.orElse(null) : null);
    }

    /** Shows a message that did not come from the check, such as "this email is already registered". */
    void show(String message) {
        boolean hasMessage = message != null;
        error.setText(hasMessage ? message : "");
        error.setVisible(hasMessage);
        error.setManaged(hasMessage);
        input.getStyleClass().remove("invalid");
        if (hasMessage) input.getStyleClass().add("invalid");
    }

    /** Makes every message visible, for when the user presses the button without filling the form. */
    void touch() {
        touched = true;
        revalidate();
    }

    String text() { return input.getText() == null ? "" : input.getText(); }

    void clear() {
        input.clear();
        touched = false;
        show(null);
    }

    TextInputControl input() { return input; }

    /** True while the text passes the check. Buttons bind their "disabled" state to this. */
    ReadOnlyBooleanProperty validProperty() { return valid.getReadOnlyProperty(); }
}
