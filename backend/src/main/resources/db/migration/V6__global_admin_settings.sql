CREATE TABLE app_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_settings (setting_key, setting_value)
VALUES ('crawler.respectRobotsTxt', 'true')
ON CONFLICT (setting_key) DO NOTHING;
