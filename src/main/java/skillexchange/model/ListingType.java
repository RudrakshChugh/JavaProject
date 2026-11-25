package skillexchange.model;

/** How a session of a listing is paid for. An enum with a field: each constant carries its label. */
public enum ListingType {
    PAID("Paid"),
    SWAP("Credit swap"),
    FREE("Free");

    private final String label;

    ListingType(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}
