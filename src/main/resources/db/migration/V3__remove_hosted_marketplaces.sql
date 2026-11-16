-- The gateway no longer hosts marketplaces (ADR 0024, GW_AUTH_0090): no hosted origin, no push
-- policy, no push scope on a token. This migration takes the schema for all of that away.
--
-- THIS MIGRATION DELETES DATA, BY OWNER DECISION FOR A BETA. Every hosted marketplace -- live or
-- already removed -- is purged together with everything that depends on it: its snapshots, their
-- vetting history, its waivers, exemptions, grants and settings, and the ledger entries recorded
-- under its marketplace id. A live hosted marketplace therefore stops being served at the moment
-- of the upgrade, and its approval history is gone. AGENTS.md says migrations meet live data; this
-- one meets it on purpose, and says so here, in the guide and in the pull request. It never
-- refuses: the owner chose a clean schema over a record of marketplaces that no longer exist.
--
-- It touches no repository. The bare repositories at {data-dir}/hosted/{name}.git (or the object
-- store's hosted/ prefix) are the only copy of each publisher's source and are not in the
-- database; the gateway lists them to administrators until the operator has copied and deleted
-- them (guides/removing-hosted-marketplaces).
--
-- Dependents, enumerated from the schema's foreign keys and its marketplace/snapshot columns:
--   snapshots.marketplace_id                       no cascade       -> deleted explicitly, first
--   snapshot_closures (+ members), snapshot_facts (+ plugin names), vetting_runs (+ verdicts,
--     findings), snapshot_vetting_overrides, snapshot_revocation_reversals
--                                                  ON DELETE CASCADE from snapshots
--   vetting_waivers, auto_approval_exemptions, role_grants, vetter_toggles, vetting_chain_modes,
--     vetting_chain_orders                         ON DELETE CASCADE from marketplaces
--   marketplaces.last_ingest_snapshot_id           ON DELETE SET NULL (the row itself goes)
--   fetch_log.marketplace_id                       no FK by design  -> deleted by id
--   fetch_log.marketplace (a NAME), staging_ref_sightings.marketplace (a NAME)
--                                                                    -> deleted by name, below
-- webhook_deliveries carry the name only inside a payload already sent; they are left.
--
-- THE NAME-REUSE TRAP. A removed marketplace's name can be registered again, so the ledger is never
-- purged by name alone. Entries with a purged marketplace_id go. Older entries with no
-- marketplace_id (written before identities were recorded) go only when their name equals a purged
-- marketplace's name AND no marketplace that survives has ever carried that name. Where a survivor
-- has, those older entries are LEFT: they cannot be told from the survivor's own. A sink's
-- cursor_position is a ledger id and the export reads id > cursor in order, so a gap in the ids is
-- harmless; entries already exported to a SIEM stay there.

CREATE TEMP TABLE purged_marketplaces ON COMMIT DROP AS
    SELECT id, name FROM marketplaces WHERE origin = 'hosted';

CREATE TEMP TABLE purged_names ON COMMIT DROP AS
    SELECT DISTINCT p.name
    FROM purged_marketplaces p
    WHERE NOT EXISTS (
        SELECT 1 FROM marketplaces m
        WHERE m.name = p.name AND m.id NOT IN (SELECT id FROM purged_marketplaces));

DELETE FROM fetch_log WHERE marketplace_id IN (SELECT id FROM purged_marketplaces);
DELETE FROM fetch_log WHERE marketplace_id IS NULL AND marketplace IN (SELECT name FROM purged_names);
DELETE FROM staging_ref_sightings WHERE marketplace IN (SELECT name FROM purged_names);

DELETE FROM snapshots WHERE marketplace_id IN (SELECT id FROM purged_marketplaces);
DELETE FROM marketplaces WHERE id IN (SELECT id FROM purged_marketplaces);

-- Marketplaces: the constraints that exist only for hosting go, then the columns and types. Nothing
-- may lack a URL any more, so url is required again and nothing keeps a special case.
ALTER TABLE marketplaces DROP CONSTRAINT marketplaces_hosted_is_on_demand;
ALTER TABLE marketplaces DROP CONSTRAINT marketplaces_upstream_has_url;
ALTER TABLE marketplaces ALTER COLUMN url SET NOT NULL;
ALTER TABLE marketplaces DROP COLUMN origin, DROP COLUMN push_policy;
DROP TYPE marketplace_origin;
DROP TYPE marketplace_push_policy;

-- Access tokens keep every fetch scope they had. push_scopes could only ever name a hosted
-- marketplace, and none exists. A token whose only grant was a push scope is, for fetching, exactly
-- what it already was: with no fetch scopes it fetches every marketplace, so operators should
-- revoke tokens that were issued only to publish. The never-empty rule loses its push clause.
ALTER TABLE access_tokens DROP CONSTRAINT scope_lists_are_never_empty;
ALTER TABLE access_tokens DROP COLUMN push_scopes;
ALTER TABLE access_tokens ADD CONSTRAINT scope_lists_are_never_empty CHECK (
    (scopes IS NULL OR cardinality(scopes) > 0)
    AND (api_scopes IS NULL OR cardinality(api_scopes) > 0));
