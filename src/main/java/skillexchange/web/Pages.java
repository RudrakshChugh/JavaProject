package skillexchange.web;

import skillexchange.api.SkillExchangeApi;
import skillexchange.exception.SkillExchangeException;
import skillexchange.model.Activity;
import skillexchange.model.AttemptResult;
import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;
import skillexchange.model.Conversation;
import skillexchange.model.CreditSummary;
import skillexchange.model.CreditTransaction;
import skillexchange.model.Exercise;
import skillexchange.model.LearningModule;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.Message;
import skillexchange.model.ModuleView;
import skillexchange.model.Question;
import skillexchange.model.RaceReport;
import skillexchange.model.ScoreRow;
import skillexchange.model.SessionMode;
import skillexchange.model.Stats;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.model.TutorProfile;
import skillexchange.service.DemoData;
import skillexchange.web.WebSite.Page;
import skillexchange.web.WebSite.Request;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static skillexchange.web.Html.avatar;
import static skillexchange.web.Html.badge;
import static skillexchange.web.Html.esc;
import static skillexchange.web.Html.hidden;
import static skillexchange.web.Html.icon;
import static skillexchange.web.Html.plural;
import static skillexchange.web.Html.typeBadge;

/**
 * Every page of the web site, each one a method that asks the API for what it needs and returns
 * the page as HTML. This is server-side rendering: the browser receives finished pages and runs
 * no program of its own. All text that came from a user passes through {@link Html#esc}.
 */
final class Pages {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE");
    private static final DateTimeFormatter LOCAL_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    static final String MODULE_TEMPLATE = """
            MODULE: Title of the module
            SUMMARY: One line about what it covers.

            MCQ: Type a question with several choices here
            - A wrong choice
            * The right choice (its line starts with a star)
            - Another wrong choice

            TF: Type a statement that is true or false. => true

            FILL: The answer to this is ____. => answer | another accepted answer
            POINTS: 2
            """;

    private static final String[][] TABS = {
            {"browse", "/", "Browse"}, {"bookings", "/bookings", "My bookings"}, {"teach", "/teach", "Teach"},
            {"messages", "/messages", "Messages"}, {"learn", "/learn", "Learn"}, {"credits", "/credits", "Credits"},
            {"how", "/how", "How it works"}};

    // ---------- The frame around every page ----------

