import { Clock } from './core/clock.js';
import { MotionBank } from './core/motion.js';
import { Compositor } from './core/compositor.js';
import { StateMachine } from './core/state-machine.js';
import { Physics } from './core/physics.js';
import { Signals } from './core/signals.js';
import { CanvasRenderer } from './render/canvas-renderer.js';
import { bridge, isTauri } from './platform/bridge.js';

const SKINS_BASE = new URL('./assets/skins/', location.href);

const stage = document.getElementById('stage');
const canvas = document.getElementById('pet');
const bubble = document.getElementById('bubble');
const settings = document.getElementById('settings');
const skinSelect = document.getElementById('skin-select');
const propSelect = document.getElementById('prop-select');
const motionSelect = document.getElementById('motion-select');
const sizeSelect = document.getElementById('size-select');
const optTransparent = document.getElementById('opt-transparent');
const optAutostart = document.getElementById('opt-autostart');
const btnReload = document.getElementById('btn-reload');
const btnClose = document.getElementById('btn-close');
const settingsNote = document.getElementById('settings-note');

const bank = new MotionBank();
const renderer = new CanvasRenderer(canvas);
const physics = new Physics();
const compositor = new Compositor(bank, { parts: [] });
let signals = null;
let sm = null;
let clock = null;

let skinId = 'stepboy';
let manifest = null;
let skin = null;
let version = -1;
let screenInfo = null;
let microAcc = 0;
let nextMicroGap = 3000;
let poseAcc = 0;
let nextPoseGap = 2500;
let dreamAcc = 0;
let bubbleTimer = 0;
let dragging = false;
let dragMoved = false;
let downPoint = { x: 0, y: 0 };
let lastPoint = { x: 0, y: 0 };

// ---------- 显示大小 ----------
// 逻辑坐标恒为 skin.canvas 的 200×300，缩放只作用于三处：位图密度、CSS 尺寸、窗口尺寸。
// 物理、动作、热区数值全都不用改，改的只是「一个逻辑像素等于几个屏幕像素」。
const SCALE_KEY = 'stepboy.scale';
const SCALE_MIN = 0.5;
const SCALE_MAX = 2;
/** 首次使用的默认比例。200×300 的窗口在 1080p 上偏大，默认收一档。 */
const SCALE_DEFAULT = 0.6;
/** 记录「上一次发布时的默认值」，用于判断用户有没有主动改过比例。 */
const SCALE_DEFAULT_KEY = 'stepboy.scale.default';

/** @param {number} v */
function clampScale(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return SCALE_DEFAULT;
  return Math.min(SCALE_MAX, Math.max(SCALE_MIN, n));
}

let petScale = (() => {
  try {
    const ls = globalThis.localStorage;
    if (!ls) return SCALE_DEFAULT;

    // 默认值调整时让老用户跟随，但**不覆盖主动选择**：
    // 当前值仍等于「上次的默认值」→ 说明用户没动过，更新为新默认；
    // 当前值是别的数字 → 用户自己选的，保留。
    // 更早的版本没写过 SCALE_DEFAULT_KEY，只好把历代默认值列出来兜底。
    const LEGACY_DEFAULTS = [1, 0.8];
    const savedDefault = ls.getItem(SCALE_DEFAULT_KEY);
    const current = ls.getItem(SCALE_KEY);
    if (current != null) {
      const sameAsOldDefault = savedDefault != null
        ? Number(current) === Number(savedDefault)
        : LEGACY_DEFAULTS.includes(Number(current));
      if (sameAsOldDefault) ls.setItem(SCALE_KEY, String(SCALE_DEFAULT));
    }
    ls.setItem(SCALE_DEFAULT_KEY, String(SCALE_DEFAULT));

    const raw = ls.getItem(SCALE_KEY);
    return raw == null ? SCALE_DEFAULT : clampScale(raw);
  } catch {
    return SCALE_DEFAULT;
  }
})();

/**
 * 应用显示比例：渲染器密度、舞台尺寸、窗口尺寸、热区一起改。
 * @param {number} s
 * @param {{skipWindow?: boolean}} [opts]
 */
