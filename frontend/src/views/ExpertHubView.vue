<template>
  <HubShell :tabs="TABS" v-model:tab="tab">
    <ExpertView v-if="tab === 'experts'" />
    <!-- 技能 / 技能市场之间原有互相跳转的按钮, 在容器内改为切换二级页 -->
    <SkillManageView v-else-if="tab === 'skills'" @go-market="tab = 'skill-market'" />
    <SkillMarketView v-else-if="tab === 'skill-market'" @go-manage="tab = 'skills'" />
    <ExpertMarketView v-else-if="tab === 'market'" />
  </HubShell>
</template>

<script setup>
import { ref } from 'vue'
import HubShell from '@/components/HubShell.vue'
import ExpertView from '@/views/AgentView.vue'
import SkillManageView from '@/views/SkillManageView.vue'
import SkillMarketView from '@/views/SkillMarketView.vue'
import ExpertMarketView from '@/views/TemplateMarketView.vue'

/**
 * 专家工作台。
 * <p>
 * 四页本来就是一件事的两层 —— 专家是能力主体, 技能是专家按需加载的知识,
 * 市场是两者的分发处; 分成三个顶栏页签会让"去哪找"变成要先猜的事。
 * <p>
 * 顺序刻意是"我的 → 市场": 日常动作是管理自己的专家, 逛市场是低频动作。
 * <p>
 * 关于命名: 组件名与接口字段仍叫 agent(后端表、API、代码标识都没动),
 * 这里与各页面里的用户可见文案统一叫「专家」—— 只改展示, 不改数据契约。
 */
const TABS = [
  { key: 'experts', label: '我的专家', hint: '创建、编辑、克隆与停用专家(角色设定 / 模型 / 工具 / 知识库)' },
  { key: 'skills', label: '技能', hint: '专家按需加载的纯文本知识包; 可新建、导入导出、启停' },
  { key: 'skill-market', label: '技能市场', hint: '从公开技能库发现并引入技能(正文需到来源页补全)' },
  { key: 'market', label: '专家市场', hint: '发现并克隆他人分享的专家与工作流模板' }
]

const tab = ref('experts')
</script>
