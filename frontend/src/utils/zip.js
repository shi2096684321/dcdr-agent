// 通用格式化工具
export function formatDuration(ms) {
  if (!ms && ms !== 0) return '-'
  const s = Math.round(ms / 1000)
  if (s < 60) return s + 's'
  const m = Math.floor(s / 60)
  return `${m}m${s % 60}s`
}

/**
 * 最小 ZIP 写入器（store 模式，不压缩）。
 *
 * 只为创作页「导出 ZIP」服务：导出的都是 PNG / JPEG / MP4，
 * 本身已高度压缩，store 模式体积损失很小，没必要为此引入 jszip。
 * 只写本地头 + 中央目录 + EOCD，遵循 PKZIP APPNOTE 最小子集。
 */

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** 打包为 zip Blob（store，无压缩）。entries: [{ name, data: Uint8Array }] */
export function buildZip(entries) {
  const encoder = new TextEncoder()
  const now = new Date()
  // DOS 时间戳：日期以 1980 为纪元，时间精度 2 秒
  const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1)
  const dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()

  const chunks = []
  const central = []
  let offset = 0

  for (const entry of entries) {
    const nameBytes = encoder.encode(entry.name)
    const crc = crc32(entry.data)

    const local = new Uint8Array(30 + nameBytes.length)
    const lv = new DataView(local.buffer)
    lv.setUint32(0, 0x04034b50, true)  // local file header 签名
    lv.setUint16(4, 20, true)          // 解压所需版本
    lv.setUint16(6, 0x0800, true)      // 标志位: 文件名 UTF-8
    lv.setUint16(8, 0, true)           // 压缩方式: store
    lv.setUint16(10, dosTime, true)
    lv.setUint16(12, dosDate, true)
    lv.setUint32(14, crc, true)
    lv.setUint32(18, entry.data.length, true)  // 压缩后大小
    lv.setUint32(22, entry.data.length, true)  // 原始大小
    lv.setUint16(26, nameBytes.length, true)
    lv.setUint16(28, 0, true)                  // 扩展字段长度
    local.set(nameBytes, 30)
    chunks.push(local, entry.data)

    const cd = new Uint8Array(46 + nameBytes.length)
    const cv = new DataView(cd.buffer)
    cv.setUint32(0, 0x02014b50, true)  // central directory header 签名
    cv.setUint16(4, 20, true)          // 制作版本
    cv.setUint16(6, 20, true)          // 解压所需版本
    cv.setUint16(8, 0x0800, true)
    cv.setUint16(10, 0, true)
    cv.setUint16(12, dosTime, true)
    cv.setUint16(14, dosDate, true)
    cv.setUint32(16, crc, true)
    cv.setUint32(20, entry.data.length, true)
    cv.setUint32(24, entry.data.length, true)
    cv.setUint16(28, nameBytes.length, true)
    cv.setUint16(30, 0, true)          // 扩展字段长度
    cv.setUint16(32, 0, true)          // 注释长度
    cv.setUint16(34, 0, true)          // 磁盘号
    cv.setUint16(36, 0, true)          // 内部属性
    cv.setUint32(38, 0, true)          // 外部属性
    cv.setUint32(42, offset, true)     // 本地头相对偏移
    cd.set(nameBytes, 46)
    central.push(cd)

    offset += local.length + entry.data.length
  }

  const cdSize = central.reduce((a, b) => a + b.length, 0)
  const eocd = new Uint8Array(22)
  const ev = new DataView(eocd.buffer)
  ev.setUint32(0, 0x06054b50, true)            // EOCD 签名
  ev.setUint16(4, 0, true)                     // 磁盘号
  ev.setUint16(6, 0, true)                     // 中央目录起始磁盘
  ev.setUint16(8, entries.length, true)        // 本磁盘条目数
  ev.setUint16(10, entries.length, true)       // 条目总数
  ev.setUint32(12, cdSize, true)               // 中央目录大小
  ev.setUint32(16, offset, true)               // 中央目录偏移
  ev.setUint16(20, 0, true)                    // 注释长度

  return new Blob([...chunks, ...central, eocd], { type: 'application/zip' })
}

/** 触发浏览器下载 */
export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 延迟回收, 确保 Safari 等浏览器已开始读取
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
