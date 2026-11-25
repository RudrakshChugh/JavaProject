package skillexchange.ui;

import javafx.scene.Node;
import skillexchange.api.Event;

/**
 * One screen of the main window. The window treats every screen the same way through this
 * interface, whatever class it really is.
 */
interface View {
    String title();

    String subtitle();

    /** The JavaFX node to place in the window. */
    Node node();

    /** Loads the screen's data again from the server. */
    void refresh();

    /** Whether a change of this kind on the server should make the screen reload. */
    default boolean caresAbout(Event.Topic topic) {
        return true;
    }

    /** Stops timers and animations. Called when the screen is thrown away (signing in or out). */
    default void dispose() {
    }
}
