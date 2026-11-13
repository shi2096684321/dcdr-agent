import test from 'node:test';
import assert from 'node:assert/strict';

import { sampleTrack, IDENTITY, clamp } from '../web/src/core/easing.js';
import { MotionBank, normalizeMotion } from '../web/src/core/motion.js';
import { Compositor } from '../web/src/core/compositor.js';
import { StateMachine, STATES, AGENT_ACTION_MAP } from '../web/src/core/state-machine.js';
import { Physics } from '../web/src/core/physics.js';

test('sampleTrack: 缺省属性取恒等值', () => {
  const out = sampleTrack([{ t: 0, dy: 0 }, { t: 1, dy: 4 }], 0.5);
  assert.ok(Math.abs(out.dy - 2) < 1e-9);  // easeInOutSine(0.5) ≈ 0.5
  assert.equal(out.dx, IDENTITY.dx);
  assert.equal(out.sx, IDENTITY.sx);
  assert.equal(out.alpha, IDENTITY.alpha);
});

test('sampleTrack: 属性首次出现之前保持恒等，之后保持最后值', () => {
  const frames = [{ t: 0, alpha: 0 }, { t: 0.4, alpha: 1 }, { t: 0.8, alpha: 0 }];
  assert.ok(Math.abs(sampleTrack(frames, 0.2).alpha - 0.5) < 1e-9);
  assert.equal(sampleTrack(frames, 0.9).alpha, 0);
  const late = [{ t: 0.5, dy: 6 }, { t: 1, dy: 6 }];
  assert.equal(sampleTrack(late, 0.1).dy, 0);
  assert.equal(sampleTrack(late, 0.75).dy, 6);
});

test('sampleTrack: 支持 linear 缓动并钳制相位', () => {
  const frames = [{ t: 0, dx: 0 }, { t: 1, dx: 10 }];
  assert.equal(sampleTrack(frames, 0.25, 'linear').dx, 2.5);
  assert.equal(sampleTrack(frames, 2, 'linear').dx, 10);
  assert.equal(sampleTrack(frames, -1, 'linear').dx, 0);
});

test('normalizeMotion: 补齐文档默认值', () => {
  const m = normalizeMotion({ id: 'x', tracks: {} });
  assert.equal(m.layer, 'event');
  assert.equal(m.duration, 600);
  assert.equal(m.fade, 120);
  assert.equal(m.priority, 10);
});

test('compositor: alpha = 基础值 + 增量后钳制（v0.4 修正，非连乘）', () => {
  const bank = new MotionBank();
  bank.add({
    id: 'happy', layer: 'event', duration: 200, fade: 0,
    tracks: {
      body: [{ t: 0, dy: 0 }, { t: 1, dy: -10 }],
      heart: [{ t: 0, alpha: 0 }, { t: 0.5, alpha: 1 }, { t: 1, alpha: 0 }]
    }
  });
  const skin = { parts: [{ id: 'body', alpha: 1 }, { id: 'heart', alpha: 0 }] };
  const c = new Compositor(bank, skin);
  c.play('happy');
  const t = c.tick(100, {});                 // phase 0.5
  assert.ok(Math.abs(t.get('body').dy + 5) < 1e-9);
  assert.equal(t.get('heart').alpha, 1);     // 0 + 1 => 可见
  assert.equal(t.get('body').alpha, 1);
});

test('compositor: 物理层增量叠加 + alpha 上界钳制', () => {
  const bank = new MotionBank();
  bank.add({ id: 'happy', layer: 'event', duration: 200, fade: 0, tracks: {
    heart: [{ t: 0, alpha: 0 }, { t: 0.5, alpha: 1 }, { t: 1, alpha: 0 }]
  } });
  const c = new Compositor(bank, { parts: [{ id: 'heart', alpha: 0 }] });
  c.play('happy');
  const t = c.tick(100, { parts: { heart: { alpha: 0.5, dy: -3 } } });
  assert.equal(t.get('heart').alpha, 1);     // 1.5 被钳制
  assert.equal(t.get('heart').dy, -3);
});

