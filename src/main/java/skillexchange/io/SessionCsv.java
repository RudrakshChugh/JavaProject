package skillexchange.io;

import skillexchange.model.Booking;
import skillexchange.model.Student;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes a student's session history as a CSV file that opens in a spreadsheet. */
public final class SessionCsv {

    private SessionCsv() { }

    public static void write(Path file, Student me, List<Booking> bookings) throws IOException {
        // Character streams for text: a PrintWriter over a BufferedWriter, written as UTF-8.
        try (BufferedWriter buffered = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(buffered)) {
            out.println("booking_id,role,skill,with,type,price,start,end,mode,place,status,booked_at");
            for (Booking b : bookings) {
                out.println(String.join(",",
                        String.valueOf(b.getId()),
                        b.isLearner(me) ? "learner" : "tutor",
                        csv(b.getSlot().getListing().getSkill()),
                        csv(b.otherParty(me).getName()),
                        b.getSlot().getListing().getType().name(),
                        csv(b.getSlot().getListing().getPriceDescription()),
                        b.getSlot().getStart().toString(),
                        b.getSlot().getEnd().toString(),
                        b.getSlot().getMode().name(),
                        csv(b.getSlot().getPlace()),
                        b.getStatus().name(),
                        b.getBookedAt().toString()));
            }
            if (out.checkError()) throw new IOException("Could not write " + file);
        }
    }

    /** Standard CSV escaping: a value with a comma, quote or line break is quoted, and quotes are doubled. */
    static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
