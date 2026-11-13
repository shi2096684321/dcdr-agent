import { IDENTITY, clamp } from './easing.js';

/**
 * 物理层（DESIGN.md §7.4）：弹簧惯性 + 重力下落与两次回弹。
 * 输出分两类：parts 是角色内部形变增量，window 是整个窗口的位移。
 */
const G = 1800;      // px/s^2
const SPRING_K = 90;
const SPRING_C = 14;

export class Physics {
  /** @param {{ground?:number}} [opts] */
  constructor(opts = {}) {
    this.dragging = false;
    this.falling = false;
    this.targetX = 0;
    this.x = 0;
    this.vx = 0;
    this.vy = 0;
    this.fall = 0;
    this.ground = Math.max(1, opts.ground ?? 220);
    this.bounces = 0;
    this._landed = false;
  }

  /** 拖拽期间由 main 传入鼠标横向位移，弹簧产生倾斜惯性。 */
  drag(dx) {
    this.targetX = clamp(this.targetX + dx * 0.35, -40, 40);
  }

  setDragging(v) {
    this.dragging = !!v;
    if (!v) {
      this.targetX = 0;
      this.x = 0;
      this.vx = 0;
    }
  }

  /** @param {{ground:number}} bounds 距工作区底边的可下落距离 */
  setGround(distance) {
    this.ground = Math.max(1, distance);
  }

  /** @param {number} [vy] 松手时的竖直初速（抛掷） */
  release(vy = 0) {
    this.falling = true;
    this.fall = 0;
    this.bounces = 0;
    this.vy = vy;
    this._landed = false;
  }

  get active() { return this.dragging || this.falling; }

  /** 取一次落地事件（消费后清除）。 */
  consumeLanded() {
    const v = this._landed;
    this._landed = false;
    return v;
  }

  /** @param {number} dtMs */
  step(dtMs) {
    const dt = Math.min(100, Math.max(0, dtMs)) / 1000;
    const body = { ...IDENTITY };
    const window = { dx: 0, dy: 0 };

    if (this.dragging) {
      const a = -SPRING_K * (this.x - this.targetX) - SPRING_C * this.vx;
      this.vx += a * dt;
      this.x += this.vx * dt;
      body.rot = clamp(this.x * 0.35, -14, 14);
      body.dy = 2;                       // 被拎起来的轻微拉伸
      body.sy = 1 + Math.min(0.04, Math.abs(this.vx) * 0.0006);
    } else if (this.falling) {
      this.vy += G * dt;
      let dy = this.vy * dt;
      const remain = this.ground - this.fall;
      if (remain >= 0 && dy >= remain) {
        dy = remain;
        this.bounces += 1;
        if (this.bounces >= 2) {
          this.falling = false;
          this._landed = true;
          this.vy = 0;
          body.sy = 0.90;               // 落地挤压
          body.dy = 3;
        } else {
          this.vy = -Math.abs(this.vy) * 0.35;
          this.fall = 0;
          this.ground = Math.max(16, this.ground * 0.45);
          body.sy = 0.95;
        }
      } else {
        this.fall += Math.max(0, dy);
      }
      window.dy = dy;
    }

    return { parts: { body }, window };
  }
}
