package skillexchange.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skillexchange.exception.AuthException;
import skillexchange.exception.BookingException;
import skillexchange.exception.ValidationException;
import skillexchange.model.AttemptResult;
import skillexchange.model.Conversation;
import skillexchange.model.Exercise;
import skillexchange.model.FillBlank;
import skillexchange.model.LearningModule;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.ModuleDraft;
import skillexchange.model.ModuleView;
import skillexchange.model.MultipleChoice;
import skillexchange.model.Question;
import skillexchange.model.Student;
import skillexchange.model.TrueFalse;
import skillexchange.persistence.Database;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningAndChatTest {

    @TempDir
    Path dir;

    private Services s;
    private Student tutor;
    private Student learner;
    private Student stranger;
    private Listing python;

    @BeforeEach
    void setUp() throws Exception {
        s = Services.create(new Database(dir.resolve("test.db")));
        tutor = s.accounts().register("Ravi", "ravi@test.edu", "pass1234".toCharArray());
        learner = s.accounts().register("Asha", "asha@test.edu", "pass1234".toCharArray());
        stranger = s.accounts().register("Kabir", "kabir@test.edu", "pass1234".toCharArray());
        python = s.bookings().createListing(tutor, ListingType.FREE, "Python", "", 60, 0, 0);
    }

    private ModuleDraft draft() {
        List<Exercise> exercises = List.of(
                new MultipleChoice(0, "Which keyword defines a function?", List.of("func", "def", "fn"), 1, 2),
                new TrueFalse(0, "Python lists can change size.", true, 1),
                new FillBlank(0, "The function that prints is ____.", List.of("print", "print()"), 1));
        return new ModuleDraft("Functions", "Defining and calling", exercises);
    }

    @Test
    void aModuleKeepsItsExercisesInOrderAndOfTheRightKind() throws Exception {
        LearningModule m = s.learning().createModule(tutor, python.getId(), draft());
        assertEquals(3, m.exercises().size());
        assertEquals(4, m.totalPoints());
        assertTrue(m.exercises().get(0) instanceof MultipleChoice);
        assertTrue(m.exercises().get(1) instanceof TrueFalse);
        assertTrue(m.exercises().get(2) instanceof FillBlank);
        assertEquals("def", m.exercises().get(0).correctAnswer());
    }

    @Test
    void learnersGetQuestionsWithoutAnswersAndAreMarkedByTheServer() throws Exception {
        LearningModule m = s.learning().createModule(tutor, python.getId(), draft());
        List<Question> questions = s.learning().questions(m.id());
        assertEquals(List.of("func", "def", "fn"), questions.get(0).options());

        Map<Long, String> answers = new HashMap<>();
        answers.put(questions.get(0).id(), "def");          // right, 2 points
        answers.put(questions.get(1).id(), "False");        // wrong
        answers.put(questions.get(2).id(), "  PRINT() ");   // right: case and spaces are ignored
        AttemptResult result = s.learning().submit(learner, m.id(), answers);

        assertEquals(3, result.score());
        assertEquals(4, result.total());
        assertEquals(75, result.percent());
        assertFalse(result.feedback().get(1).right());
        assertEquals("True", result.feedback().get(1).correct());
    }

    @Test
    void bestScoreIsRememberedPerStudent() throws Exception {
        LearningModule m = s.learning().createModule(tutor, python.getId(), draft());
        List<Question> q = s.learning().questions(m.id());
        s.learning().submit(learner, m.id(), Map.of(q.get(0).id(), "def"));
        s.learning().submit(learner, m.id(), Map.of());

        ModuleView mine = s.learning().modulesFor(learner, "").get(0);
        assertEquals(2, mine.bestScore());
        assertNull(s.learning().modulesFor(stranger, "").get(0).bestScore());
        assertTrue(s.learning().modulesFor(tutor, "").get(0).mine());
        assertEquals(2, s.learning().scores(tutor, m.id()).get(0).attempts());
    }

    @Test
    void moduleRules() throws Exception {
        LearningModule m = s.learning().createModule(tutor, python.getId(), draft());
        assertThrows(BookingException.class, () -> s.learning().createModule(learner, python.getId(), draft()), "not the owner");
        assertThrows(BookingException.class, () -> s.learning().submit(tutor, m.id(), Map.of()), "own module");
        assertThrows(BookingException.class, () -> s.learning().scores(learner, m.id()));
        assertThrows(ValidationException.class,
                () -> s.learning().createModule(tutor, python.getId(), new ModuleDraft("Empty", "", List.of())));
        s.learning().deleteModule(tutor, m.id());
        assertTrue(s.learning().modulesFor(learner, "").isEmpty());
    }

    @Test
    void chatIsPrivateToItsTwoStudents() throws Exception {
        Conversation c = s.chat().open(learner, python.getId());
        assertEquals(c.id(), s.chat().open(learner, python.getId()).id(), "opening twice gives the same conversation");
        s.chat().send(learner, c.id(), "  Where do we meet?  ");
        s.chat().send(tutor, c.id(), "Library, 5 pm.");

        assertEquals(2, s.chat().messages(tutor, c.id()).size());
        assertEquals("Where do we meet?", s.chat().messages(learner, c.id()).get(0).text());
        assertEquals("Library, 5 pm.", s.chat().conversationsOf(tutor).get(0).lastText());
        assertEquals(tutor, c.other(learner));

        assertThrows(AuthException.class, () -> s.chat().messages(stranger, c.id()));
        assertThrows(AuthException.class, () -> s.chat().send(stranger, c.id(), "hello"));
        assertThrows(ValidationException.class, () -> s.chat().send(learner, c.id(), "   "));
        assertThrows(BookingException.class, () -> s.chat().open(tutor, python.getId()), "own listing");
    }
}