test('compositor: dx/dy 相加，sx/sy 相乘', () => {
  const bank = new MotionBank();
  bank.add({ id: 'a', layer: 'micro', duration: 100, fade: 0, tracks: { body: [{ t: 0, dx: 2, sx: 2 }] } });
  bank.add({ id: 'b', layer: 'micro', duration: 100, fade: 0, tracks: { body: [{ t: 0, dx: 3, sx: 3 }] } });
  const c = new Compositor(bank, { parts: [{ id: 'body', alpha: 1 }] });
  c.play('a'); c.play('b');
  const t = c.tick(0, {});
  assert.equal(t.get('body').dx, 5);
  assert.equal(t.get('body').sx, 6);
});

test('compositor: 未登记的 partId 被安全忽略', () => {
  const bank = new MotionBank();
  bank.add({ id: 'a', layer: 'event', duration: 100, fade: 0, tracks: { ghost: [{ t: 0, dy: 5 }] } });
  const c = new Compositor(bank, { parts: [{ id: 'body', alpha: 1 }] });
  c.play('a');
  const t = c.tick(0, {});
  assert.equal(t.get('body').dy, 0);
  assert.equal(t.has('ghost'), false);
});

test('state-machine: 优先级与打断规则严格按 §7.2', () => {
  const sm = new StateMachine();
  assert.equal(sm.state, 'idle');
  assert.ok(sm.dispatch('keydown'));       // idle → typing
  assert.ok(sm.dispatch('click'));         // 20 >= 10 → poke
  assert.equal(sm.state, 'poke');
  assert.ok(!sm.dispatch('keydown'));      // 不能降到更低优先级
  assert.ok(sm.dispatch('pointerdown'));   // → drag
  assert.ok(!sm.dispatch('click'));        // drag 不可打断
  assert.ok(sm.dispatch('pointerup'));     // 专用通道 → drop
  assert.equal(sm.state, 'drop');
  assert.ok(sm.dispatch('landed'));        // force → idle
  assert.equal(sm.state, 'idle');
  assert.equal(STATES.drag.interruptible, false);
  assert.equal(STATES.drop.interruptible, false);
});

test('state-machine: typing 1.5s 超时回 idle，sleep 静默', () => {
  const sm = new StateMachine();
  sm.dispatch('keydown');
  sm.update(1500);
  assert.equal(sm.state, 'idle');
  sm.dispatch('idle');
  assert.equal(sm.quiet, true);
  sm.dispatch('wake');
  assert.equal(sm.state, 'idle');
});

test('compositor: follow 覆盖层继承主部件变换，但保留自身 alpha', () => {
  const bank = new MotionBank();
  bank.add({ id: 'a', layer: 'base', loop: true, duration: 100, fade: 0,
             tracks: { body: [{ t: 0, dy: 0 }, { t: 1, dy: 10 }] } });
  bank.add({ id: 'b', layer: 'event', duration: 100, fade: 0,
             tracks: { face: [{ t: 0, alpha: 1 }] } });
  const skin = { parts: [{ id: 'body', alpha: 1 }, { id: 'face', alpha: 0, follow: 'body' }] };
  const c = new Compositor(bank, skin);
  c.setBase(['a']);
  c.play('b');
  const t = c.tick(50, {});
  assert.ok(Math.abs(t.get('body').dy - 5) < 1e-9);
  assert.ok(Math.abs(t.get('face').dy - 5) < 1e-9, '覆盖层必须跟身体一起位移');
  assert.equal(t.get('face').alpha, 1);
  assert.equal(t.get('body').alpha, 1);
});

test('state-machine: Agent 六档状态映射到专属动作，且 busy 内可热切换', () => {
  assert.equal(AGENT_ACTION_MAP.thinking, 'thinking');
  assert.equal(AGENT_ACTION_MAP.working, 'working');
  assert.equal(AGENT_ACTION_MAP.organizing, 'organizing');
  assert.equal(AGENT_ACTION_MAP.waiting, 'waiting');
  const sm = new StateMachine();
  sm.dispatch('signal', { state: 'thinking' });
  assert.equal(sm.state, 'busy');
  assert.equal(sm.action, 'thinking');
  sm.dispatch('signal', { state: 'organizing' });
  assert.equal(sm.state, 'busy');
  assert.equal(sm.action, 'organizing');
  sm.dispatch('landed');
  assert.equal(sm.state, 'idle');
});

