import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SKINS = path.join(ROOT, 'web', 'assets', 'skins');
const read = (...p) => JSON.parse(fs.readFileSync(path.join(...p), 'utf8'));

const MOTIONS = read(SKINS, 'stepboy', 'motions', 'index.json');
const SKIN = read(SKINS, 'stepboy', 'skin.json');
const MANIFEST = read(SKINS, 'stepboy', 'manifest.json');

test('皮肤列表与两个皮肤包都可读', () => {
  const index = read(SKINS, 'index.json');
  assert.deepEqual(index.skins.map((s) => s.id), ['stepboy', 'classic']);
  assert.equal(MANIFEST.id, 'stepboy');
  assert.equal(SKIN.id, 'stepboy');
  assert.equal(SKIN.canvas.w, 200);
  assert.equal(SKIN.canvas.h, 300);
  assert.deepEqual(SKIN.canvas.anchor, { x: 100, y: 288 });
});

test('动作清单共 62 个，且 id 与文件名一致', () => {
  assert.equal(MOTIONS.length, 62);
  assert.equal(new Set(MOTIONS).size, 62);
  for (const id of MOTIONS) {
    const m = read(SKINS, 'stepboy', 'motions', id + '.json');
    assert.equal(m.id, id, id + ' 的 id 应与文件名一致');
    assert.ok(['base', 'micro', 'event'].includes(m.layer), id + ' layer 非法');
  }
});

test('所有 tracks 的 partId 都在 skin.json 登记', () => {
  const partIds = new Set(SKIN.parts.map((p) => p.id));
  assert.ok(partIds.has('body'));
  for (const id of MOTIONS) {
    const m = read(SKINS, 'stepboy', 'motions', id + '.json');
    for (const partId of Object.keys(m.tracks || {})) {
      assert.ok(partIds.has(partId), `${id} 引用了未登记部件 ${partId}`);
    }
  }
});

test('base 层 2 个常驻动作，全部叠加部件基础 alpha 为 0', () => {
  const base = MOTIONS
    .map((id) => read(SKINS, 'stepboy', 'motions', id + '.json'))
    .filter((m) => m.layer === 'base');
  assert.equal(base.length, 2);
  assert.deepEqual(base.map((m) => m.id), ['breathe', 'sway']);
  assert.ok(base.every((m) => m.loop === true));
  assert.equal(SKIN.parts.length, 38);
  for (const p of SKIN.parts) {
    if (p.id === 'body') assert.equal(p.alpha, 1);
    else assert.equal(p.alpha, 0, p.id + ' 应默认隐藏');
  }
});

test('12 个姿态替身：互斥组 pose、底部 pivot、素材落盘', () => {
  const poses = SKIN.parts.filter((p) => p.group === 'pose');
  assert.equal(poses.length, 12);
  assert.deepEqual(poses.map((p) => p.id).sort(), [
    'pose_cheer', 'pose_coding', 'pose_desk_sleep', 'pose_drink',
    'pose_greet', 'pose_pout', 'pose_read', 'pose_sit',
    'pose_sleep', 'pose_stretch', 'pose_think', 'pose_walk',
  ]);
  for (const p of poses) {
    assert.equal(p.alpha, 0, p.id + ' 应默认隐藏');
    assert.equal(p.z, 0);
    assert.deepEqual(p.pivot, [0.5, 1]);
    assert.equal(fs.existsSync(path.join(SKINS, 'stepboy', p.src)), true);
    // 声明的 w/h 必须是源图的一半（2x 像素密度），否则角色大小会跳
    const png = fs.readFileSync(path.join(SKINS, 'stepboy', p.src.replace(/\.webp$/, '.png')));
    assert.equal(png.readUInt32BE(16) / 2, p.w, p.id + ' 的 w 与源图不符');
    assert.equal(png.readUInt32BE(20) / 2, p.h, p.id + ' 的 h 与源图不符');
  }
  for (const id of poses.map((p) => p.id)) {
    assert.ok(MOTIONS.includes('pose_' + id.replace('pose_', '')), '缺少配套动作 ' + id);
  }
  // 姿态动作必须让 body 淡出，否则两个身体会叠在一起
  for (const id of poses.map((p) => p.id)) {
    const m = read(SKINS, 'stepboy', 'motions', id + '.json');
    const bodyAlpha = (m.tracks.body || []).filter((k) => 'alpha' in k);
    assert.ok(bodyAlpha.some((k) => k.alpha === -1), id + ' 未让 body 淡出');
  }
});

