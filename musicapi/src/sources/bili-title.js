// B站投稿标题解析（视频标题 → {歌名, 歌手}）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 单独成文件的原因：这是一套自成体系的正则/清洗规则（500 行里近半是注释与常量），
// 与取流协议无耦合，放一起会让 bili.js 超过单文件 250 纯 LOC 硬约束。
// 全部是纯函数：单测直接喂字符串，不触网、不读环境变量。

function stripBiliEm(s) {
  return String(s != null ? s : '').replace(/<em[^>]*>/gi, '').replace(/<\/em>/gi, '');
}

// ==================== B站视频标题解析 ====================
// 背景：B站投稿标题是“视频标题”(Uploader 自由填写)，直接透传会导致
//   歌名=整句标题、歌手=UP主(如 name='【Hi-Res】｜《晴天》- 周杰伦…'、artists='VV音乐局')。
// parseBiliTitle 把常见投稿格式解析为标准 {song, artist}：
//   - 《歌名》优先：'梦然-《少年》官方版'→少年/梦然；'周杰伦——《晴天》'→晴天/周杰伦；
//     '歌手《歌名》'→按 pre 取歌手；多个《》并存(如广告混剪)判歧义→回退原样(由播放量门控沉底)。
//   - 无《》时按分隔符拆两段：'歌名 - 歌手翻唱'(Y 尾为翻唱/cover/演唱)→歌名/歌手；
//     '歌名完整版 - 歌手'(X 尾为纯版本词)→歌名/歌手；任一段等于 UP 主名→另一段为歌名、
//     UP 主为歌手；否则按最常见的'歌手 - 歌名'假设(Y 尾版本词照剥)。
//     切分只认两侧带空格的半角连字符(保 'Hi-Res' 不裂)与全角/长破折号。
//   - 【…】/[…]/(…)/〈…〉包标签：仅当括号内命中 tag 词(翻唱/cover/live/官方/MV/OST/4K/无损…)
//     才剥除(保守：'【晴天】周杰伦'这类不剥，避免误删歌名)；数学粗体等杂体经 NFKC 归一。
//   - 歌手候选校验：长度 1–12、拒指示词/视频词(这/那/才是/原版/合集/「」引号…)，
//     不合格→回退 UP 主(如'这才是《晴天》原版MV！'→晴天/UP主)。
//   - 绝不返回空 name/artists：解析失败一律回退现行行为(name=去 em 标签原标题、artists=UP主)。
function parseBiliTitle(rawTitle, author) {
  const fallbackName = stripBiliEm(rawTitle).trim();
  const fallbackArtist = author != null ? String(author) : '';
  if (!fallbackName) return { name: '', artists: fallbackArtist };
  // OST 元数据书名号先剥：'她说 (电视剧《北上》主题曲)'中《北上》是剧名非歌名，
  // 仅剥紧邻主题曲/片尾曲/插曲/OST/OP/ED 的《》(真歌名《》不受影响)
  const t = stripBiliOstBooks(fallbackName.normalize('NFKC'));

  // 切分：ascii 连字符要求至少一侧空格(保 'Hi-Res' 类词内连字符不断裂)，
  // 全角/长破折号恒为分隔符。'》- 周'这类半角形态同样切开。
  const parts = t.split(/\s+[–—－—-]\s*|\s*[–—－—-]\s+|[–—－—]+/).map((p) => p.trim()).filter(Boolean);

  const withBook = parts.map((p) => ({ part: p, books: matchBookTitles(p) }));
  const hitParts = withBook.filter((x) => x.books.length > 0);
  if (hitParts.length === 1 && hitParts[0].books.length === 1) {
    const song = cleanBiliToken(hitParts[0].books[0]);
    if (song && song.length <= 40) {
      const idx = parts.indexOf(hitParts[0].part);
      const artist = pickBiliArtist(parts, idx, song, fallbackArtist);
      if (artist) return { name: song, artists: artist };
    }
    return { name: fallbackName, artists: fallbackArtist };
  }
  if (hitParts.length > 1 || hitParts.some((x) => x.books.length > 1)) {
    return { name: fallbackName, artists: fallbackArtist }; // 多《》并存判歧义，回退
  }

  // 无《》：两段式 dash 规则(先角色词，再版本词，最后默认歌手前置)
  if (parts.length === 2) {
    const [x, y] = parts.map((p) => stripBiliTags(p).trim());
    // '歌名 - 歌手翻唱'：Y 尾为演唱者角色词 → X=歌，剥词后 Y=歌手
    const ySinger = stripBiliSuffix(y, BILI_SINGER_ROLE_RE);
    if (ySinger.stripped && ySinger.token) {
      const song = cleanBiliToken(x);
      if (song && song.length <= 40) return { name: song, artists: ySinger.token };
    }
    if (x === fallbackArtist && y) return { name: cleanBiliToken(y) || fallbackName, artists: fallbackArtist };
    if (y === fallbackArtist && x) return { name: cleanBiliToken(x) || fallbackName, artists: fallbackArtist };
    // '歌名完整版 - 歌手'：X 尾为纯版本词(非演唱角色)→X=歌，Y=歌手
    const xVer = stripBiliSuffix(x, BILI_VERSION_RE);
    if (xVer.stripped && xVer.token && !stripBiliSuffix(y, BILI_ROLE_SUFFIX_RE).stripped) {
      const artist = cleanBiliToken(y);
      if (xVer.token.length <= 40 && artist && isBiliArtistSane(artist)) {
        return { name: xVer.token, artists: artist };
      }
    }
    // 默认：最常见的'歌手 - 歌名'假设(Y 尾版本词照剥，如'…晴天MV 2160P修复版'→'晴天')
    const artist = cleanBiliToken(stripBiliSuffix(x, BILI_ROLE_SUFFIX_RE).token);
    const song = cleanBiliToken(stripBiliSuffix(y, BILI_ROLE_SUFFIX_RE).token);
    if (artist && song && isBiliArtistSane(artist) && song.length <= 40) {
      return { name: song, artists: artist };
    }
    return { name: fallbackName, artists: fallbackArtist };
  }

  // 单段：剥包标签 + 尾部版本词(如'少年完整版'→'少年')，歌手回退 UP 主
  if (parts.length === 1) {
    const song = cleanBiliToken(stripBiliSuffix(stripBiliTags(parts[0]).trim(), BILI_ROLE_SUFFIX_RE).token);
    if (song && song.length <= 40) return { name: song, artists: fallbackArtist };
  }
  return { name: fallbackName, artists: fallbackArtist };
}

