package skillexchange.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

/** Reads and validates console input, re-prompting until the value is valid. */
class Input {
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
                if (d > 0) return d;
            } catch (NumberFormatException ignored) { }
            System.out.println("  Enter a number greater than 0.");
        }
    }

    LocalDateTime futureDateTime(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                LocalDateTime t = LocalDateTime.parse(s, DATE_TIME);
                if (t.isAfter(LocalDateTime.now())) return t;
                System.out.println("  That time is in the past.");
            } catch (DateTimeParseException e) {
                System.out.println("  Use the format yyyy-MM-dd HH:mm, e.g. 2026-11-05 14:30");
            }
        }
    }

    boolean yesNo(String prompt) {
        while (true) {
            String s = line(prompt + " (y/n): ").toLowerCase();
            if (s.equals("y") || s.equals("yes")) return true;
            if (s.equals("n") || s.equals("no")) return false;
        }
    }
}
