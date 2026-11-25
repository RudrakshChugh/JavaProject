package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/** What anyone may see about a tutor: what they teach, when they are free, how many sessions they have taught. */
public record TutorProfile(Student student, List<Listing> listings, List<TimeSlot> openSlots, int sessionsTaught)
        implements Serializable {
    public TutorProfile {
        listings = List.copyOf(listings);
        openSlots = List.copyOf(openSlots);
    }
}
