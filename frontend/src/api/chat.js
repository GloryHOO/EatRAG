/**
 * 问答相关 API —— SSE 流式接口封装
 *
 * 后端 SSE 事件格式：
 *   event: <type>
 *   data: <JSON>
 * JSON 形如：
 *   {"type":"message","content":"片段"}
 *   {"type":"done","routeType":"detail","sources":[{"dishName":"...","category":"...","difficulty":"..."}]}
 */

/**
 * 发起流式问答
 * @param {string} question 用户问题
 * @param {object} callbacks 回调集合
 * @param {(content: string) => void} callbacks.onMessage 收到文本片段时调用
 * @param {(payload: object) => void} callbacks.onDone     收到完成事件时调用（含 routeType 和 sources）
 * @param {(error: Error) => void} callbacks.onError      出错时调用
 * @returns {{ close: () => void }} 返回带 close() 方法的句柄，用于取消连接
 */
export function chatStream(question, { onMessage, onDone, onError }) {
  // 用 EventSource 建立 SSE 连接，question 拼在查询参数中
  const source = new EventSource('/api/chat/stream?question=' + encodeURIComponent(question))

  // 收到文本片段：事件名为 message
  source.addEventListener('message', (event) => {
    try {
      const payload = JSON.parse(event.data)
      if (onMessage) {
        onMessage(payload.content)
      }
    } catch (err) {
      if (onError) {
        onError(new Error('解析消息失败: ' + err.message))
      }
    }
  })

  // 收到完成事件：事件名为 done，payload 含 routeType 和 sources
  source.addEventListener('done', (event) => {
    try {
      const payload = JSON.parse(event.data)
      if (onDone) {
        onDone(payload)
      }
    } catch (err) {
      if (onError) {
        onError(new Error('解析完成事件失败: ' + err.message))
      }
    } finally {
      // 收到完成事件后主动关闭连接，避免 EventSource 自动重连导致重复请求
      source.close()
    }
  })

  // 后端显式发送 error 事件
  source.addEventListener('error', (event) => {
    // EventSource 就绪状态为 CLOSED 时通常是连接异常/结束
    if (source.readyState === EventSource.CLOSED) {
      if (onError) {
        onError(new Error('SSE 连接已关闭'))
      }
    }
  })

  // EventSource 自身的连接错误
  source.onerror = () => {
    if (onError) {
      onError(new Error('SSE 连接异常'))
    }
  }

  // 返回句柄，调用方可通过 close() 主动取消
  return {
    close() {
      source.close()
    }
  }
}