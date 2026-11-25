package skillexchange.persistence;

import skillexchange.exception.DataAccessException;
import skillexchange.exception.SkillExchangeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The connection to the SQLite database file, and the only place that begins, commits and rolls
 * back transactions.
 *
 * SQLite keeps the whole database in one file, so there is no database server to install.
 * The JDBC code would be the same for MySQL: only the URL passed to DriverManager changes.
 */
public final class Database {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    private final String url;
    private final Path file;

    public Database(Path file) {
        this.file = file.toAbsolutePath();
        this.url = "jdbc:sqlite:" + this.file;
        try {
            if (this.file.getParent() != null) Files.createDirectories(this.file.getParent());
        } catch (IOException e) {
            throw new DataAccessException("Could not create the data folder for " + this.file, e);
        }
        createSchema();
    }

    public Path getFile() { return file; }

    /**
     * Runs the work as one transaction: either every change in it is saved, or none is.
     * If the work throws anything, the transaction is rolled back and the exception continues
     * to the caller. A SQLException is wrapped in an unchecked DataAccessException (exception chaining).
     */
    public <T> T inTransaction(SqlWork<T> work) throws SkillExchangeException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | SkillExchangeException | RuntimeException e) {
                rollback(c);
                throw e;
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Database error, transaction rolled back", e);
            throw new DataAccessException("The database could not complete the request", e);
        }
    }

    /** Runs a read-only query. Each statement sees a consistent state without a transaction of our own. */
    public <T> T query(SqlQuery<T> query) {
        try (Connection c = open()) {
            return query.run(c);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Database error while reading", e);
            throw new DataAccessException("The database could not complete the request", e);
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");     // SQLite checks REFERENCES only when asked to
            st.execute("PRAGMA busy_timeout = 5000");   // wait up to 5 s for another writer instead of failing
        }
        return c;
    }

    private static void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Rollback failed", e);
        }
    }

    /** Reads schema.sql from the class path and runs each statement in it. */
    private void createSchema() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");    // readers do not block the writer
            for (String sql : readStatements("/db/schema.sql")) {
                st.execute(sql);
            }
            LOG.config(() -> "Database ready at " + file);
        } catch (SQLException | IOException e) {
            throw new DataAccessException("Could not prepare the database at " + file, e);
        }
    }

    /** Splits a .sql resource into statements: comment lines are dropped, a semicolon ends a statement. */
    private static String[] readStatements(String resource) throws IOException {
        InputStream in = Database.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("Missing resource " + resource);
        StringBuilder sql = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.strip().startsWith("--")) continue;
                sql.append(line).append('\n');
            }
        }
        return java.util.Arrays.stream(sql.toString().split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
