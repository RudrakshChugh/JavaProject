package skillexchange.model;

import java.io.Serializable;

/**
 * Base class for a skill a tutor offers to teach.
 * Subclasses decide how a session is "paid" for (polymorphism).
 */
public abstract class Listing implements Serializable {
    private static final long serialVersionUID = 2L;

    private final long id;
    private final Student tutor;
    private final String skill;
    private final String description;
    private final int durationMinutes;

    protected Listing(long id, Student tutor, String skill, String description, int durationMinutes) {
        if (tutor == null) throw new IllegalArgumentException("Tutor is required");
        if (skill == null || skill.isBlank()) throw new IllegalArgumentException("Skill is required");
        if (durationMinutes <= 0) throw new IllegalArgumentException("Duration must be positive");
        this.id = id;
        this.tutor = tutor;
        this.skill = skill;
        this.description = description == null ? "" : description;
        this.durationMinutes = durationMinutes;
    }

    /**
     * Factory method: builds the right subclass for a type. The database layer and the service use
     * it, so "which class belongs to which type" is decided in exactly one place.
     */
    public static Listing of(ListingType type, long id, Student tutor, String skill, String description,
                             int durationMinutes, double fee, int creditCost) {
        return switch (type) {
            case PAID -> new PaidListing(id, tutor, skill, description, durationMinutes, fee);
            case SWAP -> new CreditSwapListing(id, tutor, skill, description, durationMinutes, creditCost);
            case FREE -> new FreeListing(id, tutor, skill, description, durationMinutes);
        };
    }

    public long getId() { return id; }
    public Student getTutor() { return tutor; }
    public String getSkill() { return skill; }
    public String getDescription() { return description; }
    public int getDurationMinutes() { return durationMinutes; }

    public abstract ListingType getType();

    /** Human-readable cost of one session, e.g. "Rs. 200" or "2 credits". */
    public abstract String getPriceDescription();

    /** Credits a learner must spend to book this listing. 0 unless the subclass overrides it. */
    public int getCreditCost() { return 0; }

    /** Fee in rupees. 0 unless the subclass overrides it. */
    public double getFee() { return 0; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Listing other && id == other.id;
    }

    @Override
    public int hashCode() { return Long.hashCode(id); }

    @Override
    public String toString() {
        return skill + " with " + tutor.getName() + " (" + getPriceDescription() + ")";
    }
}
