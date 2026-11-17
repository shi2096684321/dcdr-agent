<template>
  <div class="chat-mode">
    <aside class="sidebar" :class="{collapsed: sidebarCollapsed}">
      <!-- 会话导航栏: 标题 + 会话数 + 收起。原来这里只有一颗"新建对话"按钮,
           没有"这是哪"的标题, 也没有搜索入口 —— 会话一多只能靠滚动找。 -->
      <div class="sidebar-header">
        <span class="sidebar-title">对话</span>
        <span v-if="sessions.length" class="sidebar-count">{{ sessions.length }}</span>
        <button class="collapse-btn" @click="sidebarCollapsed = true" title="收起侧边栏">«</button>
      </div>
      <div class="sidebar-nav">
        <button class="new-btn" @click="newSession">
          <svg class="new-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
          <span>新建对话</span>
        </button>
        <label class="session-search">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="11" cy="11" r="6.4"/><path d="M16 16l4 4"/></svg>
          <input v-model="sessionQuery" type="text" placeholder="搜索会话" aria-label="搜索会话"
                 @keydown.esc="sessionQuery = ''" />
          <button v-if="sessionQuery" type="button" class="search-clear" title="清除" @click.prevent="sessionQuery = ''">×</button>
        </label>
      </div>
      <div class="session-list">
        <template v-for="g in sessionGroups" :key="g.key">
          <div class="session-group">{{ g.label }}</div>
        <div v-for="s in g.items" :key="s.id" class="session-item" :class="{active: s.id===currentSessionId}" @click="switchSession(s.id)">
          <template v-if="editingId === s.id">
            <input class="rename-input" v-model="editText" v-focus
                   @click.stop @keyup.enter="saveRename(s.id)" @keyup.esc="cancelRename" @blur="saveRename(s.id)" />
          </template>
          <template v-else>
            <span class="session-title" :title="s.title">{{ s.title }}</span>
            <!-- 操作按钮收进绝对定位的浮层：它们在正常流里会固定吃掉 ~80px 宽度，
                 侧边栏只有 150px 左右，标题因此被压成"生产级回..."。悬停才浮出，
                 不参与布局，标题可以占到整行宽度。 -->
            <div class="session-actions">
            <button class="rename-btn" @click.stop="startRename(s)" title="重命名">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h4l9.5-9.5a2.1 2.1 0 0 0-3-3L5 17v3z"/></svg>
            </button>
            <el-dropdown class="export-dropdown" trigger="click" @command="fmt => exportSession(s, fmt)" @click.stop>
              <button class="export-btn" @click.stop title="导出对话">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v10M8.5 11l3.5 3.5L15.5 11M5 18.5h14"/></svg>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="markdown">Markdown (.md)</el-dropdown-item>
                  <el-dropdown-item command="json">JSON (.json)</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <button class="delete-btn" @click.stop="removeSession(s)" title="删除">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 7h14M9.5 7V5.2c0-.7.5-1.2 1.2-1.2h2.6c.7 0 1.2.5 1.2 1.2V7M7 7l.8 11.3c0 .9.8 1.7 1.7 1.7h5c.9 0 1.7-.8 1.7-1.7L17 7"/></svg>
            </button>
            </div>
          </template>
        </div>
        </template>
        <div v-if="!filteredSessions.length" class="session-empty">
          <span v-if="sessionQuery">没有匹配「{{ sessionQuery }}」的会话</span>
          <span v-else>还没有对话，点上方「新建对话」开始</span>
        </div>
      </div>
    </aside>
    <button v-if="sidebarCollapsed" class="sidebar-expand" @click="sidebarCollapsed = false" title="展开侧边栏">»</button>

    <div class="chat-area">
      <!-- 上下文警告 -->
      <div v-if="contextWarning" class="context-warning">
        <svg class="cw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4.5l8.2 14.5H3.8L12 4.5z"/><path d="M12 10v4M12 17h.01"/></svg>
        <span class="cw-text">{{ contextWarning }}</span>
        <button class="cw-new" @click="newSession">开启新对话</button>
        <button class="cw-close" @click="contextWarning = null" title="忽略">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
        </button>
      </div>

      <!-- 消息列表 -->
      <!-- 外层只负责给右侧问题导航提供一个定位基准: 导航要贴在消息区(而不是整个对话区)的垂直中线上 -->
      <div class="messages-wrap" ref="msgWrap">
      <div class="messages" ref="msgBox" @scroll.passive="onMessagesScroll">
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
        <!-- 按"回合"而不是按会话里的每条记录渲染: 提问轮(assistant 问 + 用户答 + assistant 接着做)
             在库里是三条消息、两次模型调用, 但在用户眼里就是一轮 —— 合并成一个块,
             只留一个头像、一个思考块、一份操作按钮。 -->
        <template v-for="row in rows" :key="row.key">
          <div class="turn" :ref="el => setRowEl(row.key, el)">
          <MessageBubble
            :msg="rowMessage(row)"
            :streaming="streaming"
            :editing="editingSeq === rowMessage(row).seq"
            :hide-body="row.type === 'ask' && !row.follow"
            :merged-trace="row.type === 'ask' ? askRowTrace(row) : null"
            v-model:editBuffer="editBuffer"
            @start-edit="startEdit"
            @save-edit="saveEdit"
            @cancel-edit="cancelEdit"
            @retry="retry"
            @rewind="onRewind"
            @undo-op="onUndoOp"
            @preview-image="previewImage"
            @preview-code="openCodePreview"
            @feedback="onFeedbackChange"
          >
            <!-- 提问卡片插在思考与正文之间: 阅读顺序是"想 → 问你 → 给答案", 同一条时间线 -->
            <template v-if="row.type === 'ask'" #after-trace>
              <div v-if="editingSeq !== rowMessage(row).seq" class="ask-slot">
                <AskUserCard
                  :questions="row.questions"
                  :answered="!!row.answer"
                  :answer-text="row.answer ? (row.answer.content || '') : ''"
                  @submit="onAskSubmit"
                  @skip="onAskSkip"
                />
              </div>
            </template>
          </MessageBubble>
          </div>
        </template>

        <!-- 流式输出中(仅当当前会话正是该流归属会话时展示; 切走则不显示, 返回继续) -->
        <div v-if="streaming && streamSessionId === currentSessionId"
             class="msg-item msg-assistant" :class="{ 'msg-continuation': streamContinuation }">
          <div v-if="!streamContinuation" class="msg-avatar"><img :src="logoUrl" class="avatar-img" alt="DCDR" /></div>
          <div class="msg-content">
            <div v-if="!streamContinuation" class="msg-role">DCDR</div>
            <!-- 思考流: 思考文本与工具步骤按发生顺序排在同一条时间线上, 边流边跟在最新一步
                 提问已回答的续写轮不在这里重复开一个思考块 —— 它接在上一块的合并时间线后面 -->
            <ThinkingTrace
              v-if="streamTrace.length && !streamContinuation"
              :segments="streamTrace"
              live
              :elapsed="thinkSeconds"
              :has-answer="!!streamContent"
              :live-note="traceLiveNote"
              @undo-op="onUndoOp"
              @preview-code="openCodePreview"
            />
            <div v-if="streamImages.length" class="msg-images">
              <div v-for="(img, idx) in streamImages" :key="idx" class="msg-image-wrapper">
                <img :src="img" :alt="'生成的图片'" class="msg-image"
                     @click="previewImage(img)" loading="lazy" />
              </div>
            </div>
            <div v-if="pipeline" class="search-pipeline">
              <div class="pipeline-head">
                <svg class="pipeline-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="8.4"/><path d="M3.6 12h16.8M12 3.6c2.3 2.4 3.5 5.3 3.5 8.4s-1.2 6-3.5 8.4c-2.3-2.4-3.5-5.3-3.5-8.4s1.2-6 3.5-8.4z"/></svg>
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
            <!-- 没有时间线可展示时(准备阶段), 状态提示单独一行; 已有时间线时提示跟在时间线末尾 -->
            <div v-else-if="statusHint && !streamTrace.length" class="msg-status">
              <span class="status-dot"></span>{{ statusHint }}
            </div>
            <!-- 等待首个内容块期间的思考动画: 覆盖准备阶段(记忆召回/知识库检索/图片识别/路由)与首 token 等待 -->
            <div v-if="streamContent" class="msg-bubble streaming" v-html="renderStreamBubble(streamContent)" @click="onCodeClick"></div>
            <div v-else-if="!statusHint && !pipeline && !streamTrace.length" class="msg-thinking">
              <span class="think-dots"><i></i><i></i><i></i></span>
              <span class="think-label">思考中<template v-if="thinkSeconds > 0">（{{ thinkSeconds }}s）</template></span>
            </div>
          </div>
        </div>
      </div>

      <!-- 会话内问题导航: 悬浮/点图标展开的浮层, 不占正文宽度 -->
      <QuestionNav
        v-if="navVisible"
        :items="navItems"
        :active="navActive"
        :pinned="navPinned"
        @toggle="navPinned = !navPinned"
        @jump="jumpTo"
      />
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <!-- 已选图片预览 -->
        <div v-if="selectedFiles.length" class="selected-files">
          <div v-for="(file, idx) in selectedFiles" :key="idx" class="selected-file">
            <div class="file-preview">
              <img v-if="isImage(file)" :src="file.preview" alt="preview" />
              <svg v-else class="file-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M13.5 3.8H7.2c-.9 0-1.6.7-1.6 1.6v13.2c0 .9.7 1.6 1.6 1.6h9.6c.9 0 1.6-.7 1.6-1.6V8.6l-4.9-4.8z"/><path d="M13.4 3.9v4.7h5"/></svg>
              <button class="remove-file" @click="removeFile(idx)" title="移除">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
              </button>
            </div>
            <span class="file-name">{{ file.file.name }}</span>
          </div>
        </div>

        <div class="input-box">
          <!-- V3.3 F-49 工作区: 绑定在会话上, 徽标常驻显示当前授权目录与档位。
               V3.4 起放进输入框内部 —— 它和"这轮带什么上下文"是同一类信息(知识库/技能/工作区),
               摆在框外会让人以为它是页面级设置, 而不是本会话的授权状态 -->
          <div class="ws-toolbar">
            <WorkspaceSelector :session-id="currentSessionId || ''" @need-session="ensureSession" />
            <!-- 变更面板: 模型改完文件后, 用户必须能看清"改了什么"并一键退回 -->
            <WorkspaceChangePanel ref="changePanelRef" :session-id="currentSessionId || ''" @changed="onChangesReverted" />
          </div>
          <textarea ref="inputEl" v-model="input" placeholder="输入消息，Enter 发送，Shift + Enter 换行"
                    @keydown.enter.exact.prevent="send" rows="1"
                    :class="{ 'has-files': selectedFiles.length > 0 }"></textarea>
          <!-- 已选上下文: 加号面板收起后必须还看得见 —— 否则用户会忘记这轮带了哪几个库/技能,
               要么重复添加, 要么以为没生效 -->
          <div v-if="pickedCount" class="picked-chips">
            <span v-if="currentAgentName" class="pchip expert">
              <span class="pchip-k">专家</span>{{ currentAgentName }}
              <button class="pchip-x" title="取消选择专家" @click="selectedAgentId = ''">×</button>
            </span>
            <span v-for="kb in pickedKbs" :key="kb.id" class="pchip">
              <span class="pchip-k">库</span>{{ kb.name }}
              <button class="pchip-x" title="移出本轮" @click="removeKb(kb.id)">×</button>
            </span>
            <span v-for="s in pickedSkills" :key="s.name" class="pchip">
              <span class="pchip-k">技能</span>{{ s.displayName || s.name }}
              <button class="pchip-x" title="移出本轮" @click="removeSkill(s.name)">×</button>
            </span>
          </div>
          <div class="input-foot">
            <div class="input-actions">
            <!-- V3.4 统一「+」入口: 专家 / 知识库 / 技能收在一个面板里, 取代原先并排的三个下拉
                 (三者本就是同一件事"这轮带什么", 并排会吃掉 300+px, 窄屏时动作按钮被挤成两行) -->
            <AgentContextPicker
              v-model:agent-id="selectedAgentId"
              v-model:kb-ids="selectedKbIds"
              v-model:skill-names="selectedSkillNames"
              :agents="agents" :knowledge-bases="selectableKnowledgeBases" :skills="availableSkills"
              :loading="kbLoading || skillLoading || agentLoading"
              :disabled="streaming" :logged-in="authStore.isLoggedIn"
              @open="onPickerOpen" />
            <!-- 文生图按钮 -->
            <button class="action-btn" title="文生图（AI 绘图）：请先在输入框填写图片描述" @click="sendImage"
                    :disabled="streaming"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.6L13.7 9.2L19.3 10.9L13.7 12.6L12 18.2L10.3 12.6L4.7 10.9L10.3 9.2L12 3.6Z"/><path d="M18.6 16.2L19.3 18.3L21.4 19L19.3 19.7L18.6 21.8L17.9 19.7L15.8 19L17.9 18.3L18.6 16.2Z"/></svg></button>
            <!-- 联网搜索开关 -->
            <button class="action-btn" :class="{active: webSearch}" title="联网搜索：开启后优先调用联网检索获取实时/权威信息"
                    @click="$emit('update:webSearch', !webSearch)" :disabled="streaming"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="8.2"/><path d="M3.8 12H20.2"/><path d="M12 3.8C14.6 6.3 16.2 9 16.2 12C16.2 15 14.6 17.7 12 20.2C9.4 17.7 7.8 15 7.8 12C7.8 9 9.4 6.3 12 3.8Z"/></svg></button>
            <!-- 深度思考开关(仅思考型模型生效): 关闭时跳过思考直接作答, 更快更省 -->
            <button class="action-btn" :class="{active: thinkingEnabled}" title="深度思考：开启后模型先思考再作答（仅思考型模型生效），关闭更快更省"
                    @click="$emit('update:thinkingEnabled', !thinkingEnabled)" :disabled="streaming"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9.6 4.4C7 4.4 5 6.4 5 9C5 9.7 5.2 10.4 5.4 11C4.5 11.7 4 12.8 4 14C4 15.9 5.5 17.4 7.4 17.4H8.4V19.6"/><path d="M14.4 4.4C17 4.4 19 6.4 19 9C19 9.7 18.8 10.4 18.6 11C19.5 11.7 20 12.8 20 14C20 15.9 18.5 17.4 16.6 17.4H15.6V19.6"/><path d="M9.6 4.4V8M14.4 4.4V8"/></svg></button>
            <!-- 图片上传按钮 -->
            <label class="action-btn" title="上传图片/视频">
              <input type="file" ref="fileInput" @change="handleFileSelect" accept="image/*,video/*" multiple hidden />
              <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M16.4 8.2L9.2 15.4C8 16.6 6.1 16.6 4.9 15.4C3.7 14.2 3.7 12.3 4.9 11.1L12.1 3.9C13.7 2.3 16.3 2.3 17.9 3.9C19.5 5.5 19.5 8.1 17.9 9.7L10.7 16.9C9.7 17.9 8.1 17.9 7.1 16.9C6.1 15.9 6.1 14.3 7.1 13.3L13.7 6.7"/></svg>
            </label>
          </div>
            <!-- 只留条数与发送提示: 知识库/技能已在 chips 里可见, 这里再报一遍"N 库"就是重复 -->
            <div class="input-meta mono">
              {{ turns }} 轮 · Enter 发送
            </div>
            <!-- 上下文占用圆环(V3.4): 常驻显示本轮已用 / 总上下文, 点击展开五段明细并调整分母。
                 放在 meta 与发送键之间 —— input-meta 的 margin-left:auto 会把它俩一起推到右侧 -->
            <ContextUsageRing :usage="contextUsage" :limit-view="contextLimitView"
                              :saving="contextLimitSaving" @update-limit="onUpdateContextLimit" />
            <button class="send-btn gradient-bg" @click="streaming ? stop() : send()"
                    :disabled="(!input.trim() && !selectedFiles.length) && !streaming">
              <svg v-if="!streaming" class="ic-send" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19.4V5"/><path d="M5.6 11.4L12 5L18.4 11.4"/></svg>
            <svg v-else class="ic-send" viewBox="0 0 24 24" fill="currentColor"><rect x="7.2" y="7.2" width="9.6" height="9.6" rx="1.6"/></svg>
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
          <span class="ctx-v mono">{{ turns }} 轮</span>
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

      <!-- 历史摘要(V3.4): 压缩掉的旧对话变成了这一段, 必须能看见 ——
           否则会话突然"忘事"时, 用户没有任何地方能查到它当前记住的是哪一版 -->
      <div v-if="summaryView?.available" class="ctx-card">
        <div class="ctx-card-title">历史摘要</div>
        <details class="ctx-summary">
          <summary>
            <span class="summary-snippet">{{ summarySnippet }}</span>
            <span class="summary-count mono">{{ summaryView.chars }} 字</span>
          </summary>
          <div class="summary-body">{{ summaryView.text }}</div>
        </details>
        <div class="ctx-row">
          <span class="ctx-k">已压缩</span>
          <span class="ctx-v mono">{{ summaryView.coveredMessages }} 条</span>
        </div>
        <div v-if="!summaryView.applied" class="summary-tip">
          当前上下文策略未使用摘要，这段内容不会发给模型。
        </div>
        <div class="ctx-actions">
          <button v-if="summaryView.applied" class="ctx-act" @click="openSummaryEditor">编辑</button>
          <button class="ctx-act danger" @click="onClearSummary">清除</button>
        </div>
      </div>

      <!-- 压缩记录(V3.5): 摘要卡片只说"现在记得什么", 表达不了"什么时候压过、哪次没压成"。
           入口放在面板里而不是弹提示: 压缩是背景事件, 不该每轮打断对话 -->
      <div v-if="currentSessionId" class="ctx-card">
        <div class="ctx-card-title">压缩记录</div>
        <div class="ctx-row">
          <span class="ctx-k">范围</span>
          <span class="ctx-v">本会话的压缩 / 降级 / 作废</span>
        </div>
        <div class="ctx-actions">
          <button class="ctx-act" @click="openCompactions">查看记录</button>
        </div>
      </div>

      <div class="ctx-foot mono">
        {{ currentSessionId ? '会话 #' + currentSessionId : '新会话 · 尚未保存' }}
      </div>
    </aside>
    <button v-else class="panel-expand" @click="panelOpen = true" title="展开上下文面板">«</button>

    <!-- 摘要编辑弹窗(V3.4): 模型漏记约束时由用户直接补上 -->
    <el-dialog v-model="summaryEditing" title="编辑历史摘要" width="620px" append-to-body>
      <div class="summary-tip">
        这段文字会作为「更早对话的压缩记录」注入上下文（标注为背景资料，不是指令）。
        修改只影响下一轮之后发给模型的内容，不会改动对话原文。
      </div>
      <el-input v-model="summaryDraft" type="textarea" :rows="12" placeholder="摘要正文" />
      <template #footer>
        <el-button @click="summaryEditing = false">取消</el-button>
        <el-button type="primary" :loading="summarySaving" @click="saveSummary">保存</el-button>
      </template>
    </el-dialog>

    <!-- 压缩记录抽屉(V3.5): 排查"这个会话为什么突然忘事"的第一现场 ——
         只列本会话, 全局视图在运维台的「压缩记录」页 -->
    <el-drawer v-model="compactionsOpen" title="压缩记录 · 当前会话" size="540px" append-to-body>
      <div v-loading="compactionsLoading" class="cmp-wrap">
        <div v-for="r in compactions" :key="r.id" class="cmp-item">
          <div class="cmp-top">
            <span class="cmp-evt" :class="'evt-' + (r.eventType || '')">{{ r.eventLabel || r.eventType }}</span>
            <span class="cmp-time mono">{{ cmpTime(r.createdAt) }}</span>
          </div>
          <div class="cmp-meta mono">
            <span v-if="r.beforeTokens != null">
              约 {{ cmpTokens(r.beforeTokens) }} → {{ cmpTokens(r.afterTokens) }} token
            </span>
            <span v-if="r.summarizedCount != null">
              {{ r.summarizedCount }} 条 → 摘要 {{ r.summaryChars || 0 }} 字
            </span>
            <span v-if="r.keptCount != null">保留 {{ r.keptCount }} 条</span>
            <span v-if="r.durationMs">{{ cmpDur(r.durationMs) }}</span>
          </div>
          <p v-if="r.reason" class="cmp-reason">{{ r.reason }}</p>
        </div>
        <div v-if="!compactionsLoading && !compactions.length" class="cmp-empty">
          本会话还没有压缩记录<br />
          历史超过触发阈值并被压缩 / 降级时会生成一条
        </div>
      </div>
    </el-drawer>

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
  renameSession, deleteSession, uploadFile,
  buildExportUrl, getKnowledgeBases, getAgents, getSkills, rewindSession, undoOperation,
  getContextUsage, getContextLimit, updateContextLimit,
  getSessionSummary, updateSessionSummary, clearSessionSummary,
  getContextCompactions
} from '@/api'
import { ElMessageBox, ElMessage } from 'element-plus'
import logoUrl from '@/assets/dcdr-logo.png'
import MessageBubble from '@/components/MessageBubble.vue'
import ThinkingTrace from '@/components/ThinkingTrace.vue'
import AskUserCard from '@/components/AskUserCard.vue'
import WorkspaceSelector from '@/components/WorkspaceSelector.vue'
import WorkspaceChangePanel from '@/components/WorkspaceChangePanel.vue'
import ImagePreviewModal from '@/components/ImagePreviewModal.vue'
import CodePreviewModal from '@/components/CodePreviewModal.vue'
import ContextUsageRing from '@/components/ContextUsageRing.vue'
import AgentContextPicker from '@/components/AgentContextPicker.vue'
import QuestionNav from '@/components/QuestionNav.vue'
import { buildTraceSegments } from '@/utils/toolTrace'
import { useMarkdown } from '@/composables/useMarkdown'
import { useCodeBlock } from '@/composables/useCodeBlock'
import { useAuthStore } from '@/stores/auth'

