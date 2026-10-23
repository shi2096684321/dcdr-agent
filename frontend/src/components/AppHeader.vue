<template>
  <header class="app-header">
    <div class="brand">
      <img class="brand-mark" :src="logoUrl" alt="DCDR" />
      <span class="brand-name">DCDR</span>
      <span class="brand-ver">v3.1</span>
    </div>

    <!-- 全局命令入口：一级导航已在左侧轨道，这里只留"去哪儿"的兜底搜索 -->
    <button type="button" class="command" @click="emit('open-command')">
      <span class="command-icon" v-html="ICON_SEARCH" />
      <span class="command-text">搜索会话、Agent、工作流…</span>
      <kbd class="command-kbd">⌘K</kbd>
    </button>

    <div class="header-right">
      <button
        type="button"
        class="ws-toggle"
        :class="{ 'is-active': webSearch }"
        title="联网搜索：开启后优先调用联网检索获取实时/权威信息"
        @click="emit('update:webSearch', !webSearch)"
      >
        <span class="ws-icon" v-html="ICON_GLOBE" />
        <span class="ws-label">联网搜索</span>
      </button>

      <el-select :model-value="currentModel" placeholder="选择模型" class="model-select" @change="onModelChange">
        <el-option-group label="对话模型">
          <el-option v-for="m in platformChatModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" style="margin-left: 8px">对话</el-tag>
          </el-option>
        </el-option-group>
        <el-option-group v-if="customModels.length" label="我的自定义模型">
          <el-option v-for="m in customModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" type="warning" style="margin-left: 8px">自定义</el-tag>
          </el-option>
        </el-option-group>
        <el-option-group label="图片生成">
          <el-option v-for="m in imageModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" type="danger" style="margin-left: 8px">图片</el-tag>
          </el-option>
        </el-option-group>
        <el-option-group label="视频生成">
          <el-option v-for="m in videoModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" type="warning" style="margin-left: 8px">视频</el-tag>
          </el-option>
        </el-option-group>
        <el-option :value="MANAGE_MODELS" label="管理自定义模型">
          <span class="manage-models">＋ 管理自定义模型</span>
        </el-option>
      </el-select>

      <ThemeToggle />

      <!-- V2.2 用户菜单 -->
      <el-dropdown v-if="authStore.isLoggedIn" trigger="click" @command="onUserCommand">
        <div class="user-trigger">
          <el-avatar :size="30" class="user-avatar">{{ avatarLetter }}</el-avatar>
          <span class="user-name">{{ authStore.username }}</span>
          <span v-if="authStore.isAdmin" class="user-role">admin</span>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile" :icon="UserIcon">个人信息</el-dropdown-item>
            <el-dropdown-item command="password" :icon="Lock">修改密码</el-dropdown-item>
            <el-dropdown-item v-if="authStore.isAdmin" command="admin" :icon="Setting">管理后台</el-dropdown-item>
            <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button v-else type="primary" size="default" @click="emit('show-auth')">登录 / 注册</el-button>
    </div>
  </header>

  <!-- 修改密码对话框 -->
  <el-dialog v-model="pwdDialogVisible" title="修改密码" width="420px">
    <el-form label-width="80px">
      <el-form-item label="原密码">
        <el-input v-model="pwdForm.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="pwdForm.newPassword" type="password" placeholder="8 位以上" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="pwdLoading" @click="submitChangePassword">确认</el-button>
    </template>
  </el-dialog>

  <!-- 自定义模型管理 -->
  <el-dialog v-model="umVisible" title="管理自定义模型" width="560px">
    <div class="um-toolbar">
      <span class="um-tip">自定义 OpenAI 兼容 LLM（base-url + API Key + 模型名），保存后即可在下拉中选择</span>
      <el-button type="primary" size="small" @click="openUmCreate">新增</el-button>
    </div>
    <div class="um-list" v-loading="umLoading">
      <div v-for="m in umItems" :key="m.id" class="um-item">
        <div class="um-info">
          <span class="um-name">{{ m.name || m.model }}</span>
          <el-tag size="small" :type="m.enabled ? 'success' : 'info'">{{ m.enabled ? '启用' : '禁用' }}</el-tag>
          <span class="um-model">{{ m.model }}</span>
        </div>
        <div class="um-ops">
          <el-button size="small" @click="openUmEdit(m)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="deleteUm(m)">删除</el-button>
        </div>
      </div>
      <div v-if="!umLoading && !umItems.length" class="um-empty">暂无自定义模型，点击「新增」配置一个</div>
    </div>
  </el-dialog>

  <!-- 自定义模型新增/编辑表单 -->
  <el-dialog v-model="umFormVisible" :title="umTitle" width="500px">
    <el-form label-width="92px">
      <el-form-item label="选择厂商">
        <el-select v-model="umPreset" placeholder="内置常见厂商(自动填 endpoint)" clearable filterable style="width: 100%">
          <el-option v-for="p in PRESETS" :key="p.label" :label="p.label" :value="p.label">
            <span>{{ p.label }}</span>
            <span class="um-preset-url">{{ p.baseUrl }}</span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="umForm.name" placeholder="如: 我的 DeepSeek" maxlength="64" />
      </el-form-item>
      <el-form-item label="Base URL" required>
        <el-input v-model="umForm.baseUrl" placeholder="https://api.deepseek.com" />
      </el-form-item>
      <el-form-item label="API Key" :required="!umForm.id">
        <el-input v-model="umForm.apiKey" type="password" show-password :placeholder="umForm.id ? '留空则保留原 Key' : 'sk-xxx'" />
      </el-form-item>
      <el-form-item label="模型名" required>
        <el-input v-model="umForm.model" :placeholder="defaultModelPlaceholder" />
      </el-form-item>
      <el-form-item label="采样温度">
        <el-input-number v-model="umForm.temperature" :min="0" :max="2" :step="0.1" />
      </el-form-item>
      <el-form-item label="最大token">
        <el-input-number v-model="umForm.maxTokens" :min="1" :max="32000" :step="100" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="umForm.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="umFormVisible = false">取消</el-button>
      <el-button type="primary" :loading="umSaving" @click="submitUm">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { User as UserIcon, Lock, Setting, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useModelStore } from '@/stores/model'
