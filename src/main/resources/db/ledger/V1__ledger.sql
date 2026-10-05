-- The books. Balances are derived from postings and kept on the account row for speed and for
-- locking; journal entries and postings are immutable (corrections are reversing entries).

CREATE TABLE accounts (
    name           text PRIMARY KEY,
    kind           text NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY')),
    allow_negative boolean NOT NULL,
    balance_paise  bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (allow_negative OR balance_paise >= 0)
);

CREATE TABLE journal_entries (
    id              uuid PRIMARY KEY,
    idempotency_key text NOT NULL UNIQUE,
    request_hash    text NOT NULL,
    description     text NOT NULL,
    reference       text,
    posted_at       timestamptz NOT NULL
);

CREATE TABLE postings (
    entry_id     uuid NOT NULL REFERENCES journal_entries (id),
    seq          int NOT NULL,
    account      text NOT NULL REFERENCES accounts (name),
    side         text NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    PRIMARY KEY (entry_id, seq)
);

CREATE INDEX postings_by_account ON postings (account, entry_id);

-- Nothing in the journal is ever changed or removed, whatever the code does.
CREATE FUNCTION journal_is_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'journal entries are immutable; post a reversing entry instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION journal_is_immutable();
CREATE TRIGGER postings_immutable BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION journal_is_immutable();