    /** Wraps a page's body in the document: head, top bar, footer and the message from the last action. */
    String layout(Request rq, Page page) {
        Visit v = rq.visit();
        StringBuilder h = new StringBuilder(8192);
        h.append("<!doctype html>\n<html lang=\"en\" data-theme=\"").append(rq.dark() ? "dark" : "light").append("\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(page.title())).append(" · Skill Exchange</title>\n")
                .append("<meta name=\"description\" content=\"Peer tutoring and skill exchange booking system\">\n")
                .append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n")
                .append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n")
                .append("<link href=\"https://fonts.googleapis.com/css2?family=Nunito:wght@500;600;700;800;900&display=swap\" rel=\"stylesheet\">\n")
                .append("<link rel=\"stylesheet\" href=\"/styles.css\">\n</head>\n<body>\n")
                .append("<a class=\"skip-link\" href=\"#view\">Skip to content</a>\n")
                .append("<header class=\"topbar\"><div class=\"topbar-inner\">")
                .append("<a class=\"brand\" href=\"/\" aria-label=\"Skill Exchange home\"><span>Skill Exchange</span></a>")
                .append("<nav class=\"nav\" aria-label=\"Sections\">");
        for (String[] tab : TABS) {
            h.append("<a href=\"").append(tab[1]).append("\"").append(tab[0].equals(page.tab()) ? " aria-current=\"page\"" : "")
                    .append(">").append(tab[2]).append("</a>");
        }
        h.append("</nav><div class=\"topbar-end\">")
                .append("<form class=\"theme-form\" method=\"post\" action=\"/theme\">").append(hidden(v, rq.here()))
                .append("<button class=\"icon-btn\" aria-label=\"Switch colour theme\" title=\"Switch colour theme\">")
                .append(icon(rq.dark() ? "sun" : "moon")).append("</button></form>");
        Student me = v.me;
        if (me == null) {
            h.append("<a class=\"btn primary sm\" href=\"/signin\">Sign in</a>");
        } else {
            h.append("<a class=\"account-chip\" href=\"/account\" aria-label=\"Your profile\">").append(avatar(me, ""))
                    .append("<span class=\"who\">").append(esc(me.getName())).append("</span>")
                    .append("<span class=\"credit-count\" title=\"Credit balance\">").append(plural(page.credits(), "credit")).append("</span>")
                    .append(icon("chevron")).append("</a>");
        }
        h.append("</div></div></header>\n<main id=\"view\" class=\"container\" tabindex=\"-1\">\n").append(page.body())
                .append("\n</main>\n<footer class=\"footer\"><div class=\"container footer-inner\">")
                .append("<span>Peer Tutoring and Skill Exchange Booking System</span>")
                .append("<span>Java server · JDBC · Pages rendered in Java · No JavaScript</span></div></footer>\n");
        String[] flash = v.takeFlash();
        if (flash != null) {
            h.append("<div class=\"toasts\" role=\"status\"><div class=\"toast flash ").append(flash[1]).append("\">")
                    .append(icon("err".equals(flash[1]) ? "alert" : "check")).append("<p>").append(esc(flash[0])).append("</p><span></span></div></div>\n");
        }
        return h.append("</body>\n</html>\n").toString();
    }

    private static Page page(Request rq, String tab, String title, CharSequence body) throws SkillExchangeException {
        int credits = rq.visit().signedIn() ? rq.visit().api.credits().balance() : 0;
        return new Page(tab, title, body.toString(), credits);
    }

    /** A page that is one centred card: what the first version showed as a pop-up dialog. */
    private static String dialog(String title, CharSequence inner, boolean wide) {
        return "<div class=\"card dialog-card" + (wide ? " wide" : "") + "\"><h2>" + esc(title) + "</h2>" + inner + "</div>";
    }

    Page message(Request rq, String tab, String title, String text) throws SkillExchangeException {
        return page(rq, tab, title, dialog(title, "<p class=\"muted\">" + esc(text) + "</p><div class=\"actions\">"
                + "<a class=\"btn primary\" href=\"/\">Back to Browse</a></div>", false));
    }

    // ---------- Browse ----------

    Page browse(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        SkillExchangeApi api = v.api;
        String q = rq.get("q").strip();
        ListingType type = type(rq.get("type"));
        List<TimeSlot> all = api.openSlots("", null);
        Stats stats = api.stats();
        List<Activity> activity = api.recentActivity();
        int credits = v.signedIn() ? api.credits().balance() : 0;

        StringBuilder h = new StringBuilder();
        h.append("<section class=\"hero\"><div id=\"intro\">").append(intro(v, credits)).append("</div><div class=\"hero-side\"><div class=\"stats\">");
        int[] numbers = {stats.students(), stats.listings(), stats.openSessions(), stats.completedSessions()};
        String[] captions = {"Students", "Skills listed", "Open sessions", "Sessions completed"};
        for (int i = 0; i < numbers.length; i++) {
            h.append("<div class=\"stat\"><b>").append(numbers[i]).append("</b><span>").append(captions[i]).append("</span></div>");
        }
        h.append("</div><div class=\"pulse\" aria-label=\"Recent bookings\">");
        if (activity.isEmpty()) {
            h.append("<span class=\"subtle\">No bookings yet. Yours could be the first.</span>");
        } else {
            Activity a = activity.get(0);
            h.append("<span class=\"pulse-item\">").append(avatar(a.learner(), a.learner().length(), "sm")).append("<span><b>")
                    .append(esc(a.learner())).append("</b> booked <b>").append(esc(a.skill())).append("</b> with ").append(esc(a.tutor()))
                    .append("</span><span class=\"subtle nowrap\">").append(Html.ago(a.at())).append("</span></span>");
        }
        h.append("</div></div></section>");

        // Skills to explore: the open sessions grouped by skill, in the order they first come up.
        Map<String, List<TimeSlot>> bySkill = all.stream()
                .collect(Collectors.groupingBy(s -> s.getListing().getSkill(), LinkedHashMap::new, Collectors.toList()));
        if (!bySkill.isEmpty()) {
            h.append("<section id=\"explore\"><div class=\"section-head\"><h2>Explore skills</h2>")
                    .append("<a class=\"btn sm\" href=\"/surprise\">").append(icon("dice")).append("Surprise me</a></div><div class=\"rail\">");
            bySkill.forEach((skill, slots) -> {
                boolean on = skill.equalsIgnoreCase(q);
                // A Set drops duplicates: each tutor and each type is shown once per skill.
                Set<Student> tutors = slots.stream().map(TimeSlot::getTutor).collect(Collectors.toCollection(LinkedHashSet::new));
                Set<ListingType> types = slots.stream().map(s -> s.getListing().getType()).collect(Collectors.toCollection(LinkedHashSet::new));
                h.append("<a class=\"skill-tile spot").append(on ? " on" : "").append("\" href=\"/?q=").append(on ? "" : Html.url(skill))
                        .append("#open\"><span class=\"row between\"><span class=\"avatars\">");
                tutors.forEach(t -> h.append(avatar(t, "sm")));
                h.append("</span><span class=\"dots\">");
                types.forEach(t -> h.append(typeBadge(t)));
                String next = Html.dayWord(slots.get(0).getStart());
                h.append("</span></span><b>").append(esc(skill)).append("</b><span class=\"meta\">").append(plural(slots.size(), "open session"))
                        .append(" · next ").append(next == null ? "on " + slots.get(0).getStart().format(Html.DAY) : next.toLowerCase())
                        .append("</span></a>");
            });
            h.append("</div></section>");
        }

        // Open sessions, filtered by the search box and the type chips.
        List<TimeSlot> matching = all.stream().filter(s -> matches(s, q)).toList();
        List<TimeSlot> visible = matching.stream().filter(s -> type == null || s.getListing().getType() == type).toList();
        h.append("<section id=\"open\"><div class=\"section-head\"><h2>Open sessions</h2><div class=\"side\"><span class=\"count\">")
                .append(plural(visible.size(), "session")).append("</span></div></div><div class=\"toolbar\">")
                .append("<form class=\"search-form\" method=\"get\" action=\"/#open\"><label class=\"search\">").append(icon("search"))
                .append("<span class=\"sr-only\">Search by skill or tutor</span><input class=\"input\" type=\"search\" name=\"q\" ")
                .append("placeholder=\"Search by skill or tutor\" value=\"").append(esc(q)).append("\"></label>")
                .append("<input type=\"hidden\" name=\"type\" value=\"").append(type == null ? "ALL" : type.name()).append("\">")
                .append("<button class=\"btn sm\">Search</button></form><div class=\"chips\" role=\"group\" aria-label=\"Filter by type\">");
        h.append(chip("All", "/?type=ALL&q=" + Html.url(q) + "#open", type == null, matching.size()));
        for (ListingType t : ListingType.values()) {
            long n = matching.stream().filter(s -> s.getListing().getType() == t).count();
            h.append(chip(t.getLabel(), "/?type=" + t.name() + "&q=" + Html.url(q) + "#open", type == t, (int) n));
        }
        h.append("</div></div><div id=\"results\">");
        if (visible.isEmpty()) {
            h.append(all.isEmpty()
                    ? Html.empty("calendar", "No open sessions right now", "Be the first to offer one.",
                            "<a class=\"btn primary\" href=\"/teach\">Offer a skill</a>")
                    : Html.empty("search", "No sessions match", "Try another skill or tutor name, or clear the filters.",
                            "<a class=\"btn\" href=\"/#open\">Clear filters</a>"));
        }
        // One group of cards per day. The server sends slots soonest first, so the days come out in order.
        Map<LocalDate, List<TimeSlot>> byDay = visible.stream()
                .collect(Collectors.groupingBy(s -> s.getStart().toLocalDate(), LinkedHashMap::new, Collectors.toList()));
        byDay.forEach((day, slots) -> {
            h.append("<h3 class=\"day-head\">").append(Html.dayLabel(day.atStartOfDay())).append("</h3><div class=\"grid\">");
            slots.forEach(s -> h.append(slotCard(v, s, credits)));
            h.append("</div>");
        });
        h.append("</div></section>");
        return new Page("browse", "Browse", h.toString(), credits);
    }

    private static boolean matches(TimeSlot s, String q) {
        String k = q.toLowerCase();
        return k.isEmpty() || s.getListing().getSkill().toLowerCase().contains(k) || s.getTutor().getName().toLowerCase().contains(k)
                || s.getListing().getDescription().toLowerCase().contains(k);
    }

    private static ListingType type(String name) {
        for (ListingType t : ListingType.values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return null;        // "ALL", empty or anything unknown means every type
    }

    private static String chip(String label, String href, boolean on, int count) {
        return "<a class=\"chip" + (on ? " on" : "") + "\" href=\"" + esc(href) + "\"" + (on ? " aria-current=\"true\"" : "") + ">"
                + esc(label) + " <span class=\"n\">" + count + "</span></a>";
    }

    private String intro(Visit v, int credits) throws SkillExchangeException {
        Student me = v.me;
        if (me == null) {
            return "<p class=\"eyebrow\">Peer tutoring for students, by students</p>"
                    + "<h1>Teach what you know. <em>Learn</em> what you don't.</h1>"
                    + "<p class=\"lede\">List a skill, publish the times you are free, and book sessions with each other. "
                    + "Pay a fee, spend credits you earned by teaching, or keep it free.</p><div class=\"cta\">"
                    + "<a class=\"btn primary\" href=\"/signin\">Sign in to book</a><a class=\"btn\" href=\"/how\">How it works</a></div>";
        }
        StringBuilder h = new StringBuilder("<p class=\"eyebrow\">Signed in as ").append(esc(me.getName())).append("</p><h1>Welcome back, <em>")
                .append(esc(me.getName())).append("</em>.</h1><p class=\"lede\">You have ").append(plural(credits, "credit"))
                .append(" to spend on swap sessions. Book a session below, or offer a skill of your own to earn more.</p><div class=\"cta\">")
                .append("<a class=\"btn primary\" href=\"/teach\">").append(icon("plus")).append("Offer a skill</a>")
                .append("<a class=\"btn\" href=\"/bookings\">My bookings</a></div>");
        LocalDateTime now = LocalDateTime.now();
        // The confirmed booking that starts soonest, if there is one.
        v.api.myBookings().stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED && b.getSlot().getStart().isAfter(now))
                .min(Comparator.comparing(b -> b.getSlot().getStart()))
                .ifPresent(next -> {
                    boolean learning = next.isLearner(me);
                    h.append("<a class=\"next-up\" href=\"/bookings\">").append(icon("clock")).append("<span><span class=\"small subtle\">Your next session · ")
                            .append(learning ? "learning" : "teaching").append("</span><b>").append(esc(next.getSlot().getListing().getSkill()))
                            .append(learning ? " with " : " for ").append(esc(next.otherParty(me).getName())).append("</b></span><span class=\"countdown\">")
                            .append(Html.timeLeft(next.getSlot().getStart())).append("</span></a>");
                });
        return h.toString();
    }

    private String slotCard(Visit v, TimeSlot s, int credits) {
        Listing l = s.getListing();
        boolean mine = v.isMe(l.getTutor());
        boolean shortOfCredits = v.signedIn() && !mine && l.getCreditCost() > credits;
        String action;
        if (mine) {
            action = "<button class=\"btn block\" disabled>Your own session</button>";
        } else if (shortOfCredits) {
            action = "<p class=\"short\">You need " + plural(l.getCreditCost(), "credit") + " and have " + credits + ".</p>"
                    + "<button class=\"btn block\" disabled>Not enough credits</button>";
        } else {
            action = "<div class=\"row act\"><form method=\"post\" action=\"/messages/open\">" + hidden(v, "/")
                    + "<input type=\"hidden\" name=\"listing\" value=\"" + l.getId() + "\"><button class=\"icon-btn\" title=\"Message "
                    + esc(l.getTutor().getName()) + "\" aria-label=\"Message " + esc(l.getTutor().getName()) + "\">" + icon("chat") + "</button></form>"
                    + "<a class=\"btn primary\" href=\"/book?slot=" + s.getId() + "\">Book session</a></div>";
        }
        return "<article class=\"card slot spot t-" + l.getType().name().toLowerCase() + "\"><div class=\"row between\">" + typeBadge(l.getType())
                + "<span class=\"price\">" + esc(Html.price(l)) + "</span></div><h3>" + esc(l.getSkill()) + "</h3><p class=\"desc\">"
                + esc(l.getDescription().isBlank() ? "No description yet." : l.getDescription()) + "</p><ul class=\"facts\">"
                + "<li><a class=\"tutor-link\" href=\"/tutor?id=" + l.getTutor().getId() + "\" title=\"See what " + esc(l.getTutor().getName())
                + " teaches\">" + avatar(l.getTutor(), "sm") + "<span class=\"text\">" + esc(l.getTutor().getName()) + "</span></a></li>"
                + "<li>" + icon("clock") + "<span class=\"text\">" + Html.timeRange(s) + " <span class=\"sep\">·</span> " + l.getDurationMinutes() + " min</span></li>"
                + "<li>" + icon("pin") + "<span class=\"text\">" + s.getMode().getLabel() + "</span></li></ul>" + action + "</article>";
    }

    // ---------- Booking a slot, and a tutor's profile ----------

    Page book(Request rq, TimeSlot s) throws SkillExchangeException {
        Visit v = rq.visit();
        Listing l = s.getListing();
        int credits = v.api.credits().balance();
        int cost = l.getCreditCost();
        boolean shortOfCredits = cost > credits;
        String tutor = l.getTutor().getName();
        // A switch expression: one note per listing type.
        String note = shortOfCredits
                ? "You need " + plural(cost, "credit") + " for this session and have " + credits + ". Teach a swap session to earn more."
                : switch (l.getType()) {
                    case SWAP -> plural(cost, "credit") + " will be held when you book. " + tutor + " receives them only after you confirm "
                            + "the session took place. If the booking is cancelled, they come back to you.";
                    case PAID -> "You pay the fee to " + tutor + " at the session. Nothing is charged here.";
                    case FREE -> "This is a free session. " + tutor + " is volunteering their time.";
                };
        StringBuilder h = new StringBuilder("<dl class=\"summary\">");
        row(h, "Tutor", tutor);
        row(h, "Date", Html.dayLabel(s.getStart()));
        row(h, "Time", Html.timeRange(s) + " (" + l.getDurationMinutes() + " min)");
        row(h, "Where", s.getMode().getLabel() + ". You get the " + s.getMode().getPlaceLabel().toLowerCase() + " once you book.");
        row(h, "Price", Html.price(l));
        if (l.getType() == ListingType.SWAP && !shortOfCredits) row(h, "Your balance after booking", plural(credits - cost, "credit"));
        h.append("</dl>").append(Html.note(shortOfCredits ? "alert" : "info", note, shortOfCredits))
                .append("<form class=\"actions\" method=\"post\" action=\"/book\">").append(hidden(v, "/"))
                .append("<input type=\"hidden\" name=\"slot\" value=\"").append(s.getId()).append("\"><a class=\"btn\" href=\"/\">Not now</a>")
                .append("<button class=\"btn primary\"").append(shortOfCredits ? " disabled" : " autofocus").append(">Confirm booking</button></form>");
        return page(rq, "browse", "Book " + l.getSkill(), dialog("Book " + l.getSkill(), h, false));
    }

    private static void row(StringBuilder h, String label, String value) {
        h.append("<dt>").append(esc(label)).append("</dt><dd>").append(esc(value)).append("</dd>");
    }

    Page tutor(Request rq, TutorProfile p) throws SkillExchangeException {
        Visit v = rq.visit();
        StringBuilder h = new StringBuilder("<div class=\"profile-head\">").append(avatar(p.student(), "xl")).append("<div><h2>")
                .append(esc(p.student().getName())).append("</h2><p class=\"muted small\">").append(plural(p.sessionsTaught(), "session"))
                .append(" taught · ").append(plural(p.listings().size(), "skill")).append(" on offer</p></div></div>");
        if (p.listings().isEmpty()) h.append("<p class=\"muted\">Not teaching anything at the moment.</p>");
        for (Listing l : p.listings()) {
            h.append("<div class=\"profile-listing\"><div class=\"row between\">").append(typeBadge(l.getType())).append("<span class=\"price\">")
                    .append(esc(Html.price(l))).append("</span></div><h3>").append(esc(l.getSkill())).append("</h3><ul class=\"slots-list\">");
            List<TimeSlot> open = p.openSlots().stream().filter(s -> s.getListing().equals(l)).toList();
            if (open.isEmpty()) h.append("<li class=\"none\">No open times right now</li>");
            for (TimeSlot s : open) {
                h.append("<li><span>").append(Html.shortDay(s.getStart())).append(" · ").append(Html.timeRange(s)).append(" · ")
                        .append(s.getMode().getLabel()).append("</span>");
                if (!v.isMe(p.student())) h.append("<a class=\"btn sm\" href=\"/book?slot=").append(s.getId()).append("\">Book</a>");
                h.append("</li>");
            }
            h.append("</ul>");
            if (v.signedIn() && !v.isMe(p.student())) {
                h.append("<form method=\"post\" action=\"/messages/open\" style=\"margin-top:10px\">").append(hidden(v, rq.here()))
                        .append("<input type=\"hidden\" name=\"listing\" value=\"").append(l.getId()).append("\"><button class=\"btn sm\">")
                        .append(icon("chat")).append("Message about ").append(esc(l.getSkill())).append("</button></form>");
            }
            h.append("</div>");
        }
        h.append("<div class=\"actions\"><a class=\"btn\" href=\"/\">Back to Browse</a></div>");
        return page(rq, "browse", p.student().getName(), "<div class=\"card dialog-card\">" + h + "</div>");
    }

    // ---------- Signing in ----------

    /**
     * The sign-in page. After a failed attempt it is shown again with what was typed and the message
     * under the field it belongs to; {@code errors} maps a field name to its message.
     */
    Page signIn(Request rq, Map<String, String> errors) throws SkillExchangeException {
        Visit v = rq.visit();
        String next = rq.get("next");
        StringBuilder h = new StringBuilder("<p class=\"muted\">Enter your email and password.</p>");
        h.append("<form class=\"form\" method=\"post\" action=\"/signin\">").append(hidden(v, "/signin"))
                .append("<input type=\"hidden\" name=\"next\" value=\"").append(esc(next)).append("\">")
                .append(field("Email", "<input class=\"input\" name=\"email\" type=\"email\" required maxlength=\"254\" autocomplete=\"email\" "
                        + "placeholder=\"you@college.edu\" value=\"" + esc(rq.get("email")) + "\">", null))
                .append(field("Password", "<input class=\"input\" name=\"password\" type=\"password\" required autocomplete=\"current-password\""
                        + (rq.get("email").isEmpty() ? "" : " autofocus") + ">", errors.get("login")))
                .append("<button class=\"btn primary block\">Sign in</button></form>")
                .append("<p class=\"note\">").append(icon("info")).append("<span>Trying the demo? Pick a student below, then type the password <code>")
                .append(DemoData.DEMO_PASSWORD).append("</code>.</span></p><div class=\"people\">");
        String[] demo = {"Asha", "Ravi", "Meera", "Kabir"};
        for (int i = 0; i < demo.length; i++) {
            h.append("<a class=\"person\" href=\"/signin?email=").append(demo[i].toLowerCase()).append("@uni.edu&next=").append(Html.url(next))
                    .append("\">").append(avatar(demo[i], i + 1, "lg")).append("<span><b>").append(demo[i]).append("</b><span>")
                    .append(demo[i].toLowerCase()).append("@uni.edu</span></span></a>");
        }
        h.append("</div><div class=\"divider\">or create a profile</div><form class=\"form\" method=\"post\" action=\"/register\">")
                .append(hidden(v, "/signin")).append("<input type=\"hidden\" name=\"next\" value=\"").append(esc(next)).append("\"><div class=\"two\">")
                .append(field("Name", "<input class=\"input" + bad(errors, "name") + "\" name=\"name\" required minlength=\"2\" maxlength=\"40\" "
                        + "autocomplete=\"name\" value=\"" + esc(rq.get("name")) + "\">", errors.get("name")))
                .append(field("Email", "<input class=\"input" + bad(errors, "email") + "\" name=\"newEmail\" type=\"email\" required maxlength=\"254\" "
                        + "autocomplete=\"email\" value=\"" + esc(rq.get("newEmail")) + "\">", errors.get("email")))
                .append("</div><div class=\"two\">")
                .append(field("Password", "<input class=\"input" + bad(errors, "password") + "\" name=\"newPassword\" type=\"password\" required "
                        + "minlength=\"8\" maxlength=\"64\" autocomplete=\"new-password\">", errors.get("password")))
                .append(field("Confirm password", "<input class=\"input" + bad(errors, "confirm") + "\" name=\"confirm\" type=\"password\" required "
                        + "maxlength=\"64\" autocomplete=\"new-password\">", errors.get("confirm")))
                .append("</div><p class=\"hint\">At least 8 characters with a letter and a number. Only a scrambled version is stored, "
                        + "never the password itself. New profiles start with 5 credits.</p>")
                .append("<button class=\"btn primary block\">Create profile</button></form>");
        return page(rq, "", "Sign in", dialog("Sign in", h, false));
    }

    private static String bad(Map<String, String> errors, String field) {
        return errors.containsKey(field) ? " bad" : "";
    }

    /** A labelled form field with, if there is one, its error message underneath. */
    private static String field(String label, String inputHtml, String error) {
        return "<label class=\"field\">" + esc(label) + inputHtml + (error == null ? "" : "<span class=\"err\">" + esc(error) + "</span>") + "</label>";
    }

    Page account(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        Student me = rq.me();
        String body = "<div class=\"profile-head\">" + avatar(me, "xl") + "<div><h2>" + esc(me.getName()) + "</h2><p class=\"muted small\">"
                + esc(me.getEmail()) + "</p></div></div>" + Html.note("info", "Signed in on this browser. Other browsers and the desktop "
                + "application keep their own sign-in.", false)
                + "<form class=\"actions\" method=\"post\" action=\"/signout\">" + hidden(v, "/")
                + "<a class=\"btn\" href=\"/credits\">See my credits</a><button class=\"btn quiet warn\">Sign out</button></form>";
        return page(rq, "", "Your profile", "<div class=\"card dialog-card\">" + body + "</div>");
    }

    // ---------- My bookings ----------

    Page bookings(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        Student me = rq.me();
        List<Booking> all = v.api.myBookings();
        BookingStatus filter = null;
        for (BookingStatus s : BookingStatus.values()) {
            if (s.name().equalsIgnoreCase(rq.get("status"))) filter = s;
        }
        BookingStatus wanted = filter;
        StringBuilder h = new StringBuilder(Html.head("My bookings", "Sessions you are learning in and sessions you are teaching."));
        if (all.isEmpty()) {
            h.append(Html.empty("calendar", "No bookings yet", "When you book a session, or someone books one of yours, it shows up here.",
                    "<a class=\"btn primary\" href=\"/\">Browse open sessions</a>"));
            return page(rq, "bookings", "My bookings", h);
        }
        h.append("<div class=\"toolbar\"><div class=\"chips\" role=\"group\" aria-label=\"Filter by status\">")
                .append(chip("All", "/bookings", wanted == null, all.size()));
        String[][] statuses = {{"CONFIRMED", "Confirmed"}, {"COMPLETED", "Completed"}, {"CANCELLED", "Cancelled"}};
        for (String[] s : statuses) {
            long n = all.stream().filter(b -> b.getStatus().name().equals(s[0])).count();
            h.append(chip(s[1], "/bookings?status=" + s[0], wanted != null && wanted.name().equals(s[0]), (int) n));
        }
        h.append("</div><a class=\"btn sm\" href=\"/bookings/export\" style=\"margin-left:auto\">").append(icon("download"))
                .append("Export as CSV</a></div><div class=\"stack\" style=\"margin-top:16px\">");
        List<Booking> visible = all.stream().filter(b -> wanted == null || b.getStatus() == wanted).toList();
        if (visible.isEmpty()) h.append(Html.empty("calendar", "No " + wanted.name().toLowerCase() + " bookings", "", ""));
        visible.forEach(b -> h.append(bookingRow(v, me, b)));
        h.append("</div>");
        return page(rq, "bookings", "My bookings", h);
    }

    private String bookingRow(Visit v, Student me, Booking b) {
        TimeSlot slot = b.getSlot();
        boolean learning = b.isLearner(me);
        boolean live = b.getStatus() == BookingStatus.CONFIRMED;
        boolean upcoming = live && slot.getStart().isAfter(LocalDateTime.now());
        StringBuilder h = new StringBuilder("<article class=\"card booking").append(live ? "" : " is-off").append("\"><div class=\"date-tile\" aria-hidden=\"true\">")
                .append("<span class=\"mon\">").append(slot.getStart().format(MONTH)).append("</span><span class=\"day\">")
                .append(slot.getStart().getDayOfMonth()).append("</span><span class=\"dow\">").append(slot.getStart().format(WEEKDAY))
                .append("</span></div><div><div class=\"row wrap\">").append(badge(learning ? "Learning" : "Teaching", learning ? "learning" : "teaching", false))
                .append(' ').append(typeBadge(slot.getListing().getType()));
        if (upcoming) h.append("<span class=\"countdown\">").append(Html.timeLeft(slot.getStart())).append("</span>");
        h.append("</div><h3>").append(esc(slot.getListing().getSkill())).append(" <span>").append(learning ? "with " : "for ")
                .append(esc(b.otherParty(me).getName())).append("</span></h3><p class=\"muted small\">").append(Html.shortDay(slot.getStart()))
                .append(" · ").append(Html.timeRange(slot)).append(" · ").append(esc(Html.price(slot.getListing())))
                .append(" · <span class=\"mono\">B").append(b.getId()).append("</span></p><p class=\"escrow\">").append(icon("pin")).append("<span>")
                .append(slot.getMode().getLabel()).append(": ").append(esc(slot.getPlace())).append("</span></p>");
        int cost = b.getHeldCredits();
        if (cost > 0) {
            String credits = plural(cost, "credit");
            String is = cost == 1 ? " is" : " are";
            String was = cost == 1 ? " was" : " were";
            String text = switch (b.getStatus()) {
                case CONFIRMED -> learning ? credits + is + " held until you confirm the session took place."
                        : credits + is + " held for you until " + b.getLearner().getName() + " confirms the session took place.";
                case COMPLETED -> learning ? credits + was + " paid to " + b.getTutor().getName() + "." : "You earned " + credits + ".";
                case CANCELLED -> learning ? credits + was + " refunded to you." : credits + was + " refunded to " + b.getLearner().getName() + ".";
            };
            String iconName = switch (b.getStatus()) {
                case CONFIRMED -> "lock";
                case COMPLETED -> "check";
                case CANCELLED -> "undo";
            };
            h.append("<p class=\"escrow\">").append(icon(iconName)).append("<span>").append(esc(text)).append("</span></p>");
        }
        h.append("</div><div class=\"booking-side\">").append(badge(live ? "Confirmed" : b.getStatus().getLabel(), b.getStatus().name().toLowerCase(), true))
                .append("<div class=\"booking-actions\">")
                .append(Html.postButton(v, "/messages/open", "/bookings", "booking", b.getId(), "btn quiet sm", icon("chat") + "Message", "Message"));
        if (live) {
            if (learning && !upcoming)
                h.append("<a class=\"btn primary sm\" href=\"/bookings/confirm?do=complete&id=").append(b.getId()).append("\">Confirm it took place</a>");
            h.append("<a class=\"btn quiet warn sm\" href=\"/bookings/confirm?do=cancel&id=").append(b.getId()).append("\">Cancel</a>");
        }
        h.append("</div>");
        if (learning && upcoming) h.append("<p class=\"hint\">You can confirm it once it starts.</p>");
        return h.append("</div></article>").toString();
    }

    /** The "are you sure?" page for confirming or cancelling a booking. */
    Page confirmBooking(Request rq, Booking b, boolean complete) throws SkillExchangeException {
        Visit v = rq.visit();
        Student me = rq.me();
        boolean learning = b.isLearner(me);
        int cost = b.getHeldCredits();
        String credits = plural(cost, "credit");
        String session = b.getSlot().getListing().getSkill() + " on " + Html.shortDay(b.getSlot().getStart()) + ", " + Html.timeRange(b.getSlot())
                + (learning ? ", with " : ", for ") + b.otherParty(me).getName() + ".";
        String consequence = complete
                ? (cost > 0 ? "The " + credits + " held for this booking will be paid to " + b.getTutor().getName() + "."
                        : "The booking will be marked as completed.") + " This cannot be undone."
                : "The time slot opens up again for other students."
                        + (cost > 0 ? " The " + credits + " held for it will be refunded to " + (learning ? "you" : b.getLearner().getName()) + "." : "");
        String title = complete ? "Confirm the session took place?" : "Cancel this booking?";
        String body = "<p class=\"muted\">" + esc(session) + "</p>" + Html.note(complete ? "info" : "alert", consequence, !complete)
                + "<form class=\"actions\" method=\"post\" action=\"/bookings/" + (complete ? "complete" : "cancel") + "\">" + hidden(v, "/bookings")
                + "<input type=\"hidden\" name=\"id\" value=\"" + b.getId() + "\"><a class=\"btn\" href=\"/bookings\">" + (complete ? "Not yet" : "Keep booking")
                + "</a><button class=\"btn " + (complete ? "primary" : "danger") + "\">" + (complete ? "Yes, it took place" : "Cancel booking") + "</button></form>";
        return page(rq, "bookings", title, dialog(title, body, false));
    }

    // ---------- Teach ----------

    Page teach(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        rq.me();
        List<Listing> listings = v.api.myListings();
        List<TimeSlot> slots = v.api.mySlots();
        List<LearningModule> modules = v.api.myModules();

        StringBuilder h = new StringBuilder(Html.head("Teach", "Create a listing for something you can teach, then add the times you are free."));
        h.append("<div class=\"split\"><form class=\"card form sticky\" method=\"post\" action=\"/teach/listing\">").append(hidden(v, "/teach"))
                .append("<h2>New listing</h2><div class=\"field\"><fieldset class=\"seg\"><legend>Listing type</legend>");
        ListingType[] order = {ListingType.SWAP, ListingType.PAID, ListingType.FREE};
        for (int i = 0; i < order.length; i++) {
            String id = "type-" + order[i].name();
            h.append("<input type=\"radio\" name=\"type\" id=\"").append(id).append("\" value=\"").append(order[i].name()).append("\"")
                    .append(i == 0 ? " checked" : "").append("><label for=\"").append(id).append("\">").append(order[i].getLabel()).append("</label>");
        }
        h.append("</fieldset><p class=\"hint type-help help-SWAP\">Learners pay in credits. The credits are held when they book and reach you once they confirm the session took place.</p>")
                .append("<p class=\"hint type-help help-PAID\">Learners pay you the fee at the session. Nothing is charged in the app.</p>")
                .append("<p class=\"hint type-help help-FREE\">A volunteer session. Learners pay nothing.</p></div>")
                .append(field("Skill", "<input class=\"input\" name=\"skill\" required minlength=\"2\" maxlength=\"40\" placeholder=\"e.g. Python for beginners\">", null))
                .append("<label class=\"field\"><span>Short description <span class=\"opt\">(optional)</span></span>"
                        + "<input class=\"input\" name=\"description\" maxlength=\"300\" placeholder=\"What will you cover?\"></label><div class=\"two\">")
                .append(field("Length (minutes)", "<input class=\"input\" name=\"minutes\" type=\"number\" min=\"15\" max=\"240\" step=\"5\" value=\"60\" required>", null))
                .append("<label class=\"field price-fee\">Fee (Rs.)<input class=\"input\" name=\"fee\" type=\"number\" min=\"1\" max=\"100000\" step=\"any\" value=\"200\"></label>")
                .append("<label class=\"field price-credits\">Credits per session<input class=\"input\" name=\"credits\" type=\"number\" min=\"1\" max=\"10\" value=\"2\"></label>")
                .append("</div><button class=\"btn primary block\">Create listing</button></form><div class=\"stack\">");
        if (listings.isEmpty()) {
            h.append(Html.empty("plus", "You are not teaching anything yet", "Create your first listing, then add a time slot so students can book it.", ""));
        } else {
            h.append("<h2>Your listings <span class=\"subtle\" style=\"font-weight:400\">").append(listings.size()).append("</span></h2>");
        }
        for (Listing l : listings) {
            h.append(listingCard(v, l, slots.stream().filter(s -> s.getListing().equals(l)).toList(),
                    modules.stream().filter(m -> m.listing().equals(l)).toList()));
        }
        h.append("</div></div>");
        return page(rq, "teach", "Teach", h);
    }

    private String listingCard(Visit v, Listing l, List<TimeSlot> slots, List<LearningModule> modules) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder h = new StringBuilder("<article class=\"card listing\"><div class=\"row between\">").append(typeBadge(l.getType()))
                .append("<span class=\"price\">").append(esc(Html.price(l))).append("</span></div><h3>").append(esc(l.getSkill()))
                .append("</h3><p class=\"muted small\">").append(l.getDurationMinutes()).append(" min · <span class=\"mono\">L").append(l.getId())
                .append("</span>").append(l.getDescription().isBlank() ? "" : " · " + esc(l.getDescription())).append("</p><ul class=\"slots-list\">");
        if (slots.isEmpty()) h.append("<li class=\"none\">No time slots yet</li>");
        for (TimeSlot s : slots) {
            // An open slot whose start time has passed can no longer be booked.
            String status = s.isBooked() ? badge("Booked", "booked", true)
                    : !s.getStart().isAfter(now) ? badge("Expired", "expired", true) : badge("Open", "open", true);
            h.append("<li><span class=\"grow\">").append(Html.shortDay(s.getStart())).append(" · ").append(Html.timeRange(s))
                    .append("<small>").append(s.getMode().getLabel()).append(": ").append(esc(s.getPlace())).append("</small></span><span class=\"row\">").append(status);
            if (!s.isBooked()) h.append(Html.postButton(v, "/teach/slot/remove", "/teach", "slot", s.getId(), "icon-btn", icon("x"), "Remove this slot"));
            h.append("</span></li>");
        }
        h.append("</ul><form class=\"add-slot\" method=\"post\" action=\"/teach/slot\">").append(hidden(v, "/teach"))
                .append("<input type=\"hidden\" name=\"listing\" value=\"").append(l.getId()).append("\"><input class=\"input\" type=\"datetime-local\" name=\"start\" required min=\"")
                .append(now.format(LOCAL_INPUT)).append("\" aria-label=\"Start time\"><select class=\"input\" name=\"mode\" aria-label=\"Where\">");
        for (SessionMode m : SessionMode.values()) {
            h.append("<option value=\"").append(m.name()).append("\">").append(m.getLabel()).append("</option>");
        }
        h.append("</select><input class=\"input\" name=\"place\" required maxlength=\"120\" placeholder=\"Room, or https:// meeting link\" aria-label=\"Room or meeting link\">")
                .append("<button class=\"btn sm\">").append(icon("plus")).append("Add slot</button></form><p class=\"sub-head\">Exercise modules</p><ul class=\"slots-list\">");
        if (modules.isEmpty()) h.append("<li class=\"none\">No exercise modules yet</li>");
        for (LearningModule m : modules) {
            h.append("<li><span class=\"grow\"><b>").append(esc(m.title())).append("</b><small>").append(plural(m.exercises().size(), "exercise"))
                    .append(" · ").append(plural(m.totalPoints(), "point")).append("</small></span><span class=\"row\">")
                    .append("<a class=\"btn quiet sm\" href=\"/teach/exercises?module=").append(m.id()).append("\">Exercises</a>")
                    .append("<a class=\"btn quiet sm\" href=\"/teach/scores?module=").append(m.id()).append("\">Scores</a>")
                    .append(Html.postButton(v, "/teach/module/delete", "/teach", "module", m.id(), "icon-btn", icon("x"), "Delete this module"))
                    .append("</span></li>");
        }
        h.append("</ul><div class=\"row wrap\"><a class=\"btn sm\" href=\"/teach/module?listing=").append(l.getId()).append("\">").append(icon("plus"))
                .append("Write a module</a><a class=\"btn quiet warn sm delete-listing\" href=\"/teach/delete?listing=").append(l.getId())
                .append("\" style=\"margin:0 0 0 auto\">Delete listing</a></div></article>");
        return h.toString();
    }

