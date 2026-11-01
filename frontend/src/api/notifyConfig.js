import { api, unwrap } from './http'

// ===== V3.4 用户级推送配置 =====

// 我的推送配置: 返回全部渠道概览, Webhook 只回显掩码
export const getNotifyConfigs = () => unwrap(api.get('/api/notify-config'))

// 保存某渠道配置: webhookUrl / secret 留空表示保留原值
export const saveNotifyConfig = (data) => unwrap(api.put('/api/notify-config', data))

// 删除某渠道配置 (回退部署级默认)
export const deleteNotifyConfig = (channel) =>
  unwrap(api.delete(`/api/notify-config/${channel}`))

// 用已保存的个人配置发一条测试消息 (不受节流限制)
export const testNotifyConfig = (channel) =>
  unwrap(api.post(`/api/notify-config/test/${channel}`))