import { changePassword } from '@/api/auth'
import { getUserModels, createUserModel, updateUserModel, deleteUserModel, extractMessage } from '@/api'
import ThemeToggle from '@/components/ThemeToggle.vue'
import logoUrl from '@/assets/dcdr-logo.png'

// 模型下拉中的"管理自定义模型"哨兵选项值
const MANAGE_MODELS = '__manage_models__'
const isCustom = (m) => typeof m.name === 'string' && m.name.startsWith('custom:')

// 内置常见 OpenAI 兼容厂商 (V3.1)
const PRESETS = [
  { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  { label: '通义千问 (DashScope)', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-plus' },
  { label: 'Kimi (Moonshot)', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o' },
  { label: 'Ollama (本地)', baseUrl: 'http://localhost:11434/v1', model: 'llama3' },
  { label: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V3' }
]

const ICON_SEARCH = `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="11" cy="11" r="7.2" stroke="currentColor" stroke-width="1.9"/><path d="M16.4 16.4L21 21" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/></svg>`

const ICON_GLOBE = `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="8.2" stroke="currentColor" stroke-width="1.9"/><path d="M3.8 12H20.2" stroke="currentColor" stroke-width="1.9"/><path d="M12 3.8C14.6 6.3 16.2 9 16.2 12C16.2 15 14.6 17.7 12 20.2C9.4 17.7 7.8 15 7.8 12C7.8 9 9.4 6.3 12 3.8Z" stroke="currentColor" stroke-width="1.9"/></svg>`

const props = defineProps({
  models: { type: Array, default: () => [] },
  currentModel: { type: String, default: '' },
  webSearch: { type: Boolean, default: false }
})
const emit = defineEmits(['update:currentModel', 'update:mode', 'update:webSearch', 'show-auth', 'open-command'])

const authStore = useAuthStore()
const modelStore = useModelStore()

const platformChatModels = computed(() => props.models.filter((m) => m.type === 'CHAT' && !isCustom(m)))
const customModels = computed(() => props.models.filter((m) => m.type === 'CHAT' && isCustom(m)))
const imageModels = computed(() => props.models.filter((m) => m.type === 'IMAGE'))
const videoModels = computed(() => props.models.filter((m) => m.type === 'VIDEO'))

const avatarLetter = computed(() => (authStore.username || '?').charAt(0).toUpperCase())

/**
 * 切换模型只改选择, <b>不再跳转页面</b>。
 * <p>
 * 早先按 uiMode 自动切对话/创作: 在写文档时顺手选个图片模型就会被踢到创作页, 回来还得重新找回原页面;
 * 反过来在创作页改回对话模型又会被弹走 —— 一次"想看看有哪些模型"的浏览变成了两次页面跳转。
 * <p>
 * 不匹配(在对话页选了图片/视频模型)改由<b>使用的那一刻</b>提示: 见 ChatView 的发送前校验与
 * CreativeView 的生成前校验。切换瞬间不打扰, 因为此时用户可能只是在浏览模型列表。
 */
function onModelChange(val) {
  if (val === MANAGE_MODELS) {
    openUmDialog()
    return
  }
  emit('update:currentModel', val)
}

// ===== 自定义模型管理 (V3.1) =====
const umVisible = ref(false)
const umLoading = ref(false)
const umItems = ref([])
const umFormVisible = ref(false)
const umSaving = ref(false)
const umPreset = ref('')
const anyPreset = computed(() => PRESETS.find((p) => p.label === umPreset.value))
const defaultModelPlaceholder = computed(() => (anyPreset.value ? `如: ${anyPreset.value.model}` : 'deepseek-chat'))
const umForm = reactive({ id: '', name: '', baseUrl: '', apiKey: '', model: '', temperature: 0.7, maxTokens: 2000, enabled: true })
const umTitle = computed(() => (umForm.id ? '编辑自定义模型' : '新增自定义模型'))

// 选中内置厂商后自动填 endpoint, 名称留空则带上厂商名
watch(umPreset, (val) => {
  const p = PRESETS.find((x) => x.label === val)
  if (!p) return
  if (umForm.baseUrl === '' || !umForm.id) umForm.baseUrl = p.baseUrl
  if (!umForm.name.trim()) umForm.name = p.label
  if (!umForm.id && !umForm.model.trim()) umForm.model = p.model
})

function emptyUm() {
  umForm.id = ''
  umForm.name = ''
  umForm.baseUrl = ''
  umForm.apiKey = ''
  umForm.model = ''
  umForm.temperature = 0.7
  umForm.maxTokens = 2000
  umForm.enabled = true
}

function openUmDialog() {
  umVisible.value = true
  loadUm()
}

async function loadUm() {
  umLoading.value = true
  try {
    umItems.value = await getUserModels()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载失败')
  } finally {
    umLoading.value = false
  }
}

function openUmCreate() {
  umPreset.value = ''
  emptyUm()
  umFormVisible.value = true
}

function openUmEdit(m) {
  umPreset.value = ''
  Object.assign(umForm, {
    id: m.id,
    name: m.name,
    baseUrl: m.baseUrl,
    apiKey: '',
    model: m.model,
    temperature: m.temperature,
    maxTokens: m.maxTokens,
    enabled: m.enabled
  })
  umFormVisible.value = true
}

async function submitUm() {
  if (!umForm.baseUrl.trim()) return ElMessage.warning('请填写 Base URL')
  if (!umForm.model.trim()) return ElMessage.warning('请填写模型名')
  if (!umForm.id && !umForm.apiKey.trim()) return ElMessage.warning('请填写 API Key')
  umSaving.value = true
  try {
    const payload = {
      name: umForm.name.trim() || umForm.model.trim(),
      provider: 'openai-compatible',
      baseUrl: umForm.baseUrl.trim(),
      apiKey: umForm.apiKey.trim(),
      model: umForm.model.trim(),
      temperature: umForm.temperature,
      maxTokens: umForm.maxTokens,
      enabled: umForm.enabled
    }
    if (umForm.id) await updateUserModel(umForm.id, payload)
    else await createUserModel(payload)
    ElMessage.success('保存成功')
    umFormVisible.value = false
    loadUm()
    // 刷新模型下拉
    modelStore.loadModels(true).catch(() => {})
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    umSaving.value = false
  }
}

function deleteUm(m) {
  ElMessageBox.confirm(`确定删除模型「${m.name || m.model}」?`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteUserModel(m.id)
        ElMessage.success('已删除')
        loadUm()
        modelStore.loadModels(true).catch(() => {})
      } catch (e) {
        ElMessage.error(extractMessage(e) || '删除失败')
      }
    })
    .catch(() => {})
}

function onUserCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗?', '提示', { type: 'warning' })
      .then(async () => {
        await authStore.logout()
        ElMessage.success('已退出登录')
        emit('update:mode', 'chat')
      })
      .catch(() => {})
  } else if (cmd === 'admin') {
    emit('update:mode', 'admin')
  } else if (cmd === 'password') {
    pwdDialogVisible.value = true
  } else if (cmd === 'profile') {
    ElMessage.info(`当前账号: ${authStore.username} (${authStore.user?.role || '-'})`)
  }
}