const { renderBubbleHtml } = useMarkdown()
// 流式正文实时渲染 Markdown(含引用角标), 与最终落库的 MessageBubble 完全一致
const renderStreamBubble = (text) => renderBubbleHtml(text, streamSources.value)
// 代码块「复制 / 运行预览」: 复制就地完成, 预览弹窗由本组件统一承载
// (思考里的代码块同理, 由 ThinkingTrace 内部转发 preview-code 到 openCodePreview)
const codePreview = ref({ visible: false, code: '', lang: '' })
function openCodePreview({ code, lang }) {
  codePreview.value = { visible: true, code: code || '', lang: lang || '' }
}
const { handleCodeClick: onCodeClick } = useCodeBlock(openCodePreview)
const vFocus = { mounted: (el) => el.focus() }
const props = defineProps({
  currentModel: String,
  webSearch: { type: Boolean, default: false },
  thinkingEnabled: { type: Boolean, default: true },
  // 顶栏模型分组数据: 用于发送前判断"当前选中的是不是对话模型"(切换模型已不再自动跳页)
  models: { type: Array, default: () => [] }
})
const authStore = useAuthStore()

const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
/**
 * 本轮待回答的提问 {id, questions}。
 * <p>只在「收到 user_input_request → done」这段窗口里存在, 收尾时挂到 assistant 消息上后清空;
 * 不做持久化 —— 刷新后的卡片是从消息的 toolCalls 里还原出来的。
 */
