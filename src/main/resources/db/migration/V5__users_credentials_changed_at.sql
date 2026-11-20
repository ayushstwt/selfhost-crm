-- Set when the password changes. Web sessions authenticated before this instant are ended.
ALTER TABLE users ADD COLUMN credentials_changed_at TIMESTAMPTZ;
