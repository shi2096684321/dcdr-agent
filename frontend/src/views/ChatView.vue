<template>
  <div class="chat-mode">
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <div class="sidebar-header">
        <button class="new-btn" @click="newSession">+ 新建对话</button>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起侧边栏">«</button>
      </div>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item" :class="{active: s.id===currentSessionId}" @click="switchSession(s.id)">
          <template v-if="editingId === s.id">
            <input class="rename-input" v-model="editText" v-focus
                   @click.stop @keyup.enter="saveRename(s.id)" @keyup.esc="cancelRename" @blur="saveRename(s.id)" />
          </template>
          <template v-else>
            <span class="session-title">{{ s.title }}</span>
            <button class="rename-btn" @click.stop="startRename(s)" title="重命名">✎</button>
            <button class="settings-btn" @click.stop="openSettings(s)" title="会话设置(人设/约束)">⚙</button>
            <el-dropdown class="export-dropdown" trigger="click" @command="fmt => exportSession(s, fmt)" @click.stop>
              <button class="export-btn" @click.stop title="导出对话">⬇</button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="markdown">Markdown (.md)</el-dropdown-item>
                  <el-dropdown-item command="json">JSON (.json)</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <button class="delete-btn" @click.stop="removeSession(s)" title="删除">🗑</button>
          </template>
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false" title="展开侧边栏">»</button>

    <div class="chat-area">
      <!-- 上下文警告 -->
      <div v-if="contextWarning" class="context-warning">
        <span class="cw-icon">⚠</span>
        <span class="cw-text">{{ contextWarning }}</span>
        <button class="cw-new" @click="newSession">开启新对话</button>
        <button class="cw-close" @click="contextWarning = null" title="忽略">✕</button>
      </div>

      <!-- 消息列表 -->
      <div class="messages" ref="msgBox">
        <div v-if="messages.length === 0 && !streaming" class="welcome">
          <div class="welcome-badge">
            <img :src="logoUrl" class="welcome-icon" alt="DCDR" />
          </div>
          <h2>你好，我是 DCDR</h2>
          <p>文字对话、图片理解、视频分析，也可以直接让我画</p>
          <div class="quick-actions">
            <button class="quick-card" @click="quickSend('帮我分析这张图片')">
              <svg class="quick-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="3.2" y="4.4" width="17.6" height="15.2" rx="2.6" stroke="currentColor" stroke-width="1.7"/>
                <circle cx="8.8" cy="9.8" r="1.7" stroke="currentColor" stroke-width="1.7"/>
                <path d="M3.6 16.8L8.4 12.6L13 16.8L16.4 14L20.4 17.4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span class="quick-text">
                <b>图片理解</b>
                <i>上传截图或照片</i>
              </span>
            </button>
            <button class="quick-card" @click="quickSend('描述这个视频的内容')">
              <svg class="quick-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="3.2" y="5.4" width="13.4" height="13.2" rx="2.4" stroke="currentColor" stroke-width="1.7"/>
                <path d="M16.6 10.4L20.4 8.2V15.8L16.6 13.6" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
                <path d="M6.8 9.4H11.4M6.8 12.2H10" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>
              </svg>
              <span class="quick-text">
                <b>视频分析</b>
                <i>逐帧理解与摘要</i>
              </span>
            </button>
            <button class="quick-card" @click="quickSend('帮我创作一张图片')">
              <svg class="quick-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 3.4L13.6 8.8L19 10.4L13.6 12L12 17.4L10.4 12L5 10.4L10.4 8.8L12 3.4Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
                <path d="M18.2 15.6L18.9 17.7L21 18.4L18.9 19.1L18.2 21.2L17.5 19.1L15.4 18.4L17.5 17.7L18.2 15.6Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
              </svg>
              <span class="quick-text">
                <b>AI 绘图</b>
                <i>用一句话生成图片</i>
              </span>
            </button>
          </div>
          <div class="welcome-hint mono">Enter 发送 · Shift + Enter 换行 · 支持图片与视频附件</div>
        </div>

        <!-- 已持久化消息项 -->
        <MessageBubble
          v-for="msg in messages" :key="msg.id"
          :msg="msg" :streaming="streaming"
          :editing="editingSeq === msg.seq"
          v-model:editBuffer="editBuffer"
          @start-edit="startEdit"
          @save-edit="saveEdit"
          @cancel-edit="cancelEdit"
          @retry="retry"
          @preview-image="previewImage"
          @preview-code="openCodePreview"
          @feedback="onFeedbackChange"
        />

        <!-- 流式输出中(仅当当前会话正是该流归属会话时展示; 切走则不显示, 返回继续) -->
        <div v-if="streaming && streamSessionId === currentSessionId" class="msg-item msg-assistant">
          <div class="msg-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
          <div class="msg-content">
            <div class="msg-role">DCDR</div>
            <div v-if="reasoningContent" class="msg-reasoning">
              <details open>
                <summary>💭 推理过程</summary>
                <div v-html="renderReasoning(reasoningContent)" @click="onCodeClick"></div>
              </details>
            </div>
            <div v-if="streamImages.length" class="msg-images">
              <div v-for="(img, idx) in streamImages" :key="idx" class="msg-image-wrapper">
                <img :src="img" :alt="'生成的图片'" class="msg-image"
                     @click="previewImage(img)" loading="lazy" />
              </div>
            </div>
            <div v-if="pipeline" class="search-pipeline">
              <div class="pipeline-head">
                <span class="pipeline-icon">🌐</span>
                <span class="pipeline-title">联网搜索流水线</span>
                <span v-if="pipeline.done" class="pipeline-done">完成</span>
              </div>
              <div class="pipeline-steps">
                <div class="pstep" :class="{ active: pipeline.stage >= 1, done: pipeline.stage > 1 }">
                  <span class="pstep-no">1</span>
                  <span class="pstep-text"><b>搜索与抓取</b><i>实时检索多个资讯源并抓取页面</i></span>
                </div>
                <div class="pstep" :class="{ active: pipeline.stage >= 2, done: pipeline.stage > 2 }">
                  <span class="pstep-no">2</span>
                  <span class="pstep-text"><b>内容筛选与提取</b><i>提取正文、剔除广告噪音、合并去重</i></span>
                </div>
                <div class="pstep" :class="{ active: pipeline.stage >= 3, done: pipeline.stage >= 3 && pipeline.done }">
                  <span class="pstep-no">3</span>
                  <span class="pstep-text"><b>多源整合与生成</b><i>按主题重新编排，生成结构化回答</i></span>
                </div>
              </div>
            </div>
            <div v-else-if="statusHint" class="msg-status">
              <span class="status-dot"></span>{{ statusHint }}
            </div>
            <!-- 工具调用过程(实时): 收到 tool_call 块即累积展示, 无需刷新页面 -->
            <div v-if="streamToolInvocations.length" class="tool-invoke">
              <button class="tool-invoke-toggle" @click="streamToolOpen = !streamToolOpen">
                <span class="tool-icon">🔧</span>
                <span>已调用 {{ streamToolInvocations.length }} 个工具</span>
                <span class="tool-caret">{{ streamToolOpen ? '▲' : '▼' }}</span>
              </button>
              <div v-if="streamToolOpen" class="tool-invoke-list">
                <div v-for="(t, i) in streamToolInvocations" :key="t.id || i" class="tool-item">
                  <div class="tool-item-head">
                    <span class="tool-badge">{{ t.name || 'tool' }}</span>
                    <span v-if="t.id" class="tool-id">{{ t.id }}</span>
                  </div>
                  <pre v-if="t.arguments" class="tool-args">{{ t.arguments }}</pre>
                </div>
              </div>
            </div>
            <!-- 等待首个内容块期间的思考动画: 覆盖准备阶段(记忆召回/知识库检索/图片识别/路由)与首 token 等待 -->
            <div v-if="streamContent || reasoningContent" class="msg-bubble streaming" v-html="renderStreamBubble(streamContent)" @click="onCodeClick"></div>
            <div v-else-if="!statusHint && !pipeline" class="msg-thinking">
              <span class="think-dots"><i></i><i></i><i></i></span>
              <span class="think-label">思考中<template v-if="thinkSeconds > 0">（{{ thinkSeconds }}s）</template></span>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <!-- 已选图片预览 -->
        <div v-if="selectedFiles.length" class="selected-files">
          <div v-for="(file, idx) in selectedFiles" :key="idx" class="selected-file">
            <div class="file-preview">
              <img v-if="isImage(file)" :src="file.preview" alt="preview" />
              <div v-else class="file-icon">📁</div>
              <button class="remove-file" @click="removeFile(idx)">✕</button>
            </div>
            <span class="file-name">{{ file.file.name }}</span>
          </div>
        </div>

        <div class="input-box">
          <textarea v-model="input" placeholder="输入消息，Enter 发送，Shift + Enter 换行"
                    @keydown.enter.exact.prevent="send" rows="1"
                    :style="{ maxHeight: selectedFiles.length ? '80px' : '160px' }"></textarea>
          <div class="input-foot">
            <div class="input-actions">
            <!-- 文生图按钮 -->
            <button class="action-btn" title="文生图（AI 绘图）：请先在输入框填写图片描述" @click="sendImage"
                    :disabled="streaming">🎨</button>
            <!-- 联网搜索开关 -->
            <button class="action-btn" :class="{active: webSearch}" title="联网搜索：开启后优先调用联网检索获取实时/权威信息"
                    @click="$emit('update:webSearch', !webSearch)" :disabled="streaming">🌐</button>
            <!-- 深度思考开关(仅思考型模型生效): 关闭时跳过思考直接作答, 更快更省 -->
            <button class="action-btn" :class="{active: thinkingEnabled}" title="深度思考：开启后模型先思考再作答（仅思考型模型生效），关闭更快更省"
                    @click="$emit('update:thinkingEnabled', !thinkingEnabled)" :disabled="streaming">🧠</button>
            <!-- 图片上传按钮 -->
            <label class="action-btn" title="上传图片/视频">
              <input type="file" ref="fileInput" @change="handleFileSelect" accept="image/*,video/*" multiple hidden />
              📎
            </label>
            <!-- 知识库选择(RAG 检索增强): 登录用户可见; 显式选择=强意图, 后端走强制检索(命中带引用/未命中明说)。
                 普通用户仅显示自己的 USER 级库(PLATFORM 级是管理员统一配置的无感增强, 不出现在选择器);
                 admin 可见全部(可显式选平台库强制检索) -->
            <el-select v-if="authStore.isLoggedIn" v-model="selectedKbIds" multiple collapse-tags collapse-tags-tooltip
                       :max-collapse-tags="1" placeholder="知识库" size="small"
                       class="kb-select" :class="{active: selectedKbIds.length > 0}"
                       :disabled="streaming" :loading="kbLoading"
                       title="选择知识库后, 每轮对话会自动从知识库检索相关内容增强回答"
                       @visible-change="onKbDropdownOpen">
              <el-option v-for="kb in selectableKnowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
            </el-select>
            <!-- V3.1 技能(手动指定执行): 选中后其正文会直接注入本轮上下文, 不再等模型自行判断是否加载。
                 只列"已启用"的技能 —— 停用/不可见的技能后端会拒绝注入(见 SkillCatalogProvider#skillPrompts),
                 列出来只会让人以为选中就生效了。留空 = 回到默认的"模型按需加载"。 -->
            <el-select v-if="authStore.isLoggedIn" v-model="selectedSkillNames" multiple collapse-tags collapse-tags-tooltip
                       :max-collapse-tags="1" placeholder="技能" size="small"
                       class="skill-select" :class="{active: selectedSkillNames.length > 0}"
                       :disabled="streaming" :loading="skillLoading"
                       title="手动指定技能：选中后强制把其正文加入本轮上下文并按它执行；留空则由模型自行判断是否需要"
                       @visible-change="onSkillDropdownOpen">
              <el-option v-for="s in availableSkills" :key="s.name"
                         :label="s.displayName || s.name" :value="s.name" />
            </el-select>
            <!-- V2.3 Agent 选择: 登录用户可见, 选择后对话使用该 Agent 的角色/模型/工具配置 -->
            <el-select v-if="authStore.isLoggedIn" v-model="selectedAgentId" clearable
                       placeholder="专家" size="small"
                       class="agent-select" :class="{active: !!selectedAgentId}"
                       :disabled="streaming" :loading="agentLoading"
                       title="选择自定义专家后, 对话将使用其角色设定/模型/工具/知识库配置"
                       @visible-change="onAgentDropdownOpen">
              <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
            </el-select>
          </div>
            <div class="input-meta mono">
              {{ messages.length }} 条 · {{ selectedKbIds.length ? selectedKbIds.length + ' 库' : '无知识库' }} · Enter 发送
            </div>
            <button class="send-btn gradient-bg" @click="streaming ? stop() : send()"
                    :disabled="(!input.trim() && !selectedFiles.length) && !streaming">
              {{ streaming ? '■' : '➤' }}
            </button>
          </div>
        </div>
        <div class="input-hint">支持上传图片、视频进行对话分析</div>
      </div>
    </div>

    <!-- 右侧上下文面板：只呈现真实状态，不放占位内容。
         「工具 / 链路追踪」需要接 ToolRegistry 与 trace 数据，属下一阶段（B），
         这里不做假 tab。 -->
    <aside v-if="panelOpen" class="context-panel">
      <div class="ctx-head">
        <span class="ctx-title">上下文</span>
        <button class="ctx-collapse" @click="panelOpen = false" title="收起面板">»</button>
      </div>

      <div class="ctx-card">
        <div class="ctx-card-title">运行参数</div>
        <div class="ctx-row">
          <span class="ctx-k">模型</span>
          <span class="ctx-v mono">{{ currentModel || '未选择' }}</span>
        </div>
        <div class="ctx-row">
          <span class="ctx-k">联网搜索</span>
          <span class="ctx-v" :class="{ on: webSearch }">{{ webSearch ? '已开启' : '关闭' }}</span>
        </div>
        <div class="ctx-row">
          <span class="ctx-k">深度思考</span>
          <span class="ctx-v" :class="{ on: thinkingEnabled }">{{ thinkingEnabled ? '已开启' : '关闭' }}</span>
        </div>
        <div class="ctx-row">
          <span class="ctx-k">对话消息</span>
          <span class="ctx-v mono">{{ messages.length }} 条</span>
        </div>
      </div>

      <div class="ctx-card">
        <div class="ctx-card-title">本轮增强</div>
        <div class="ctx-row">
          <span class="ctx-k">专家</span>
          <span class="ctx-v">{{ currentAgentName || '默认' }}</span>
        </div>
        <div class="ctx-row">
          <span class="ctx-k">知识库</span>
          <span class="ctx-v mono">{{ selectedKbIds.length ? selectedKbIds.length + ' 库' : '未选择' }}</span>
        </div>
        <div v-if="selectedKbNames.length" class="ctx-list">
          <span v-for="n in selectedKbNames" :key="n" class="ctx-chip">{{ n }}</span>
        </div>
        <div class="ctx-row">
          <span class="ctx-k">技能</span>
          <span class="ctx-v mono">{{ selectedSkillNames.length ? selectedSkillNames.length + ' 个' : '未选择' }}</span>
        </div>
        <div v-if="selectedSkillNames.length" class="ctx-list">
          <span v-for="n in selectedSkillNames" :key="n" class="ctx-chip">{{ n }}</span>
        </div>
      </div>

      <div class="ctx-foot mono">
        {{ currentSessionId ? '会话 #' + currentSessionId : '新会话 · 尚未保存' }}
      </div>
    </aside>
    <button v-else class="panel-expand" @click="panelOpen = true" title="展开上下文面板">«</button>

    <!-- 会话设置弹窗: 定制会话角色设定(人设/偏好), 由平台包装后转述给模型, 不覆盖平台系统约束 -->
    <el-dialog v-model="settingsVisible" title="会话设置 · 角色设定" width="560px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="角色设定">
          <el-input v-model="settingsPrompt" type="textarea" :rows="6"
                    placeholder="为当前会话设定角色与偏好，例如：回答先给结论、用中文、不超过 200 字。它将由平台转述给模型，不会覆盖平台的安全与规则约束。留空保存可清除。" />
        </el-form-item>
      </el-form>
      <div class="settings-tip">提示：仅对当前会话生效，不影响其他会话；与平台规则冲突时以平台规则为准。</div>
      <template #footer>
        <el-button @click="settingsVisible = false">取消</el-button>
        <el-button @click="clearSettings" v-if="settingsPrompt">清除</el-button>
        <el-button type="primary" :loading="settingsSaving" @click="saveSettings">保存</el-button>
      </template>
    </el-dialog>

    <!-- 图片预览弹窗 -->
    <ImagePreviewModal v-model:url="previewImageUrl" />

    <!-- 代码运行预览弹窗(沙箱 iframe) -->
    <CodePreviewModal
      v-model:visible="codePreview.visible"
      :code="codePreview.code"
      :lang="codePreview.lang"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, watch, onUnmounted } from 'vue'
