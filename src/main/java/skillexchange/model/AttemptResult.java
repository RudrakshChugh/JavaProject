package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/** What the server sends back after marking a learner's answers. */
public record AttemptResult(int score, int total, List<Feedback> feedback) implements Serializable {

    public AttemptResult {
        feedback = List.copyOf(feedback);
    }

    public int percent() {
        return total == 0 ? 0 : Math.round(score * 100f / total);
    }

    /** A nested record: the marking of one question. */
    public record Feedback(String prompt, String given, String correct, boolean right, int points)
            implements Serializable { }
}