async function applyScale(s, opts = {}) {
  petScale = clampScale(s);
  if (renderer.skin) await renderer.setScale(petScale);
  if (skin) {
    const w = Math.round(skin.canvas.w * petScale);
    const h = Math.round(skin.canvas.h * petScale);
    stage.style.width = w + 'px';
    stage.style.height = h + 'px';
    // 气泡等 UI 元素也跟着缩，否则小比例下会盖住半个角色
    document.documentElement.style.setProperty('--ui-scale', String(petScale));
    if (!opts.skipWindow) await bridge.setWindowSize(w, h);
  }
  if (sizeSelect) sizeSelect.value = String(petScale);
  try {
    globalThis.localStorage?.setItem(SCALE_KEY, String(petScale));
  } catch { /* 隐私模式禁用存储时忽略 */ }
  reportHotspots();
}

// 设置面板固定 184px 宽（padding 后约 204px）。桌宠缩到 80% 以下时窗口就装不下了，
// 所以打开面板时临时把窗口撑到能放下，关闭时再缩回按比例的真实尺寸。
const PANEL_MIN_W = 216;
const PANEL_MIN_H = 350;

async function showSettings() {
  if (!settings.hidden) return;
  settings.hidden = false;
  const baseW = (skin && skin.canvas.w) || 200;
  const baseH = (skin && skin.canvas.h) || 300;
  const w = Math.max(PANEL_MIN_W, Math.round(baseW * petScale));
  const h = Math.max(PANEL_MIN_H, Math.round(baseH * petScale));
  await bridge.setWindowSize(w, h);
  reportHotspots();
}

async function hideSettings() {
  if (settings.hidden) return;
  settings.hidden = true;
  await applyScale(petScale);
}

if (!isTauri) document.body.classList.add('preview-mode');

/** 从随机池按 weight × 心情系数抽取一个动作 id。 */
function pickPool(name) {
  const ids = (manifest && manifest.pools && manifest.pools[name]) || [];
  const list = ids.map((id) => bank.get(id)).filter(Boolean);
  if (list.length === 0) return null;
  const weights = list.map((m) => Math.max(0, signals ? signals.weightOf(m) : (m.weight ?? 1)));
  const total = weights.reduce((a, b) => a + b, 0);
  if (total <= 0) return list[0].id;
  let r = Math.random() * total;
  for (let i = 0; i < list.length; i += 1) {
    r -= weights[i];
    if (r <= 0) return list[i].id;
  }
  return list[list.length - 1].id;
}

/** 播放动作并在配置了 line 时冒气泡（sleep 状态由自身静默规则保证）。 */
function playWithLine(id) {
  if (!compositor.play(id)) return;
  const m = bank.get(id);
  if (m && m.line) say(m.line, 1800);
}

function randomGap() {
  const d = (manifest && manifest.defaults) || {};
  const lo = d.microMinGapMs ?? 1800;
  const hi = d.microMaxGapMs ?? 6500;
  return lo + Math.random() * Math.max(0, hi - lo);
}

function randomPoseGap() {
  const d = (manifest && manifest.defaults) || {};
  const lo = d.poseMinGapMs ?? 4000;
  const hi = d.poseMaxGapMs ?? 12000;
  return lo + Math.random() * Math.max(0, hi - lo);
}

/** classic 立绘皮肤没有 pose 替身，播姿态动作会让 body 淡出却没有替身补位。 */
function skinHasPoses() {
  return !!skin && (skin.parts || []).some((p) => p.group === 'pose');
}

function say(text, ms = 2600) {
  if (!text) return;
  bubble.textContent = text;
  bubble.hidden = false;
  requestAnimationFrame(() => bubble.classList.add('show'));
  clearTimeout(bubbleTimer);
  bubbleTimer = setTimeout(hideBubble, ms);
}

function hideBubble() {
  bubble.classList.remove('show');
  clearTimeout(bubbleTimer);
  bubbleTimer = setTimeout(() => { bubble.hidden = true; }, 200);
}

function note(text) {
  if (settingsNote) settingsNote.textContent = text;
}

/** 手动换道具贴纸；空值 = 恢复按动作自动切换。 */
function setProp(value) {
  const id = value ? String(value) : '';
  compositor.holdProp(id || null);
  if (propSelect) propSelect.value = id;
}

/** 皮肤加载后按该皮肤登记的 group='prop' 部件重建下拉。 */
function refreshPropOptions() {
  if (!propSelect) return;
  const props = (skin.parts || []).filter((p) => p.group === 'prop');
  propSelect.innerHTML = '';
  const auto = document.createElement('option');
  auto.value = '';
  auto.textContent = '自动（跟随状态）';
  propSelect.appendChild(auto);
  for (const p of props) {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.id;
    propSelect.appendChild(opt);
  }
  propSelect.value = compositor.heldProp || '';
}

