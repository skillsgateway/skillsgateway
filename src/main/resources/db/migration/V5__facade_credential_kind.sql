-- What kind of credential authenticated a facade fetch (GW_AUTH_0041), now that there is more than
-- one kind: a gateway-issued personal access token, or a bearer token from the identity provider
-- the web surface trusts (GW_AUTH_0040).
--
-- Why a column and not an inference. The obvious reading of "no token id" is "not a PAT", and it
-- is wrong twice over: `token_id` is already NULL on every administrative entry, and on every
-- facade entry written before per-token attribution existed (GW_AUTH_0009). Giving that NULL a third
-- meaning would leave the ledger unable to answer, without guessing, the question it exists to
-- answer after a leak.
--
-- Nullable, with no default. NULL is the honest value for an entry no credential authenticated --
-- everything the gateway wrote about itself -- and for facade entries older than this column.
-- Backfilling the existing facade rows to 'pat' would be true today and would also be the gateway
-- writing history it did not witness; the ledger is append-only, and "we do not know" is a thing it
-- is allowed to say.
--
-- Denormalised and not a foreign key, for the same reason `actor_type` is: an entry must still mean
-- what it meant after the credential it names has been revoked and its row deleted.

CREATE TYPE fetch_log_credential_kind AS ENUM ('pat', 'idp');

ALTER TABLE fetch_log ADD COLUMN credential_kind fetch_log_credential_kind;
