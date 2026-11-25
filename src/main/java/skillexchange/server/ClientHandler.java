package skillexchange.server;

import skillexchange.api.Event;
import skillexchange.api.Open;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.AuthException;
import skillexchange.exception.DataAccessException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.net.Frame;
import skillexchange.service.Services;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves one client connection on its own thread: reads a call, runs it, writes the reply, repeat.
 *
 * A call names an API method. The method is looked up and invoked by reflection, so adding a
 * method to {@link SkillExchangeApi} is all it takes to make it callable from the client.
 */
final class ClientHandler implements Runnable, EventHub.Sink {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    /** API methods by name, built once when the class is loaded (a static initialiser block). */
    private static final Map<String, Method> METHODS = new HashMap<>();
    static {
        for (Method m : SkillExchangeApi.class.getMethods()) {
            if (METHODS.put(m.getName(), m) != null)
                throw new IllegalStateException("API method names must be unique: " + m.getName());
        }
    }

    /**
     * Deserialization only builds objects of the classes listed here. A stranger who connects
     * cannot make the server create anything else. "!*" at the end rejects every other class.
     */
    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=40;maxarray=10000;skillexchange.**;java.lang.*;java.util.*;java.time.*;!*");

    private final Socket socket;
    private final EventHub hub;
    private final ApiSession session;
    private final Runnable onClose;
    private ObjectOutputStream out;

    ClientHandler(Socket socket, Services services, EventHub hub, Runnable onClose) {
        this.socket = socket;
        this.hub = hub;
        this.session = new ApiSession(services, hub, this);
        this.onClose = onClose;
    }

    @Override
    public void run() {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        LOG.fine(() -> "Client connected from " + peer);
        try (socket) {
            // The output stream must be created and flushed first: the other side's ObjectInputStream
            // waits for its header before its constructor returns.
            out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.flush();
            ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
            in.setObjectInputFilter(FILTER);
            hub.connect(this);

            while (true) {
                Object received = in.readObject();
                if (!(received instanceof Frame.Call call))
                    throw new IOException("Expected a call, got " + received.getClass().getName());
                send(handle(call));
            }
        } catch (EOFException | SocketException e) {
            LOG.fine(() -> "Client " + peer + " (" + session.describeUser() + ") disconnected");
        } catch (IOException | ClassNotFoundException e) {
            LOG.log(Level.WARNING, "Connection to " + peer + " closed after an error", e);
        } finally {
            hub.disconnect(this);
            session.close();
            onClose.run();
        }
    }

    /** Runs one API call and wraps the result, or the exception it threw, in a reply. */
    private Frame.Reply handle(Frame.Call call) {
        Method method = METHODS.get(call.method());
        try {
            if (method == null)
                throw new SkillExchangeException("The server has no operation called " + call.method());
            // Reflection on our own annotation: only methods marked @Open may run before signing in.
            if (!method.isAnnotationPresent(Open.class) && !session.isSignedIn())
                throw new AuthException("Sign in first");
            Object value = method.invoke(session, call.args() == null ? new Object[0] : call.args());
            return new Frame.Reply(call.id(), value, null);
        } catch (InvocationTargetException e) {
            // invoke() wraps whatever the method threw; getCause() is the real exception.
            return new Frame.Reply(call.id(), null, forClient(call, e.getCause()));
        } catch (SkillExchangeException e) {
            return new Frame.Reply(call.id(), null, forClient(call, e));
        } catch (IllegalArgumentException | IllegalAccessException e) {
            LOG.log(Level.WARNING, "Malformed call to " + call.method(), e);
            return new Frame.Reply(call.id(), null, new SkillExchangeException("The request was not understood"));
        }
    }

    /**
     * Decides what the client gets to see. A broken rule travels as it is, so the client can show
     * its message. Anything unexpected is logged here in full and replaced by a plain message.
     */
    private Throwable forClient(Frame.Call call, Throwable thrown) {
        if (thrown instanceof SkillExchangeException rule) {
            LOG.fine(() -> session.describeUser() + " -> " + call.method() + " refused: " + rule.getMessage());
            rule.setStackTrace(new StackTraceElement[0]);       // the client has no use for the server's stack
            return rule;
        }
        Level level = thrown instanceof DataAccessException ? Level.SEVERE : Level.WARNING;
        LOG.log(level, "Unexpected error in " + call.method() + " for " + session.describeUser(), thrown);
        return new SkillExchangeException("Something went wrong on the server. It has been logged.");
    }

    /**
     * Writes one frame. Synchronized, because two threads write to this socket: this connection's
     * own thread (replies) and the notifier thread (events). Without it their bytes could interleave.
     */
    private synchronized void send(Frame frame) throws IOException {
        out.writeObject(frame);
        out.reset();        // forget what was sent, so an object that changes later is sent afresh
        out.flush();
    }

    @Override
    public void push(Event event) throws IOException {
        if (out == null) return;
        send(new Frame.Push(event));
    }

    void closeQuietly() {
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing a client socket failed", e);
        }
    }
}