import {
  getSessions, createSession, getMessages, deleteMessages,
  streamChat, streamChatImage, stopChat,
  renameSession, updateSessionPersona, getSession, deleteSession, uploadFile,
  buildExportUrl, getKnowledgeBases, getAgents, getSkills
} from '@/api'
import { ElMessageBox, ElMessage } from 'element-plus'
import logoUrl from '@/assets/dcdr-logo.png'
import MessageBubble from '@/components/MessageBubble.vue'
import ImagePreviewModal from '@/components/ImagePreviewModal.vue'
import CodePreviewModal from '@/components/CodePreviewModal.vue'
import { useMarkdown } from '@/composables/useMarkdown'
import { useCodeBlock } from '@/composables/useCodeBlock'
import { useAuthStore } from '@/stores/auth'

const { renderMarkdown, renderBubbleHtml } = useMarkdown()
// 流式正文实时渲染 Markdown(含引用角标), 与最终落库的 MessageBubble 完全一致
const renderStreamBubble = (text) => renderBubbleHtml(text, streamSources.value)
// 流式态推理过程: 代码块同样带「复制 / 运行预览」按钮
const renderReasoning = (text) => renderMarkdown(text, { codeActions: true })
// 代码块「复制 / 运行预览」: 复制就地完成, 预览弹窗由本组件统一承载
const codePreview = ref({ visible: false, code: '', lang: '' })
function openCodePreview({ code, lang }) {
  codePreview.value = { visible: true, code: code || '', lang: lang || '' }
}
const { handleCodeClick: onCodeClick } = useCodeBlock(openCodePreview)
const vFocus = { mounted: (el) => el.focus() }
const props = defineProps({ currentModel: String, webSearch: { type: Boolean, default: false }, thinkingEnabled: { type: Boolean, default: true } })
const authStore = useAuthStore()

