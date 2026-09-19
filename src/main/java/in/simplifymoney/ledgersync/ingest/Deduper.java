package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses ParsedTxn records that describe the same real-world transaction
 * into one DedupedTxn.
 *
 * Two things create duplicates in this corpus, neither of them a
 * message-level accident to special-case:
 *   - the same underlying bank event is reported twice, once by SMS and once
 *     by email (same account/time/direction/amount/merchant, different
 *     channel and message_id)
 *   - the same message is re-uploaded by the phone with a brand new
 *     message_id (RawMessage documents this explicitly - message_id
 *     identifies the upload, not the underlying message)
 *
 * Neither case can be told apart by message_id, so the key here is the
 * transaction's own content: which account, when the bank says it happened,
 * which way the money moved, how much, and to/from whom. Two parses that
 * agree on all five are the same transaction, however many times or ways it
 * was uploaded.
 */
public final class Deduper {

    private Deduper() {}

    public record DedupedTxn(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount,
            String merchant,
            BigDecimal statedBalance,
            List<String> sourceMessageIds) {
    }

    public static List<DedupedTxn> dedupe(List<ParsedTxn> parsed) {
        Map<String, List<ParsedTxn>> groups = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            groups.computeIfAbsent(key(p), k -> new ArrayList<>()).add(p);
        }

        List<DedupedTxn> out = new ArrayList<>();
        for (List<ParsedTxn> group : groups.values()) {
            ParsedTxn first = group.get(0);

            List<String> ids = new ArrayList<>();
            BigDecimal balance = null;
            for (ParsedTxn p : group) {
                ids.add(p.sourceMessageId());
                // Prefer a non-null stated balance if any upload carried one
                // (e.g. one channel quotes it, another doesn't).
                if (balance == null && p.statedBalance() != null) {
                    balance = p.statedBalance();
                }
            }
            ids.sort(String::compareTo);

            out.add(new DedupedTxn(first.accountLast4(), first.occurredAt(),
                    first.direction(), first.amount(), first.merchant(), balance,
                    List.copyOf(ids)));
        }
        return out;
    }

    private static String key(ParsedTxn p) {
        return p.accountLast4() + '|'
                + p.occurredAt() + '|'
                + p.direction() + '|'
                + p.amount().stripTrailingZeros().toPlainString() + '|'
                + normalizeMerchant(p.merchant());
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().toUpperCase();
    }
}
