package skillexchange.ui;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import skillexchange.exception.ValidationException;
import skillexchange.model.Student;
import skillexchange.service.DemoData;
import skillexchange.validation.Validator;

import java.util.List;
import java.util.Optional;

/** The sign-in dialog: sign in with email and password, or create a profile below. */
final class SignIn {
    private static final List<String> DEMO_NAMES = List.of("Asha", "Ravi", "Meera", "Kabir");

    private SignIn() { }

    /** Opens the dialog. After signing in the application shows the page {@code thenTab}. */
    static void open(AppContext ctx, String thenTab) {
        ctx.modal().show("Sign in", Ui.wrapped("Enter your email and password.", "muted"),
                signInForm(ctx, thenTab), divider("or create a profile"), registerForm(ctx, thenTab));
    }

    private static Node signInForm(AppContext ctx, String thenTab) {
        TextField emailInput = new TextField();
        emailInput.setPromptText("you@college.edu");
        PasswordField passwordInput = new PasswordField();
        FormField email = new FormField("Email", emailInput, text -> Validator.errorOf(() -> Validator.email(text)));
        FormField password = new FormField("Password", passwordInput,
                text -> text.isEmpty() ? Optional.of("Enter your password") : Optional.empty());
        HBox failure = errorBox();

        Button submit = Ui.btn("Sign in", "primary");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setDefaultButton(true);          // pressing Enter in the form presses this button
        // A binding: the button is disabled exactly while either field is invalid.
        submit.disableProperty().bind(email.validProperty().and(password.validProperty()).not());
        // An event handler written as an anonymous class. Everywhere else the same thing is a lambda.
        submit.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                showError(failure, null);
                char[] typed = password.text().toCharArray();
                Ui.async(() -> ctx.api().login(email.text(), typed), student -> signedIn(ctx, student, thenTab), error -> {
                    showError(failure, Ui.message(error));
                    passwordInput.clear();
                    passwordInput.requestFocus();
                });
            }
        });

        // The four demo students, one click each.
        HBox people = new HBox(8);
        for (int i = 0; i < DEMO_NAMES.size(); i++) {
            String name = DEMO_NAMES.get(i);
            Button person = new Button(name, Ui.avatar(name, i + 1, 26));
            person.getStyleClass().setAll("person");
            person.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(person, Priority.ALWAYS);
            person.setOnAction(e -> {
                emailInput.setText(name.toLowerCase() + "@uni.edu");
                passwordInput.setText(DemoData.DEMO_PASSWORD);
            });
            people.getChildren().add(person);
        }
        Node note = Ui.note("info", "Trying the demo? Pick a student below. They all use the password "
                + DemoData.DEMO_PASSWORD + ".");
        return new VBox(14, email, password, failure, submit, note, people);
    }

    private static Node registerForm(AppContext ctx, String thenTab) {
        TextField nameInput = new TextField();
        TextField emailInput = new TextField();
        emailInput.setPromptText("you@college.edu");
        PasswordField passwordInput = new PasswordField();
        PasswordField confirmInput = new PasswordField();

        FormField name = new FormField("Name", nameInput, text -> Validator.errorOf(() -> Validator.name(text)));
        FormField email = new FormField("Email", emailInput, text -> Validator.errorOf(() -> Validator.email(text)));
        FormField password = new FormField("Password", passwordInput,
                text -> Validator.errorOf(() -> Validator.password(text.toCharArray())));
        FormField confirm = new FormField("Confirm password", confirmInput,
                text -> text.equals(passwordInput.getText()) ? Optional.empty() : Optional.of("The two passwords are different"));
        // The confirm field depends on the password field, so it is checked again when the password changes.
        passwordInput.textProperty().addListener((property, old, now) -> {
            if (!confirm.text().isEmpty()) confirm.revalidate();
        });
        HBox failure = errorBox();

        Button submit = Ui.btn("Create profile", "primary");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.disableProperty().bind(name.validProperty().and(email.validProperty())
                .and(password.validProperty()).and(confirm.validProperty()).not());
        confirmInput.setOnAction(e -> submit.fire());       // Enter in the last field submits the form
        submit.setOnAction(e -> {
            showError(failure, null);
            char[] typed = password.text().toCharArray();
            Ui.async(() -> ctx.api().register(name.text(), email.text(), typed),
                    student -> signedIn(ctx, student, thenTab), error -> {
                        // The server checks everything again. If it names a field, the message goes under that field.
                        if (error instanceof ValidationException invalid && "email".equals(invalid.getField())) {
                            email.show(invalid.getMessage());
                        } else if (error instanceof ValidationException invalid && "name".equals(invalid.getField())) {
                            name.show(invalid.getMessage());
                        } else {
                            showError(failure, Ui.message(error));
                        }
                    });
        });
        Label hint = Ui.wrapped("At least 8 characters with a letter and a number. Only a scrambled version is "
                + "stored, never the password itself. New profiles start with 5 credits.", "hint");
        return new VBox(14, two(name, email), two(password, confirm), hint, failure, submit);
    }

    private static void signedIn(AppContext ctx, Student student, String thenTab) {
        ctx.app().showMain(student, thenTab);
        ctx.app().main().toast("Signed in as " + student.getName(), false);
    }

    /** Two fields side by side, each taking half the width. */
    static HBox two(Node left, Node right) {
        HBox box = new HBox(12, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        ((javafx.scene.layout.Region) left).setPrefWidth(100);
        ((javafx.scene.layout.Region) right).setPrefWidth(100);
        return box;
    }

    static HBox divider(String text) {
        return Ui.row(12, Ui.rule(), Ui.label(text.toUpperCase(), "divider"), Ui.rule());
    }

    static HBox errorBox() {
        HBox box = new HBox(Ui.wrapped(""));
        box.getStyleClass().add("form-error");
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    static void showError(HBox box, String message) {
        ((Label) box.getChildren().get(0)).setText(message == null ? "" : message);
        box.setVisible(message != null);
        box.setManaged(message != null);
    }
}
