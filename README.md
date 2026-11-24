<p align="center">
  <h1 align="center">Peer Tutoring & Skill Exchange Booking System</h1>
  <p align="center">
    <strong>A Core Java console application where students teach what they know, book what they want to learn, and pay with money, swap credits, or nothing at all.</strong>
  </p>
  <p align="center">
    <img src="https://img.shields.io/badge/Java-26-red?logo=openjdk&logoColor=white" alt="Java">
    <img src="https://img.shields.io/badge/Core%20Java-No%20Frameworks-blue" alt="Core Java">
    <img src="https://img.shields.io/badge/Concurrency-synchronized-green" alt="Concurrency">
    <img src="https://img.shields.io/badge/Persistence-Serialization-purple" alt="Serialization">
    <img src="https://img.shields.io/badge/Build-javac-orange" alt="javac">
  </p>
</p>

---

## Overview

Students often know something worth teaching, like Java, guitar, calculus or a language, and want to learn something else in return. This system lets a student **list a skill** and publish **time slots** for it. Other students can **browse, search and book** a session.

Each listing is one of three types:

| Listing type | How the learner pays |
|---|---|
| **Paid** | A fee in rupees (e.g. `Rs. 200.00`) |
| **Credit Swap** | Skill-exchange credits, earned by teaching others |
| **Free** | Nothing (volunteer session) |

> **Key Differentiator:** Swap sessions use an **escrow-based credit ledger**. When a learner books, their credits are *held*, not paid. The tutor receives them only after the learner confirms the session took place. If the booking is cancelled, the credits are refunded. Tutors are only paid for sessions that actually happen, and a refund can never fail because the tutor already spent the credits.

---

## Screenshots

The web front end, served by the JDK's built-in HTTP server. The home page updates itself: the numbers, the recent-bookings line, the countdown to your next session and the open sessions all change as other students book and publish, without a reload:

<p align="center">
  <img src="docs/screenshots/home.png" width="800" alt="Browse open tutoring sessions">
</p>

The **Credits** page shows the escrow ledger: the available balance, the credits currently held for unconfirmed sessions, and every movement with the booking it belongs to:

<p align="center">
  <img src="docs/screenshots/credits.png" width="800" alt="Credit balance, escrow and history">
</p>

The **How it works** page runs the concurrency test live. Without synchronization all 8 threads book the same slot; with it, exactly one does:

<p align="center">
  <img src="docs/screenshots/concurrency-race.png" width="800" alt="Live concurrency race">
</p>

---

## System Architecture

```mermaid
graph TD
    A[Student at Console] --> B[ConsoleApp Menu]
    B --> C[Input Validation]
    C --> D[BookingService]

    D --> E[Students]
    D --> F[Listings]
    D --> G[Time Slots]
    D --> H[Bookings]
    D --> I[CreditLedger]

    F --> F1[PaidListing]
    F --> F2[CreditSwapListing]
    F --> F3[FreeListing]

    I --> I1[HOLD on booking]
    I1 --> I2[RELEASE on completion]
    I1 --> I3[REFUND on cancellation]

    D -.->|Rule violations| X[Custom Exceptions]
    X -.-> B

    D --> P[DataStore]
    P --> P1[skillexchange.dat<br/>serialized snapshot]
    P --> P2[sessions.csv]
    P --> P3[balances.txt]

    classDef ui fill:#d4edda,stroke:#28a745,stroke-width:2px,color:#333;
    classDef core fill:#cce5ff,stroke:#004085,stroke-width:2px,color:#333;
    classDef credit fill:#fff3cd,stroke:#ffc107,stroke-width:2px,color:#333;
    classDef err fill:#f8d7da,stroke:#dc3545,stroke-width:2px,color:#333;

    class A,B,C ui;
    class D,E,F,G,H,F1,F2,F3,P,P1,P2,P3 core;
    class I,I1,I2,I3 credit;
    class X err;
```

---

## Core Java Concepts Demonstrated

| Concept | Where | How it is used |
|---|---|---|
| **Inheritance** | `model/Listing.java` and its 3 subclasses | Abstract `Listing` holds the shared fields. `PaidListing`, `CreditSwapListing` and `FreeListing` extend it |
| **Polymorphism** | `Listing.getCreditCost()`, `getPriceDescription()` | `BookingService.book()` charges credits through `getCreditCost()` and never checks the listing type |
| **Encapsulation** | All model classes | Final fields and validating constructors. Only the service changes booking state |
| **Collections** | `service/BookingService.java` | `LinkedHashMap` stores data by id, `ArrayList` holds results, a `Comparator` sorts slots by time, and `HashMap` stores ledger balances |
| **Custom Exceptions** | `exception/` | A checked hierarchy: `BookingException` → `SlotAlreadyBooked`, `BookingConflict`, `InsufficientCredits`, plus `PersistenceException` |
| **File Handling** | `persistence/DataStore.java` | `PrintWriter`/`BufferedWriter` export a CSV session history and a text credit report |
| **Serialization** | `persistence/DataStore.java` | `ObjectOutputStream` saves the whole `BookingService` object to disk, and `ObjectInputStream` restores it |
| **Multithreading** | `ConcurrencyDemo.java` | `ExecutorService`, `CountDownLatch`, `Future` and `AtomicInteger` simulate students booking at the same moment |
| **Synchronization** | `BookingService`, `CreditLedger` | `synchronized` methods make "check, then book" one atomic step, and `volatile` fields keep booking state visible to every thread |
| **Enums** | `BookingStatus`, `CreditTransaction.Type` | Booking life cycle and ledger entry types |

