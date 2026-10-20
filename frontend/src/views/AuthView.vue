<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="auth-header">
        <img :src="logoUrl" class="auth-logo" alt="DCDR" />
        <div>
          <h2 class="auth-title">DCDR Agent</h2>
          <p class="auth-subtitle">多模态智能对话助手</p>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="auth-tabs" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-position="top" @submit.prevent="handleLogin">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="loginForm.username" placeholder="请输入用户名" :prefix-icon="User" size="large" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="loginForm.password" type="password" placeholder="请输入密码"
                        :prefix-icon="Lock" size="large" show-password @keyup.enter="handleLogin" />
            </el-form-item>
            <el-button type="primary" size="large" class="auth-submit" :loading="authStore.loading" @click="handleLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top" @submit.prevent="handleRegister">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="registerForm.username" placeholder="3-64 位字母/数字/下划线" :prefix-icon="User" size="large" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="8 位以上, 含大小写与数字"
                        :prefix-icon="Lock" size="large" show-password />
            </el-form-item>
            <el-form-item label="邮箱(可选)" prop="email">
              <el-input v-model="registerForm.email" placeholder="example@domain.com" :prefix-icon="Message" size="large" />
            </el-form-item>
            <el-button type="primary" size="large" class="auth-submit" :loading="authStore.loading" @click="handleRegister">
              注册
            </el-button>
            <p class="auth-tip">首位注册用户自动成为管理员</p>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { User, Lock, Message } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { extractMessage } from '@/api/http'
import logoUrl from '@/assets/dcdr-logo.png'

const authStore = useAuthStore()
const emit = defineEmits(['success'])

const activeTab = ref('login')
const loginFormRef = ref()
const registerFormRef = ref()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '', email: '' })

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '长度 3-64 字符', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '仅字母/数字/下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '8 位以上', trigger: 'blur' }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function handleLogin() {
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await authStore.login(loginForm.username, loginForm.password)
      ElMessage.success(`欢迎回来, ${authStore.username}`)
      emit('success')
    } catch (e) {
      ElMessage.error(extractMessage(e) || '登录失败')
    }
  })
}

async function handleRegister() {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      await authStore.register(registerForm.username, registerForm.password, registerForm.email || undefined)
      ElMessage.success('注册成功, 已自动登录')
      emit('success')
    } catch (e) {
      ElMessage.error(extractMessage(e) || '注册失败')
    }
  })
}
</script>

<style scoped>
.auth-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1100px 720px at 22% -12%, rgba(46, 167, 255, 0.22), transparent 62%),
    radial-gradient(900px 640px at 92% 112%, rgba(147, 129, 255, 0.18), transparent 62%),
    var(--dcdr-bg);
  padding: 20px;
}
.auth-card {
  background: var(--dcdr-surface);
  border: 1px solid var(--dcdr-border);
  border-radius: var(--dcdr-radius-card);
  padding: 36px 40px;
  width: 100%;
  max-width: 420px;
  box-shadow: var(--dcdr-shadow-pop);
}
.auth-header { display: flex; align-items: center; gap: 14px; margin-bottom: 28px; }
.auth-logo { width: 48px; height: 48px; border-radius: 12px; object-fit: cover; }
.auth-title { font-size: 22px; font-weight: 600; color: var(--dcdr-text-1); margin: 0; }
.auth-subtitle { font-size: 13px; color: var(--dcdr-text-2); margin: 4px 0 0; }
.auth-tabs :deep(.el-tabs__header) { margin-bottom: 20px; }
.auth-submit { width: 100%; margin-top: 8px; }
.auth-tip { font-size: 12px; color: var(--dcdr-text-3); text-align: center; margin: 12px 0 0; }
</style>
