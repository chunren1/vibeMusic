ALTER TABLE users ADD COLUMN netease_cookie_enc TEXT NULL;
ALTER TABLE users ADD COLUMN netease_cookie_iv VARCHAR(32) NULL;
ALTER TABLE users ADD COLUMN netease_cookie_updated_at DATETIME NULL;
ALTER TABLE users ADD COLUMN netease_cookie_valid TINYINT(1) NULL;
ALTER TABLE users ADD COLUMN bili_sessdata_enc TEXT NULL;
ALTER TABLE users DROP COLUMN netease_cookie_enc, DROP COLUMN netease_cookie_iv, DROP COLUMN netease_cookie_updated_at, DROP COLUMN netease_cookie_valid, DROP COLUMN bili_sessdata_enc;
