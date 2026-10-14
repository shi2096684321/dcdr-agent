<template>
  <HubShell :tabs="TABS" v-model:tab="tab">
    <!-- 从工作流列表点「编辑 DAG」时切到编排页, 并带上目标工作流 id。
         这个 id 现在只在容器内部传递 —— 以前它要穿过 App.vue 才能到达 DagEditorView -->
    <WorkflowView v-if="tab === 'workflow'" @open-dag="openDag" />
    <DagEditorView v-else-if="tab === 'dag'" :initial-workflow-id="dagTargetId" />
    <ScheduledTaskView v-else-if="tab === 'schedule'" />
  </HubShell>
</template>

<script setup>
import { ref } from 'vue'
import HubShell from '@/components/HubShell.vue'
import WorkflowView from '@/views/WorkflowView.vue'
import DagEditorView from '@/views/DagEditorView.vue'
import ScheduledTaskView from '@/views/ScheduledTaskView.vue'

/**
 * 自动化工作台。
 * <p>
 * 三页的共同点是"按某种方式自动跑起来并留下执行记录": 工作流是显式编排、DAG 是可视化编排、
 * 定时任务是时间触发。以前它们在顶栏占三格(其中 DAG 还只是个"从工作流跳进去"的落点),
 * 现在收敛成一个入口 —— 顺带把跨页状态 dagTargetId 关回容器内。
 */
const TABS = [
  { key: 'workflow', label: '工作流', hint: '步骤式 / DAG 工作流的定义、执行、历史与 Webhook' },
  { key: 'dag', label: 'DAG 编排', hint: '拖拽节点 + 连线, 可视化编排多专家协作流程' },
  { key: 'schedule', label: '定时任务', hint: '按 Cron 定期触发专家执行' }
]

const tab = ref('workflow')
/** 从工作流列表带过来的目标工作流 id; 为空时 DAG 编排页自行展示 */
const dagTargetId = ref('')

function openDag(id) {
  dagTargetId.value = id || ''
  tab.value = 'dag'
}
</script>
