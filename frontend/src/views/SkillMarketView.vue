<template>
  <div class="mkt-view">
    <div class="page-header">
      <div class="ph-left">
        <h2>技能市场</h2>
        <p class="sub">
          来自 SkillHub 的公开技能库：可搜索、查看第三方安全扫描结论，并把技能连同它的
          <b>来源页</b>一起引入到本地 —— 正文从来源页复制即可。
        </p>
      </div>
      <div class="ph-actions">
        <el-button @click="$emit('go-manage')">我的技能</el-button>
      </div>
    </div>

    <!-- 未启用 / 上游异常 -->
    <template v-if="!statusLoading && !enabled">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="技能市场未启用"
        description="这是一个外部依赖，默认关闭。启用后需要能访问 skillhub 服务（部署方自行决定是否允许出网）。"
      />
      <div class="cfg-card">
        <div class="cfg-title">启用方式</div>
        <pre class="cfg-code">agent:
  skill:
    hub:
      enabled: true          # 默认 false
      base-url: https://lightmake.site
      timeout-seconds: 10    # 出站超时
      cache-ttl-seconds: 300 # 分类/详情缓存</pre>
        <div class="hint">
          关闭状态下不会发起任何外部请求（客户端也是惰性构建的）。开启后回到本页即可浏览。
        </div>
      </div>
    </template>

    <template v-else-if="enabled">
      <!-- 预期校准: 用户最贵的成本是"引入 → 补正文 → 启用 → 发现跑不通"这条白走的路 -->
      <el-alert type="info" :closable="false" show-icon class="usage-note">
        <template #title>引入前请先看这三条</template>
        <ul class="un-list">
          <li>
            <b>正文要手动搬一次</b>：市场接口不提供技能正文，但每个技能都带一个
            <b>「来源页」</b>，打开它就能看到完整正文 —— 复制过来粘进正文框，保存并启用即可
            （引入时是停用状态）。
          </li>
          <li>
            <b>本项目技能不携带脚本</b>：能力依赖脚本执行的技能（数据采集、浏览器自动化等）
            即使补全了正文也无法直接运行 —— 那类能力在本项目里靠<b>工具</b>提供，而不是靠技能。
            建议优先引入 <b>流程 / 规范 / 模板 / 清单</b> 类的知识型技能。
          </li>
        </ul>
      </el-alert>

      <div class="toolbar">
        <el-select v-model="category" placeholder="全部分类" clearable style="width:180px" @change="onFilterChange">
          <el-option v-for="c in categories" :key="c.key" :label="c.name || c.key" :value="c.key" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索技能（名称 / 简介 / 标签）"
          clearable
          style="width:300px"
          @keyup.enter="onFilterChange"
          @clear="onFilterChange"
        />
        <el-button type="primary" @click="onFilterChange">搜索</el-button>
        <el-select v-model="sort" style="width:138px" @change="onFilterChange">
          <el-option v-for="s in sorts" :key="s.key" :label="s.label" :value="s.key" />
        </el-select>
        <span class="count">
          <template v-if="searchMode">按相关度排序 · {{ items.length }} 条</template>
          <template v-else-if="recommended">推荐池 {{ total }} 个候选</template>
          <template v-else>共 {{ total }} 个技能</template>
        </span>
      </div>

      <!-- 搜索结果由上游按相关度返回, 且不接受排序参数(选了也会被忽略) —— 与其静默无效, 不如说清怎么切换 -->
      <div v-if="searchMode" class="sort-hint">
        关键词结果由上游按<b>相关度</b>返回，不分页；选择「下载最多 / 收藏最多 / 最近更新」等具体排序，
        即可切换为按该条件排序的分页列表。
      </div>
      <!-- 候选池语义必须讲清: 否则「推荐池 100 个候选」会被误读成「市场只有 100 个技能」 -->
      <div v-else-if="recommended" class="sort-hint">
        「推荐」按<b>综合评分</b>排列：以热度（下载 / 收藏 / 安装）为主，兼顾最近更新（90 天半衰），
        因此不会一直把几年前的老技能顶在前面。候选取自「下载最多」与「最近更新」两路的前 50 名，
        所以上面的数字是<b>候选池规模</b>而非全库总数；要看完整结果请切换为其它排序。
      </div>

      <div v-loading="loading" class="grid">
        <div v-for="s in items" :key="s.canonicalName || s.slug" class="card">
          <div class="card-head">
            <!-- 外链图标可能失效(上游 CDN 轮换/防盗链): 失败时回落到首字母, 而不是留一个破图/空白 -->
            <img v-if="s.iconUrl && !s.iconBroken" :src="s.iconUrl" class="icon" alt="" @error="s.iconBroken = true" />
            <div v-else class="icon icon-ph">{{ (s.name || s.slug || '?').charAt(0).toUpperCase() }}</div>
            <div class="head-main">
              <div class="title-row">
                <span class="title" :title="s.name">{{ s.name || s.slug }}</span>
                <el-tag v-if="s.installed" size="small" type="success" effect="dark">已引入</el-tag>
              </div>
              <div class="sub-row">
                <span class="publisher" :title="s.namespace">
                  {{ s.publisher || s.namespace || '—' }}
                  <span v-if="s.publisherVerified" class="verified">已认证</span>
                </span>
                <el-tag v-if="s.version" size="small" effect="plain">v{{ s.version }}</el-tag>
              </div>
            </div>
          </div>

          <div class="summary">{{ s.summary || '（无简介）' }}</div>

          <div class="tags">
            <el-tag v-if="s.category" size="small" type="primary" effect="plain">{{ categoryName(s.category) }}</el-tag>
            <el-tag v-for="t in (s.subCategories || []).slice(0, 2)" :key="t" size="small" effect="plain">{{ t }}</el-tag>
            <el-tag v-if="s.requiresApiKey" size="small" type="warning" effect="plain">需 API Key</el-tag>
          </div>

          <div class="card-foot">
            <span class="stat">↓ {{ fmtNum(s.downloads) }}</span>
            <span class="stat">★ {{ fmtNum(s.stars) }}</span>
            <span class="stat src">{{ s.source || '—' }}</span>
          </div>

          <div class="card-ops">
            <el-button size="small" @click="openDetail(s)">详情</el-button>
            <el-button size="small" type="primary" @click="openInstall(s)">
              {{ s.installed ? '重新引入' : '引入' }}
            </el-button>
          </div>
          <!-- 来源页直接放卡片上: 引入后要来这里复制正文。
               之前只有详情弹窗里有链接, 用户得先点开详情才知道往哪去 —— 这里补的是那条路 -->
          <a
            v-if="s.sourceUrl"
            :href="s.sourceUrl"
            target="_blank"
            rel="noopener"
            class="card-src"
            title="打开来源页复制技能正文"
          >来源页（正文在这里）↗</a>
        </div>

        <el-empty
          v-if="!loading && !items.length"
          :description="searchMode ? '没有匹配的技能' : '该分类下暂无技能'"
        />
      </div>

      <el-pagination
        v-if="!searchMode && total > pageSize"
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        class="pager"
        @current-change="load"
      />
    </template>

    <!-- ===== 详情 ===== -->
    <el-dialog v-model="detailVisible" :title="detail?.name || '技能详情'" width="780px" top="7vh">
      <div v-if="detail" v-loading="detailLoading">
        <div class="d-top">
          <span class="d-id">{{ detail.canonicalName || detail.slug }}</span>
          <el-tag v-if="detail.version" size="small" effect="plain">v{{ detail.version }}</el-tag>
          <el-tag v-if="detail.category" size="small" type="primary" effect="plain">{{ categoryName(detail.category) }}</el-tag>
          <el-tag v-if="detail.publisherVerified" size="small" type="success">发布者已认证</el-tag>
          <el-tag v-if="detail.installed" size="small" type="success" effect="dark">已引入</el-tag>
        </div>

        <p class="d-summary">{{ detail.summary || '（无简介）' }}</p>

        <div class="d-stats">
          <span>下载 {{ fmtNum(detail.downloads) }}</span>
          <span>安装 {{ fmtNum(detail.installs) }}</span>
          <span>收藏 {{ fmtNum(detail.stars) }}</span>
          <span v-if="detail.publisher">发布者 {{ detail.publisher }}</span>
        </div>

        <!-- 安全扫描: 这是本项目自己造不出来的信号, 单独一块展示 -->
        <div class="d-section">
          <div class="d-title">第三方安全扫描</div>
          <el-alert
            v-if="!detail.securityReports?.length"
            type="warning"
            :closable="false"
            show-icon
            title="暂无扫描结论"
            description="上游可能仍在排队扫描；这不代表安全，请自行确认技能内容。"
          />
          <el-table v-else :data="detail.securityReports" size="small" border>
            <el-table-column prop="platform" label="平台" width="110" />
            <el-table-column label="结论" width="140">
              <template #default="{ row }">
                <el-tag :type="secTagType(row.status)" size="small">{{ row.statusText || row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="报告">
              <template #default="{ row }">
                <a v-if="row.reportUrl" :href="row.reportUrl" target="_blank" rel="noopener">查看报告</a>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="hint">该结论由上游第三方平台给出，仅作引入前的参考，不替代本地判断。</div>
        </div>

        <div class="d-section">
          <div class="d-title">版本历史（{{ detail.versions?.length || 0 }}）</div>
          <el-table :data="detail.versions" size="small" border max-height="220">
            <el-table-column prop="version" label="版本" width="100" />
            <el-table-column label="时间" width="160">
              <template #default="{ row }"><span class="muted">{{ fmtTime(row.createdAt) }}</span></template>
            </el-table-column>
            <el-table-column label="安全结论" width="130">
              <template #default="{ row }">
                <el-tag v-if="row.securityStatus" :type="secTagType(row.securityStatus)" size="small">
                  {{ row.securityStatusText || row.securityStatus }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="changelog" label="变更" show-overflow-tooltip />
          </el-table>
        </div>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="引入后会得到什么"
          description="市场公开接口不发放技能正文，因此引入产出的是「元数据骨架 + 待补全正文」，并以停用状态入库。你需要到来源页复制正文补全后再启用。"
          style="margin-top:12px"
        />
        <div v-if="detail.sourceUrl" class="src-link">
          来源页：
          <a :href="detail.sourceUrl" target="_blank" rel="noopener">{{ detail.sourceUrl }}</a>
        </div>
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" @click="openInstall(detail)">引入到我的技能</el-button>
      </template>
    </el-dialog>

    <!-- ===== 引入确认 ===== -->
    <el-dialog v-model="installVisible" title="引入技能" width="560px">
      <div class="ins-target">{{ installTarget?.name || installTarget?.slug }}</div>
      <p class="ins-desc">
        将把该技能的<b>元数据</b>（名称/简介/版本/作者/分类/标签）引入到你的技能列表。
        由于市场接口不提供正文，引入后正文为待补全骨架，且<b>自动停用</b>。
      </p>
      <el-form label-width="90px">
        <el-form-item label="本地标识">
          <el-input v-model="installName" :placeholder="installTarget?.slug || ''" />
          <div class="hint">留空则用来源标识；不符合本项目命名规则时会自动归一化。</div>
        </el-form-item>
        <el-form-item label="同名覆盖">
          <el-switch v-model="installOverwrite" />
          <span class="hint inline">
            会用骨架正文覆盖你已补全的正文，非必要不要开启
          </span>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="installTarget && secTagType(installTarget.securityStatus) === 'danger'"
        type="error"
        :closable="false"
        show-icon
        title="来源站安全扫描结论异常"
        description="建议先在详情里查看扫描报告，确认内容安全后再引入。"
        style="margin-top:8px"
      />
      <template #footer>
        <el-button @click="installVisible = false">取消</el-button>
        <el-button type="primary" :loading="installing" @click="submitInstall">确认引入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getSkillMarketStatus, getSkillMarketCategories, getSkillMarketSorts, getSkillMarketSkills,
  searchSkillMarket, getSkillMarketDetail, installSkillFromMarket, extractMessage
} from '@/api'

const emit = defineEmits(['go-manage'])

const statusLoading = ref(true)
const enabled = ref(false)

const loading = ref(false)
const items = ref([])
const categories = ref([])
const category = ref('')
const sorts = ref([{ key: 'recommended', label: '推荐' }])
const sort = ref('recommended')
// 后端在推荐排序下返回的 total 是「候选池大小」而非全库总数, 需要换文案说明
const recommended = ref(false)
const keyword = ref('')
const page = ref(1)
const pageSize = 12
const total = ref(0)
const searchMode = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const installVisible = ref(false)
const installing = ref(false)
const installTarget = ref(null)
const installName = ref('')
const installOverwrite = ref(false)

const categoryMap = computed(() => {
  const m = {}
  for (const c of categories.value) m[c.key] = c.name || c.key
  return m
})

function categoryName(key) {
  return categoryMap.value[key] || key
}

function fmtNum(n) {
  const v = Number(n || 0)
  if (v >= 10000) return `${(v / 10000).toFixed(1)}w`
  return String(v)
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

/** 结论 → tag 颜色。未知结论按"需注意"处理，与后端 severity 的口径一致 */
function secTagType(status) {
  const s = (status || '').toLowerCase()
  if (s === 'benign' || s === 'safe' || s === 'pass') return 'success'
  if (!s) return 'info'
  if (s === 'queued' || s === 'pending' || s === 'scanning' || s === 'processing') return 'warning'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    const kw = keyword.value.trim()
    // 两条路径的分工(上游的客观差异, 不是我们挑的):
    //  - 搜索接口: 按相关度返回, 但忽略一切排序参数、也不返回总数 → 只能不分页
    //  - 列表接口: 支持 keyword/category/sortBy/order 且返回 total → 可排序可分页
    // 所以「推荐 + 有关键词」走搜索(拿相关度), 其余一律走列表(拿排序与分页)。
    if (kw && sort.value === 'recommended') {
      items.value = await searchSkillMarket(kw, 24)
      searchMode.value = true
      recommended.value = false
      total.value = items.value.length
    } else {
      const data = await getSkillMarketSkills({
        page: page.value - 1,
        pageSize,
        category: category.value || undefined,
        keyword: kw || undefined,
        sort: sort.value
      })
      items.value = data.items || []
      total.value = data.total || 0
      recommended.value = !!data.recommended
      searchMode.value = false
    }
  } catch (e) {
    items.value = []
    ElMessage.error(extractMessage(e) || '加载技能市场失败')
  } finally {
    loading.value = false
  }
}

async function loadSorts() {
  try {
    const list = await getSkillMarketSorts()
    if (list?.length) sorts.value = list
  } catch (e) {
    // 排序选项拉不到不该阻断浏览: 保留兜底的「综合」即可
    console.warn('[SkillMarket] 加载排序方式失败:', extractMessage(e))
  }
}

function onFilterChange() {
  page.value = 1
  load()
}

async function loadCategories() {
  try {
    categories.value = await getSkillMarketCategories()
  } catch (e) {
    // 分类拉不到不该阻断浏览: 市场主体是技能列表, 分类只是筛选器
    console.warn('[SkillMarket] 加载分类失败:', extractMessage(e))
  }
}

async function openDetail(s) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getSkillMarketDetail(s.slug, s.namespace)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function openInstall(s) {
  if (!s) return
  installTarget.value = s
  installName.value = ''
  installOverwrite.value = false
  installVisible.value = true
}

async function submitInstall() {
  const t = installTarget.value
  if (!t) return
  installing.value = true
  try {
    const r = await installSkillFromMarket({
      slug: t.slug,
      namespace: t.namespace || undefined,
      name: installName.value.trim() || undefined,
      overwrite: installOverwrite.value
    })
    installVisible.value = false
    detailVisible.value = false
    ElMessage.success(`${r.action === 'overwritten' ? '已覆盖' : '已引入'}：${r.name}（已停用，正文待补全）`)

    // 告警必须被读到: 里面含"安全结论""正文待补全""依赖缺失"等决策信息
    const warnings = r.warnings || []
    const body = warnings.length ? warnings.map((w) => `· ${w}`).join('\n') : '已引入到你的技能列表。'
    try {
      await ElMessageBox.confirm(
        `${body}\n\n现在去技能管理补全正文吗？`,
        '引入完成（正文待补全）',
        { confirmButtonText: '去补全', cancelButtonText: '稍后', type: 'success', customClass: 'warn-box' }
      )
      emit('go-manage')
    } catch {
      // 用户选择稍后处理
    }
    load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '引入失败')
  } finally {
    installing.value = false
  }
}

onMounted(async () => {
  try {
    const st = await getSkillMarketStatus()
    enabled.value = !!st?.enabled
  } catch (e) {
    ElMessage.error(extractMessage(e) || '读取技能市场状态失败')
  } finally {
    statusLoading.value = false
  }
  if (enabled.value) {
    loadSorts()
    load()
    loadCategories()
  }
})
</script>

<style scoped>
.mkt-view { flex: 1; overflow-y: auto; padding: 20px 24px; background: var(--dcdr-bg-subtle); }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.ph-left h2 { margin: 0 0 4px; font-size: 15px; font-weight: 600; }
.ph-left .sub { margin: 0; color: var(--dcdr-text-3); font-size: 13px; line-height: 1.6; max-width: 760px; }
.ph-actions { display: flex; gap: 8px; flex-shrink: 0; }

.cfg-card { background: var(--dcdr-surface); border-radius: 8px; padding: 16px 18px; margin-top: 12px; }
.cfg-title { font-weight: 500; margin-bottom: 8px; }
.cfg-code { background: var(--dcdr-bg-subtle); border-radius: 6px; padding: 12px; font-size: 12.5px; line-height: 1.8; margin: 0 0 8px; overflow-x: auto; }
.hint { color: var(--dcdr-text-3); font-size: 12px; line-height: 1.6; }
.hint.inline { margin-left: 10px; }

.card-src { display: block; margin-top: 8px; font-size: 12px; color: var(--dcdr-accent); text-decoration: none; }
.card-src:hover { text-decoration: underline; }

.usage-note { margin-bottom: 14px; }
.un-list { margin: 6px 0 0; padding-left: 18px; line-height: 1.8; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.toolbar .count { color: var(--dcdr-text-3); font-size: 13px; margin-left: auto; }
.sort-hint { color: var(--dcdr-text-3); font-size: 12.5px; line-height: 1.6; margin: 0 0 14px; }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; min-height: 200px; }
.card { background: var(--dcdr-surface); border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; border: 1px solid var(--dcdr-border); transition: box-shadow 0.2s, transform 0.2s; }
.card:hover { box-shadow: 0 4px 16px rgba(0, 0, 0, 0.07); transform: translateY(-1px); }
.card-head { display: flex; gap: 10px; align-items: flex-start; }
.icon { width: 36px; height: 36px; border-radius: 8px; object-fit: cover; flex-shrink: 0; }
.icon-ph { background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; }
.head-main { min-width: 0; flex: 1; }
.title-row { display: flex; align-items: center; gap: 6px; }
.title { font-weight: 600; font-size: 14.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sub-row { display: flex; align-items: center; gap: 6px; margin-top: 3px; }
.publisher { color: var(--dcdr-text-3); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 140px; }
.verified { color: #00b42a; margin-left: 2px; }
.summary { color: var(--dcdr-text-2); font-size: 13px; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; min-height: 62px; }
.tags { display: flex; flex-wrap: wrap; gap: 5px; }
.card-foot { display: flex; gap: 12px; color: var(--dcdr-text-3); font-size: 12px; }
.card-foot .src { margin-left: auto; }
.card-ops { display: flex; justify-content: flex-end; gap: 8px; }

.pager { margin-top: 18px; justify-content: center; }
.muted { color: var(--dcdr-text-3); font-size: 13px; }

.d-top { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; margin-bottom: 10px; }
.d-id { font-family: ui-monospace, Consolas, monospace; color: var(--dcdr-text-2); font-size: 13px; }
.d-summary { color: var(--dcdr-text-2); font-size: 13px; line-height: 1.7; margin: 0 0 10px; }
.d-stats { display: flex; gap: 16px; color: var(--dcdr-text-3); font-size: 12.5px; margin-bottom: 14px; flex-wrap: wrap; }
.d-section { margin-bottom: 14px; }
.d-title { font-weight: 500; font-size: 13.5px; margin-bottom: 8px; }
.src-link { margin-top: 8px; font-size: 12.5px; color: var(--dcdr-text-3); word-break: break-all; }

.ins-target { font-weight: 600; font-size: 15px; margin-bottom: 8px; }
.ins-desc { color: var(--dcdr-text-2); font-size: 13px; line-height: 1.7; margin: 0 0 12px; }
</style>
