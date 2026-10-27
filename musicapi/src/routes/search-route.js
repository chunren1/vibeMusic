// ==================== 聚合搜索主入口 GET /search ====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js 原样搬出，**行为零变更**。
// 这条路由是网关最热的路径（限流三级 + 缓存 + 双源并行 + 打分去重 + 分页），
// 单独成文件便于与「单源搜索路由」（source-routes.js）区分维护。
const { writeLog } = require('../logger');
const sourceHealth = require('../source-health');
const { cacheHitTotal } = require('../metrics');
const {
  SCORE_CFG,
  calculateScore,
  generateFingerprint,
  isSameSong,
  paginate,
  refineResults,
} = require('../scoring');
const { searchNetease, searchQQ, searchCache } = require('../search');
const { logSafe } = require('../sources/shared');
const { searchLimiter } = require('../rate-limiters');
const {
  GENERIC_500, firstQuery, queryStr, buildSearchCacheKey, stripSearchInternals,
} = require('./helpers');

/**
 * GET /search?keyword=xxx&page=1&size=20&prefer=netease
 */
function registerSearchRoute(app) {
  app.get('/search', searchLimiter, async (req, res) => {
    try {
      const keywordRaw = queryStr(req.query.keyword);
      const page = firstQuery(req.query.page) ?? 1;
      const size = firstQuery(req.query.size) ?? 20;
      const preferRaw = queryStr(req.query.prefer);
      const maxRank = 30; // 各平台获取的最大条数

      if (keywordRaw === undefined || keywordRaw.trim() === '') {
        return res.status(400).json({ code: 400, message: 'keyword is required', data: null });
      }
      if (keywordRaw.length > 100) {
        return res.status(400).json({ code: 400, message: 'keyword too long (max 100 chars)', data: null });
      }
      // size 上限 100，防内存溢出
      const safeSize = Math.min(Math.max(1, parseInt(size) || 20), 100);
      // prefer 枚举白名单
      const validPrefers = ['netease', 'qq'];
      const safePrefer = validPrefers.includes(preferRaw) ? preferRaw : undefined;

      const kw = keywordRaw.trim();

      // 缓存键含 prefer + Cookie 派生维度：排序受偏好加成影响，不同偏好必须隔离；
      // 网易策略链头两条消费 per-request 用户 Cookie，同词不同用户也必须隔离（G1），
      // 匿名桶（anon）仍共享以保留命中率。
      const cacheKey = buildSearchCacheKey(kw, maxRank, safePrefer, req);
      const cached = searchCache.get(cacheKey);
      if (cached !== undefined) {
        cacheHitTotal.inc({ cache_type: 'search' });
        writeLog('access', 'INFO', `[Cache] HIT for "${logSafe(kw)}"`);
        stripSearchInternals(cached);
        const pageData = paginate(cached, page, safeSize);
        return res.json({ code: 200, message: 'success (cached)', data: pageData });
      }

      // ---- 并行请求上游 ----
      const [neteaseSongs, qqSongs] = await Promise.all([
        sourceHealth.guard('netease', () => searchNetease(kw, maxRank, req)),
        sourceHealth.guard('qq', () => searchQQ(kw, maxRank)),
      ]);

      // ---- 计算得分 ----
      const scored = [];

      neteaseSongs.forEach((song, idx) => {
        const s = { ...song, platform: 'netease', _raw: song._raw };
        s.score = calculateScore(s, kw, idx, neteaseSongs.length, safePrefer);
        scored.push(s);
      });

      qqSongs.forEach((song, idx) => {
        const s = { ...song, platform: 'qq', _raw: song._raw };
        s.score = calculateScore(s, kw, idx, qqSongs.length, safePrefer);
        scored.push(s);
      });

      // ---- 信息指纹去重 ----
      const deduped = [];
      const seen = new Map();

      // 按得分降序排列后再去重，确保得分高的优先保留
      scored.sort((a, b) => b.score - a.score);

      for (const song of scored) {
        const fp = generateFingerprint(song);

        if (seen.has(fp)) {
          const existing = deduped[seen.get(fp)];
          // 同名同歌手加成
          if (isSameSong(existing, song)) {
            existing.score += SCORE_CFG.sameSongBonus;
            existing.sourcePlatforms = [...(existing.sourcePlatforms || [existing.platform]), song.platform];
            // 标量质量提示：只记较长时长，绝不存对象引用（等时长自赋值曾致循环引用 → 500）
            existing.higherQualityDuration = Math.max(song.duration || 0, existing.duration || 0);
          }
          // 保留高分的
          if (song.score > existing.score) {
            deduped[seen.get(fp)] = { ...song, sourcePlatforms: [song.platform] };
          }
        } else {
          seen.set(fp, deduped.length);
          deduped.push({ ...song, sourcePlatforms: [song.platform] });
        }
      }

      // ---- 最终排序 ----
      deduped.sort((a, b) => b.score - a.score);

      // ---- 质量过滤 ----
      const refined = refineResults(deduped, kw);

      // ---- 清理内部字段后再缓存（空结果不入库：上游双失败的空数组不值得缓存，
      // 否则上游恢复后仍长期命中 stale 空结果） ----
      stripSearchInternals(refined);
      if (refined.length > 0) {
        searchCache.set(cacheKey, refined);
      }

      // ---- 分页 ----
      const pageData = paginate(refined, page, safeSize);

      res.json({ code: 200, message: 'success', data: pageData });
    } catch (error) {
      writeLog('api', 'ERROR', `[/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: null });
    }
  });
}

module.exports = registerSearchRoute;
