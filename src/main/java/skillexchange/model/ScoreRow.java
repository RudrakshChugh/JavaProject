package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** One learner's results on a module, for the tutor who wrote it. */
public record ScoreRow(Student student, int bestScore, int total, int attempts, LocalDateTime lastAt)
        implements Serializable { }
