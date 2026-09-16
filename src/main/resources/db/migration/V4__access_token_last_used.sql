-- When a credential most recently authenticated successfully (GW_AUTH_0031). NULL means it never
-- has -- including every credential that predates this column, which is not backfilled: the ledger
-- carries token ids for facade fetches but the machine API chain never wrote one, so any backfill
-- would be honest for one chain and a guess for the other.
--
-- Written at most once per credential per minute, so this is approximate to that bound by design.
-- The append-only `fetch_log` remains the exact, per-request record; this column exists so that
-- "is anybody still using this credential?" is a fact of the credential rather than a ledger query.
-- Deliberately no index: it is read only alongside the row it belongs to, never filtered on.

ALTER TABLE access_tokens ADD COLUMN last_used_at TIMESTAMPTZ;
