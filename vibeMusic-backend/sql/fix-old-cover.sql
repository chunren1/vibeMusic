-- 从播放历史补全收藏中的封面URL
UPDATE user_favorite uf
JOIN play_history ph ON uf.source_id = ph.source_id AND uf.user_id = ph.user_id
SET uf.cover_url = ph.cover_url
WHERE uf.cover_url IS NULL AND ph.cover_url IS NOT NULL;
