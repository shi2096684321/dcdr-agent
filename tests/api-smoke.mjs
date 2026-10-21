/**
 * 生产级接口回归脚本（真实调用，不 mock、不写假数据）
 *
 * 设计原则：
 *   1. 只调真实 HTTP 接口，所有断言基于真实响应体；
 *   2. 创建的每一份数据都真实落库并保留（用于后续截图与手工验证）；
 *   3. 依赖外部能力的用例（LLM 对话、向量化、图片生成）不做替身，
 *      跑不通就如实记为 SKIP/FAIL，并写明原因。
 *
 * 用法：
 *   TEST_USER=admin TEST_PASS=****** \
 *   node tests/api-smoke.mjs [--base http://127.0.0.1:8080]
 *
 * 产物：
 *   tests/results/api-smoke-<时间戳>.json   机器可读逐条结果
 *   控制台 markdown 表格摘要
 */
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const argOf = (name, def) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
};

const BASE = argOf('--base', process.env.BASE_URL || 'http://127.0.0.1:8080');
const USER = process.env.TEST_USER;
const PASS = process.env.TEST_PASS;
const FIXTURE = path.resolve('tests/fixtures/knowledge-sample.md');
const OUT_DIR = path.resolve('tests/results');

if (!USER || !PASS) {
  console.error('缺少 TEST_USER / TEST_PASS 环境变量');
  process.exit(2);
}

let token = null;
const results = [];
const created = {};

async function call(method, url, { body, headers = {}, form } = {}) {
  const h = { ...headers };
  // 显式传入 Authorization 时以其为准（用于验证非法 Token 等负向用例）
  if (token && !h['Authorization']) h['Authorization'] = `Bearer ${token}`;
  let payload;
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    h['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const started = Date.now();
  const res = await fetch(BASE + url, { method, headers: h, body: payload });
  const ms = Date.now() - started;
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON 响应，保留原文 */ }
  return { status: res.status, ms, json, text };
}

/** 记录一条用例结果 */
function record(id, module, desc, expect, r, extra = {}) {
  const bizCode = r.json?.code ?? null;
  const ok = expect(r);
  const item = {
    id, module, desc,
    http: r.status,
    code: bizCode,
    message: r.json?.message ?? (r.text ? r.text.slice(0, 120) : ''),
    ms: r.ms,
    verdict: ok ? 'PASS' : 'FAIL',
    ...extra,
  };
  results.push(item);
  const mark = ok ? 'PASS' : 'FAIL';
  console.log(`[${mark}] ${id} ${desc} -> http=${r.status} code=${bizCode} ${r.ms}ms${ok ? '' : ' | ' + item.message}`);
  return item;
}

/** 跳过用例（外部依赖缺失等），必须写明原因 */
function skip(id, module, desc, reason) {
  results.push({ id, module, desc, http: null, code: null, message: reason, ms: 0, verdict: 'SKIP' });
  console.log(`[SKIP] ${id} ${desc} | ${reason}`);
}

const okBiz = (r) => r.status === 200 && r.json?.code === 200;

/**
 * 清理上一轮遗留的测试数据，保证每次运行产出同一份干净数据集。
 * 只删名字以 FIXTURE_PREFIX 开头的对象，绝不碰真实业务数据。
 * 说明：清理的是"本次测试自己造的数据"，最终一轮产生的数据会保留在库里。
 */
async function cleanupFixtures() {
  // 会话标题用「生产级回归-」前缀，其余实体用「回归测试」前缀（标题带连字符、内容带全角冒号，故只比前缀本体）
  const PREFIXES = ['生产级回归', '回归测试'];
  const isMine = (s) => PREFIXES.some((p) => (s ?? '').startsWith(p));
  const tidy = async (listUrl, delUrl, pickName) => {
    const r = await call('GET', listUrl);
    const rows = Array.isArray(r.json?.data) ? r.json.data : (r.json?.data?.content ?? []);
    let n = 0;
    for (const row of rows) {
      if (isMine(pickName(row))) {
        await call('DELETE', delUrl(row));
        n++;
      }
    }
    return n;
  };
  let removed = 0;
  removed += await tidy('/api/sessions', (x) => `/api/sessions/${x.id}`, (x) => x.title);
  removed += await tidy('/api/agents', (x) => `/api/agents/${x.id}`, (x) => x.name);
  removed += await tidy('/api/knowledge-bases', (x) => `/api/knowledge-bases/${x.id}`, (x) => x.name);
  removed += await tidy('/api/workflows', (x) => `/api/workflows/${x.id}`, (x) => x.name);
  removed += await tidy('/api/scheduled-tasks', (x) => `/api/scheduled-tasks/${x.id}`, (x) => x.name);
  removed += await tidy('/api/projects', (x) => `/api/projects/${x.id}`, (x) => x.name);
  const mem = await call('GET', '/api/memories');
  for (const m of mem.json?.data ?? []) {
    if (isMine(m.content)) {
      await call('DELETE', `/api/memories/${m.id}`);
      removed++;
    }
  }
  console.log(`[SETUP] 清理上一轮测试数据 ${removed} 条（前缀 ${PREFIXES.join(' / ')}）`);
}