const askRound = ref(null)
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
// ===== 思考流(trace) =====
/**
 * 本轮的时间线段落: {kind:'reason', text} / {kind:'tool', ...} / {kind:'note', text}。
 * <p>为什么要合并成一条数组: 后端把思考 token 与 tool_call 当作两类独立事件下发,
 * 分别塞进两个 ref 就只能渲染成"思考面板 + 工具面板"两块, 读到的顺序变成
 * "所有想法 → 所有工具"。按到达顺序落到同一条数组里, 才是模型真实的"想了→做了→又想了"。
 */
const streamTrace = ref([])
/** 本轮工具调用清单(供落库与刷新后对齐): 从时间线里派生, 不再单独维护一份 */
const streamToolInvocations = computed(() =>
  streamTrace.value.filter(s => s.kind === 'tool')
    .map(s => ({ name: s.name, id: s.id, arguments: s.args })))
/** 工具执行中的实时提示会重复描述正在跑的那一步, 交给时间线自己的行状态就够了 */
const traceLiveNote = computed(() => (/^正在调用工具/.test(statusHint.value) ? '' : statusHint.value))

/** 追加思考文本: 同一次推理是逐 token 到达的, 必须追加到最后一段而不是每 token 新起一段 */
function appendReasoning(text) {
  if (!text) return
  const last = streamTrace.value[streamTrace.value.length - 1]
  if (last && last.kind === 'reason') last.text += text
  else streamTrace.value.push({ kind: 'reason', text })
}

/** 工具开始执行: 先入时间线并标记 running, 结果到达时原地翻成 done/error */
function beginToolStep(call) {
  streamTrace.value.push({
    kind: 'tool',
    name: call.tool,
    id: call.id,
    args: call.args,
    result: '',
    status: 'running',
    startedAt: Date.now(),
    ms: null
  })
}

/**
 * 工具执行结束。
 * <p>优先按后端下发的 tool_call_id 精确配对 —— 同一批里两个同名工具时，按名字回配会串位。
 * 老分块没有 id 时退回按工具名倒序找"仍在运行"的最后一步（串行执行时先入的必先出）。
 */
function finishToolStep(chunk) {
  const steps = streamTrace.value
  const result = chunk.result
  let idx = -1
  if (chunk.id) {
    idx = steps.findIndex(s => s.kind === 'tool' && s.id === chunk.id && s.status === 'running')
  }
  if (idx < 0) {
    for (let i = steps.length - 1; i >= 0; i--) {
      const s = steps[i]
      if (s.kind !== 'tool' || s.name !== chunk.tool || s.status !== 'running') continue
      idx = i
      break
    }
  }
  if (idx < 0) return
  const s = steps[idx]
  s.status = String(result || '').includes('工具执行失败') ? 'error' : 'done'
  s.result = result || ''
  s.ms = Date.now() - (s.startedAt || Date.now())
}

/** 出错收尾: 仍在 running 的步骤不会再有结果, 标成失败, 避免时间线上永远转圈 */
function failRunningTools() {
  streamTrace.value.forEach(s => { if (s.kind === 'tool' && s.status === 'running') s.status = 'error' })
}
const msgBox = ref(null)
/** 消息区外层(含右侧导航带), 用来判断"还有没有地方放导航" */
const msgWrap = ref(null)
const editingId = ref(null)
const editText = ref('')
const contextWarning = ref(null)
const lastTokenUsage = ref(null)
const lastDurationMs = ref(null)

// ===== 上下文占用 (V3.4) =====
/** 最近一轮的分段占用(后端算, 结构见 dto.ContextUsage) */
const contextUsage = ref(null)
/** 总上下文设置: 生效值 / 个人值 / 部署默认 / 允许范围 / 是否可改 */
const contextLimitView = ref(null)
const contextLimitSaving = ref(false)

// ===== 历史摘要 (V3.4) =====
/** 当前会话的压缩摘要(available=false 表示还没压缩过) */
const summaryView = ref(null)
const summaryEditing = ref(false)
const summaryDraft = ref('')
const summarySaving = ref(false)
/** 卡片里只露前 120 字: 摘要动辄上千字, 全铺开会把右侧面板顶满 */
const summarySnippet = computed(() => {
  const t = summaryView.value?.text || ''
  return t.length > 120 ? t.slice(0, 120) + '…' : t
})

// ===== 压缩记录 (V3.5) =====
/** 当前会话的压缩事件; 打开抽屉时才拉取, 不跟着每轮对话刷新 */
const compactions = ref([])
const compactionsOpen = ref(false)
const compactionsLoading = ref(false)

/**
 * 打开压缩记录抽屉。
 * <p>回填前校验「回来时当前会话没变」: 与 loadSummary 同一个理由 ——
 * 快速连点两个会话时, 先发的请求可能后到, 把 A 的事件写到 B 的面板上。
 */
async function openCompactions() {
  const id = currentSessionId.value
  if (!id) return
  compactionsOpen.value = true
  compactionsLoading.value = true
  compactions.value = []
  try {
    const data = await getContextCompactions({ sessionId: id, size: 30 })
    if (currentSessionId.value !== id) return
    compactions.value = data.content || []
  } catch (e) {
    ElMessage.error(e?.message || '加载压缩记录失败')
  } finally {
    compactionsLoading.value = false
  }
}

/** 抽屉里的三个展示格式: 时间去掉 T、token 用 k 缩写、耗时 ms/s 自适应 */
function cmpTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 19) : '—'
}
function cmpTokens(n) {
  const v = Number(n) || 0
  return v >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(v)
}
function cmpDur(ms) {
  const v = Number(ms) || 0
  return v >= 1000 ? (v / 1000).toFixed(1) + 's' : v + 'ms'
}

// 消息编辑/重试
const editingSeq = ref(null)
const editBuffer = ref('')

// 文件相关
const selectedFiles = ref([])
const previewImageUrl = ref(null)
const sidebarCollapsed = ref(false)

// ===== 输入框自适应高度 =====
const inputEl = ref(null)
/** 变更面板: 流式结束后要刷新一次计数(模型刚改完文件) */
const changePanelRef = ref(null)

/**
 * 输入框随内容长高(上限由 CSS 的 max-height 决定)。
 * <p>
 * 原生 textarea 不会自己长高: 多行内容或粘贴长文时, 超出 one-row 高度的部分被内部滚动条吃掉,
 * 用户只能看到一行, 写完还得上下翻。这里每次内容变化都把高度重置为 auto 再按 scrollHeight 收起,
 * 上限 240px(带附件预览时 120px, 因为预览区本身要占高度), 超过才出现内部滚动。
 * <p>
 * 「先归零再量」是必须的: 直接读 scrollHeight 时它只会取"当前高度与内容高度的较大者",
 * 于是删除文本时永远缩不回去, 输入框会一路只增不减。
 */
function autoGrowInput() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  const cssMax = parseFloat(window.getComputedStyle(el).maxHeight)
  const cap = Number.isFinite(cssMax) && cssMax > 0 ? cssMax : 240
  el.style.height = Math.min(el.scrollHeight, cap) + 'px'
  el.style.overflowY = el.scrollHeight > cap ? 'auto' : 'hidden'
  // 超过上限进入内部滚动时贴底, 保证最新一行/光标可见
  if (el.scrollHeight > cap) el.scrollTop = el.scrollHeight
}

watch(input, () => nextTick(autoGrowInput))
// 附件预览会顶掉一部分可用高度, 上限随 CSS 类切换后重新量一次
watch(() => selectedFiles.value.length > 0, () => nextTick(autoGrowInput))

onMounted(() => {
  autoGrowInput()
  // 窗口变窄时同样的文字要占更多行, 变宽时应收回去 —— 否则拖一下窗口高度就和内容对不上了
  window.addEventListener('resize', autoGrowInput)
})
onUnmounted(() => window.removeEventListener('resize', autoGrowInput))

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

/* ---------- V3.4 统一「+」入口: 已选上下文的 chips ---------- */

/** 已选知识库(取名称用于 chips, 同时保证只展示当前可见的库) */
const pickedKbs = computed(() =>
  selectableKnowledgeBases.value.filter((k) => selectedKbIds.value.includes(k.id))
)
const pickedSkills = computed(() =>
  availableSkills.value.filter((s) => selectedSkillNames.value.includes(s.name))
)
const pickedCount = computed(() =>
  (selectedAgentId.value ? 1 : 0) + pickedKbs.value.length + pickedSkills.value.length
)

/* chips 上的 × 直接改状态: 就不必为了删一个技能再点开面板找一遍 */
function removeKb(id) {
  selectedKbIds.value = selectedKbIds.value.filter((x) => x !== id)
}
function removeSkill(name) {
  selectedSkillNames.value = selectedSkillNames.value.filter((x) => x !== name)
}

/**
 * 加号面板展开时按需加载三份列表(此前是三个下拉各自的 visible-change 回调)。
 * <p>
 * 懒加载的策略照旧不变: 知识库/专家只加载一次(变化少), 技能每次展开都刷新 ——
 * 技能的启用/停用改得很频繁, 拿一份过期列表会让人选到已停用的技能, 然后困惑"为什么没生效"。
 */
