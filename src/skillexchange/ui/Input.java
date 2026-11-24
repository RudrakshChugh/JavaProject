package skillexchange.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Scanner;

/** Reads and validates console input, re-prompting until the value is valid. */
class Input {
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // For reading: STRICT refuses dates that do not exist. The default would quietly turn
    // "2027-02-31" into 28 February. ("uuuu" is the year pattern that STRICT mode needs.)
    private static final DateTimeFormatter STRICT_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_DAYS_AHEAD = 365;

    /** Thrown when stdin closes (Ctrl+Z / Ctrl+D or end of piped input). */
    static class EndOfInputException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Scanner scanner;

    Input(Scanner scanner) {
        this.scanner = scanner;
    }

    /** May return an empty string. */
    String line(String prompt) {
        System.out.print(prompt);
        if (!scanner.hasNextLine()) throw new EndOfInputException();
        String s = scanner.nextLine();
        if (s.startsWith("﻿")) s = s.substring(1);   // byte-order mark from some piped input
        return s.trim();
    }

    String required(String prompt) {
        while (true) {
            String s = line(prompt);
            if (!s.isEmpty()) return s;
            System.out.println("  Please enter a value.");
        }
    }

    int intInRange(String prompt, int min, int max) {
        while (true) {
            String s = line(prompt);
            try {
                int n = Integer.parseInt(s);
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException ignored) { }
            System.out.println("  Enter a whole number from " + min + " to " + max + ".");
        }
    }

    double positiveDouble(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                double d = Double.parseDouble(s);
                if (d > 0 && !Double.isInfinite(d)) return d;
            } catch (NumberFormatException ignored) { }
            System.out.println("  Enter a number greater than 0.");
        }
    }

    LocalDateTime futureDateTime(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                LocalDateTime t = LocalDateTime.parse(s, STRICT_DATE_TIME);
                LocalDateTime now = LocalDateTime.now();
                if (!t.isAfter(now)) System.out.println("  That time is in the past.");
                else if (t.isAfter(now.plusDays(MAX_DAYS_AHEAD)))
                    System.out.println("  Pick a time within the next " + MAX_DAYS_AHEAD + " days.");
                else return t;
            } catch (DateTimeParseException e) {
                System.out.println("  Enter a real date as yyyy-MM-dd HH:mm, e.g. 2026-11-05 14:30");
            }
        }
    }

    /**
     * Reads a password. In a real terminal the typing is hidden; when input is piped in
     * (or inside an IDE, where there is no console) it is read as an ordinary line.
     */
    char[] password(String prompt) {
        java.io.Console console = System.console();
        if (console != null) {
            char[] typed = console.readPassword(prompt);
            if (typed == null) throw new EndOfInputException();
            return typed;
        }
        return line(prompt).toCharArray();
    }

    boolean yesNo(String prompt) {
        while (true) {
            String s = line(prompt + " (y/n): ").toLowerCase();
            if (s.equals("y") || s.equals("yes")) return true;
            if (s.equals("n") || s.equals("no")) return false;
        }
    }
}