const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
// 方案 B: 流式输出归属会话隔离。streamSessionId 记录当前流式输出归属的会话。
// 由于同一时刻最多只有一个流式(send/sendImage 均有 streaming 互斥), 全局流式展示变量
// 即代表该唯一活跃流; 切换会话时仅通过 streamSessionId===currentSessionId 控制显示,
// 不中断流, 切回该会话时继续展示, onDone 时只把回答写入归属会话。
const streamSessionId = ref(null)
const interruptedRef = ref(false)
const streamContent = ref('')
const reasoningContent = ref('')
const streamImages = ref([])
const statusHint = ref('')
// 思考中计时(秒): 等待首个内容块期间显示。准备阶段(记忆召回/知识库检索/图片识别/路由)可能持续十几秒,
// 实测路由单次可达 17~22s —— 有秒数用户才能确认"仍在工作"而不是卡死。
const thinkSeconds = ref(0)
let thinkTimer = null
watch(streaming, (active) => {
  if (thinkTimer) { clearInterval(thinkTimer); thinkTimer = null }
  thinkSeconds.value = 0
  if (active) {
    thinkTimer = setInterval(() => { thinkSeconds.value += 1 }, 1000)
  }
})
onUnmounted(() => {
  if (thinkTimer) clearInterval(thinkTimer)
})
// 联网搜索三步流水线状态: null 表示非搜索; {stage:1|2|3, done:bool}
const pipeline = ref(null)
const streamSources = ref([])
// 本轮流式回答中已发生的工具调用清单(实时累积)。
// 后端把 tool_call 块实时下发, 但历史接口只以聚合的 toolInvocations 返回,
// 若不在此累积, 工具调用面板要等刷新(重新拉取历史)才出现 —— 表现为"不实时"。
const streamToolInvocations = ref([])
// 流式态工具调用面板是否展开(默认收起, 与历史消息一致)
const streamToolOpen = ref(false)
const msgBox = ref(null)
const editingId = ref(null)
const editText = ref('')
const contextWarning = ref(null)
const lastTokenUsage = ref(null)
const lastDurationMs = ref(null)

// 消息编辑/重试
const editingSeq = ref(null)
const editBuffer = ref('')

// 文件相关
const selectedFiles = ref([])
const previewImageUrl = ref(null)
const sidebarCollapsed = ref(false)

// 会话设置(会话级 System Prompt 定制)
const settingsVisible = ref(false)
const settingsSessionId = ref(null)
const settingsPrompt = ref('')
const settingsSaving = ref(false)

// 知识库选择(RAG 检索增强)
const knowledgeBases = ref([])
const selectedKbIds = ref([])
const kbLoading = ref(false)

// 选择器可选列表: 普通用户过滤掉 PLATFORM 级(平台库走后端无感 Agentic 注入, 不应被显式选择);
// admin 可见全部(可显式选平台库触发强制检索)
const selectableKnowledgeBases = computed(() =>
  authStore.isAdmin ? knowledgeBases.value : knowledgeBases.value.filter(kb => kb.visibility !== 'PLATFORM')
)

async function loadKnowledgeBases() {
  kbLoading.value = true
  try {
    knowledgeBases.value = await getKnowledgeBases()
  } catch (e) {
    console.error('加载知识库列表失败', e)
  } finally {
    kbLoading.value = false
  }
}

// 下拉首次展开时懒加载知识库列表
function onKbDropdownOpen(open) {
  if (open && knowledgeBases.value.length === 0) loadKnowledgeBases()
}

// V3.1 技能(手动指定执行): 选中的技能正文会被后端直接注入本轮上下文, 强制模型按其流程执行
const skills = ref([])
const selectedSkillNames = ref([])
const skillLoading = ref(false)

// 只列"已启用"的技能: 停用/不可见的技能后端一律拒绝注入, 列出来只会让人以为选中就生效了
const availableSkills = computed(() => skills.value.filter((s) => s.enabled))

async function loadSkills() {
  skillLoading.value = true
  try {
    skills.value = await getSkills()
  } catch (e) {
    console.error('加载技能列表失败', e)
  } finally {
    skillLoading.value = false
  }
}

// 每次展开都刷新(不像知识库那样只加载一次): 技能启用/停用改得很频繁,
// 拿一份过期的列表会让人选到已停用的技能, 然后困惑"为什么没生效"
function onSkillDropdownOpen(open) {
  if (open) loadSkills()
}

// V2.3 自定义 Agent 选择
const agents = ref([])
const selectedAgentId = ref('')
const agentLoading = ref(false)

/* 右侧上下文面板：全部读真实状态，不造数据 */
const panelOpen = ref(true)
const currentAgentName = computed(() => agents.value.find((a) => a.id === selectedAgentId.value)?.name || '')
const selectedKbNames = computed(() =>
  selectableKnowledgeBases.value.filter((k) => selectedKbIds.value.includes(k.id)).map((k) => k.name)
)

async function loadAgents() {
  agentLoading.value = true
  try {
    agents.value = await getAgents()
  } catch (e) {
    console.error('加载 Agent 列表失败', e)
  } finally {
    agentLoading.value = false
  }
}

function onAgentDropdownOpen(open) {
  if (open && agents.value.length === 0) loadAgents()
}

function isImage(file) {
  return file.file.type.startsWith('image/')
}

async function loadSessions() { sessions.value = await getSessions() }

async function newSession() {
  const s = await createSession({ title: 'New Chat', model: props.currentModel })
  sessions.value.unshift(s)
  currentSessionId.value = s.id
  messages.value = []
}

async function switchSession(id) {
  if (editingId.value) return
  currentSessionId.value = id
  messages.value = await getMessages(id)
  scrollBottom()
}

function startRename(s) {
  editingId.value = s.id
  editText.value = s.title
}

function cancelRename() {
  editingId.value = null
  editText.value = ''
}

async function saveRename(id) {
  if (editingId.value === null) return
  const title = editText.value.trim()
  editingId.value = null
  editText.value = ''
  if (!title) return
  const s = sessions.value.find(x => x.id === id)
  if (s && s.title === title) return
  await renameSession(id, title)
  await loadSessions()
}

