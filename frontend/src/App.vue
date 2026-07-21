<template>
  <div class="app-container">
    <header class="app-header">
      <div class="logo">
        <div class="logo-icon gradient-bg">AI</div>
        <span>Java AI Agent</span>
      </div>
      <div class="header-right">
        <div class="mode-switch">
          <div class="mode-tab" :class="{active: mode==='chat'}" @click="mode='chat'">对话</div>
          <div class="mode-tab" :class="{active: mode==='creative'}" @click="mode='creative'">创作</div>
        </div>
        <el-select v-model="currentModel" placeholder="选择模型" style="width:200px" @change="onModelChange">
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
        </el-select>
      </div>
    </header>
    <main class="app-main">
      <ChatMode v-if="mode==='chat'" :current-model="currentModel" />
      <CreativeMode v-else :current-model="currentModel" :image-models="imageModels" />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getModels } from './api'
import ChatMode from './components/ChatMode.vue'
import CreativeMode from './components/CreativeMode.vue'

const mode = ref('chat')
const models = ref([])
const currentModel = ref('openai-chat')
const chatModels = computed(() => models.value.filter(m => m.type === 'CHAT'))
const imageModels = computed(() => models.value.filter(m => m.type === 'IMAGE'))

function onModelChange(val) {
  const m = models.value.find(x => x.name === val)
  if (m && m.uiMode === 'creative') mode.value = 'creative'
  else mode.value = 'chat'
}

onMounted(async () => { models.value = await getModels() })
</script>

<style>
.app-container { height: 100vh; display: flex; flex-direction: column; }
.app-header { height: 56px; background: #fff; border-bottom: 1px solid #e8e8e8; display: flex; align-items: center; padding: 0 20px; box-shadow: 0 1px 4px rgba(0,0,0,0.04); z-index: 100; }
.logo { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 600; }
.logo-icon { width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 14px; font-weight: bold; }
.header-right { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.mode-switch { display: flex; background: #f5f7fa; border-radius: 8px; padding: 3px; }
.mode-tab { padding: 5px 16px; font-size: 13px; border-radius: 6px; cursor: pointer; color: #606266; user-select: none; }
.mode-tab.active { background: #fff; color: #667eea; font-weight: 500; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
.app-main { flex: 1; display: flex; overflow: hidden; }
</style>
