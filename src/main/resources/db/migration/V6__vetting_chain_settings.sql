-- The two administrative settings that shape a vetting chain run beyond which vetters are switched
-- on: the chain mode (GW_VETTING_0032) and the vetter order (GW_VETTING_0033).
--
-- Both are stored the way vetter_toggles is, and resolved by the rule it established: the row
-- scoped to a marketplace, else the global row (marketplace_id IS NULL), else the default. An empty
-- pair of tables is exactly the behaviour before this migration -- every vetter runs, in its
-- configured position -- so nothing is backfilled.
--
-- Two tables rather than one row carrying two values. Each setting has its own note, its own acting
-- administrator and its own timestamp, and one row would have to carry two of each or lose the
-- attribution the toggle table establishes.

-- Recorded in place of a vetter the chain never got to, because it had already stopped
-- (GW_VETTING_0032.2). Deliberately not 'disabled': that records a standing administrative decision
-- and this records a run that ran out of road, the two have different remedies, and the ledger is
-- where the distinction has to survive. Like 'disabled' it neither clears nor blocks on its own;
-- unlike it, a run that carries one can never have a non-blocked effective outcome
-- (GW_VETTING_0032.3).
ALTER TYPE vetting_verdict_state ADD VALUE 'not_reached';

-- Whether the chain runs every enabled vetter or stops after the first blocking failure.
CREATE TYPE vetting_chain_mode_mode AS ENUM ('run-all', 'stop-after-fail');

CREATE TABLE vetting_chain_modes (
    id BIGSERIAL PRIMARY KEY,
    marketplace_id BIGINT REFERENCES marketplaces (id) ON DELETE CASCADE,
    mode vetting_chain_mode_mode NOT NULL,
    -- The administrator's optional note, mirrored onto the ledger entry the change writes.
    reason TEXT,
    updated_by TEXT NOT NULL CHECK (updated_by <> ''),
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (marketplace_id)
);

CREATE TABLE vetting_chain_orders (
    id BIGSERIAL PRIMARY KEY,
    marketplace_id BIGINT REFERENCES marketplaces (id) ON DELETE CASCADE,
    -- The vetter names an administrator arranged, in the order they arranged them. A whole value
    -- that is replaced and never partially edited, so an array rather than a join table. It need
    -- not name every vetter: the ones it does not name follow in their configured positions, which
    -- is what keeps an override from silently dropping a control a later release adds.
    vetters TEXT[] NOT NULL CHECK (cardinality(vetters) > 0),
    reason TEXT,
    updated_by TEXT NOT NULL CHECK (updated_by <> ''),
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (marketplace_id)
);
