package skillexchange.api;

import skillexchange.model.Message;

import java.io.Serializable;

/**
 * Something the server tells a connected client without being asked, so the screen updates by itself.
 * Sealed: these two records are the only kinds of event.
 */
public sealed interface Event extends Serializable {

    /** A chat message arrived in one of the student's conversations. */
    record NewMessage(Message message) implements Event { }

    /** Somebody changed data of this kind; screens that show it should load it again. */
    record DataChanged(Topic topic) implements Event { }

    enum Topic { SESSIONS, CREDITS, MODULES }
}
