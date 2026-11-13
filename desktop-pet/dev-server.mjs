import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, 'web');
// 端口默认 5180，刻意避开 Vite 的 5173。
// 本项目放在 dcdr-agent 仓库里，那边的 frontend/ 也用 Vite、默认同样抢 5173；
// 两边同时起会撞车，且 Windows 上 localhost 优先解析成 IPv6，
// 浏览器打开 localhost:5173 命中的是那个前端项目，不是桌宠。
const PORT = Number(process.env.PORT || 5180);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon'
};

/** @param {http.IncomingMessage} req @param {http.ServerResponse} res */
function handle(req, res) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  } catch {
    res.writeHead(400).end('Bad request');
    return;
  }
  if (pathname === '/' || pathname.endsWith('/')) pathname += 'index.html';

  const target = path.resolve(ROOT, '.' + pathname);
  if (target !== ROOT && !target.startsWith(ROOT + path.sep)) {
    res.writeHead(403).end('Forbidden');
    return;
  }

  fs.readFile(target, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Not found: ' + pathname);
      return;
    }
    const headers = { 'Content-Type': MIME[path.extname(target).toLowerCase()] || 'application/octet-stream' };
    // 开发期一律禁缓存：皮肤包轮询热重载（§6.8）+ 改 JS/CSS 后刷新即生效
    if (pathname.startsWith('/assets/skins/') ||
        pathname.startsWith('/src/') ||
        pathname === '/index.html') {
      headers['Cache-Control'] = 'no-store';
    }
    res.writeHead(200, headers).end(data);
  });
}

// 同时绑定 IPv4 与 IPv6 回环：浏览器输 localhost 时 Windows 优先走 ::1，
// 只监听 127.0.0.1 会出现「地址没错但打不开」。两个都是回环地址，不暴露到局域网。
/** 启动后自动拉起默认浏览器（--open / -o）。 */
function openBrowser(url) {
  const [cmd, args] =
    process.platform === 'win32'
      ? ['cmd', ['/c', 'start', '', url]]   // start 的第一个引号参数是窗口标题，不能省
      : process.platform === 'darwin'
        ? ['open', [url]]
        : ['xdg-open', [url]];
  try {
    // 不 detach：浏览器进程很快返回，交给父进程管理，避免留下孤儿进程
    spawn(cmd, args, { stdio: 'ignore', windowsHide: true });
  } catch (err) {
    console.warn('[stepboy] 自动打开浏览器失败，请手动访问：' + url);
  }
}

const SHOULD_OPEN = process.argv.includes('--open') || process.argv.includes('-o');
let opened = false;

for (const { host, label } of [
  { host: '127.0.0.1', label: `http://127.0.0.1:${PORT}` },
  { host: '::1', label: `http://[::1]:${PORT}` }
]) {
  const server = http.createServer(handle);
  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.error(`[stepboy] 端口 ${PORT} 已被占用。换端口：PORT=5181 npm run dev`);
      process.exit(1);
    }
    // 个别环境没有 IPv6 回环，忽略即可，IPv4 那条仍可用
  });
  server.listen(PORT, host, () => {
    console.log(`[stepboy] web preview: ${label}  root=${ROOT}`);
    // 只在第一次监听成功时开一次，否则两个地址会各开一个标签页
    if (SHOULD_OPEN && !opened) {
      opened = true;
      openBrowser(`http://127.0.0.1:${PORT}`);
    }
  });
}

process.on('SIGINT', () => process.exit(0));
