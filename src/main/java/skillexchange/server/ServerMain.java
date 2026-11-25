package skillexchange.server;

import skillexchange.io.Backup;
import skillexchange.logging.LogConfig;
import skillexchange.model.Stats;
import skillexchange.persistence.BookingDao;
import skillexchange.persistence.Database;
import skillexchange.persistence.ListingDao;
import skillexchange.persistence.StudentDao;
import skillexchange.service.DemoData;
import skillexchange.service.Services;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts the back end: the database, the services and the socket server.
 *
 * <pre>
 * java skillexchange.server.ServerMain [--port 5050] [--http-port 8080] [--http-only] [--data data] [--reset]
 * </pre>
 */
public final class ServerMain {
    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    private ServerMain() { }

    public static void main(String[] args) {
        int port = SkillExchangeServer.DEFAULT_PORT;
        int httpPort = SkillExchangeServer.NO_PORT;
        Path dataDir = Path.of("data");
        boolean reset = false;
        try {
            // A hosting service says which port to answer HTTP on through the PORT environment variable.
            String fromHost = System.getenv("PORT");
            if (fromHost != null && !fromHost.isBlank()) httpPort = Integer.parseInt(fromHost.trim());
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--http-port" -> httpPort = Integer.parseInt(args[++i]);
                    case "--http-only" -> port = SkillExchangeServer.NO_PORT;
                    case "--data" -> dataDir = Path.of(args[++i]);
                    case "--reset" -> reset = true;
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            if (port == SkillExchangeServer.NO_PORT && httpPort == SkillExchangeServer.NO_PORT)
                throw new IllegalArgumentException("--http-only needs --http-port or the PORT variable");
        } catch (RuntimeException e) {      // a missing value, a port that is not a number, an unknown option
            System.err.println("Usage: ServerMain [--port " + SkillExchangeServer.DEFAULT_PORT
                    + "] [--http-port 8080] [--http-only] [--data data] [--reset]");
            System.exit(2);
        }

        LogConfig.init(dataDir.resolve("logs"));
        try {
            SkillExchangeServer server = boot(dataDir, port, httpPort, reset);
            // Runs when the JVM is asked to stop (Ctrl+C or "quit"): close connections and threads cleanly.
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                stopped.countDown();
            }, "shutdown"));
            console(server, dataDir);
            // No console to read from (started in the background): stay up until the JVM is stopped.
            stopped.await();
        } catch (BindException e) {
            LOG.severe("Port " + port + " is already in use. Is the server running already? Use --port to pick another.");
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "The server could not start", e);
            System.exit(1);
        }
    }

    /** Builds everything and starts listening. Also used by the desktop app to host a server itself. */
    public static SkillExchangeServer boot(Path dataDir, int port, boolean reset) throws Exception {
        return boot(dataDir, port, SkillExchangeServer.NO_PORT, reset);
    }

    /** The same, with an HTTP port as well as (or, with NO_PORT for {@code port}, instead of) the socket port. */
    public static SkillExchangeServer boot(Path dataDir, int port, int httpPort, boolean reset) throws Exception {
        Path dbFile = dataDir.resolve("skillexchange.db");
        if (reset) {
            // SQLite keeps two helper files next to the database while it is in WAL mode.
            for (String suffix : new String[] {"", "-wal", "-shm"}) {
                Files.deleteIfExists(Path.of(dbFile + suffix));
            }
            LOG.info("Saved data deleted (--reset)");
        }
        Services services = Services.create(new Database(dbFile));
        if (services.accounts().countStudents() == 0) {
            DemoData.seed(services);
        }
        SkillExchangeServer server = new SkillExchangeServer(services, port, httpPort);
        server.start();
        return server;
    }

    /** A small admin console on standard input. Returns when the input ends or "quit" is typed. */
    private static void console(SkillExchangeServer server, Path dataDir) {
        System.out.println("Type help for commands, quit to stop the server.");
        try (Scanner in = new Scanner(System.in)) {
            while (in.hasNextLine()) {
                String command = in.nextLine().strip().toLowerCase();
                switch (command) {
                    case "" -> { }
                    case "help" -> System.out.println("stats   numbers of students, listings and sessions\n"
                            + "clients how many clients are connected\n"
                            + "backup  write a serialized snapshot to the data folder\n"
                            + "quit    stop the server");
                    case "stats" -> {
                        Stats s = server.services().bookings().stats();
                        System.out.printf("Students: %d, listings: %d, open sessions: %d, completed sessions: %d%n",
                                s.students(), s.listings(), s.openSessions(), s.completedSessions());
                    }
                    case "clients" -> System.out.println(server.connectedClients() + " connected");
                    case "backup" -> backup(server, dataDir);
                    case "quit", "exit" -> {
                        System.exit(0);     // runs the shutdown hook, which closes the server
                        return;
                    }
                    default -> System.out.println("Unknown command. Type help.");
                }
            }
        }
    }

    private static void backup(SkillExchangeServer server, Path dataDir) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dataDir.resolve("backup-" + stamp + ".ser");
        try {
            Backup.Snapshot snapshot = server.services().db().query(c -> new Backup.Snapshot(LocalDateTime.now(),
                    new StudentDao().findAll(c), new ListingDao().findAll(c), new BookingDao().findAll(c)));
            Backup.write(file, snapshot);
            Backup.Snapshot check = Backup.read(file);      // read it back to prove the file is good
            System.out.printf("Wrote %s: %d students, %d listings, %d bookings%n", file,
                    check.students().size(), check.listings().size(), check.bookings().size());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Backup failed", e);
        }
    }
}
