<template>
  <header class="app-header">
    <div class="logo">
      <img :src="logoUrl" class="logo-img" alt="DCDR" />
      <span>DCDR</span>
    </div>
    <div class="header-right">
      <div class="mode-switch">
        <div class="mode-tab" :class="{active: mode==='chat'}" @click="$emit('update:mode', 'chat')">对话</div>
        <div class="mode-tab" :class="{active: mode==='creative'}" @click="$emit('update:mode', 'creative')">创作</div>
        <!-- 知识库开放给普通用户(可管理自己的 USER 级; 平台级只读), 登录即可使用 -->
        <div v-if="authStore.isLoggedIn" class="mode-tab" :class="{active: mode==='knowledge'}" @click="$emit('update:mode', 'knowledge')">知识库</div>
        <!-- 专家工作台: 专家 / 技能 / 技能市场 / 专家市场 四个二级页共用一个顶栏入口 -->
        <div v-if="authStore.isLoggedIn" class="mode-tab" :class="{active: mode==='expert'}" @click="$emit('update:mode', 'expert')">专家</div>
        <div v-if="authStore.isLoggedIn" class="mode-tab" :class="{active: mode==='memory'}" @click="$emit('update:mode', 'memory')">记忆</div>
        <!-- 自动化: 工作流 / DAG 编排 / 定时任务 -->
        <div v-if="authStore.isLoggedIn" class="mode-tab" :class="{active: mode==='auto'}" @click="$emit('update:mode', 'auto')">自动化</div>
        <!-- 运维: 监控与成本 / 调用链 / 智能路由 / 检索健康(仅管理员) -->
        <div v-if="authStore.isLoggedIn" class="mode-tab" :class="{active: mode==='ops'}" @click="$emit('update:mode', 'ops')">运维</div>
        <div v-if="authStore.isAdmin" class="mode-tab" :class="{active: mode==='admin'}" @click="$emit('update:mode', 'admin')">管理</div>
      </div>
      <button class="web-search-toggle" :class="{active: webSearch}" title="联网搜索：开启后优先调用联网检索获取实时/权威信息"
              @click="emit('update:webSearch', !webSearch)">
        <span class="ws-icon">🌐</span>
        <span class="ws-label">联网搜索</span>
      </button>
      <el-select :modelValue="currentModel" placeholder="选择模型" style="width:220px" @change="onModelChange">
        <el-option-group label="对话模型">
          <el-option v-for="m in platformChatModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" style="margin-left:8px">对话</el-tag>
          </el-option>
        </el-option-group>
        <el-option-group v-if="customModels.length" label="我的自定义模型">
          <el-option v-for="m in customModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" type="warning" style="margin-left:8px">自定义</el-tag>
          </el-option>
        </el-option-group>
        <el-option-group label="图片生成">
          <el-option v-for="m in imageModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" type="danger" style="margin-left:8px">图片</el-tag>
          </el-option>
        </el-option-group>
        <el-option-group label="视频生成">
          <el-option v-for="m in videoModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" type="warning" style="margin-left:8px">视频</el-tag>
          </el-option>
        </el-option-group>
        <el-option :value="MANAGE_MODELS" label="管理自定义模型">
          <span style="color:#667eea">＋ 管理自定义模型</span>
        </el-option>
      </el-select>

      <!-- V2.2 用户菜单 -->
      <el-dropdown v-if="authStore.isLoggedIn" trigger="click" @command="onUserCommand">
        <div class="user-trigger">
          <el-avatar :size="32" class="user-avatar">{{ avatarLetter }}</el-avatar>
          <span class="user-name">{{ authStore.username }}</span>
          <el-tag v-if="authStore.isAdmin" type="danger" size="small" effect="dark">admin</el-tag>
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
        <el-select v-model="umPreset" placeholder="内置常见厂商(自动填 endpoint)" clearable filterable style="width:100%">
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

const props = defineProps({
  models: { type: Array, default: () => [] },
  currentModel: { type: String, default: '' },
  mode: { type: String, default: 'chat' },
  webSearch: { type: Boolean, default: false }
})
const emit = defineEmits(['update:currentModel', 'update:mode', 'update:webSearch', 'show-auth'])

const authStore = useAuthStore()
const modelStore = useModelStore()

const platformChatModels = computed(() => props.models.filter(m => m.type === 'CHAT' && !isCustom(m)))
const customModels = computed(() => props.models.filter(m => m.type === 'CHAT' && isCustom(m)))
const imageModels = computed(() => props.models.filter(m => m.type === 'IMAGE'))
const videoModels = computed(() => props.models.filter(m => m.type === 'VIDEO'))

