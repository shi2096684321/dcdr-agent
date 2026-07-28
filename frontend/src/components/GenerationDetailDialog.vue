<template>
  <Teleport to="body">
    <div v-if="detailGen" class="img-viewer" @click.self="$emit('close')">
      <button class="viewer-close" @click="$emit('close')">&times;</button>
      <div class="viewer-stage">
        <img :src="detailGen.resultUrl" class="viewer-img" />
      </div>
      <div class="viewer-panel">
        <div class="vp-head">
          <p class="vp-title">{{ detailGen.prompt || '无提示词' }}</p>
          <span class="vp-tag">{{ detailGen.status === 'completed' ? '已完成' : detailGen.status }}</span>
        </div>
        <div class="vp-section">
          <span class="vp-label">提示词</span>
          <pre class="vp-prompt">{{ detailGen.prompt }}</pre>
        </div>
        <div v-if="detailGen.paramsJson" class="vp-section">
          <span class="vp-label">生成参数</span>
          <div class="vp-grid">
            <div class="vp-cell"><span>尺寸</span><b>{{ detailGen.size || '-' }}</b></div>
            <div class="vp-cell"><span>风格</span><b>{{ styleLabel(parseParam('style')) }}</b></div>
            <div class="vp-cell"><span>数量</span><b>{{ parseParam('n') || 1 }}</b></div>
            <div class="vp-cell"><span>随机种子</span><b>{{ parseParam('seed') != null ? parseParam('seed') : '-' }}</b></div>
            <div class="vp-cell"><span>模型</span><b>{{ detailGen.model || '-' }}</b></div>
            <div class="vp-cell"><span>智能改写</span><b>{{ parseParam('promptExtend') ? '开' : '关' }}</b></div>
            <div class="vp-cell"><span>水印</span><b>{{ parseParam('watermark') ? '开' : '关' }}</b></div>
          </div>
        </div>
        <div v-if="parseParam('negativePrompt')" class="vp-section">
          <span class="vp-label">反向提示词</span>
          <pre class="vp-prompt">{{ parseParam('negativePrompt') }}</pre>
        </div>
        <div class="vp-section">
          <span class="vp-label">元信息</span>
          <div class="vp-grid">
            <div class="vp-cell"><span>耗时</span><b>{{ formatDuration(detailGen.durationMs) }}</b></div>
            <div class="vp-cell"><span>创建时间</span><b>{{ formatDate(detailGen.createdAt) }}</b></div>
          </div>
        </div>
        <div class="vp-actions">
          <button class="vp-btn ghost" @click="onApplyParams">套用参数</button>
        </div>
        <a class="vp-download" :href="detailGen.resultUrl" download>⬇ 下载图片</a>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { formatDuration, formatDate, styleLabel } from '@/utils/format'

const props = defineProps({
  detailGen: { type: Object, default: null }
})
const emit = defineEmits(['close', 'apply-params'])

/** 从 paramsJson 中解析参数 */
function parseParam(key) {
  if (!props.detailGen?.paramsJson) return null
  try {
    const params = JSON.parse(props.detailGen.paramsJson)
    return params[key] ?? null
  } catch { return null }
}

/** 套用参数: 解析后回传给父组件, 由父组件赋值到控制面板状态 */
function onApplyParams() {
  const gen = props.detailGen
  if (!gen) return
  try {
    const params = gen.paramsJson ? JSON.parse(gen.paramsJson) : {}
    emit('apply-params', { gen, params })
  } catch (e) {
    console.error('解析参数失败', e)
  }
}
</script>