function onPickerOpen() {
  if (knowledgeBases.value.length === 0) loadKnowledgeBases()
  loadSkills()
  if (agents.value.length === 0) loadAgents()
}

/* ---------- 历史摘要 (V3.4) ---------- */

/**
 * 读某个会话的摘要。
 * <p>回填前必须校验「回来时这个 id 还是当前会话」: 快速连点两个会话时, 先发的请求可能后到,
 * 把 A 的摘要写到 B 的卡片上 —— 而这个错误的摘要用户会当成真的(它决定模型记得什么)。
 * 与流式输出用 streamSessionId 做归属判断是同一个道理。
 */
async function loadSummary(sessionId) {
  if (!sessionId) { summaryView.value = null; return }
  try {
    const view = await getSessionSummary(sessionId)
    if (currentSessionId.value !== sessionId) return
    summaryView.value = view
  } catch (e) {
    // 摘要读不到不影响对话: 面板那块直接不显示即可
    console.error('加载历史摘要失败', e)
    if (currentSessionId.value === sessionId) summaryView.value = null
  }
}

function openSummaryEditor() {
  summaryDraft.value = summaryView.value?.text || ''
  summaryEditing.value = true
}

async function saveSummary() {
  const id = currentSessionId.value
  if (!id || summarySaving.value) return
  summarySaving.value = true
  try {
    summaryView.value = await updateSessionSummary(id, summaryDraft.value)
    summaryEditing.value = false
    ElMessage.success('摘要已更新，下一轮对话生效')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    summarySaving.value = false
  }
}

/**
 * 清除摘要。这是摘要失准(漏记约束、混进错误结论)时的唯一出口:
 * 清掉之后下一轮若仍超预算, 会从完整原文重新压一份, 而不是在这份错的基础上继续滚。
 */
