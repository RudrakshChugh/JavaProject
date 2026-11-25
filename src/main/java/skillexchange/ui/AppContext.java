package skillexchange.ui;

import javafx.stage.Stage;
import skillexchange.api.SkillExchangeApi;
import skillexchange.model.Student;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * What every screen needs: the server API, the signed-in student (or nobody) and the window.
 * Passed to each screen's constructor (dependency injection by hand).
 */
final class AppContext {
    private final App app;
    private final SkillExchangeApi api;
    private final Student me;           // null while nobody is signed in
    private MainView main;

    AppContext(App app, SkillExchangeApi api, Student me) {
        this.app = app;
        this.api = api;
        this.me = me;
    }

    App app() { return app; }
    SkillExchangeApi api() { return api; }
    Student me() { return me; }
    boolean signedIn() { return me != null; }
    boolean isMe(Student other) { return me != null && me.equals(other); }
    Stage stage() { return app.stage(); }
    MainView main() { return main; }
    Modal modal() { return main.modal(); }
    void setMain(MainView main) { this.main = main; }

    /** Calls the server in the background; a failure is shown as a toast. */
    <T> void call(Callable<T> work, Consumer<T> then) {
        Ui.async(work, then, this::failed);
    }

    /** The same for work that returns nothing. */
    void run(Ui.Action work, Runnable then) {
        Ui.async(() -> {
            work.run();
            return null;
        }, nothing -> then.run(), this::failed);
    }

    void failed(Throwable error) {
        if (main != null) main.toast(Ui.message(error), true);
    }

    void toast(String text) {
        if (main != null) main.toast(text, false);
    }
}
