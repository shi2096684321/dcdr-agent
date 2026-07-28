<template>
  <div class="msg-item" :class="'msg-' + msg.role">
    <div class="msg-avatar" :class="{ 'gradient-bg': msg.role === 'user' }">
      <img v-if="msg.role !== 'user'" :src="logoUrl" class="avatar-img" alt="DCDR" />
      <template v-else>U</template>
    </div>
    <div class="msg-content">
      <div class="msg-role">{{ msg.role === 'user' ? '你' : 'DCDR' }}</div>

      <!-- 图片消息 -->
      <div v-if="msg.images && msg.images.length" class="msg-images">
        <div v-for="(img, idx) in msg.images" :key="idx" class="msg-image-wrapper">
          <img :src="img.url || img" :alt="img.name || '图片'" class="msg-image"
               @click="$emit('preview-image', img.url || img)" loading="lazy" />
          <div v-if="img.name" class="image-name">{{ img.name }}</div>
        </div>
      </div>

      <!-- 视频消息 -->
      <div v-if="msg.videos && msg.videos.length" class="msg-videos">
        <div v-for="(vid, idx) in msg.videos" :key="idx" class="msg-video-wrapper">
          <video :src="vid.url || vid" controls class="msg-video" preload="metadata"></video>
          <div v-if="vid.name" class="video-name">{{ vid.name }}</div>
        </div>
      </div>

      <!-- 推理过程 -->
      <div v-if="msg.reasoningContent" class="msg-reasoning">
        <details>
          <summary>💭 推理过程</summary>
          <div v-html="renderMarkdown(msg.reasoningContent)"></div>
        </details>
      </div>

      <!-- 编辑态 -->
      <div v-if="editing" class="edit-box">
        <textarea :value="editBuffer"
                  @input="$emit('update:editBuffer', $event.target.value)"
                  class="edit-textarea" placeholder="编辑消息内容..."
                  @keydown.ctrl.enter="$emit('save-edit', msg)" @keydown.meta.enter="$emit('save-edit', msg)"></textarea>
        <div class="msg-actions">
          <button class="btn btn-small btn-primary" @click="$emit('save-edit', msg)">保存并重新生成</button>
          <button class="btn btn-small" @click="$emit('cancel-edit')">取消</button>
        </div>
      </div>
      <template v-else>
        <div v-if="msg.content" class="msg-bubble" v-html="renderMarkdown(msg.content)"></div>

        <div v-if="msg.interrupted" class="msg-interrupted">⚠ 已中断，以下为已生成的内容</div>

        <!-- 元信息 -->
        <div v-if="msg.role === 'assistant' && (msg.tokenUsage != null || msg.durationMs != null)" class="msg-meta">
          <span v-if="msg.durationMs != null">⏱ {{ (msg.durationMs / 1000).toFixed(1) }}s</span>
          <span v-if="msg.tokenUsage != null">🪙 {{ msg.tokenUsage }} token</span>
        </div>

        <!-- 操作: 编辑历史消息 / 重新生成助手回答 -->
        <div v-if="!streaming" class="msg-actions">
          <button v-if="msg.role === 'user'" class="btn btn-small" @click="$emit('start-edit', msg)" title="编辑并重新生成">✏️ 编辑</button>
          <button v-if="msg.role === 'assistant'" class="btn btn-small" @click="$emit('retry', msg)" title="重新生成此回答">🔄 重新生成</button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import logoUrl from '@/assets/dcdr-logo.png'
import { useMarkdown } from '@/composables/useMarkdown'

defineProps({
  msg: { type: Object, required: true },
  streaming: { type: Boolean, default: false },
  editing: { type: Boolean, default: false },
  editBuffer: { type: String, default: '' }
})
defineEmits(['update:editBuffer', 'start-edit', 'save-edit', 'cancel-edit', 'retry', 'preview-image'])

const { renderMarkdown } = useMarkdown()
</script>

