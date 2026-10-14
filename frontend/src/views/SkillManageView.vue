<template>
  <div class="skill-view">
    <div class="page-header">
      <div class="ph-left">
        <h2>技能</h2>
        <p class="sub">
          按需加载的领域知识包：平时只把「简介」放进上下文，模型判断需要时才加载正文。
          技能是纯文本、不含任何可执行内容，需要执行能力请通过依赖工具引用。
        </p>
      </div>
      <div class="ph-actions">
        <el-button @click="$emit('go-market')">技能市场</el-button>
        <el-button @click="openImportDialog">导入技能包</el-button>
        <el-button type="primary" @click="openCreateDialog">新建技能</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索标识 / 名称 / 简介" clearable style="width:260px" />
      <el-select v-model="categoryFilter" placeholder="全部分类" clearable style="width:170px">
        <el-option v-for="c in usedCategories" :key="c.key" :label="c.label" :value="c.key" />
      </el-select>
      <el-select v-model="statusFilter" style="width:130px">
        <el-option label="全部状态" value="all" />
        <el-option label="已启用" value="enabled" />
        <el-option label="已停用" value="disabled" />
      </el-select>
      <!-- 按来源筛: 补全正文时通常只想看"市场引入的"那一批 -->
      <el-select v-model="originFilter" style="width:130px">
        <el-option label="全部来源" value="all" />
        <el-option label="市场引入" value="market" />
        <el-option label="本地自建" value="local" />
        <el-option label="内置" value="builtin" />
      </el-select>
      <span class="count">共 {{ filtered.length }} / {{ skills.length }} 个技能</span>
      <el-button text @click="load">刷新</el-button>
    </div>

    <el-table :data="filtered" v-loading="loading" class="skill-table" empty-text="还没有技能，点击右上角「新建技能」或去「技能市场」看看">
      <el-table-column label="技能" min-width="250">
        <template #default="{ row }">
          <div class="sk-cell">
            <!-- 图标是外链(不存字节), 必须容忍加载失败 → 回落到首字母占位, 而不是留一个破图 -->
            <img
              v-if="row.iconUrl && !row.iconBroken"
              :src="row.iconUrl"
              class="sk-icon"
              alt=""
              @error="row.iconBroken = true"
            />
            <div v-else class="sk-icon sk-icon-ph">{{ (row.displayName || row.name || '?').charAt(0).toUpperCase() }}</div>
            <div class="sk-cell-main">
              <div class="sk-main">
                <!-- 待补全放最前: 它决定"这技能现在能不能用", 比"公开"这类属性更该先被看到 -->
                <el-tag v-if="row.instructionsPending" size="small" type="danger" effect="dark">待补全正文</el-tag>
                <span class="sk-name">{{ row.displayName || row.name }}</span>
                <el-tag v-if="row.isPublic" size="small" type="success">公开</el-tag>
                <el-tag v-if="row.hasExecutable" size="small" type="warning">需执行能力</el-tag>
              </div>
              <div class="sk-meta">
                <span class="sk-id">{{ row.name }}</span>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="简介" min-width="260">
        <template #default="{ row }">
          <div class="sk-desc">{{ row.description || '—' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="分类" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.categoryId" size="small" effect="plain">{{ categoryLabel(row.categoryId) }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="来源" width="170">
        <template #default="{ row }">
          <div class="sk-origin">
            <el-tag size="small" :type="originOf(row).type" effect="plain">{{ originOf(row).label }}</el-tag>
            <!-- 血缘标识直接摊开显示: 补全正文、核对内容时, 这是回到"原技能"的唯一线索 ——
                 它存在独立字段里, 所以正文被替换掉也不会丢 -->
            <div v-if="originOf(row).ref" class="sk-origin-ref" :title="originOf(row).ref">
              {{ originOf(row).ref }}
            </div>
            <!-- 补全正文用的地址: 直接从列表点走, 不必先打开技能读正文才发现有链接 -->
            <a
              v-if="row.sourceUrl"
              :href="row.sourceUrl"
              target="_blank"
              rel="noopener"
              class="sk-src"
              title="打开来源页复制正文"
            >来源页 ↗</a>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="内容" width="120">
        <template #default="{ row }">
          <span class="muted">
            {{ row.resourcePaths?.length || 0 }} 个参考资源
          </span>
        </template>
      </el-table-column>

      <el-table-column label="依赖" width="130">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.missingTools?.length"
            :content="`未注册: ${row.missingTools.join(', ')}`"
            placement="top"
          >
            <el-tag size="small" type="warning">缺 {{ row.missingTools.length }} 个</el-tag>
          </el-tooltip>
          <span v-else-if="row.requiredTools?.length" class="muted">{{ row.requiredTools.length }} 个已就绪</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :disabled="!row.editable"
            @change="(v) => toggleEnabled(row, v)"
          />
        </template>
      </el-table-column>

      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">
          <span class="muted">{{ fmtTime(row.updatedAt) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="272" fixed="right">
        <template #default="{ row }">
          <!-- 待补全时用「补全正文」替掉「编辑」: 同一件事, 但前者说清了现在该做什么 -->
          <el-button
            v-if="row.instructionsPending"
            size="small"
            type="primary"
            :disabled="!row.editable"
            @click="openEdit(row)"
          >补全正文</el-button>
          <el-button size="small" @click="openDetail(row)">查看</el-button>
          <el-button v-if="!row.instructionsPending" size="small" :disabled="!row.editable" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="doExport(row)">导出</el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="!row.editable || row.source === 'builtin'"
            @click="doDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ===== 新建 / 编辑 ===== -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑技能' : '新建技能'" width="820px" top="6vh" @closed="resetForm">
      <!-- 待补全时的指引: 光说"请补全"没用, 要说清"做什么 + 去哪拿" -->
      <el-alert
        v-if="form.instructionsPending"
        type="warning"
        :closable="false"
        show-icon
        class="pending-guide"
        title="这段正文是引入时生成的占位骨架，技能要等它补全后才会生效"
      >
        <ol class="pg-steps">
          <li>打开下面的<b>来源页</b>，复制其中的技能正文（通常是 SKILL.md 全文）</li>
          <li>粘贴替换掉「技能正文」里的<b>整段内容</b> —— 把那句 ⚠️ 说明也一起换掉</li>
          <li>保存</li>
          <li>回到技能列表，把该技能<b>启用</b>（引入时是停用状态）</li>
        </ol>
        <div class="pg-src">
          <template v-if="form.sourceUrl">
            来源页：<a :href="form.sourceUrl" target="_blank" rel="noopener">{{ form.sourceUrl }}</a>
          </template>
          <template v-else>
            该技能没有记录来源页地址 —— 请到当初获取它的地方复制正文，或先在下面「来源页」里补上地址。
          </template>
        </div>
      </el-alert>
      <el-form label-width="96px" label-position="right">
        <el-form-item label="技能标识" required>
          <el-input v-model="form.name" placeholder="kebab-case，如 excel-helper（模型按此名加载）" maxlength="64" />
          <div class="hint">小写字母/数字/连字符，3~64 位。改名后已有对话中引用旧名的调用会失效。</div>
        </el-form-item>
        <el-form-item label="展示名">
          <el-input v-model="form.displayName" placeholder="卡片标题，留空则同标识" maxlength="255" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input
            v-model="form.iconUrl"
            placeholder="https://… 或 / 开头的站内路径（留空则用首字母占位）"
            maxlength="512"
          />
          <div class="hint">
            只保存地址、不保存图片本身；因此外链失效时界面会自动回落到首字母占位。
            为保持技能包是纯文本，不接受 base64 内联图片。
          </div>
        </el-form-item>
        <el-form-item label="来源页">
          <el-input
            v-model="form.sourceUrl"
            placeholder="https://…（正文的来源地址，补全或核对内容时用）"
            maxlength="512"
          />
          <div class="hint">市场引入的技能会自动带上；手工建的可以留空。</div>
        </el-form-item>
        <!-- 血缘只读展示: 补全正文时能对上"我是在补哪个技能", 而不必回头翻市场 -->
        <el-form-item v-if="form.sourceRef" label="来源标识">
          <el-input :model-value="originRefText(form.sourceRef)" readonly />
          <div class="hint">
            引入时记下的血缘（只读，不参与保存）：补全正文不会把它冲掉，
            日后要回原技能核对内容就看这里。
          </div>
        </el-form-item>
        <el-form-item label="简介">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="唯一常驻上下文的内容：写清「什么时候该用它」，而不是泛泛而谈"
          />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="版本">
            <el-input v-model="form.version" placeholder="如 1.0.0" maxlength="32" />
          </el-form-item>
          <el-form-item label="作者">
            <el-input v-model="form.author" placeholder="可留空" maxlength="128" />
          </el-form-item>
          <el-form-item label="分类">
            <el-select v-model="form.categoryId" filterable allow-create default-first-option clearable placeholder="可选择或自建">
              <el-option v-for="c in CATEGORIES" :key="c.key" :label="c.label" :value="c.key" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="标签">
          <el-select v-model="form.tags" multiple filterable allow-create default-first-option placeholder="输入后回车添加" style="width:100%" />
        </el-form-item>

        <el-form-item label="技能正文" required>
          <el-input
            v-model="form.instructions"
            type="textarea"
            :rows="12"
            class="mono"
            placeholder="技能的核心：模型加载这个技能时看到的全部指令。写清步骤、判据、输出格式与边界。"
          />
          <div class="hint">{{ form.instructions.length }} 字符（上限 100000）</div>
        </el-form-item>

        <el-form-item label="参考资源">
          <div class="res-editor">
            <div v-for="(r, i) in form.resources" :key="i" class="res-row">
              <el-input v-model="r.path" placeholder="相对路径，如 reference/api.md" class="res-path" />
              <el-input v-model="r.content" type="textarea" :rows="3" placeholder="文本内容（仅支持 md / txt / json / csv / yaml / yml / toml）" />
              <el-button text type="danger" @click="form.resources.splice(i, 1)">移除</el-button>
            </div>
            <el-button size="small" @click="form.resources.push({ path: '', content: '' })">+ 添加参考资源</el-button>
            <div class="hint">参考资源按路径按需加载（模型要看某一条时才取回），所以大文档放这里而不是正文里。</div>
          </div>
        </el-form-item>

        <el-form-item label="依赖工具">
          <el-select v-model="form.requiredTools" multiple filterable allow-create default-first-option placeholder="输入工具名后回车（引用已注册工具，不捆绑）" style="width:100%" />
          <div class="hint">只声明依赖，能力由工具本身提供。缺失只告警、不阻断保存。</div>
        </el-form-item>

        <div class="form-row switches">
          <el-form-item label="需执行能力">
            <el-switch v-model="form.hasExecutable" />
            <span class="inline-hint">仅提示：该技能的工作流需要跑代码，请确保沙箱等工具已启用</span>
          </el-form-item>
          <el-form-item label="公开">
            <el-switch v-model="form.isPublic" />
            <span class="inline-hint">公开后其他用户也能加载</span>
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ===== 详情 ===== -->
    <el-dialog v-model="detailVisible" :title="detail?.displayName || detail?.name || '技能详情'" width="760px" top="8vh">
      <div v-if="detail" v-loading="detailLoading">
        <div class="d-meta">
          <el-tag size="small" effect="plain">{{ detail.name }}</el-tag>
          <el-tag v-if="detail.categoryId" size="small">{{ categoryLabel(detail.categoryId) }}</el-tag>
          <el-tag v-if="detail.version" size="small" type="info">v{{ detail.version }}</el-tag>
          <el-tag :type="detail.enabled ? 'success' : 'info'" size="small">{{ detail.enabled ? '已启用' : '已停用' }}</el-tag>
          <el-tag v-if="detail.author" size="small" effect="plain">{{ detail.author }}</el-tag>
        </div>

        <el-alert
          v-if="detail.missingTools?.length"
          type="warning"
          :closable="false"
          show-icon
          :title="`依赖工具未注册：${detail.missingTools.join(', ')}`"
          description="相关能力暂不可用；其余知识部分仍可正常使用。"
          style="margin-bottom:12px"
        />
        <el-alert
          v-if="detail.hasExecutable"
          type="info"
          :closable="false"
          show-icon
          title="该技能声明需要可执行能力"
          description="技能本身不含代码，请通过引用沙箱等已注册工具来提供执行能力。"
          style="margin-bottom:12px"
        />

        <p class="d-desc">{{ detail.description || '（无简介）' }}</p>

        <el-tabs v-model="detailTab">
          <el-tab-pane label="技能正文" name="instruction">
            <pre class="d-pre">{{ detail.instructions }}</pre>
          </el-tab-pane>
          <el-tab-pane :label="`参考资源 (${detail.resourcePaths?.length || 0})`" name="resources">
            <el-empty v-if="!detail.resourcePaths?.length" description="无参考资源" :image-size="60" />
            <template v-else>
              <el-radio-group v-model="activeResource" size="small" class="res-tabs">
                <el-radio-button v-for="p in detail.resourcePaths" :key="p" :value="p">{{ p }}</el-radio-button>
              </el-radio-group>
              <pre class="d-pre">{{ detail.resources?.[activeResource] || '' }}</pre>
            </template>
          </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail?.editable" type="primary" @click="detailVisible = false; openEdit(detail)">编辑</el-button>
      </template>
    </el-dialog>

    <!-- ===== 导入技能包 ===== -->
    <el-dialog v-model="importVisible" title="导入技能包" width="640px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="技能包是单个 JSON（指令 + 参考文本），不是 zip"
        description="可用「导出」产出的格式；同名技能默认拒绝导入，勾选覆盖才会替换。"
        style="margin-bottom:12px"
      />
      <el-input
        v-model="importText"
        type="textarea"
        :rows="12"
        class="mono"
        placeholder='粘贴技能包 JSON，例如 {"schema":"dcdr-skill/v1","name":"my-skill","instructions":"..."}'
      />
      <div class="import-foot">
        <el-checkbox v-model="importOverwrite">同名时覆盖（会替换已有正文，慎用）</el-checkbox>
        <el-button text @click="importText = ''">清空</el-button>
      </div>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getSkills, getSkill, createSkill, updateSkill, deleteSkill, setSkillEnabled,
  importSkillPackage, exportSkillPackage, extractMessage
} from '@/api'

defineEmits(['go-market'])

/**
 * 分类取值直接沿用技能市场的 13 个一级分类。
 * 好处是"本地自建的技能"和"市场引入的技能"落在同一个取值域里, 筛选与统计口径一致;
 * 同时允许 allow-create, 不强制用户接受这套分类。
 */
const CATEGORIES = [
  { key: 'office-efficiency', label: '办公效率' },
  { key: 'content-creation', label: '内容创作' },
  { key: 'dev-programming', label: '开发编程' },
  { key: 'data-analysis', label: '数据分析' },
  { key: 'design-media', label: '设计多媒体' },
  { key: 'ai-agent', label: 'AI Agent' },
  { key: 'knowledge-management', label: '知识管理' },
  { key: 'business-ops', label: '商业运营' },
  { key: 'education', label: '教育学习' },
  { key: 'professional', label: '行业专业' },
  { key: 'it-ops-security', label: 'IT 运维与安全' },
  { key: 'life-service', label: '生活服务' }
]

const loading = ref(false)
const skills = ref([])
const keyword = ref('')
const categoryFilter = ref('')
const statusFilter = ref('all')
const originFilter = ref('all')

const formVisible = ref(false)
const saving = ref(false)
const form = ref(emptyForm())

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailTab = ref('instruction')
const activeResource = ref('')

const importVisible = ref(false)
const importing = ref(false)
const importText = ref('')
const importOverwrite = ref(false)

const usedCategories = computed(() => {
  const keys = new Set(skills.value.map((s) => s.categoryId).filter(Boolean))
  return CATEGORIES.filter((c) => keys.has(c.key))
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return skills.value.filter((s) => {
    if (categoryFilter.value && s.categoryId !== categoryFilter.value) return false
    if (statusFilter.value === 'enabled' && !s.enabled) return false
    if (statusFilter.value === 'disabled' && s.enabled) return false
    if (originFilter.value === 'market' && !s.sourceRef) return false
    if (originFilter.value === 'builtin' && s.source !== 'builtin') return false
    // 本地 = 既非内置、也没有引入血缘
    if (originFilter.value === 'local' && (s.sourceRef || s.source === 'builtin')) return false
    if (!kw) return true
    return [s.name, s.displayName, s.description].some((v) => (v || '').toLowerCase().includes(kw))
  })
})

/** 血缘标识去掉来源前缀 —— 'skillhub:@ns/slug' → '@ns/slug'（前缀只是内部命名空间，展示没必要带） */
function originRefText(ref) {
  return String(ref || '').replace(/^skillhub:/, '')
}

/**
 * 来源展示。三类互斥：内置 > 市场引入 > 本地。
 * <p>
 * 判据是 {@code sourceRef}（血缘）而不是 {@code source}：后者表达的是<b>权限归属</b>
 * （内置不可删），市场引入的技能 {@code source} 仍是 local —— 它确实就是用户自己的一条技能。
 */
function originOf(row) {
  if (row.source === 'builtin') return { label: '内置', type: 'info', ref: '' }
  const ref = originRefText(row.sourceRef)
  if (!ref) return { label: '本地', type: '', ref: '' }
  return { label: '市场引入', type: 'warning', ref }
}

function emptyForm() {
  return {
    id: '', name: '', displayName: '', iconUrl: '', sourceUrl: '', description: '', version: '', author: '',
    categoryId: '', tags: [], instructions: '', resources: [], requiredTools: [],
    hasExecutable: false, isPublic: false,
    // 只读展示用: 由后端按正文内容判定, 不参与提交(用户换了正文它自动变 false)
    instructionsPending: false,
    // 只读展示用: 血缘由导入链路写入, 刻意不放进提交载荷 ——
    // 它若混进保存请求, 用户补全正文的那次普通编辑就会把来源清掉
    sourceRef: ''
  }
}

function categoryLabel(key) {
  const c = CATEGORIES.find((x) => x.key === key)
  return c ? c.label : key
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  try {
    skills.value = await getSkills()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载技能列表失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = emptyForm()
}

function openCreateDialog() {
  resetForm()
  formVisible.value = true
}

async function openEdit(row) {
  try {
    const d = await getSkill(row.id)
    form.value = {
      id: d.id,
      name: d.name || '',
      displayName: d.displayName || '',
      iconUrl: d.iconUrl || '',
      sourceUrl: d.sourceUrl || '',
      instructionsPending: !!d.instructionsPending,
      sourceRef: d.sourceRef || '',
      description: d.description || '',
      version: d.version || '',
      author: d.author || '',
      categoryId: d.categoryId || '',
      tags: d.tags || [],
      instructions: d.instructions || '',
      resources: Object.entries(d.resources || {}).map(([path, content]) => ({ path, content })),
      requiredTools: d.requiredTools || [],
      hasExecutable: !!d.hasExecutable,
      isPublic: !!d.isPublic
    }
    formVisible.value = true
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载技能详情失败')
  }
}

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  detailTab.value = 'instruction'
  try {
    const d = await getSkill(row.id)
    detail.value = d
    activeResource.value = (d.resourcePaths || [])[0] || ''
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载技能详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function buildResources() {
  const out = {}
  const seen = new Set()
  for (const r of form.value.resources) {
    const path = (r.path || '').trim()
    if (!path) continue
    if (seen.has(path)) throw new Error(`参考资源路径重复: ${path}`)
    seen.add(path)
    out[path] = r.content || ''
  }
  return out
}

async function submitForm() {
  const f = form.value
  if (!f.name.trim()) return ElMessage.warning('请填写技能标识')
  // 与后端 NAME_PATTERN 对齐: 提前拦下来能给更友好的提示, 而不是等接口报 400
  if (!/^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/.test(f.name.trim())) {
    return ElMessage.warning('技能标识需为 kebab-case：小写字母/数字/连字符，3~64 位')
  }
  if (!f.instructions.trim()) return ElMessage.warning('技能正文不能为空 —— 它是技能的核心')

  let resources
  try {
    resources = buildResources()
  } catch (e) {
    return ElMessage.warning(e.message)
  }

  saving.value = true
  try {
    // 刻意不提交 sourceRef/instructionsPending: 前者是系统记录的血缘(由导入链路写),
    // 后者由正文派生。把它们放进保存请求, 会让"补全正文"这次编辑顺手改动它们
    const payload = {
      name: f.name.trim(),
      displayName: f.displayName.trim(),
      iconUrl: f.iconUrl.trim(),
      sourceUrl: f.sourceUrl.trim(),
      description: f.description,
      version: f.version.trim(),
      author: f.author.trim(),
      categoryId: f.categoryId || '',
      tags: f.tags,
      instructions: f.instructions,
      resources,
      requiredTools: f.requiredTools,
      hasExecutable: f.hasExecutable,
      isPublic: f.isPublic
    }
    if (f.id) await updateSkill(f.id, payload)
    else await createSkill(payload)
    ElMessage.success('保存成功')
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row, enabled) {
  try {
    await setSkillEnabled(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用（不再进入技能目录）')
  } catch (e) {
    ElMessage.error(extractMessage(e) || '操作失败')
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除技能「${row.displayName || row.name}」吗？该操作不可撤销。`,
      '删除技能',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteSkill(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '删除失败')
  }
}

async function doExport(row) {
  try {
    const pkg = await exportSkillPackage(row.id)
    const blob = new Blob([JSON.stringify(pkg, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${pkg.name || row.name}.skill.json`
    a.click()
    // 立即释放: 不释放会一直占着这份 blob 的内存直到页面卸载
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(extractMessage(e) || '导出失败')
  }
}

function openImportDialog() {
  importText.value = ''
  importOverwrite.value = false
  importVisible.value = true
}

async function submitImport() {
  const raw = importText.value.trim()
  if (!raw) return ElMessage.warning('请粘贴技能包 JSON')

  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    return ElMessage.warning('不是合法的 JSON')
  }
  // 兼容两种粘贴形态: 技能包本体, 或 {"skill": {...}, "overwrite": true} 的外层包装
  const skill = parsed && parsed.skill ? parsed.skill : parsed
  if (!skill || typeof skill !== 'object') return ElMessage.warning('技能包格式不正确')

  importing.value = true
  try {
    const r = await importSkillPackage(skill, importOverwrite.value)
    const extra = r.warnings?.length ? `（${r.warnings.length} 条提示，见下方）` : ''
    ElMessage.success(`${r.action === 'overwritten' ? '已覆盖' : '已导入'}：${r.name}${extra}`)
    // 告警用弹窗而不是 toast: "依赖工具缺失""以停用状态导入"这类信息必须被读到
    if (r.warnings?.length) {
      ElMessageBox.alert(r.warnings.map((w) => `· ${w}`).join('\n'), '导入提示', {
        confirmButtonText: '知道了'
      }).catch(() => {})
    }
    importVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '导入失败')
  } finally {
    importing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.skill-view { flex: 1; overflow-y: auto; padding: 20px 24px; background: #f7f8fa; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.ph-left h2 { margin: 0 0 6px; font-size: 20px; }
.ph-left .sub { margin: 0; color: #86909c; font-size: 13px; line-height: 1.6; max-width: 720px; }
.ph-actions { display: flex; gap: 8px; flex-shrink: 0; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.toolbar .count { color: #86909c; font-size: 13px; margin-left: auto; }

.skill-table { background: #fff; border-radius: 8px; }
.sk-cell { display: flex; align-items: flex-start; gap: 10px; }
.sk-cell-main { min-width: 0; }
.sk-icon { width: 32px; height: 32px; border-radius: 8px; object-fit: cover; flex-shrink: 0; }
.sk-icon-ph {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-weight: 600; font-size: 13px;
}
/* wrap: 前面多了「待补全正文」标签, 不换行会把后面的名称/标签挤出去 */
.sk-main { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.sk-name { font-weight: 500; }
.sk-meta { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.sk-id { color: #86909c; font-size: 12px; font-family: ui-monospace, Consolas, monospace; }
.sk-src { font-size: 12px; color: #667eea; text-decoration: none; white-space: nowrap; }
.sk-src:hover { text-decoration: underline; }

/* 来源列: 标签 + 血缘标识 + 来源页链接, 三行竖排 —— 挤在一行会让长标识把列撑爆 */
.sk-origin { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.sk-origin-ref {
  max-width: 100%;
  font-size: 12px;
  color: #86909c;
  font-family: ui-monospace, Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 待补全指引 */
.pending-guide { margin-bottom: 14px; }
.pg-steps { margin: 0 0 8px; padding-left: 18px; line-height: 1.8; }
.pg-src { word-break: break-all; }
.pg-src a { color: #667eea; }
.sk-desc { color: #4e5969; font-size: 13px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.muted { color: #86909c; font-size: 13px; }

.hint { color: #86909c; font-size: 12px; line-height: 1.6; margin-top: 4px; }
.inline-hint { color: #86909c; font-size: 12px; margin-left: 10px; }
.form-row { display: flex; gap: 12px; }
.form-row > .el-form-item { flex: 1; }
.form-row.switches { align-items: center; }
.mono :deep(textarea) { font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; }

.res-editor { width: 100%; }
.res-row { display: flex; gap: 8px; align-items: flex-start; margin-bottom: 8px; }
.res-path { width: 220px; flex-shrink: 0; }

.d-meta { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.d-desc { color: #4e5969; font-size: 13px; line-height: 1.7; margin: 0 0 12px; }
.d-pre { background: #f7f8fa; border-radius: 6px; padding: 12px; font-size: 12.5px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; max-height: 420px; overflow-y: auto; margin: 0; }
.res-tabs { margin-bottom: 10px; display: flex; flex-wrap: wrap; gap: 6px; }
.import-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }
</style>
