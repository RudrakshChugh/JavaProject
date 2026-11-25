package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/** A module that has not been saved yet: what a tutor types in, or what a module file contains. */
public record ModuleDraft(String title, String summary, List<Exercise> exercises) implements Serializable {
    public ModuleDraft {
        exercises = List.copyOf(exercises);
    }
}
