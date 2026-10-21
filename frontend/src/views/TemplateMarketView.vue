<template>
  <div class="market-mode">
    <div class="market-header">
      <h2>专家市场</h2>
      <p class="subtitle">发现并克隆他人分享的专家与工作流模板; 也可以把自己的模板分享给所有人 (V3.0-beta F-35)</p>
    </div>

    <div class="toolbar">
      <el-select v-model="filters.type" size="small" style="width:120px" @change="reload">
        <el-option label="全部类型" value="" />
        <el-option label="专家" value="agent" />
        <el-option label="工作流" value="workflow" />
      </el-select>
      <el-input v-model="filters.keyword" size="small" style="width:220px" placeholder="搜索标题 / 描述"
                clearable @keyup.enter="reload" @clear="reload" />
      <el-select v-model="filters.sort" size="small" style="width:120px" @change="reload">
        <el-option label="最新" value="recent" />
        <el-option label="最多克隆" value="clones" />
        <el-option label="最多评分" value="rating" />
      </el-select>
      <el-button size="small" @click="reload" :loading="loading">搜索</el-button>
      <div style="flex:1"></div>
      <el-button size="small" @click="openMyShares">我的分享</el-button>
      <el-button size="small" type="primary" @click="openShareDialog">分享我的模板</el-button>
    </div>

    <div class="tpl-grid" v-loading="loading">
      <div v-for="t in templates" :key="t.id" class="tpl-card">
        <div class="tpl-head">
          <el-tag size="small" :type="t.type === 'agent' ? 'warning' : 'success'">
            {{ t.type === 'agent' ? '专家' : '工作流' }}
          </el-tag>
          <el-tag v-if="isBuiltin(t.id)" size="small" type="info" effect="plain">官方预置</el-tag>
          <span class="tpl-title">{{ t.title }}</span>
        </div>
        <div class="tpl-desc">{{ t.description || '（无描述）' }}</div>
        <div class="tpl-meta">
          <span class="meta-item">{{ ownerLabel(t) }}</span>
          <span class="meta-item">{{ t.cloneCount }} 次克隆</span>
          <span class="meta-item">{{ t.ratingCount ? t.ratingAvg.toFixed(1) : '-' }} 分 · {{ t.ratingCount }} 人评</span>
          <span v-if="t.category" class="meta-item">#{{ t.category }}</span>
        </div>
        <div class="tpl-tags" v-if="t.tags && t.tags.length">
          <el-tag v-for="tag in t.tags.slice(0, 4)" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        </div>
        <div class="tpl-actions">
          <el-button size="small" type="primary" @click="doClone(t)">克隆到我的账户</el-button>
          <el-rate :model-value="0" size="small" clearable @change="(v) => doRate(t, v)" />
        </div>
      </div>
      <div v-if="!loading && !templates.length" class="empty-tip">
        暂无专家模板<br />点击右上角「分享我的模板」, 把你的专家 / 工作流分享给其他用户
      </div>
    </div>

    <div class="pager" v-if="total > pageSize">
      <el-pagination background layout="prev, pager, next" :total="total"
                     :page-size="pageSize" v-model:current-page="page" @current-change="load" />
    </div>

    <!-- 分享对话框 -->
    <el-dialog v-model="shareDialogVisible" title="分享到专家市场" width="480px">
      <el-form label-width="80px">
        <el-form-item label="类型">
          <el-radio-group v-model="shareForm.type">
            <el-radio value="agent">专家</el-radio>
            <el-radio value="workflow">工作流</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标">
          <el-select v-model="shareForm.targetId" placeholder="选择要分享的模板" style="width:100%">
            <el-option v-for="t in shareTargets" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="shareForm.title" placeholder="留空使用模板名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="shareForm.description" type="textarea" :rows="2" placeholder="留空使用模板描述" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="shareForm.category" placeholder="如: 翻译 / 写作 / 编程 / 分析 / 客服" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="shareForm.tags" placeholder="逗号分隔, 如: 翻译,英文,专业" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shareDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="sharing" @click="doShare">分享</el-button>
      </template>
    </el-dialog>

    <!-- 我的分享 -->
    <el-dialog v-model="mySharesVisible" title="我的分享" width="640px">
      <el-table :data="myShares" size="small" v-loading="mySharesLoading">
        <el-table-column prop="title" label="标题" min-width="140" />
        <el-table-column prop="type" label="类型" width="80">
          <template #default="{ row }">{{ row.type === 'agent' ? '专家' : '工作流' }}</template>
        </el-table-column>
        <el-table-column prop="cloneCount" label="克隆" width="60" />
        <el-table-column label="评分" width="90">
          <template #default="{ row }">{{ row.ratingCount ? row.ratingAvg.toFixed(1) : '-' }} ({{ row.ratingCount }})</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" text @click="doUnshare(row)">下架</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMarketTemplates, getMyShares, shareToMarket, cloneMarketTemplate,
  rateMarketTemplate, unshareMarketTemplate,
  getAgents, getWorkflows, extractMessage
} from '@/api'

