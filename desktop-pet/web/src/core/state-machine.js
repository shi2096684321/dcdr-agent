/**
 * 状态机（DESIGN.md §7.2）。
 * 优先级与打断规则严格按文档表格；drag ⇄ drop 为专用通道。
 */

/** Agent 状态 → 专属动作（DESIGN.md §8.2）；coding 为写代码态，带电脑贴纸。 */
export const AGENT_ACTION_MAP = Object.freeze({
  thinking: 'thinking',
  working: 'working',
  organizing: 'organizing',
  waiting: 'waiting',
  searching: 'searching',
  debugging: 'debugging',
  coding: 'coding'
});

export const STATES = Object.freeze({
  idle:   { priority: 0,  interruptible: true,  action: null,          pool: null },
  follow: { priority: 5,  interruptible: true,  action: 'look',        pool: null },
  typing: { priority: 10, interruptible: true,  action: 'type_tap',    pool: null },
  busy:   { priority: 12, interruptible: true,  action: 'work',        pool: null },
  sleep:  { priority: 15, interruptible: true,  action: 'sleep',       pool: null },
  poke:   { priority: 20, interruptible: true,  action: null,          pool: 'poke' },
  alert:  { priority: 25, interruptible: true,  action: 'alert',       pool: null },
  drag:   { priority: 30, interruptible: false, action: 'struggle',    pool: null },
  drop:   { priority: 30, interruptible: false, action: 'land',        pool: null }
});

export class StateMachine {
  /**
   * @param {{onEnter?:(to:string,from:string,action:string|null)=>void,
   *          onExit?:(from:string,to:string)=>void,
   *          pickPool?:(name:string)=>string|null}} [opts]
   */
  constructor(opts = {}) {
    this.onEnter = opts.onEnter || (() => {});
    this.onExit = opts.onExit || (() => {});
    this.pickPool = opts.pickPool || (() => null);
    this.current = 'idle';
    this.action = /** @type {string|null} */ (null);
    this.nextAction = /** @type {string|null} */ (null);
    this.elapsed = 0;
  }

  get state() { return this.current; }
  /** sleep 状态下屏蔽一切随机微动作、碎碎念与告警。 */
  get quiet() { return this.current === 'sleep'; }
  /** drag/drop 期间不由外部打断。 */
  get locked() { return !STATES[this.current].interruptible; }

  /** @param {string} from @param {string} to */
  canInterrupt(from, to) {
    if (from === to) return false;
    if ((from === 'drag' && to === 'drop') || (from === 'drop' && to === 'drag')) return true;
    if (!STATES[from].interruptible) return false;
    if (to === 'idle') return true;
    return STATES[to].priority >= STATES[from].priority;
  }

  /** @param {string} to @param {boolean} [force] */
  _go(to, force = false) {
    if (to === this.current) return false;
    if (!force && !this.canInterrupt(this.current, to)) return false;
    const from = this.current;
    this.onExit(from, to, this.action);
    this.current = to;
    this.elapsed = 0;
    const spec = STATES[to];
    this.action = spec.pool ? this.pickPool(spec.pool) : (this.nextAction || spec.action);
    this.nextAction = null;
    this.onEnter(to, from, this.action);
    return true;
  }

  /** @param {string} event @param {any} [payload] */
  dispatch(event, payload = {}) {
    switch (event) {
      case 'pointerdown': return this._go('drag');
      case 'pointerup':   return this.current === 'drag' ? this._go('drop') : false;
      case 'click':       return this._go('poke');
      case 'keydown':     return this._go('typing');
      case 'pointernear': return this._go('follow');
      case 'pointerleave':
        return this.current === 'follow' ? this._go('idle', true) : false;
      case 'idle':        return this._go('sleep');
      case 'wake':        return this.current === 'sleep' ? this._go('idle', true) : false;
      case 'signal': {
        const st = (payload && payload.state) || 'working';
        const action = AGENT_ACTION_MAP[st] || 'work';
        if (this.current === 'busy') {
          // 同一 busy 状态下热切换工作动作（thinking → organizing 等）
          this.onExit(this.current, this.current, this.action);
          this.action = action;
          this.elapsed = 0;
          this.onEnter(this.current, this.current, action);
          return true;
        }
        this.nextAction = action;
        const ok = this._go('busy');
        if (!ok) this.nextAction = null;
        return ok;
      }
      case 'actionDone':
      case 'landed':      return this._go('idle', true);
      default:            return false;
    }
  }

  /** @param {number} dtMs */
  update(dtMs) {
    this.elapsed += dtMs;
    if (this.current === 'typing' && this.elapsed >= 1500) this._go('idle', true);
    else if (this.current === 'alert' && this.elapsed >= 3000) this._go('idle', true);
  }
}