// 修改密码
const pwdDialogVisible = ref(false)
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '' })

async function submitChangePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (pwdForm.newPassword.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  pwdLoading.value = true
  try {
    await changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    ElMessage.success('密码修改成功')
    pwdDialogVisible.value = false
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
  } catch (e) {
    ElMessage.error(extractMessage(e) || '修改失败')
  } finally {
    pwdLoading.value = false
  }
}
</script>

<style scoped>
.app-header {
  position: relative;
  z-index: 2;
  flex: none;
  height: var(--dcdr-header-h);
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 20px;
  background: var(--dcdr-header-bg);
  border-bottom: 1px solid var(--dcdr-border);
  backdrop-filter: blur(14px);
}

/* ---------- 品牌 ---------- */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  object-fit: cover;
  box-shadow: var(--dcdr-glow-accent);
}

.brand-name {
  font-family: var(--dcdr-font-mono);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: var(--dcdr-text-1);
}

.brand-ver {
  font-family: var(--dcdr-font-mono);
  font-size: 9.5px;
  padding: 2px 6px;
  border-radius: var(--dcdr-radius-pill);
  background: var(--dcdr-hover);
  color: var(--dcdr-text-2);
}

/* ---------- 全局命令框 ---------- */
.command {
  width: 360px;
  max-width: 34vw;
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 12px;
  border-radius: var(--dcdr-radius-pill);
  border: 1px solid var(--dcdr-border);
  background: var(--dcdr-hover);
  color: var(--dcdr-text-3);
  transition: border-color var(--dcdr-dur) var(--dcdr-ease);
}

