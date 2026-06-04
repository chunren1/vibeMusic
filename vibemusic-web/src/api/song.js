import request from './request'

/** 搜索歌曲（Redis 缓存 1h + 网易云 API） */
export function searchSongs(keyword) {
  return request.get('/songs/search', { params: { keyword } })
}

/** 随机推荐歌曲 */
export function getRandomSongs(count = 8) {
  return request.get('/songs/random', { params: { count } })
}

/**
 * 播放歌曲（获取 URL + 记录历史）
 * @param {string} sourceId - 网易云歌曲 ID
 * @param {string} name     - 歌曲名
 * @param {string} artist   - 歌手名
 */
export function playSong(sourceId, name, artist) {
  return request.get('/songs/play', {
    params: { sourceId, name, artist }
  })
}

/**
 * 下载歌曲到 RustFS（同时存入 DB）
 * @param {string} sourceId - 网易云歌曲 ID
 * @param {object} song     - 完整歌曲信息 { name, artist, album, coverUrl, duration }
 */
export function downloadSong(sourceId, song) {
  return request.post(`/download/${sourceId}`, {
    name: song.name,
    artist: song.artist,
    album: song.album,
    coverUrl: song.coverUrl,
    duration: song.duration,
  })
}

/** 最近播放历史 */
export function getPlayHistory(userId = 0, count = 20) {
  return request.get('/songs/history', { params: { userId, count } })
}
