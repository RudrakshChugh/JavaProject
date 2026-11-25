package skillexchange.service;

import skillexchange.exception.AuthException;
import skillexchange.exception.BookingConflictException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.model.Conversation;
import skillexchange.model.Listing;
import skillexchange.model.Message;
import skillexchange.model.Student;
import skillexchange.persistence.ChatDao;
import skillexchange.persistence.Database;
import skillexchange.persistence.ListingDao;
import skillexchange.validation.Validator;

import java.util.List;
import java.util.Optional;

/**
 * Chat between a learner and the tutor of a listing, so they can agree what will be taught,
 * how, and where. A conversation belongs to exactly two students, and nobody else can read it.
 */
public final class ChatService {
    private final Database db;
    private final AppClock clock;
    private final ChatDao chats;
    private final ListingDao listings;

    ChatService(Database db, AppClock clock, ChatDao chats, ListingDao listings) {
        this.db = db;
        this.clock = clock;
        this.chats = chats;
        this.listings = listings;
    }

    /** The learner's conversation with the tutor of a listing. Created the first time it is opened. */
    public synchronized Conversation open(Student learner, long listingId) throws SkillExchangeException {
        return db.inTransaction(c -> {
            Listing listing = listings.require(c, listingId);
            if (listing.getTutor().equals(learner))
                throw new BookingConflictException("This is your own listing. Learners will message you about it.");
            Optional<Conversation> existing = chats.find(c, listingId, learner.getId());
            if (existing.isPresent()) return existing.get();
            return chats.require(c, chats.insert(c, listingId, learner.getId(), clock.now()));
        });
    }

    public List<Conversation> conversationsOf(Student student) {
        return db.query(c -> chats.findInvolving(c, student.getId()));
    }

    public List<Message> messages(Student reader, long conversationId) throws SkillExchangeException {
        return db.inTransaction(c -> {
            requireParticipant(chats.require(c, conversationId), reader);
            return chats.messages(c, conversationId);
        });
    }

    /** Stores a message and returns it together with the conversation, so the server knows whom to notify. */
    public Sent send(Student sender, long conversationId, String text) throws SkillExchangeException {
        String body = Validator.message(text);
        return db.inTransaction(c -> {
            Conversation conversation = requireParticipant(chats.require(c, conversationId), sender);
            Message message = chats.insertMessage(c, conversationId, sender.getId(), body, clock.now());
            return new Sent(message, conversation);
        });
    }

    /** The result of {@link #send}: two values returned together. */
    public record Sent(Message message, Conversation conversation) { }

    private static Conversation requireParticipant(Conversation conversation, Student student) throws AuthException {
        if (!conversation.involves(student))
            throw new AuthException("This conversation belongs to other students");
        return conversation;
    }
}