test('compositor: 道具互斥组同一帧只显示一个', () => {
  const bank = new MotionBank();
  bank.add({ id: 'm', layer: 'micro', duration: 1000, fade: 0,
             tracks: { coffee: [{ t: 0, alpha: 1 }] } });
  bank.add({ id: 'e', layer: 'event', duration: 1000, fade: 0,
             tracks: { laptop_code: [{ t: 0, alpha: 1 }] } });
  const skin = { parts: [
    { id: 'body', alpha: 1 },
    { id: 'laptop_code', alpha: 0, group: 'prop' },
    { id: 'coffee', alpha: 0, group: 'prop' }
  ] };
  const c = new Compositor(bank, skin);
  c.play('m');
  c.play('e');
  const t = c.tick(16, {});
  assert.equal(t.get('laptop_code').alpha, 1, '后激活的 event 动作应胜出');
  assert.equal(t.get('coffee').alpha, 0, '同组另一道具必须隐藏');
});

test('compositor: holdProp 手动锁定压制自动道具，null 后恢复', () => {
  const bank = new MotionBank();
  bank.add({ id: 'e', layer: 'event', duration: 1000, fade: 0,
             tracks: { laptop_code: [{ t: 0, alpha: 1 }] } });
  const skin = { parts: [
    { id: 'body', alpha: 1 },
    { id: 'laptop_code', alpha: 0, group: 'prop' },
    { id: 'coffee', alpha: 0, group: 'prop' }
  ] };
  const c = new Compositor(bank, skin);
  c.play('e');
  assert.equal(c.tick(16, {}).get('laptop_code').alpha, 1);
  c.holdProp('coffee');
  const held = c.tick(16, {});
  assert.equal(held.get('coffee').alpha, 1, '手动锁定的道具应常显');
  assert.equal(held.get('laptop_code').alpha, 0);
  c.holdProp(null);
  const auto = c.tick(16, {});
  assert.equal(auto.get('laptop_code').alpha, 1);
  assert.equal(auto.get('coffee').alpha, 0);
});

test('compositor: 整身姿态替身与 body 交叉淡入淡出，结束后回落默认站姿', () => {
  const bank = new MotionBank();
  bank.add({ id: 'sit', layer: 'event', duration: 200, fade: 0, tracks: {
    body:     [{ t: 0, alpha: 0 }, { t: 0.2, alpha: -1 }, { t: 0.8, alpha: -1 }, { t: 1, alpha: 0 }],
    pose_sit: [{ t: 0, alpha: 0 }, { t: 0.2, alpha: 1 },  { t: 0.8, alpha: 1 },  { t: 1, alpha: 0 }]
  } });
  const skin = { parts: [
    { id: 'body', alpha: 1 },
    { id: 'pose_sit', alpha: 0, group: 'pose' },
    { id: 'pose_walk', alpha: 0, group: 'pose' }
  ] };
  const c = new Compositor(bank, skin);
  assert.equal(c.tick(16, {}).get('body').alpha, 1);
  c.play('sit');
  const mid = c.tick(100, {});
  assert.equal(mid.get('body').alpha, 0, '站姿应淡出');
  assert.equal(mid.get('pose_sit').alpha, 1, '替身应淡入');
  assert.equal(mid.get('pose_walk').alpha, 0, '同组其他替身必须隐藏');
  for (let i = 0; i < 20; i++) c.tick(16, {});
  const end = c.tick(16, {});
  assert.equal(end.get('body').alpha, 1, '动作结束应回落站姿');
  assert.equal(end.get('pose_sit').alpha, 0);
});

test('physics: 重力落地两次回弹后停止', () => {
  const p = new Physics({ ground: 100 });
  p.release(0);
  let landed = false;
  for (let i = 0; i < 300 && !landed; i += 1) {
    p.step(16);
    landed = p.consumeLanded();
  }
  assert.ok(landed, '应产生落地事件');
  assert.equal(p.active, false);
  assert.equal(clamp(2, 0, 1), 1);
});
