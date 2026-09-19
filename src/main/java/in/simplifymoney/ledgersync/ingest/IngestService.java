package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.ingest.Deduper.DedupedTxn;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Each run recomputes the full ledger: whatever is already in the store is
 * treated as one more source of transactions, merged by identity (account +
 * occurred_at + direction + amount + merchant) against the freshly parsed
 * corpus, deduped, categorized, and written back as a whole. This is what
 * makes "ingest the same corpus twice" and "ingest an overlapping corpus"
 * converge to the same ledger instead of accumulating duplicates - the store
 * itself promises no uniqueness, so idempotency is enforced here.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsed.add(p.get());
        }

        List<DedupedTxn> fromCorpus = Deduper.dedupe(parsed);
        List<DedupedTxn> fromStore = alreadyStored();
        List<DedupedTxn> merged = merge(fromStore, fromCorpus);

        Categorizer.Result result = Categorizer.categorize(merged);

        store.clear();
        for (NormalizedTxn t : result.transactions()) store.save(t);
        for (Discrepancy d : result.discrepancies()) store.saveDiscrepancy(d);

        return new Stats(messages.size(), result.transactions().size(), skipped);
    }

    /** What the store already holds, reduced back to mergeable, deduped shape. */
    private List<DedupedTxn> alreadyStored() {
        List<DedupedTxn> out = new ArrayList<>();
        for (NormalizedTxn t : store.all()) {
            // Rows whose every source id looks like "m-legacy-*" came from
            // V2__seed.sql, not from a previous `ingest` of a real corpus
            // file - that seed data exists to exercise Backfill/
            // ConsistencyChecker (Task 4) against pre-existing, known-dirty
            // SQL rows, and is a different concern from this corpus's own
            // ledger. Treating it as ingest history would silently fold
            // synthetic legacy numbers into corpus-a's totals.
            if (isLegacySeed(t)) continue;

            // The stated balance behind an already-stored row is not
            // retained on NormalizedTxn (it is not part of the frozen
            // shape), so it is unknown here. That only affects the
            // balance-chain reconciliation check for rows ingested in an
            // earlier run; it does not affect dedup or categorization,
            // which depend only on account/time/direction/amount/merchant.
            out.add(new DedupedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                    t.amount(), t.merchant(), null, t.sourceMessageIds()));
        }
        return out;
    }

    private static boolean isLegacySeed(NormalizedTxn t) {
        return t.sourceMessageIds().stream().allMatch(id -> id.startsWith("m-legacy-"));
    }

    private static List<DedupedTxn> merge(List<DedupedTxn> a, List<DedupedTxn> b) {
        Map<String, DedupedTxn> byKey = new LinkedHashMap<>();
        for (DedupedTxn d : a) byKey.merge(key(d), d, IngestService::combine);
        for (DedupedTxn d : b) byKey.merge(key(d), d, IngestService::combine);
        return new ArrayList<>(byKey.values());
    }

    private static DedupedTxn combine(DedupedTxn x, DedupedTxn y) {
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(x.sourceMessageIds());
        ids.addAll(y.sourceMessageIds());
        var balance = x.statedBalance() != null ? x.statedBalance() : y.statedBalance();
        return new DedupedTxn(x.accountLast4(), x.occurredAt(), x.direction(), x.amount(),
                x.merchant(), balance, List.copyOf(ids));
    }

    private static String key(DedupedTxn d) {
        return d.accountLast4() + '|' + d.occurredAt() + '|' + d.direction() + '|'
                + d.amount().stripTrailingZeros().toPlainString() + '|'
                + (d.merchant() == null ? "" : d.merchant().trim().toUpperCase());
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