---

## Booking Rules

The service enforces every rule, and each violation throws a specific exception:

| Rule | Exception |
|---|---|
| A slot can only be booked once | `SlotAlreadyBookedException` |
| A slot that has already started cannot be booked | `BookingException` |
| You cannot book your own session | `BookingConflictException` |
| A learner cannot attend two sessions at the same time | `BookingConflictException` |
| A learner cannot book a session while they are teaching | `BookingConflictException` |
| A session cannot be booked while its tutor is attending another session at that time | `BookingConflictException` |
| A tutor's slots cannot overlap | `BookingConflictException` |
| A swap booking needs enough credits | `InsufficientCreditsException` |
| Only the learner or the tutor can cancel a booking | `BookingException` |
| Only the learner can confirm a session (this pays the tutor) | `BookingException` |
| A session can only be confirmed once it has started | `BookingException` |
| Only its own tutor can remove a slot, and only while nobody has booked it | `BookingException`, `SlotAlreadyBookedException` |
| A listing cannot be deleted while it has a confirmed booking | `BookingException` |

Every check runs **before** any state changes, so a failed booking leaves nothing half-done.

---

## Credit Ledger (Escrow)

```text
Learner books a swap session    ->  HOLD     learner -2   (credits leave the learner's balance)
Learner confirms it took place  ->  RELEASE  tutor   +2   (tutor is paid)
        or
Booking is cancelled            ->  REFUND   learner +2   (credits come back)
```

Every movement is recorded as a `CreditTransaction` with a timestamp. The history is shown in the app and exported to `data/balances.txt`.

---

## Concurrency Results

`ConcurrencyDemo` releases 8 threads at the same instant using a `CountDownLatch`:

| Scenario | Without synchronization | With `synchronized` |
|---|---|---|
| 8 students book the **same slot** | **Slot booked 8 times** | 1 booked, 7 × `SlotAlreadyBookedException` |
| 1 learner with 3 credits books **8 different 2-credit slots** | n/a | 1 booked, 7 × `InsufficientCreditsException`, balance never negative |
| Same-slot race repeated **200 rounds** | n/a | **200/200 rounds with exactly one winner** |

Locks are always taken in the order service → ledger, and the ledger never calls back into the service, so there is no possibility of deadlock.

---

## Repository Structure

```text
├── src/skillexchange/
│   ├── Main.java                  # Entry point: load data, seed demo data, start menu
│   ├── DemoData.java              # Sample students, listings and slots for the first run
│   ├── ConcurrencyDemo.java       # Race condition vs. synchronized booking
│   ├── model/
│   │   ├── Student.java
│   │   ├── Listing.java           # Abstract base class
│   │   ├── PaidListing.java
│   │   ├── CreditSwapListing.java
│   │   ├── FreeListing.java
│   │   ├── TimeSlot.java
│   │   ├── Booking.java
│   │   ├── BookingStatus.java
│   │   └── CreditTransaction.java
│   ├── service/
│   │   ├── BookingService.java    # All booking rules, thread-safe
│   │   └── CreditLedger.java      # Escrow credit balances and history
│   ├── exception/                 # Custom checked exceptions
│   ├── persistence/
│   │   └── DataStore.java         # Serialization + CSV/text exports
│   ├── ui/
│   │   ├── ConsoleApp.java        # Menu (no business logic)
│   │   └── Input.java             # Validated console input
│   └── web/
│       ├── WebServer.java         # JSON API + static files on the JDK HttpServer
│       ├── RaceDemo.java          # Concurrency race for the web page
│       └── Json.java              # Minimal JSON writer
├── web/                           # Front end: index.html, styles.css, app.js
├── data/                          # Created at runtime (saved state and reports)
├── out/                           # Compiled classes
└── README.md
```

---

## Quick Start

### 1. Requirements
- JDK 17 or newer (the code uses switch expressions, `instanceof` patterns and `Stream.toList()`). It was developed on **Java 26** and has also been built and run on **Java 21**.
- No build tool or external libraries

### 2. Compile
```powershell
javac -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```
On macOS/Linux:
```bash
javac -d out $(find src -name "*.java")
```

### 3. Run the App
```bash
java -cp out skillexchange.Main
```
On the first run, demo data is loaded. Log in as **S1 to S4** (Asha, Ravi, Meera, Kabir) with the demo password `demo123`, or register a new student with a password of your own. New students start with **5 credits**.

