package skillexchange.web;

import skillexchange.api.SkillExchangeApi;
import skillexchange.model.Student;

/**
 * One browser's session on the web site. It holds the same kind of API object a desktop client
 * talks to, so a visitor in a browser is just another client of the same server.
 */
final class Visit {
    final String id;
    /** A secret that every form sends back. A request without it did not come from our own page. */
    final String csrf;
    final SkillExchangeApi api;
    // volatile: a browser can send two requests at once, handled by two threads.
    volatile Student me;
    volatile long lastSeen = System.currentTimeMillis();
    private String flash;
    private boolean flashIsError;

    Visit(String id, String csrf, SkillExchangeApi api) {
        this.id = id;
        this.csrf = csrf;
        this.api = api;
    }

    boolean signedIn() {
        return me != null;
    }

    boolean isMe(Student other) {
        Student current = me;
        return current != null && current.equals(other);
    }

    /** Leaves a message to be shown once on the next page, after a redirect. */
    synchronized void flash(String text, boolean error) {
        flash = text;
        flashIsError = error;
    }

    /** The waiting message as {text, "err" or "ok"}, or null. Reading it removes it. */
    synchronized String[] takeFlash() {
        if (flash == null) return null;
        String[] taken = {flash, flashIsError ? "err" : "ok"};
        flash = null;
        return taken;
    }
}
