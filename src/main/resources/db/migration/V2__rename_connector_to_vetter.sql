-- The element of the vetting chain is a vetter; "connector" now means only the HTTP transport that
-- lets a vetter running outside the gateway take part (skills-gateway.vetting.external[*]). Nothing
-- in this schema is that transport — it is configuration, never rows — so every occurrence of the
-- word here is the chain element and is renamed.
--
-- A rename rather than an additive column: the project is pre-1.0 and the gateway is the only
-- writer of these tables, so carrying both spellings would only give the two names time to drift.

ALTER TABLE vetting_verdicts RENAME COLUMN connector TO vetter;
ALTER TABLE vetting_verdicts RENAME CONSTRAINT vetting_verdicts_run_id_connector_key TO vetting_verdicts_run_id_vetter_key;

ALTER TABLE snapshot_vetting_overrides RENAME COLUMN blocking_connectors TO blocking_vetters;

ALTER TABLE connector_toggles RENAME TO vetter_toggles;
ALTER TABLE vetter_toggles RENAME COLUMN connector TO vetter;
ALTER TABLE vetter_toggles RENAME CONSTRAINT connector_toggles_pkey TO vetter_toggles_pkey;
ALTER TABLE vetter_toggles RENAME CONSTRAINT connector_toggles_connector_check TO vetter_toggles_vetter_check;
ALTER TABLE vetter_toggles RENAME CONSTRAINT connector_toggles_connector_marketplace_id_key TO vetter_toggles_vetter_marketplace_id_key;
ALTER TABLE vetter_toggles RENAME CONSTRAINT connector_toggles_updated_by_check TO vetter_toggles_updated_by_check;
ALTER TABLE vetter_toggles RENAME CONSTRAINT connector_toggles_marketplace_id_fkey TO vetter_toggles_marketplace_id_fkey;
ALTER INDEX idx_connector_toggles_lookup RENAME TO idx_vetter_toggles_lookup;
ALTER SEQUENCE connector_toggles_id_seq RENAME TO vetter_toggles_id_seq;

-- The two rule identifiers the chain itself issues are renamed with the word. A waiver is written
-- against a rule identifier, so a standing acceptance has to follow the rename or it would silently
-- stop covering the finding it was written for. 'connector-disabled' becomes 'vetter-not-run' rather
-- than 'vetter-disabled': the ledger event for switching a vetter off already owns that name, and
-- one string meaning two things on the audit surface was the collision this rename exists to remove.
UPDATE vetting_waivers SET rule_id = 'vetter-error' WHERE rule_id = 'connector-error';
UPDATE vetting_waivers SET rule_id = 'vetter-not-run' WHERE rule_id = 'connector-disabled';
