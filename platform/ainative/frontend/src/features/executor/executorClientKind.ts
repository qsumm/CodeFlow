/**
 * 客户端类型判定（纯函数）：Qoder 系需要完整的四个启动参数，其余客户端只要记忆模式。
 * 单独成文件是为了让弹窗组件能直接引用，而不反向依赖页面壳（避免循环 import）。
 */

/** Qoder CLI / Qoder CLI CN 需要模型、推理强度、上下文窗口三项启动参数。 */
export function isQoderClientKind(kind?: string | null): boolean {
  return kind === 'QODER_CLI' || kind === 'QODER_CN_CLI';
}

// 0.8 及更早版本创建的执行器 client_kind 可为 NULL/空白（历史数据），这类执行器需要先补齐类型
export function isClientKindMissing(kind?: string | null): boolean {
  return !kind || !kind.trim();
}
