package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** One line in a student's credit history. A negative amount means credits left the account. */
public class CreditTransaction implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        INITIAL,  // starting balance when the account was opened
        HOLD,     // learner's credits held when they book a swap session
        RELEASE,  // held credits paid to the tutor once the session is completed
        REFUND    // held credits returned to the learner after a cancellation
    }

    private final Student student;
    private final Type type;
    private final int amount;
    private final String bookingId;   // null for INITIAL
    private final LocalDateTime timestamp;

    public CreditTransaction(Student student, Type type, int amount, String bookingId) {
        this.student = student;
        this.type = type;
        this.amount = amount;
        this.bookingId = bookingId;
        this.timestamp = LocalDateTime.now();
    }

    public Student getStudent() { return student; }
    public Type getType() { return type; }
    public int getAmount() { return amount; }
    public String getBookingId() { return bookingId; }
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("%-7s %+d%s", type, amount, bookingId == null ? "" : " (booking " + bookingId + ")");
    }
}
