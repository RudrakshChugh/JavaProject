package skillexchange.net;

import skillexchange.api.Event;

import java.io.Serializable;

/**
 * One object sent over the socket. Client and server talk by writing these with an
 * ObjectOutputStream and reading them with an ObjectInputStream (object serialization).
 */
public sealed interface Frame extends Serializable {

    /** Client to server: "run this API method with these arguments". The id pairs it with its reply. */
    record Call(long id, String method, Object[] args) implements Frame { }

    /** Server to client: the result of a call, or the exception it threw. */
    record Reply(long id, Object value, Throwable error) implements Frame { }

    /** Server to client, unasked: something changed. */
    record Push(Event event) implements Frame { }
}