.command:hover {
  border-color: rgba(46, 167, 255, 0.45);
}

.command-icon {
  display: flex;
  width: 15px;
  height: 15px;
  flex: none;
}

.command-text {
  flex: 1;
  text-align: left;
  font-size: 12.5px;
  color: var(--dcdr-text-3);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.command-kbd {
  font-family: var(--dcdr-font-mono);
  font-size: 9.5px;
  padding: 3px 6px;
  border-radius: 5px;
  background: var(--dcdr-hover);
  border: 1px solid var(--dcdr-border);
  color: var(--dcdr-text-3);
}

/* ---------- 右侧 ---------- */
.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.ws-toggle {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 12px;
  border-radius: var(--dcdr-radius-pill);
  border: 1px solid var(--dcdr-border);
  background: var(--dcdr-hover);
  color: var(--dcdr-text-2);
  font-size: 12.5px;
  transition:
    color var(--dcdr-dur) var(--dcdr-ease),
    border-color var(--dcdr-dur) var(--dcdr-ease),
    background var(--dcdr-dur) var(--dcdr-ease);
}

.ws-toggle:hover {
  color: var(--dcdr-text-1);
  border-color: rgba(46, 167, 255, 0.45);
}

.ws-toggle.is-active {
  background: var(--dcdr-accent-wash);
  border-color: rgba(46, 167, 255, 0.5);
  color: var(--dcdr-accent-soft);
  font-weight: 500;
}

.ws-icon {
  display: flex;
  width: 14px;
  height: 14px;
}

.model-select {
  width: 212px;
}

.manage-models {
  color: var(--dcdr-accent);
}

/* ---------- 用户 ---------- */
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: var(--dcdr-radius-pill);
  cursor: pointer;
  transition: background var(--dcdr-dur) var(--dcdr-ease);
}

.user-trigger:hover {
  background: var(--dcdr-hover);
}

.user-avatar {
  background: linear-gradient(135deg, var(--dcdr-accent-2) 0%, var(--dcdr-accent) 100%);
  color: #fff;
  font-weight: 600;
  font-size: 13px;
}

.user-name {
  font-size: 12.5px;
  color: var(--dcdr-text-1);
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-family: var(--dcdr-font-mono);
  font-size: 9px;
  padding: 2px 6px;
  border-radius: var(--dcdr-radius-pill);
  background: rgba(255, 143, 163, 0.16);
  color: var(--dcdr-danger);
}

/* ---------- 自定义模型管理对话框 ---------- */
.um-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.um-tip {
  color: var(--dcdr-text-2);
  font-size: 13px;
  flex: 1;
}

.um-list {
  max-height: 420px;
  overflow-y: auto;
}

.um-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  border-bottom: 1px solid var(--dcdr-border);
}

.um-item:last-child {
  border-bottom: none;
}

.um-info {
  flex: 1;
}

.um-name {
  font-weight: 500;
  font-size: 14px;
  margin-right: 8px;
}

.um-model {
  color: var(--dcdr-text-3);
  font-size: 12px;
  font-family: var(--dcdr-font-mono);
}

.um-ops {
  display: flex;
  gap: 4px;
}

.um-empty {
  text-align: center;
  padding: 40px 0;
  color: var(--dcdr-text-3);
}

.um-preset-url {
  float: right;
  color: var(--dcdr-text-3);
  font-size: 12px;
  margin-left: 16px;
}
</style>
