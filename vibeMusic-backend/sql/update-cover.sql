ALTER TABLE play_history ADD COLUMN cover_url VARCHAR(500) AFTER artist;
ALTER TABLE user_favorite ADD COLUMN cover_url VARCHAR(500) AFTER artist;
