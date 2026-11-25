package skillexchange.model;

import java.io.Serializable;

/** The four numbers on the sign-in screen. */
public record Stats(int students, int listings, int openSessions, int completedSessions) implements Serializable { }
