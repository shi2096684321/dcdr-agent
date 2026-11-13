import { sampleTrack } from './easing.js';

/** 动作字段默认值（DESIGN.md §6.4）。 */
export const MOTION_DEFAULTS = Object.freeze({
  layer: 'event',
  duration: 600,
  loop: false,
  priority: 10,
  interruptible: true,
  weight: 1,
  easing: 'easeInOutSine',
  fade: 120,
  trigger: null
});

/** @param {any} raw */
export function normalizeMotion(raw) {
  const m = { ...MOTION_DEFAULTS, ...raw, tracks: raw && raw.tracks ? raw.tracks : {} };
  m.duration = Math.max(1, Number(m.duration) || 600);
  m.fade = Math.max(0, Number(m.fade) || 0);
  m.weight = Number(m.weight);
  if (!Number.isFinite(m.weight)) m.weight = 1;
  return m;
}

export class MotionBank {
  constructor() {
    /** @type {Map<string, any>} */
    this.motions = new Map();
  }

  /** @param {any} raw */
  add(raw) {
    const m = normalizeMotion(raw);
    this.motions.set(m.id, m);
    return m;
  }

  /** @param {string} id */
  get(id) {
    return this.motions.get(id);
  }

  all() {
    return [...this.motions.values()];
  }

  /** @param {string} layer */
  byLayer(layer) {
    return this.all().filter((m) => m.layer === layer);
  }

  /**
   * 从皮肤包加载动作索引与全部动作 JSON。
   * @param {string} baseUrl 以 / 结尾的皮肤包 URL
   * @param {string} [indexUrl]
   */
  async load(baseUrl, indexUrl = 'motions/index.json') {
    const index = new URL(indexUrl, baseUrl);
    const dir = new URL('.', index); // 动作文件与 index.json 同目录
    const listRes = await fetch(index);
    if (!listRes.ok) throw new Error('motions index load failed: ' + listRes.status);
    const list = await listRes.json();
    const raws = await Promise.all(
      list.map(async (id) => {
        const r = await fetch(new URL(`${id}.json`, dir));
        if (!r.ok) throw new Error('motion load failed: ' + id);
        return r.json();
      })
    );
    this.motions.clear();
    for (const raw of raws) this.add(raw);
    return this.all();
  }

  /**
   * 求一个动作在给定相位下各部件的增量。
   * @param {any} motion
   * @param {number} phase 0..1
   * @returns {Record<string, ReturnType<typeof sampleTrack>>}
   */
  evaluate(motion, phase) {
    const out = {};
    for (const [partId, frames] of Object.entries(motion.tracks || {})) {
      out[partId] = sampleTrack(frames, phase, motion.easing);
    }
    return out;
  }
}