<style scoped>
.msg-item { display: flex; gap: 14px; margin-bottom: 28px; max-width: 900px; margin-left: auto; margin-right: auto; width: 100%; animation: fadeInUp 0.3s ease; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.msg-avatar { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px; font-weight: 600; flex-shrink: 0; overflow: hidden; box-shadow: 0 3px 10px rgba(0,0,0,0.1); }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.msg-content { flex: 1; min-width: 0; }
.msg-role { font-size: 13px; color: #909399; margin-bottom: 6px; font-weight: 500; }

/* 图片消息 */
.msg-images { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-image-wrapper { position: relative; }
.msg-image { max-width: 280px; max-height: 240px; border-radius: 12px; object-fit: cover; cursor: pointer; transition: all 0.25s ease; box-shadow: 0 4px 12px rgba(0,0,0,0.1); border: 1px solid #e8ecf4; }
.msg-image:hover { transform: scale(1.02); box-shadow: 0 6px 20px rgba(0,0,0,0.15); }
.image-name { font-size: 11px; color: #909399; text-align: center; margin-top: 4px; max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 视频消息 */
.msg-videos { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-video-wrapper { position: relative; }
.msg-video { max-width: 400px; max-height: 300px; border-radius: 12px; background: #000; }
.video-name { font-size: 11px; color: #909399; text-align: center; margin-top: 4px; max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 推理过程 */
.msg-reasoning { margin-bottom: 8px; }
.msg-reasoning details { background: linear-gradient(135deg, #f8f9fc 0%, #f0f2f7 100%); border: 1px solid #e4e7ed; border-radius: 10px; padding: 10px 14px; }
.msg-reasoning summary { cursor: pointer; color: #606266; font-size: 12px; font-weight: 500; user-select: none; outline: none; padding: 4px 0; }
.msg-reasoning details > div { color: #606266; font-size: 13px; line-height: 1.7; margin-top: 10px; }
.msg-reasoning details > div :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px; overflow-x: auto; }
.msg-reasoning details > div :deep(code) { background: #fff; padding: 2px 8px; border-radius: 4px; }

/* 消息气泡 */
.msg-bubble { padding: 14px 18px; border-radius: 14px; background: #fff; border: 1px solid #e8ecf4; font-size: 14px; line-height: 1.8; box-shadow: 0 2px 8px rgba(0,0,0,0.04); word-break: break-word; }
.msg-bubble.streaming { border-color: #667eea33; }
.msg-bubble :deep(pre) { background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: #e0e0e0; padding: 16px; border-radius: 10px; overflow-x: auto; margin: 10px 0; }
.msg-bubble :deep(code) { background: #f5f7fa; padding: 3px 8px; border-radius: 5px; font-size: 13px; color: #e83e8c; }
.msg-bubble :deep(p) { margin: 8px 0; }
.msg-bubble :deep(img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }

/* 元信息 */
.msg-meta { margin-top: 8px; display: flex; gap: 10px; font-size: 12px; color: #909399; }
.msg-meta span { background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 8px; padding: 3px 10px; }
.msg-interrupted { margin-top: 6px; font-size: 12px; color: #d97706; background: rgba(217,119,6,0.1); border: 1px solid rgba(217,119,6,0.3); border-radius: 8px; padding: 4px 10px; }

/* 光标动画 */
.cursor { animation: blink 1s infinite; color: #667eea; font-weight: bold; }
@keyframes blink { 50% { opacity: 0; } }

/* 消息操作(编辑/重试) */
.msg-actions { margin-top: 8px; display: flex; gap: 8px; }
.btn { display: inline-flex; align-items: center; gap: 4px; border: 1px solid #dcdfe6; background: #fff; color: #606266; padding: 5px 12px; border-radius: 8px; font-size: 12px; cursor: pointer; transition: all 0.2s; }
.btn:hover { border-color: #667eea; color: #667eea; background: #667eea0d; }
.btn-small { padding: 4px 10px; font-size: 12px; }
.btn-primary { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-color: transparent; color: #fff; }
.btn-primary:hover { color: #fff; opacity: 0.9; }

/* 编辑历史消息 */
.edit-box { background: #fff; border: 1px solid #667eea44; border-radius: 14px; padding: 10px; box-shadow: 0 2px 8px rgba(102,126,234,0.08); }
.edit-textarea { width: 100%; min-height: 84px; resize: vertical; border: 1px solid #dcdfe6; border-radius: 8px; background: #fafbff; color: #303133; padding: 10px 12px; font-size: 14px; line-height: 1.6; outline: none; font-family: inherit; box-sizing: border-box; }
.edit-textarea:focus { border-color: #667eea; background: #fff; }
</style>
