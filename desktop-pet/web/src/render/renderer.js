/**
 * 渲染层接口约定（DESIGN.md §5.2 / §6.3）。
 * 本文件只定义 JSDoc 类型，不含运行时逻辑；任何渲染适配器需实现 Renderer。
 *
 * @typedef {Object} Transform
 * @property {number} dx      水平位移增量（逻辑像素）
 * @property {number} dy      垂直位移增量（逻辑像素）
 * @property {number} rot     旋转增量（度）
 * @property {number} sx      水平缩放倍率，恒等 1
 * @property {number} sy      垂直缩放倍率，恒等 1
 * @property {number} alpha   最终透明度，已钳制到 [0,1]
 *
 * @typedef {Object} PartSpec
 * @property {string} id
 * @property {string} src
 * @property {number} z
 * @property {number} x      pivot 点相对锚点的 x 偏移
 * @property {number} y      pivot 点相对锚点的 y 偏移
 * @property {number} w      显示宽
 * @property {number} h      显示高
 * @property {[number, number]} pivot
 * @property {number} [alpha] 基础透明度，默认 1
 * @property {boolean} [flip]
 *
 * @typedef {Object} Skin
 * @property {string} id
 * @property {{w:number,h:number,anchor:{x:number,y:number}}} canvas
 * @property {{x:number,y:number,w:number,h:number}} hitbox
 * @property {PartSpec[]} parts
 *
 * @typedef {Object} Renderer
 * @property {(skin: Skin, baseUrl: string) => Promise<void>} load 加载部件素材
 * @property {(transforms: Map<string, Transform>) => boolean} draw 绘制一帧，返回是否实际重绘
 * @property {(x: number, y: number) => boolean} hitTest 命中检测（相对舞台左上角）
 * @property {() => void} destroy 释放资源
 */

export {};
