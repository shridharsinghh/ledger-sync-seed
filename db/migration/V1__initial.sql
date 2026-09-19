-- The ledger, as originally written.
--
-- NOTE: originally declared `id IDENTITY PRIMARY KEY`. SqlLedgerStore opens
-- the connection with MODE=PostgreSQL, and H2 2.2.224 does not recognize
-- IDENTITY as a type in that mode ("Unknown data type: IDENTITY") - `migrate`
-- failed before a single row could ever be written. SERIAL is the
-- PostgreSQL-mode equivalent and was confirmed to work.
CREATE TABLE IF NOT EXISTS ledger (
    id                 SERIAL PRIMARY KEY,
    account_last4      VARCHAR(4)     NOT NULL,
    occurred_at        VARCHAR(40)    NOT NULL,
    direction          VARCHAR(6)     NOT NULL,
    amount             DECIMAL(14, 2) NOT NULL,
    category           VARCHAR(10)    NOT NULL,
    merchant           VARCHAR(120),
    source_message_ids VARCHAR(500)   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger (account_last4);
