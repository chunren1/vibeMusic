import request from './request'

/** 首页 Banner */
export function getBanners() {
  return request.get('/songs/banner')
}

/** 搜索歌曲（Redis 缓存 1h + 网易云 API） */
export function searchSongs(keyword) {
  return request.get('/songs/search', { params: { keyword } })
}

/** 随机推荐歌曲 */
export function getRandomSongs(count = 8) {
  return request.get('/songs/random', { params: { count } })
}

/** 播放歌曲（获取 URL + 记录历史） */
export function playSong(sourceId, name, artist) {
  return request.get('/songs/play', { params: { sourceId, name, artist } })
}

/** 下载歌曲到 RustFS */
export function downloadSong(sourceId, song) {
  return request.post(`/download/${sourceId}`, {
    name: song.name, artist: song.artist, album: song.album,
    coverUrl: song.coverUrl, duration: song.duration,
  })
}

/** 最近播放历史 */
export function getPlayHistory(userId = 1, count = 999) {
  return request.get('/songs/history', { params: { userId, count } })
}

// ========== 收藏 ==========

/** 切换收藏 */
export function toggleFavorite(sourceId, songName, artist, userId = 1) {
  return request.post('/favorites/toggle', { userId, sourceId, songName, artist })
}

/** 我的收藏列表 */
export function getFavorites(userId = 1, count = 50) {
  return request.get('/favorites/list', { params: { userId, count } })
}

/** 获取收藏的 songId 集合 */
export function getFavoriteIds(userId = 1) {
  return request.get('/favorites/ids', { params: { userId } })
}

// ========== 歌单 ==========

/** 获取歌单列表 */
export function getPlaylists(userId = 1) {
  return request.get('/playlists/list', { params: { userId } })
}

/** 创建歌单 */
export function createPlaylist(name, description, userId = 1) {
  return request.post('/playlists/create', { userId, name, description })
}

/** 添加歌曲到歌单 */
export function addToPlaylist(playlistId, song, userId = 1) {
  return request.post('/playlists/add-song', {
    userId, playlistId,
    sourceId: song.sourceId,
    songName: song.name || song.songName,
    artist: song.artist || '',
    coverUrl: song.coverUrl || '',
    duration: song.duration || 0,
  })
}

/** 获取歌单中的歌曲 */
export function getPlaylistSongs(playlistId) {
  return request.get('/playlists/songs', { params: { playlistId } })
}

/** 从歌单移除歌曲 */
export function removeFromPlaylist(userId, playlistId, sourceId) {
  return request.delete('/playlists/remove-song', { params: { userId, playlistId, sourceId } })
}

/** 删除歌单 */
export function deletePlaylist(playlistId, userId = 1) {
  return request.delete('/playlists/delete', { params: { userId, playlistId } })
}
