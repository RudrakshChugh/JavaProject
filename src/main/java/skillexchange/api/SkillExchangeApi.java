package skillexchange.api;

import skillexchange.exception.SkillExchangeException;
import skillexchange.model.Activity;
import skillexchange.model.AttemptResult;
import skillexchange.model.Booking;
import skillexchange.model.Conversation;
import skillexchange.model.CreditSummary;
import skillexchange.model.LearningModule;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.Message;
import skillexchange.model.ModuleDraft;
import skillexchange.model.ModuleView;
import skillexchange.model.Question;
import skillexchange.model.RaceReport;
import skillexchange.model.ScoreRow;
import skillexchange.model.SessionMode;
import skillexchange.model.Stats;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.model.TutorProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the client can ask the server to do.
 *
 * The server has a class that implements this interface. The client never sees that class: it gets
 * a proxy object that implements the same interface and sends each call over the network
 * (see {@code net.RemoteApi}). So the JavaFX code simply calls {@code api.book(slotId)}.
 *
 * Method names must be unique (no overloading here), because a call is sent by name.
 */
public interface SkillExchangeApi {

    // ---------- Open to everyone ----------

    @Open Stats stats() throws SkillExchangeException;

    @Open Student register(String name, String email, char[] password) throws SkillExchangeException;

    @Open Student login(String email, char[] password) throws SkillExchangeException;

    @Open void logout() throws SkillExchangeException;

    /** Runs the concurrency demonstration on the server and reports what happened. */
    @Open RaceReport raceDemo() throws SkillExchangeException;

    /** Open sessions that have not started. {@code type} may be null for every type. Without the meeting place. */
    @Open List<TimeSlot> openSlots(String query, ListingType type) throws SkillExchangeException;

    /** The latest bookings, for the ticker on the home page. */
    @Open List<Activity> recentActivity() throws SkillExchangeException;

    /** What a tutor teaches and when they are free. */
    @Open TutorProfile tutorProfile(long studentId) throws SkillExchangeException;

    // ---------- Learning: booking sessions ----------

    Booking book(long slotId) throws SkillExchangeException;

    void cancel(long bookingId) throws SkillExchangeException;

    /** The learner confirms the session took place, which pays any held credits to the tutor. */
    void complete(long bookingId) throws SkillExchangeException;

    /** Bookings in which the signed-in student is the learner or the tutor. */
    List<Booking> myBookings() throws SkillExchangeException;

    CreditSummary credits() throws SkillExchangeException;

    // ---------- Teaching ----------

    Listing createListing(ListingType type, String skill, String description, int durationMinutes,
                          double fee, int creditCost) throws SkillExchangeException;

    void deleteListing(long listingId) throws SkillExchangeException;

    List<Listing> myListings() throws SkillExchangeException;

    TimeSlot addSlot(long listingId, LocalDateTime start, SessionMode mode, String place) throws SkillExchangeException;

    void removeSlot(long slotId) throws SkillExchangeException;

    List<TimeSlot> mySlots() throws SkillExchangeException;

    // ---------- Chat ----------

    /** The learner's conversation with the tutor of a listing. Created on first use. */
    Conversation openConversation(long listingId) throws SkillExchangeException;

    /** The conversation between the learner and the tutor of a booking. Either of them may open it. */
    Conversation openBookingConversation(long bookingId) throws SkillExchangeException;

    List<Conversation> conversations() throws SkillExchangeException;

    List<Message> messages(long conversationId) throws SkillExchangeException;

    Message sendMessage(long conversationId, String text) throws SkillExchangeException;

    // ---------- Learning modules and exercises ----------

    List<ModuleView> modules(String query) throws SkillExchangeException;

    List<Question> questions(long moduleId) throws SkillExchangeException;

    /** Marks the answers (question id to answer text) and records the attempt. */
    AttemptResult submitAnswers(long moduleId, Map<Long, String> answers) throws SkillExchangeException;

    /** Modules of the signed-in tutor's listings, with the answers. */
    List<LearningModule> myModules() throws SkillExchangeException;

    LearningModule createModule(long listingId, ModuleDraft draft) throws SkillExchangeException;

    void deleteModule(long moduleId) throws SkillExchangeException;

    List<ScoreRow> moduleScores(long moduleId) throws SkillExchangeException;
}
