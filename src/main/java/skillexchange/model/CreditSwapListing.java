package skillexchange.model;

/**
 * A session paid for with skill-exchange credits.
 * The learner's credits are held when they book and reach the tutor once the session is confirmed
 * (see CreditLedger).
 */
public class CreditSwapListing extends Listing {
    private static final long serialVersionUID = 2L;

    private final int creditCost;

    public CreditSwapListing(long id, Student tutor, String skill, String description,
                             int durationMinutes, int creditCost) {
        super(id, tutor, skill, description, durationMinutes);
        if (creditCost <= 0) throw new IllegalArgumentException("Credit cost must be positive");
        this.creditCost = creditCost;
    }

    @Override
    public int getCreditCost() { return creditCost; }

    @Override
    public ListingType getType() { return ListingType.SWAP; }

    @Override
    public String getPriceDescription() {
        return creditCost + (creditCost == 1 ? " credit" : " credits");
    }
}
