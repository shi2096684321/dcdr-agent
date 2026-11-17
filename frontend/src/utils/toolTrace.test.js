import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTraceSegments } from './toolTrace.js'

/**
 * 历史消息的思考流还原规则 —— 后端只存"每段思考多少字 + 接着调了哪个工具"，
 * 前端按累加偏移切 reasoningContent。切片错位、工具配对错了，用户看到的过程就是假的。
 */

test('按 trace 切片还原 想→做→想，工具按 id 配对', () => {
  const segs = buildTraceSegments({
    reasoningContent: '想了一段又想了一段',
    trace: [
      { t: 'reason', n: 4 },
      { t: 'tool', id: 'call_a' },
      { t: 'reason', n: 5 }
    ],
    toolInvocations: [
      { id: 'call_a', name: 'workspace_read', arguments: '{"path":"a.txt"}' }
    ]
  })

  assert.deepEqual(segs.map(s => s.kind), ['reason', 'tool', 'reason'])
  assert.equal(segs[0].text, '想了一段')
  assert.equal(segs[2].text, '又想了一段')
  assert.equal(segs[1].name, 'workspace_read')
  assert.equal(segs[1].id, 'call_a')
})

test('老消息没有 trace：退回整段思考 + 全部工具', () => {
  const segs = buildTraceSegments({
    reasoningContent: '整段思考',
    toolInvocations: [{ id: 'x', name: 'calculator', arguments: '{}' }]
  })

  assert.deepEqual(segs.map(s => s.kind), ['reason', 'tool'])
  assert.equal(segs[0].text, '整段思考')
})

test('工具 id 对不上时不张冠李戴，未引用的调用补在末尾', () => {
  const segs = buildTraceSegments({
    reasoningContent: '想了',
    trace: [{ t: 'reason', n: 2 }, { t: 'tool', id: 'missing' }],
    toolInvocations: [{ id: 'real', name: 'web_search', arguments: '{}' }]
  })

  assert.deepEqual(segs.map(s => s.kind), ['reason', 'tool'])
  assert.equal(segs[1].id, 'real', '配对失败也不能把别的调用顶到这一步上')
})

test('切片没覆盖完的思考尾段补回来，不丢文字', () => {
  const segs = buildTraceSegments({
    reasoningContent: '第一段文字后面还有',
    trace: [{ t: 'reason', n: 3 }]
  })

  assert.equal(segs.length, 2)
  assert.equal(segs[0].text, '第一段')
  assert.equal(segs[1].text, '文字后面还有')
})
