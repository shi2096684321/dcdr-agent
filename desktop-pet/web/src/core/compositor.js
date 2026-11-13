import { clamp } from './easing.js';

/**
 * 四层动画合成器（DESIGN.md §7.1）。
 * 严格按文档公式叠加：dx/dy/rot 相加，sx/sy 相乘，alpha 为基础值 + 增量后钳制。
 */

/** @param {number} baseAlpha */
function newTransform(baseAlpha) {
  return { dx: 0, dy: 0, rot: 0, sx: 1, sy: 1, alpha: baseAlpha };
}

/** @param {any} acc @param {any} d @param {number} gain */
function apply(acc, d, gain) {
  acc.dx += (d.dx || 0) * gain;
  acc.dy += (d.dy || 0) * gain;
  acc.rot += (d.rot || 0) * gain;
  acc.sx *= 1 + ((d.sx ?? 1) - 1) * gain;
  acc.sy *= 1 + ((d.sy ?? 1) - 1) * gain;
  acc.alpha += (d.alpha || 0) * gain;
}

export class Compositor {
  /** @param {import('./motion.js').MotionBank} bank @param {any} skin */
  constructor(bank, skin) {
    this.bank = bank;
    this.skin = skin;
    this.elapsed = 0;
    /** @type {Map<string, {motion:any, elapsed:number, gain:number, fading:string}>} */
    this.active = new Map();
    /** @type {string[]} */
    this.baseIds = [];
    /** @type {Map<string, string>} 互斥组 → 手动锁定的部件 id（DESIGN.md §6.3 group）。 */
    this.held = new Map();
  }

  setBank(bank) { this.bank = bank; }
  setSkin(skin) {
    this.skin = skin;
    // 切皮肤后丢弃新皮肤里不存在的锁定，避免下拉选中一个无效 id
    for (const [g, id] of this.held) {
      if (!skin.parts.some((p) => p.id === id)) this.held.delete(g);
    }
  }

  /** 当前锁定的道具贴纸（prop 组）。 */
  get heldProp() { return this.held.get('prop') || null; }
  /** 当前锁定的整身姿态（pose 组）。 */
  get heldPose() { return this.held.get('pose') || null; }

  /**
   * 锁定任意互斥组中的部件；传入空值清空全部锁定。
   * @param {string|null} partId
   */
  hold(partId) {
    if (!partId) { this.held.clear(); return null; }
    const part = this.skin.parts.find((q) => q.id === partId && q.group);
    if (!part) return null;
    this.held.set(part.group, partId);
    return partId;
  }

  /** 只操作 prop 组（兼容既有调用与设置面板「道具」下拉）。 */
  holdProp(partId) {
    if (!partId) { this.held.delete('prop'); return null; }
    const part = this.skin.parts.find((q) => q.id === partId && q.group === 'prop');
    if (!part) return null;
    this.held.set('prop', partId);
    return partId;
  }

  /** 只操作 pose 组（可选：手动锁定整身姿态）。空值 = 恢复按动作自动切换。 */
  holdPose(partId) {
    if (!partId) { this.held.delete('pose'); return null; }
    const part = this.skin.parts.find((q) => q.id === partId && q.group === 'pose');
    if (!part) return null;
    this.held.set('pose', partId);
    return partId;
  }

  /** 常驻 base 层动作（通常只有 breathe）。 */
  setBase(ids) {
    this.baseIds = ids.filter((id) => {
      const m = this.bank.get(id);
      return m && m.layer === 'base';
    });
    return this.baseIds;
  }

  /** @param {string} id */
  play(id) {
    const m = this.bank.get(id);
    if (!m) return false;
    // event 层由状态机独占，切换时淡出上一个动作
    if (m.layer === 'event') {
      for (const [key, inst] of this.active) {
        if (key !== id && inst.motion.layer === 'event') this.stop(key);
      }
    }
    this.active.set(id, {
      motion: m,
      elapsed: 0,
      gain: m.fade > 0 ? 0 : 1,
      fading: m.fade > 0 ? 'in' : 'hold'
    });
    return true;
  }

  /** @param {string} id */
  stop(id) {
    const inst = this.active.get(id);
    if (!inst) return;
    if (inst.motion.fade > 0) inst.fading = 'out';
    else this.active.delete(id);
  }

  stopAll() { this.active.clear(); }

  /** @param {string} id */
  isPlaying(id) {
    const inst = this.active.get(id);
    return !!inst && inst.fading !== 'out';
  }

  /** 供 Clock 切换 30/60fps 使用。 */
  get hasActivity() {
    for (const inst of this.active.values()) {
      if (inst.fading !== 'out') return true;
    }
    return false;
  }

