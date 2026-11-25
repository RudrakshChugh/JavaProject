package skillexchange.model;

/** A session with a fee in rupees. The fee is recorded; the application does not collect money. */
public class PaidListing extends Listing {
    private static final long serialVersionUID = 2L;

    private final double fee;

    public PaidListing(long id, Student tutor, String skill, String description, int durationMinutes, double fee) {
        super(id, tutor, skill, description, durationMinutes);
        // "!(fee > 0)" also rejects NaN, which "fee <= 0" lets through.
        if (!(fee > 0) || Double.isInfinite(fee)) throw new IllegalArgumentException("Fee must be a positive amount");
        this.fee = fee;
    }

    @Override
    public double getFee() { return fee; }

    @Override
    public ListingType getType() { return ListingType.PAID; }

    @Override
    public String getPriceDescription() {
        return fee == Math.floor(fee) ? String.format("Rs. %.0f", fee) : String.format("Rs. %.2f", fee);
    }
}
