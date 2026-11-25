package skillexchange.service;

import skillexchange.exception.BookingException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.model.AttemptResult;
import skillexchange.model.AttemptResult.Feedback;
import skillexchange.model.Exercise;
import skillexchange.model.LearningModule;
import skillexchange.model.Listing;
import skillexchange.model.ModuleDraft;
import skillexchange.model.ModuleView;
import skillexchange.model.Question;
import skillexchange.model.ScoreRow;
import skillexchange.model.Student;
import skillexchange.persistence.Database;
import skillexchange.persistence.ListingDao;
import skillexchange.persistence.ModuleDao;
import skillexchange.validation.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Learning modules: sets of exercises a tutor attaches to a listing, which learners take and the
 * server marks. The answers stay on the server; a learner only ever receives {@link Question}s.
 */
public final class LearningService {
    private static final Logger LOG = Logger.getLogger(LearningService.class.getName());

    private final Database db;
    private final AppClock clock;
    private final ModuleDao modules;
    private final ListingDao listings;

    LearningService(Database db, AppClock clock, ModuleDao modules, ListingDao listings) {
        this.db = db;
        this.clock = clock;
        this.modules = modules;
        this.listings = listings;
    }

    public LearningModule createModule(Student tutor, long listingId, ModuleDraft draft) throws SkillExchangeException {
        if (draft == null) throw new BookingException("The module is empty");
        String title = Validator.moduleTitle(draft.title());
        String summary = Validator.moduleSummary(draft.summary());
        List<Exercise> exercises = Validator.exercises(draft.exercises());
        LearningModule saved = db.inTransaction(c -> {
            Listing listing = listings.require(c, listingId);
            if (!listing.getTutor().equals(tutor))
                throw new BookingException("You can only add modules to your own listings");
            long id = modules.insertModule(c, listingId, title, summary, clock.now());
            modules.insertExercises(c, id, exercises);     // same transaction: no module without its exercises
            return modules.require(c, id);
        });
        LOG.info(() -> tutor.getName() + " added module \"" + saved.title() + "\" with "
                + saved.exercises().size() + " exercises to listing " + listingId);
        return saved;
    }

    public void deleteModule(Student tutor, long moduleId) throws SkillExchangeException {
        db.inTransaction(c -> {
            requireAuthor(modules.require(c, moduleId), tutor);
            modules.delete(c, moduleId);
            return null;
        });
    }

    /** Every module that matches the text, with this student's best score on each. */
    public List<ModuleView> modulesFor(Student viewer, String text) {
        return db.query(c -> {
            List<ModuleView> views = new ArrayList<>();
            for (LearningModule m : modules.search(c, text)) {
                Integer best = modules.bestScore(c, m.id(), viewer.getId()).orElse(null);
                views.add(new ModuleView(m.id(), m.listing(), m.title(), m.summary(), m.exercises().size(),
                        m.totalPoints(), best, m.listing().getTutor().equals(viewer)));
            }
            return views;
        });
    }

    /** The questions of a module, without the answers. */
    public List<Question> questions(long moduleId) throws SkillExchangeException {
        LearningModule module = db.inTransaction(c -> modules.require(c, moduleId));
        // A stream: turn every Exercise into the Question a learner may see.
        return module.exercises().stream().map(Exercise::toQuestion).toList();
    }

    /** Marks one attempt and records it. {@code answers} maps a question id to what the learner chose or typed. */
    public AttemptResult submit(Student learner, long moduleId, Map<Long, String> answers) throws SkillExchangeException {
        return db.inTransaction(c -> {
            LearningModule module = modules.require(c, moduleId);
            if (module.listing().getTutor().equals(learner))
                throw new BookingException("You wrote this module, so you cannot take it yourself");
            int score = 0;
            List<Feedback> feedback = new ArrayList<>();
            for (Exercise e : module.exercises()) {
                String given = answers == null ? null : answers.get(e.id());
                boolean right = e.isCorrect(given);      // polymorphic: each kind of exercise marks itself
                if (right) score += e.points();
                feedback.add(new Feedback(e.prompt(), given == null || given.isBlank() ? "(no answer)" : given.strip(),
                        e.correctAnswer(), right, e.points()));
            }
            modules.insertAttempt(c, moduleId, learner.getId(), score, module.totalPoints(), clock.now());
            return new AttemptResult(score, module.totalPoints(), feedback);
        });
    }

    /** The tutor's own modules, answers included. */
    public List<LearningModule> modulesOf(Student tutor) {
        return db.query(c -> modules.findByTutor(c, tutor.getId()));
    }

    public List<ScoreRow> scores(Student tutor, long moduleId) throws SkillExchangeException {
        return db.inTransaction(c -> {
            requireAuthor(modules.require(c, moduleId), tutor);
            return modules.scores(c, moduleId);
        });
    }

    private static void requireAuthor(LearningModule module, Student tutor) throws BookingException {
        if (!module.listing().getTutor().equals(tutor))
            throw new BookingException("Only the tutor who wrote this module can do that");
    }
}
