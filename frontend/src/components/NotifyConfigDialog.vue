<template>
  <el-dialog
    :model-value="modelValue"
    title="推送配置"
    width="640px"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="nc-tip">
      配置的是<b>你自己的</b>机器人：定时任务结果只推到你的群，其他人看不到你的任务内容。
      Webhook 里带着机器人密钥，服务端加密存储，这里只显示掩码。
    </div>

    <el-tabs v-model="active" class="nc-tabs" v-loading="loading">
      <el-tab-pane v-for="c in configs" :key="c.channel" :name="c.channel">
        <!-- tab 上带"已配置"圆点: 只配了钉钉却在企微页看到"未配置", 很容易被当成"没保存上" -->
        <template #label>
          <span class="nc-tab-label">
            {{ c.channelLabel }}
            <span v-if="c.configured" class="nc-dot" :class="{ off: !c.enabled }" />
          </span>
        </template>

        <div class="nc-status">
          <el-tag size="small" :type="c.configured ? 'success' : 'info'">
            {{ c.configured ? (c.enabled ? '已配置 · 启用中' : '已配置 · 已停用') : '未配置' }}
          </el-tag>
          <span v-if="c.configured" class="nc-hint">当前已保存的地址见下方 Webhook 一栏</span>
          <span v-else-if="c.globalFallbackAvailable" class="nc-hint">当前使用部署级默认机器人</span>
          <span v-else class="nc-hint">未配置前该渠道无法推送</span>
        </div>

        <el-form label-width="96px" class="nc-form">
          <el-form-item label="Webhook">
            <el-input
              v-model="form.webhookUrl"
              :placeholder="c.configured ? '留空则保留已保存的地址；要修改请整条重新粘贴' : c.webhookExample"
              clearable
            >
              <!-- 输入框空着不等于"没配置": 用后缀明确告诉用户保存时会沿用旧值 -->
              <template v-if="c.configured && !form.webhookUrl" #suffix>
                <span class="nc-keep">沿用已保存</span>
              </template>
            </el-input>
            <!-- 已保存地址的回显: 凭据不回传明文, 所以只能给掩码 —— 但至少让人看得见"确实存下来了" -->
            <div v-if="c.configured" class="nc-saved">
              已保存：<code>{{ c.webhookMasked }}</code>
              <span v-if="c.updatedAt" class="nc-updated">更新于 {{ formatDate(c.updatedAt) }}</span>
            </div>
          </el-form-item>
          <el-form-item v-if="c.supportsSecret" label="加签密钥">
            <el-input
              v-model="form.secret"
              type="password"
              show-password
              :placeholder="c.hasSecret ? '留空则保留已保存的密钥' : 'SEC 开头；机器人选「加签」时必填'"
            />
            <div v-if="c.hasSecret" class="nc-saved">已保存加签密钥（出于安全不回显，留空则沿用）</div>
          </el-form-item>
          <el-form-item v-if="c.supportsSecret" label="关键词">
            <el-input v-model="form.keyword" placeholder="机器人选「自定义关键词」时填写，会追加到消息末尾" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
            <span class="nc-hint" style="margin-left: 10px">关闭后回退到部署级默认机器人</span>
          </el-form-item>
        </el-form>

        <div class="nc-actions">
          <el-button type="primary" size="small" :loading="saving" @click="save(c)">保存</el-button>
          <el-button size="small" :loading="testing" :disabled="!c.configured" @click="sendTest(c)">
            发送测试消息
          </el-button>
          <el-button size="small" type="danger" plain :disabled="!c.configured" @click="remove(c)">
            删除
          </el-button>
        </div>

        <div class="nc-safety">
          安全限制：只接受 https 且域名必须是该渠道官方域名（{{ hostOf(c) }}），
          避免把服务端变成可向任意地址发请求的代理；只有保存后才会真正生效。
        </div>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getNotifyConfigs, saveNotifyConfig, deleteNotifyConfig, testNotifyConfig, extractMessage
} from '@/api'
import { formatDate } from '@/utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const configs = ref([])
const active = ref('wecom_bot')
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)

const form = reactive({ webhookUrl: '', secret: '', keyword: '', enabled: true })

/** 从后端给的示例地址里取域名, 避免前端再维护一份渠道→域名映射 */
function hostOf(c) {
  try {
    return new URL(c.webhookExample).host
  } catch (e) {
    return '官方域名'
  }
}

function currentConfig() {
  return configs.value.find((c) => c.channel === active.value)
}

