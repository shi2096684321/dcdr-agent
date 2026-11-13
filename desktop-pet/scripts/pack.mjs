#!/usr/bin/env node
/**
 * 一键打包桌宠：构建 release 版并输出到 dist/。
 *
 * 用法：
 *   npm run pack
 *   node scripts/pack.mjs --out "D:\\somewhere"   # 自定义输出目录
 *
 * 为什么要有这个脚本：
 *   1. 必须带 --features custom-protocol，否则 release 版仍会去连 devUrl，双击只会看到「无法访问此页面」。
 *   2. 必须把 MSVC / rc.exe 所在目录放到 PATH 最前，否则会命中 Git 自带的 coreutils link.exe。
 *   3. 必须把编译缓存放到项目盘之外，本项目在 E 盘，E 盘空间不足时 target 会撑爆磁盘。
 *   4. 本机工具链配置只能显式 --config 传入，不能放进 Cargo 自动加载的 .cargo/config.toml。
 *
 * 工具链位置按「环境变量 → 标准安装路径探测」解析，不绑定某一台机器：
 *   MSVC_BIN / SDK_BIN / CARGO_BIN / CARGO_TARGET_DIR 均可覆盖。
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TAURI_DIR = path.join(ROOT, 'src-tauri');
const EXE_NAME = 'Stepboy.exe';

const args = process.argv.slice(2);
const outArgIdx = args.indexOf('--out');
const DIST = outArgIdx >= 0 && args[outArgIdx + 1] ? path.resolve(args[outArgIdx + 1]) : path.join(ROOT, 'dist');

// 本机专用配置：Cargo 会自动加载 .cargo/config.toml，那份文件若写死本机路径并 force 覆盖
// INCLUDE/LIB，别人 clone 后 cargo 直接失败。改名 config.local.toml（已 gitignore），
// 由本脚本显式 --config 传入，只有真的存在这台机器的配置时才生效。
const LOCAL_CONFIG = path.join(ROOT, '.cargo', 'config.local.toml');

/** 找第一个含指定 exe 的目录；优先用环境变量，其次按候选列表探测。 */
function findBin(envName, exe, candidates) {
  const fromEnv = process.env[envName];
  if (fromEnv && fs.existsSync(path.join(fromEnv, exe))) return fromEnv;
  for (const c of candidates) {
    if (c && fs.existsSync(path.join(c, exe))) return c;
  }
  return null;
}

/** MSVC 版本号会随 BuildTools 升级变化，遍历版本目录而不是写死一个。 */
function msvcCandidates() {
  const out = [];
  for (const root of [
    'C:\\BuildTools\\VC\\Tools\\MSVC',
    'C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\MSVC',
    'C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Tools\\MSVC',
    'C:\\Program Files\\Microsoft Visual Studio\\2022\\Professional\\VC\\Tools\\MSVC',
    'C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\VC\\Tools\\MSVC',
  ]) {
    if (!fs.existsSync(root)) continue;
    let versions;
    try { versions = fs.readdirSync(root).sort().reverse(); } catch { continue; }
    for (const v of versions) out.push(path.join(root, v, 'bin', 'Hostx64', 'x64'));
  }
  return out;
}

/** rc.exe 在手动落盘的 SDK/bin，或 Windows Kits 的版本目录里。 */
function sdkCandidates() {
  const out = ['C:\\BuildTools\\SDK\\bin'];
  const kits = 'C:\\Program Files (x86)\\Windows Kits\\10\\bin';
  if (fs.existsSync(kits)) {
    let versions;
    try { versions = fs.readdirSync(kits).sort().reverse(); } catch { return out; }
    for (const v of versions) out.push(path.join(kits, v, 'x64'));
  }
  return out;
}

const MSVC_BIN = findBin('MSVC_BIN', 'link.exe', msvcCandidates());
const SDK_BIN = findBin('SDK_BIN', 'rc.exe', sdkCandidates());
const CARGO_BIN = process.env.CARGO_BIN || path.join(os.homedir(), '.cargo', 'bin');

