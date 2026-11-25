package skillexchange.server;

import skillexchange.api.Event;
import skillexchange.api.Event.Topic;
import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.AuthException;
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
import skillexchange.service.ChatService;
import skillexchange.service.RaceDemo;
import skillexchange.service.Services;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The server's implementation of the API, one object per client connection. It remembers who is
 * signed in on that connection, so every action is done as that student: a client cannot act for
 * someone else by sending a different id, because no method takes "who am I" as a parameter.
 *
 * It contains no business rules. Each method passes the work to a service and then tells the
 * other clients what changed.
 */
final class ApiSession implements SkillExchangeApi {
    private static final Logger LOG = Logger.getLogger(ApiSession.class.getName());

    // One permit: only one concurrency demonstration may run on the server at a time.
    private static final Semaphore RACE_PERMIT = new Semaphore(1);

    private final Services services;
    private final EventHub hub;
    private final EventHub.Sink connection;
    private volatile Student user;      // written by this connection's thread, read by others for logging

    ApiSession(Services services, EventHub hub, EventHub.Sink connection) {
        this.services = services;
        this.hub = hub;
        this.connection = connection;
    }

    boolean isSignedIn() {
        return user != null;
    }

    String describeUser() {
        Student u = user;
        return u == null ? "guest" : u.getName();
    }

    /** Called when the connection closes. */
    void close() {
        Student u = user;
        if (u != null) hub.signOut(u.getId(), connection);
        user = null;
    }

    private Student me() throws AuthException {
        Student u = user;
        if (u == null) throw new AuthException("Sign in first");
        return u;
    }

    private void changed(Topic... topics) {
        for (Topic topic : topics) {
            hub.toEveryone(new Event.DataChanged(topic));
        }
    }

    // ---------- Open to everyone ----------

    @Override
    public Stats stats() {
        return services.bookings().stats();
    }

    @Override
    public Student register(String name, String email, char[] password) throws SkillExchangeException {
        Student created = services.accounts().register(name, email, password);
        signIn(created);
        changed(Topic.SESSIONS);
        return created;
    }

    @Override
    public Student login(String email, char[] password) throws SkillExchangeException {
        Student found = services.accounts().login(email, password);
        signIn(found);
        return found;
    }

    private void signIn(Student student) {
        close();                                    // a connection holds one student at a time
        user = student;
        hub.signIn(student.getId(), connection);
    }

    @Override
    public void logout() {
        close();
    }

