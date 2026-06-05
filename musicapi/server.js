const express = require('express');
const cors = require('cors');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');

const app = express();
const PORT = 3000;

// 中间件
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// 设置QQ音乐Cookie
qqMusic.setCookie({
  uin: '1273616219',
  qqmusic_key: 'Q_H_L_63k3Nv3zEKCIfeQJCRrKXZICPg6sUdha-XoSAlx3RBwV9Qip1npSFycpuORVlNaponLrISopmYk6WEWvUoS_4hTXH',
  psrf_qqaccess_token: '29F5F6D1231AE0ABB89B32F52121ED48',
  psrf_qqopenid: '54FBBE13A9B091D29A47EC3ACAC67DB9',
  psrf_qqrefresh_token: 'F1C734756A7F1C008EEF600940491260',
  psrf_qqunionid: 'F9F2E9EF38FDDD07070D02463664A8A8',
  ptcz: 'e638df01d5718b98fc7fe51b942be0e06508357a00b3fb5feb01830c253ef2dd',
  qm_keyst: 'Q_H_L_63k3Nv3zEKCIfeQJCRrKXZICPg6sUdha-XoSAlx3RBwV9Qip1npSFycpuORVlNaponLrISopmYk6WEWvUoS_4hTXH',
  tmeLoginType: '2'
});

// ==================== 兼容原网易云API路由 ====================

/**
 * 获取歌词
 * GET /lyric?id=xxx
 */
