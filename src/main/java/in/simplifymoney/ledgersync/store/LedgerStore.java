package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Where transactions live.
 *
 * Note what this interface does NOT promise: that saving the same transaction
 * twice results in one row.
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    List<NormalizedTxn> all();

    long count();

    /**
     * Removes everything (transactions and discrepancies). IngestService uses
     * this to make ingestion idempotent: rather than trying to patch rows in
     * place against a store with no uniqueness guarantee, each ingest run
     * recomputes the full deduped+categorized state (old rows merged with the
     * newly read corpus) and rewrites the store from scratch. Re-running the
     * same corpus, or an overlapping one, therefore converges to the same
     * result rather than accumulating duplicates.
     */
    void clear();

    /**
     * Discrepancies are computed once, at ingest time (that is when the raw
     * messages - and their stated balances - are available). They are
     * persisted here so a later, separate `report` run can read them back.
     */
    void saveDiscrepancy(Discrepancy d);

    List<Discrepancy> discrepancies();
}
