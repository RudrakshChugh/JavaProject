package skillexchange.model;

import java.io.Serializable;
import java.util.List;

/** Everything the Credits screen shows for one student. */
public record CreditSummary(int balance, int held, List<CreditTransaction> history) implements Serializable {
    public CreditSummary {
        history = List.copyOf(history);   // defensive copy: the record cannot be changed from outside
    }
}
