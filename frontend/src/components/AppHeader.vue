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
        <div class="mode-tab" :class="{active: mode==='knowledge'}" @click="$emit('update:mode', 'knowledge')">知识库</div>
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
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import logoUrl from '@/assets/dcdr-logo.png'

const props = defineProps({
  models: { type: Array, default: () => [] },
  currentModel: { type: String, default: '' },
  mode: { type: String, default: 'chat' },
  webSearch: { type: Boolean, default: false }
})
const emit = defineEmits(['update:currentModel', 'update:mode', 'update:webSearch'])

const chatModels = computed(() => props.models.filter(m => m.type === 'CHAT'))
const imageModels = computed(() => props.models.filter(m => m.type === 'IMAGE'))
const videoModels = computed(() => props.models.filter(m => m.type === 'VIDEO'))

function onModelChange(val) {
  emit('update:currentModel', val)
  const m = props.models.find(x => x.name === val)
  // 切换到图片生成模型时自动跳到创作模式, 对话模型跳回对话模式
  emit('update:mode', m && m.uiMode === 'creative' ? 'creative' : 'chat')
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
</style>