async function onClearSummary() {
  const id = currentSessionId.value
  if (!id) return
  try {
    await ElMessageBox.confirm(
      '清除后，下一轮对话会从完整原文重新压缩（原文一直保留，不会丢）。继续吗？',
      '清除历史摘要', { type: 'warning', confirmButtonText: '清除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await clearSessionSummary(id)
    ElMessage.success('摘要已清除，下一轮将重新压缩')
    loadSummary(id)
  } catch (e) {
    ElMessage.error(e?.message || '清除失败')
  }
}

/* ---------- 上下文占用 (V3.4) ---------- */

/**
 * 读某个会话的占用快照。
 * <p>
 * 切会话必须重读: 圆环展示的是"这个会话上一轮用了多少", 不重读就会把上一个会话的数字带过来,
 * 用户会以为"我刚开的新会话就已经用了 60%"。
 * <p>回填前同样校验归属, 理由见 loadSummary。
 */
async function loadContextUsage(sessionId) {
  if (!sessionId) { contextUsage.value = null; return }
  try {
    const usage = await getContextUsage(sessionId)
    if (currentSessionId.value !== sessionId) return
    contextUsage.value = usage
  } catch (e) {
    console.error('加载上下文占用失败', e)
    if (currentSessionId.value === sessionId) contextUsage.value = null
  }
}

async function loadContextLimit() {
  try {
    contextLimitView.value = await getContextLimit()
  } catch (e) {
    console.error('加载总上下文设置失败', e)
  }
}

/**
 * 修改总上下文(分母)。null = 恢复默认。
 * <p>
 * 返回的视图带新的生效值, 直接替换; 圈上的百分比必须<b>就地按新分母重算</b> ——
 * 否则用户改完设置要等下一轮对话才看到变化, 会以为"没生效"。
 */
async function onUpdateContextLimit(limit) {
  if (contextLimitSaving.value) return
  contextLimitSaving.value = true
  try {
    contextLimitView.value = await updateContextLimit(limit)
    applyLimitToUsage()
    // 提示语带上具体档位(与 WorkBuddy 的「上下文长度已切换至 {value}」同一口径):
    // 只回一句"已更新"的话, 用户仍要自己回头去确认到底切到了哪一档
    const label = contextLimitView.value?.options?.find((o) => (o.value ?? '') === (limit ?? ''))?.label
    ElMessage.success(label ? `上下文长度已切换至 ${label}` : '上下文长度已更新')
  } catch (e) {
    ElMessage.error(e?.message || '设置失败')
  } finally {
    contextLimitSaving.value = false
  }
}

/** 分母变了 → 重算总百分比; 各分段的占比以"已用总量"为分母, 不受影响 */
function applyLimitToUsage() {
  const u = contextUsage.value
  const limit = contextLimitView.value?.limit
  if (!u || !limit) return
  u.limitTokens = limit
  u.percent = Math.round((u.totalTokens * 100 / limit) * 10) / 10
}

function isImage(file) {
  return file.file.type.startsWith('image/')
}

/**
 * 会话列表只取 source=user(用户自己开的对话)。
 * <p>
 * V3.5 起定时任务 / 工作流自动执行产生的会话会被后端排除 —— 它们归到
 * 「自动化 → 任务对话」, 这样侧边栏里每条都是"我聊过的", 不用再从一堆
 * "定时任务 · xxx"里翻自己的会话。显式传 user 而不是依赖后端默认值,
 * 是为了让这里出现"要不要改取哪些来源"的意图变得可见。
 */
/* ---------- 会话导航栏: 搜索 + 按时间分组 ---------- */

/**
 * 会话搜索词。
 * <p>只过滤左侧列表，不影响消息内容 —— 全文检索需要后端支持，
 * 这里对应的是"我记得那条对话标题里有什么"这个更常见的场景。
 */
const sessionQuery = ref('')

const filteredSessions = computed(() => {
  const q = sessionQuery.value.trim().toLowerCase()
  if (!q) return sessions.value
  return sessions.value.filter((s) => (s.title || '').toLowerCase().includes(q))
})

/**
 * 会话归到哪个日期桶。
 * <p>后端给的是不带时区的 {@code yyyy-MM-ddTHH:mm:ss}，{@code new Date()} 按本地时区解析，
 * 与本机时钟一致；解析失败保守归入"更早"，不让一条坏数据把分组顶乱。
 */
function dayBucket(iso) {
  if (!iso) return 'earlier'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return 'earlier'
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = new Date(startOfToday)
  startOfYesterday.setDate(startOfYesterday.getDate() - 1)
  if (d.getTime() >= startOfToday) return 'today'
  if (d.getTime() >= startOfYesterday.getTime()) return 'yesterday'
  return 'earlier'
}

/**
 * 分组后的会话（空组不下发）。
 * <p>按"今天 / 昨天 / 更早"切分：会话一多，纯时间倒序排下来，找三天前的那条只能靠
 * 记住滚动位置。空组过滤掉，否则新用户会看到三个空标题。
 */
const sessionGroups = computed(() => {
  const buckets = { today: [], yesterday: [], earlier: [] }
  for (const s of filteredSessions.value) {
    buckets[dayBucket(s.updatedAt || s.createdAt)].push(s)
  }
  return [
    { key: 'today', label: '今天', items: buckets.today },
    { key: 'yesterday', label: '昨天', items: buckets.yesterday },
    { key: 'earlier', label: '更早', items: buckets.earlier }
  ].filter((g) => g.items.length)
})

async function loadSessions() { sessions.value = await getSessions('user') }

async function newSession() {
  const s = await createSession({ title: 'New Chat', model: props.currentModel })
  sessions.value.unshift(s)
  // 清掉搜索词: 否则新建的会话被上一轮的过滤条件挡住, 用户会以为"新建没成功"
  sessionQuery.value = ''
  currentSessionId.value = s.id
  messages.value = []
  // 新会话没有快照与摘要: 清空而不是沿用上一个会话的
  contextUsage.value = null
  summaryView.value = null
}

/**
 * 工作区选择器需要一个真实会话才能绑定, 而新会话是懒创建的(首次发消息才落库)。
 * 用户先在空白页选了工作区时, 这里补建一个会话; WorkspaceSelector 收到新 sessionId 后完成绑定。
 */
async function ensureSession() {
  if (currentSessionId.value) return
  try {
    await newSession()
  } catch (e) {
    ElMessage.error(e?.message || '创建会话失败，无法绑定工作区')
  }
}

async function switchSession(id) {
  if (editingId.value) return
  currentSessionId.value = id
  messages.value = await getMessages(id)
  // 占用与摘要都按会话独立, 切过去就要换成这个会话的
  loadContextUsage(id)
  loadSummary(id)
  scrollBottom()
}

/**
 * 撤回(V3.3 F-49): 撤回该用户消息及其之后的一切。
 * 文件会按变更日志回滚到该轮之前, 消息一并截断;
 * 撤回前被外部改过的文件会被后端跳过并在提示里说明, 不会覆盖用户手改。
 */
async function onRewind(msg) {
  const sessionId = currentSessionId.value
  if (!sessionId || !msg?.id) return
  try {
    await ElMessageBox.confirm(
      '将撤回这条消息之后的所有对话, 并把这一轮产生的文件改动一起回滚（改动会先进回收区，可再恢复）。继续吗？',
      '撤回此轮', { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const rep = await rewindSession(sessionId, msg.id, 'ALL')
    const parts = [
      `回滚 ${rep?.reverted?.length || 0} 个文件`,
      `删除 ${rep?.messagesDeleted || 0} 条消息`
    ]
    // 记忆必须一起退, 否则撤完之后模型下一轮还能"记得"这段对话 —— 用户会直接感到没撤干净
    if (rep?.memoriesArchived) parts.push(`归档 ${rep.memoriesArchived} 条记忆`)
    if (rep?.skipped?.length) parts.push(`跳过 ${rep.skipped.length} 个（撤回前被外部修改）`)
    ElMessage.success(parts.join('，'))
    messages.value = await getMessages(sessionId)
  } catch (e) {
    ElMessage.error(e?.message || '撤回失败')
  }
}

/**
 * L1 单操作撤销: 只回滚某一次工具调用造成的文件改动。
 * 与整轮撤回的区别是不动对话 —— 用户想收回的只是那一次写/删。
 */
async function onUndoOp(opId) {
  const sessionId = currentSessionId.value
  if (!sessionId || !opId) return
  try {
    const rep = await undoOperation(sessionId, opId)
    const reverted = rep?.reverted?.length || 0
    if (!reverted) {
      const reason = rep?.skipped?.[0]?.reason || '没有可回滚的改动'
      ElMessage.warning(reason)
      return
    }
    const parts = [`已回滚 ${reverted} 个文件`]
    if (rep?.skipped?.length) parts.push(`跳过 ${rep.skipped.length} 个（${rep.skipped[0].reason}）`)
    ElMessage.success(parts.join('，'))
    messages.value = await getMessages(sessionId)
  } catch (e) {
    ElMessage.error(e?.message || '撤销失败')
  }
}

/**
 * 变更面板里撤销之后: 消息气泡上「撤销这次改动」的可用状态已经变了, 重新拉一次消息列表。
 * 面板自己负责提示结果, 这里只做同步, 不再重复弹提示。
 */
async function onChangesReverted() {
  const sessionId = currentSessionId.value
  if (!sessionId) return
  try {
    messages.value = await getMessages(sessionId)
  } catch (e) {
    // 同步失败不影响撤销本身已完成的事实, 静默即可
  }
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

/**
 * 从消息的 toolCalls 里取出提问问题列表; 不是提问消息则返回 null。
 * <p>后端落库的 arguments 是模型原样给的 JSON, 不含服务端生成的 id, 而卡片组件依赖 id 做 key
 * 和答案配对, 所以这里补齐一遍(id 按顺序生成, 与流式下发时同规则, 前后一致)。
 */
function askOf(msg) {
  if (!msg || !Array.isArray(msg.toolCalls)) return null
  const call = msg.toolCalls.find(tc => tc && ((tc.name || tc.function?.name) === 'ask_user'))
  if (!call) return null
  const rawArgs = call.arguments ?? call.function?.arguments
  if (!rawArgs) return null
  try {
    const parsed = typeof rawArgs === 'string' ? JSON.parse(rawArgs) : rawArgs
    const questions = Array.isArray(parsed?.questions) ? parsed.questions : []
    if (!questions.length) return null
    return questions.slice(0, 4).map((q, i) => ({
      id: q.id || `q${i + 1}`,
      header: q.header || '',
      question: q.question || '',
      multiSelect: q.multiSelect === true,
      options: (Array.isArray(q.options) ? q.options : []).slice(0, 4).map(o => {
        if (typeof o === 'string') return { value: o, label: o }
        const label = o.label || o.text || o.value || ''
        return { value: o.value || label, label, description: o.description || '' }
      }).filter(o => o.label)
    }))
  } catch (e) {
    console.warn('解析提问参数失败', e)
    return null
  }
}

/**
 * 该条消息是不是「对提问的回答」：它前面最近的一条非工具消息是带 ask_user 的助手消息。
 * <p>判定靠位置（不是在消息上存标记）：刷新后重放历史也能得到同样结果，不必新增字段。
 * <p>为什么不只认"紧邻的前一条"：落库顺序可能错乱、中间也可能夹着被隐藏的工具消息，
 * 只看 {@code idx - 1} 会漏判，于是"回答"被当成新一轮提问。
 */
function isAskAnswerIn(list, idx) {
  if (idx <= 0) return false
  if (list[idx]?.role !== 'user') return false
  for (let j = idx - 1; j >= 0; j--) {
    const prev = list[j]
    if (!prev) continue
    if (prev.role === 'tool') continue
    if (prev.role === 'assistant') return !!askOf(prev)
    if (prev.role === 'user') return false
  }
  return false
}

function isAskAnswerAt(idx) {
  // 流式续写判定看的是本地列表（此刻还没有 seq，orderedMessages 会原样返回），
  // 用 messages 直接判即可 —— 也避免在它前面引用后定义的 orderedMessages。
  return isAskAnswerIn(messages.value, idx)
}

/** 正在流式生成的这一条，是不是"答完提问之后的续写"（上一轮刚问过） */
const streamContinuation = computed(() =>
  messages.value.length > 0 && isAskAnswerAt(messages.value.length - 1))

/**
 * 按 seq 排好的消息列表（折叠与计数都以它为准）。
 * <p>只在<b>每条消息都有 seq</b> 时排序：本地刚 push、还没回写 seq 的消息不参与，
 * 否则 undefined 会把新消息排到最前面，流式输出看起来像"跳到顶部"。
 */
const orderedMessages = computed(() => {
  const list = messages.value
  if (!list.length || list.some((m) => !m || m.seq == null)) return list
  return [...list].sort((a, b) => a.seq - b.seq)
})

/**
 * 把消息列表折成「回合」。
 * <p>提问一轮在库里是三条：assistant(问, 正文是占位文案) → user(答) → assistant(接着做)。
 * 用户视角是一轮，所以这里合成一条 row：
 * <ul>
 *   <li>ask 轮：渲染成同一个气泡，卡片插在思考与正文之间，只保留一个头像与一份操作；</li>
 *   <li>其余消息：原样一条 row。</li>
 * </ul>
 * <p><b>为什么不要求"答案紧邻提问"</b>：后端落库是异步的，曾出现 user(答) 先于
 * assistant(问) 拿到 seq 的情况，紧邻判定会让整轮退化成两块（界面上看着像两轮）。
 * 这里改为向后找第一条 user 当答案、遇到下一条 assistant 才放弃，并按 seq 排序兜底。
 * 合并只影响展示 —— 会话上下文、seq、反馈键都还是原来的条目，模型看到的历史不受影响。
 */
const rows = computed(() => {
  const list = orderedMessages.value
  const out = []
  for (let i = 0; i < list.length; i++) {
    const m = list[i]
    const questions = askOf(m)
    if (!questions) {
      out.push({ type: 'msg', key: m.id, msg: m })
      continue
    }
    // 答案：提问之后的第一条 user。中间若又出现 assistant，说明这次提问没人回答
    // （用户直接开了新话题，或那条回答没落库）—— 卡片照样展示，但不硬凑一个答案。
    let ansIdx = -1
    for (let j = i + 1; j < list.length; j++) {
      const role = list[j]?.role
      if (role === 'user') { ansIdx = j; break }
      if (role === 'assistant') break
    }
    const answer = ansIdx > 0 ? list[ansIdx] : null
    // 续写：答案之后的第一条 assistant；若又是一次提问就不并进来 ——
    // 否则两轮提问会缠成一块，卡片也没法各归各位
    let follow = null
    let followIdx = -1
    if (answer) {
      for (let j = ansIdx + 1; j < list.length; j++) {
        const nm = list[j]
        if (nm?.role === 'user') break
        if (nm?.role === 'assistant') {
          if (!askOf(nm)) { follow = nm; followIdx = j }
          break
        }
      }
    }
    out.push({ type: 'ask', key: m.id, ask: m, questions, answer, follow })
    i = followIdx > 0 ? followIdx : (answer ? ansIdx : i)
  }
  return out
})

/**
 * 轮次 = 用户<b>真正发起</b>的提问次数。
 * <p>
 * 不按 user 消息条数算：回答提问卡片（"邵阳"）也是一条 user 消息，但它属于上一轮的追问，
 * 按条数就会把一次交互算成两轮。反过来，也不按消息区的块数（{@code rows.length}）算 ——
 * 一次交互本来就是"用户气泡 + AI 块"两块，块数天然是轮次的两倍。
 * <p>
 * 口径：user 消息里，凡是紧跟在 ask 之后的（{@link isAskAnswerIn}）都不计，
 * 于是"问天气 → 卡片问城市 → 答邵阳 → 多云"= 1 轮。
 */
const turns = computed(() => {
  const list = orderedMessages.value
  let n = 0
  for (let i = 0; i < list.length; i++) {
    if (list[i]?.role !== 'user') continue
    if (isAskAnswerIn(list, i)) continue
    n++
  }
  return n
})

/** row 的主消息：提问轮有后续回答时用回答那条（操作/反馈/seq 都该落在它身上） */
function rowMessage(row) {
  return row.type === 'ask' ? (row.follow || row.ask) : row.msg
}

/**
 * 提问轮的合并时间线：提问那次调用的"想 + 问"，接上回答之后的"想 + 做"。
 * <p>回答还没落库（正在生成）时把实时时间线接上 —— 生成过程中也是一轮，不会先出现两个思考块再合并。
 */
function askRowTrace(row) {
  const segs = buildTraceSegments(row.ask)
  if (row.follow) return segs.concat(buildTraceSegments(row.follow))
  // 只有"正在续写的就是这次提问"才接实时时间线。历史里若有"已回答但无后续回答"的提问轮
  // (续写被中断/失败), 它同样没有 follow, 不按答案身份过滤就会把本轮的实时思考接到旧卡片上。
  const isCurrentContinuation = streaming.value && streamContinuation.value
    && row.answer === messages.value[messages.value.length - 1]
  if (isCurrentContinuation) return segs.concat(streamTrace.value)
  return segs.length ? segs : null
}

// ===== 右侧问题导航 =====
/** 回合 key → DOM 元素, 供导航跳转与"当前在哪一条"的判断 */
const rowEls = new Map()
function setRowEl(key, el) {
  if (el) rowEls.set(key, el)
  else rowEls.delete(key)
}

/**
 * 导航条目：只列用户真的问过的问题。
 * <p>不列助手回答（它可能很长，列进去就变成第二份正文），也不列提问卡片的回答
 * （那条已被折进卡片，不是独立问题）。
 */
const navItems = computed(() => {
  const out = []
  rows.value.forEach((row) => {
    const m = row.type === 'msg' ? row.msg : null
    if (!m || m.role !== 'user') return
    const text = (m.content || '').replace(/\s+/g, ' ').trim()
    if (!text) return
    out.push({ key: row.key, text })
  })
  return out
})

/** 当前所处的问题下标（按"哪一条已经滚过视口上沿"算） */
const navActive = ref(0)
/** 消息区宽度：窄了就不显示导航 —— 宁可少一个便利功能，也不能压住正文 */
const navRoom = ref(0)
/** 是否被"钉住"（用户点了图标）。悬浮展开是临时态，由组件自己管理，不落在这里 */
const navPinned = ref(false)
/** 有没有条件放这个导航（问题够多 + 宽度够） */
const navVisible = computed(() => navItems.value.length > 1 && navRoom.value >= 760)

// 窗口变窄 / 切到短会话时取消钉住, 避免"钉着一个已不存在的面板"这种状态残留
watch(navVisible, (v) => { if (!v) navPinned.value = false })

let navScrollRaf = 0
function onMessagesScroll() {
  if (navScrollRaf) return
  navScrollRaf = requestAnimationFrame(() => {
    navScrollRaf = 0
    syncNavActive()
  })
}

function syncNavActive() {
  const box = msgBox.value
  if (!box) return
  // 视口上沿往下 80px: 刚滚过去的那条就算"当前", 而不是要求它正好在最顶上
  const line = box.getBoundingClientRect().top + 80
  let idx = 0
  navItems.value.forEach((item, i) => {
    const el = rowEls.get(item.key)
    if (el && el.getBoundingClientRect().top <= line) idx = i
  })
  navActive.value = idx
}

function jumpTo(item) {
  const el = rowEls.get(item.key)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

let navObserver = null
onMounted(() => {
  // 观察外层而不是消息区本身: 导航一出现就会改消息区的 padding-right,
  // 拿被改的那个元素的宽度做判断会形成"显示→变窄→隐藏→变宽"的抖动
  const el = msgWrap.value
  if (!el) return
  navRoom.value = el.clientWidth
  if (typeof ResizeObserver === 'undefined') return
  // 侧栏折叠/窗口缩放都会改变这块区域的宽度, 用观察者而不是只监听 window resize:
  // 侧栏宽度是过渡动画, window 不会触发事件
  navObserver = new ResizeObserver((entries) => {
    navRoom.value = entries[0]?.contentRect?.width || 0
  })
  navObserver.observe(el)
})
onUnmounted(() => { navObserver?.disconnect() })

// 消息增减(加载历史 / 新的一轮)后重算"当前问题"
watch(() => navItems.value.length, () => nextTick(syncNavActive))

/**
 * 卡片提交: 答案结构化回传, 气泡文案用可读文本(用户能看懂自己在回什么)。
 * <p>会话必须已经存在 —— 这里刻意不落回 {@code send()} 的"无会话就新建"分支:
 * 提问的回答属于当前这条对话, 一旦新建会话, 用户会看到"问题在一个对话、回答在另一个对话"。
 */
function onAskSubmit({ answers, text }) {
  if (!requireAskSession()) return
  send({ questionAnswer: answers, text })
}

/** 跳过: 仍然发一条消息 —— 让模型明确"用户不想回答", 而不是以为自己在干等 */
function onAskSkip() {
  if (!requireAskSession()) return
  send({ questionAnswer: {}, text: '跳过这些问题，请按你的判断继续' })
}

/**
 * 回答提问前校验会话确实还在: 只判断 id 非空不够 —— 会话被删后 id 可能还没清,
 * 那时 send() 会走"无会话就新建"的分支, 回答就落到另一条对话里了。
 */
function requireAskSession() {
  const id = currentSessionId.value
  if (id && sessions.value.some(s => s.id === id)) return true
  ElMessage.warning('当前会话已失效，请刷新页面后重新提问')
  return false
}

function scrollBottom() { nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight }) }

/** 模型类型 → 中文名(仅用于提示文案) */
const MODEL_TYPE_LABEL = { CHAT: '对话', IMAGE: '图片生成', VIDEO: '视频生成', AUDIO: '语音' }

/**
 * 发送前的模型校验。
 * <p>
 * 切换模型已不再自动跳页(F-09-004 调整), 于是可能出现"停在对话页、顶栏却选着图片/视频模型"的状态。
 * 这种不匹配只在<b>真正要发消息时</b>提示 —— 浏览模型列表本身是无害的, 不该被打断。
 * 提示里带上"去哪儿用它", 免得用户只知道"用不了"却不知道该怎么继续。
 *
 * @returns true 表示可以发送; false 表示已给出提示并拦下本次发送
 */
function ensureChatModel() {
  if (!props.currentModel) {
    ElMessage.warning('请先在顶部选择一个对话模型')
    return false
  }
  const m = (props.models || []).find(x => x.name === props.currentModel)
  // 列表未加载完、或是后端新增的模型时查不到 → 不做前端拦截, 让后端给出准确错误(避免误伤正常对话)
  if (!m || m.type === 'CHAT') return true
  const typeLabel = MODEL_TYPE_LABEL[m.type] || m.type
  ElMessage.warning(`当前选中的「${m.label || m.name}」是${typeLabel}模型, 不能用于对话。`
    + '请在顶部切换到对话模型; 若要使用它, 请到左侧「创作」页。')
  return false
}

async function send(opts = {}) {
  const overrideImages = opts.images || null
  // opts.text: 点选项/跳过时由卡片直接给出文案, 不必先塞进输入框再读回来(会闪一下)
  const text = (opts.text != null ? String(opts.text) : input.value).trim()
  const hasFiles = selectedFiles.value.length > 0

  // 只点了选项没打字也允许发送: questionAnswer 本身就是一条完整的回答
  if ((!text && !hasFiles && !overrideImages && !opts.questionAnswer) || streaming.value) return
  if (!ensureChatModel()) return
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
  streamTrace.value = []
  statusHint.value = ''
  pipeline.value = null
  interruptedRef.value = false
  askRound.value = null
  scrollBottom()

  await streamChat(
    {
      sessionId: currentSessionId.value,
      message: text,
      // 对提问卡片的回答: key=questionId, value=选中值列表。
      // 后端据此把答案补成那次 ask_user 调用的工具结果(见 ToolPairingRepair)。
      questionAnswer: opts.questionAnswer,
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
      if (chunk.type === 'error') { statusHint.value = ''; pipeline.value = null; failRunningTools(); streamContent.value += '\n[错误] ' + (chunk.message || ''); return }
      if (chunk.type === 'interrupted') { statusHint.value = ''; pipeline.value = null; failRunningTools(); interruptedRef.value = true; return }
      if (chunk.type === 'context_warning') { statusHint.value = ''; contextWarning.value = chunk.message || '对话已较长，建议开启新对话'; return }
      if (chunk.type === 'tool_call') {
        // 工具步骤直接进时间线: 边流边出现在思考里, 不必刷新页面等历史接口的聚合结果
        beginToolStep(chunk)
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
        finishToolStep(chunk)
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
      if (chunk.type === 'user_input_request') {
        // 模型需要确认: 本轮到此结束(紧随其后的只有 done)。先记下来, 卡片在 onDone 里挂到消息上 ——
        // 挂在临时状态里刷新就没了, 挂在消息上才能回看。
        statusHint.value = ''
        askRound.value = { id: chunk.id, questions: chunk.questions || [] }
        return
      }
      if (chunk.type === 'context_usage') {
        // 后端在调用模型前下发本轮占用明细。仅归属会话当前展示时更新 ——
        // 期间切走了的话, 这个数字属于另一个会话, 写进来会把当前会话的圆环带偏
        if (streamSessionId.value === currentSessionId.value) contextUsage.value = chunk.contextUsage || null
        return
      }
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
      if (ok(chunk.reasoningContent)) { reasoningContent.value += chunk.reasoningContent; appendReasoning(chunk.reasoningContent); scrollBottom() }
      if (ok(chunk.content)) { streamContent.value += chunk.content; scrollBottom() }
    },
    () => {
      // 方案 B: 仅当当前展示的会话就是该流归属会话时, 才把回答写入本地 messages;
      // 若期间已切到其他会话, 不污染当前列表(返回归属会话时靠 getMessages 拉取后端已落库的完整回答)
      const stillOnOwnerSession = streamSessionId.value === currentSessionId.value
      // 本轮流式累积的工具调用清单, 随回答一起落到本地消息上(与后端 getMessages 的聚合结果结构一致)
      const roundInvocations = streamToolInvocations.value.slice()
      // 提问轮: 正文可能是空的(模型直接发工具调用), 也要落一条消息, 否则卡片挂不上去;
      // toolCalls 的形状必须和后端落库的那份一致, 刷新前后才渲染成同一种卡片。
      const asked = askRound.value
      const askToolCalls = asked
        ? [{ id: asked.id, name: 'ask_user', arguments: JSON.stringify({ questions: asked.questions }) }]
        : undefined
      if (stillOnOwnerSession && (streamContent.value || reasoningContent.value || streamImages.value.length || asked)) {
        messages.value.push({
          id: Date.now(),
          role: 'assistant',
          content: streamContent.value,
          reasoningContent: reasoningContent.value,
          images: streamImages.value.length ? streamImages.value.map(u => ({ url: u, name: '' })) : undefined,
          sources: streamSources.value.length ? streamSources.value.slice() : undefined,
          toolInvocations: roundInvocations.length ? roundInvocations : undefined,
          toolCalls: askToolCalls,
          type: streamImages.value.length ? 'image' : 'text',
          interrupted: interruptedRef.value,
          tokenUsage: lastTokenUsage.value,
          durationMs: lastDurationMs.value
        })
      }
      askRound.value = null
      streamContent.value = ''
      reasoningContent.value = ''
      streamImages.value = []
      streamTrace.value = []
      statusHint.value = ''
      pipeline.value = null
      streamSessionId.value = null
      streaming.value = false
      loadSessions()
      scrollBottom()
      // 模型这一轮可能刚改过文件, 变更计数要跟着走, 否则用户得手动点开面板才看到
      changePanelRef.value?.refresh()
      // 压缩发生在准备阶段: 这一轮结束后摘要可能刚生成/刚更新, 面板要跟上
      if (currentSessionId.value) loadSummary(currentSessionId.value)
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
                // 时间线(思考↔工具顺序): 流式 chunk 不带 trace, 本地这条消息一直缺它,
                // buildTraceSegments 因而退回"整段思考 + 全部工具"的旧排法 —— 与刷新后看到的不一致。
                // 后端随落库记好了 trace, 这里回写让"刚生成"和"刷新后"是同一条时间线。
                if (m.trace?.length) messages.value[i].trace = m.trace
                // 后端聚合的工具调用清单作为兜底: 本地流式未累积到(如期间切换过会话)时补齐
                if (!messages.value[i].toolInvocations && m.toolInvocations?.length) {
                  messages.value[i].toolInvocations = m.toolInvocations
                } else if (messages.value[i].toolInvocations && m.toolInvocations?.length) {
                  // 补齐 opId: 流式事件里没有它, 只有后端从工具结果里提取后才带回来。
                  // 不补的话刚跑完的这一轮看不到「撤销这次改动」, 得刷新页面才出现。
                  const byId = new Map(m.toolInvocations.filter(t => t.id).map(t => [t.id, t]))
                  messages.value[i].toolInvocations.forEach(t => {
                    const remote = t.id ? byId.get(t.id) : null
                    if (remote?.opId) t.opId = remote.opId
                  })
                }
              })
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamSessionId.value = null; streamContent.value = ''; reasoningContent.value = ''; failRunningTools(); statusHint.value = ''; ElMessage.error('对话请求失败：' + (e?.message || '网络异常')) }
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
  streamTrace.value = []
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
        beginToolStep(chunk)
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
        finishToolStep(chunk)
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
      if (chunk.type === 'context_usage') {
        // 后端在调用模型前下发本轮占用明细。仅归属会话当前展示时更新 ——
        // 期间切走了的话, 这个数字属于另一个会话, 写进来会把当前会话的圆环带偏
        if (streamSessionId.value === currentSessionId.value) contextUsage.value = chunk.contextUsage || null
        return
      }
      if (chunk.type === 'done') {
        statusHint.value = ''
        lastTokenUsage.value = chunk.tokenUsage?.total ?? null
        lastDurationMs.value = chunk.durationMs ?? null
        return
      }
      if (chunk.type !== 'chunk') return
      statusHint.value = ''
      if (chunk.reasoningContent) { reasoningContent.value += chunk.reasoningContent; appendReasoning(chunk.reasoningContent); scrollBottom() }
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
                // 时间线(思考↔工具顺序): 流式 chunk 不带 trace, 本地这条消息一直缺它,
                // buildTraceSegments 因而退回"整段思考 + 全部工具"的旧排法 —— 与刷新后看到的不一致。
                // 后端随落库记好了 trace, 这里回写让"刚生成"和"刷新后"是同一条时间线。
                if (m.trace?.length) messages.value[i].trace = m.trace
                // 后端聚合的工具调用清单作为兜底: 本地流式未累积到(如期间切换过会话)时补齐
                if (!messages.value[i].toolInvocations && m.toolInvocations?.length) {
                  messages.value[i].toolInvocations = m.toolInvocations
                } else if (messages.value[i].toolInvocations && m.toolInvocations?.length) {
                  // 补齐 opId: 流式事件里没有它, 只有后端从工具结果里提取后才带回来。
                  // 不补的话刚跑完的这一轮看不到「撤销这次改动」, 得刷新页面才出现。
                  const byId = new Map(m.toolInvocations.filter(t => t.id).map(t => [t.id, t]))
                  messages.value[i].toolInvocations.forEach(t => {
                    const remote = t.id ? byId.get(t.id) : null
                    if (remote?.opId) t.opId = remote.opId
                  })
                }
              })
            }
          })
          .catch(() => {})
      }
    },
    (e) => { streaming.value = false; streamSessionId.value = null; streamContent.value = ''; reasoningContent.value = ''; streamImages.value = []; failRunningTools(); statusHint.value = ''; ElMessage.error('文生图请求失败：' + (e?.message || '网络异常')) }
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