/** 皮肤加载后列出全部动作，便于逐个试播（含三态电脑等道具动作）。 */
function refreshMotionOptions() {
  if (!motionSelect) return;
  motionSelect.innerHTML = '';
  const hint = document.createElement('option');
  hint.value = '';
  hint.textContent = '选择动作…';
  motionSelect.appendChild(hint);
  for (const m of bank.all()) {
    const opt = document.createElement('option');
    opt.value = m.id;
    opt.textContent = m.id;
    motionSelect.appendChild(opt);
  }
  motionSelect.value = '';
}

async function refreshGround() {
  screenInfo = await bridge.getScreen();
  const h = skin ? skin.canvas.h : 300;
  const y = typeof screenInfo.windowY === 'number'
    ? screenInfo.windowY
    : (screenInfo.workHeight || screenInfo.h || 1080) - h;
  physics.setGround(Math.max(16, (screenInfo.workHeight || screenInfo.h || 1080) - y - h));
}

/** 加载 / 重载一个皮肤包；不清空状态机（DESIGN.md §6.8）。 */
async function loadSkin(id) {
  const base = new URL(`./${id}/`, SKINS_BASE).href;
  const [mf, sk] = await Promise.all([
    fetch(new URL('manifest.json', base), { cache: 'no-store' }).then((r) => {
      if (!r.ok) throw new Error('manifest.json ' + r.status);
      return r.json();
    }),
    fetch(new URL('skin.json', base), { cache: 'no-store' }).then((r) => {
      if (!r.ok) throw new Error('skin.json ' + r.status);
      return r.json();
    })
  ]);
  const nextBank = new MotionBank();
  await nextBank.load(base, mf.motions || 'motions/index.json');
  await renderer.load(sk, base);

  manifest = mf;
  skin = sk;
  version = Number(mf.version) || 1;
  skinId = id;
  bank.motions = nextBank.motions;
  compositor.setBank(bank);
  compositor.setSkin(skin);
  compositor.setBase(bank.byLayer('base').map((m) => m.id));
  compositor.stopAll();

  // 舞台尺寸按当前缩放来，同时同步窗口大小（窗口改了，下面的 refreshGround 才准）
  await applyScale(petScale);
  refreshPropOptions();
  refreshMotionOptions();

  if (signals) signals.idleToSleepMs = (manifest.defaults || {}).idleToSleepMs ?? 300000;
  nextMicroGap = randomGap();
  poseAcc = 0;
  nextPoseGap = 2500;   // 首次 2.5 s 就换一次整身姿态，打开预览立刻能看到变化
  await refreshGround();
}

/** 状态进入时的动作与副作用。 */
function onEnter(to, from, action) {
  if (action) playWithLine(action);
  if (to === 'drag') {
    physics.setDragging(true);
    stage.classList.add('dragging');
  }
  if (to === 'drop') {
    physics.setDragging(false);
    stage.classList.remove('dragging');
    refreshGround().then(() => physics.release(0));
  }
  if (to === 'sleep') {
    dreamAcc = 0;
    hideBubble();
  }
  if (from === 'sleep' && to !== 'sleep') hideBubble();
  if (to === 'idle' && from === 'sleep') playWithLine('wake_up');
}

function onExit(from, to, action) {
  if (action) compositor.stop(action);   // 退出状态时淡出该状态动作
  if (from === 'drag') {
    physics.setDragging(false);
    stage.classList.remove('dragging');
  }
}

/** 信号 → 状态机 / 数值分档。 */
function onSignalEvent(type, payload) {
  switch (type) {
    case 'wake':      sm.dispatch('wake'); break;
    case 'idle':      sm.dispatch('idle'); break;
    case 'signal': {
      const st = (payload && payload.state) || 'working';
      if (st === 'success') playWithLine('celebrate');
      else if (st === 'error') playWithLine('debugging');
      else sm.dispatch('signal', payload || {});   // 六档映射由状态机 AGENT_ACTION_MAP 处理
      break;
    }
    case 'keydown':   sm.dispatch('keydown'); break;
    case 'click':     sm.dispatch('click'); break;
    case 'pointernear':   sm.dispatch('pointernear'); break;
    case 'pointerleave':  sm.dispatch('pointerleave'); break;
    case 'skin':
      if (payload) loadSkin(String(payload)).catch(() => {});
      break;
    case 'prop':
      setProp(payload);   // 外部信号 / 托盘可直接指定当前贴纸
      break;
    case 'balance': {
      // 数值分档复用已有动作，不新增状态（DESIGN.md §8.3）
      const v = Number(payload) || 0;
      playWithLine(v < 0.2 ? 'balance_low' : v < 0.5 ? 'sigh' : v < 0.8 ? 'look' : 'happy');
      break;
    }
    default: break;
  }
}

