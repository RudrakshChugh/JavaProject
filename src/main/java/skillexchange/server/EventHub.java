package skillexchange.server;

import skillexchange.api.Event;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Remembers which client connections belong to which signed-in student, and delivers events to
 * them: a chat message to its two participants, "data changed" to everyone.
 *
 * Delivery runs on one background thread. So a client thread that causes an event never waits
 * for other clients' sockets, and events reach every client in the order they happened.
 */
final class EventHub {
    private static final Logger LOG = Logger.getLogger(EventHub.class.getName());

    /** Anything an event can be pushed to. A nested interface; ClientHandler implements it. */
    interface Sink {
        void push(Event event) throws IOException;
    }

    private final Set<Sink> everyone = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Sink>> byStudent = new ConcurrentHashMap<>();
    private final ExecutorService notifier = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-notifier");
        t.setDaemon(true);
        return t;
    });

    void connect(Sink sink) {
        everyone.add(sink);
    }

    void disconnect(Sink sink) {
        everyone.remove(sink);
        byStudent.values().forEach(sinks -> sinks.remove(sink));
    }

    void signIn(long studentId, Sink sink) {
        // computeIfAbsent creates the set for a student's first connection, atomically.
        byStudent.computeIfAbsent(studentId, id -> ConcurrentHashMap.newKeySet()).add(sink);
    }

    void signOut(long studentId, Sink sink) {
        Set<Sink> sinks = byStudent.get(studentId);
        if (sinks != null) sinks.remove(sink);
    }

    int connections() {
        return everyone.size();
    }

    /** Sends an event to every connection of the given students. Varargs: any number of ids. */
    void toStudents(Event event, long... studentIds) {
        notifier.execute(() -> {
            for (long id : studentIds) {
                Set<Sink> sinks = byStudent.get(id);
                if (sinks != null) deliver(sinks, event);
            }
        });
    }

    void toEveryone(Event event) {
        notifier.execute(() -> deliver(everyone, event));
    }

    /**
     * Walks the set with an explicit Iterator, because a connection that has gone away must be
     * removed while looping, and Iterator.remove() is the safe way to do that.
     */
    private void deliver(Set<Sink> sinks, Event event) {
        Iterator<Sink> it = sinks.iterator();
        while (it.hasNext()) {
            Sink sink = it.next();
            try {
                sink.push(event);
            } catch (IOException e) {
                LOG.fine(() -> "Dropping a connection that no longer answers: " + e.getMessage());
                it.remove();
            }
        }
    }

    void shutdown() {
        notifier.shutdownNow();
    }
}
