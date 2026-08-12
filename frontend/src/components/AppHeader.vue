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
        <div v-if="authStore.isAdmin" class="mode-tab" :class="{active: mode==='knowledge'}" @click="$emit('update:mode', 'knowledge')">知识库</div>
        <div v-if="authStore.isAdmin" class="mode-tab" :class="{active: mode==='admin'}" @click="$emit('update:mode', 'admin')">管理</div>
      </div>
      <button class="web-search-toggle" :class="{active: webSearch}" title="联网搜索：开启后优先调用联网检索获取实时/权威信息"
              @click="emit('update:webSearch', !webSearch)">
        <span class="ws-icon">🌐</span>
        <span class="ws-label">联网搜索</span>
      </button>
      <el-select :modelValue="currentModel" placeholder="选择模型" style="width:200px" @change="onModelChange">
        <el-option-group label="对话模型">
          <el-option v-for="m in chatModels" :key="m.name" :label="m.label" :value="m.name">
            <span>{{ m.label }}</span><el-tag size="small" style="margin-left:8px">对话</el-tag>
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
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { User as UserIcon, Lock, Setting, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { changePassword } from '@/api/auth'
import { extractMessage } from '@/api'
import logoUrl from '@/assets/dcdr-logo.png'

const props = defineProps({
  models: { type: Array, default: () => [] },
  currentModel: { type: String, default: '' },
  mode: { type: String, default: 'chat' },
  webSearch: { type: Boolean, default: false }
})
const emit = defineEmits(['update:currentModel', 'update:mode', 'update:webSearch', 'show-auth'])

const authStore = useAuthStore()

const chatModels = computed(() => props.models.filter(m => m.type === 'CHAT'))
const imageModels = computed(() => props.models.filter(m => m.type === 'IMAGE'))
const videoModels = computed(() => props.models.filter(m => m.type === 'VIDEO'))

const avatarLetter = computed(() => (authStore.username || '?').charAt(0).toUpperCase())

function onModelChange(val) {
  emit('update:currentModel', val)
  const m = props.models.find(x => x.name === val)
  emit('update:mode', m && m.uiMode === 'creative' ? 'creative' : 'chat')
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
</style>
