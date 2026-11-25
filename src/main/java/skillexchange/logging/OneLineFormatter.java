package skillexchange.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * A custom log Formatter: one line per record, "time LEVEL [thread] Class: message", followed by
 * the stack trace if the record carries an exception.
 */
public final class OneLineFormatter extends Formatter {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
        String simpleName = logger.substring(logger.lastIndexOf('.') + 1);
        StringBuilder line = new StringBuilder()
                .append(TIME.format(Instant.ofEpochMilli(record.getMillis())))
                .append(' ').append(String.format("%-7s", record.getLevel().getName()))
                .append(" [").append(Thread.currentThread().getName()).append("] ")
                .append(simpleName).append(": ")
                .append(formatMessage(record))
                .append(System.lineSeparator());
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace, true));
            line.append(trace);
        }
        return line.toString();
    }
}