    Page deleteListing(Request rq, Listing l) throws SkillExchangeException {
        String body = "<p class=\"muted\">Listing <span class=\"mono\">L" + l.getId() + "</span>, " + esc(Html.price(l)) + ".</p>"
                + Html.note("alert", "The listing, its open time slots and its exercise modules are removed. Sessions that already took place stay "
                + "in your bookings and credit history. A listing with a confirmed booking cannot be deleted: cancel the booking first.", true)
                + "<form class=\"actions\" method=\"post\" action=\"/teach/delete\">" + hidden(rq.visit(), "/teach")
                + "<input type=\"hidden\" name=\"listing\" value=\"" + l.getId() + "\"><a class=\"btn\" href=\"/teach\">Keep listing</a>"
                + "<button class=\"btn danger\">Delete listing</button></form>";
        return page(rq, "teach", "Delete " + l.getSkill() + "?", dialog("Delete " + l.getSkill() + "?", body, false));
    }

    /** The page on which a tutor types a module. {@code text} and {@code error} are set after a failed save. */
    Page writeModule(Request rq, Listing l, String text, String error) throws SkillExchangeException {
        String body = "<p class=\"muted\">One question per block. MCQ for multiple choice, TF for true or false, FILL for fill in the blank. "
                + "The answer comes after =&gt;.</p>" + (error == null ? "" : "<p class=\"form-error\" role=\"alert\">" + esc(error) + "</p>")
                + "<form class=\"form\" method=\"post\" action=\"/teach/module\">" + hidden(rq.visit(), "/teach")
                + "<input type=\"hidden\" name=\"listing\" value=\"" + l.getId() + "\"><textarea class=\"input\" name=\"text\" rows=\"16\" required>"
                + esc(text == null ? MODULE_TEMPLATE : text) + "</textarea><div class=\"actions\"><a class=\"btn\" href=\"/teach\">Cancel</a>"
                + "<button class=\"btn primary\">Save module</button></div></form>";
        return page(rq, "teach", "New module", dialog("New module for " + l.getSkill(), body, true));
    }

