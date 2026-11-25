package skillexchange.server;

import skillexchange.api.Open;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.AuthException;
import skillexchange.exception.DataAccessException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.net.Frame;

import java.io.ObjectInputFilter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs one API call for a client, whichever way the call arrived (socket or HTTP).
 *
 * A call names an API method. The method is looked up and invoked by reflection, so adding a
 * method to {@link SkillExchangeApi} is all it takes to make it callable from the client.
 */
final class Dispatcher {
    private static final Logger LOG = Logger.getLogger(Dispatcher.class.getName());

    /** API methods by name, built once when the class is loaded (a static initialiser block). */
    private static final Map<String, Method> METHODS = new HashMap<>();
    static {
        for (Method m : SkillExchangeApi.class.getMethods()) {
            if (METHODS.put(m.getName(), m) != null)
                throw new IllegalStateException("API method names must be unique: " + m.getName());
        }
    }

    /**
     * Deserialization only builds objects of the classes listed here, and only so many of them.
     * A stranger who connects cannot make the server create anything else. "!*" at the end
     * rejects every other class.
     */
    static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=40;maxarray=10000;maxrefs=20000;skillexchange.**;java.lang.*;java.util.*;java.time.*;!*");

    private Dispatcher() { }

    /** Runs the call and wraps the result, or the exception it threw, in a reply. */
    static Frame.Reply handle(ApiSession session, Frame.Call call) {
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
            return new Frame.Reply(call.id(), null, forClient(session, call, e.getCause()));
        } catch (SkillExchangeException e) {
            return new Frame.Reply(call.id(), null, forClient(session, call, e));
        } catch (IllegalArgumentException | IllegalAccessException e) {
            LOG.log(Level.WARNING, "Malformed call to " + call.method(), e);
            return new Frame.Reply(call.id(), null, new SkillExchangeException("The request was not understood"));
        }
    }

    /**
     * Decides what the client gets to see. A broken rule travels as it is, so the client can show
     * its message. Anything unexpected is logged here in full and replaced by a plain message.
     */
    private static Throwable forClient(ApiSession session, Frame.Call call, Throwable thrown) {
        if (thrown instanceof SkillExchangeException rule) {
            LOG.fine(() -> session.describeUser() + " -> " + call.method() + " refused: " + rule.getMessage());
            rule.setStackTrace(new StackTraceElement[0]);       // the client has no use for the server's stack
            return rule;
        }
        Level level = thrown instanceof DataAccessException ? Level.SEVERE : Level.WARNING;
        LOG.log(level, "Unexpected error in " + call.method() + " for " + session.describeUser(), thrown);
        return new SkillExchangeException("Something went wrong on the server. It has been logged.");
    }
}
