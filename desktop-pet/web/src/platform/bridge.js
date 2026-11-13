/**
 * 平台桥（DESIGN.md §10.2）。
 * 只使用文档列出的 10 个 IPC 命令，经 __TAURI_INTERNALS__.invoke 直连，
 * 不依赖 @tauri-apps/api。Web 预览下全部降级为本地模拟。
 */

const internals = globalThis.__TAURI_INTERNALS__;
export const isTauri = !!(internals && typeof internals.invoke === 'function');

/** @param {string} cmd @param {Record<string, any>} [args] */
async function invoke(cmd, args = {}) {
  if (!isTauri) return null;
  try {
    return await internals.invoke(cmd, args);
  } catch (err) {
    console.warn('[bridge] invoke failed:', cmd, err);
    return null;
  }
}

let lastActivity = Date.now();
let domOffset = { x: 0, y: 0 };
let pendingX = 0;
let pendingY = 0;

export const bridge = {
  isTauri,

  noteActivity() { lastActivity = Date.now(); },

  startDrag() {
    return invoke('pet_start_drag');
  },

  /** @param {number} dx @param {number} dy */
  async moveWindowBy(dx, dy) {
    if (isTauri) {
      // 窗口坐标是整数：累积亚像素位移后再提交，避免每帧被截断导致下落停滞
      pendingX += dx;
      pendingY += dy;
      const sx = Math.trunc(pendingX);
      const sy = Math.trunc(pendingY);
      pendingX -= sx;
      pendingY -= sy;
      if (sx === 0 && sy === 0) return true;
      return invoke('pet_move_by', { dx: sx, dy: sy });
    }
    domOffset.x += dx;
    domOffset.y += dy;
    const stage = document.getElementById('stage');
    if (stage) stage.style.transform = `translate(${domOffset.x}px, ${domOffset.y}px)`;
    return true;
  },

  /** @param {any[]} rects */
  setHotspots(rects) {
    return invoke('pet_set_hotspots', { rects });
  },

  async getIdleMs() {
    if (isTauri) {
      const v = await invoke('pet_get_idle');
      if (typeof v === 'number') return v;
      return 0;
    }
    return Date.now() - lastActivity;
  },

  async getScreen() {
    if (isTauri) {
      const v = await invoke('pet_get_screen');
      if (v && typeof v.w === 'number') return v;
    }
    return {
      w: globalThis.innerWidth || 1920,
      h: globalThis.innerHeight || 1080,
      workHeight: globalThis.innerHeight || 1080,
      scale: globalThis.devicePixelRatio || 1
    };
  },

  /** @param {boolean} on true = 启用不透明兜底 */
  setOpaque(on) {
    document.documentElement.style.background = on ? '#eef3f8' : 'transparent';
    return invoke('pet_set_opaque', { on });
  },

  /**
   * 调整窗口尺寸（逻辑像素）。桌面模式下 Rust 会保持底边不动，Web 预览下只改 DOM。
   * @param {number} w @param {number} h
   */
  setWindowSize(w, h) {
    if (!isTauri) {
      const stage = document.getElementById('stage');
      if (stage) {
        stage.style.width = Math.round(w) + 'px';
        stage.style.height = Math.round(h) + 'px';
      }
      return Promise.resolve(true);
    }
    return invoke('pet_set_size', { w, h });
  },

  /** @param {string} kind @param {any} value */
  emitSignal(kind, value) {
    const payload = { kind, value };
    if (!isTauri) {
      globalThis.dispatchEvent(new CustomEvent('pet-signal', { detail: payload }));
      return Promise.resolve(true);
    }
    return invoke('pet_emit_signal', payload);
  },

  /** @param {boolean} on */
  setAutostart(on) {
    return invoke('pet_set_autostart', { on });
  },

  /** P4：外部 skins/ 目录，Web 下降级为内置 index.json */
  async listSkins() {
    if (isTauri) {
      const v = await invoke('pet_list_skins');
      if (Array.isArray(v)) return v;
    }
    try {
      const r = await fetch('./assets/skins/index.json', { cache: 'no-store' });
      if (r.ok) return (await r.json()).skins || [];
    } catch { /* ignore */ }
    return [];
  },

  /** @param {string} id */
  readSkin(id) {
    return invoke('pet_read_skin', { id });
  },

  /** 外部信号监听（§8.2）：Tauri 事件 + Web 自定义事件双通道 */
  onSignal(handler) {
    if (isTauri && internals.event && typeof internals.event.listen === 'function') {
      internals.event.listen('pet://signal', (e) => {
        const d = e && e.payload ? e.payload : {};
        handler(d.kind, d.value);
      }).catch(() => {});
      internals.event.listen('pet://opaque', (e) => {
        document.documentElement.style.background =
          e && e.payload ? '#eef3f8' : 'transparent';
      }).catch(() => {});
    }
    globalThis.addEventListener('pet-signal', (e) => {
      const d = /** @type {CustomEvent} */ (e).detail || {};
      handler(d.kind, d.value);
    });
  }
};
