<script setup lang="ts">
import { ref, nextTick } from 'vue'

// ============ 类型定义 ============

/** SSE事件数据 */
interface SseEventData {
  ticketId?: string
  conversationId?: string
  message?: string
  intent?: string
  intentDescription?: string
  confidence?: number
  reasoning?: string
  tools?: string[]
  toolName?: string
  success?: boolean
  data?: string
  status?: string
  error?: string
  // query改写
  original?: string
  rewritten?: string
  // 澄清（问题补充）
  question?: string
  missingSlots?: string[]
  // 人机确认
  actionId?: string
  arguments?: Record<string, unknown>
  prompt?: string
  decision?: string
}

/** 待确认的敏感操作（人机交互） */
interface PendingConfirmation {
  actionId: string
  toolName: string
  prompt: string
}

/** 聊天消息 */
interface ChatMessage {
  id: number
  type: 'user' | 'system'
  content: string
  steps: ProcessingStep[]
  timestamp: Date
  pendingConfirmation?: PendingConfirmation | null
}

/** 处理步骤（展示AI处理过程） */
interface ProcessingStep {
  type: string
  label: string
  data: Record<string, unknown>
  timestamp?: Date
}

// ============ 状态管理 ============

const messages = ref<ChatMessage[]>([])
const inputContent = ref('')
const isProcessing = ref(false)
const customerId = ref('C' + Math.random().toString(36).substring(2, 8))
// 会话ID：首轮由后端生成并在SSE事件中回传，之后每轮都带上它，
// 这样同一个聊天窗口的所有消息才属于"同一个会话"（多轮记忆/澄清/确认的前提）
const conversationId = ref('')
let messageIdCounter = 0

// ============ SSE连接处理 ============

/**
 * 通过SSE创建工单并接收实时处理事件
 *
 * 通信协议：
 * 1. POST请求创建工单，后端返回SSE事件流
 * 2. 前端通过EventSource逐条接收事件
 * 3. 根据事件类型更新UI（显示处理步骤和最终结果）
 */
function createTicketViaSse(content: string) {
  isProcessing.value = true

  const msgId = ++messageIdCounter
  const systemMessage: ChatMessage = {
    id: msgId,
    type: 'system',
    content: '正在处理...',
    steps: [],
    timestamp: new Date()
  }
  messages.value.push(systemMessage)

  // 构建SSE请求
  // 使用fetch + ReadableStream处理POST方式的SSE（EventSource只支持GET）
  const requestBody = JSON.stringify({
    customerId: customerId.value,
    content: content,
    // 带上会话ID续接多轮；首轮为空则由后端生成
    ...(conversationId.value ? { conversationId: conversationId.value } : {})
  })

  fetch('/api/tickets/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: requestBody
  }).then(response => {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    function processChunk(): Promise<void> {
      return reader.read().then(({ done, value }) => {
        if (done) {
          isProcessing.value = false
          return
        }

        buffer += decoder.decode(value, { stream: true })

        // 解析SSE格式：event: xxx\ndata: {...}\n\n
        const lines = buffer.split('\n')
        buffer = ''

        let currentEvent = ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.substring(6).trim()
          } else if (line.startsWith('data:')) {
            const dataStr = line.substring(5).trim()
            try {
              const data = JSON.parse(dataStr) as SseEventData
              handleSseEvent(msgId, currentEvent, data)
            } catch {
              // 数据不完整，放回buffer
              buffer = line + '\n' + buffer
            }
          } else if (line === '') {
            currentEvent = ''
          } else {
            // 可能是多行data的一部分
            buffer = line + '\n' + buffer
          }
        }

        return processChunk()
      })
    }

    return processChunk()
  }).catch(error => {
    console.error('SSE连接失败:', error)
    updateMessage(msgId, '连接失败: ' + error.message)
    isProcessing.value = false
  })
}

/**
 * 处理SSE事件，更新消息和处理步骤
 */
