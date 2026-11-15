package skillexchange.model;

import java.io.Serializable;

/**
 * Base class for a skill a tutor offers to teach.
 * Subclasses decide how a session is "paid" for (polymorphism).
 */
public abstract class Listing implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final Student tutor;
    private final String skill;
    private final String description;
    private final int durationMinutes;

    protected Listing(String id, Student tutor, String skill, String description, int durationMinutes) {
        if (tutor == null) throw new IllegalArgumentException("Tutor is required");
        if (durationMinutes <= 0) throw new IllegalArgumentException("Duration must be positive");
        this.id = id;
        this.tutor = tutor;
        this.skill = skill;
        this.description = description;
        this.durationMinutes = durationMinutes;
    }

    public String getId() { return id; }
    public Student getTutor() { return tutor; }
    public String getSkill() { return skill; }
    public String getDescription() { return description; }
    public int getDurationMinutes() { return durationMinutes; }

    /** Short label for the listing type, e.g. "PAID". */
    public abstract String getType();

    /** Credits a learner must spend to book this listing. 0 unless the subclass overrides it. */
    public int getCreditCost() { return 0; }

    /** Human-readable cost of one session, e.g. "Rs. 200.00" or "2 credits". */
    public abstract String getPriceDescription();

    @Override
    public String toString() {
        return String.format("[%s] %-6s %s by %s, %d min, %s",
                id, getType(), skill, tutor.getName(), durationMinutes, getPriceDescription());
    }
}
