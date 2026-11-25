package skillexchange.model;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;

/**
 * One question in a learning module.
 *
 * This is the project's own interface, and a sealed one: only the three record types named after
 * {@code permits} may implement it, so the compiler knows the full list of exercise kinds.
 * It shows the things an interface can hold besides abstract methods: a constant, a nested enum,
 * default methods and a static method.
 */
public sealed interface Exercise extends Serializable permits MultipleChoice, TrueFalse, FillBlank {

    /** A constant: fields in an interface are implicitly public, static and final. */
    int MAX_POINTS = 10;

    enum Kind {
        MCQ("Multiple choice"),
        TRUE_FALSE("True or false"),
        FILL("Fill in the blank");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }
    }

    long id();
    String prompt();
    int points();
    Kind kind();

    /** True if the learner's answer earns the points. */
    boolean isCorrect(String answer);

    /** The right answer as text, shown in the feedback after an attempt. */
    String correctAnswer();

    /** The choices to show. Empty unless the kind has fixed choices. */
    default List<String> options() {
        return List.of();
    }

    /** What a learner is allowed to see: everything except the answer. */
    default Question toQuestion() {
        return new Question(id(), kind(), prompt(), options(), points());
    }

    /** Answers are compared without regard to case or extra spaces. */
    static String normalise(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