onMounted(() => { loadSessions(); loadContextLimit() })
</script>

<style scoped>
.chat-mode { flex: 1; display: flex; overflow: hidden; height: 100%; position: relative; }

/* 侧边栏 */
.sidebar { width: 240px; background: var(--dcdr-surface-glass); backdrop-filter: blur(14px); border-right: 1px solid var(--dcdr-border); display: flex; flex-direction: column; transition: width 0.25s ease; }
.sidebar.collapsed { width: 0; min-width: 0; border-right: none; overflow: hidden; }
.sidebar-header { display: flex; align-items: center; gap: 8px; padding: 14px 12px 6px; }
.sidebar-title { font-size: 13px; font-weight: 500; color: var(--dcdr-text-1); letter-spacing: 0.2px; }
/* 会话数: 只在有会话时出现, 用小圆角 pill —— 它是"这里还有多少东西"的量感, 不是标题的一部分 */
.sidebar-count { font-family: var(--dcdr-font-mono); font-size: 10px; line-height: 16px; padding: 0 6px; border-radius: 8px; color: var(--dcdr-text-3); background: var(--dcdr-hover); }
.sidebar-header .collapse-btn { margin-left: auto; }
/* 导航区: 新建 + 搜索。与下面的列表用一条分隔线切开, 滚动时标题与入口不会跟着走 */
.sidebar-nav { display: flex; flex-direction: column; gap: 8px; padding: 0 12px 10px; border-bottom: 1px solid var(--dcdr-border); }
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
/* 历史摘要卡片(V3.4): 默认只露前三行, 展开才铺全文 —— 摘要动辄上千字, 全铺会把面板顶满 */
.ctx-summary { font-size: 11px; color: var(--dcdr-text-2); }
.ctx-summary summary { display: flex; align-items: flex-start; gap: 6px; cursor: pointer; list-style: none; }
.ctx-summary summary::-webkit-details-marker { display: none; }
.summary-snippet {
  flex: 1; min-width: 0; line-height: 1.55;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.summary-count { flex: none; font-size: 9.5px; color: var(--dcdr-text-3); }
.summary-body {
  margin-top: 6px; padding: 8px; max-height: 220px; overflow-y: auto; white-space: pre-wrap;
  line-height: 1.6; border-radius: 8px;
  background: var(--dcdr-bg-subtle); border: 1px solid var(--dcdr-border);
}
.ctx-actions { display: flex; gap: 6px; }
.ctx-act {
  height: 24px; padding: 0 10px; font-size: 11px; cursor: pointer; border-radius: 6px;
  border: 1px solid var(--dcdr-border); background: transparent; color: var(--dcdr-text-2);
  transition: all 0.15s ease;
}
.ctx-act:hover { border-color: var(--dcdr-accent-line); color: var(--dcdr-text-1); }
.ctx-act.danger:hover { border-color: var(--dcdr-danger); color: var(--dcdr-danger); }
.summary-tip { margin-bottom: 10px; font-size: 12px; line-height: 1.6; color: var(--dcdr-text-3); }

/* 压缩记录抽屉(V3.5): 每条事件挂在一条竖线上, 让"什么时候压过"有先后感 */
.cmp-wrap { padding: 4px 2px; }
.cmp-item { padding: 10px 0 10px 12px; margin-left: 3px; border-left: 2px solid var(--dcdr-border); }
.cmp-top { display: flex; align-items: center; gap: 8px; }
.cmp-evt {
  font-size: 10.5px; padding: 1px 7px; border-radius: 999px;
  border: 1px solid var(--dcdr-border); color: var(--dcdr-text-2);
}
.cmp-evt.evt-compacted { color: var(--dcdr-accent-soft); border-color: var(--dcdr-accent-line); }
.cmp-evt.evt-degraded { color: var(--dcdr-warning); border-color: var(--dcdr-warning); }
.cmp-evt.evt-invalidated { color: var(--dcdr-danger); border-color: var(--dcdr-danger); }
.cmp-time { font-size: 10.5px; color: var(--dcdr-text-3); }
.cmp-meta {
  display: flex; flex-wrap: wrap; gap: 10px; margin-top: 5px;
  font-size: 11px; color: var(--dcdr-text-2);
}
.cmp-reason { margin: 5px 0 0; font-size: 11.5px; line-height: 1.6; color: var(--dcdr-text-3); }
.cmp-empty { padding: 40px 0; text-align: center; font-size: 12px; line-height: 2; color: var(--dcdr-text-3); }

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
.new-btn { display: flex; align-items: center; justify-content: center; gap: 6px; width: 100%; height: 36px; color: var(--dcdr-accent-soft); font-size: 12.5px; font-weight: 500; cursor: pointer; border-radius: var(--dcdr-radius-inner); border: 1px solid var(--dcdr-accent-line); background: var(--dcdr-accent-wash); transition: background 0.2s ease, border-color 0.2s ease; }
.new-btn:hover { background: var(--dcdr-active); border-color: var(--dcdr-accent); }
.new-icon { width: 14px; height: 14px; flex: none; }
/* 搜索框: label 包 input, 点图标也能聚焦; focus-within 才亮边框, 平时保持安静 */
.session-search { display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 8px; border-radius: 8px; background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border); transition: border-color 0.15s ease, background 0.15s ease; }
.session-search:focus-within { border-color: var(--dcdr-accent-line); background: var(--dcdr-surface); }
.search-icon { width: 13px; height: 13px; flex: none; color: var(--dcdr-text-3); }
.session-search input { flex: 1; min-width: 0; border: none; background: transparent; outline: none; color: var(--dcdr-text-1); font-size: 12px; font-family: inherit; }
.session-search input::placeholder { color: var(--dcdr-text-3); }
.search-clear { flex: none; width: 16px; height: 16px; padding: 0; border: none; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 11px; line-height: 1; cursor: pointer; color: var(--dcdr-text-2); background: var(--dcdr-hover); }
.search-clear:hover { color: var(--dcdr-text-1); }
/* 分组标签: 等宽小字, 与标题形成层级; 首个分组不留上边距, 否则列表顶部会空一块 */
.session-group { padding: 8px 6px 4px; font-family: var(--dcdr-font-mono); font-size: 9.5px; letter-spacing: 0.6px; color: var(--dcdr-text-3); }
.session-group:first-child { padding-top: 2px; }
.session-empty { padding: 18px 10px; text-align: center; font-size: 11.5px; line-height: 1.7; color: var(--dcdr-text-3); }
.session-list { flex: 1; overflow-y: auto; padding: 12px 10px; }
.session-list::-webkit-scrollbar { width: 4px; }
.session-list::-webkit-scrollbar-thumb { background: var(--dcdr-bg); border-radius: 4px; }
.session-item { padding: 12px 14px; border-radius: 10px; cursor: pointer; margin-bottom: 6px; display: flex; align-items: center; gap: 8px; transition: all 0.2s ease; position: relative; }
.session-item:hover { background: var(--dcdr-bg-subtle); }
.session-item.active { background: var(--dcdr-accent-wash); color: var(--dcdr-text-1); font-weight: 500; border: 1px solid var(--dcdr-accent-line); }
/* 标题最多两行：侧边栏只有 ~150px，操作按钮若在正常流里会固定吃掉 ~120px，
   长标题（如"生产级回归-会话A（改名）"）就会退化成"生产级回..."。 */
