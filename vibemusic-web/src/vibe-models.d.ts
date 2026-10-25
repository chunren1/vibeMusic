// 业务数据模型声明（仅类型，零运行时影响）。
// 由 tsconfig.check.json 的 "src/**/*.d.ts" include 引入，供 checkJs 下的 .js/.vue 全局使用。
// 字段按后端/接口实际返回形状标注为可选，避免因字段缺失而误判。

interface Song {
  id?: number | string
  sourceId: string
  name?: string
  songName?: string
  artist?: string
  album?: string
  duration?: number
  platform?: string
  coverUrl?: string
  coverColor?: string
  playedAt?: string | number
  createdAt?: string | number
  source?: string
}

interface Playlist {
  id?: number | string
  name?: string
  coverUrl?: string
  songCount?: number
  count?: number
  description?: string
  color?: string
  source?: string
  // creator 在接口里是对象（不是 id），各平台字段名不统一故并列
  creator?: { avatarUrl?: string; avatar?: string; nickname?: string; name?: string }
  playCount?: number
  _fallback?: boolean
}

// time 在不同来源分别是 "12.34"（字符串）与数字，故用联合类型
interface LyricLine {
  time?: string | number
  text?: string
}

interface ChatMessage {
  role?: string
  content?: string
  thinking?: string
  greeting?: string
  songs?: Song[]
}

// gender 同时存在 "male" 字符串与数字枚举两种用法，保持宽松由调用处归一
interface UserInfo {
  userId?: number | string
  nickname?: string
  username?: string
  avatar?: string
  bgImage?: string
  gender?: string
  birthday?: string
}
