<template>
  <HubShell :tabs="tabs" v-model:tab="tab">
    <MonitorView v-if="tab === 'monitor'" />
    <LlmCallLogView v-else-if="tab === 'llm-logs'" />
    <RoutingView v-else-if="tab === 'routing'" />
    <!-- 与 App.vue 原先的守卫一致: 页签已按角色过滤, 这里再兜一层, 避免非管理员落到 403 -->
    <RetrievalHealthView v-else-if="tab === 'retrieval-health' && authStore.isAdmin" />
  </HubShell>
</template>

<script setup>
import { computed, ref } from 'vue'
import HubShell from '@/components/HubShell.vue'
import MonitorView from '@/views/MonitorView.vue'
import LlmCallLogView from '@/views/LlmCallLogView.vue'
import RoutingView from '@/views/RoutingView.vue'
import RetrievalHealthView from '@/views/RetrievalHealthView.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 运维控制台。
 * <p>
 * 这几页是同一个受众(部署方)在看同一件事的四个切面 —— "线上跑成什么样":
 * 成本与执行 / 单次请求的调用链 / 路由判断准不准 / 检索有没有在漂移。
 * 注意调用链里本来就含"路由判断", 而单次请求又拆着 token 与耗时 —— 它们本就是一份数据的不同粒度。
 * <p>
 * 全是只读数据页, 没有"创建/编辑"这类破坏性动作, 所以合在一起也不会互相干扰。
 */
const TABS = [
  { key: 'monitor', label: '监控与成本', hint: '工作流执行监控 / token 成本 / 配额 / 回答反馈' },
  { key: 'llm-logs', label: '调用链', hint: '每次请求的完整调用链(路由判断 / 生成轮 / 工具步骤 / token / 耗时)' },
  { key: 'routing', label: '智能路由', hint: '路由判断结果, 用于分析准确率与调优置信度阈值' },
  // 零命中样本含用户原始提问, 属运维数据 → 仅管理员(后端亦校验)
  { key: 'retrieval-health', label: '检索健康', hint: 'RAG 检索质量的可观测性', adminOnly: true }
]

const authStore = useAuthStore()
const tabs = computed(() => TABS.filter((t) => !t.adminOnly || authStore.isAdmin))
const tab = ref('monitor')
</script>
