package skillexchange.model;

/** A session the learner pays money for. */
public class PaidListing extends Listing {
    private static final long serialVersionUID = 1L;

    private final double fee;

    public PaidListing(String id, Student tutor, String skill, String description,
                       int durationMinutes, double fee) {
        super(id, tutor, skill, description, durationMinutes);
        // "!(fee > 0)" also rejects NaN, which "fee <= 0" lets through.
        if (!(fee > 0) || Double.isInfinite(fee)) throw new IllegalArgumentException("Fee must be a positive amount");
        this.fee = fee;
    }

    public double getFee() { return fee; }

    @Override
    public String getType() { return "PAID"; }

    @Override
    public String getPriceDescription() { return String.format("Rs. %.2f", fee); }
}