  /**
   * @param {number} dtMs
   * @param {{parts?:Record<string, any>, window?:{dx:number,dy:number}}} [physics]
   * @returns {Map<string, any>}
   */
  tick(dtMs, physics) {
    this.elapsed += dtMs;
    /** @type {Array<{partId:string, delta:any, gain:number, rank:number}>} */
    const layers = [];

    // 1. 常驻基底
    for (const id of this.baseIds) {
      const m = this.bank.get(id);
      const phase = (this.elapsed % m.duration) / m.duration;
      for (const [partId, delta] of Object.entries(this.bank.evaluate(m, phase))) {
        layers.push({ partId, delta, gain: 1, rank: 1 });
      }
    }

    // 2/3. micro + event：逐个推进相位与交叉淡化
    for (const [id, inst] of [...this.active]) {
      const m = inst.motion;
      inst.elapsed += dtMs;
      if (inst.fading === 'in') {
        inst.gain = Math.min(1, inst.gain + dtMs / m.fade);
        if (inst.gain >= 1) inst.fading = 'hold';
      } else if (inst.fading === 'out') {
        inst.gain = Math.max(0, inst.gain - dtMs / m.fade);
        if (inst.gain <= 0) {
          this.active.delete(id);
          continue;
        }
      }
      if (!m.loop && inst.elapsed >= m.duration && inst.fading !== 'out') inst.fading = 'out';
      const phase = m.loop
        ? (inst.elapsed % m.duration) / m.duration
        : Math.min(1, inst.elapsed / m.duration);
      for (const [partId, delta] of Object.entries(this.bank.evaluate(m, phase))) {
        // event 状态动作的道具优先于 micro 随机小动作，避免状态被随机动作盖掉
        const rank = m.layer === 'event' ? 3 : m.layer === 'micro' ? 2 : 1;
        layers.push({ partId, delta, gain: inst.gain, rank });
      }
    }

    // 4. 物理修正
    if (physics && physics.parts) {
      for (const [partId, delta] of Object.entries(physics.parts)) {
        layers.push({ partId, delta, gain: 1, rank: 1 });
      }
    }

    /** @type {Map<string, any>} */
    const out = new Map();
    /** @type {Map<string, string[]>} 互斥组 → 部件 id 列表 */
    const groups = new Map();
    /** @type {Map<string, string>} 部件 id → 所属互斥组 */
    const partGroup = new Map();
    /** @type {Map<string, string|null>} 互斥组 → 基础可见部件（默认站姿 / 无道具） */
    const groupDefault = new Map();
    for (const part of this.skin.parts) {
      out.set(part.id, newTransform(part.alpha ?? 1));
      if (!part.group) continue;
      partGroup.set(part.id, part.group);
      if (!groups.has(part.group)) groups.set(part.group, []);
      groups.get(part.group).push(part.id);
      if (!groupDefault.has(part.group)) groupDefault.set(part.group, null);
      if ((part.alpha ?? 1) > 0 && !groupDefault.get(part.group)) {
        groupDefault.set(part.group, part.id);
      }
    }
    /** @type {Map<string, {id:string, score:number}>} 组内当前最强唤起者 */
    const groupBest = new Map();
    for (const { partId, delta, gain, rank } of layers) {
      const acc = out.get(partId);
      if (!acc || gain <= 0) continue; // tracks 里的 partId 必须在 skin.json 登记
      apply(acc, delta, gain);
      const g = partGroup.get(partId);
      const a = delta.alpha || 0;
      if (g && a > 0) {
        const score = a * gain * rank;
        const cur = groupBest.get(g);
        // 同层分数相同时后出现的层胜出（新动作排在 active 之后）
        if (!cur || score >= cur.score) groupBest.set(g, { id: partId, score });
      }
    }
    // 继承主部件的整体变换：表情覆盖层必须跟着身体呼吸/缩放，否则眼睛会漂移。
    // alpha 不复制——它由覆盖层自己的动作增量控制（见 §7.1）。
    for (const part of this.skin.parts) {
      if (!part.follow) continue;
      const src = out.get(part.follow);
      const dst = out.get(part.id);
      if (!src || !dst) continue;
      dst.dx = src.dx;
      dst.dy = src.dy;
      dst.rot = src.rot;
      dst.sx = src.sx;
      dst.sy = src.sy;
    }
    // 互斥组仲裁：同一 group 内同时只显示一个部件。
    // 优先级：手动锁定 > 本帧动作唤起最强者 > 组内基础可见部件。
    // 最后一级回落：组内没有动作唤起时，保留基础可见部件（默认主体 / 无道具）。
    for (const [g, ids] of groups) {
      const heldId = this.held.get(g);
      const held = heldId && ids.includes(heldId) ? heldId : null;
      const win = held || groupBest.get(g)?.id || groupDefault.get(g) || null;
      for (const id of ids) {
        const acc = out.get(id);
        if (!acc) continue;
        if (id !== win) acc.alpha = 0;
        else if (held) acc.alpha = 1; // 手动锁定 = 常显，不受动作淡入淡出影响
      }
    }
    for (const acc of out.values()) acc.alpha = clamp(acc.alpha, 0, 1);
    return out;
  }
}
