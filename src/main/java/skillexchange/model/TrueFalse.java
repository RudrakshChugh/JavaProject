package skillexchange.model;

import java.util.List;

/** A statement the learner marks as true or false. */
public record TrueFalse(long id, String prompt, boolean answer, int points) implements Exercise {

    private static final List<String> OPTIONS = List.of("True", "False");

    @Override
    public Kind kind() { return Kind.TRUE_FALSE; }

    @Override
    public List<String> options() { return OPTIONS; }

    @Override
    public boolean isCorrect(String given) {
        return Exercise.normalise(given).equals(Exercise.normalise(correctAnswer()));
    }

    @Override
    public String correctAnswer() { return answer ? "True" : "False"; }
}