    Page exercises(Request rq, LearningModule m) throws SkillExchangeException {
        StringBuilder h = new StringBuilder("<p class=\"muted\">").append(esc(m.summary().isBlank() ? "Exercises and answers, as only you can see them." : m.summary())).append("</p>");
        int number = 1;
        for (Exercise e : m.exercises()) {
            h.append("<div class=\"question\"><div class=\"row between\"><p class=\"eyebrow\">Question ").append(number++).append(" · ")
                    .append(e.kind().getLabel()).append("</p><span class=\"subtle small\">").append(plural(e.points(), "point")).append("</span></div><b>")
                    .append(esc(e.prompt())).append("</b>");
            if (!e.options().isEmpty()) h.append("<p class=\"muted small\">Options: ").append(esc(String.join("  /  ", e.options()))).append("</p>");
            h.append("<p class=\"good-text\">Answer: ").append(esc(e.correctAnswer())).append("</p></div>");
        }
        h.append("<div class=\"actions\"><a class=\"btn\" href=\"/teach\">Back to Teach</a></div>");
        return page(rq, "teach", m.title(), dialog(m.title(), h, true));
    }

    Page scores(Request rq, LearningModule m, List<ScoreRow> rows) throws SkillExchangeException {
        StringBuilder h = new StringBuilder("<ul class=\"slots-list\">");
        if (rows.isEmpty()) h.append("<li class=\"none\">Nobody has taken this module yet</li>");
        for (ScoreRow r : rows) {
            h.append("<li><span class=\"row\">").append(avatar(r.student(), "sm")).append("<span class=\"grow\"><b>").append(esc(r.student().getName()))
                    .append("</b><small>").append(plural(r.attempts(), "attempt")).append(" · last ").append(Html.shortDay(r.lastAt()))
                    .append("</small></span></span>").append(badge(r.bestScore() + " / " + r.total(), "confirmed", false)).append("</li>");
        }
        h.append("</ul><div class=\"actions\"><a class=\"btn\" href=\"/teach\">Back to Teach</a></div>");
        return page(rq, "teach", "Scores", dialog("Scores for \"" + m.title() + "\"", h, false));
    }

