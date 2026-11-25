package skillexchange.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sets up java.util.logging for the whole program. Every class has its own Logger
 * ({@code Logger.getLogger(ClassName.class.getName())}); this decides where their records go:
 *
 * - the console: INFO and above
 * - logs/app-N.log: everything from FINE up, in rotating files of 1 MB
 * - logs/audit-N.log: only what students did (the records of the service layer), chosen by a Filter
 */
public final class LogConfig {

    private static boolean configured;

    private LogConfig() { }

    /** Synchronized: the embedded server and the client may both ask for logging at start-up. */
    public static synchronized void init(Path logDir) {
        if (configured) return;
        configured = true;

        Logger root = Logger.getLogger("");
        for (Handler old : root.getHandlers()) {
            root.removeHandler(old);                    // drop the JDK's default two-line console format
        }
        root.setLevel(Level.INFO);                      // libraries: INFO and above
        Logger.getLogger("skillexchange").setLevel(Level.FINE);   // our own code: FINE and above

        OneLineFormatter formatter = new OneLineFormatter();
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(formatter);
        root.addHandler(console);

        try {
            Files.createDirectories(logDir);
            // %g is the file's number in the rotation: 3 files of 1 MB each, appended to across runs.
            FileHandler file = new FileHandler(logDir.resolve("app-%g.log").toString(), 1_000_000, 3, true);
            file.setLevel(Level.FINE);
            file.setFormatter(formatter);
            root.addHandler(file);

            FileHandler audit = new FileHandler(logDir.resolve("audit-%g.log").toString(), 1_000_000, 3, true);
            audit.setFormatter(formatter);
            // A Filter decides record by record. It is a functional interface, so a lambda will do.
            audit.setFilter(record -> record.getLevel().intValue() >= Level.INFO.intValue()
                    && record.getLoggerName() != null
                    && record.getLoggerName().startsWith("skillexchange.service"));
            root.addHandler(audit);
        } catch (IOException e) {
            // Logging to files is a convenience. Without it the program still runs and logs to the console.
            Logger.getLogger(LogConfig.class.getName()).log(Level.WARNING, "Could not open log files in " + logDir, e);
        }
    }
}