const loading = ref(false)
const templates = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const filters = reactive({ type: '', keyword: '', sort: 'recent' })

const shareDialogVisible = ref(false)
const sharing = ref(false)
const shareTargets = ref([])
const shareForm = reactive({ type: 'agent', targetId: '', title: '', description: '', category: '', tags: '' })

const mySharesVisible = ref(false)
const mySharesLoading = ref(false)
const myShares = ref([])

const isBuiltin = (id) => String(id).startsWith('builtin:')
const ownerLabel = (t) => (isBuiltin(t.id) ? '官方' : (t.ownerId ? '用户 ' + String(t.ownerId).slice(0, 8) : '用户'))

async function load() {
  loading.value = true
  try {
    const params = { page: page.value - 1, size: pageSize, sort: filters.sort }
    if (filters.type) params.type = filters.type
    if (filters.keyword) params.keyword = filters.keyword
    const data = await getMarketTemplates(params)
    templates.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载市场模板失败')
  } finally {
    loading.value = false
  }
}
function reload() { page.value = 1; load() }

async function doClone(t) {
  try {
    await ElMessageBox.confirm(`将「${t.title}」克隆为你的私有副本 (可自由编辑)?`, '克隆模板', { type: 'info' })
    const r = await cloneMarketTemplate(t.id)
    ElMessage.success(`克隆成功: ${r.newName} (${r.type === 'agent' ? '专家' : '工作流'})`)
    load()
  } catch (e) { /* 取消 */ if (e !== 'cancel' && e?.message) ElMessage.error(extractMessage(e)) }
}

async function doRate(t, val) {
  if (!val) return
  try {
    await rateMarketTemplate(t.id, val)
    ElMessage.success('评分成功')
    load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '评分失败')
  }
}

async function openShareDialog() {
  shareDialogVisible.value = true
  shareForm.targetId = ''
  try {
    // 仅列自己可分享的目标 (自己的 + 模板; 后端会校验归属)
    const [agents, workflows] = await Promise.all([getAgents(), getWorkflows()])
    shareTargets.value = [
      ...(agents || []).map(a => ({ id: a.id, name: a.name, type: 'agent' })),
      ...(workflows || []).map(w => ({ id: w.id, name: w.name, type: 'workflow' }))
    ]
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载可分享列表失败')
  }
}

async function doShare() {
  if (!shareForm.targetId) {
    ElMessage.warning('请选择要分享的目标')
    return
  }
  sharing.value = true
  try {
    await shareToMarket({ ...shareForm })
    ElMessage.success('已分享到市场')
    shareDialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '分享失败')
  } finally {
    sharing.value = false
  }
}

async function openMyShares() {
  mySharesVisible.value = true
  mySharesLoading.value = true
  try {
    myShares.value = await getMyShares()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载我的分享失败')
  } finally {
    mySharesLoading.value = false
  }
}

async function doUnshare(row) {
  try {
    await ElMessageBox.confirm(`确定下架「${row.title}」?`, '下架分享', { type: 'warning' })
    await unshareMarketTemplate(row.id)
    ElMessage.success('已下架')
    openMyShares()
  } catch (e) { /* 取消 */ if (e !== 'cancel' && e?.message) ElMessage.error(extractMessage(e)) }
}

onMounted(() => { load() })
</script>

<style scoped>
.market-mode { flex: 1; overflow-y: auto; padding: 24px 28px; background: var(--dcdr-bg-subtle); }
.market-header h2 { margin: 0 0 3px; font-size: 15px; font-weight: 600; color: var(--dcdr-text-1); }
.subtitle { margin: 0 0 14px; font-size: 11.5px; color: var(--dcdr-text-3); line-height: 1.6; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.tpl-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; min-height: 200px; }
.tpl-card { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 14px 16px;
  display: flex; flex-direction: column; gap: 8px; transition: box-shadow 0.2s; }
.tpl-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
.tpl-head { display: flex; align-items: center; gap: 8px; }
.tpl-title { font-weight: 600; font-size: 15px; color: var(--dcdr-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tpl-desc { font-size: 13px; color: var(--dcdr-text-2); min-height: 36px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.tpl-meta { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; color: var(--dcdr-text-3); }
.tpl-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.tpl-actions { display: flex; align-items: center; gap: 12px; margin-top: 4px; border-top: 1px solid var(--dcdr-border); padding-top: 10px; }
.empty-tip { grid-column: 1 / -1; text-align: center; color: var(--dcdr-text-3); padding: 60px 0; line-height: 2; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
</style>