/** 每帧主循环。 */
function tick(dtMs) {
  signals.tick(dtMs);
  sm.update(dtMs);

  // 整身姿态播放期间，暂停随机微动作：姿态立绘自带姿势与道具，
  // 而 micro 的表情覆盖层/道具贴片是按站姿坐标绘制的，叠到坐姿、趴姿上会错位。
  const poseBusy = ((manifest.pools || {}).pose || []).some((id) => compositor.isPlaying(id));

  // 随机微动作：sleep、drag/drop 与整身姿态播放期间静默
  if (!sm.quiet && !sm.locked && !poseBusy) {
    microAcc += dtMs;
    if (microAcc >= nextMicroGap) {
      microAcc = 0;
      nextMicroGap = randomGap();
      const id = pickPool('micro');
      if (id) compositor.play(id);
    }
  } else {
    microAcc = 0;   // 不累计，避免姿态结束后立刻补发一堆贴片
  }

  // 整身姿态巡演：idle 且当前皮肤登记了 pose 替身时，定时随机换一整套立绘
  if (sm.state === 'idle' && !sm.quiet && !sm.locked && skinHasPoses()) {
    if (!poseBusy) {
      poseAcc += dtMs;
      if (poseAcc >= nextPoseGap) {
        poseAcc = 0;
        nextPoseGap = randomPoseGap();
        const id = pickPool('pose');
        if (id) playWithLine(id);   // 姿态动作自带 line 台词
      }
    }
  } else {
    poseAcc = 0;                    // 交互期间不累计，避免打断后立刻弹出
  }

  // 睡眠梦话：只弹气泡，不出声
  if (sm.quiet) {
    dreamAcc += dtMs;
    if (dreamAcc >= 4200) {
      dreamAcc = 0;
      const lines = (manifest.pools || {}).dream || [];
      if (lines.length) say(lines[Math.floor(Math.random() * lines.length)], 2200);
    }
  }

  // poke 动作播完回 idle
  if (sm.state === 'poke' && sm.action && !compositor.isPlaying(sm.action)) {
    sm.dispatch('actionDone');
  }

  const ph = physics.step(dtMs);
  if (ph.window && ph.window.dy) bridge.moveWindowBy(0, ph.window.dy);
  if (physics.consumeLanded()) sm.dispatch('landed');

  const transforms = compositor.tick(dtMs, ph);
  renderer.draw(transforms);
  clock.setActive(compositor.hasActivity || physics.active || sm.locked);
}

/** 100 ms 热区上报 + 心跳（DESIGN.md §5.4 / §10.3）。 */
function reportHotspots() {
  if (!skin) return;
  const a = skin.canvas.anchor;
  const h = skin.hitbox;
  // Rust 侧把屏幕光标反算成「窗口内逻辑坐标」，窗口已被放大 s 倍，
  // 所以这里上报的热区也必须乘 s，否则角色放大后点不到。
  const s = petScale;
  const rects = [{ x: (a.x + h.x) * s, y: (a.y + h.y) * s, w: h.w * s, h: h.h * s }];
  if (!bubble.hidden) rects.push({ x: 0, y: 0, w: skin.canvas.w * s, h: 44 * s });
  if (!settings.hidden) {
    // 面板打开时窗口可能被临时撑大（见 showSettings），按实际包围盒上报才点得到
    const r = settings.getBoundingClientRect();
    rects.push({ x: r.left, y: r.top, w: r.width, h: r.height });
  }
  bridge.setHotspots(rects);
}

function pointInSettings(e) {
  return !settings.hidden && settings.contains(e.target);
}

