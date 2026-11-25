package skillexchange.model;

import java.util.List;

/** A question with several choices, exactly one of which is right. */
public record MultipleChoice(long id, String prompt, List<String> options, int correctIndex, int points)
        implements Exercise {

    /** Compact constructor: runs before the fields are assigned, so an invalid question cannot exist. */
    public MultipleChoice {
        options = List.copyOf(options);
        if (options.size() < 2) throw new IllegalArgumentException("A multiple-choice question needs at least 2 options");
        if (correctIndex < 0 || correctIndex >= options.size())
            throw new IllegalArgumentException("The correct option is not one of the options");
    }

    @Override
    public Kind kind() { return Kind.MCQ; }

    @Override
    public boolean isCorrect(String answer) {
        return Exercise.normalise(answer).equals(Exercise.normalise(correctAnswer()));
    }

    @Override
    public String correctAnswer() { return options.get(correctIndex); }
}
