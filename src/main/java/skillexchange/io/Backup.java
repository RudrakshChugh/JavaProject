package skillexchange.io;

import skillexchange.model.Booking;
import skillexchange.model.Listing;
import skillexchange.model.Student;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A snapshot of the main data, written to one file by object serialization.
 * The server console's "backup" command uses it.
 */
public final class Backup {

    private Backup() { }

    /**
     * What a backup file holds. The lists refer to the same Student objects many times (a student
     * is in the student list, is the tutor of listings and the learner of bookings). Serialization
     * writes each object once and keeps the references, so the object graph comes back as it was.
     */
    public record Snapshot(LocalDateTime takenAt, List<Student> students, List<Listing> listings,
                           List<Booking> bookings) implements Serializable { }

    /** Writes to a temporary file first and then moves it into place, so a crash never leaves half a backup. */
    public static void write(Path target, Snapshot snapshot) throws IOException {
        if (target.toAbsolutePath().getParent() != null) Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        // Byte streams for binary data: an ObjectOutputStream over a BufferedOutputStream.
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeObject(snapshot);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Snapshot read(Path file) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return (Snapshot) in.readObject();     // readObject() returns Object, so a cast is needed
        } catch (ClassNotFoundException | ClassCastException e) {
            throw new IOException(file + " is not a Skill Exchange backup", e);
        }
    }
}