<style scoped>
.img-viewer {
  position: fixed; inset: 0; z-index: 200;
  background: rgba(8, 9, 14, 0.82); display: flex;
  backdrop-filter: blur(12px);
  animation: fadein 0.25s ease;
}
@keyframes fadein { from { opacity: 0; } to { opacity: 1; } }
.viewer-close {
  position: absolute; top: 18px; right: 18px; z-index: 210;
  width: 42px; height: 42px; border-radius: 50%;
  background: rgba(255, 255, 255, 0.1); border: 1px solid rgba(255,255,255,0.08);
  color: #fff; font-size: 20px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s ease;
}
.viewer-close:hover { background: rgba(255, 255, 255, 0.2); transform: rotate(90deg); }
.viewer-stage { flex: 1; display: flex; align-items: center; justify-content: center; overflow: hidden; cursor: zoom-out; padding: 24px; }
.viewer-img {
  max-width: 90%; max-height: 90%; object-fit: contain; border-radius: 12px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.7);
  transition: transform 0.12s ease-out;
  animation: fadein 0.3s ease;
}
.viewer-panel {
  width: 372px; display: flex; flex-direction: column; padding: 24px;
  overflow-y: auto; gap: 20px;
  background: rgba(38, 41, 56, 0.97); backdrop-filter: blur(24px);
  border-left: 1px solid rgba(255,255,255,0.14);
  box-shadow: -20px 0 60px rgba(0, 0, 0, 0.4);
}
.vp-head { display: flex; flex-direction: column; gap: 10px; }
.vp-title { font-size: 16px; font-weight: 600; line-height: 1.45; margin: 0; word-break: break-word; color: #f4f6fb; }
.vp-tag {
  align-self: flex-start; font-size: 11px; padding: 3px 10px; border-radius: 999px;
  background: rgba(52, 211, 153, 0.18); color: #6ee7b7;
  border: 1px solid rgba(52, 211, 153, 0.35); font-weight: 600; letter-spacing: 0.02em;
}
.vp-section { display: flex; flex-direction: column; gap: 8px; }
.vp-label { font-size: 11px; color: #c2c8d6; text-transform: uppercase; letter-spacing: 0.08em; font-weight: 700; }
.vp-prompt {
  background: rgba(0, 0, 0, 0.28); border: 1px solid rgba(255, 255, 255, 0.14); border-radius: 14px;
  padding: 13px; font-size: 13px; line-height: 1.65; color: #e8ebf3;
  white-space: pre-wrap; word-break: break-word; max-height: 160px; overflow-y: auto;
}
.vp-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.vp-cell {
  background: rgba(0, 0, 0, 0.22); border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 10px;
  padding: 11px; display: flex; flex-direction: column; gap: 4px;
  transition: all 0.18s ease;
}
.vp-cell:hover { border-color: rgba(255, 255, 255, 0.25); background: rgba(0, 0, 0, 0.32); }
.vp-cell span { font-size: 11px; color: #aeb4c4; font-weight: 500; }
.vp-cell b { font-size: 13px; font-weight: 600; color: #f4f6fb; font-family: 'SF Mono', ui-monospace, monospace; }
.vp-actions { display: flex; gap: 10px; }
.vp-btn {
  flex: 1; padding: 11px; border: 1px solid rgba(255, 255, 255, 0.16); border-radius: 14px;
  font-size: 13px; font-weight: 600; cursor: pointer; background: rgba(255, 255, 255, 0.08); color: #f4f6fb;
  transition: all 0.2s ease;
}
.vp-btn.ghost { background: rgba(139, 127, 245, 0.12); color: #b8aef9; border-color: rgba(139, 127, 245, 0.25); }
.vp-btn.ghost:hover { background: rgba(139, 127, 245, 0.22); border-color: #8b7ff5; }
.vp-download {
  display: flex; align-items: center; justify-content: center; gap: 7px; padding: 13px;
  border-radius: 14px; background: rgba(139, 127, 245, 0.12);
  color: #f4f6fb; font-weight: 700; text-decoration: none; font-size: 14px;
  border: 1px solid rgba(139, 127, 245, 0.25);
  transition: all 0.2s ease;
}
.vp-download:hover { background: rgba(139, 127, 245, 0.22); border-color: #8b7ff5; }
</style>