const avatarLetter = computed(() => (authStore.username || '?').charAt(0).toUpperCase())

function onModelChange(val) {
  if (val === MANAGE_MODELS) {
    openUmDialog()
    return
  }
  emit('update:currentModel', val)
  const m = props.models.find(x => x.name === val)
  emit('update:mode', m && m.uiMode === 'creative' ? 'creative' : 'chat')
}

// ===== 自定义模型管理 (V3.1) =====
const umVisible = ref(false)
const umLoading = ref(false)
const umItems = ref([])
const umFormVisible = ref(false)
const umSaving = ref(false)
const umPreset = ref('')
const anyPreset = computed(() => PRESETS.find(p => p.label === umPreset.value))
const defaultModelPlaceholder = computed(() => (anyPreset.value ? `如: ${anyPreset.value.model}` : 'deepseek-chat'))
const umForm = reactive({ id: '', name: '', baseUrl: '', apiKey: '', model: '', temperature: 0.7, maxTokens: 2000, enabled: true })
const umTitle = computed(() => (umForm.id ? '编辑自定义模型' : '新增自定义模型'))

// 选中内置厂商后自动填 endpoint, 名称留空则带上厂商名
watch(umPreset, (val) => {
  const p = PRESETS.find(x => x.label === val)
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
    id: m.id, name: m.name, baseUrl: m.baseUrl, apiKey: '',
    model: m.model, temperature: m.temperature, maxTokens: m.maxTokens, enabled: m.enabled
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
      name: umForm.name.trim() || umForm.model.trim(), provider: 'openai-compatible', baseUrl: umForm.baseUrl.trim(),
      apiKey: umForm.apiKey.trim(), model: umForm.model.trim(),
      temperature: umForm.temperature, maxTokens: umForm.maxTokens, enabled: umForm.enabled
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
    }).catch(() => {})
}

function onUserCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗?', '提示', { type: 'warning' }).then(async () => {
      await authStore.logout()
      ElMessage.success('已退出登录')
      emit('update:mode', 'chat')
    }).catch(() => {})
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
.app-header { height: 56px; background: #fff; border-bottom: 1px solid #e8e8e8; display: flex; align-items: center; padding: 0 20px; box-shadow: 0 1px 4px rgba(0,0,0,0.04); z-index: 100; }
.logo { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 600; }
.logo-img { width: 32px; height: 32px; border-radius: 8px; object-fit: cover; }
.header-right { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.mode-switch { display: flex; background: #f5f7fa; border-radius: 8px; padding: 3px; }
.mode-tab { padding: 5px 16px; font-size: 13px; border-radius: 6px; cursor: pointer; color: #606266; user-select: none; }
.mode-tab.active { background: #fff; color: #667eea; font-weight: 500; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
.web-search-toggle { display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 14px; border-radius: 8px; border: 1px solid #dcdfe6; background: #f5f7fa; color: #606266; font-size: 13px; cursor: pointer; user-select: none; transition: all 0.2s; }
.web-search-toggle:hover { border-color: #667eea66; color: #667eea; }
.web-search-toggle.active { background: linear-gradient(135deg, rgba(102,126,234,0.16) 0%, rgba(118,75,162,0.14) 100%); border-color: rgba(102,126,234,0.5); color: #667eea; font-weight: 500; }
.web-search-toggle .ws-icon { font-size: 15px; }
.user-trigger { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 4px 8px; border-radius: 8px; transition: background 0.2s; }
.user-trigger:hover { background: #f5f7fa; }
.user-avatar { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; font-weight: 600; font-size: 14px; }
.user-name { font-size: 13px; color: #303133; max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 自定义模型管理对话框样式 */
.um-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.um-tip { color: #86909c; font-size: 13px; flex: 1; }
.um-list { max-height: 420px; overflow-y: auto; }
.um-item { display: flex; align-items: center; gap: 8px; padding: 12px 0; border-bottom: 1px solid #f2f3f5; }
.um-item:last-child { border-bottom: none; }
.um-info { flex: 1; }
.um-name { font-weight: 500; font-size: 14px; margin-right: 8px; }
.um-model { color: #86909c; font-size: 12px; }
.um-ops { display: flex; gap: 4px; }
.um-empty { text-align: center; padding: 40px 0; color: #86909c; }
.um-preset-url { float: right; color: #86909c; font-size: 12px; margin-left: 16px; }
</style>
