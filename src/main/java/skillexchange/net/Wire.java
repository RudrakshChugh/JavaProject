package skillexchange.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Turns an object into bytes and back, by object serialization. Used where a message travels as
 * the body of an HTTP request instead of down an open socket.
 */
public final class Wire {

    private Wire() { }

    public static byte[] toBytes(Object object) throws IOException {
        // A byte stream that writes into memory instead of a file.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(object);
        }
        return bytes.toByteArray();
    }

    /** Reads one object. {@code filter} limits which classes may be created; null means no limit. */
    public static Object fromBytes(byte[] data, ObjectInputFilter filter) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            if (filter != null) in.setObjectInputFilter(filter);
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("The other side sent a class this program does not know", e);
        }
    }
}