    // ---------- Messages ----------

    Page messages(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        Student me = rq.me();
        List<Conversation> conversations = v.api.conversations();
        long wanted = rq.get("c").isEmpty() ? -1 : rq.id("c");
        // The conversation asked for, or else the most recent one.
        Conversation open = conversations.stream().filter(c -> c.id() == wanted).findFirst()
                .orElse(conversations.isEmpty() ? null : conversations.get(0));

        StringBuilder h = new StringBuilder(Html.head("Messages", "Talk to your tutor or learner about what you will cover, how, and where you will meet."));
        if (conversations.isEmpty()) {
            h.append(Html.empty("chat", "No conversations yet", "Press the message button on a session in Browse to start one.",
                    "<a class=\"btn primary\" href=\"/\">Browse open sessions</a>"));
            return page(rq, "messages", "Messages", h);
        }
        h.append("<div class=\"chat-split\"><div class=\"card chat-people\">");
        for (Conversation c : conversations) {
            Student other = c.other(me);
            h.append("<a class=\"person").append(open != null && open.id() == c.id() ? " on" : "").append("\" href=\"/messages?c=").append(c.id()).append("\">")
                    .append(avatar(other, "lg")).append("<span><b>").append(esc(other.getName())).append("</b><span>").append(esc(c.listing().getSkill()))
                    .append("</span><span class=\"last\">").append(esc(c.lastText() == null ? "No messages yet" : c.lastText())).append("</span></span></a>");
        }
        h.append("</div><div class=\"card chat-card\">");
        if (open != null) {
            Student other = open.other(me);
            h.append("<div class=\"profile-head\">").append(avatar(other, "lg")).append("<div><h3>").append(esc(other.getName())).append("</h3><p class=\"muted small\">")
                    .append(esc(open.listing().getSkill())).append(" · ").append(v.isMe(open.tutor()) ? "you are the tutor" : "tutor: " + esc(open.tutor().getName()))
                    .append("</p></div></div><iframe class=\"chat-frame\" title=\"Messages\" src=\"/messages/thread?c=").append(open.id()).append("\"></iframe>")
                    .append("<form class=\"chat-send\" method=\"post\" action=\"/messages/send\">").append(hidden(v, "/messages?c=" + open.id()))
                    .append("<input type=\"hidden\" name=\"c\" value=\"").append(open.id()).append("\"><input class=\"input\" name=\"text\" required maxlength=\"1000\" ")
                    .append("autocomplete=\"off\" autofocus placeholder=\"Type a message and press Enter\"><button class=\"btn primary\">Send</button></form>")
                    .append("<p class=\"hint\">New messages appear by themselves every few seconds.</p>");
        }
        h.append("</div></div>");
        return page(rq, "messages", "Messages", h);
    }

