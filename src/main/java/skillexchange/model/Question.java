package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/** An exercise as a learner sees it before answering: the answer is not included. */
public record Question(long id, Exercise.Kind kind, String prompt, List<String> options, int points)
        implements Serializable { }
