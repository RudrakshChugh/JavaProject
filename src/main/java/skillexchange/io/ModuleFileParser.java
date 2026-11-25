package skillexchange.io;

import skillexchange.exception.ModuleFormatException;
import skillexchange.model.Exercise;
import skillexchange.model.Exercise.Kind;
import skillexchange.model.FillBlank;
import skillexchange.model.ModuleDraft;
import skillexchange.model.MultipleChoice;
import skillexchange.model.TrueFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads learning modules from a plain text file, so a tutor can write exercises in any editor
 * and import them. One file may hold several modules. The format, line by line:
 *
 * <pre>
 * # a comment
 * MODULE: Variables and types
 * SUMMARY: Names, numbers and text.
 * MCQ: Which of these is a valid variable name?
 * - 2cool
 * * cool_2            (the line starting with * is the right answer)
 * POINTS: 2           (optional; applies to the question above it, default 1)
 * TF: Python needs semicolons. => false
 * FILL: The function that prints is ____. => print | print()
 * </pre>
 */
public final class ModuleFileParser {

    private ModuleFileParser() { }

    /** Reads a file chosen by the tutor. A character stream, buffered, decoded as UTF-8. */
    public static List<ModuleDraft> parse(Path file) throws IOException, ModuleFormatException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /** Reads a module file that ships inside the application (the demo modules). */
    public static List<ModuleDraft> parseResource(String resource) throws IOException, ModuleFormatException {
        InputStream in = ModuleFileParser.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("Missing resource " + resource);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(reader);
        }
    }

    public static List<ModuleDraft> parse(BufferedReader reader) throws IOException, ModuleFormatException {
        List<ModuleDraft> modules = new ArrayList<>();
        String title = null;
        String summary = "";
        int titleLine = 0;
        List<Pending> questions = new ArrayList<>();
        Pending current = null;

        int lineNo = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;

            if (text.startsWith("- ") || text.startsWith("* ")) {
                if (current == null || current.kind != Kind.MCQ)
                    throw new ModuleFormatException(lineNo, "An option line must come after an MCQ: line");
                if (text.startsWith("* ")) {
                    if (current.correct >= 0) throw new ModuleFormatException(lineNo, "Only one option may start with *");
                    current.correct = current.options.size();
                }
                current.options.add(text.substring(2).strip());
                continue;
            }

            int colon = text.indexOf(':');
            if (colon < 0) throw new ModuleFormatException(lineNo, "Expected a line like \"MCQ: question\"");
            String key = text.substring(0, colon).strip().toUpperCase(Locale.ROOT);
            String value = text.substring(colon + 1).strip();
            if (!key.equals("MODULE") && title == null)
                throw new ModuleFormatException(lineNo, "The file must begin with a MODULE: line");

            switch (key) {
                case "MODULE" -> {
                    if (title != null) modules.add(build(title, summary, questions, titleLine));
                    if (value.isEmpty()) throw new ModuleFormatException(lineNo, "MODULE: needs a title");
                    title = value;
                    titleLine = lineNo;
                    summary = "";
                    questions = new ArrayList<>();
                    current = null;
                }
                case "SUMMARY" -> summary = value;
                case "MCQ" -> {
                    current = new Pending(Kind.MCQ, value, lineNo);
                    questions.add(current);
                }
                case "TF", "FILL" -> {
                    int arrow = value.lastIndexOf("=>");
                    if (arrow < 0) throw new ModuleFormatException(lineNo, key + ": needs \"=> answer\" at the end");
                    current = new Pending(key.equals("TF") ? Kind.TRUE_FALSE : Kind.FILL, value.substring(0, arrow).strip(), lineNo);
                    current.answer = value.substring(arrow + 2).strip();
                    questions.add(current);
                }
                case "POINTS" -> {
                    if (current == null) throw new ModuleFormatException(lineNo, "POINTS: must come after a question");
                    try {
                        current.points = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new ModuleFormatException(lineNo, "POINTS: must be a whole number");
                    }
                }
                default -> throw new ModuleFormatException(lineNo, "Unknown keyword \"" + key
                        + "\". Use MODULE, SUMMARY, MCQ, TF, FILL or POINTS.");
            }
        }
        if (title == null) throw new ModuleFormatException(Math.max(lineNo, 1), "The file has no MODULE: line");
        modules.add(build(title, summary, questions, titleLine));
        return modules;
    }

    private static ModuleDraft build(String title, String summary, List<Pending> questions, int titleLine)
            throws ModuleFormatException {
        if (questions.isEmpty()) throw new ModuleFormatException(titleLine, "Module \"" + title + "\" has no questions");
        List<Exercise> exercises = new ArrayList<>();
        for (Pending p : questions) {
            exercises.add(p.toExercise());
        }
        return new ModuleDraft(title, summary, exercises);
    }

    /** A question while it is still being read. A static nested class: only this parser uses it. */
    private static final class Pending {
        final Kind kind;
        final String prompt;
        final int line;
        final List<String> options = new ArrayList<>();
        int correct = -1;
        String answer = "";
        int points = 1;

        Pending(Kind kind, String prompt, int line) {
            this.kind = kind;
            this.prompt = prompt;
            this.line = line;
        }

        Exercise toExercise() throws ModuleFormatException {
            if (prompt.isEmpty()) throw new ModuleFormatException(line, "The question text is missing");
            switch (kind) {
                case MCQ:
                    if (options.size() < 2) throw new ModuleFormatException(line, "An MCQ needs at least two option lines");
                    if (correct < 0) throw new ModuleFormatException(line, "Mark the right option by starting its line with *");
                    return new MultipleChoice(0, prompt, options, correct, points);
                case TRUE_FALSE:
                    String a = answer.toLowerCase(Locale.ROOT);
                    if (!a.equals("true") && !a.equals("false"))
                        throw new ModuleFormatException(line, "The answer of a TF: question must be true or false");
                    return new TrueFalse(0, prompt, a.equals("true"), points);
                default:
                    List<String> accepted = Arrays.stream(answer.split("\\|")).map(String::strip)
                            .filter(s -> !s.isEmpty()).toList();
                    if (accepted.isEmpty()) throw new ModuleFormatException(line, "A FILL: question needs an answer after =>");
                    return new FillBlank(0, prompt, accepted, points);
            }
        }
    }
}