test('manifest 随机池：19 个微动作 / 8 个点击动作 / 6 句梦话', () => {
  assert.equal(MANIFEST.pools.micro.length, 19);
  for (const id of ['thinking','focus','break_time','brainstorm','reading','note_taking','coffee_break','listening']) {
    assert.ok(MANIFEST.pools.micro.includes(id));
  }
  assert.ok(MANIFEST.pools.micro.includes('blink'));
  assert.equal(MANIFEST.pools.poke.length, 8);
  assert.equal(MANIFEST.pools.dream.length, 6);
  for (const name of ['micro', 'poke']) {
    for (const id of MANIFEST.pools[name]) {
      assert.ok(MOTIONS.includes(id), `pools.${name} 引用了不存在的 ${id}`);
    }
  }
});

test('思考 / 工作类动作与状态映射都落在清单内', async () => {
  const { AGENT_ACTION_MAP } = await import('../web/src/core/state-machine.js');
  const ids = Object.values(AGENT_ACTION_MAP);
  assert.equal(ids.length, 7);
  assert.equal(AGENT_ACTION_MAP.coding, 'coding');
  for (const id of [...ids, 'celebrate', 'searching', 'debugging']) {
    assert.ok(MOTIONS.includes(id), id + ' 不在动作清单内');
  }
  // thinking 是 micro 层随机动作，按设计故意不冒气泡；其余 event 层动作都有台词
  for (const id of ['working','organizing','waiting','searching','debugging','celebrate']) {
    const m = read(SKINS, 'stepboy', 'motions', id + '.json');
    assert.ok(m.line, id + ' 应有气泡台词');
  }
  assert.equal(fs.existsSync(path.join(SKINS, 'stepboy', 'parts', 'bulb.svg')), true);
  assert.equal(fs.existsSync(path.join(SKINS, 'stepboy', 'parts', 'gear.svg')), true);
});

test('6 个表情覆盖层：默认隐藏、声明 follow、素材已落盘', () => {
  const faces = SKIN.parts.filter((p) => p.id.startsWith('eyes_'));
  assert.equal(faces.length, 6);
  for (const p of faces) {
    assert.equal(p.alpha, 0);
    assert.equal(p.follow, 'body');
    assert.equal(p.z, 2);
    assert.equal(fs.existsSync(path.join(SKINS, 'stepboy', p.src)), true);
  }
  assert.ok(MOTIONS.includes('blink'));
});

test('所有 tracks 引用的表情部件都已登记', () => {
  const ids = new Set(SKIN.parts.map((p) => p.id));
  for (const id of MOTIONS) {
    const m = read(SKINS, 'stepboy', 'motions', id + '.json');
    for (const partId of Object.keys(m.tracks || {})) {
      assert.ok(ids.has(partId), `${id} 引用了未登记部件 ${partId}`);
    }
  }
});

test('带 line 的动作都有气泡文案，随机微动作不强制冒气泡', () => {
  const withLine = MOTIONS
    .map((id) => read(SKINS, 'stepboy', 'motions', id + '.json'))
    .filter((m) => m.line);
  assert.ok(withLine.length >= 8);
  assert.ok(withLine.every((m) => typeof m.line === 'string' && m.line.length > 0));
  // 微动作节奏是手感参数，断言合理区间而非具体值：
  // 太密（<1200ms）会显得不安分、一直在动；太疏（>8000ms）又像静止图片。
  const d = MANIFEST.defaults;
  assert.ok(d.microMinGapMs >= 1200, '微动作最小间隔过密，会显得一直在晃');
  assert.ok(d.microMinGapMs <= d.microMaxGapMs, '最小间隔不应大于最大间隔');
  assert.ok(d.microMaxGapMs <= 8000, '微动作最大间隔过长，会像静止图');
});

test('状态表的动作 id 都在清单内（pool 项除外）', async () => {
  const { STATES } = await import('../web/src/core/state-machine.js');
  for (const [name, spec] of Object.entries(STATES)) {
    if (spec.action) assert.ok(MOTIONS.includes(spec.action), `${name} -> ${spec.action} 不存在`);
  }
});

