package skillexchange.model;

/**
 * A session paid for with skill-exchange credits.
 * The learner loses credits and the tutor gains them (the ledger comes in a later step).
 */
public class CreditSwapListing extends Listing {
    private static final long serialVersionUID = 1L;

    private final int creditCost;

    public CreditSwapListing(String id, Student tutor, String skill, String description,
                             int durationMinutes, int creditCost) {
        super(id, tutor, skill, description, durationMinutes);
        if (creditCost <= 0) throw new IllegalArgumentException("Credit cost must be positive");
        this.creditCost = creditCost;
    }

    @Override
    public int getCreditCost() { return creditCost; }

    @Override
    public String getType() { return "SWAP"; }

    @Override
    public String getPriceDescription() {
        return creditCost + (creditCost == 1 ? " credit" : " credits");
    }
}
