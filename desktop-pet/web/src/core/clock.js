/**
 * 帧循环（DESIGN.md §3.2 / §9）：动态帧率 + 隐藏时暂停。
 * onTick 收到的是毫秒 deltaTime，与动作 duration/fade 的毫秒单位一致。
 */
export class Clock {
  /**
   * @param {(dtMs:number)=>void} onTick
   * @param {{idleFps?:number, activeFps?:number}} [opts]
   */
  constructor(onTick, opts = {}) {
    this.onTick = onTick;
    this.idleFps = opts.idleFps ?? 30;
    this.activeFps = opts.activeFps ?? 60;
    this.active = false;
    this.running = false;
    this.raf = 0;
    this.last = 0;
    this.acc = 0;
    this._frame = this._frame.bind(this);
    this._visibility = () => {
      if (document.hidden) this.pause();
      else this.resume();
    };
  }

  /** 有活动动作时切到 60fps，静止时 30fps。 */
  setActive(v) {
    this.active = !!v;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.last = performance.now();
    this.acc = 0;
    document.addEventListener('visibilitychange', this._visibility);
    this.raf = requestAnimationFrame(this._frame);
  }

  pause() {
    if (!this.running || !this.raf) return;
    cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  resume() {
    if (!this.running || this.raf || document.hidden) return;
    this.last = performance.now();
    this.acc = 0;
    this.raf = requestAnimationFrame(this._frame);
  }

  stop() {
    this.running = false;
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
    document.removeEventListener('visibilitychange', this._visibility);
  }

  /** @param {number} now */
  _frame(now) {
    if (!this.running) return;
    this.raf = requestAnimationFrame(this._frame);
    // 钳制单帧步长，避免切回标签页时出现大 dt 跳变
    const dt = Math.min(100, Math.max(0, now - this.last));
    this.last = now;
    this.acc += dt;
    const interval = 1000 / (this.active ? this.activeFps : this.idleFps);
    if (this.acc < interval) return;
    const step = this.acc;
    this.acc = 0;
    this.onTick(step);
  }
}