.session-title { font-size: 13px; flex: 1; min-width: 0; line-height: 1.4; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; word-break: break-word; }
/* 操作按钮：悬停才浮出，绝对定位不参与布局（见模板注释）。
   注意 pointer-events 的用法：容器**始终**不吃事件，只有悬停时按钮本身吃事件，
   否则浮层会盖住标题右半边，点标题切会话就会点不动（实测 Playwright 报 intercepts pointer events）。 */
.session-actions { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); display: flex; align-items: center; gap: 2px; padding-left: 8px; border-radius: 8px; background: var(--dcdr-bg-subtle); opacity: 0; pointer-events: none; transition: opacity 0.16s ease; }
.session-item.active .session-actions { background: var(--dcdr-accent-wash); }
.session-item:hover .session-actions { opacity: 1; }
.session-item:hover .session-actions button,
.session-item:hover .session-actions .export-dropdown { pointer-events: auto; }
.rename-btn, .delete-btn, .export-btn { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border: none; background: transparent; cursor: pointer; color: var(--dcdr-text-3); padding: 0; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.rename-btn svg, .delete-btn svg, .export-btn svg { width: 13px; height: 13px; }
.rename-btn:hover { color: var(--dcdr-accent); background: var(--dcdr-accent-wash); }
.export-btn:hover { color: var(--dcdr-success); background: rgba(103,194,58,0.1); }
.delete-btn:hover { color: var(--dcdr-danger); background: rgba(245,108,108,0.1); }
.export-dropdown { flex-shrink: 0; }
.rename-input { flex: 1; font-size: 13px; border: 2px solid var(--dcdr-accent); border-radius: 8px; padding: 6px 10px; outline: none; min-width: 0; }

/* 聊天区域 */
.chat-area { flex: 1; display: flex; flex-direction: column; background: transparent; position: relative; overflow: hidden; }
/* min-height: 0 让消息区能在 flex 布局里真正收缩: 输入框长高时该被压缩的是消息区(它自己会滚动),
   而不是输入区 —— 否则长输入会被父容器的 overflow:hidden 直接裁掉 */
.messages-wrap { position: relative; flex: 1 1 0; min-height: 0; display: flex; }
.messages { flex: 1 1 0; min-height: 0; overflow-y: auto; padding: 24px 32px; display: flex; flex-direction: column; }
.turn { width: 100%; }
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

/* 提问卡片: 已并入消息正文列(由 MessageBubble 的插槽渲染), 只负责与上下文的间距 */
.ask-slot { margin: 4px 0 12px; }
.ask-slot :deep(.ask-card) { margin-left: 0; max-width: 100%; }

/* 续接块(提问已回答之后继续生成的这一条): 不重复头像, 与上一块贴紧 —— 视觉上是一轮回答的继续 */
.msg-item.msg-continuation { margin-top: -16px; }
.msg-item.msg-continuation .msg-content { padding-left: 54px; }

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
/* 思考: 由 ThinkingTrace 承载(思考文本 + 工具步骤同一条时间线) */
.msg-bubble { padding: 14px 18px; border-radius: 14px; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); font-size: 14px; line-height: 1.8; box-shadow: 0 2px 8px rgba(0,0,0,0.04); word-break: break-word; }
.msg-bubble.streaming { border-color: var(--dcdr-accent-wash); }
.msg-bubble :deep(pre) { background: var(--dcdr-code-bg); color: var(--dcdr-code-fg); padding: 16px; border-radius: 10px; overflow-x: auto; margin: 10px 0; }
.msg-bubble :deep(code) { background: var(--dcdr-bg-subtle); padding: 3px 8px; border-radius: 5px; font-size: 13px; color: var(--dcdr-accent-2); }
.msg-bubble :deep(p) { margin: 8px 0; }
.msg-bubble :deep(img) { max-width: 100%; border-radius: 8px; margin: 8px 0; }
/* 代码块: 语言标签 + 复制 / 运行预览 工具条(与 MessageBubble 内的历史消息保持一致) */
.msg-bubble :deep(.code-block) { margin: 10px 0; border-radius: 11px; overflow: hidden; border: 1px solid var(--dcdr-code-border); }
.msg-bubble :deep(.code-block pre) { margin: 0; border-radius: 0; }
.msg-bubble :deep(.code-block-bar) { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--dcdr-code-bar); border-bottom: 1px solid var(--dcdr-code-border); }
.msg-bubble :deep(.code-lang) { font-size: 11px; color: var(--dcdr-code-dim); font-family: monospace; letter-spacing: 0.5px; }
.msg-bubble :deep(.code-actions) { margin-left: auto; display: flex; gap: 6px; }
.msg-bubble :deep(.code-action) { border: 1px solid var(--dcdr-code-border); background: transparent; color: var(--dcdr-code-dim); font-size: 11px; line-height: 1.6; padding: 2px 10px; border-radius: 6px; cursor: pointer; transition: all 0.15s ease; }
.msg-bubble :deep(.code-action:hover) { border-color: var(--dcdr-accent); color: #eaf2ff; background: rgba(46,167,255,0.18); }
.msg-bubble :deep(.code-action.is-done) { border-color: var(--dcdr-success); color: var(--dcdr-code-ok); }
.msg-status { display: inline-flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 12px; background: var(--dcdr-accent-wash); color: var(--dcdr-accent); font-size: 14px; }
.msg-status .status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--dcdr-accent); animation: statusPulse 1s ease-in-out infinite; }
@keyframes statusPulse { 0%, 100% { opacity: 0.3; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1.2); } }
/* 联网搜索三步流水线面板 */
.search-pipeline { background: linear-gradient(135deg, var(--dcdr-surface) 0%, var(--dcdr-bg-subtle) 100%); border: 1px solid var(--dcdr-border); border-radius: 14px; padding: 14px 16px; margin-bottom: 4px; }
.pipeline-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.pipeline-icon { width: 16px; height: 16px; flex: none; }
.pipeline-title { font-weight: 700; color: var(--dcdr-text-1); font-size: 14px; }
.pipeline-done { margin-left: auto; font-size: 11px; color: var(--dcdr-success); background: #2e9e6b1a; padding: 2px 10px; border-radius: 20px; font-weight: 600; }
.pipeline-steps { display: flex; flex-direction: column; gap: 10px; }
.pstep { display: flex; gap: 12px; align-items: flex-start; opacity: 0.45; transition: all 0.3s ease; }
.pstep.active, .pstep.done { opacity: 1; }
.pstep-no { flex-shrink: 0; width: 26px; height: 26px; border-radius: 50%; background: var(--dcdr-surface-2); color: var(--dcdr-text-1); font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; transition: all 0.3s; }
.pstep.active .pstep-no { background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); box-shadow: 0 0 0 4px var(--dcdr-accent-wash); }
.pstep.done .pstep-no { background: linear-gradient(135deg, var(--dcdr-success) 0%, color-mix(in srgb, var(--dcdr-success) 70%, #000) 100%); }
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
/* flex: none: 输入区高度由内容(含自适应高度的 textarea)决定, 不参与压缩 */
/* 工作区徽标 + 变更入口: 现在位于输入框内部的第一行, 缩进由 .input-box 的内边距承担,
   因此这里不再给横向内边距; 徽标自身的内边距同样去掉, 避免双倍 */
.ws-toolbar { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ws-toolbar :deep(.ws-bar) { padding: 0; min-width: 0; }

.input-area { flex: none; padding: 16px 28px 20px; background: linear-gradient(180deg, transparent 0%, var(--dcdr-bg) 70%); }

/* 已选文件预览 */
.selected-files { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; max-width: 844px; margin-left: auto; margin-right: auto; }
.selected-file { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.file-preview { position: relative; width: 72px; height: 72px; border-radius: 10px; overflow: hidden; border: 2px solid var(--dcdr-accent-wash); background: var(--dcdr-bg-subtle); }
.file-preview img { width: 100%; height: 100%; object-fit: cover; }
.file-icon { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--dcdr-text-3); }
.file-icon svg { width: 26px; height: 26px; }
.remove-file { position: absolute; top: 2px; right: 2px; width: 18px; height: 18px; border-radius: 50%; border: none; background: rgba(0,0,0,0.5); color: #fff; cursor: pointer; display: flex; align-items: center; justify-content: center; padding: 0; }
.remove-file svg { width: 9px; height: 9px; }
.remove-file:hover { background: var(--dcdr-danger); }
.file-name { font-size: 10px; color: var(--dcdr-text-3); max-width: 72px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; }

/* 输入框 */
.input-box { max-width: 780px; margin: 0 auto; background: var(--dcdr-surface); border: 1px solid var(--dcdr-border); border-radius: var(--dcdr-radius-card); padding: 12px 14px 10px; display: flex; flex-direction: column; gap: 10px; box-shadow: var(--dcdr-shadow-card); transition: border-color 0.2s ease, box-shadow 0.2s ease; }
.input-box:focus-within { border-color: var(--dcdr-accent-line); box-shadow: 0 4px 25px var(--dcdr-accent-wash); }
.input-foot { display: flex; align-items: center; gap: 10px; }
.input-actions { display: flex; flex-wrap: wrap; gap: 6px; min-width: 0; }
.input-meta { margin-left: auto; font-family: var(--dcdr-font-mono); font-size: 9.5px; color: var(--dcdr-text-3); white-space: nowrap; }
.action-btn { display: flex; align-items: center; justify-content: center; width: 36px; height: 36px; border-radius: 10px; cursor: pointer; font-size: 18px; transition: all 0.2s; color: var(--dcdr-text-2); background: var(--dcdr-bg-subtle); border: none; }
.action-btn:hover { background: var(--dcdr-accent-wash); color: var(--dcdr-accent); }
.action-btn .ic { width: 17px; height: 17px; }
.send-btn .ic-send { width: 15px; height: 15px; }
.send-btn { display: inline-flex; align-items: center; justify-content: center; }
.action-btn.active { background: var(--dcdr-accent-wash); color: var(--dcdr-accent); box-shadow: inset 0 0 0 1px var(--dcdr-accent-line); }
/* 已选上下文 chips(V3.4): 加号面板收起后, "这轮带了什么"仍要可见 */
.picked-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.pchip {
  display: inline-flex; align-items: center; gap: 4px; max-width: 220px;
  padding: 3px 4px 3px 8px; border-radius: 999px;
  font-size: 11.5px; color: var(--dcdr-text-1);
  background: var(--dcdr-surface-faint); border: 1px solid var(--dcdr-border);
}
.pchip.expert { border-color: var(--dcdr-accent-line); background: var(--dcdr-accent-wash); }
.pchip-k { flex: none; font-size: 9.5px; letter-spacing: 0.3px; color: var(--dcdr-text-3); }
.pchip-x {
  flex: none; width: 16px; height: 16px; padding: 0; border: none; border-radius: 50%;
  background: transparent; color: var(--dcdr-text-3); cursor: pointer; font-size: 12px; line-height: 1;
  display: inline-flex; align-items: center; justify-content: center; transition: all 0.15s ease;
}
.pchip-x:hover { background: var(--dcdr-hover); color: var(--dcdr-text-1); }
.input-box textarea { flex: none; width: 100%; border: none; outline: none; resize: none; font-size: 13px; line-height: 1.65; max-height: 240px; font-family: inherit; padding: 2px 2px 0; background: transparent; color: var(--dcdr-text-1); }
/* 有附件预览时收窄上限: 预览区 + 超长输入框会把消息区挤没 */
.input-box textarea.has-files { max-height: 120px; }
.send-btn { flex: none; width: 34px; height: 34px; border: none; border-radius: var(--dcdr-radius-pill); color: #fff; cursor: pointer; font-size: 16px; display: flex; align-items: center; justify-content: center; transition: all 0.25s ease; background: linear-gradient(135deg, var(--dcdr-accent) 0%, var(--dcdr-accent-2) 100%); box-shadow: var(--dcdr-glow-accent); }
.send-btn:hover:not(:disabled) { filter: brightness(1.08); }
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }

.input-hint { text-align: center; font-size: 12px; color: var(--dcdr-text-3); margin-top: 8px; }

/* 上下文警告 */
.context-warning { max-width: 900px; margin: 12px auto 0; display: flex; align-items: center; gap: 10px; padding: 12px 16px; background: linear-gradient(135deg, var(--dcdr-surface) 0%, var(--dcdr-surface) 100%); border: 1px solid var(--dcdr-border); border-radius: 12px; font-size: 13px; color: var(--dcdr-warning); animation: fadeInUp 0.3s ease; }
.context-warning .cw-icon { width: 15px; height: 15px; flex: none; }
.context-warning .cw-text { flex: 1; line-height: 1.5; }
.context-warning .cw-new { border: none; background: linear-gradient(135deg, var(--dcdr-warning) 0%, color-mix(in srgb, var(--dcdr-warning) 78%, #fff) 100%); color: #fff; padding: 6px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 500; flex-shrink: 0; transition: all 0.2s; }
.context-warning .cw-new:hover { box-shadow: 0 3px 10px rgba(250,140,22,0.35); }
.context-warning .cw-close { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border: none; background: transparent; color: var(--dcdr-warning); cursor: pointer; padding: 0; border-radius: 6px; flex-shrink: 0; transition: all 0.2s; }
.context-warning .cw-close svg { width: 13px; height: 13px; }
.context-warning .cw-close:hover { background: var(--dcdr-bg-subtle); border-radius: 6px; }
</style>
