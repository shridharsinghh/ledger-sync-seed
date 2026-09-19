package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.ingest.Deduper.DedupedTxn;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns deduped transactions into categorized, ledger-ready ones, and finds
 * what does not add up.
 */
public final class Categorizer {

    private Categorizer() {}

    private static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100.00");

    // How close in time two legs of a self-transfer have to be to count as
    // the same event. The widest gap actually observed between a matched
    // pair in corpus-a is 2 minutes; 15 minutes leaves headroom for a
    // slower-posting bank on the corpus we have not seen, while still being
    // far tighter than the gap between two coincidentally-equal, unrelated
    // transactions would need to be.
    private static final Duration TRANSFER_TOLERANCE = Duration.ofMinutes(15);

    public record Result(List<NormalizedTxn> transactions, List<Discrepancy> discrepancies) {}

    public static Result categorize(List<DedupedTxn> deduped) {
        boolean[] isTransfer = pairTransfers(deduped);
        List<Discrepancy> discrepancies = new ArrayList<>();
        List<NormalizedTxn> out = new ArrayList<>();

        for (int i = 0; i < deduped.size(); i++) {
            DedupedTxn d = deduped.get(i);
            Category category = isTransfer[i] ? Category.TRANSFER : defaultCategory(d);

            out.add(new NormalizedTxn(d.accountLast4(), d.occurredAt(), d.direction(),
                    d.amount(), category, d.merchant(), d.sourceMessageIds()));

            if (!isTransfer[i] && claimsTransfer(d.merchant())) {
                discrepancies.add(new Discrepancy(d.accountLast4(), d.occurredAt(), d.amount(),
                        "merchant text ('" + d.merchant() + "') claims a self/internal transfer, "
                                + "but no matching opposite-direction leg of the same amount was "
                                + "found in another account within " + TRANSFER_TOLERANCE.toMinutes()
                                + " minutes; recorded as " + category + " instead"));
            }
        }

        discrepancies.addAll(checkBalanceChains(deduped));
        return new Result(out, discrepancies);
    }

    private static Category defaultCategory(DedupedTxn d) {
        boolean isMicroUpiDebit = d.direction() == Direction.DEBIT
                && d.merchant() != null
                && d.merchant().toUpperCase().startsWith("UPI")
                && d.amount().compareTo(MICRO_THRESHOLD) <= 0;
        if (isMicroUpiDebit) return Category.MICRO;
        return d.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    /**
     * Greedy nearest-time matching: among all cross-account, opposite-
     * direction, equal-amount candidate pairs within TRANSFER_TOLERANCE,
     * take the closest-in-time pair first, then the next closest among what
     * is left, so each transaction is used as at most one leg of at most one
     * transfer.
     */
    private static boolean[] pairTransfers(List<DedupedTxn> deduped) {
        boolean[] used = new boolean[deduped.size()];
        List<long[]> candidates = new ArrayList<>(); // {i, j, deltaSeconds}

        for (int i = 0; i < deduped.size(); i++) {
            DedupedTxn a = deduped.get(i);
            for (int j = i + 1; j < deduped.size(); j++) {
                DedupedTxn b = deduped.get(j);
                if (a.accountLast4().equals(b.accountLast4())) continue;
                if (a.direction() == b.direction()) continue;
                if (a.amount().compareTo(b.amount()) != 0) continue;
                long delta = Math.abs(Duration.between(a.occurredAt(), b.occurredAt()).getSeconds());
                if (delta > TRANSFER_TOLERANCE.getSeconds()) continue;
                candidates.add(new long[]{i, j, delta});
            }
        }
        candidates.sort(Comparator.comparingLong(c -> c[2]));

        for (long[] c : candidates) {
            int i = (int) c[0], j = (int) c[1];
            if (used[i] || used[j]) continue;
            used[i] = true;
            used[j] = true;
        }
        return used;
    }

    private static boolean claimsTransfer(String merchant) {
        if (merchant == null) return false;
        String m = merchant.toUpperCase();
        return m.contains("SELF") || m.contains("P2A") || m.contains("P2P")
                || m.contains("OWN A/C") || m.contains("OWN ACCOUNT");
    }

    /**
     * For each account, walk its transactions in time order and check the
     * bank's own stated balance (when a message quoted one) against what our
     * running total expects. A mismatch means either a transaction we did
     * not capture, or one we captured wrong - either way, something the
     * ledger cannot account for on its own.
     *
     * The running total resets to the bank's stated balance after each check
     * so one bad reading does not cascade into every later one.
     */
    private static List<Discrepancy> checkBalanceChains(List<DedupedTxn> deduped) {
        List<Discrepancy> out = new ArrayList<>();
        var byAccount = deduped.stream().collect(Collectors.groupingBy(DedupedTxn::accountLast4));

        for (var entry : byAccount.entrySet()) {
            List<DedupedTxn> txns = new ArrayList<>(entry.getValue());
            txns.sort(Comparator.comparing(DedupedTxn::occurredAt));

            BigDecimal running = null;
            for (DedupedTxn t : txns) {
                BigDecimal signed = t.direction() == Direction.DEBIT
                        ? t.amount().negate() : t.amount();

                if (running != null && t.statedBalance() != null) {
                    BigDecimal expected = running.add(signed);
                    if (expected.compareTo(t.statedBalance()) != 0) {
                        BigDecimal gap = t.statedBalance().subtract(expected);
                        out.add(new Discrepancy(t.accountLast4(), t.occurredAt(), t.amount(),
                                "running balance expected " + expected.toPlainString()
                                        + " after this transaction, bank stated "
                                        + t.statedBalance().toPlainString()
                                        + " (gap " + gap.toPlainString() + ")"));
                    }
                }

                if (t.statedBalance() != null) {
                    running = t.statedBalance(); // trust the bank's figure going forward
                } else if (running != null) {
                    running = running.add(signed);
                }
            }
        }
        return out;
    }
}
