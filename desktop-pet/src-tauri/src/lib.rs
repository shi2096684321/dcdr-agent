use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::TrayIconBuilder,
    AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, State, WebviewWindow,
};

static START: OnceLock<Instant> = OnceLock::new();

fn now_ms() -> u64 {
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

pub struct PetState {
    pub hotspots: Mutex<Vec<Rect>>,
    pub last_heartbeat_ms: AtomicU64,
    pub dragging: AtomicBool,
    pub opaque: AtomicBool,
    pub passthrough: AtomicBool,
    pub always_on_top: AtomicBool,
    pub autostart: AtomicBool,
}

impl Default for PetState {
    fn default() -> Self {
        Self {
            hotspots: Mutex::new(Vec::new()),
            last_heartbeat_ms: AtomicU64::new(0),
            dragging: AtomicBool::new(false),
            opaque: AtomicBool::new(false),
            passthrough: AtomicBool::new(true),
            always_on_top: AtomicBool::new(true),
            autostart: AtomicBool::new(false),
        }
    }
}

#[cfg(windows)]
mod win {
    use windows_sys::Win32::Foundation::POINT;
    use windows_sys::Win32::System::SystemInformation::GetTickCount;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        GetAsyncKeyState, GetLastInputInfo, LASTINPUTINFO,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::GetCursorPos;

    pub fn cursor_pos() -> Option<(i32, i32)> {
        unsafe {
            let mut p: POINT = std::mem::zeroed();
            if GetCursorPos(&mut p) != 0 {
                Some((p.x, p.y))
            } else {
                None
            }
        }
    }

    pub fn left_down() -> bool {
        unsafe { (GetAsyncKeyState(0x01) as u16 & 0x8000) != 0 }
    }

    pub fn idle_ms() -> u64 {
        unsafe {
            let mut lii: LASTINPUTINFO = std::mem::zeroed();
            lii.cbSize = std::mem::size_of::<LASTINPUTINFO>() as u32;
            if GetLastInputInfo(&mut lii) == 0 {
                return 0;
            }
            GetTickCount().wrapping_sub(lii.dwTime) as u64
        }
    }
}

#[cfg(not(windows))]
mod win {
    pub fn cursor_pos() -> Option<(i32, i32)> { None }
    pub fn left_down() -> bool { false }
    pub fn idle_ms() -> u64 { 0 }
}

// ---------- IPC 命令（DESIGN.md §10.2） ----------

#[tauri::command]
fn pet_set_hotspots(state: State<'_, Arc<PetState>>, rects: Vec<Rect>) {
    if let Ok(mut hs) = state.hotspots.lock() {
        *hs = rects;
    }
    state.last_heartbeat_ms.store(now_ms(), Ordering::Relaxed);
}

#[tauri::command]
fn pet_start_drag(window: WebviewWindow, state: State<'_, Arc<PetState>>) -> Result<(), String> {
    state.dragging.store(true, Ordering::Relaxed);
    window.start_dragging().map_err(|e| e.to_string())
}

#[tauri::command]
fn pet_move_by(window: WebviewWindow, dx: f64, dy: f64) -> Result<(), String> {
    let pos = window.outer_position().map_err(|e| e.to_string())?;
    let next = tauri::Position::Physical(tauri::PhysicalPosition::new(
        pos.x + dx.round() as i32,
        pos.y + dy.round() as i32,
    ));
    window.set_position(next).map_err(|e| e.to_string())
}

#[tauri::command]
fn pet_get_idle() -> u64 {
    win::idle_ms()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ScreenInfo {
    w: u32,
    h: u32,
    work_height: u32,
    scale: f64,
    window_x: i32,
    window_y: i32,
}

#[tauri::command]
fn pet_get_screen(window: WebviewWindow) -> ScreenInfo {
    let (w, h) = match window.current_monitor() {
        Ok(Some(m)) => {
            let s = m.size();
            (s.width, s.height)
        }
        _ => (1920, 1080),
    };
    let (wx, wy) = window
        .outer_position()
        .map(|p| (p.x, p.y))
        .unwrap_or((0, 0));
    ScreenInfo {
        w,
        h,
        // 任务栏高度保守估计；P4 可换成 SPI_GETWORKAREA 精确取工作区
        work_height: h.saturating_sub(48),
        scale: window.scale_factor().unwrap_or(1.0),
        window_x: wx,
        window_y: wy,
    }
}

#[tauri::command]
fn pet_set_opaque(
    window: WebviewWindow,
    on: bool,
    state: State<'_, Arc<PetState>>,
) -> Result<(), String> {
    state.opaque.store(on, Ordering::Relaxed);
    window.emit("pet://opaque", on).map_err(|e| e.to_string())
}

/// 调整窗口尺寸（逻辑像素），并保持**底边不动**——桌宠是站在桌面上的，
/// 放大时应该往上长，而不是把脚挤到屏幕外。
#[tauri::command]
fn pet_set_size(window: WebviewWindow, w: f64, h: f64) -> Result<(), String> {
    if !(w.is_finite() && h.is_finite()) || w < 40.0 || h < 40.0 || w > 2000.0 || h > 2000.0 {
        return Err(format!("invalid size: {w}x{h}"));
    }
    let dpi = window.scale_factor().map_err(|e| e.to_string())?;
    let old_pos = window.outer_position().map_err(|e| e.to_string())?;
    let old_size = window.outer_size().map_err(|e| e.to_string())?;

    // 物理 → 逻辑统一换算，避免 1.5 倍屏时偏移量被 DPI 再放大一次
    let x = old_pos.x as f64 / dpi;
    let y = old_pos.y as f64 / dpi;
    let old_h = old_size.height as f64 / dpi;

    // 底边固定：往上长，脚不动
    let mut new_x = x;
    let mut new_y = y + old_h - h;

    // 别把角色挤出屏幕
    if let Ok(Some(mon)) = window.current_monitor() {
        let mw = mon.size().width as f64 / dpi;
        let mh = mon.size().height as f64 / dpi;
        if new_x + w > mw {
            new_x = mw - w;
        }
        if new_y + h > mh {
            new_y = mh - h;
        }
    }
    new_x = new_x.max(0.0);
    new_y = new_y.max(0.0);

    window
        .set_size(LogicalSize::new(w, h))
        .map_err(|e| e.to_string())?;
    if (new_x - x).abs() > 0.5 || (new_y - y).abs() > 0.5 {
        window
            .set_position(LogicalPosition::new(new_x, new_y))
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[derive(Serialize, Clone)]
struct SignalPayload {
    kind: String,
    value: serde_json::Value,
}

#[tauri::command]
fn pet_emit_signal(
    app: AppHandle,
    kind: String,
    value: Option<serde_json::Value>,
) -> Result<(), String> {
    app.emit(
        "pet://signal",
        SignalPayload {
            kind,
            value: value.unwrap_or(serde_json::Value::Null),
        },
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn pet_set_autostart(on: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
        let mut cmd = std::process::Command::new("reg");
        if on {
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            let path = exe.to_string_lossy().to_string();
            cmd.args([
                "add",
                KEY,
                "/v",
                "StepboyPet",
                "/t",
                "REG_SZ",
                "/d",
                path.as_str(),
                "/f",
            ]);
        } else {
            cmd.args(["delete", KEY, "/v", "StepboyPet", "/f"]);
        }
        let out = cmd.output().map_err(|e| e.to_string())?;
        if !out.status.success() {
            return Err(String::from_utf8_lossy(&out.stderr).to_string());
        }
    }
    #[cfg(not(windows))]
    let _ = on;
    Ok(())
}

#[derive(Serialize)]
struct SkinEntry {
    id: String,
    name: String,
    path: String,
}

fn skins_dir() -> std::path::PathBuf {
    let base = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    base.join("skins")
}

#[tauri::command]
fn pet_list_skins() -> Vec<SkinEntry> {
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(skins_dir()) {
        for e in rd.flatten() {
            let p = e.path();
            if !p.is_dir() || !p.join("manifest.json").exists() {
                continue;
            }
            let id = e.file_name().to_string_lossy().to_string();
            let name = std::fs::read_to_string(p.join("manifest.json"))
                .ok()
                .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
                .and_then(|v| v.get("name").and_then(|n| n.as_str()).map(String::from))
                .unwrap_or_else(|| id.clone());
            out.push(SkinEntry {
                id,
                name,
                path: p.to_string_lossy().to_string(),
            });
        }
    }
    out
}

#[tauri::command]
fn pet_read_skin(id: String) -> Result<serde_json::Value, String> {
    if id.contains("..") || id.contains('/') || id.contains('\\') {
        return Err("invalid skin id".into());
    }
    let text = std::fs::read_to_string(skins_dir().join(&id).join("manifest.json"))
        .map_err(|e| e.to_string())?;
    serde_json::from_str(&text).map_err(|e| e.to_string())
}

// ---------- 光标穿透轮询（DESIGN.md §10.3） ----------

fn cursor_in_hotspot(window: &WebviewWindow, state: &PetState) -> bool {
    let (cx, cy) = match win::cursor_pos() {
        Some(v) => v,
        None => return false,
    };
    let pos = match window.outer_position() {
        Ok(p) => p,
        Err(_) => return false,
    };
    let scale = window.scale_factor().unwrap_or(1.0);
    // 热区是窗口内逻辑坐标，这里把屏幕物理光标反算回去（与统一转物理坐标等价）
    let lx = ((cx - pos.x) as f64 / scale) as i32;
    let ly = ((cy - pos.y) as f64 / scale) as i32;
    match state.hotspots.lock() {
        Ok(hs) => hs
            .iter()
            .any(|r| lx >= r.x && lx <= r.x + r.w && ly >= r.y && ly <= r.y + r.h),
        Err(_) => false,
    }
}

fn start_cursor_watcher(app: AppHandle, state: Arc<PetState>) {
    std::thread::spawn(move || {
        let window = match app.get_webview_window("pet") {
            Some(w) => w,
            None => return,
        };
        let mut last: Option<bool> = None;
        loop {
            std::thread::sleep(Duration::from_millis(25));
            let ldown = win::left_down();
            if !ldown && state.dragging.load(Ordering::Relaxed) {
                state.dragging.store(false, Ordering::Relaxed);
            }
            let stale = now_ms()
                .saturating_sub(state.last_heartbeat_ms.load(Ordering::Relaxed))
                > 500;
            let ignore = if !state.passthrough.load(Ordering::Relaxed) {
                false
            } else if ldown {
                false
            } else if stale {
                true // fail-safe：心跳超时强制恢复穿透
            } else if cursor_in_hotspot(&window, &state) {
                false
            } else {
                true
            };
            if last != Some(ignore) {
                let _ = window.set_ignore_cursor_events(ignore);
                last = Some(ignore);
            }
        }
    });
}

// ---------- 托盘（DESIGN.md §10.4） ----------

fn handle_menu(app: &AppHandle, state: &Arc<PetState>, id: &str) {
    let window = app.get_webview_window("pet");
    match id {
        "toggle" => {
            if let Some(w) = window {
                if w.is_visible().unwrap_or(true) {
                    let _ = w.hide();
                } else {
                    let _ = w.show();
                }
            }
        }
        "top" => {
            let next = !state.always_on_top.load(Ordering::Relaxed);
            state.always_on_top.store(next, Ordering::Relaxed);
            if let Some(w) = window {
                let _ = w.set_always_on_top(next);
            }
        }
        "passthrough" => {
            let next = !state.passthrough.load(Ordering::Relaxed);
            state.passthrough.store(next, Ordering::Relaxed);
        }
        "opaque" => {
            let next = !state.opaque.load(Ordering::Relaxed);
            state.opaque.store(next, Ordering::Relaxed);
            let _ = app.emit("pet://opaque", next);
        }
        "autostart" => {
            let next = !state.autostart.load(Ordering::Relaxed);
            let result = pet_set_autostart(next);
            if result.is_ok() {
                state.autostart.store(next, Ordering::Relaxed);
            }
        }
        "skin_stepboy" | "skin_classic" => {
            let id = if id == "skin_stepboy" { "stepboy" } else { "classic" };
            let _ = pet_emit_signal(
                app.clone(),
                "skin".into(),
                Some(serde_json::Value::String(id.into())),
            );
        }
        "quit" => app.exit(0),
        _ => {}
    }
}

fn build_tray(app: &AppHandle, state: Arc<PetState>) -> Result<(), Box<dyn std::error::Error>> {
    let toggle = MenuItem::with_id(app, "toggle", "显示/隐藏", true, None::<&str>)?;
    let top = MenuItem::with_id(app, "top", "置顶开关", true, None::<&str>)?;
    let pass = MenuItem::with_id(app, "passthrough", "点击穿透开关", true, None::<&str>)?;
    let opaque = MenuItem::with_id(app, "opaque", "不透明兜底", true, None::<&str>)?;
    let autostart = MenuItem::with_id(app, "autostart", "开机自启", true, None::<&str>)?;
    let skin_q = MenuItem::with_id(app, "skin_stepboy", "皮肤：Q 版", true, None::<&str>)?;
    let skin_c = MenuItem::with_id(app, "skin_classic", "皮肤：立绘", true, None::<&str>)?;
    let sep = PredefinedMenuItem::separator(app)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &toggle, &top, &pass, &opaque, &autostart, &skin_q, &skin_c, &sep, &quit,
        ],
    )?;

    TrayIconBuilder::new()
        .icon(app.default_window_icon().unwrap().clone())
        .menu(&menu)
        .on_menu_event(move |app, event| {
            handle_menu(app, &state, event.id.as_ref());
        })
        .build(app)?;
    Ok(())
}

pub fn run() {
    let state = Arc::new(PetState::default());
    let watcher_state = state.clone();
    let tray_state = state.clone();

    tauri::Builder::default()
        .manage(state)
        .setup(move |app| {
            let handle = app.handle().clone();
            start_cursor_watcher(handle.clone(), watcher_state);
            build_tray(&handle, tray_state)?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            pet_set_hotspots,
            pet_start_drag,
            pet_move_by,
            pet_get_idle,
            pet_get_screen,
            pet_set_opaque,
            pet_set_size,
            pet_emit_signal,
            pet_set_autostart,
            pet_list_skins,
            pet_read_skin
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
