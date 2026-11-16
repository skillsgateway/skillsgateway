-- A machine credential may now be issued without an expiry, but only by an explicit request
-- (GW_AUTH_0077). The service still refuses an omitted expiry and a never-expiring credential
-- under a configured lifetime cap, so this constraint is no longer the backstop for that rule;
-- what it forbade was the very thing an administrator may now choose.
--
-- Existing rows all satisfy the weaker rule, so there is nothing to repair.
ALTER TABLE access_tokens DROP CONSTRAINT machine_credentials_expire;