    /**
     * The messages of one conversation, as a small document of its own shown inside the chat card.
     * It asks the browser to load it again every four seconds, which is how new messages arrive
     * without any JavaScript.
     */
    String thread(Request rq, List<Message> messages) {
        Visit v = rq.visit();
        StringBuilder h = new StringBuilder("<!doctype html>\n<html lang=\"en\" data-theme=\"").append(rq.dark() ? "dark" : "light")
                .append("\"><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"4\"><title>Messages</title>")
                .append("<link href=\"https://fonts.googleapis.com/css2?family=Nunito:wght@500;600;700;800;900&display=swap\" rel=\"stylesheet\">")
                .append("<link rel=\"stylesheet\" href=\"/styles.css\"></head><body class=\"thread\">");
        if (messages.isEmpty()) h.append("<p class=\"muted\">Say hello. Agree what you will cover, how, and where you will meet.</p>");
        // Newest first in the document: the page is laid out bottom-up, so the newest ends up at the bottom, in view.
        List<Message> newestFirst = new ArrayList<>(messages);
        java.util.Collections.reverse(newestFirst);
        for (Message m : newestFirst) {
            h.append("<div class=\"bubble-row").append(v.isMe(m.sender()) ? " mine" : "").append("\"><div class=\"bubble\">").append(esc(m.text()))
                    .append("<time>").append(Html.shortDay(m.sentAt())).append(' ').append(m.sentAt().format(Html.TIME)).append("</time></div></div>");
        }
        return h.append("</body></html>").toString();
    }

