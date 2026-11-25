package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A chat between one learner and the tutor of one listing, where they agree what will be taught,
 * how and where.
 */
public record Conversation(long id, Listing listing, Student learner, String lastText, LocalDateTime lastAt)
        implements Serializable {

    public Student tutor() { return listing.getTutor(); }

    public boolean involves(Student s) {
        return learner.equals(s) || tutor().equals(s);
    }

    /** The person {@code me} is talking to. */
    public Student other(Student me) {
        return learner.equals(me) ? tutor() : learner;
    }
}