app.get('/lyric', async (req, res) => {
  try {
    const { id } = req.query;
    if (!id) return res.status(400).json({ code: 400, message: 'id is required' });
    const result = await NeteaseCloudMusicApi.lyric({ id });
    res.json(result.body);
  } catch (error) {
    console.error('Lyric Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

/**
 * 搜索歌曲（cloudsearch 返回完整字段）
 * GET /cloudsearch?keywords=xxx&limit=20
 */
app.get('/cloudsearch', async (req, res) => {
  try {
    const { keywords, limit = 20, type = 1 } = req.query;
    const result = await NeteaseCloudMusicApi.cloudsearch({ keywords, limit, type });
    res.json(result.body);
  } catch (error) {
    console.error('Cloudsearch Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

/**
 * 获取歌曲播放URL（VIP品质）
 * GET /song/url/v1?id=xxx&level=exhigh
 */
app.get('/song/url/v1', async (req, res) => {
  try {
    const { id, level = 'exhigh' } = req.query;
    const result = await NeteaseCloudMusicApi.song_url_v1({ id, level });
    res.json(result.body);
  } catch (error) {
    console.error('Song URL Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

/**
 * 获取歌曲详情
 * GET /song/detail?ids=xxx
 */
app.get('/song/detail', async (req, res) => {
  try {
    const { ids } = req.query;
    const result = await NeteaseCloudMusicApi.song_detail({ ids });
    res.json(result.body);
  } catch (error) {
    console.error('Song Detail Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

/**
 * 获取推荐歌单（用于Banner轮播图）
 * GET /personalized?limit=10
 */
app.get('/personalized', async (req, res) => {
  try {
    const { limit = 10 } = req.query;
    const result = await NeteaseCloudMusicApi.personalized({ limit });
    res.json(result.body);
  } catch (error) {
    console.error('Personalized Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

// ==================== 新增多平台聚合搜索 ====================

// 平台权重配置
const PLATFORM_WEIGHTS = {
  netease: 1.0,
  qq: 0.9
};

/**
 * 多平台聚合搜索接口（含排序）
 * GET /search?keyword=xxx&page=1&size=20
 */
app.get('/search', async (req, res) => {
  try {
    const { keyword, page = 1, size = 20 } = req.query;
    const maxRank = 20;
    const sameSongBonus = 0.3;
    
    if (!keyword) {
      return res.status(400).json({ code: 400, message: 'keyword is required', data: null });
    }
    
    // 并发调用各平台
    const [neteaseResult, qqResult] = await Promise.all([
      searchNetease(keyword, maxRank),
      searchQQ(keyword, maxRank)
    ]);
    
    // 合并结果并计算得分
    const mergedMap = new Map();
    
    // 处理网易云结果
    neteaseResult.forEach((song, index) => {
      const rawRank = index + 1;
      const baseScore = (maxRank - rawRank + 1) / maxRank;
      const finalScore = baseScore * PLATFORM_WEIGHTS.netease;
      
      song.rawRank = rawRank;
      song.baseScore = baseScore;
      song.finalScore = finalScore;
      song.platform = 'netease';
      
      const key = `${song.name}|${song.artists}`;
      mergedMap.set(key, song);
    });
    
    // 处理QQ音乐结果
    qqResult.forEach((song, index) => {
      const rawRank = index + 1;
      const baseScore = (maxRank - rawRank + 1) / maxRank;
      const finalScore = baseScore * PLATFORM_WEIGHTS.qq;
      
      song.rawRank = rawRank;
      song.baseScore = baseScore;
      song.finalScore = finalScore;
      song.platform = 'qq';
      
      const key = `${song.name}|${song.artists}`;
      
      if (mergedMap.has(key)) {
        const existing = mergedMap.get(key);
        if (finalScore > existing.finalScore) {
          song.sameSongBonus = sameSongBonus;
          song.finalScore += sameSongBonus;
          song.sourcePlatforms = [existing.platform, 'qq'];
          mergedMap.set(key, song);
        } else {
          existing.sameSongBonus = sameSongBonus;
          existing.finalScore += sameSongBonus;
          existing.sourcePlatforms = [existing.platform, 'qq'];
        }
      } else {
        mergedMap.set(key, song);
      }
    });
    
    // 按finalScore降序排序
    const sortedResults = Array.from(mergedMap.values()).sort((a, b) => b.finalScore - a.finalScore);
    
    // 分页
    const total = sortedResults.length;
    const start = (page - 1) * size;
    const end = start + parseInt(size);
    const pageData = sortedResults.slice(start, end);
    
    res.json({
      code: 200,
      message: 'success',
      data: {
        total,
        page: parseInt(page),
        size: parseInt(size),
        list: pageData
      }
    });
  } catch (error) {
    console.error('Aggregate Search Error:', error.message);
    res.status(500).json({ code: 500, message: error.message, data: null });
  }
});

// ==================== 网易云音乐API路由（通用） ====================
app.all('/netease/*', async (req, res) => {
  try {
    const apiPath = req.path.replace('/netease/', '');
    const apiName = apiPath.replace(/\//g, '_');
    const params = { ...req.query, ...req.body };
    
    if (typeof NeteaseCloudMusicApi[apiName] === 'function') {
      const result = await NeteaseCloudMusicApi[apiName](params);
      res.json(result.body);
    } else {
      res.status(404).json({ code: 404, message: `API ${apiName} not found` });
    }
  } catch (error) {
    console.error('NetEase API Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

// ==================== QQ音乐API路由 ====================
app.all('/qq/*', async (req, res) => {
  try {
    const path = req.path.replace('/qq/', '');
    const query = { ...req.query, ...req.body };
    const result = await qqMusic.api(path, query);
    res.json({ code: 200, message: 'success', data: result });
  } catch (error) {
    console.error('QQ Music API Error:', error.message);
    res.status(500).json({ code: 500, message: error.message, data: null });
  }
});

/**
 * QQ音乐播放URL
 * GET /song/url/qq?id=xxx
 */
app.get('/song/url/qq', async (req, res) => {
  try {
    const { id } = req.query;
    console.log('QQ URL request for:', id);
    const result = await qqMusic.api('/song/urls', { id });
    console.log('QQ URL raw:', JSON.stringify(result).substring(0,200));
    const url = result && result[id] ? result[id] : null;
    res.json({ code: 200, data: [{ id: id, url: url }] });
  } catch (error) {
    console.error('QQ Song URL Error:', error.message);
    res.status(500).json({ code: 500, message: error.message });
  }
});

// ==================== 辅助函数 ====================

async function searchNetease(keyword, limit) {
  try {
    const result = await NeteaseCloudMusicApi.cloudsearch({ keywords: keyword, limit, type: 1 });
    if (result.body.code === 200 && result.body.result && result.body.result.songs) {
      return result.body.result.songs.map(s => ({
        id: s.id,
        name: s.name,
        artists: (s.ar || s.artists || []).map(a => a.name).join(' / ') || '未知歌手',
        album: s.al ? s.al.name : (s.album ? s.album.name : ''),
        cover: (s.al && s.al.picUrl) ? s.al.picUrl : (s.album && s.album.picUrl ? s.album.picUrl : ''),
        duration: s.dt || 0
      }));
    }
    return [];
  } catch (error) {
    console.error('NetEase search error:', error.message);
    return [];
  }
}

async function searchQQ(keyword, limit) {
  try {
    const result = await qqMusic.api('search', { key: keyword, limit });
    if (result && result.list && Array.isArray(result.list)) {
      return result.list.map(s => {
        // Build cover URL from albummid (more reliable than albumcover)
        let cover = '';
        if (s.albumcover) {
          cover = s.albumcover;
        } else if (s.albummid) {
          cover = `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.albummid}.jpg`;
        }
        return {
          id: s.songmid,
          name: s.songname,
          artists: s.singer ? s.singer.map(a => a.name).join(' / ') : '',
          album: s.albumname || '',
          cover: cover,
          duration: s.interval ? s.interval * 1000 : 0
        };
      });
    }
    return [];
  } catch (error) {
    console.error('QQ search error:', error.message);
    return [];
  }
}

// ==================== 健康检查 ====================
app.get('/health', (req, res) => {
  res.json({ code: 200, message: 'Music API Service is running', data: {
    netease: 'available',
    qq: 'available',
    timestamp: new Date().toISOString()
  }});
});

// ==================== 启动服务 ====================
app.listen(PORT, () => {
  console.log(`Music API Service running on http://localhost:${PORT}`);
  console.log(`兼容网易云API: http://localhost:${PORT}/cloudsearch, /song/url/v1, /song/detail, /personalized`);
  console.log(`多平台聚合搜索: http://localhost:${PORT}/search`);
  console.log(`网易云音乐: http://localhost:${PORT}/netease`);
  console.log(`QQ音乐: http://localhost:${PORT}/qq`);
});