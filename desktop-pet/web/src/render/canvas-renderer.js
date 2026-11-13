/**
 * Canvas 2D 分层部件渲染器（DESIGN.md §6.3 绘制伪代码 / §9 优化点 3）。
 * 严格按 pivot 点定位：part.x/y 定位的是 pivot 点，不是部件中心。
 */

/** @param {string} url */
function loadImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('image load failed: ' + url));
    img.src = url;
  });
}

export class CanvasRenderer {
  /** @param {HTMLCanvasElement} canvas */
  constructor(canvas) {
    this.canvas = canvas;
    /** @type {CanvasRenderingContext2D} */
    this.ctx = canvas.getContext('2d');
    /** @type {any} */
    this.skin = null;
    /** @type {Map<string, HTMLCanvasElement>} */
    this.cache = new Map();
    /** @type {any[]} */
    this.ordered = [];
    this.dpr = 1;
    /** 显示比例：只影响位图密度与 CSS 尺寸，逻辑坐标恒为 skin.canvas 的 200×300 */
    this.scale = 1;
    /** @type {string|null} */
    this.baseUrl = null;
    this.lastKey = '';
  }

  /**
   * @param {any} skin
   * @param {string} baseUrl 以 / 结尾的皮肤包 URL
   */
  async load(skin, baseUrl) {
    this.skin = skin;
    this.baseUrl = baseUrl;
    this.lastKey = '';
    this.ordered = [...skin.parts].sort((a, b) => a.z - b.z);
    this.applyMetrics();
    await this.buildCache();
    return this;
  }

  /**
   * 按当前缩放重算画布度量。
   * 位图分辨率 = 逻辑尺寸 × 密度，CSS 尺寸 = 逻辑尺寸 × scale。
   * 绘制始终用 200×300 逻辑坐标，所以部件数值、动作、物理计算都不用改。
   */
  applyMetrics() {
    const { w, h } = this.skin.canvas;
    const dpr = Math.min(2, globalThis.devicePixelRatio || 1);
    // 下限 1：缩小到 50% 时若密度也跟着降到 0.5，位图会比原图还小、细节丢失；
    // 保持密度 ≥1 相当于超采样，画布位图大于显示尺寸，缩下去反而更清晰。
    // 上限 4：scale 2 + 2 倍屏时密度为 4，再高离屏缓存的内存不划算。
    this.dpr = Math.min(4, Math.max(1, dpr * this.scale));
    this.canvas.width = Math.round(w * this.dpr);
    this.canvas.height = Math.round(h * this.dpr);
    this.canvas.style.width = Math.round(w * this.scale) + 'px';
    this.canvas.style.height = Math.round(h * this.scale) + 'px';
  }

  /** 预渲染各部件到离屏 canvas（同一图片只解码一次）。 */
  async buildCache() {
    const dpr = this.dpr;
    this.cache.clear();
    this.lastKey = '';
    await Promise.all(this.ordered.map(async (p) => {
      const img = await loadImage(new URL(p.src, this.baseUrl).href);
      const off = document.createElement('canvas');
      off.width = Math.max(1, Math.round(p.w * dpr));
      off.height = Math.max(1, Math.round(p.h * dpr));
      const octx = off.getContext('2d');
      octx.scale(dpr, dpr);
      octx.drawImage(img, 0, 0, p.w, p.h);
      this.cache.set(p.id, off);
    }));
  }

  /**
   * 调整显示比例（0.5–3）。位图会按新密度重新预渲染，图片走浏览器缓存，很快。
   * @param {number} s
   */
  async setScale(s) {
    const next = Math.min(3, Math.max(0.5, Number(s) || 1));
    if (Math.abs(next - this.scale) < 0.001) return;
    this.scale = next;
    if (!this.skin) return;
    this.applyMetrics();
    await this.buildCache();
  }

  /**
   * 绘制一帧；变换未变化时跳过整帧重绘（§9 优化点 2）。
   * @param {Map<string, any>} transforms
   * @returns {boolean} 是否实际重绘
   */
  draw(transforms) {
    if (!this.skin) return false;
    const key = JSON.stringify([...transforms.entries()].map(([id, t]) => [
      id, t.dx.toFixed(3), t.dy.toFixed(3), t.rot.toFixed(3),
      t.sx.toFixed(4), t.sy.toFixed(4), t.alpha.toFixed(3)
    ]));
    if (key === this.lastKey) return false;
    this.lastKey = key;

    const { anchor } = this.skin.canvas;
    const ctx = this.ctx;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.clearRect(0, 0, this.skin.canvas.w, this.skin.canvas.h);

    for (const p of this.ordered) {
      const t = transforms.get(p.id);
      if (!t || t.alpha <= 0) continue;
      const off = this.cache.get(p.id);
      if (!off) continue;
      const [px, py] = p.pivot || [0.5, 0.5];
      ctx.save();
      ctx.translate(anchor.x + p.x + t.dx, anchor.y + p.y + t.dy);
      ctx.rotate(t.rot * Math.PI / 180);
      ctx.scale(t.sx * (p.flip ? -1 : 1), t.sy);
      ctx.globalAlpha = t.alpha;
      ctx.drawImage(off, -px * p.w, -py * p.h, p.w, p.h);
      ctx.restore();
    }
    return true;
  }

  /** @param {number} x @param {number} y 相对舞台左上角的 CSS 像素 */
  hitTest(x, y) {
    if (!this.skin) return false;
    const { anchor } = this.skin.canvas;
    const h = this.skin.hitbox;
    // 传入的是 CSS 像素，先除回逻辑坐标再和 skin 里的数值比较
    const rx = x / this.scale - anchor.x;
    const ry = y / this.scale - anchor.y;
    return rx >= h.x && rx <= h.x + h.w && ry >= h.y && ry <= h.y + h.h;
  }

  destroy() {
    this.cache.clear();
    this.skin = null;
    this.lastKey = '';
  }
}
