-- 测试基础数据（每次方法执行前清空 + 插入，事务回滚后自动清除）
DELETE FROM user_favorite;
DELETE FROM play_history;
DELETE FROM playlist_song;
DELETE FROM playlist;
DELETE FROM song;
DELETE FROM users;

INSERT INTO users (id, username, password, nickname) VALUES
(1, 'testuser', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'testuser');

INSERT INTO song (id, source_id, name, artist, album, platform, url) VALUES
(1, '000rh0dE2TyUic', '晴天', '周杰伦', '叶惠美', 'netease', 'http://localhost/test/song1.mp3'),
(2, '001abcTest001', '七里香', '周杰伦', '七里香', 'netease', 'http://localhost/test/song2.mp3'),
(3, '002defTest002', '夜曲', '周杰伦', '十一月的肖邦', 'qq', 'http://localhost/test/song3.mp3');

INSERT INTO play_history (id, user_id, source_id, song_name, artist, cover_url) VALUES
(1, 1, '000rh0dE2TyUic', '晴天', '周杰伦', 'http://img.test/cover1.jpg');

INSERT INTO user_favorite (id, user_id, source_id, song_name, artist) VALUES
(1, 1, '000rh0dE2TyUic', '晴天', '周杰伦');
