-- Best-effort forge metadata captured at registration (GW_0021).
ALTER TABLE marketplaces ADD COLUMN forge TEXT;
ALTER TABLE marketplaces ADD COLUMN forge_project TEXT;
ALTER TABLE marketplaces ADD COLUMN description TEXT;
ALTER TABLE marketplaces ADD COLUMN upstream_updated_at TIMESTAMPTZ;