function handleSseEvent(msgId: number, eventType: string, data: SseEventData) {
  const msg = messages.value.find(m => m.id === msgId)
  if (!msg) return

  // 每个事件都带conversationId：首轮据此记下后端分配的会话ID，之后复用它续接多轮
  if (data.conversationId && !conversationId.value) {
    conversationId.value = data.conversationId
  }

  switch (eventType) {
    case 'ai_analyzing':
      msg.steps.push({
        type: 'analyzing',
        label: 'AI分析中...',
        data: data as unknown as Record<string, unknown>,
        timestamp: new Date()
      })
      msg.content = 'AI正在分析您的问题...'
      break

    case 'ai_analyzed':
      msg.steps.push({
        type: 'analyzed',
        label: `意图识别: ${data.intentDescription || data.intent}`,
        data: data as unknown as Record<string, unknown>
      })
      msg.content = `已识别意图：${data.intentDescription || data.intent}（置信度: ${((data.confidence || 0) * 100).toFixed(0)}%）`
      break

    case 'tool_dispatching':
      msg.steps.push({
        type: 'dispatching',
        label: `调用工具: ${(data.tools || []).join(', ')}`,
        data: data as unknown as Record<string, unknown>
      })
      msg.content = '正在调用业务工具查询数据...'
      break

    case 'tool_result':
      msg.steps.push({
        type: 'result',
        label: `${data.toolName}: ${data.success ? '成功' : '失败'}`,
        data: data as unknown as Record<string, unknown>
      })
      break

    case 'query_rewritten':
      msg.steps.push({
        type: 'rewritten',
        label: `query改写: ${data.original} → ${data.rewritten}`,
        data: data as unknown as Record<string, unknown>
      })
      break

    case 'clarification':
      msg.content = data.question || '请补充必要的信息。'
      msg.steps.push({
        type: 'clarify',
        label: `需要补充信息: ${(data.missingSlots || []).join(', ') || '-'}`,
        data: data as unknown as Record<string, unknown>
      })
      break

    case 'confirmation_required':
      msg.content = data.prompt || '该操作需要您的确认。'
      msg.pendingConfirmation = {
        actionId: data.actionId || '',
        toolName: data.toolName || '',
        prompt: data.prompt || ''
      }
      msg.steps.push({
        type: 'confirm',
        label: `等待确认敏感操作: ${data.toolName}`,
        data: data as unknown as Record<string, unknown>
      })
      break

    case 'response':
      msg.content = data.message || '处理完成'
      msg.steps.push({
        type: 'response',
        label: '生成回复',
        data: data as unknown as Record<string, unknown>
      })
      break

    case 'error':
      msg.content = data.message || '处理失败'
      msg.steps.push({
        type: 'error',
        label: `错误: ${data.error || data.message}`,
        data: data as unknown as Record<string, unknown>
      })
      break

    case 'done':
      isProcessing.value = false
      msg.steps.push({
        type: 'done',
        label: `处理完成 [${data.status}]`,
        data: data as unknown as Record<string, unknown>
      })
      break
  }

  scrollToBottom()
}

function updateMessage(msgId: number, content: string) {
  const msg = messages.value.find(m => m.id === msgId)
  if (msg) {
    msg.content = content
  }
}

/**
 * 人机交互：用户批准/拒绝一个挂起的敏感操作（如取消订单）
 * 调用后端确认接口，真正执行或放弃该操作
 */