// 编译缓存默认放系统临时目录，避免撑爆项目所在盘。
// 旧版本固定在 C:\cargo-build-stepboy，那里若已有缓存就继续沿用，省一次 15 分钟全量重编。
const LEGACY_BUILD_DIR = 'C:\\cargo-build-stepboy';
const BUILD_DIR = process.env.CARGO_TARGET_DIR
  || (fs.existsSync(path.join(LEGACY_BUILD_DIR, 'release'))
    ? LEGACY_BUILD_DIR
    : path.join(os.tmpdir(), 'stepboy-cargo-target'));

const log = (m) => console.log(`[pack] ${m}`);
const die = (m) => {
  console.error(`[pack] 失败：${m}`);
  process.exit(1);
};

function freeBytes(dir) {
  try {
    const st = fs.statfsSync(dir);
    return Number(st.bavail) * Number(st.bsize);
  } catch {
    return null;
  }
}

const mb = (n) => (n / 1048576).toFixed(1);

// ---------- 1. 前置检查 ----------
log('检查工具链…');
if (!MSVC_BIN) die('找不到 link.exe。安装 VS Build Tools，或用 MSVC_BIN 指向 VC/Tools/MSVC/<版本>/bin/Hostx64/x64。');
if (!SDK_BIN) die('找不到 rc.exe。安装 Windows SDK Build Tools，或用 SDK_BIN 指向 Windows Kits/10/bin/<版本>/x64。');
if (!fs.existsSync(path.join(CARGO_BIN, 'cargo.exe'))) die(`找不到 cargo.exe（期望 ${CARGO_BIN}），可用 CARGO_BIN 覆盖。`);

// ---------- 2. 构建 ----------
log(`构建 release（target: ${BUILD_DIR}）…`);

/**
 * 递归取目录下最新的 mtime，用来判断资源是否比 exe 新。
 * @param {string} dir
 * @returns {number}
 */
function newestMtime(dir) {
  let max = 0;
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      const p = path.join(cur, e.name);
      if (e.isDirectory()) stack.push(p);
      else {
        try {
          const m = fs.statSync(p).mtimeMs;
          if (m > max) max = m;
        } catch {
          /* 文件刚被移除，忽略 */
        }
      }
    }
  }
  return max;
}

// cargo 不会因为 web/ 或 icons/ 里的文件变动就重编 crate：
//   - 前端资源在 src/lib.rs 的 generate_context! 宏展开时嵌入 → 要 touch lib.rs
//   - exe 图标由 build.rs 里的 tauri-winres 编译进资源段 → 要 touch build.rs
// 不 touch 的话 cargo 一路显示 Finished，包里却还是旧资源，非常难查。
const builtExe = path.join(BUILD_DIR, 'release', 'stepboy-pet.exe');
const exeMtime = fs.existsSync(builtExe) ? fs.statSync(builtExe).mtimeMs : 0;
const webMtime = newestMtime(path.join(ROOT, 'web'));
const icoMtime = (() => {
  try {
    return fs.statSync(path.join(TAURI_DIR, 'icons', 'icon.ico')).mtimeMs;
  } catch {
    return 0;
  }
})();

if (webMtime > exeMtime) {
  const target = path.join(TAURI_DIR, 'src', 'lib.rs');
  fs.utimesSync(target, new Date(), new Date());
  log('web/ 有改动 → touch src/lib.rs，强制重新嵌入前端资源（会触发较慢的完整重编）');
}
if (icoMtime > exeMtime) {
  const target = path.join(TAURI_DIR, 'build.rs');
  fs.utimesSync(target, new Date(), new Date());
  log('icons/icon.ico 有改动 → touch build.rs，强制重新编译图标资源');
}

