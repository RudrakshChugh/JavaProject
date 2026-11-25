package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One line in a student's credit history. A negative amount means credits left the account.
 * A record: an immutable data carrier whose constructor, getters, equals and toString are generated.
 * {@code skill} and {@code other} describe the booking the entry belongs to; both are null for the
 * starting balance.
 */
public record CreditTransaction(long id, long studentId, Type type, int amount, int balanceAfter,
                                Long bookingId, String skill, String other, LocalDateTime at) implements Serializable {

    /** A nested enum: the kinds of ledger entry only make sense together with a transaction. */
    public enum Type {
        INITIAL("Starting balance"),
        HOLD("Held for a booking"),
        RELEASE("Earned by teaching"),
        REFUND("Refunded");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }
    }
}
