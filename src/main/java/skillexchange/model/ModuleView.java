package skillexchange.model;

import java.io.Serializable;

/**
 * A module as it appears in the Learn screen: no answers, plus the viewer's own best score.
 * {@code bestScore} is null if the viewer has not attempted the module yet.
 */
public record ModuleView(long id, Listing listing, String title, String summary, int questions,
                         int totalPoints, Integer bestScore, boolean mine) implements Serializable {

    public boolean attempted() { return bestScore != null; }

    /** Best score as a whole percentage, or 0 if not attempted. */
    public int percent() {
        return bestScore == null || totalPoints == 0 ? 0 : Math.round(bestScore * 100f / totalPoints);
    }
}
