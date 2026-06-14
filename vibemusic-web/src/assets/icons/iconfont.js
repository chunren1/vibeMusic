/**
 * vibeMusic iconfont — 内联 SVG Symbols
 * 所有图标统一使用 24px viewBox, stroke/currentColor 自适应主题
 */

;(function () {
  var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  svg.setAttribute('style', 'position:absolute;width:0;height:0;overflow:hidden')
  svg.innerHTML = [
    // ====== 播放控制 ======
    '<symbol id="icon-play" viewBox="0 0 24 24"><polygon points="8,5 19,12 8,19" fill="currentColor"/></symbol>',
    '<symbol id="icon-pause" viewBox="0 0 24 24"><rect x="6" y="4" width="4" height="16" fill="currentColor"/><rect x="14" y="4" width="4" height="16" fill="currentColor"/></symbol>',
    '<symbol id="icon-previous" viewBox="0 0 24 24"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z" fill="currentColor"/></symbol>',
    '<symbol id="icon-next" viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z" fill="currentColor"/></symbol>',

    // ====== 收藏 ======
    '<symbol id="icon-heart" viewBox="0 0 24 24"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" fill="currentColor" stroke="currentColor" stroke-width="2"/></symbol>',
    '<symbol id="icon-heart-fill" viewBox="0 0 24 24"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" fill="currentColor"/></symbol>',

    // ====== 下载 ======
    '<symbol id="icon-download" viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><polyline points="7 10 12 15 17 10" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><line x1="12" y1="15" x2="12" y2="3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></symbol>',

    // ====== 音量 ======
    '<symbol id="icon-volume" viewBox="0 0 24 24"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor" stroke="currentColor" stroke-width="2"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07" fill="none" stroke="currentColor" stroke-width="2"/></symbol>',
    '<symbol id="icon-volume-mute" viewBox="0 0 24 24"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor" stroke="currentColor" stroke-width="2"/><line x1="23" y1="9" x2="17" y2="15" stroke="currentColor" stroke-width="2"/><line x1="17" y1="9" x2="23" y2="15" stroke="currentColor" stroke-width="2"/></symbol>',

    // ====== 播放列表 ======
    '<symbol id="icon-playlist" viewBox="0 0 24 24"><line x1="8" y1="6" x2="21" y2="6" stroke="currentColor" stroke-width="2"/><line x1="8" y1="12" x2="21" y2="12" stroke="currentColor" stroke-width="2"/><line x1="8" y1="18" x2="21" y2="18" stroke="currentColor" stroke-width="2"/><circle cx="4" cy="6" r="1" fill="currentColor"/><circle cx="4" cy="12" r="1" fill="currentColor"/><circle cx="4" cy="18" r="1" fill="currentColor"/></symbol>',

    // ====== 音质 ======
    '<symbol id="icon-quality" viewBox="0 0 24 24"><path d="M12 3v18M3 12h18M8 8l8 8M16 8l-8 8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></symbol>',

    // ====== 播放模式 ======
    '<symbol id="icon-list-loop" viewBox="0 0 1280 1024"><path d="M1121.8 243.7A373.4 373.4 0 0 1 1231.9 509.5c0 34.2-4.6 68.2-13.7 100.8a42.4 42.4 0 0 1-81.7-22.6 291.9 291.9 0 0 0 10.6-78.2c0-160.5-130.6-291.1-291.1-291.1H461.5v75.1c0 24.1-16.8 33.5-37.3 20.8L243.5 202.2c-20.5-12.7-20.7-33.8-.4-46.9L424.7 38.1c20.2-13.1 36.8-4 36.8 20.1v75.4h394.5c100.4 0 194.8 39.1 265.8 110.1zm-70 573.1c20.5 12.7 20.7 33.8.4 46.8l-181.6 117.3c-20.2 13.1-36.8 4.1-36.8-20V885.4H407.9c-100.4 0-194.8-39.1-265.8-110.1A373.4 373.4 0 0 1 32 509.5c0-72.6 20.7-143.1 60-203.9a42.4 42.4 0 1 1 71.2 46 290 290 0 0 0-46.4 157.8c0 160.6 130.6 291.2 291.1 291.2h425.9v-75.1c0-24.1 16.8-33.5 37.2-20.7l180.8 111.9z" fill="currentColor"/></symbol>',
    '<symbol id="icon-single" viewBox="0 0 1024 1024"><path d="M928 476.8c-19.2 0-32 12.8-32 32v86.4c0 108.8-86.4 198.4-198.4 198.4H201.6l41.6-38.4c6.4-6.4 12.8-16 12.8-25.6 0-19.2-16-35.2-35.2-35.2-9.6 0-22.4 3.2-28.8 9.6l-108.8 99.2c-16 12.8-12.8 35.2 0 48l108.8 96c6.4 6.4 19.2 12.8 28.8 12.8 19.2 0 35.2-12.8 38.4-32 0-12.8-6.4-22.4-16-28.8l-48-44.8h499.2c147.2 0 265.6-118.4 265.6-259.2v-86.4c0-19.2-12.8-32-32-32zM96 556.8c19.2 0 32-12.8 32-32v-89.6c0-112 89.6-201.6 198.4-204.8h496l-41.6 38.4c-6.4 6.4-12.8 16-12.8 25.6 0 19.2 16 35.2 35.2 35.2 9.6 0 22.4-3.2 28.8-9.6l105.6-99.2c16-12.8 12.8-35.2 0-48l-108.8-96c-6.4-6.4-19.2-12.8-28.8-12.8-19.2 0-35.2 12.8-38.4 32 0 12.8 6.4 22.4 16 28.8l48 44.8H329.6C182.4 169.6 64 288 64 438.4v86.4c0 19.2 12.8 32 32 32z" fill="currentColor"/><path d="M544 672V352h-48L416 409.6l16 41.6 60.8-41.6V672z" fill="currentColor"/></symbol>',
    '<symbol id="icon-shuffle" viewBox="0 0 24 24"><polyline points="16 3 21 3 21 8" fill="none" stroke="currentColor" stroke-width="2"/><line x1="4" y1="20" x2="21" y2="3" fill="none" stroke="currentColor" stroke-width="2"/><polyline points="21 16 21 21 16 21" fill="none" stroke="currentColor" stroke-width="2"/><line x1="15" y1="15" x2="21" y2="21" fill="none" stroke="currentColor" stroke-width="2"/><line x1="4" y1="4" x2="9" y2="9" fill="none" stroke="currentColor" stroke-width="2"/></symbol>',

    // ====== 搜索 ======
    '<symbol id="icon-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" stroke-width="2"/><line x1="21" y1="21" x2="16.65" y2="16.65" stroke="currentColor" stroke-width="2"/></symbol>',

    // ====== 关闭 ======
    '<symbol id="icon-close" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18" stroke="currentColor" stroke-width="2"/><line x1="6" y1="6" x2="18" y2="18" stroke="currentColor" stroke-width="2"/></symbol>',
  ].join('')
  document.body.appendChild(svg)
})()
