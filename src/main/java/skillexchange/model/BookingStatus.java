package skillexchange.model;

public enum BookingStatus {
    CONFIRMED("Upcoming"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    BookingStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
