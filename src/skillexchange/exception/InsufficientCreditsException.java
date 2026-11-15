package skillexchange.exception;

import skillexchange.model.Student;

/** Thrown when a learner doesn't have enough credits for a swap session. */
public class InsufficientCreditsException extends BookingException {
    private static final long serialVersionUID = 1L;

    private final Student student;
    private final int required;
    private final int available;

    public InsufficientCreditsException(Student student, int required, int available) {
        super(student.getName() + " needs " + required + (required == 1 ? " credit" : " credits")
                + " but has only " + available);
        this.student = student;
        this.required = required;
        this.available = available;
    }

    public Student getStudent() { return student; }
    public int getRequired() { return required; }
    public int getAvailable() { return available; }
}
