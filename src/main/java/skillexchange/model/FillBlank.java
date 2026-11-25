package skillexchange.model;

import java.util.List;

/** A sentence with a gap. Any of the accepted answers earns the points. */
public record FillBlank(long id, String prompt, List<String> accepted, int points) implements Exercise {

    public FillBlank {
        accepted = List.copyOf(accepted);
        if (accepted.isEmpty()) throw new IllegalArgumentException("A fill-in question needs at least one accepted answer");
    }

    @Override
    public Kind kind() { return Kind.FILL; }

    @Override
    public boolean isCorrect(String answer) {
        String given = Exercise.normalise(answer);
        return accepted.stream().map(Exercise::normalise).anyMatch(given::equals);
    }

    @Override
    public String correctAnswer() { return accepted.get(0); }
}
