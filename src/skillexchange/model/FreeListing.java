package skillexchange.model;

/** A volunteer session with no cost. */
public class FreeListing extends Listing {
    private static final long serialVersionUID = 1L;

    public FreeListing(String id, Student tutor, String skill, String description, int durationMinutes) {
        super(id, tutor, skill, description, durationMinutes);
    }

    @Override
    public String getType() { return "FREE"; }

    @Override
    public String getPriceDescription() { return "Free"; }
}