/** 提取《…》书名号片段(内部 1–30 字，Lyrics 引用尾巴超长的不算)。 */
function matchBookTitles(s) {
  const out = [];
  const re = /《([^》]{1,30})》/g;
  let m;
  while ((m = re.exec(s)) !== null) out.push(m[1].trim());
  return out.filter(Boolean);
}

/** 剥紧邻 OST 类词的《》(剧名/番名)，真歌名《》保留。 */
function stripBiliOstBooks(s) {
  return String(s)
    .replace(/《[^》]{1,30}》(?=[\s:：]{0,4}(主题曲|片尾曲|插曲|OST|OP|ED|预告|先导))/g, '')
    .replace(/(主题曲|片尾曲|插曲|OST|OP|ED)[\s:：]{0,4}《[^》]{1,30}》/g, '$1');
}

// 包标签 tag 词：命中才剥括号(保守，避免'【晴天】'这类歌名括号被误删)
const BILI_TAG_WORDS = ['翻唱', 'cover', 'live', '现场', '官方', '正式版', '完整版', '完整',
  'mv', 'm/v', 'ost', '主题曲', '片尾曲', '插曲', 'op', 'ed', '4k', '修复', '无损', 'hi-res',
  'hires', '杜比', '高清', 'hd', '歌词', '字幕', 'remix', 'dj', '伴奏', '纯享', '直拍',
  '饭拍', '安利', '循环', '试听', '先行', '预告', '单曲', 'ep', 'pv'];
const BILI_BRACKET_RE = /【([^【】]{1,30})】|\[([^\[\]]{1,30})\]|\(([^()]{1,30})\)|〈([^〈〉]{1,30})〉/g;

/** 剥除命中 tag 词的包标签括号；不命中则原样保留。 */
function stripBiliTags(s) {
  let prev;
  let out = String(s);
  do {
    prev = out;
    out = out.replace(BILI_BRACKET_RE, (full, a, b, c, d) => {
      const inner = (a ?? b ?? c ?? d ?? '').toLowerCase();
      if (!inner.trim()) return '';
      if (BILI_TAG_WORDS.some((w) => inner.includes(w))) return '';
      return full;
    });
  } while (out !== prev);
  return out;
}

