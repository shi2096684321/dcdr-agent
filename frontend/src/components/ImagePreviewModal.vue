<template>
  <div v-if="url" class="image-preview-modal" @click="$emit('update:url', null)">
    <img :src="url" class="preview-image" />
  </div>
</template>

<script setup>
defineProps({ url: { type: String, default: null } })
defineEmits(['update:url'])
</script>

<style scoped>
.image-preview-modal { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.85); z-index: 9999; display: flex; align-items: center; justify-content: center; cursor: pointer; animation: fadeIn 0.2s ease; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
.preview-image { max-width: 90vw; max-height: 90vh; object-fit: contain; border-radius: 8px; box-shadow: 0 20px 60px rgba(0,0,0,0.5); }
</style>