test('道具引用自洽；写代码三态对应三张电脑贴纸', () => {
  const props = SKIN.parts.filter((p) => p.group === 'prop').map((p) => p.id);
  const used = new Set();
  for (const id of MOTIONS) {
    const m = read(SKINS, 'stepboy', 'motions', id + '.json');
    for (const k of Object.keys(m.tracks || {})) {
      if (props.includes(k)) used.add(k);
      // 动作里引用的道具必须在 skin 中定义，否则运行时会静默丢帧
      if (/^(laptop_|book$|notebook$|coffee$|magnifier$|clipboard$|headphone$)/.test(k)) {
        assert.ok(props.includes(k), `${id} 引用了未定义的道具：${k}`);
      }
    }
  }
  // 不再要求每个道具都被引用：micro 层的道具已移除（随机触发、无台词、贴在胸口正中缺少语义），
  // book / notebook / headphone 作为备用素材保留在 skin 里，但暂时没有动作使用它们。
  assert.ok(used.size >= 6, `至少应有 6 个道具在用，实际 ${used.size}`);
  const tracksOf = (id) => Object.keys(read(SKINS, 'stepboy', 'motions', id + '.json').tracks);
  assert.ok(tracksOf('coding').includes('laptop_code'));
  assert.ok(tracksOf('compile_error').includes('laptop_error'));
  assert.ok(tracksOf('fix_done').includes('laptop_done'));
  for (const id of ['coding','compile_error','fix_done','inspecting','checklist',
                    'reading','note_taking','coffee_break','listening']) {
    assert.ok(MOTIONS.includes(id), id + ' 应在动作清单内');
  }
});

test('9 个道具贴纸：互斥组、层级、follow 与素材落盘', () => {
  const props = SKIN.parts.filter((p) => p.group === 'prop');
  assert.equal(props.length, 9);
  assert.ok(props.some((p) => p.id === 'laptop_code'));
  assert.ok(props.some((p) => p.id === 'laptop_error'));
  assert.ok(props.some((p) => p.id === 'laptop_done'));
  for (const p of props) {
    assert.equal(p.alpha, 0);
    assert.equal(p.z, 1, p.id + ' 应盖在身体之上、特效之下');
    assert.equal(p.follow, 'body', p.id + ' 必须跟随身体');
    assert.equal(p.w, 64);
    assert.equal(p.h, 64);
    assert.equal(fs.existsSync(path.join(SKINS, 'stepboy', p.src)), true);
  }
  const classic = read(SKINS, 'classic', 'skin.json');
  const cProps = classic.parts.filter((p) => p.group === 'prop');
  assert.equal(cProps.length, 9, 'classic 也应登记道具以复用动作');
});

test('classic 复用同一画布与动作，不重复落盘', () => {
  const cm = read(SKINS, 'classic', 'manifest.json');
  const cs = read(SKINS, 'classic', 'skin.json');
  assert.deepEqual(cs.canvas, SKIN.canvas);
  assert.equal(cm.motions, '../stepboy/motions/index.json');
  assert.equal(fs.existsSync(path.join(SKINS, 'classic', 'parts', 'body.webp')), true);
  assert.equal(fs.existsSync(path.join(SKINS, 'classic', 'motions')), false);
});

test('所有部件素材文件都已落盘', () => {
  for (const id of ['stepboy', 'classic']) {
    const skin = read(SKINS, id, 'skin.json');
    for (const p of skin.parts) {
      const f = path.join(SKINS, id, p.src);
      assert.equal(fs.existsSync(f), true, '缺少素材 ' + f);
    }
  }
});

test('姿态巡演池：12 个 pose 动作、4–12 s 间隔、用户坐姿图已落盘', () => {
  assert.equal(MANIFEST.pools.pose.length, 12);
  assert.deepEqual(MANIFEST.pools.pose, [
    'pose_greet', 'pose_cheer', 'pose_walk', 'pose_sit', 'pose_sleep', 'pose_stretch',
    'pose_coding', 'pose_think', 'pose_read', 'pose_drink', 'pose_pout', 'pose_desk_sleep'
  ]);
  for (const id of MANIFEST.pools.pose) {
    assert.ok(MOTIONS.includes(id), id + ' 不在动作清单内');
  }
  assert.equal(MANIFEST.defaults.poseMinGapMs, 4000);
  assert.equal(MANIFEST.defaults.poseMaxGapMs, 12000);
  // version 每改一次皮肤资源都会 +1（用于前端热重载轮询），锁死具体值没意义
  assert.ok(Number.isInteger(MANIFEST.version) && MANIFEST.version >= 1);
  const sit = SKIN.parts.find((p) => p.id === 'pose_sit');
  assert.equal(sit.w, 140.5);
  assert.equal(sit.h, 271);
  assert.equal(fs.existsSync(path.join(SKINS, 'stepboy', sit.src)), true);
  const classicPools = read(SKINS, 'classic', 'manifest.json').pools;
  assert.deepEqual(classicPools.pose, MANIFEST.pools.pose);
});