    @Override
    public RaceReport raceDemo() throws SkillExchangeException {
        if (!RACE_PERMIT.tryAcquire())
            throw new SkillExchangeException("Another demonstration is running right now. Try again in a moment.");
        try {
            return RaceDemo.run();
        } catch (SkillExchangeException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "The concurrency demonstration failed", e);
            throw new SkillExchangeException("The demonstration could not run on the server", e);
        } finally {
            RACE_PERMIT.release();                  // give the permit back whatever happened
        }
    }

    // ---------- Finding and booking sessions ----------

    @Override
    public List<Activity> recentActivity() {
        return services.bookings().recentActivity();
    }

    @Override
    public TutorProfile tutorProfile(long studentId) throws SkillExchangeException {
        return services.bookings().profile(studentId);
    }

    @Override
    public List<TimeSlot> openSlots(String query, ListingType type) {
        // The meeting link or room is only for the two people in a booking, so it is removed here.
        return services.bookings().openSlots(query, type).stream().map(TimeSlot::withoutPlace).toList();
    }

    @Override
    public Booking book(long slotId) throws SkillExchangeException {
        Booking booking = services.bookings().book(me(), slotId);
        changed(Topic.SESSIONS, Topic.CREDITS);
        return booking;
    }

    @Override
    public void cancel(long bookingId) throws SkillExchangeException {
        services.bookings().cancel(me(), bookingId);
        changed(Topic.SESSIONS, Topic.CREDITS);
    }

    @Override
    public void complete(long bookingId) throws SkillExchangeException {
        services.bookings().complete(me(), bookingId);
        changed(Topic.SESSIONS, Topic.CREDITS);
    }

    @Override
    public List<Booking> myBookings() throws SkillExchangeException {
        return services.bookings().bookingsOf(me());
    }

    @Override
    public CreditSummary credits() throws SkillExchangeException {
        return services.bookings().credits(me());
    }

    // ---------- Teaching ----------

    @Override
    public Listing createListing(ListingType type, String skill, String description, int durationMinutes,
                                 double fee, int creditCost) throws SkillExchangeException {
        Listing listing = services.bookings().createListing(me(), type, skill, description, durationMinutes, fee, creditCost);
        changed(Topic.SESSIONS);
        return listing;
    }

    @Override
    public void deleteListing(long listingId) throws SkillExchangeException {
        services.bookings().deleteListing(me(), listingId);
        changed(Topic.SESSIONS, Topic.MODULES);
    }

    @Override
    public List<Listing> myListings() throws SkillExchangeException {
        return services.bookings().listingsOf(me());
    }

    @Override
    public TimeSlot addSlot(long listingId, LocalDateTime start, SessionMode mode, String place) throws SkillExchangeException {
        TimeSlot slot = services.bookings().addSlot(me(), listingId, start, mode, place);
        changed(Topic.SESSIONS);
        return slot;
    }

    @Override
    public void removeSlot(long slotId) throws SkillExchangeException {
        services.bookings().removeSlot(me(), slotId);
        changed(Topic.SESSIONS);
    }

    @Override
    public List<TimeSlot> mySlots() throws SkillExchangeException {
        return services.bookings().slotsOf(me());
    }

    // ---------- Chat ----------

    @Override
    public Conversation openConversation(long listingId) throws SkillExchangeException {
        return services.chat().open(me(), listingId);
    }

    @Override
    public Conversation openBookingConversation(long bookingId) throws SkillExchangeException {
        Student me = me();
        Booking booking = services.bookings().findBooking(bookingId)
                .filter(b -> b.involves(me))
                .orElseThrow(() -> new AuthException("That booking is not yours"));
        return services.chat().open(booking.getLearner(), booking.getSlot().getListing().getId());
    }

    @Override
    public List<Conversation> conversations() throws SkillExchangeException {
        return services.chat().conversationsOf(me());
    }

    @Override
    public List<Message> messages(long conversationId) throws SkillExchangeException {
        return services.chat().messages(me(), conversationId);
    }

    @Override
    public Message sendMessage(long conversationId, String text) throws SkillExchangeException {
        ChatService.Sent sent = services.chat().send(me(), conversationId, text);
        Conversation c = sent.conversation();
        // Pushed to both people, on every window they have open, the moment it is stored.
        hub.toStudents(new Event.NewMessage(sent.message()), c.learner().getId(), c.tutor().getId());
        return sent.message();
    }

    // ---------- Learning modules ----------

    @Override
    public List<ModuleView> modules(String query) throws SkillExchangeException {
        return services.learning().modulesFor(me(), query);
    }

    @Override
    public List<Question> questions(long moduleId) throws SkillExchangeException {
        me();
        return services.learning().questions(moduleId);
    }

    @Override
    public AttemptResult submitAnswers(long moduleId, Map<Long, String> answers) throws SkillExchangeException {
        AttemptResult result = services.learning().submit(me(), moduleId, answers);
        changed(Topic.MODULES);
        return result;
    }

    @Override
    public List<LearningModule> myModules() throws SkillExchangeException {
        return services.learning().modulesOf(me());
    }

    @Override
    public LearningModule createModule(long listingId, ModuleDraft draft) throws SkillExchangeException {
        LearningModule module = services.learning().createModule(me(), listingId, draft);
        changed(Topic.MODULES);
        return module;
    }

    @Override
    public void deleteModule(long moduleId) throws SkillExchangeException {
        services.learning().deleteModule(me(), moduleId);
        changed(Topic.MODULES);
    }

    @Override
    public List<ScoreRow> moduleScores(long moduleId) throws SkillExchangeException {
        return services.learning().scores(me(), moduleId);
    }
}