    // ---------- Learn ----------

    Page learn(Request rq) throws SkillExchangeException {
        Visit v = rq.visit();
        rq.me();
        String q = rq.get("q").strip();
        List<ModuleView> modules = v.api.modules(q);
        StringBuilder h = new StringBuilder(Html.head("Learn", "Practise with exercises written by tutors. The server marks your answers and keeps your best score."));
        h.append("<div class=\"toolbar\"><form class=\"search-form\" method=\"get\" action=\"/learn\"><label class=\"search\">").append(icon("search"))
                .append("<span class=\"sr-only\">Search modules</span><input class=\"input\" type=\"search\" name=\"q\" placeholder=\"Search modules by title, skill or tutor\" value=\"")
                .append(esc(q)).append("\"></label><button class=\"btn sm\">Search</button></form></div>");
        if (modules.isEmpty()) {
            h.append(Html.empty("book", q.isEmpty() ? "No modules yet" : "Nothing matches", q.isEmpty() ? "Tutors add exercise modules to their listings in Teach."
                    : "Try another word.", ""));
            return page(rq, "learn", "Learn", h);
        }
        h.append("<div class=\"grid\" style=\"margin-top:16px\">");
        for (ModuleView m : modules) {
            h.append("<article class=\"card slot t-module\"><div class=\"row between\">").append(badge(m.listing().getSkill(), "confirmed", false))
                    .append("<span class=\"price\">").append(plural(m.totalPoints(), "point")).append("</span></div><h3>").append(esc(m.title()))
                    .append("</h3><p class=\"desc\">").append(esc(m.summary().isBlank() ? "No description yet." : m.summary())).append("</p><ul class=\"facts\"><li>")
                    .append(avatar(m.listing().getTutor(), "sm")).append("<span class=\"text\">").append(esc(m.listing().getTutor().getName())).append("</span></li><li>")
                    .append(icon("book")).append("<span class=\"text\">").append(plural(m.questions(), "question")).append("</span></li></ul>");
            if (m.mine()) {
                h.append("<button class=\"btn block\" disabled>Your own module</button>");
            } else {
                h.append("<div class=\"meter\"><i style=\"width:").append(m.percent()).append("%\"></i></div><p class=\"muted small\">")
                        .append(m.attempted() ? "Best score " + m.bestScore() + " / " + m.totalPoints() + " (" + m.percent() + "%)" : "Not attempted yet")
                        .append("</p><a class=\"btn primary block\" href=\"/learn/module?id=").append(m.id()).append("\">").append(m.attempted() ? "Try again" : "Start").append("</a>");
            }
            h.append("</article>");
        }
        h.append("</div>");
        return page(rq, "learn", "Learn", h);
    }

    Page quiz(Request rq, long moduleId, String title, List<Question> questions) throws SkillExchangeException {
        StringBuilder h = new StringBuilder("<form class=\"form\" method=\"post\" action=\"/learn/submit\">").append(hidden(rq.visit(), "/learn"))
                .append("<input type=\"hidden\" name=\"module\" value=\"").append(moduleId).append("\">");
        int number = 1;
        for (Question q : questions) {
            h.append("<div class=\"question\"><div class=\"row between\"><p class=\"eyebrow\">Question ").append(number++).append("</p><span class=\"subtle small\">")
                    .append(plural(q.points(), "point")).append("</span></div><b>").append(esc(q.prompt())).append("</b>");
            if (q.kind() == Exercise.Kind.FILL) {
                h.append("<input class=\"input\" name=\"a").append(q.id()).append("\" maxlength=\"60\" autocomplete=\"off\" placeholder=\"Type your answer\">");
            } else {
                for (String option : q.options()) {
                    h.append("<label class=\"opt\"><input type=\"radio\" name=\"a").append(q.id()).append("\" value=\"").append(esc(option)).append("\">")
                            .append(esc(option)).append("</label>");
                }
            }
            h.append("</div>");
        }
        h.append("<div class=\"actions\"><a class=\"btn\" href=\"/learn\">Close</a><button class=\"btn primary\">Submit answers</button></div></form>");
        return page(rq, "learn", title, dialog(title, h, true));
    }

    Page quizResult(Request rq, long moduleId, AttemptResult r) throws SkillExchangeException {
        String verdict = r.percent() >= 80 ? "Excellent work" : r.percent() >= 50 ? "Good, keep practising" : "Have another go";
        StringBuilder h = new StringBuilder("<p class=\"score-big\">").append(r.score()).append(" / ").append(r.total())
                .append("</p><h3 style=\"text-align:center\">").append(r.percent()).append("% · ").append(verdict).append("</h3>");
        int number = 1;
        for (AttemptResult.Feedback f : r.feedback()) {
            h.append("<div class=\"question ").append(f.right() ? "right" : "wrong").append("\"><div class=\"row between\"><p class=\"eyebrow\">Question ")
                    .append(number++).append("</p><span class=\"").append(f.right() ? "good-text\">Correct +" + f.points() : "bad-text\">Not quite")
                    .append("</span></div><b>").append(esc(f.prompt())).append("</b><p>Your answer: ").append(esc(f.given())).append("</p>");
            if (!f.right()) h.append("<p class=\"good-text\">Right answer: ").append(esc(f.correct())).append("</p>");
            h.append("</div>");
        }
        h.append("<div class=\"actions\"><a class=\"btn\" href=\"/learn/module?id=").append(moduleId).append("\">Try again</a><a class=\"btn primary\" href=\"/learn\">Done</a></div>");
        return page(rq, "learn", "Your result", dialog("Your result", h, true));
    }

    // ---------- Credits ----------

