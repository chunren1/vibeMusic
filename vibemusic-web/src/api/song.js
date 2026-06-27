import request from './request'

// AbortController 管理搜索请求竞态
let searchController = null

/** 获取歌词 */
export function getLyric(sourceId) {
  return request.get('/songs/lyric', { params: { sourceId } })
}

/** 首页 Banner */
export function getBanners() {
  return request.get('/songs/banner')
}

/** 搜索歌曲（支持分源过滤和分页，自动取消旧请求防竞态） */
export function searchSongs(keyword, page = 1, size = 20, platform = null) {
  // 取消上一次未完成的搜索请求
  if (searchController) {
    searchController.abort()
  }
  searchController = new AbortController()

  const params = { keyword, page, size }
  if (platform) params.platform = platform
  return request.get('/songs/search', { params, signal: searchController.signal })
}

/** 随机推荐歌曲 */
export function getRandomSongs(count = 8) {
  return request.get('/songs/random', { params: { count } })
}

/** 个性化推荐 */
export function getPersonalizedRecommend(deviceId = null, refresh = false) {
  const config = { params: {} }
  if (deviceId) {
    config.headers = { 'X-Device-Id': deviceId }
    config.params.deviceId = deviceId
  }
  if (refresh) {
    config.params.refresh = true
  }
  return request.get('/recommend/personalized', config)
}

/** 播放歌曲（获取 URL + 记录历史） */
export function playSong(sourceId, name, artist, coverUrl, platform = '') {
  return request.get('/songs/play', { params: { sourceId, name, artist, coverUrl, platform } })
}

/** 下载歌曲到 RustFS */
export function downloadSong(sourceId, song) {
  return request.post(`/download/${sourceId}`, {
    name: song.name, artist: song.artist, album: song.album,
    coverUrl: song.coverUrl, duration: song.duration,
  })
}

/** 最近播放历史 */
export function getPlayHistory(count = 999) {
  return request.get('/songs/history', { params: { count } })
}

/** 批量删除播放历史 */
export function removePlayHistoryBatch(sourceIds) {
  return request.post('/songs/history/remove', { sourceIds })
}

// ========== 收藏 ==========

/** 切换收藏 */
export function toggleFavorite(sourceId, songName, artist, coverUrl) {
  return request.post('/favorites/toggle', { sourceId, songName, artist, coverUrl })
}

/** 我的收藏列表 */
export function getFavorites(count = 50) {
  return request.get('/favorites/list', { params: { count } })
}

/** 获取收藏的 songId 集合 */
export function getFavoriteIds() {
  return request.get('/favorites/ids')
}

/** 批量取消收藏 */
export function removeFavoritesBatch(sourceIds) {
  return request.post('/favorites/remove-batch', { sourceIds })
}

// ========== 歌单 ==========

/** 获取歌单列表 */
export function getPlaylists() {
  return request.get('/playlists/list')
}

/** 创建歌单 */
export function createPlaylist(name, description) {
  return request.post('/playlists/create', { name, description })
}

/** 添加歌曲到歌单 */
export function addToPlaylist(playlistId, song) {
  return request.post('/playlists/add-song', {
    playlistId,
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
export function removeFromPlaylist(playlistId, sourceId) {
  return request.delete('/playlists/remove-song', { params: { playlistId, sourceId } })
}

/** 删除歌单 */
export function deletePlaylist(playlistId) {
  return request.delete('/playlists/delete', { params: { playlistId } })
}

/** 批量删除歌单 */
export function deletePlaylistsBatch(ids) {
  return request.post('/playlists/delete-batch', { ids })
}

/** 导入外部歌单到我的歌单 */
export function importPlaylist(source, id) {
  return request.post('/playlists/import', { source, id })
}

/** 更新歌单信息 */
export function updatePlaylist(playlistId, name, description, coverUrl) {
  return request.post('/playlists/update', { playlistId, name, description, coverUrl })
}

/** 歌单排序 */
export function reorderPlaylists(order) {
  return request.post('/playlists/reorder', { order })
}

/** 导出歌单 */
export function exportPlaylist(playlistId) {
  return request.get('/playlists/export', { params: { playlistId } })
}

/** 导出播放历史 */
export function exportPlayHistory() {
  return request.get('/songs/history/export')
}