const env = {
  ...process.env,
  PATH: [SDK_BIN, MSVC_BIN, CARGO_BIN, process.env.PATH].filter(Boolean).join(path.delimiter),
  CARGO_TARGET_DIR: BUILD_DIR,
};
// --config 是 cargo 的全局参数，必须放在子命令之前；传绝对路径，因为 cwd 是 src-tauri。
// 不开 shell：Windows 下 cmd.exe 会把带空格的 --config 路径拆开，反而引入新坑。
const cargoArgs = [];
if (fs.existsSync(LOCAL_CONFIG)) {
  cargoArgs.push('--config', LOCAL_CONFIG);
  log(`使用本机工具链配置：${LOCAL_CONFIG}`);
}
cargoArgs.push('build', '--release', '--features', 'custom-protocol', '--offline');
const build = spawnSync('cargo', cargoArgs, {
  cwd: TAURI_DIR,
  env,
  stdio: 'inherit',
});
if (build.status !== 0) die('cargo build 失败，看上面的错误。');

if (!fs.existsSync(builtExe)) die(`构建完成但找不到产物：${builtExe}`);
const sizeBytes = fs.statSync(builtExe).size;
log(`构建成功：${mb(sizeBytes)} MB`);

// ---------- 3. 输出 ----------
fs.mkdirSync(DIST, { recursive: true });
const distExe = path.join(DIST, EXE_NAME);
const distFree = freeBytes(DIST);

// 先清掉上一次可能留下的启动器与说明，避免出现「exe 在别处」的过期信息
for (const n of ['启动桌宠.bat', 'README.txt']) {
  const f = path.join(DIST, n);
  if (fs.existsSync(f)) fs.rmSync(f, { force: true });
}

if (distFree !== null && distFree < sizeBytes + 2 * 1048576) {
  // 目标盘放不下：exe 留在构建目录，dist 里放启动器
  log(`目标盘可用 ${mb(distFree)} MB，不够放 ${mb(sizeBytes)} MB 的 exe，改为生成启动器。`);
  const fallback = path.join(process.env.LOCALAPPDATA || process.env.USERPROFILE, 'Stepboy');
  fs.mkdirSync(fallback, { recursive: true });
  const fallbackExe = path.join(fallback, EXE_NAME);
  fs.copyFileSync(builtExe, fallbackExe);

  fs.writeFileSync(
    path.join(DIST, '启动桌宠.bat'),
    `@echo off\r\nstart "" "${fallbackExe}"\r\n`,
    'utf8'
  );
  fs.writeFileSync(
    path.join(DIST, 'README.txt'),
    [
      '桌宠 exe 因为当前盘空间不足，实际存放在：',
      `  ${fallbackExe}`,
      '',
      '双击本目录的「启动桌宠.bat」即可运行。',
      '清理出足够空间后重新执行 npm run pack，exe 会直接落到本目录。',
    ].join('\r\n'),
    'utf8'
  );
  log(`启动器已生成：${path.join(DIST, '启动桌宠.bat')}`);
  log(`实际 exe：${fallbackExe}`);
  log('完成。双击 dist 目录里的「启动桌宠.bat」即可运行。');
} else {
  fs.copyFileSync(builtExe, distExe);
  const leftover = freeBytes(DIST);
  log(`已输出：${distExe}（${mb(sizeBytes)} MB）`);
  if (leftover !== null) log(`目标盘剩余可用：${mb(leftover)} MB`);
  fs.writeFileSync(
    path.join(DIST, 'README.txt'),
    [
      '双击 Stepboy.exe 即可运行，无需安装、无需 dev-server。',
      '',
      `版本：${new Date().toISOString().slice(0, 10)} 构建`,
      `体积：${mb(sizeBytes)} MB`,
      '',
      '说明：',
      '  - 前端资源已内嵌进 exe，WebView2 由系统提供（Win10 1803+ / Win11 自带）。',
      '  - 首次运行若提示缺少 WebView2，装一次 Microsoft Edge WebView2 Runtime 即可。',
      '  - 右键托盘图标可退出；快捷键见项目 README。',
    ].join('\r\n'),
    'utf8'
  );
  log('完成。双击 dist 目录里的 Stepboy.exe 即可运行。');
}
