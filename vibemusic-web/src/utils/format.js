/** 格式化秒数为 mm:ss */
export function formatDuration(seconds) {
  if (!seconds && seconds !== 0) return '';
  const s = parseInt(seconds);
  const m = Math.floor(s / 60);
  const sec = s % 60;
  return m + ':' + String(sec).padStart(2, '0');
}
