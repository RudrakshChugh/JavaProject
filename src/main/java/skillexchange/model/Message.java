package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** One chat message. */
public record Message(long id, long conversationId, Student sender, String text, LocalDateTime sentAt)
        implements Serializable { }
