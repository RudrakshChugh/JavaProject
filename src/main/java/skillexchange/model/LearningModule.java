package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/** A set of exercises a tutor attaches to a listing, with the answers. Only the tutor receives this. */
public record LearningModule(long id, Listing listing, String title, String summary, List<Exercise> exercises)
        implements Serializable {

    public LearningModule {
        exercises = List.copyOf(exercises);
    }

    public int totalPoints() {
        return exercises.stream().mapToInt(Exercise::points).sum();
    }
}
