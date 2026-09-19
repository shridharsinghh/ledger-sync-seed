-- Reconciliation discrepancies, computed at ingest time. The `report`
-- command is a separate process/run from `ingest`, so anything reconciliation
-- needs that isn't in NormalizedTxn (e.g. why something didn't add up) has to
-- be persisted somewhere the report step can read it back from.
CREATE TABLE IF NOT EXISTS discrepancies (
    id             SERIAL PRIMARY KEY,
    account_last4  VARCHAR(4)     NOT NULL,
    occurred_at    VARCHAR(40)    NOT NULL,
    amount         DECIMAL(14, 2) NOT NULL,
    note           VARCHAR(500)   NOT NULL
);
