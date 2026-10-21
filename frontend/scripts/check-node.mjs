/**
 * Node 版本预检。
 * Vite 5.x 要求 Node ^18.0.0 || >=20.0.0；在 Node 16 上会因为
 * globalThis.crypto.getRandomValues 不存在而抛出：
 *   TypeError: crypto$2.getRandomValues is not a function
 */
const MIN_MAJOR = 18;
const version = process.versions.node;
const major = Number(version.split('.')[0]);

if (major < MIN_MAJOR) {
  console.error('');
  console.error(`[环境检查] 当前 Node.js 版本为 v${version}，Vite 5 需要 Node 18 或更高版本。`);
  console.error('           这就是 "crypto$2.getRandomValues is not a function" 的根因。');
  console.error('           请切换到 Node 18/20/22 后重试（例如 nvm use 20）。');
  console.error(`           当前可执行文件：${process.execPath}`);
  console.error('');
  process.exit(1);
}

if (typeof globalThis.crypto?.getRandomValues !== 'function') {
  console.error('');
  console.error(`[环境检查] 在 Node v${version} 上 globalThis.crypto.getRandomValues 不可用。`);
  console.error('           请使用 Node 18+ 官方发行版，或检查是否加了 --no-experimental-global-webcrypto 之类的启动参数。');
  console.error('');
  process.exit(1);
}
