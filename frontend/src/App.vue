<template>
  <div class="app-container">
    <AppHeader
      v-model:current-model="currentModel"
      v-model:mode="mode"
      v-model:web-search="webSearch"
      :models="models"
    />
    <main class="app-main">
      <ChatView v-if="mode==='chat'" :current-model="currentModel" v-model:web-search="webSearch" />
      <CreativeView v-else-if="mode==='creative'" :current-model="currentModel" :image-models="imageModels" :video-models="videoModels" @update:current-model="currentModel = $event" />
      <KnowledgeBaseView v-else-if="mode==='knowledge'" />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getModels } from '@/api'
import AppHeader from '@/components/AppHeader.vue'
import ChatView from '@/views/ChatView.vue'
import CreativeView from '@/views/CreativeView.vue'
import KnowledgeBaseView from '@/views/KnowledgeBaseView.vue'

const mode = ref('chat')
const models = ref([])
const currentModel = ref('')
const webSearch = ref(false)
const chatModels = computed(() => models.value.filter(m => m.type === 'CHAT'))
const imageModels = computed(() => models.value.filter(m => m.type === 'IMAGE'))
const videoModels = computed(() => models.value.filter(m => m.type === 'VIDEO'))

onMounted(async () => {
  models.value = await getModels()
  // 默认选中后端配置的默认对话模型
  const def = models.value.find(m => m.type === 'CHAT' && m.isDefault) || chatModels.value[0]
  currentModel.value = def ? def.name : ''
})
</script>

<style scoped>
.app-container { height: 100vh; display: flex; flex-direction: column; }
.app-main { flex: 1; display: flex; overflow: hidden; }
</style>
