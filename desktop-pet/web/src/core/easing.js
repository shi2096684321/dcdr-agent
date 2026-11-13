/**
 * 缓动与关键帧采样（DESIGN.md §6.4 / §7.1）。
 * 纯函数，无 DOM 依赖，可直接用 node --test 单测。
 */

export const easings = {
  linear: (t) => t,
  easeInOutSine: (t) => -(Math.cos(Math.PI * t) - 1) / 2,
  easeInQuad: (t) => t * t,
  easeOutQuad: (t) => 1 - (1 - t) * (1 - t),
  easeOutCubic: (t) => 1 - Math.pow(1 - t, 3),
  easeOutBack: (t) => {
    const c1 = 1.70158;
    const c3 = c1 + 1;
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
  },
  easeOutElastic: (t) => {
    if (t === 0 || t === 1) return t;
    const c4 = (2 * Math.PI) / 3;
    return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1;
  }
};

/** 关键帧增量的恒等值：alpha 是增量，故恒等为 0（见 §7.1 的 clamp 公式）。 */
export const IDENTITY = Object.freeze({ dx: 0, dy: 0, rot: 0, sx: 1, sy: 1, alpha: 0 });
export const DELTA_KEYS = ['dx', 'dy', 'rot', 'sx', 'sy', 'alpha'];

export function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

/**
 * 在相位 t(0..1) 处采样一条关键帧轨道。
 * 每个属性独立处理：只在定义了该属性的关键帧之间插值，
 * 未出现前取恒等值，出现过之后保持最后一个值（DESIGN.md §6.4 缺省规则）。
 *
 * @param {Array<{t:number, [key:string]: number}>} frames
 * @param {number} phase
 * @param {string} [easingName]
 * @returns {{dx:number,dy:number,rot:number,sx:number,sy:number,alpha:number}}
 */
export function sampleTrack(frames, phase, easingName = 'easeInOutSine') {
  const ease = easings[easingName] || easings.easeInOutSine;
  const t = clamp(Number(phase) || 0, 0, 1);
  const out = { ...IDENTITY };
  if (!Array.isArray(frames) || frames.length === 0) return out;

  for (const key of DELTA_KEYS) {
    const pts = frames
      .filter((f) => typeof f[key] === 'number' && typeof f.t === 'number')
      .sort((a, b) => a.t - b.t);
    if (pts.length === 0) continue;

    if (t < pts[0].t) {
      out[key] = IDENTITY[key];
      continue;
    }
    if (t >= pts[pts.length - 1].t) {
      out[key] = pts[pts.length - 1][key];
      continue;
    }
    let i = 0;
    while (i < pts.length - 1 && pts[i + 1].t <= t) i += 1;
    const a = pts[i];
    const b = pts[i + 1];
    const span = b.t - a.t;
    const local = span <= 0 ? 0 : (t - a.t) / span;
    out[key] = a[key] + (b[key] - a[key]) * ease(local);
  }
  return out;
}
