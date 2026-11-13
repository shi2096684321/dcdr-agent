/**
 * 信号采集与心情数值（DESIGN.md §7.3 / §8.1）。
 * 只产出事件给 main，由 main 转发状态机；不直接持有 DOM 之外的状态。
 */
export class Signals {
  /**
   * @param {{bridge:any, onEvent?:(type:string,payload?:any)=>void,
   *          idleToSleepMs?:number}} opts
   */
  constructor(opts) {
    this.bridge = opts.bridge;
    this.onEvent = opts.onEvent || (() => {});
    this.idleToSleepMs = opts.idleToSleepMs ?? 300000;
    this.mood = 0.6;
    this.sleeping = false;
    this._checkAcc = 0;
  }

  /** 记录一次用户活动；睡眠中则唤醒。 */
  note(type) {
    if (this.sleeping) {
      this.sleeping = false;
      this.onEvent('wake');
    }
    if (type === 'click' || type === 'feed') this.feed(0.12);
    if (type && type !== 'idle') this.onEvent(type);
  }

  /** 外部信号注入（对应 pet_emit_signal / 本地配置文件）。 */
  inject(kind, value) {
    if (kind === 'balance') this.onEvent('balance', value);
    else if (kind === 'agent') this.onEvent('signal', { state: value });
    else if (kind === 'build') this.onEvent('signal', { state: value === 'error' ? 'error' : 'working' });
    else this.onEvent(kind, value);
  }

  /** @param {number} delta */
  feed(delta) {
    this.mood = Math.min(1, Math.max(0, this.mood + delta));
  }

  /** 心情只作为随机池权重乘数，不新增状态（§7.3）。 */
  weightOf(motion) {
    let w = Number(motion.weight ?? 1);
    if (this.mood < 0.35 && ['sigh', 'stretch'].includes(motion.id)) w *= 1.6;
    if (this.mood > 0.7 && ['happy', 'look'].includes(motion.id)) w *= 1.5;
    return w;
  }

  /** @param {number} dtMs */
  tick(dtMs) {
    this.mood = Math.max(0, this.mood - dtMs * 0.00001);
    this._checkAcc += dtMs;
    if (this._checkAcc < 1000) return;
    this._checkAcc = 0;
    // 空闲检测交给 bridge：Tauri 用 GetLastInputInfo，Web 下用本地计时
    Promise.resolve(this.bridge.getIdleMs()).then((ms) => {
      if (typeof ms !== 'number') return;
      if (!this.sleeping && ms >= this.idleToSleepMs) {
        this.sleeping = true;
        this.onEvent('idle');
      } else if (this.sleeping && ms < 1000) {
        this.sleeping = false;
        this.onEvent('wake');
      }
    }).catch(() => {});
  }
}