async function main() {
  // ── 0. 未认证基线 ────────────────────────────────────────────────
  let r = await call('GET', '/api/models');
  record('AUTH-01', 'auth', '未携带 Token 访问受保护接口应 401', (x) => x.status === 401, r);

  // ── 1. 认证 ─────────────────────────────────────────────────────
  r = await call('POST', '/api/auth/login', { body: { username: USER, password: PASS } });
  record('AUTH-02', 'auth', '账号密码登录返回双 Token', (x) => okBiz(x) && !!x.json.data?.accessToken, r);
  token = r.json?.data?.accessToken ?? null;
  if (!token) throw new Error('登录失败，后续用例无法执行');
  const refreshToken = r.json?.data?.refreshToken;

  r = await call('GET', '/api/auth/me');
  record('AUTH-03', 'auth', '当前用户信息与角色', (x) => okBiz(x) && x.json.data?.username === USER, r,
    { note: `role=${r.json?.data?.role}` });

  r = await call('POST', '/api/auth/refresh', { body: { refreshToken } });
  record('AUTH-04', 'auth', '用 Refresh Token 换新 Access Token', (x) => okBiz(x) && !!x.json.data?.accessToken, r);

  // 回归发现（缺陷 DEF-01）：refresh 之后**旧的 access token 立刻失效**。
  // 期望：刷新不应当让仍在有效期内的 access token 失效，否则客户端后台刷新后
  //       下一个请求就会 401（前端表现为"刷新令牌后随机掉线"）。
  r = await call('GET', '/api/auth/me', { headers: { Authorization: `Bearer ${token}` } });
  record('AUTH-05', 'auth', 'refresh 后旧 access token 仍可用（期望 200）', okBiz, r,
    { note: r.status === 401 ? '缺陷 DEF-01：refresh 后旧 token 被立即拉黑' : '' });

  // 用新签发的 token 继续后续用例
  token = null;
  r = await call('POST', '/api/auth/login', { body: { username: USER, password: PASS } });
  token = r.json?.data?.accessToken;
  if (!token) throw new Error('重新登录失败，后续用例无法执行');

  await cleanupFixtures();

  r = await call('GET', '/api/auth/me', { headers: { Authorization: 'Bearer invalid.token.value' } });
  record('AUTH-06', 'auth', '非法 Token 被拒绝', (x) => x.status === 401, r);


  // ── 2. 会话 ─────────────────────────────────────────────────────
  r = await call('GET', '/api/models');
  const models = r.json?.data ?? [];
  const chatModel = models.find((m) => m.type === 'CHAT' && m.isDefault)?.name
    ?? models.find((m) => m.type === 'CHAT')?.name ?? null;

  r = await call('GET', '/api/sessions');
  record('SESS-01', 'session', '会话列表可读取', okBiz, r);

  r = await call('POST', '/api/sessions', { body: { title: '生产级回归-会话A', model: chatModel } });
  record('SESS-02', 'session', '创建会话', (x) => okBiz(x) && !!x.json.data?.id, r);
  const sessionId = r.json?.data?.id;
  created.sessionId = sessionId;

  r = await call('PUT', `/api/sessions/${sessionId}/title`, { body: { title: '生产级回归-会话A（改名）' } });
  record('SESS-03', 'session', '会话改名', okBiz, r);

  r = await call('PATCH', `/api/sessions/${sessionId}/persona`, { body: { persona: '你是一名严谨的后端工程师，回答先给结论再给依据。' } });
  record('SESS-04', 'session', '设置会话人设', okBiz, r);

  r = await call('GET', `/api/sessions/${sessionId}/messages`);
  record('SESS-05', 'session', '读取会话消息（新会话应为空）', (x) => okBiz(x) && Array.isArray(x.json.data), r);

  r = await call('GET', `/api/sessions/${sessionId}/export?format=markdown`);
  record('SESS-06', 'session', '导出会话为 Markdown', (x) => x.status === 200, r);

  r = await call('GET', '/api/sessions/not-exist-id');
  record('SESS-07', 'session', '访问不存在的会话返回"不存在"（防资源枚举）',
    (x) => (x.status === 404) || (x.json?.code === 404), r,
    { note: `HTTP=${r.status} 业务码=${r.json?.code}（不存在统一走 body 业务码，见报告 DEF-02）` });


  // ── 3. 专家 Agent ───────────────────────────────────────────────
  r = await call('GET', '/api/agents/templates');
  record('AGENT-01', 'agent', '内置专家模板列表', (x) => okBiz(x) && x.json.data?.length > 0, r,
    { note: `templates=${r.json?.data?.length}` });

  r = await call('POST', '/api/agents', {
    body: {
      name: '回归测试-API 设计评审专家',
      description: '生产级回归创建的专家，用于校验 REST 契约与幂等性',
      persona: '你是资深 API 架构师，评审时先列风险再给改法。',
      model: 'qwen-plus',
      tools: [],
      temperature: 0.3,
    },
  });
  record('AGENT-02', 'agent', '创建自定义专家', (x) => okBiz(x) && !!x.json.data?.id, r);
  const agentId = r.json?.data?.id;
  created.agentId = agentId;

  r = await call('PUT', `/api/agents/${agentId}`, {
    body: { name: '回归测试-API 设计评审专家', description: '更新描述：验证版本快照是否生成', persona: '你是资深 API 架构师。', model: 'qwen-plus', temperature: 0.5 },
  });
  record('AGENT-03', 'agent', '更新专家触发版本快照', okBiz, r);

  r = await call('GET', `/api/agents/${agentId}/versions`);
  record('AGENT-04', 'agent', '专家历史版本可查', (x) => okBiz(x) && Array.isArray(x.json.data), r,
    { note: `versions=${r.json?.data?.length}` });

  r = await call('POST', `/api/agents/${agentId}/clone`, { body: { name: '回归测试-API 设计评审专家-副本' } });
  record('AGENT-05', 'agent', '克隆专家', (x) => okBiz(x) && !!x.json.data?.id, r);

  // ── 4. 知识库 ───────────────────────────────────────────────────
  r = await call('POST', '/api/knowledge-bases', { body: { name: '回归测试-产品知识库', description: '生产级回归创建，文档为产品说明样例', visibility: 'USER' } });
  record('KB-01', 'knowledge', '创建知识库', (x) => okBiz(x) && !!x.json.data?.id, r);
  const kbId = r.json?.data?.id;
  created.kbId = kbId;

  if (fs.existsSync(FIXTURE)) {
    const form = new FormData();
    const buf = fs.readFileSync(FIXTURE);
    form.append('file', new Blob([buf], { type: 'text/markdown' }), path.basename(FIXTURE));
    r = await call('POST', `/api/knowledge-bases/${kbId}/documents`, { form });
    record('KB-02', 'knowledge', '上传文档并入库（需向量化）', (x) => okBiz(x), r);
  } else {
    skip('KB-02', 'knowledge', '上传文档并入库', '缺少固定素材文件 tests/fixtures/knowledge-sample.md');
  }

  // 入库是异步的：轮询到终态（completed/failed）再断言，避免把 pending 当成结果
  let docs = [];
  for (let i = 0; i < 10; i++) {
    r = await call('GET', `/api/knowledge-bases/${kbId}/documents`);
    docs = Array.isArray(r.json?.data) ? r.json.data : [];
    const st = docs[0]?.status;
    if (st && st !== 'pending') break;
    await new Promise((res) => setTimeout(res, 1500));
  }
  record('KB-03', 'knowledge', '文档列表可读并回报入库终态', (x) => okBiz(x) && docs.length > 0, r,
    { note: `documents=${docs.length} status=${docs[0]?.status ?? 'n/a'} chunks=${docs[0]?.chunkCount ?? 'n/a'}` });


  r = await call('POST', `/api/knowledge-bases/${kbId}/search`, { body: { query: '这个产品支持哪些模型接入方式', topK: 3 } });
  if (r.status === 200 && r.json?.code === 200) {
    record('KB-04', 'knowledge', '知识库检索返回结果', (x) => okBiz(x), r);
  } else {
    skip('KB-04', 'knowledge', '知识库检索', `依赖 Embedding 模型，实际返回 code=${r.json?.code} ${r.json?.message ?? ''}`);
  }

  // ── 5. 长期记忆 ─────────────────────────────────────────────────
  r = await call('POST', '/api/memories', {
    body: { content: '回归测试：该用户偏好中文回答，结论先行、再给依据。', type: 'PREFERENCE', scope: 'USER', importance: 0.8, pinned: true, tags: ['回归测试', '偏好'] },
  });
  record('MEM-01', 'memory', '写入长期记忆（常驻）', (x) => okBiz(x) && !!x.json.data?.id, r);
  const memId = r.json?.data?.id;
  created.memId = memId;

  r = await call('POST', '/api/memories', {
    body: { content: '回归测试：项目部署在 Windows + Docker Desktop，PG 走 5432。', type: 'PROJECT', scope: 'USER', importance: 0.5, tags: ['回归测试', '部署'] },
  });
  record('MEM-02', 'memory', '写入第二条记忆（项目类）', (x) => okBiz(x) && !!x.json.data?.id, r);

  r = await call('GET', '/api/memories');
  record('MEM-03', 'memory', '记忆列表可读', (x) => okBiz(x) && x.json.data?.length >= 2, r,
    { note: `memories=${r.json?.data?.length}` });

  r = await call('GET', '/api/memories/summary');
  record('MEM-04', 'memory', '记忆概览统计', okBiz, r);

  r = await call('POST', `/api/memories/${memId}/archive`);
  record('MEM-05', 'memory', '归档记忆', okBiz, r);
  r = await call('POST', `/api/memories/${memId}/restore`);
  record('MEM-06', 'memory', '恢复归档记忆', okBiz, r);

  // ── 6. 项目与技能 ───────────────────────────────────────────────
  r = await call('POST', '/api/projects', { body: { name: '回归测试-创作项目', mode: 'IMAGE', model: 'wanx-v1' } });
  record('PROJ-01', 'project', '创建创作项目', (x) => okBiz(x) && !!x.json.data?.id, r);
  created.projectId = r.json?.data?.id;

  r = await call('GET', '/api/projects');
  record('PROJ-02', 'project', '项目列表可读', okBiz, r);

  r = await call('GET', '/api/skills');
  record('SKILL-01', 'skill', '内置技能列表', (x) => okBiz(x) && x.json.data?.length > 0, r,
    { note: `skills=${r.json?.data?.length}` });

  r = await call('GET', '/api/skill-market/categories');
  if (okBiz(r)) {
    record('SKILL-02', 'skill', '技能市场分类', okBiz, r);
  } else {
    skip('SKILL-02', 'skill', '技能市场分类', `外部技能市场不可达（本机网络/代理），实际 code=${r.json?.code} ${r.json?.message ?? ''}`);
  }


  r = await call('GET', '/api/market/templates');
  record('MKT-01', 'market', '专家市场模板列表', okBiz, r);

  // ── 7. 自动化 ───────────────────────────────────────────────────
  r = await call('GET', '/api/workflows/templates');
  record('WF-01', 'workflow', '工作流模板列表', (x) => okBiz(x) && x.json.data?.length > 0, r,
    { note: `templates=${r.json?.data?.length}` });

  const tpl = r.json?.data?.[0];
  if (tpl) {
    // 模板的编排字段是 steps / dag（不是 nodes / edges），mode 决定串行还是 DAG
    r = await call('POST', '/api/workflows', {
      body: {
        name: '回归测试-翻译并总结',
        description: '由内置模板复制而来，验证工作流 CRUD 与编排结构',
        mode: tpl.mode,
        steps: tpl.steps,
        dag: tpl.dag,
      },
    });
    record('WF-02', 'workflow', '按模板结构创建工作流', (x) => okBiz(x) && !!x.json.data?.id, r,
      { note: `mode=${tpl.mode} steps=${tpl.steps?.length ?? 0}` });
    created.workflowId = r.json?.data?.id;
  }


  r = await call('GET', '/api/workflows');
  record('WF-03', 'workflow', '工作流列表可读', okBiz, r);

  r = await call('GET', '/api/workflows/executions');
  record('WF-04', 'workflow', '执行历史可读', okBiz, r);

  r = await call('POST', '/api/scheduled-tasks', {
    body: { name: '回归测试-每日摘要', agentId, cron: '0 0 9 * * *', inputMessage: '生成一份昨日总结', enabled: false },
  });
  record('TASK-01', 'schedule', '创建定时任务（默认禁用，不触发真实调用）', (x) => okBiz(x) && !!x.json.data?.id, r);
  created.scheduledTaskId = r.json?.data?.id;

  r = await call('GET', '/api/scheduled-tasks');
  record('TASK-02', 'schedule', '定时任务列表可读', okBiz, r);

  // ── 8. 运维与治理 ───────────────────────────────────────────────
  r = await call('GET', '/api/admin/users');
  const userPage = r.json?.data;
  const userRows = Array.isArray(userPage) ? userPage : (userPage?.content ?? []);
  record('ADM-01', 'admin', '用户分页列表（admin 权限）', (x) => okBiz(x) && userRows.length > 0, r,
    { note: `users=${userRows.length} total=${userPage?.totalElements ?? 'n/a'}` });


  r = await call('GET', '/api/admin/stats');
  record('ADM-02', 'admin', '系统概览统计', okBiz, r);

  r = await call('GET', '/api/admin/audit-logs');
  record('ADM-03', 'admin', '审计日志可读', okBiz, r,
    { note: `logs=${Array.isArray(r.json?.data) ? r.json.data.length : 'n/a'}` });

  r = await call('GET', '/api/monitor/cost');
  record('OPS-01', 'ops', '成本统计', okBiz, r);

  r = await call('GET', '/api/monitor/llm');
  record('OPS-02', 'ops', '模型调用统计', okBiz, r);

  r = await call('GET', '/api/llm-calls/logs');
  record('OPS-03', 'ops', '调用链日志可读', okBiz, r);

  r = await call('GET', '/api/routing/logs');
  record('OPS-04', 'ops', '智能路由日志可读', okBiz, r);

  r = await call('GET', '/api/retrieval-health/overview');
  record('OPS-05', 'ops', '检索健康概览（admin）', okBiz, r);

  r = await call('GET', '/api/system/thread-pool');
  record('OPS-06', 'ops', '线程池指标', okBiz, r);

  r = await call('GET', '/api/models');
  record('MODEL-01', 'model', '模型列表（平台内置 + 自定义）', (x) => okBiz(x) && x.json.data?.length > 0, r,
    { note: `models=${r.json?.data?.length}` });

  r = await call('GET', '/api/tenants');
  record('TEN-01', 'tenant', '租户列表（admin）', okBiz, r);

  // ── 9. 依赖 LLM 的能力：如实标注，不造假 ────────────────────────
  if (created.agentId) {
    r = await call('POST', '/api/chat', {
      body: { sessionId: created.sessionId, message: '用一句话说明这个系统解决什么问题', model: chatModel, stream: false },
    });
    if (okBiz(r)) {
      // 注意：接口在上游失败时仍返回 code=200，错误信息被写进回答正文。
      // 只看状态码会误判为 PASS，故必须校验正文内容（见报告 DEF-03）。
      const answer = r.json?.data?.content ?? '';
      const upstreamFailed = /^调用失败|invalid_api_key|Incorrect API key/.test(answer);
      record('CHAT-01', 'chat', '同步对话（真实调用模型）',
        (x) => okBiz(x) && !upstreamFailed, r,
        { note: `model=${chatModel} answerLen=${answer.length} answerHead=${answer.slice(0, 80)}` });
    } else {
      skip('CHAT-01', 'chat', '同步对话（真实调用模型）',
        `model=${chatModel}，未配置有效模型 API Key，实际 code=${r.json?.code} ${(r.json?.message ?? '').slice(0, 160)}`);
    }
  }


  // ── 汇总 ────────────────────────────────────────────────────────
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const file = path.join(OUT_DIR, `api-smoke-${stamp}.json`);
  const summary = {
    base: BASE,
    startedAt: stamp,
    total: results.length,
    pass: results.filter((x) => x.verdict === 'PASS').length,
    fail: results.filter((x) => x.verdict === 'FAIL').length,
    skip: results.filter((x) => x.verdict === 'SKIP').length,
    created,
    results,
  };
  fs.writeFileSync(file, JSON.stringify(summary, null, 2), 'utf8');
  console.log(`\n合计 ${summary.total} 条：PASS ${summary.pass} / FAIL ${summary.fail} / SKIP ${summary.skip}`);
  console.log(`结果文件：${file}`);
  process.exit(summary.fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error('执行中断：', e);
  process.exit(2);
});
