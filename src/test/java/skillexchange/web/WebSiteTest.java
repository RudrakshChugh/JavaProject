package skillexchange.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skillexchange.persistence.Database;
import skillexchange.server.SkillExchangeServer;
import skillexchange.service.DemoData;
import skillexchange.service.Services;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web site, used the way a browser uses it: GET a page, read the form in it, POST the form,
 * follow the redirect. Each "browser" here is an HttpClient with its own cookie jar.
 */
class WebSiteTest {

    @TempDir
    Path dir;

    private SkillExchangeServer server;
    private String base;

    /** A browser: remembers cookies, and can be told to follow redirects or not. A nested class. */
    private final class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        final HttpClient noFollow = HttpClient.newBuilder().cookieHandler(http.cookieHandler().orElseThrow())
                .followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        /** Posts a form, adding the hidden csrf value found in the page the form is on. */
        HttpResponse<String> post(String formPage, String action, String... fields) throws Exception {
            return send(http, action, csrf(get(formPage).body()), fields);
        }

        HttpResponse<String> send(HttpClient client, String action, String csrf, String... fields) throws Exception {
            StringBuilder body = new StringBuilder("csrf=").append(csrf);
            for (int i = 0; i < fields.length; i += 2) {
                body.append('&').append(fields[i]).append('=').append(URLEncoder.encode(fields[i + 1], StandardCharsets.UTF_8));
            }
            return client.send(HttpRequest.newBuilder(URI.create(base + action))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        }

        void signIn(String email) throws Exception {
            HttpResponse<String> home = post("/signin", "/signin", "email", email, "password", DemoData.DEMO_PASSWORD);
            assertTrue(home.body().contains("Welcome back"), "signed in as " + email);
        }
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"csrf\" value=\"([0-9a-f]+)\"").matcher(html);
        assertTrue(m.find(), "the page has a form with a csrf field");
        return m.group(1);
    }

    private static String firstId(String html, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(html);
        assertTrue(m.find(), "expected to find " + pattern);
        return m.group(1);
    }

    @BeforeEach
    void setUp() throws Exception {
        Services services = Services.create(new Database(dir.resolve("test.db")));
        DemoData.seed(services);
        server = new SkillExchangeServer(services, SkillExchangeServer.NO_PORT, 0);
        server.start();
        base = "http://localhost:" + server.getHttpPort();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void theHomePageIsPlainHtmlWithNoScripts() throws Exception {
        HttpResponse<String> home = new Browser().get("/");
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("Teach what you know."));
        assertTrue(home.body().contains("Open sessions"));
        assertTrue(home.body().contains("Python for Beginners"));
        assertFalse(home.body().toLowerCase().contains("<script"), "no JavaScript anywhere");
        assertTrue(home.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'none'"));
        assertEquals(200, new Browser().get("/styles.css").statusCode());
    }

    @Test
    void pagesThatNeedAnAccountSendVisitorsToSignInAndBack() throws Exception {
        Browser b = new Browser();
        HttpResponse<String> redirect = b.noFollow.send(HttpRequest.newBuilder(URI.create(base + "/credits")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(303, redirect.statusCode());
        assertEquals("/signin?next=%2Fcredits", redirect.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> after = b.post("/signin?next=%2Fcredits", "/signin", "email", "asha@uni.edu",
                "password", DemoData.DEMO_PASSWORD, "next", "/credits");
        assertTrue(after.uri().getPath().equals("/credits"), "back where they were going: " + after.uri());
        assertTrue(after.body().contains("Available balance"));
    }

    @Test
    void wrongPasswordAndBadSignUpValuesAreShownOnThePage() throws Exception {
        Browser b = new Browser();
        HttpResponse<String> wrong = b.post("/signin", "/signin", "email", "asha@uni.edu", "password", "wrong1234");
        assertTrue(wrong.body().contains("Wrong email or password"));
        assertTrue(wrong.body().contains("value=\"asha@uni.edu\""), "the email typed is kept");

        HttpResponse<String> bad = b.post("/signin", "/register", "name", "R2-D2", "newEmail", "asha@uni",
                "newPassword", "short", "confirm", "different");
        assertTrue(bad.body().contains("Name can only have letters"));
        assertTrue(bad.body().contains("needs a domain"));
        assertTrue(bad.body().contains("at least 8 characters"));
        assertTrue(bad.body().contains("The two passwords are different"));

        HttpResponse<String> taken = b.post("/signin", "/register", "name", "Another Asha", "newEmail", "ASHA@uni.edu",
                "newPassword", "pass1234", "confirm", "pass1234");
        assertTrue(taken.body().contains("already exists"));

        HttpResponse<String> ok = b.post("/signin", "/register", "name", "Nikhil", "newEmail", "nikhil@uni.edu",
                "newPassword", "pass1234", "confirm", "pass1234");
        assertTrue(ok.body().contains("Welcome back, <em>Nikhil</em>"));
    }

    @Test
    void aVisitorCanBookCancelAndSeeTheCreditsMove() throws Exception {
        Browser meera = new Browser();
        meera.signIn("meera@uni.edu");
        String home = meera.get("/?q=guitar").body();
        String slot = firstId(home, "href=\"/book\\?slot=(\\d+)\"");

        String confirm = meera.get("/book?slot=" + slot).body();
        assertTrue(confirm.contains("Book Guitar"));
        assertTrue(confirm.contains("2 credits will be held"));
        HttpResponse<String> booked = meera.post("/book?slot=" + slot, "/book", "slot", slot);
        assertTrue(booked.uri().getPath().equals("/bookings"));
        assertTrue(booked.body().contains("Booked Guitar"), "the message from the action is shown once");
        assertTrue(booked.body().contains("Music room, Student Centre"), "the place is revealed after booking");

        // The cancel link inside the Guitar card (Meera also has a French booking on the page).
        String booking = firstId(booked.body(), "(?s)<h3>Guitar .*?do=cancel&id=(\\d+)");
        HttpResponse<String> cancelled = meera.post("/bookings/confirm?do=cancel&id=" + booking, "/bookings/cancel", "id", booking);
        assertTrue(cancelled.body().contains("Booking cancelled"));
        assertTrue(meera.get("/credits").body().contains("Refunded for Guitar"));
    }

    @Test
    void chatAndExercisesWorkThroughForms() throws Exception {
        Browser meera = new Browser();
        meera.signIn("meera@uni.edu");
        // Meera already has a conversation with Kabir from the demo data.
        String messages = meera.get("/messages").body();
        String conversation = firstId(messages, "/messages/thread\\?c=(\\d+)");
        meera.post("/messages?c=" + conversation, "/messages/send", "c", conversation, "text", "See you at <4pm> & thanks");
        String thread = meera.get("/messages/thread?c=" + conversation).body();
        assertTrue(thread.contains("See you at &lt;4pm&gt; &amp; thanks"), "what was typed is shown as text, not run as HTML");
        assertTrue(thread.contains("http-equiv=\"refresh\""), "the message list reloads itself");

        // Kabir, in another browser, sees it; a stranger cannot open the conversation.
        Browser kabir = new Browser();
        kabir.signIn("kabir@uni.edu");
        assertTrue(kabir.get("/messages/thread?c=" + conversation).body().contains("See you at"));
        Browser ravi = new Browser();
        ravi.signIn("ravi@uni.edu");
        assertTrue(ravi.get("/messages/thread?c=" + conversation).body().contains("belongs to other students"));

        String learn = meera.get("/learn?q=python").body();
        String module = firstId(learn, "/learn/module\\?id=(\\d+)");
        String quiz = meera.get("/learn/module?id=" + module).body();
        assertTrue(quiz.contains("valid Python variable name"));
        assertFalse(quiz.contains("Answer:"), "the answers are not in the page");
        String question = firstId(quiz, "name=\"a(\\d+)\" value=\"2cool\"");
        HttpResponse<String> result = meera.post("/learn/module?id=" + module, "/learn/submit", "module", module, "a" + question, "cool_2");
        assertTrue(result.body().contains("Your result"));
        assertTrue(result.body().contains("Correct +1"));
    }

    @Test
    void aFormWithoutTheVisitsSecretIsRefused() throws Exception {
        Browser asha = new Browser();
        asha.signIn("asha@uni.edu");
        String before = asha.get("/teach").body();
        // What another site could make Asha's browser send: her cookie goes along, the secret does not.
        asha.send(asha.http, "/teach/listing", "0000", "type", "FREE", "skill", "Forged listing", "minutes", "60");
        String after = asha.get("/teach").body();
        assertFalse(after.contains("Forged listing"));
        assertEquals(before.split("class=\"card listing\"").length, after.split("class=\"card listing\"").length);
    }

    @Test
    void redirectsStayOnThisSite() {
        assertEquals("/teach", WebSite.localPath("/teach"));
        assertEquals("/", WebSite.localPath("https://evil.example.com"));
        assertEquals("/", WebSite.localPath("//evil.example.com"));
        assertEquals("/", WebSite.localPath(null));
    }

    @Test
    void theDesktopApplicationsAddressesStillWorkNextToTheSite() throws Exception {
        HttpResponse<String> status = new Browser().get("/status");
        assertTrue(status.body().startsWith("Skill Exchange server is running"));
    }
}
