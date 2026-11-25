package skillexchange.exception;

/** A module file could not be understood. Carries the line number so the tutor can fix the file. */
public class ModuleFormatException extends SkillExchangeException {
    private static final long serialVersionUID = 1L;

    private final int line;

    public ModuleFormatException(int line, String message) {
        super("Line " + line + ": " + message);
        this.line = line;
    }

    public int getLine() { return line; }
}