async function removeSession(s) {
  if (streaming.value && s.id === currentSessionId.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除对话「${s.title}」吗？此操作不可恢复。`,
      '删除对话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  await deleteSession(s.id)
  sessions.value = sessions.value.filter(x => x.id !== s.id)
  if (s.id === currentSessionId.value) {
    currentSessionId.value = null
    messages.value = []
    contextWarning.value = null
  }
  ElMessage.success('已删除')
}

// ===== 会话设置: 定制会话级 System Prompt(人设/约束) =====
async function openSettings(s) {
  settingsSessionId.value = s.id
  settingsPrompt.value = ''
  try {
    const detail = await getSession(s.id)
    settingsPrompt.value = detail?.persona || ''
  } catch (e) {
    console.error('加载会话 System Prompt 失败', e)
  }
  settingsVisible.value = true
}

async function saveSettings() {
  if (!settingsSessionId.value) return
  settingsSaving.value = true
  try {
    await updateSessionPersona(settingsSessionId.value, settingsPrompt.value.trim())
    ElMessage.success('已保存，后续对话按新的角色设定生效')
    settingsVisible.value = false
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '保存失败'
    ElMessage.error(msg)
  } finally {
    settingsSaving.value = false
  }
}

async function clearSettings() {
  settingsPrompt.value = ''
  await saveSettings()
}

// 文件选择处理
function handleFileSelect(e) {
  const files = Array.from(e.target.files)
  files.forEach(file => {
    if (file.type.startsWith('image/') || file.type.startsWith('video/')) {
      const reader = new FileReader()
      reader.onload = (ev) => {
        selectedFiles.value.push({
          file,
          preview: ev.target.result,
          uploaded: false,
          url: null
        })
      }
      reader.readAsDataURL(file)
    }
  })
  e.target.value = '' // 重置以便重复选择同一文件
}

function removeFile(idx) {
  selectedFiles.value.splice(idx, 1)
}

function previewImage(url) {
  previewImageUrl.value = url
}

function quickSend(text) {
  input.value = text
  send()
}

function scrollBottom() { nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight }) }

async function send(opts = {}) {
  const overrideImages = opts.images || null
  const text = input.value.trim()
  const hasFiles = selectedFiles.value.length > 0

  if ((!text && !hasFiles && !overrideImages) || streaming.value) return
  contextWarning.value = null
  lastTokenUsage.value = null
  lastDurationMs.value = null

  if (!currentSessionId.value) await newSession()

  // 构建用户消息
  const userMsg = { id: Date.now(), role: 'user', content: text }

  // 处理文件上传
  if (hasFiles) {
    userMsg.images = []
    userMsg.videos = []

    for (const sf of selectedFiles.value) {
      try {
        if (!sf.uploaded) {
          const result = await uploadFile(sf.file)
          sf.uploaded = true
          sf.url = result.url || result
        }
        if (isImage(sf)) {
          userMsg.images.push({ url: sf.url, name: sf.file.name })
        } else {
          userMsg.videos.push({ url: sf.url, name: sf.file.name })
        }
      } catch (err) {
        console.error('文件上传失败:', err)
        ElMessage.error(`上传 ${sf.file.name} 失败`)
      }
    }
  } else if (overrideImages) {
    // 编辑/重试时复用已上传的图片(无需重新上传)
    userMsg.images = overrideImages.map(i => typeof i === 'string' ? { url: i, name: '' } : i)
    userMsg.videos = []
  }

  messages.value.push(userMsg)
  input.value = ''
  selectedFiles.value = []

  streaming.value = true
  streamSessionId.value = currentSessionId.value  // 记录该流归属会话(方案 B: 切走不显示、返回继续)
  streamContent.value = ''
  reasoningContent.value = ''
  streamImages.value = []
  streamSources.value = []
  streamToolInvocations.value = []
  streamToolOpen.value = false
  statusHint.value = ''
  pipeline.value = null
  interruptedRef.value = false
  scrollBottom()

  await streamChat(
    {
      sessionId: currentSessionId.value,
      message: text,
      model: props.currentModel,
      images: (userMsg.images || []).map(i => i.url),
      webSearch: props.webSearch,
      thinkingEnabled: props.thinkingEnabled,
      knowledgeBaseIds: selectedKbIds.value.length > 0 ? selectedKbIds.value.slice() : undefined,
      // 手动指定的技能: 后端会直接把其正文注入本轮上下文(不再等模型调 skill_load)
      skillNames: selectedSkillNames.value.length > 0 ? selectedSkillNames.value.slice() : undefined,
      agentId: selectedAgentId.value || undefined
    },
    (chunk) => {
      if (chunk.type === 'status') {
        if (pipeline.value) {
          // 三步流水线: STEP1 检索 → STEP2 提取整理 → STEP3 整合生成
          if (chunk.message.includes('抓取相关页面')) pipeline.value.stage = 1
          else if (chunk.message.includes('合并去重')) pipeline.value.stage = 2
        } else {
          statusHint.value = chunk.message || ''
        }
        scrollBottom(); return
      }
      if (chunk.type === 'error') { statusHint.value = ''; pipeline.value = null; streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { statusHint.value = ''; pipeline.value = null; interruptedRef.value = true; return }
      if (chunk.type === 'context_warning') { statusHint.value = ''; contextWarning.value = chunk.message || '对话已较长，建议开启新对话'; return }
      if (chunk.type === 'tool_call') {
        // 实时累积工具调用明细, 供"已调用 N 个工具"面板边流边展示(否则只能刷新后从历史拿到)
        streamToolInvocations.value.push({
          name: chunk.tool,
          id: chunk.id,
          arguments: chunk.args
        })
        if (chunk.tool === 'image_generation') {
          statusHint.value = '正在调用工具生成图片…'
        } else if (chunk.tool === 'web_search') {
          pipeline.value = { stage: 0, done: false }
        } else {
          statusHint.value = `正在调用工具：${chunk.tool || ''}…`
        }
        scrollBottom(); return
      }
      if (chunk.type === 'tool_result') {
        statusHint.value = ''
        if (chunk.tool === 'web_search') {
          pipeline.value = pipeline.value || { stage: 0, done: false }
          pipeline.value.stage = 3
        }
        // 联网搜索等工具回传引用来源, 累计到本次会话
        // 注意: chunk.result 是喂给模型的检索上下文(含 [[n]] 标记的来源清单),
        // 不应拼进用户可见的正文 —— 来源已通过 sources 卡片单独渲染, 重复展示会一团乱
        if (chunk.sources && chunk.sources.length) {
          streamSources.value = streamSources.value.concat(chunk.sources)
          if (chunk.tool === 'web_search' && pipeline.value) pipeline.value.done = true
        }
        scrollBottom(); return
      }
      if (chunk.type === 'image') { statusHint.value = ''; streamImages.value.push(chunk.imageUrl); scrollBottom(); return }
      if (chunk.type === 'done') {
        statusHint.value = ''
        pipeline.value = null
        // done 块可能携带最终的引用来源
        if (chunk.sources && chunk.sources.length) streamSources.value = streamSources.value.concat(chunk.sources)
        lastTokenUsage.value = chunk.tokenUsage?.total ?? null
        lastDurationMs.value = chunk.durationMs ?? null
        return
      }
      if (chunk.type !== 'chunk') return
      statusHint.value = ''
      const ok = (v) => v != null
      if (ok(chunk.reasoningContent)) { reasoningContent.value += chunk.reasoningContent; scrollBottom() }
      if (ok(chunk.content)) { streamContent.value += chunk.content; scrollBottom() }
    },
    () => {
      // 方案 B: 仅当当前展示的会话就是该流归属会话时, 才把回答写入本地 messages;
      // 若期间已切到其他会话, 不污染当前列表(返回归属会话时靠 getMessages 拉取后端已落库的完整回答)
      const stillOnOwnerSession = streamSessionId.value === currentSessionId.value
      // 本轮流式累积的工具调用清单, 随回答一起落到本地消息上(与后端 getMessages 的聚合结果结构一致)
      const roundInvocations = streamToolInvocations.value.slice()
      if (stillOnOwnerSession && (streamContent.value || reasoningContent.value || streamImages.value.length)) {
        messages.value.push({
          id: Date.now(),
          role: 'assistant',
          content: streamContent.value,
          reasoningContent: reasoningContent.value,
          images: streamImages.value.length ? streamImages.value.map(u => ({ url: u, name: '' })) : undefined,
          sources: streamSources.value.length ? streamSources.value.slice() : undefined,
          toolInvocations: roundInvocations.length ? roundInvocations : undefined,
          type: streamImages.value.length ? 'image' : 'text',
          interrupted: interruptedRef.value,
          tokenUsage: lastTokenUsage.value,
          durationMs: lastDurationMs.value
        })
      }
      streamContent.value = ''
      reasoningContent.value = ''
      streamImages.value = []
      streamToolInvocations.value = []
      streamToolOpen.value = false
      statusHint.value = ''
      pipeline.value = null
      streamSessionId.value = null
      streaming.value = false
      loadSessions()
      scrollBottom()
      // 将后端 seq 回写到本地消息, 以支持编辑/重试时按 seq 截断历史(同样仅在归属会话当前展示时)
      if (stillOnOwnerSession && currentSessionId.value) {
        getMessages(currentSessionId.value)
          .then(list => {
            // 仅当数量一致时回写后端 seq(用于编辑/重试截断历史);
            // 数量不一致(如刚中断, 本地消息尚未持久化)时保留本地消息, 避免把正在输出的内容冲掉
            if (list && list.length === messages.value.length) {
              list.forEach((m, i) => {
                if (!messages.value[i]) return
                messages.value[i].seq = m.seq
                // 一并回写 sessionId / feedback: 回答反馈以 (sessionId, seq) 定位, 缺一则无法提交
                messages.value[i].sessionId = m.sessionId
                messages.value[i].feedback = m.feedback || undefined
                // 后端聚合的工具调用清单作为兜底: 本地流式未累积到(如期间切换过会话)时补齐
                if (!messages.value[i].toolInvocations && m.toolInvocations?.length) {
                  messages.value[i].toolInvocations = m.toolInvocations
                }
              })
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamSessionId.value = null; streamContent.value = ''; reasoningContent.value = ''; statusHint.value = ''; ElMessage.error('对话请求失败：' + (e?.message || '网络异常')) }
  )
}

/** 文生图(按钮触发): 将输入框内容作为提示词, 调用 /api/chat/image 即时生成图片 */
async function sendImage() {
  const text = input.value.trim()
  if (!text) { ElMessage.warning('请先在输入框填写图片描述'); return }
  if (streaming.value) return
  contextWarning.value = null
  lastTokenUsage.value = null
  lastDurationMs.value = null
  if (!currentSessionId.value) await newSession()

  const userMsg = { id: Date.now(), role: 'user', content: text }
  messages.value.push(userMsg)
  input.value = ''
  selectedFiles.value = []

  streaming.value = true
  streamSessionId.value = currentSessionId.value  // 记录该流归属会话(方案 B)
  streamContent.value = ''
  reasoningContent.value = ''
  streamImages.value = []
  streamToolInvocations.value = []
  streamToolOpen.value = false
  statusHint.value = '正在生成图片，请稍候…'
  interruptedRef.value = false
  scrollBottom()

  await streamChatImage(
    { sessionId: currentSessionId.value, prompt: text },
    (chunk) => {
      if (chunk.type === 'status') {
        if (pipeline.value) {
          if (chunk.message.includes('抓取相关页面')) pipeline.value.stage = 1
          else if (chunk.message.includes('合并去重')) pipeline.value.stage = 2
        } else {
          statusHint.value = chunk.message || ''
        }
        scrollBottom(); return
      }
      if (chunk.type === 'tool_call') {
        streamToolInvocations.value.push({
          name: chunk.tool,
          id: chunk.id,
          arguments: chunk.args
        })
        if (chunk.tool === 'image_generation') {
          statusHint.value = '正在生成图片…'
        } else if (chunk.tool === 'web_search') {
          pipeline.value = { stage: 0, done: false }
        } else {
          statusHint.value = `正在调用工具：${chunk.tool || ''}…`
        }
        scrollBottom(); return
      }
      if (chunk.type === 'tool_result') {
        statusHint.value = ''
        if (chunk.tool === 'web_search') {
          pipeline.value = pipeline.value || { stage: 0, done: false }
          pipeline.value.stage = 3
          if (chunk.sources && chunk.sources.length) pipeline.value.done = true
        }
        // 文生图场景下的 tool_result 同样不拼正文(来源已由 sources 卡片展示)
        scrollBottom(); return
      }
      if (chunk.type === 'error') { statusHint.value = ''; streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { statusHint.value = ''; interruptedRef.value = true; return }
      if (chunk.type === 'context_warning') { statusHint.value = ''; contextWarning.value = chunk.message || ''; return }
      if (chunk.type === 'image') { statusHint.value = ''; streamImages.value.push(chunk.imageUrl); scrollBottom(); return }
      if (chunk.type === 'done') {
        statusHint.value = ''
        lastTokenUsage.value = chunk.tokenUsage?.total ?? null
        lastDurationMs.value = chunk.durationMs ?? null
        return
      }
      if (chunk.type !== 'chunk') return
      statusHint.value = ''
      if (chunk.reasoningContent) { reasoningContent.value += chunk.reasoningContent; scrollBottom() }
      if (chunk.content) { streamContent.value += chunk.content; scrollBottom() }
    },
    () => {
      // 方案 B: 仅当当前展示会话是该流归属会话时才写入本地 messages
      const stillOnOwnerSession = streamSessionId.value === currentSessionId.value
      const roundInvocations = streamToolInvocations.value.slice()
      if (stillOnOwnerSession && (streamImages.value.length || streamContent.value || reasoningContent.value)) {
        messages.value.push({
          id: Date.now(),
          role: 'assistant',
          content: streamContent.value,
          reasoningContent: reasoningContent.value,
          images: streamImages.value.length ? streamImages.value.map(u => ({ url: u, name: '' })) : undefined,
          toolInvocations: roundInvocations.length ? roundInvocations : undefined,
          type: streamImages.value.length ? 'image' : 'text',
          interrupted: interruptedRef.value,
          tokenUsage: lastTokenUsage.value,
          durationMs: lastDurationMs.value
        })
      }
      streamContent.value = ''
      reasoningContent.value = ''
      streamImages.value = []
      statusHint.value = ''
      pipeline.value = null
      streamSessionId.value = null
      streaming.value = false
      loadSessions()
      scrollBottom()
      // 回写后端 seq, 支持编辑/重试时按 seq 截断历史(仅在归属会话当前展示时)
      if (stillOnOwnerSession && currentSessionId.value) {
        getMessages(currentSessionId.value)
          .then(list => {
            if (list && list.length === messages.value.length) {
              list.forEach((m, i) => {
                if (!messages.value[i]) return
                messages.value[i].seq = m.seq
                // 一并回写 sessionId / feedback: 回答反馈以 (sessionId, seq) 定位, 缺一则无法提交
                messages.value[i].sessionId = m.sessionId
                messages.value[i].feedback = m.feedback || undefined
                // 后端聚合的工具调用清单作为兜底: 本地流式未累积到(如期间切换过会话)时补齐
                if (!messages.value[i].toolInvocations && m.toolInvocations?.length) {
                  messages.value[i].toolInvocations = m.toolInvocations
                }
              })
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamSessionId.value = null; streamContent.value = ''; reasoningContent.value = ''; streamImages.value = []; statusHint.value = ''; ElMessage.error('文生图请求失败：' + (e?.message || '网络异常')) }
  )
}

/** 中断当前会话(停止生成) */
function stop() {
  if (!streaming.value || !currentSessionId.value) return
  interruptedRef.value = true
  stopChat(currentSessionId.value)
}

/** 导出对话 (F-27): 直接触发浏览器下载, 后端返回 Content-Disposition: attachment */
function exportSession(s, format) {
  const url = buildExportUrl(s.id, format)
  // 用 a 标签触发下载(比 window.open 更可靠, 不会开新标签页)
  const a = document.createElement('a')
  a.href = url
  a.download = ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  ElMessage.success(`正在导出「${s.title}」为 ${format.toUpperCase()}`)
}

/** 进入编辑态 */
function startEdit(msg) {
  editingSeq.value = msg.seq
  editBuffer.value = msg.content || ''
}

/** 取消编辑 */
function cancelEdit() {
  editingSeq.value = null
  editBuffer.value = ''
}

/** 保存编辑后的消息: 截断该消息之后的历史并重新生成 */
async function saveEdit(msg) {
  const newContent = (editBuffer.value || '').trim()
  if (!newContent) return
  const seq = msg.seq
  const idx = messages.value.indexOf(msg)
  editingSeq.value = null
  editBuffer.value = ''
  if (seq != null) {
    try { await deleteMessages(currentSessionId.value, seq) } catch (e) { console.error('删除历史消息失败', e) }
  }
  // 截断该消息及其后的所有消息(本地)
  if (idx >= 0) messages.value = messages.value.slice(0, idx)
  input.value = newContent
  await send({ images: msg.images })
}

/** 重新生成助手回答: 移除该助手消息及其前一条用户消息之后的历史, 并重新发送用户消息 */
async function retry(msg) {
  const idx = messages.value.indexOf(msg)
  if (idx < 0) return
  const userMsg = messages.value[idx - 1]
  if (!userMsg || userMsg.role !== 'user') return
  const userSeq = userMsg.seq
  if (userSeq != null) {
    try { await deleteMessages(currentSessionId.value, userSeq) } catch (e) { console.error('删除历史消息失败', e) }
  }
  // 移除用户消息及其后的所有消息(含失败的助手消息)
  messages.value = messages.value.slice(0, idx - 1)
  input.value = userMsg.content
  await send({ images: userMsg.images })
}

/**
 * V3.1 F-48: 回答反馈提交成功后的状态回写。
 * 反馈按钮的选中态以 messages 里的 feedback 字段为准 (刷新后由后端 getMessages 回填);
 * 撤销时 rating 为 null, 写回 undefined 表示未反馈。
 */
function onFeedbackChange({ seq, rating }) {
  const m = messages.value.find(x => x.role === 'assistant' && x.seq === seq)
  if (m) m.feedback = rating || undefined
}

onMounted(() => { loadSessions() })
</script>

<style scoped>
.chat-mode { flex: 1; display: flex; overflow: hidden; height: 100%; position: relative; }

/* 侧边栏 */
.sidebar { width: 240px; background: var(--dcdr-surface-glass); backdrop-filter: blur(14px); border-right: 1px solid var(--dcdr-border); display: flex; flex-direction: column; transition: width 0.25s ease; }
.sidebar.collapsed { width: 0; min-width: 0; border-right: none; overflow: hidden; }
.sidebar-header { display: flex; align-items: center; gap: 8px; padding: 14px 12px; border-bottom: 1px solid var(--dcdr-border); }
.collapse-btn { flex: none; width: 26px; height: 26px; border: none; border-radius: 6px; background: transparent; color: var(--dcdr-text-3); cursor: pointer; font-size: 15px; line-height: 1; transition: all 0.15s; }
.collapse-btn:hover { background: var(--dcdr-hover); color: var(--dcdr-text-1); }
.sidebar-expand { position: absolute; left: 0; top: 14px; z-index: 50; width: 26px; height: 36px; border: none; border-radius: 0 8px 8px 0; background: var(--dcdr-accent); color: #fff; cursor: pointer; font-size: 16px; box-shadow: 2px 0 8px rgba(0,0,0,0.15); }
.sidebar-expand:hover { filter: brightness(1.08); }

/* ---------- 右侧上下文面板 ----------
   尺寸按设计稿：轨道 64 + 侧栏 240 + 对话 836 + 面板 300 = 1440。 */
.context-panel {
  width: 300px; flex: none; display: flex; flex-direction: column; gap: 14px;
  padding: 16px; background: var(--dcdr-surface-faint);
  border-left: 1px solid var(--dcdr-border); backdrop-filter: blur(14px);
  overflow-y: auto;
}
.ctx-head { display: flex; align-items: center; justify-content: space-between; height: 20px; }
.ctx-title { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 1.2px; color: var(--dcdr-text-3); }
.ctx-collapse {
  border: none; background: transparent; color: var(--dcdr-text-3); cursor: pointer;
  font-size: 13px; line-height: 1; padding: 3px 5px; border-radius: 6px; transition: all 0.15s;
}
.ctx-collapse:hover { background: var(--dcdr-hover); color: var(--dcdr-text-1); }

.ctx-card {
  display: flex; flex-direction: column; gap: 9px; padding: 13px;
  border-radius: var(--dcdr-radius-inner); background: var(--dcdr-surface-faint);
  border: 1px solid var(--dcdr-border);
}
.ctx-card-title { font-family: var(--dcdr-font-mono); font-size: 10px; letter-spacing: 1.2px; color: var(--dcdr-text-3); }
.ctx-row { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.ctx-k { flex: none; font-size: 11px; color: var(--dcdr-text-2); }
.ctx-v { font-size: 11px; color: var(--dcdr-text-1); text-align: right; word-break: break-all; }
.ctx-v.mono { font-family: var(--dcdr-font-mono); font-size: 10.5px; }
.ctx-v.on { color: var(--dcdr-success); }
.ctx-list { display: flex; flex-wrap: wrap; gap: 5px; }
.ctx-chip {
  font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-2);
  background: var(--dcdr-hover); border: 1px solid var(--dcdr-border);
  border-radius: 999px; padding: 2px 7px;
}
.ctx-foot { margin-top: auto; padding-top: 6px; font-size: 9.5px; color: var(--dcdr-text-3); }

.panel-expand {
  position: absolute; right: 0; top: 14px; z-index: 50; width: 26px; height: 36px;
  border: none; border-radius: 8px 0 0 8px; background: var(--dcdr-accent); color: #fff;
  cursor: pointer; font-size: 14px; box-shadow: 0 0 12px var(--dcdr-accent-wash);
}
.panel-expand:hover { filter: brightness(1.08); }

/* 窄屏自动收起：1240 以下主区再让 300 给面板，消息会被挤成一条 */
@media (max-width: 1240px) {
  .context-panel { display: none; }
  .panel-expand { display: none; }
}
.new-btn { flex: 1; min-width: 0; height: 38px; color: var(--dcdr-accent-soft); font-size: 13px; font-weight: 500; cursor: pointer; border-radius: var(--dcdr-radius-inner); border: 1px solid var(--dcdr-accent-line); background: var(--dcdr-accent-wash); transition: background 0.2s ease, border-color 0.2s ease; }
.new-btn:hover { background: var(--dcdr-active); border-color: var(--dcdr-accent); }
.session-list { flex: 1; overflow-y: auto; padding: 12px 10px; }
.session-list::-webkit-scrollbar { width: 4px; }
.session-list::-webkit-scrollbar-thumb { background: var(--dcdr-bg); border-radius: 4px; }
.session-item { padding: 12px 14px; border-radius: 10px; cursor: pointer; margin-bottom: 6px; display: flex; align-items: center; gap: 8px; transition: all 0.2s ease; }
.session-item:hover { background: var(--dcdr-bg-subtle); }
.session-item.active { background: linear-gradient(135deg, rgba(102,126,234,0.1) 0%, rgba(118,75,162,0.08) 100%); color: var(--dcdr-accent); font-weight: 500; border: 1px solid rgba(102,126,234,0.15); }
.session-title { font-size: 13px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rename-btn, .delete-btn, .export-btn, .settings-btn { border: none; background: transparent; cursor: pointer; font-size: 13px; color: var(--dcdr-text-3); opacity: 0; padding: 4px 6px; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.session-item:hover .rename-btn, .session-item:hover .delete-btn, .session-item:hover .export-btn, .session-item:hover .settings-btn { opacity: 1; }
.rename-btn:hover { color: var(--dcdr-accent); background: rgba(102,126,234,0.1); }
.settings-btn:hover { color: var(--dcdr-warning); background: rgba(230,162,60,0.1); }
.export-btn:hover { color: var(--dcdr-success); background: rgba(103,194,58,0.1); }
.delete-btn:hover { color: var(--dcdr-danger); background: rgba(245,108,108,0.1); }
.export-dropdown { flex-shrink: 0; }
.settings-tip { font-size: 12px; color: var(--dcdr-text-3); line-height: 1.5; margin-top: -8px; }
.rename-input { flex: 1; font-size: 13px; border: 2px solid var(--dcdr-accent); border-radius: 8px; padding: 6px 10px; outline: none; min-width: 0; }

/* 聊天区域 */
.chat-area { flex: 1; display: flex; flex-direction: column; background: transparent; position: relative; overflow: hidden; }
.messages { flex: 1; overflow-y: auto; padding: 24px 32px; display: flex; flex-direction: column; }
.messages::-webkit-scrollbar { width: 6px; }
.messages::-webkit-scrollbar-thumb { background: var(--dcdr-bg); border-radius: 6px; }

/* 欢迎页 */
.welcome { text-align: center; padding-top: 84px; max-width: 620px; margin: 0 auto; }
.welcome-icon { width: 64px; height: 64px; border-radius: 18px; object-fit: cover; margin-bottom: 18px; box-shadow: var(--dcdr-shadow-card), 0 0 0 1px var(--dcdr-border); }
.welcome h2 { font-size: 24px; color: var(--dcdr-text-1); margin-bottom: 8px; font-weight: 700; letter-spacing: -0.2px; }
.welcome p { color: var(--dcdr-text-2); font-size: 13px; margin-bottom: 28px; }

/* 快捷入口：线性图形 + 双行文案，不用 emoji —— emoji 在深色底上自带彩色描边，
   和整套 1.7px 线性图标不是一套语言，且各平台字形差异大。 */
.quick-actions { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
.quick-card {
  display: flex; align-items: center; gap: 10px; padding: 11px 14px; text-align: left;
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
  border-radius: 12px; cursor: pointer; font-family: inherit;
  transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}
.quick-card:hover { border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); transform: translateY(-1px); }
.quick-icon { width: 18px; height: 18px; flex: none; color: var(--dcdr-accent); }
.quick-text { display: flex; flex-direction: column; gap: 2px; }
.quick-text b { font-size: 12.5px; font-weight: 500; color: var(--dcdr-text-1); }
.quick-text i { font-size: 10.5px; font-style: normal; color: var(--dcdr-text-3); }
.welcome-hint { margin-top: 22px; font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 0.2px; color: var(--dcdr-text-3); }

/* 流式输出项(复用消息项样式) */
.msg-item { display: flex; gap: 14px; margin-bottom: 28px; max-width: 900px; margin-left: auto; margin-right: auto; width: 100%; animation: fadeInUp 0.3s ease; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.msg-avatar { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px; font-weight: 600; flex-shrink: 0; overflow: hidden; box-shadow: 0 3px 10px rgba(0,0,0,0.1); }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.msg-content { flex: 1; min-width: 0; }
.msg-role { font-size: 13px; color: var(--dcdr-text-3); margin-bottom: 6px; font-weight: 500; }
.msg-images { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.msg-image-wrapper { position: relative; }
.msg-image { max-width: 280px; max-height: 240px; border-radius: 12px; object-fit: cover; cursor: pointer; transition: all 0.25s ease; box-shadow: 0 4px 12px rgba(0,0,0,0.1); border: 1px solid var(--dcdr-border); }
.msg-image:hover { transform: scale(1.02); box-shadow: 0 6px 20px rgba(0,0,0,0.15); }
.msg-reasoning { margin-bottom: 8px; }
.msg-reasoning details { background: linear-gradient(135deg, var(--dcdr-surface) 0%, var(--dcdr-bg-subtle) 100%); border: 1px solid var(--dcdr-border); border-radius: 10px; padding: 10px 14px; }
.msg-reasoning summary { cursor: pointer; color: var(--dcdr-text-2); font-size: 12px; font-weight: 500; user-select: none; outline: none; padding: 4px 0; }
.msg-reasoning details > div { color: var(--dcdr-text-2); font-size: 13px; line-height: 1.7; margin-top: 10px; }
.msg-reasoning details > div :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px; overflow-x: auto; }
.msg-reasoning details > div :deep(code) { background: var(--dcdr-surface); padding: 2px 8px; border-radius: 4px; }
.msg-bubble { padding: 14px 18px; border-radius: 14px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); font-size: 14px; line-height: 1.8; box-shadow: 0 2px 8px rgba(0,0,0,0.04); word-break: break-word; }
.msg-bubble.streaming { border-color: var(--dcdr-accent-wash); }
.msg-bubble :deep(pre) { background: var(--dcdr-code-bg); color: var(--dcdr-code-fg); padding: 16px; border-radius: 10px; overflow-x: auto; margin: 10px 0; }
.msg-bubble :deep(code) { background: var(--dcdr-bg-subtle); padding: 3px 8px; border-radius: 5px; font-size: 13px; color: var(--dcdr-accent-2); }
.msg-bubble :deep(p) { margin: 8px 0; }
.msg-bubble :deep(img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }
/* 代码块: 语言标签 + 复制 / 运行预览 工具条(与 MessageBubble 内的历史消息保持一致) */
.msg-bubble :deep(.code-block), .msg-reasoning details > div :deep(.code-block) { margin: 10px 0; border-radius: 11px; overflow: hidden; border: 1px solid var(--dcdr-code-border); }
.msg-bubble :deep(.code-block pre), .msg-reasoning details > div :deep(.code-block pre) { margin: 0; border-radius: 0; }
.msg-bubble :deep(.code-block-bar), .msg-reasoning details > div :deep(.code-block-bar) { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--dcdr-code-bar); border-bottom: 1px solid var(--dcdr-code-border); }
.msg-bubble :deep(.code-lang), .msg-reasoning details > div :deep(.code-lang) { font-size: 11px; color: var(--dcdr-code-dim); font-family: monospace; letter-spacing: 0.5px; }
.msg-bubble :deep(.code-actions), .msg-reasoning details > div :deep(.code-actions) { margin-left: auto; display: flex; gap: 6px; }
.msg-bubble :deep(.code-action), .msg-reasoning details > div :deep(.code-action) { border: 1px solid var(--dcdr-code-border); background: transparent; color: var(--dcdr-code-dim); font-size: 11px; line-height: 1.6; padding: 2px 10px; border-radius: 6px; cursor: pointer; transition: all 0.15s ease; }
.msg-bubble :deep(.code-action:hover), .msg-reasoning details > div :deep(.code-action:hover) { border-color: var(--dcdr-accent); color: #eaf2ff; background: rgba(46,167,255,0.18); }
.msg-bubble :deep(.code-action.is-done), .msg-reasoning details > div :deep(.code-action.is-done) { border-color: var(--dcdr-success); color: var(--dcdr-code-ok); }
.msg-status { display: inline-flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 12px; background: var(--dcdr-accent-wash); color: var(--dcdr-accent); font-size: 14px; }
.msg-status .status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--dcdr-accent); animation: statusPulse 1s ease-in-out infinite; }
@keyframes statusPulse { 0%, 100% { opacity: 0.3; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1.2); } }
/* 流式态工具调用面板(样式与 MessageBubble 内的历史消息面板保持一致) */
.tool-invoke { margin-top: 8px; border: 1px solid var(--dcdr-border); border-radius: 10px; background: var(--dcdr-surface); overflow: hidden; }
.tool-invoke-toggle { width: 100%; display: flex; align-items: center; gap: 6px; padding: 7px 12px; background: transparent; border: none; cursor: pointer; font-size: 12px; color: var(--dcdr-text-2); text-align: left; }
.tool-invoke-toggle:hover { background: var(--dcdr-bg-subtle); }
.tool-invoke .tool-icon { font-size: 13px; }
.tool-invoke .tool-caret { margin-left: auto; color: var(--dcdr-text-3); }
.tool-invoke-list { padding: 0 12px 10px; display: flex; flex-direction: column; gap: 8px; }
.tool-invoke .tool-item { border: 1px solid var(--dcdr-border); border-radius: 8px; background: var(--dcdr-surface); padding: 8px 10px; }
.tool-invoke .tool-item-head { display: flex; align-items: center; gap: 8px; }
.tool-invoke .tool-badge { font-size: 12px; font-weight: 600; color: var(--dcdr-accent); background: var(--dcdr-accent-wash); border-radius: 6px; padding: 2px 8px; }
.tool-invoke .tool-id { font-size: 11px; color: var(--dcdr-text-3); font-family: monospace; }
.tool-invoke .tool-args { margin: 6px 0 0; font-size: 11px; line-height: 1.5; color: var(--dcdr-text-2); background: var(--dcdr-surface); border-radius: 6px; padding: 6px 8px; white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow: auto; }
/* 联网搜索三步流水线面板 */
.search-pipeline { background: linear-gradient(135deg, var(--dcdr-surface) 0%, var(--dcdr-bg-subtle) 100%); border: 1px solid var(--dcdr-border); border-radius: 14px; padding: 14px 16px; margin-bottom: 4px; }
.pipeline-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.pipeline-icon { font-size: 16px; }
.pipeline-title { font-weight: 700; color: var(--dcdr-text-1); font-size: 14px; }
.pipeline-done { margin-left: auto; font-size: 11px; color: var(--dcdr-success); background: #2e9e6b1a; padding: 2px 10px; border-radius: 20px; font-weight: 600; }
.pipeline-steps { display: flex; flex-direction: column; gap: 10px; }
.pstep { display: flex; gap: 12px; align-items: flex-start; opacity: 0.45; transition: all 0.3s ease; }
.pstep.active, .pstep.done { opacity: 1; }
.pstep-no { flex-shrink: 0; width: 26px; height: 26px; border-radius: 50%; background: var(--dcdr-surface-2); color: var(--dcdr-text-1); font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; transition: all 0.3s; }
.pstep.active .pstep-no { background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); box-shadow: 0 0 0 4px var(--dcdr-accent-wash); }
.pstep.done .pstep-no { background: linear-gradient(135deg, #34d399 0%, #059669 100%); }
.pstep-text { display: flex; flex-direction: column; line-height: 1.35; }
.pstep-text b { font-size: 13.5px; color: var(--dcdr-text-1); }
.pstep-text i { font-size: 11.5px; color: var(--dcdr-text-3); font-style: normal; }
/* 思考中动画: 等待首个内容块期间显示(替代原先的单个闪烁光标)。
   三个圆点依次弹跳 + 文字渐变扫光, 让"模型正在工作"这件事在长时间准备阶段(记忆召回/检索/路由)可见 */
.msg-thinking { display: inline-flex; align-items: center; gap: 10px; padding: 10px 16px; border-radius: 12px; background: var(--dcdr-accent-wash); border: 1px solid var(--dcdr-accent-wash); }
.think-dots { display: inline-flex; gap: 4px; }
.think-dots i { width: 7px; height: 7px; border-radius: 50%; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); animation: thinkBounce 1.2s ease-in-out infinite; }
.think-dots i:nth-child(2) { animation-delay: 0.15s; }
.think-dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes thinkBounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-5px); opacity: 1; }
}
.think-label { font-size: 13px; background: linear-gradient(90deg, #9aa7c2 0%, var(--dcdr-accent) 50%, #9aa7c2 100%); background-size: 200% 100%; -webkit-background-clip: text; background-clip: text; color: transparent; animation: thinkShimmer 2s linear infinite; }
@keyframes thinkShimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* 输入区域 */
.input-area { padding: 16px 28px 20px; background: linear-gradient(180deg, transparent 0%, var(--dcdr-bg) 70%); }

/* 已选文件预览 */
.selected-files { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; max-width: 844px; margin-left: auto; margin-right: auto; }
.selected-file { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.file-preview { position: relative; width: 72px; height: 72px; border-radius: 10px; overflow: hidden; border: 2px solid var(--dcdr-accent-wash); background: var(--dcdr-bg-subtle); }
.file-preview img { width: 100%; height: 100%; object-fit: cover; }
.file-icon { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; font-size: 28px; }
.remove-file { position: absolute; top: 2px; right: 2px; width: 18px; height: 18px; border-radius: 50%; border: none; background: rgba(0,0,0,0.5); color: #fff; font-size: 10px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.remove-file:hover { background: var(--dcdr-danger); }
.file-name { font-size: 10px; color: var(--dcdr-text-3); max-width: 72px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; }

/* 输入框 */
.input-box { max-width: 780px; margin: 0 auto; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-card); padding: 12px 14px 10px; display: flex; flex-direction: column; gap: 10px; box-shadow: var(--dcdr-shadow-card); transition: border-color 0.2s ease, box-shadow 0.2s ease; }
.input-box:focus-within { border-color: var(--dcdr-accent); box-shadow: 0 4px 25px rgba(102,126,234,0.15); }
.input-foot { display: flex; align-items: center; gap: 10px; }
.input-actions { display: flex; flex-wrap: wrap; gap: 6px; min-width: 0; }
.input-meta { margin-left: auto; font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-3); white-space: nowrap; }
.action-btn { display: flex; align-items: center; justify-content: center; width: 36px; height: 36px; border-radius: 10px; cursor: pointer; font-size: 18px; transition: all 0.2s; color: var(--dcdr-text-2); background: var(--dcdr-bg-subtle); border: none; }
.action-btn:hover { background: var(--dcdr-accent-wash); color: var(--dcdr-accent); }
.action-btn.active { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); color: var(--dcdr-accent); box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
/* 知识库多选下拉: 与 action-btn 同高, 选中时高亮 */
.kb-select { width: 130px; flex-shrink: 0; }
.kb-select :deep(.el-select__wrapper) { min-height: 36px; border-radius: 10px; background: var(--dcdr-bg-subtle); box-shadow: none; border: 1px solid transparent; transition: all 0.2s; }
.kb-select :deep(.el-select__wrapper:hover) { background: var(--dcdr-accent-wash); }
.kb-select.active :deep(.el-select__wrapper) { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
.kb-select :deep(.el-select__placeholder) { font-size: 13px; color: var(--dcdr-text-2); }
.kb-select :deep(.el-select__tags-text) { font-size: 12px; }
/* V3.1 技能选择下拉: 复用 kb-select 样式 */
.skill-select { width: 116px; flex-shrink: 0; }
.skill-select :deep(.el-select__wrapper) { min-height: 36px; border-radius: 10px; background: var(--dcdr-bg-subtle); box-shadow: none; border: 1px solid transparent; transition: all 0.2s; }
.skill-select :deep(.el-select__wrapper:hover) { background: var(--dcdr-accent-wash); }
.skill-select.active :deep(.el-select__wrapper) { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
.skill-select :deep(.el-select__placeholder) { font-size: 13px; color: var(--dcdr-text-2); }
.skill-select :deep(.el-select__tags-text) { font-size: 12px; }
/* V2.3 Agent 选择下拉: 复用 kb-select 样式 */
.agent-select { width: 120px; flex-shrink: 0; }
.agent-select :deep(.el-select__wrapper) { min-height: 36px; border-radius: 10px; background: var(--dcdr-bg-subtle); box-shadow: none; border: 1px solid transparent; transition: all 0.2s; }
.agent-select :deep(.el-select__wrapper:hover) { background: var(--dcdr-accent-wash); }
.agent-select.active :deep(.el-select__wrapper) { background: linear-gradient(135deg, rgba(102,126,234,0.18) 0%, rgba(118,75,162,0.15) 100%); box-shadow: inset 0 0 0 1px rgba(102,126,234,0.35); }
.agent-select :deep(.el-select__placeholder) { font-size: 13px; color: var(--dcdr-text-2); }
.input-box textarea { width: 100%; border: none; outline: none; resize: none; font-size: 13px; line-height: 1.65; max-height: 160px; font-family: inherit; padding: 2px 2px 0; background: transparent; color: var(--dcdr-text-1); }
.send-btn { flex: none; width: 34px; height: 34px; border: none; border-radius: var(--dcdr-radius-pill); color: #fff; cursor: pointer; font-size: 16px; display: flex; align-items: center; justify-content: center; transition: all 0.25s ease; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); box-shadow: 0 4px 12px rgba(102,126,234,0.35); }
.send-btn:hover:not(:disabled) { transform: scale(1.05); box-shadow: 0 6px 18px rgba(102,126,234,0.45); }
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }

.input-hint { text-align: center; font-size: 12px; color: var(--dcdr-text-3); margin-top: 8px; }

/* 上下文警告 */
.context-warning { max-width: 900px; margin: 12px auto 0; display: flex; align-items: center; gap: 10px; padding: 12px 16px; background: linear-gradient(135deg, var(--dcdr-surface) 0%, var(--dcdr-surface) 100%); border: 1px solid var(--dcdr-border); border-radius: 12px; font-size: 13px; color: var(--dcdr-warning); animation: fadeInUp 0.3s ease; }
.context-warning .cw-icon { font-size: 16px; }
.context-warning .cw-text { flex: 1; line-height: 1.5; }
.context-warning .cw-new { border: none; background: linear-gradient(135deg, #fa8c16 0%, #ff9c1a 100%); color: #fff; padding: 6px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 500; flex-shrink: 0; transition: all 0.2s; }
.context-warning .cw-new:hover { box-shadow: 0 3px 10px rgba(250,140,22,0.35); }
.context-warning .cw-close { border: none; background: transparent; color: var(--dcdr-warning); cursor: pointer; font-size: 16px; padding: 4px 8px; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.context-warning .cw-close:hover { background: var(--dcdr-bg-subtle); border-radius: 6px; }
</style>