// 尾部角色/版本词：'周杰伦翻唱'→'周杰伦'、'晴天MV 2160P修复版'→'晴天'
const BILI_ROLE_SUFFIX_RE = /(翻唱|cover|演唱|live|现场版?|官方版?|正式版|完整版|mv|m\/v|修复版|无损版?|高清版?|歌词版?|字幕版|主题曲|片尾曲|插曲|ost|试听版|先行曲|预告版|\d{3,4}p|4k|hd)$/i;
// 演唱者角色词('歌名 - 歌手翻唱'判定用)：仅翻唱/cover/演唱。
// live/现场刻意排除：'歌手 - 歌名live'中 live 绝大多数是版本后缀(歌手前置更常见)，
// 若 live 也判演唱者会导致其被反转为歌名；裸'歌名 - 歌手live'(无括号)为已知残留歧义，
// 按歌手前置假设处理(括号形态【Live】仍经包标签正常剥除)。
const BILI_SINGER_ROLE_RE = /(翻唱|cover|演唱)$/i;
// 纯版本词('歌名完整版 - 歌手'判定用)：不含演唱角色词
const BILI_VERSION_RE = /(官方版?|正式版|完整版|mv|m\/v|修复版|无损版?|高清版?|歌词版?|字幕版|主题曲|片尾曲|插曲|ost|试听版|先行曲|预告版|\d{3,4}p|4k|hd)$/i;

/** 迭代剥指定尾部词；返回 {token, stripped}。 */
function stripBiliSuffix(s, re) {
  let token = String(s).trim();
  let stripped = false;
  let prev;
  do {
    prev = token;
    token = token.replace(re, '').trim();
    token = token.replace(/[-–—·•\s]+$/, '').trim();
    if (token !== prev) stripped = true;
  } while (token !== prev && token);
  return { token, stripped };
}

/** 兼容旧名：默认按全量角色/版本词表剥离。 */
function stripBiliRoleSuffix(s) {
  return stripBiliSuffix(s, BILI_ROLE_SUFFIX_RE);
}

// 歌手候选校验：拒指示词/视频残留词与引号标点
const BILI_ARTIST_STOP_RE = /[这那哪最才是否了在有和与及？！?!「」『』"'"‘’“”…·・—…]|原版|才是|视频|合集|盘点|连播|串烧|混剪|广告|循环|单曲|电影|电视剧|连续剧|动漫|游戏/;
function isBiliArtistSane(a) {
  if (!a || a.length > 12) return false;
  return !BILI_ARTIST_STOP_RE.test(a);
}

/** emoji/杂符清理(保守：只清 emoji 与控制符，保留 CJK/标点供上层校验)。 */
function cleanBiliToken(s) {
  return String(s != null ? s : '')
    .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE00}-\u{FEFF}\u{2000}-\u{206F}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 在 dash 切分 parts 中为含《》的 part 找邻位歌手：先左后右，不合格回退 UP 主。 */
function pickBiliArtist(parts, idx, song, fallbackArtist) {
  const order = [];
  if (idx > 0) order.push(parts[idx - 1]);
  if (idx < parts.length - 1) order.push(parts[idx + 1]);
  // 单 part 内'歌手《歌名》尾巴'形态：pre/post 同为候选(pre 优先)
  if (parts.length === 1) {
    const m = /^([\s\S]*?)《[^》]{1,30}》([\s\S]*)$/.exec(parts[0]);
    if (m) order.push(m[1], m[2]);
  }
  for (const cand of order) {
    let c = stripBiliTags(cand);
    c = c.split(/[｜|]/).filter((seg) => stripBiliTags(seg).trim()).join(' ');
    c = cleanBiliToken(stripBiliSuffix(c.trim(), BILI_ROLE_SUFFIX_RE).token);
    c = c.replace(/^[-–—·•\s'\"‘’“”]+|[-–—·•\s'\"‘’“”]+$/g, '').trim();
    if (c && c !== song && isBiliArtistSane(c)) return c;
  }
  return fallbackArtist || '';
}

module.exports = {
  parseBiliTitle,
  stripBiliTags,
  stripBiliSuffix,
  stripBiliRoleSuffix,
  cleanBiliToken,
  isBiliArtistSane,
  BILI_TAG_WORDS,
};
