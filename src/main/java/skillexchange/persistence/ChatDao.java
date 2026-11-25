package skillexchange.persistence;

import skillexchange.model.Conversation;
import skillexchange.model.Message;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Data Access Object for the conversations and messages tables. */
public final class ChatDao extends Dao implements Repository<Conversation> {

    // The two sub-queries fetch the newest message of the conversation, for the list on the left of the chat screen.
    private static final String COLS = "cv.id AS conversation_id, cv.created_at AS conversation_created, "
            + "(SELECT m.body FROM messages m WHERE m.conversation_id = cv.id ORDER BY m.id DESC LIMIT 1) AS last_text, "
            + "(SELECT m.sent_at FROM messages m WHERE m.conversation_id = cv.id ORDER BY m.id DESC LIMIT 1) AS last_at, "
            + StudentDao.cols("le", "learner") + ", " + ListingDao.COLS;
    private static final String FROM = " FROM conversations cv JOIN students le ON le.id = cv.learner_id"
            + " JOIN listings l ON l.id = cv.listing_id JOIN students t ON t.id = l.tutor_id";

    private static Conversation map(ResultSet rs) throws SQLException {
        LocalDateTime last = time(rs, "last_at");
        return new Conversation(rs.getLong("conversation_id"), ListingDao.map(rs), StudentDao.map(rs, "learner"),
                rs.getString("last_text"), last != null ? last : time(rs, "conversation_created"));
    }

    @Override
    public String entityName() { return "conversation"; }

    @Override
    public Optional<Conversation> findById(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE cv.id = ?", ChatDao::map, id);
    }

    @Override
    public List<Conversation> findAll(Connection c) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " ORDER BY cv.id", ChatDao::map);
    }

    public Optional<Conversation> find(Connection c, long listingId, long learnerId) throws SQLException {
        return one(c, "SELECT " + COLS + FROM + " WHERE cv.listing_id = ? AND cv.learner_id = ?", ChatDao::map,
                listingId, learnerId);
    }

    /** Conversations the student takes part in, as learner or as tutor, most recently active first. */
    public List<Conversation> findInvolving(Connection c, long studentId) throws SQLException {
        return list(c, "SELECT " + COLS + FROM + " WHERE cv.learner_id = ? OR l.tutor_id = ?"
                + " ORDER BY COALESCE(last_at, cv.created_at) DESC", ChatDao::map, studentId, studentId);
    }

    public long insert(Connection c, long listingId, long learnerId, LocalDateTime at) throws SQLException {
        return insert(c, "INSERT INTO conversations (listing_id, learner_id, created_at) VALUES (?, ?, ?)",
                listingId, learnerId, at);
    }

    private static final String MESSAGE_SELECT = "SELECT m.id, m.conversation_id, m.body, m.sent_at, "
            + StudentDao.cols("sn", "sender") + " FROM messages m JOIN students sn ON sn.id = m.sender_id";

    private static Message mapMessage(ResultSet rs) throws SQLException {
        return new Message(rs.getLong("id"), rs.getLong("conversation_id"), StudentDao.map(rs, "sender"),
                rs.getString("body"), time(rs, "sent_at"));
    }

    public List<Message> messages(Connection c, long conversationId) throws SQLException {
        return list(c, MESSAGE_SELECT + " WHERE m.conversation_id = ? ORDER BY m.id", ChatDao::mapMessage, conversationId);
    }

    public Message insertMessage(Connection c, long conversationId, long senderId, String body, LocalDateTime at) throws SQLException {
        long id = insert(c, "INSERT INTO messages (conversation_id, sender_id, body, sent_at) VALUES (?, ?, ?, ?)",
                conversationId, senderId, body, at);
        return one(c, MESSAGE_SELECT + " WHERE m.id = ?", ChatDao::mapMessage, id).orElseThrow();
    }
}