To delete saved data and start fresh:
```bash
java -cp out skillexchange.Main --reset
```

### 4. Run the Concurrency Demo
```bash
java -cp out skillexchange.ConcurrencyDemo
```

### 5. Run the Web App
```bash
java -cp out skillexchange.web.WebServer            # then open http://localhost:8080
java -cp out skillexchange.web.WebServer --reset    # start fresh with demo data
java -cp out skillexchange.web.WebServer 9090       # use another port
```
Run it from the project root so it can find the `web/` folder. The web app and the console app share the same `data/` folder, so run one at a time.

The web front end is built on the JDK's own `com.sun.net.httpserver`, so it still needs no frameworks or libraries. It has screens to browse and book slots, manage your bookings, create listings and time slots, and view your credit history. The **How it works** page runs the concurrency race live in the browser.

- **A playful look in light and dark.** Grape purple leads, with sunshine yellow, mint, pink and sky as supporting colours. Every colour is a CSS variable at the top of `web/styles.css`, so the palette can be changed in one place. The first visit follows the system's light or dark setting, and the toggle in the top bar remembers your choice.
- **Live updates.** The page asks the server for changes every 6 seconds. When another student books your session, confirms it, cancels, or publishes a new slot, you see a notice and the page updates in place. Open the app in two browser windows with two profiles to watch it.
- **Explore.** Skill tiles filter the sessions with one click, tutor names open a profile with everything that person teaches, and **Surprise me** picks a random session you could book.
- **Search palette.** Press `Ctrl+K` (`⌘K` on a Mac) or `/` to jump to any page, skill, person or action.
- Each screen has its own address (`#/browse`, `#/bookings`, `#/teach`, `#/credits`, `#/how`), so the back button and page refresh work.
- Cancelling a booking and confirming a session both ask first and say what will happen to the held credits.
- The layout adapts to phone-sized screens, and all motion is switched off for visitors whose system asks for reduced motion.

Everything on screen comes from the API. The moving dots in the concurrency race are the one illustration: the result in each lane is the real outcome, but the speed of each dot is only for show.

The API checks every request itself rather than trusting the form: field lengths, number ranges (a fee of `NaN` or `Infinity` is refused), email format, how far ahead a slot may be (one year), and that the request comes from the app's own page and not from another site.

### Signing in

Every student has a password. The server never stores it: `model/Passwords.java` turns it into a salted PBKDF2 hash using only classes from the JDK, and that hash is what gets saved.

- `POST /api/login` checks the password and answers with a random session token in a cookie (`HttpOnly`, `SameSite=Strict`).
- Every request that acts for a student (booking, cancelling, confirming, teaching) takes the student from that session, never from a parameter, so one student cannot act as another.
- A student's bookings, credit balance and history are sent only to that student. Everyone else sees a name, what they teach and their open times.
- Five wrong passwords in a row pause sign-in for that profile for 30 seconds.
- Sessions live in memory, so restarting the server signs everyone out.

This is sign-in for a learning project. It runs over plain HTTP on localhost; putting it on the internet would need HTTPS in front of it.

### Trying the whole flow in a demo

A session can only be confirmed once it has started, so to show "confirm, and the tutor is paid" live:

1. Sign in as a tutor and add a time slot one or two minutes from now.
2. In a second browser window, sign in as another student and book it.
3. When the countdown on **My bookings** reaches the start time, the **Confirm it took place** button appears by itself.

---

## Using the App

```text
--- Priya (S5) | 5 credits ---
 1. Browse open slots
 2. Search listings by skill
 3. Book a slot
 4. My bookings
 5. Cancel a booking
 6. Confirm a session took place (pays the tutor)
 7. Create a listing (teach a skill)
 8. Add a time slot to my listing
 9. My listings and slots
10. Credit balance and history
11. Remove a time slot
12. Delete a listing
 0. Log out
```

Sample session:

```text
Slot id to book (blank to go back): T9
Book Python with Ravi for 3 credits? (y/n): y
Booked! Booking B3: Priya -> Python on 2026-11-05 [CONFIRMED]
Held until you confirm the session took place: 3 credits

Slot id to book (blank to go back): T7
Book Spoken French with Kabir for 1 credit? (y/n): y
Could not book: Priya needs 1 credit but has only 0
```

---

## Data Files

All data is stored in `data/` and saved automatically after every change:

| File | Format | Purpose |
|---|---|---|
| `skillexchange.dat` | Java serialization | The full application state, loaded on startup |
| `sessions.csv` | CSV | Session history (opens in Excel) |
| `balances.txt` | Text | Credit balance and transaction history for each student |

Saving writes to a temporary file first and then renames it over the real file, so a crash mid-save cannot damage the previous save.

> After changing the fields of any model class, run with `--reset`, because older `.dat` files cannot be loaded into changed classes.

---

## Academic Note
This project was built as a semester Core Java project to demonstrate object-oriented design, collections, exception handling, file I/O, serialization and multithreading. It is a learning project and not a production booking platform.
