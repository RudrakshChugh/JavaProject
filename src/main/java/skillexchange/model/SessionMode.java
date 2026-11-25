package skillexchange.model;

/** Where a session happens. The slot's "place" is a meeting link for ONLINE and a room for IN_PERSON. */
public enum SessionMode {
    ONLINE("Online", "Meeting link"),
    IN_PERSON("In person", "Room or place");

    private final String label;
    private final String placeLabel;

    SessionMode(String label, String placeLabel) {
        this.label = label;
        this.placeLabel = placeLabel;
    }

    public String getLabel() { return label; }
    public String getPlaceLabel() { return placeLabel; }

    @Override
    public String toString() { return label; }
}
