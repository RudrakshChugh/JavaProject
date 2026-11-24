package skillexchange.persistence;

import skillexchange.exception.PersistenceException;
import skillexchange.model.*;
import skillexchange.service.BookingService;
import skillexchange.service.CreditLedger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;

/**
 * Saves the application state to disk and loads it back.
 *   skillexchange.dat   binary snapshot of the whole BookingService (serialisation)
 *   sessions.csv        readable session history (text file I/O)
 *   balances.txt        readable credit balances and history (text file I/O)
 * The .dat file is the real store; the text files are exports for people to read.
 */
public class DataStore {
    private static final String STATE_FILE = "skillexchange.dat";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path dir;

    public DataStore(Path dir) {
        this.dir = dir;
    }

    public Path getStateFile() { return dir.resolve(STATE_FILE); }

    public boolean exists() { return Files.exists(getStateFile()); }

    // ---------- Serialisation ----------

    /**
     * Writes to a temp file first, then renames it over the real file.
     * If the program crashes mid-save, the previous save stays intact.
     * Synchronized because the web server can save from several request threads at once,
     * and they all share the same temp file.
     */
    public synchronized void save(BookingService service) throws PersistenceException {
        Path target = getStateFile();
        Path temp = dir.resolve(STATE_FILE + ".tmp");
        try {
            Files.createDirectories(dir);
            try (ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeObject(service);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new PersistenceException("Could not save data to " + target, e);
        }
    }

    public BookingService load() throws PersistenceException {
        Path source = getStateFile();
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(source)))) {
            return (BookingService) in.readObject();
        } catch (InvalidClassException e) {
            throw new PersistenceException("Saved data is from an incompatible version: " + source, e);
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            throw new PersistenceException("Could not load data from " + source, e);
        }
    }

    /** Loads saved data if there is any, otherwise returns an empty service. */
    public BookingService loadOrCreate() throws PersistenceException {
        return exists() ? load() : new BookingService();
    }

    /** Deletes every file in the data folder. */
    public void reset() throws PersistenceException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) Files.delete(f);
        } catch (IOException e) {
            throw new PersistenceException("Could not clear " + dir, e);
        }
    }

    // ---------- Text exports ----------

    public synchronized Path exportSessionHistory(BookingService service) throws PersistenceException {
        Path file = dir.resolve("sessions.csv");
        try {
            Files.createDirectories(dir);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                w.println("booking_id,learner,tutor,skill,type,price,start,end,status,booked_at");
                for (Booking b : service.getAllBookings()) {
                    TimeSlot s = b.getSlot();
                    Listing l = s.getListing();
                    w.println(String.join(",",
                            csv(b.getId()), csv(b.getLearner().getName()), csv(s.getTutor().getName()),
                            csv(l.getSkill()), csv(l.getType()), csv(l.getPriceDescription()),
                            csv(s.getStart().format(TS)), csv(s.getEnd().format(TS)),
                            csv(b.getStatus().name()), csv(b.getBookedAt().format(TS))));
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("Could not write " + file, e);
        }
        return file;
    }

    public synchronized Path exportBalances(BookingService service) throws PersistenceException {
        Path file = dir.resolve("balances.txt");
        CreditLedger ledger = service.getLedger();
        try {
            Files.createDirectories(dir);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                w.println("Credit balances");
                w.println("===============");
                for (Student st : service.getAllStudents()) {
                    w.printf("%s: %d credits%n", st, ledger.getBalance(st));
                    for (CreditTransaction tx : ledger.getHistory(st)) {
                        w.printf("    %s  %s%n", tx.getTimestamp().format(TS), tx);
                    }
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("Could not write " + file, e);
        }
        return file;
    }

    /** Wraps a value in quotes if it contains a comma, quote or newline (standard CSV escaping). */
    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
