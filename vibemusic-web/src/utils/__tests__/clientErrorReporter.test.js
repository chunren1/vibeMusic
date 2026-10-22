import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  installGlobalErrorReporting,
  resetErrorReporter,
} from '@/utils/clientErrorReporter'

/** 读取 sendBeacon 收到的 payload（Blob → JSON） */
async function readBeaconPayload(beacon) {
  const blob = beacon.mock.calls[0][1]
  return JSON.parse(await blob.text())
}

function dispatchScriptError(error) {
  window.dispatchEvent(new ErrorEvent('error', { message: error.message, error, filename: 'app.js' }))
}

describe('clientErrorReporter', () => {
  let beacon
  let fetchMock

  beforeEach(() => {
    resetErrorReporter()
    beacon = vi.fn(() => true)
    fetchMock = vi.fn(() => Promise.resolve({ ok: true }))
    vi.stubGlobal('navigator', { userAgent: 'vitest-ua/1.0', sendBeacon: beacon })
    vi.stubGlobal('fetch', fetchMock)
    installGlobalErrorReporting()
  })

  afterEach(() => {
    resetErrorReporter()
    vi.unstubAllGlobals()
    window.history.replaceState({}, '', '/')
  })

  it('window error 触发一次上报，且 payload 字段齐全', async () => {
    const error = new Error('boom')
    dispatchScriptError(error)

    expect(beacon).toHaveBeenCalledTimes(1)
    expect(beacon.mock.calls[0][0]).toBe('/api/client-errors')
    expect(fetchMock).not.toHaveBeenCalled()

    const payload = await readBeaconPayload(beacon)
    expect(Object.keys(payload).sort()).toEqual(
      ['kind', 'message', 'release', 'route', 'stack', 'ts', 'ua'].sort()
    )
    expect(payload.message).toBe('boom')
    expect(payload.kind).toBe('error')
    expect(payload.stack).toContain('boom')
    expect(payload.route).toBe('/')
    expect(payload.release).toBe('dev') // 测试环境无 VITE_APP_RELEASE 注入 → 兜底
    expect(payload.ua).toBe('vitest-ua/1.0')
    expect(Number.isNaN(Date.parse(payload.ts))).toBe(false)
  })

  it('同一错误重复触发只上报一次（message + stack 去重）', () => {
    const error = new Error('duplicated')
    dispatchScriptError(error)
    dispatchScriptError(error)
    dispatchScriptError(error)

    expect(beacon).toHaveBeenCalledTimes(1)
  })

  it('超过 10 条后不再上报（每页面会话限流）', () => {
    for (let i = 0; i < 14; i++) {
      const error = new Error(`flood-${i}`)
      window.dispatchEvent(new ErrorEvent('error', { message: error.message, error }))
    }

    expect(beacon).toHaveBeenCalledTimes(10)
  })

  it('route 只取 pathname，不含 query string', async () => {
    window.history.replaceState({}, '', '/m/search?q=secret&token=abc123')
    window.dispatchEvent(
      new ErrorEvent('error', { message: 'with query', error: new Error('with query') })
    )

    const payload = await readBeaconPayload(beacon)
    expect(payload.route).toBe('/m/search')
    expect(JSON.stringify(payload)).not.toContain('secret')
  })
})
