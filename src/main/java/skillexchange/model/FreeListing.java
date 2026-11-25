package skillexchange.model;

/** A session that costs nothing. */
public class FreeListing extends Listing {
    private static final long serialVersionUID = 2L;

    public FreeListing(long id, Student tutor, String skill, String description, int durationMinutes) {
        super(id, tutor, skill, description, durationMinutes);
    }

    @Override
    public ListingType getType() { return ListingType.FREE; }

    @Override
    public String getPriceDescription() { return "Free"; }
}