function bindEvents() {
  stage.addEventListener('pointerdown', (e) => {
    if (pointInSettings(e)) return;
    const rect = stage.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    if (!renderer.hitTest(x, y)) return;
    bridge.noteActivity();
    dragging = true;
    dragMoved = false;
    downPoint = { x: e.clientX, y: e.clientY };
    lastPoint = { x: e.clientX, y: e.clientY };
  });

  globalThis.addEventListener('pointermove', (e) => {
    bridge.noteActivity();
    if (!dragging) return;
    const dx = e.clientX - lastPoint.x;
    const dy = e.clientY - lastPoint.y;
    if (!dragMoved && Math.abs(e.clientX - downPoint.x) + Math.abs(e.clientY - downPoint.y) > 4) {
      dragMoved = true;
      sm.dispatch('pointerdown');        // 确认是拖拽才进入 drag 状态
      if (isTauri) bridge.startDrag();   // 桌面模式交给系统原生拖拽
    }
    if (dragMoved) {
      physics.drag(dx);
      if (!isTauri) bridge.moveWindowBy(dx, dy);
    }
    lastPoint = { x: e.clientX, y: e.clientY };
  });

  globalThis.addEventListener('pointerup', () => {
    if (!dragging) return;
    dragging = false;
    if (dragMoved) sm.dispatch('pointerup');  // drag → drop（重力下落）
    else signals.note('click');               // 未移动视为点击 → poke
  });

  stage.addEventListener('pointerenter', () => {
    bridge.noteActivity();
    signals.note('pointernear');
  });
  stage.addEventListener('pointerleave', () => signals.note('pointerleave'));

  globalThis.addEventListener('keydown', () => {
    bridge.noteActivity();
    signals.note('keydown');
  });

  stage.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showSettings();
    note(isTauri ? '桌面模式' : 'Web 预览模式（窗口相关能力由托盘提供）');
  });

  skinSelect.addEventListener('change', async () => {
    try {
      await loadSkin(skinSelect.value);
      note('已切换到 ' + skinSelect.value);
    } catch (err) {
      note('切换失败：' + err.message);
    }
  });

  sizeSelect.addEventListener('change', async () => {
    try {
      await applyScale(sizeSelect.value);
      note('大小：' + Math.round(petScale * 100) + '%');
    } catch (err) {
      note('调整大小失败：' + err.message);
    }
  });

  propSelect.addEventListener('change', () => {
    setProp(propSelect.value);
    note(propSelect.value ? '道具已锁定：' + propSelect.value : '道具恢复自动切换');
  });

  motionSelect.addEventListener('change', () => {
    const id = motionSelect.value;
    if (!id) return;
    playWithLine(id);
    note('试播：' + id);
  });

  optTransparent.addEventListener('change', () => bridge.setOpaque(!optTransparent.checked));
  optAutostart.addEventListener('change', () => bridge.setAutostart(optAutostart.checked));
  btnReload.addEventListener('click', async () => {
    try {
      await loadSkin(skinId);
      note('皮肤已重载');
    } catch (err) {
      note('重载失败：' + err.message);
    }
  });
  btnClose.addEventListener('click', () => { hideSettings(); });
}

async function init() {
  signals = new Signals({
    bridge,
    idleToSleepMs: 300000,
    onEvent: onSignalEvent
  });
  sm = new StateMachine({ pickPool, onEnter, onExit });

  await loadSkin(skinId);
  signals.idleToSleepMs = (manifest.defaults || {}).idleToSleepMs ?? 300000;

  const skins = await bridge.listSkins();
  skinSelect.innerHTML = '';
  for (const item of skins) {
    const id = typeof item === 'string' ? item : item.id;
    const opt = document.createElement('option');
    opt.value = id;
    opt.textContent = typeof item === 'string' ? id : (item.name || id);
    skinSelect.appendChild(opt);
  }
  skinSelect.value = skinId;

  bridge.onSignal((kind, value) => signals.inject(kind, value));

  clock = new Clock(tick);
  bindEvents();
  clock.start();

  setInterval(reportHotspots, 100);

  // 热重载：轮询 manifest.version（DESIGN.md §6.8）
  setInterval(async () => {
    try {
      const r = await fetch(new URL(`./${skinId}/manifest.json`, SKINS_BASE), { cache: 'no-store' });
      if (!r.ok) return;
      const mf = await r.json();
      if ((Number(mf.version) || 1) !== version) {
        await loadSkin(skinId);
        note('检测到皮肤变更，已热重载');
      }
    } catch { /* 轮询失败不影响运行 */ }
  }, 2000);
}

init().catch((err) => {
  console.error('[stepboy] init failed', err);
  settings.hidden = false;
  if (settingsNote) settingsNote.textContent = '初始化失败：' + err.message;
});