// 切 tab 时清空"待填入"的凭据, 只带上非敏感的启用状态
function syncForm() {
  const c = currentConfig()
  form.webhookUrl = ''
  form.secret = ''
  form.keyword = c?.keyword || ''
  // 后端对"未配置"的渠道恒返回 enabled=false(避免让人误以为已经在推), 那个值只用于**展示**。
  // 直接拿它当表单初值, 就会出现"填好 webhook 保存 → 状态是已停用 → 一条都收不到" ——
  // 现场数据里第一次保存确实落成了 enabled=false。故只有已配置过的渠道才沿用它的开关值。
  form.enabled = c && c.configured ? c.enabled : true
}

watch(active, syncForm)
watch(() => props.modelValue, (v) => { if (v) load() })

async function load() {
  loading.value = true
  try {
    configs.value = await getNotifyConfigs()
    // 默认停在"已配置"的渠道上。
    // 后端会把所有渠道都返回(未配置的也返回, 便于展示"去配置"), 所以不能简单地取第一个 ——
    // 只配了钉钉的用户打开弹窗落在"企业微信 · 未配置"上, 看上去就像配置没保存住。
    const currentCfg = configs.value.find((c) => c.channel === active.value)
    if (!currentCfg || !currentCfg.configured) {
      const firstConfigured = configs.value.find((c) => c.configured)
      if (firstConfigured) active.value = firstConfigured.channel
      else if (!currentCfg && configs.value.length) active.value = configs.value[0].channel
    }
    syncForm()
  } catch (e) {
    ElMessage.error(extractMessage(e) || '加载推送配置失败')
  } finally {
    loading.value = false
  }
}

async function save(c) {
  saving.value = true
  try {
    await saveNotifyConfig({
      channel: c.channel,
      webhookUrl: form.webhookUrl.trim() || null,
      secret: form.secret.trim() || null,
      // 关键词不是凭据, 空串即"清空"; 传 null 会被后端当成"没改"
      keyword: form.keyword.trim(),
      enabled: form.enabled
    })
    ElMessage.success('已保存')
    await load()
    emit('saved')
  } catch (e) {
    ElMessage.error(extractMessage(e) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function sendTest(c) {
  testing.value = true
  try {
    const r = await testNotifyConfig(c.channel)
    if (r && r.success) ElMessage.success('测试消息已发出，请查看群消息')
    else ElMessage.error((r && (r.error || r.reason)) || '发送失败')
  } catch (e) {
    ElMessage.error(extractMessage(e) || '测试失败')
  } finally {
    testing.value = false
  }
}

async function remove(c) {
  try {
    await ElMessageBox.confirm(`确定删除「${c.channelLabel}」的个人配置吗？删除后将回退部署级默认。`, '提示',
      { type: 'warning' })
    await deleteNotifyConfig(c.channel)
    ElMessage.success('已删除')
    await load()
    emit('saved')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractMessage(e) || '删除失败')
  }
}
</script>

<style scoped>
.nc-tip {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--dcdr-text-2);
  background: var(--dcdr-bg-subtle);
  border: 1px solid var(--dcdr-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
}

.nc-tabs {
  margin-top: 4px;
}

/* tab 上的"已配置"圆点: 绿色=启用中, 灰色=已配置但停用 */
.nc-tab-label { display: inline-flex; align-items: center; gap: 5px; }
.nc-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: #2fbf71; box-shadow: 0 0 0 2px rgba(47, 191, 113, 0.16);
}
.nc-dot.off { background: var(--dcdr-text-3); box-shadow: none; }

/* 输入框空着时的"沿用已保存"提示, 避免被理解成"没有回显" */
.nc-keep {
  font-size: 11.5px;
  color: var(--dcdr-text-3);
  white-space: nowrap;
}

.nc-saved {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--dcdr-text-2);
}
.nc-saved code {
  font-family: var(--dcdr-font-mono);
  font-size: 11.5px;
  color: var(--dcdr-accent-soft, var(--dcdr-text-1));
  word-break: break-all;
}
.nc-updated { margin-left: 8px; color: var(--dcdr-text-3); }

.nc-status {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.nc-hint {
  font-size: 12px;
  color: var(--dcdr-text-3);
}

.nc-form {
  margin-bottom: 4px;
}

.nc-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.nc-safety {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--dcdr-border);
  font-size: 12px;
  line-height: 1.6;
  color: var(--dcdr-text-3);
}
</style>
