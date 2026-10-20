<template>
  <div class="admin-container">
    <el-tabs v-model="activeTab" class="admin-tabs">
      <!-- 系统概览 -->
      <el-tab-pane label="系统概览" name="stats">
        <div class="stats-grid" v-loading="statsLoading">
          <div class="stat-card" v-for="item in statCards" :key="item.label">
            <div class="stat-icon" :style="{ background: item.color }">{{ item.icon }}</div>
            <div class="stat-info">
              <div class="stat-value">{{ item.value }}</div>
              <div class="stat-label">{{ item.label }}</div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 用户管理 -->
      <el-tab-pane label="用户管理" name="users">
        <div class="toolbar">
          <el-input v-model="userSearch" placeholder="按用户名搜索" clearable style="width:240px" :prefix-icon="Search" />
          <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
        </div>
        <el-table :data="filteredUsers" v-loading="usersLoading" stripe style="width:100%">
          <el-table-column prop="username" label="用户名" min-width="120" />
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
          <el-table-column label="角色" width="100">
            <template #default="{ row }">
              <el-tag :type="row.role === 'admin' ? 'danger' : 'info'" size="small">{{ row.role }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'active' ? 'success' : 'warning'" size="small">
                {{ row.status === 'active' ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="注册时间" min-width="160">
            <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openEditUser(row)">编辑</el-button>
              <el-button size="small" type="warning" @click="openResetPassword(row)">重置密码</el-button>
              <el-button size="small" :type="row.status === 'active' ? 'danger' : 'success'"
                         @click="toggleUserStatus(row)">
                {{ row.status === 'active' ? '禁用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="userPage"
          v-model:page-size="userPageSize"
          :total="userTotal"
          layout="total, prev, pager, next"
          @current-change="loadUsers"
          style="margin-top:16px; justify-content:flex-end"
        />
      </el-tab-pane>

      <!-- 审计日志 -->
      <el-tab-pane label="审计日志" name="audit">
        <div class="toolbar">
          <el-input v-model="auditFilter.userId" placeholder="用户 ID" clearable style="width:200px" />
          <el-input v-model="auditFilter.action" placeholder="动作(如 login)" clearable style="width:160px" />
          <el-date-picker v-model="auditFilter.range" type="datetimerange" range-separator="至"
                          start-placeholder="开始时间" end-placeholder="结束时间" style="width:360px" />
          <el-button type="primary" :icon="Search" @click="loadAuditLogs">查询</el-button>
          <el-button @click="resetAuditFilter">重置</el-button>
        </div>
        <el-table :data="auditLogs" v-loading="auditLoading" stripe style="width:100%">
          <el-table-column prop="createdAt" label="时间" min-width="160">
            <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="userId" label="用户 ID" min-width="160" show-overflow-tooltip />
          <el-table-column prop="action" label="动作" min-width="140">
            <template #default="{ row }">
              <el-tag size="small">{{ row.action }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resourceType" label="资源类型" min-width="120" />
          <el-table-column prop="resourceId" label="资源 ID" min-width="180" show-overflow-tooltip />
          <el-table-column prop="ip" label="IP" min-width="140" />
          <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
        </el-table>
        <el-pagination
          v-model:current-page="auditPage"
          v-model:page-size="auditPageSize"
          :total="auditTotal"
          layout="total, prev, pager, next"
          @current-change="loadAuditLogs"
          style="margin-top:16px; justify-content:flex-end"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑用户对话框 -->
    <el-dialog v-model="editDialogVisible" title="编辑用户" width="440px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="用户名">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.role" style="width:100%">
            <el-option label="管理员 (admin)" value="admin" />
            <el-option label="普通用户 (user)" value="user" />
            <el-option label="访客 (guest)" value="guest" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editForm.status" style="width:100%">
            <el-option label="启用" value="active" />
            <el-option label="禁用" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editForm.email" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editLoading" @click="submitEditUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码对话框 -->
    <el-dialog v-model="resetDialogVisible" title="重置密码" width="440px">
      <el-form label-width="80px">
        <el-form-item label="用户名">
          <el-input :model-value="resetForm.username" disabled />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="resetForm.newPassword" type="password" placeholder="8 位以上" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetLoading" @click="submitResetPassword">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminGetStats, adminListUsers, adminUpdateUser, adminResetPassword, adminQueryAuditLogs } from '@/api/auth'
import { extractMessage } from '@/api'

const activeTab = ref('stats')

// ===== 系统统计 =====
const statsLoading = ref(false)
const stats = ref({})
const statCards = computed(() => [
  { label: '用户总数', value: stats.value.userCount ?? '-', icon: '👥', color: 'var(--dcdr-accent)' },
  { label: '启用用户', value: stats.value.activeUserCount ?? '-', icon: '✅', color: '#52c41a' },
  { label: '管理员', value: stats.value.adminCount ?? '-', icon: '🔑', color: 'var(--dcdr-danger)' },
  { label: '会话总数', value: stats.value.sessionCount ?? '-', icon: '💬', color: '#722ed1' },
  { label: '知识库数', value: stats.value.knowledgeBaseCount ?? '-', icon: '📚', color: '#fa8c16' },
  { label: '文档总数', value: stats.value.documentCount ?? '-', icon: '📄', color: '#13c2c2' },
  { label: '今日登录', value: stats.value.todayLoginCount ?? '-', icon: '🚪', color: '#eb2f96' },
  { label: '今日对话', value: stats.value.todayChatCount ?? '-', icon: '🤖', color: '#1890ff' }
])

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await adminGetStats()
  } catch (e) {
    ElMessage.error('加载统计失败')
  } finally {
    statsLoading.value = false
  }
}

// ===== 用户管理 =====
const usersLoading = ref(false)
const users = ref([])
const userTotal = ref(0)
const userPage = ref(1)
const userPageSize = ref(20)
const userSearch = ref('')

const filteredUsers = computed(() => {
  if (!userSearch.value) return users.value
  const kw = userSearch.value.toLowerCase()
  return users.value.filter(u => u.username?.toLowerCase().includes(kw))
})

async function loadUsers() {
  usersLoading.value = true
  try {
    const resp = await adminListUsers(userPage.value - 1, userPageSize.value)
    users.value = resp.content || []
    userTotal.value = resp.totalElements || 0
  } catch (e) {
    ElMessage.error('加载用户列表失败')
  } finally {
    usersLoading.value = false
  }
}

// 编辑用户
const editDialogVisible = ref(false)
const editLoading = ref(false)
const editForm = reactive({ id: '', username: '', role: '', status: '', email: '' })

function openEditUser(row) {
  editForm.id = row.id
  editForm.username = row.username
  editForm.role = row.role
  editForm.status = row.status
  editForm.email = row.email || ''
  editDialogVisible.value = true
}

async function submitEditUser() {
  editLoading.value = true
  try {
    await adminUpdateUser(editForm.id, {
      role: editForm.role,
      status: editForm.status,
      email: editForm.email
    })
    ElMessage.success('更新成功')
    editDialogVisible.value = false
    loadUsers()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '更新失败')
  } finally {
    editLoading.value = false
  }
}

// 切换用户状态(快捷启用/禁用)
async function toggleUserStatus(row) {
  const newStatus = row.status === 'active' ? 'disabled' : 'active'
  const action = newStatus === 'disabled' ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(`确定${action}用户 ${row.username} 吗?`, '提示', { type: 'warning' })
    await adminUpdateUser(row.id, { status: newStatus })
    ElMessage.success(`${action}成功`)
    loadUsers()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(`${action}失败`)
  }
}

// 重置密码
const resetDialogVisible = ref(false)
const resetLoading = ref(false)
const resetForm = reactive({ id: '', username: '', newPassword: '' })

function openResetPassword(row) {
  resetForm.id = row.id
  resetForm.username = row.username
  resetForm.newPassword = ''
  resetDialogVisible.value = true
}

async function submitResetPassword() {
  if (!resetForm.newPassword || resetForm.newPassword.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  resetLoading.value = true
  try {
    await adminResetPassword(resetForm.id, resetForm.newPassword)
    ElMessage.success('密码已重置')
    resetDialogVisible.value = false
  } catch (e) {
    ElMessage.error(extractMessage(e) || '重置失败')
  } finally {
    resetLoading.value = false
  }
}

// ===== 审计日志 =====
const auditLoading = ref(false)
const auditLogs = ref([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditPageSize = ref(20)
const auditFilter = reactive({ userId: '', action: '', range: null })

async function loadAuditLogs() {
  auditLoading.value = true
  try {
    const params = {
      page: auditPage.value - 1,
      size: auditPageSize.value
    }
    if (auditFilter.userId) params.userId = auditFilter.userId
    if (auditFilter.action) params.action = auditFilter.action
    if (auditFilter.range && auditFilter.range.length === 2) {
      params.from = auditFilter.range[0].toISOString()
      params.to = auditFilter.range[1].toISOString()
    }
    const resp = await adminQueryAuditLogs(params)
    auditLogs.value = resp.content || []
    auditTotal.value = resp.totalElements || 0
  } catch (e) {
    ElMessage.error('加载审计日志失败')
  } finally {
    auditLoading.value = false
  }
}

function resetAuditFilter() {
  auditFilter.userId = ''
  auditFilter.action = ''
  auditFilter.range = null
  auditPage.value = 1
  loadAuditLogs()
}

function formatDate(s) {
  if (!s) return '-'
  const d = new Date(s)
  return d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  loadStats()
  loadUsers()
  loadAuditLogs()
})
</script>

<style scoped>
.admin-container { padding: 20px; height: 100%; overflow: auto; background: var(--dcdr-bg-subtle); }
.admin-tabs { background: var(--dcdr-surface); border-radius: 8px; padding: 16px 20px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; align-items: center; }
.stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
.stat-card { background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 18px; display: flex; align-items: center; gap: 14px; }
.stat-icon { width: 48px; height: 48px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 22px; color: #fff; }
.stat-info { flex: 1; }
.stat-value { font-size: 24px; font-weight: 600; color: var(--dcdr-text-1); }
.stat-label { font-size: 13px; color: #6b7280; margin-top: 2px; }
</style>
