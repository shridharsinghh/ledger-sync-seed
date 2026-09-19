package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Something the ledger could not account for - a row of reconciliation.json.
 *
 * Two kinds show up today (see Categorizer and IngestService):
 *   - a transaction's own text claims a self/internal transfer but no
 *     matching opposite-direction leg of the same amount was found
 *   - the bank's stated balance after a transaction does not match the
 *     balance our own running total expects, given everything ingested
 *     for that account up to that point
 */
public record Discrepancy(
        String accountLast4,
        OffsetDateTime occurredAt,
        BigDecimal amount,
        String note) {
}