async function confirmAction(msgId: number, approved: boolean) {
  const msg = messages.value.find(m => m.id === msgId)
  if (!msg || !msg.pendingConfirmation) return

  const actionId = msg.pendingConfirmation.actionId
  // 立即清除按钮，防止重复提交
  msg.pendingConfirmation = null
  isProcessing.value = true

  try {
    const resp = await fetch(
      `/api/conversations/${conversationId.value}/actions/${actionId}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approved })
      }
    )
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    const result = await resp.json()
    msg.content = result.message || (approved ? '操作已执行。' : '已取消该操作。')
    msg.steps.push({
      type: approved ? 'response' : 'done',
      label: `${approved ? '已确认执行' : '已拒绝'}: ${result.decision || ''}`,
      data: result as unknown as Record<string, unknown>
    })
  } catch (e) {
    msg.content = '确认操作失败: ' + (e as Error).message
  } finally {
    isProcessing.value = false
    scrollToBottom()
  }
}

/**
 * 开启新会话：清空会话ID与消息，下一条消息将进入一个全新的会话
 */
function startNewConversation() {
  if (isProcessing.value) return
  conversationId.value = ''
  messages.value = []
}

// ============ 用户交互 ============

function sendMessage() {
  const content = inputContent.value.trim()
  if (!content || isProcessing.value) return

  // 用户没有点确认/取消而是直接发新消息：后端会放弃挂起的操作，这里同步移除按钮
  messages.value.forEach(m => { m.pendingConfirmation = null })

  // 添加用户消息
  messages.value.push({
    id: ++messageIdCounter,
    type: 'user',
    content,
    steps: [],
    timestamp: new Date()
  })

  inputContent.value = ''
  scrollToBottom()

  // 通过SSE发起工单处理
  createTicketViaSse(content)
}

function scrollToBottom() {
  nextTick(() => {
    const container = document.querySelector('.chat-messages')
    if (container) {
      container.scrollTop = container.scrollHeight
    }
  })
}

/**
 * 获取步骤的显示图标
 */
function getStepIcon(type: string): string {
  const icons: Record<string, string> = {
    analyzing: '...',
    analyzed: '>',
    rewritten: '~>',
    dispatching: '>>',
    result: '::',
    clarify: '??',
    confirm: '(!)',
    response: 'OK',
    error: '!!',
    done: '[OK]'
  }
  return icons[type] || '--'
}

// 预设快捷问题
const quickQuestions = [
  '我的订单10086为什么三天还没发货？',
  '我要退掉昨天买的耳机',
  '帮我查一下订单20250915的状态'
]

function useQuickQuestion(question: string) {
  inputContent.value = question
  sendMessage()
}
</script>

<template>
  <div class="chat-container">
    <!-- 头部 -->
    <header class="chat-header">
      <div class="header-left">
        <h1>AI智能客服</h1>
        <span class="subtitle">DDD + SSE + Function Calling</span>
      </div>
      <div class="header-right">
        <span class="conv-id" :title="conversationId || '尚未开始会话'">
          {{ conversationId ? '会话 ' + conversationId.slice(0, 8) : '新会话' }}
        </span>
        <button
          class="new-conv-btn"
          @click="startNewConversation"
          :disabled="isProcessing"
          title="清空当前会话，开启一个全新会话"
        >
          新会话
        </button>
        <span class="status-dot"></span>
        <span class="status-text">在线</span>
      </div>
    </header>

    <!-- 消息列表 -->
    <div class="chat-messages">
      <!-- 欢迎消息 -->
      <div v-if="messages.length === 0" class="welcome">
        <div class="welcome-icon">AI</div>
        <h2>欢迎使用AI智能客服系统</h2>
        <p>请输入您的问题，AI将自动识别意图并处理</p>
        <div class="quick-questions">
          <button
            v-for="q in quickQuestions"
            :key="q"
            class="quick-btn"
            @click="useQuickQuestion(q)"
          >
            {{ q }}
          </button>
        </div>
      </div>

      <!-- 消息气泡 -->
      <div
        v-for="msg in messages"
        :key="msg.id"
        class="message"
        :class="msg.type"
      >
        <div class="message-avatar">
          {{ msg.type === 'user' ? 'U' : 'AI' }}
        </div>
        <div class="message-body">
          <div class="message-content">{{ msg.content }}</div>

          <!-- 处理步骤（可折叠） -->
          <div v-if="msg.steps.length > 0" class="message-steps">
            <details>
              <summary>处理过程 ({{ msg.steps.length }}步)</summary>
              <div
                v-for="(step, idx) in msg.steps"
                :key="idx"
                class="step"
                :class="step.type"
              >
                <span class="step-icon">{{ getStepIcon(step.type) }}</span>
                <span class="step-label">{{ step.label }}</span>
              </div>
            </details>
          </div>

          <!-- 人机交互：敏感操作确认按钮 -->
          <div v-if="msg.pendingConfirmation" class="confirm-actions">
            <button
              class="confirm-btn approve"
              @click="confirmAction(msg.id, true)"
              :disabled="isProcessing"
            >
              确认执行
            </button>
            <button
              class="confirm-btn reject"
              @click="confirmAction(msg.id, false)"
              :disabled="isProcessing"
            >
              取消
            </button>
          </div>

          <div class="message-time">
            {{ msg.timestamp.toLocaleTimeString() }}
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="chat-input">
      <input
        v-model="inputContent"
        @keyup.enter="sendMessage"
        :disabled="isProcessing"
        placeholder="请输入您的问题..."
        class="input-field"
      />
      <button
        @click="sendMessage"
        :disabled="isProcessing || !inputContent.trim()"
        class="send-btn"
      >
        {{ isProcessing ? '处理中...' : '发送' }}
      </button>
    </div>
  </div>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f0f2f5;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
}

#app {
  width: 100%;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
}

.chat-container {
  width: 100%;
  max-width: 800px;
  height: 100vh;
  max-height: 700px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

/* 头部 */
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.header-left h1 {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}

.subtitle {
  font-size: 12px;
  opacity: 0.8;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 8px;
  height: 8px;
  background: #4ade80;
  border-radius: 50%;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.status-text {
  font-size: 13px;
}

.conv-id {
  font-size: 12px;
  opacity: 0.85;
  font-family: monospace;
  background: rgba(255, 255, 255, 0.15);
  padding: 2px 8px;
  border-radius: 10px;
}

.new-conv-btn {
  font-size: 12px;
  color: #fff;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 6px;
  padding: 3px 10px;
  cursor: pointer;
  transition: background 0.2s;
}

.new-conv-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.35);
}

.new-conv-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 消息列表 */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 欢迎页 */
.welcome {
  text-align: center;
  padding: 40px 20px;
  color: #666;
}

.welcome-icon {
  width: 60px;
  height: 60px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: bold;
  margin: 0 auto 16px;
}

.welcome h2 {
  font-size: 18px;
  color: #333;
  margin-bottom: 8px;
}

.welcome p {
  font-size: 14px;
  margin-bottom: 20px;
}

.quick-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 400px;
  margin: 0 auto;
}

.quick-btn {
  padding: 10px 16px;
  background: #f5f5f5;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  text-align: left;
  transition: all 0.2s;
  color: #444;
}

.quick-btn:hover {
  background: #eee;
  border-color: #667eea;
}

/* 消息气泡 */
.message {
  display: flex;
  gap: 10px;
  max-width: 85%;
}

.message.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.message.user .message-avatar {
  background: #667eea;
  color: white;
}

.message.system .message-avatar {
  background: #f0f0f0;
  color: #666;
}

.message-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-content {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.message.user .message-content {
  background: #667eea;
  color: white;
  border-bottom-right-radius: 4px;
}

.message.system .message-content {
  background: #f5f5f5;
  color: #333;
  border-bottom-left-radius: 4px;
}

/* 处理步骤 */
.message-steps {
  font-size: 12px;
}

.message-steps details {
  background: #fafafa;
  border: 1px solid #eee;
  border-radius: 6px;
  padding: 6px 10px;
}

.message-steps summary {
  cursor: pointer;
  color: #888;
  font-size: 12px;
  user-select: none;
}

.message-steps summary:hover {
  color: #667eea;
}

.step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
  color: #555;
}

.step-icon {
  font-family: monospace;
  font-size: 11px;
  color: #667eea;
  min-width: 24px;
}

.step.error .step-icon {
  color: #ef4444;
}

.step.done .step-icon {
  color: #22c55e;
}

.step.clarify .step-icon {
  color: #f59e0b;
}

.step.confirm .step-icon {
  color: #ea580c;
}

/* 人机确认按钮 */
.confirm-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
}

.confirm-btn {
  font-size: 13px;
  padding: 6px 16px;
  border-radius: 8px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: opacity 0.2s;
}

.confirm-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.confirm-btn.approve {
  background: #ef4444;
  color: #fff;
}

.confirm-btn.reject {
  background: #f0f0f0;
  color: #555;
  border-color: #ddd;
}

.confirm-btn:hover:not(:disabled) {
  opacity: 0.85;
}

.message-time {
  font-size: 11px;
  color: #aaa;
}

.message.user .message-time {
  text-align: right;
}

/* 输入区域 */
.chat-input {
  display: flex;
  gap: 10px;
  padding: 16px 20px;
  border-top: 1px solid #eee;
  background: #fafafa;
}

.input-field {
  flex: 1;
  padding: 10px 16px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.input-field:focus {
  border-color: #667eea;
}

.input-field:disabled {
  background: #f0f0f0;
}

.send-btn {
  padding: 10px 20px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.send-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 滚动条美化 */
.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: transparent;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: #ddd;
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: #bbb;
}
</style>
