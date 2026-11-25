package skillexchange.persistence;

import skillexchange.model.Exercise;
import skillexchange.model.FillBlank;
import skillexchange.model.LearningModule;
import skillexchange.model.MultipleChoice;
import skillexchange.model.ScoreRow;
import skillexchange.model.TrueFalse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Data Access Object for learning modules, their exercises and the learners' attempts. */
public final class ModuleDao extends Dao implements Repository<LearningModule> {

    private static final String COLS = "mo.id AS module_id, mo.title, mo.summary, " + ListingDao.COLS;
    private static final String FROM = " FROM modules mo JOIN listings l ON l.id = mo.listing_id"
            + " JOIN students t ON t.id = l.tutor_id";

    // Options and accepted answers are several short texts in one column, one per line.
    private static final String SEPARATOR = "\n";

    @Override
    public String entityName() { return "module"; }

    @Override
    public Optional<LearningModule> findById(Connection c, long id) throws SQLException {
        return withExercises(c, "SELECT " + COLS + FROM + " WHERE mo.id = ? AND l.active = 1", id).stream().findFirst();
    }

    @Override
    public List<LearningModule> findAll(Connection c) throws SQLException {
        return withExercises(c, "SELECT " + COLS + FROM + " WHERE l.active = 1 ORDER BY l.skill, mo.id");
    }

    /** Modules whose title, skill or tutor matches the text. */
    public List<LearningModule> search(Connection c, String text) throws SQLException {
        String pattern = like(text == null ? "" : text.strip());
        return withExercises(c, "SELECT " + COLS + FROM + " WHERE l.active = 1 AND (lower(mo.title) LIKE ? ESCAPE '\\'"
                + " OR lower(l.skill) LIKE ? ESCAPE '\\' OR lower(t.name) LIKE ? ESCAPE '\\') ORDER BY l.skill, mo.id",
                pattern, pattern, pattern);
    }

    public List<LearningModule> findByTutor(Connection c, long tutorId) throws SQLException {
        return withExercises(c, "SELECT " + COLS + FROM + " WHERE l.active = 1 AND l.tutor_id = ? ORDER BY l.skill, mo.id", tutorId);
    }

    private List<LearningModule> withExercises(Connection c, String sql, Object... params) throws SQLException {
        // The first query reads the modules; the mapper cannot run a second query while the first
        // ResultSet is open, so the exercises are added afterwards.
        List<LearningModule> bare = list(c, sql, rs -> new LearningModule(rs.getLong("module_id"), ListingDao.map(rs),
                rs.getString("title"), rs.getString("summary"), List.of()), params);
        List<LearningModule> full = new ArrayList<>();
        for (LearningModule m : bare) {
            full.add(new LearningModule(m.id(), m.listing(), m.title(), m.summary(), exercises(c, m.id())));
        }
        return full;
    }

    private List<Exercise> exercises(Connection c, long moduleId) throws SQLException {
        return list(c, "SELECT id, kind, prompt, options, answer, points FROM exercises WHERE module_id = ? ORDER BY position",
                ModuleDao::mapExercise, moduleId);
    }

    /** The kind column decides which implementation of Exercise is created. */
    private static Exercise mapExercise(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String prompt = rs.getString("prompt");
        String answer = rs.getString("answer");
        int points = rs.getInt("points");
        return switch (Exercise.Kind.valueOf(rs.getString("kind"))) {
            case MCQ -> new MultipleChoice(id, prompt, split(rs.getString("options")), Integer.parseInt(answer), points);
            case TRUE_FALSE -> new TrueFalse(id, prompt, Boolean.parseBoolean(answer), points);
            case FILL -> new FillBlank(id, prompt, split(answer), points);
        };
    }

    private static List<String> split(String text) {
        return Arrays.asList(text.split(SEPARATOR));
    }

    public long insertModule(Connection c, long listingId, String title, String summary, LocalDateTime at) throws SQLException {
        return insert(c, "INSERT INTO modules (listing_id, title, summary, created_at) VALUES (?, ?, ?, ?)",
                listingId, title, summary, at);
    }

    /** Inserts all exercises of a module as one JDBC batch: one round trip instead of one per row. */
    public void insertExercises(Connection c, long moduleId, List<Exercise> exercises) throws SQLException {
        String sql = "INSERT INTO exercises (module_id, position, kind, prompt, options, answer, points) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int position = 1;
            for (Exercise e : exercises) {
                String options = "";
                String answer;
                if (e instanceof MultipleChoice mcq) {
                    options = String.join(SEPARATOR, mcq.options());
                    answer = String.valueOf(mcq.correctIndex());
                } else if (e instanceof TrueFalse tf) {
                    answer = String.valueOf(tf.answer());
                } else if (e instanceof FillBlank fill) {
                    answer = String.join(SEPARATOR, fill.accepted());
                } else {
                    throw new IllegalStateException("Unknown exercise kind: " + e.kind());
                }
                bind(ps, moduleId, position++, e.kind(), e.prompt().strip(), options, answer, e.points());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** The exercises and attempts go with it: the schema says ON DELETE CASCADE. */
    public void delete(Connection c, long moduleId) throws SQLException {
        update(c, "DELETE FROM modules WHERE id = ?", moduleId);
    }

    public void insertAttempt(Connection c, long moduleId, long studentId, int score, int total, LocalDateTime at) throws SQLException {
        insert(c, "INSERT INTO attempts (module_id, student_id, score, total, taken_at) VALUES (?, ?, ?, ?, ?)",
                moduleId, studentId, score, total, at);
    }

    public Optional<Integer> bestScore(Connection c, long moduleId, long studentId) throws SQLException {
        return one(c, "SELECT MAX(score) AS best FROM attempts WHERE module_id = ? AND student_id = ?",
                rs -> {
                    int best = rs.getInt("best");
                    return rs.wasNull() ? null : (Integer) best;   // MAX of no rows is NULL
                }, moduleId, studentId);
    }

    /** One row per learner who attempted the module, best score first. */
    public List<ScoreRow> scores(Connection c, long moduleId) throws SQLException {
        return list(c, "SELECT " + StudentDao.cols("st", "st") + ", MAX(a.score) AS best, MAX(a.total) AS total,"
                        + " COUNT(*) AS attempts, MAX(a.taken_at) AS last_at"
                        + " FROM attempts a JOIN students st ON st.id = a.student_id WHERE a.module_id = ?"
                        + " GROUP BY st.id, st.name, st.email ORDER BY best DESC, last_at",
                rs -> new ScoreRow(StudentDao.map(rs, "st"), rs.getInt("best"), rs.getInt("total"),
                        rs.getInt("attempts"), time(rs, "last_at")), moduleId);
    }
}
