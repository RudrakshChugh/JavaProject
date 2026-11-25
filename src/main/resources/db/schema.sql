-- Skill Exchange database schema (SQLite).
-- Run at every start-up. Each statement uses IF NOT EXISTS, so running it again changes nothing.

CREATE TABLE IF NOT EXISTS students (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT    NOT NULL,
    email         TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    password_salt BLOB    NOT NULL,
    password_hash BLOB    NOT NULL,
    credits       INTEGER NOT NULL DEFAULT 0 CHECK (credits >= 0),
    created_at    TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS listings (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    tutor_id         INTEGER NOT NULL REFERENCES students(id),
    type             TEXT    NOT NULL CHECK (type IN ('PAID', 'SWAP', 'FREE')),
    skill            TEXT    NOT NULL,
    description      TEXT    NOT NULL DEFAULT '',
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    fee              REAL    NOT NULL DEFAULT 0,
    credit_cost      INTEGER NOT NULL DEFAULT 0,
    active           INTEGER NOT NULL DEFAULT 1,
    created_at       TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS slots (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id INTEGER NOT NULL REFERENCES listings(id),
    start_at   TEXT    NOT NULL,
    end_at     TEXT    NOT NULL,
    mode       TEXT    NOT NULL CHECK (mode IN ('ONLINE', 'IN_PERSON')),
    place      TEXT    NOT NULL,
    booked     INTEGER NOT NULL DEFAULT 0,
    removed    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS bookings (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slot_id      INTEGER NOT NULL REFERENCES slots(id),
    learner_id   INTEGER NOT NULL REFERENCES students(id),
    status       TEXT    NOT NULL CHECK (status IN ('CONFIRMED', 'COMPLETED', 'CANCELLED')),
    held_credits INTEGER NOT NULL DEFAULT 0,
    booked_at    TEXT    NOT NULL
);

-- The last line of defence against double booking: the database itself refuses a second
-- confirmed booking for the same slot, whatever the Java code does.
CREATE UNIQUE INDEX IF NOT EXISTS one_confirmed_booking_per_slot
    ON bookings(slot_id) WHERE status = 'CONFIRMED';

CREATE TABLE IF NOT EXISTS credit_transactions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id    INTEGER NOT NULL REFERENCES students(id),
    type          TEXT    NOT NULL CHECK (type IN ('INITIAL', 'HOLD', 'RELEASE', 'REFUND')),
    amount        INTEGER NOT NULL,
    balance_after INTEGER NOT NULL,
    booking_id    INTEGER REFERENCES bookings(id),
    at            TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id INTEGER NOT NULL REFERENCES listings(id),
    learner_id INTEGER NOT NULL REFERENCES students(id),
    created_at TEXT    NOT NULL,
    UNIQUE (listing_id, learner_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL REFERENCES conversations(id),
    sender_id       INTEGER NOT NULL REFERENCES students(id),
    body            TEXT    NOT NULL,
    sent_at         TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS modules (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id INTEGER NOT NULL REFERENCES listings(id),
    title      TEXT    NOT NULL,
    summary    TEXT    NOT NULL DEFAULT '',
    created_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS exercises (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    module_id INTEGER NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    kind      TEXT    NOT NULL CHECK (kind IN ('MCQ', 'TRUE_FALSE', 'FILL')),
    prompt    TEXT    NOT NULL,
    options   TEXT    NOT NULL DEFAULT '',
    answer    TEXT    NOT NULL,
    points    INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS attempts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    module_id  INTEGER NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
    student_id INTEGER NOT NULL REFERENCES students(id),
    score      INTEGER NOT NULL,
    total      INTEGER NOT NULL,
    taken_at   TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS slots_by_start ON slots(start_at);
CREATE INDEX IF NOT EXISTS messages_by_conversation ON messages(conversation_id, id);
CREATE INDEX IF NOT EXISTS attempts_by_module ON attempts(module_id, student_id);
