package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** One line of the "recent bookings" ticker on the home page. Names only, nothing private. */
public record Activity(String learner, String skill, String tutor, LocalDateTime at) implements Serializable { }