    Page credits(Request rq) throws SkillExchangeException {
        rq.me();
        CreditSummary c = rq.visit().api.credits();
        int earned = c.history().stream().filter(t -> t.type() == CreditTransaction.Type.RELEASE).mapToInt(CreditTransaction::amount).sum();
        StringBuilder h = new StringBuilder(Html.head("Credits", "Earn credits by teaching a swap session. Spend them to learn something new."));
        h.append("<div class=\"money\"><div class=\"card balance\"><p class=\"label\">").append(icon("wallet")).append("Available balance</p><p class=\"big\">")
                .append(c.balance()).append("</p><p class=\"hint\">").append(c.balance() == 1 ? "credit" : "credits").append(" ready to spend</p></div>")
                .append("<div class=\"card\"><p class=\"label\">").append(icon("lock")).append("Held in escrow</p><p class=\"big\">").append(c.held())
                .append("</p><p class=\"hint\">for sessions you booked and have not confirmed yet</p></div><div class=\"card\"><p class=\"label\">").append(icon("up"))
                .append("Earned by teaching</p><p class=\"big\">").append(earned).append("</p><p class=\"hint\">released to you by your learners</p></div></div>")
                .append("<div class=\"section-head\"><h2>How a swap is paid</h2></div><div class=\"card\"><ol class=\"flow\">")
                .append("<li><b>Book</b><span>The learner's credits are held. They leave the balance but are not paid yet.</span></li>")
                .append("<li><b>Confirm</b><span>When the learner confirms the session took place, the held credits go to the tutor.</span></li>")
                .append("<li><b>Or cancel</b><span>If the booking is cancelled instead, the held credits go back to the learner.</span></li></ol></div>")
                .append("<div class=\"section-head\"><h2>History</h2><span class=\"count\">").append(c.history().size()).append(c.history().size() == 1 ? " entry" : " entries")
                .append("</span></div><div class=\"card ledger\">");
        for (CreditTransaction t : c.history()) {
            String skill = t.skill() == null ? "a session" : t.skill();
            String booking = t.bookingId() == null ? "" : "Booking B" + t.bookingId() + (t.other() == null ? "" : " · with " + t.other());
            // A switch expression that yields an array: title, detail, icon and colour for each kind of entry.
            String[] text = switch (t.type()) {
                case INITIAL -> new String[] {"Starting balance", "Credits every new profile begins with", "wallet", "learning"};
                case HOLD -> new String[] {"Held for " + skill, booking, "lock", "hold"};
                case RELEASE -> new String[] {"Earned teaching " + skill, booking, "up", "release"};
                case REFUND -> new String[] {"Refunded for " + skill, booking, "undo", "refund"};
            };
            h.append("<div class=\"tx\"><span class=\"tx-icon b-").append(text[3]).append("\">").append(icon(text[2])).append("</span><span><b>").append(esc(text[0]))
                    .append("</b><span class=\"muted small\">").append(esc(text[1])).append("</span></span><span class=\"subtle small when nowrap\">")
                    .append(t.at().format(Html.STAMP)).append("</span><span class=\"amt").append(t.amount() > 0 ? " pos" : "").append("\">")
                    .append(t.amount() > 0 ? "+" : t.amount() < 0 ? "−" : "").append(Math.abs(t.amount())).append("</span></div>");
        }
        h.append("</div>");
        return page(rq, "credits", "Credits", h);
    }

    // ---------- How it works ----------

    private static final String[][] CONCEPTS = {
            {"Inheritance", "Three listing types", "Paid, credit swap and free listings all extend one abstract Listing class."},
            {"Polymorphism", "One booking path", "Booking asks any listing for its credit cost. Only a swap returns more than zero."},
            {"JDBC", "One transaction per booking", "The booking, the slot and the credit hold are committed together, or rolled back together."},
            {"Exceptions", "Clear failures", "Broken rules throw checked exceptions such as InsufficientCreditsException, shown to you as a message."},
            {"Server-side Java", "Pages without scripts", "Every page here is built by Java on the server. The JavaFX desktop application uses the same server."},
            {"Synchronization", "No double booking", "A lock makes checking a slot and booking it one atomic step, with a UNIQUE index behind it."},
    };

    /** {@code race} is null until the visitor has run the race. */
    Page how(Request rq, RaceReport race) throws SkillExchangeException {
        StringBuilder h = new StringBuilder(Html.head("How it works", "A Java server with a JDBC database, reached from this web site and from a JavaFX "
                + "desktop application. These are the main ideas behind it, and you can run the concurrency test live."));
        h.append("<div class=\"grid concepts\">");
        for (String[] c : CONCEPTS) {
            h.append("<div class=\"card concept spot\"><p class=\"eyebrow\">").append(c[0]).append("</p><h3>").append(c[1]).append("</h3><p>").append(c[2]).append("</p></div>");
        }
        h.append("</div><div class=\"section-head\"><h2>Concurrency test</h2></div><section class=\"card race\" id=\"race\"><div class=\"row between wrap\" style=\"gap:16px\"><div>")
                .append("<h3 style=\"font-size:17px\">Eight students. One slot. Same instant.</h3><p class=\"muted\" style=\"margin-top:4px\">Runs on the server against a fresh ")
                .append("throwaway database, so your real data is never touched. It takes up to half a minute.</p></div><form method=\"post\" action=\"/how/race#race\">")
                .append(hidden(rq.visit(), "/how")).append("<button class=\"btn primary\">").append(icon("play")).append("Run the race</button></form></div>");
        if (race != null) {
            int won = race.safeBookings();
            long paid = race.credits().stream().filter(RaceReport.Lane::booked).count();
            h.append("<div id=\"race-out\"><div class=\"race-grid\"><div class=\"tile bad\"><p class=\"label\">").append(icon("alert")).append("Without synchronization</p><p class=\"big\"><b>")
                    .append(race.unsafeBookings()).append("</b><span>/").append(race.threads()).append("</span></p><p>threads booked the same slot. That is the bug.</p></div>")
                    .append("<div class=\"tile good\"><p class=\"label\">").append(icon("check")).append("With the lock and a transaction</p><p class=\"big\"><b>").append(won)
                    .append("</b><span>/").append(race.threads()).append("</span></p><p>").append(won == 1 ? "booking" : "bookings").append(" went through. The rest were rejected.</p></div>")
                    .append("<div class=\"tile\"><p class=\"label\">Stress test</p><p class=\"big\"><b>").append(race.stressPassed()).append("</b><span>/").append(race.stressRounds())
                    .append("</span></p><p class=\"muted\">rounds ended with exactly one winner.</p></div></div><h3>Same slot, ").append(race.threads()).append(" threads</h3>")
                    .append(tracks(race.sameSlot())).append("<h3>One learner with ").append(plural(race.creditsStart(), "credit")).append(" books ").append(race.threads())
                    .append(" slots costing ").append(race.creditCost()).append(" each</h3>").append(tracks(race.credits())).append("<p class=\"muted\">Balance after the race: <b>")
                    .append(plural(race.balanceAfter(), "credit")).append("</b> (").append(paid).append(" booked). It never goes below zero.</p>")
                    .append("<p class=\"subtle small\">The moving dots are an illustration. The result shown in each lane is the real outcome from the server.</p></div>");
        }
        h.append("</section>");
        return page(rq, "how", "How it works", h);
    }

    /** One lane per thread. The dots are moved by a CSS animation; "--d" is how long each one takes. */
    private static String tracks(List<RaceReport.Lane> lanes) {
        StringBuilder h = new StringBuilder("<div class=\"tracks\">");
        for (int i = 0; i < lanes.size(); i++) {
            RaceReport.Lane l = lanes.get(i);
            h.append("<div class=\"track ").append(l.booked() ? "win" : "lose").append("\" style=\"--d:").append(l.booked() ? 800 : 1000 + (i * 137) % 700)
                    .append("ms\"><span class=\"who\"><b>").append(esc(l.student())).append("</b><small>").append(esc(l.thread())).append(" → ").append(esc(l.slot()))
                    .append("</small></span><span class=\"lane-line\"><i class=\"runner\"></i></span><span class=\"outcome\">").append(esc(l.detail())).append("</span></div>");
        }
        return h.append("</div>").toString();
    }
}
