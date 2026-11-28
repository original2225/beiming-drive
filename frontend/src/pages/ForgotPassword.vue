<template>
  <div class="login-wrap">
    <div class="login-card card">
      <div class="brand">
        <img src="/whale-flat-128.png" alt="北冥云盘" class="logo" />
        <div>
          <h1>找回密码</h1>
          <p class="muted">回答安全问题，就能重设一个新密码</p>
        </div>
      </div>

      <!-- 三步的进度条。用户随时知道自己走到哪一步 -->
      <div class="steps">
        <div v-for="(s, i) in stepLabels" :key="i" class="step"
             :class="{ on: step === i + 1, done: step > i + 1 }">
          <span class="step-dot">{{ step > i + 1 ? '✓' : i + 1 }}</span>
          <span class="step-txt">{{ s }}</span>
        </div>
      </div>

      <!-- ========== 第 1 步：填邮箱，取问题 ========== -->
      <form v-if="step === 1" @submit.prevent="loadQuestions">
        <label>
          <span>注册邮箱</span>
          <input v-model="email" class="input" type="email" placeholder="you@beiming.games" />
        </label>
        <div v-if="error" class="err">{{ error }}</div>
        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          <span v-if="busy" class="spinner"></span>
          <span>{{ busy ? '查询中…' : '下一步' }}</span>
        </button>
      </form>

      <!-- ========== 第 2 步：回答安全问题 ========== -->
      <form v-else-if="step === 2" @submit.prevent="submitAnswers">
        <p class="hint">这些问题是你当初自己设的。答案不分大小写，首尾空格无所谓。</p>
        <label v-for="q in questions" :key="q.slot">
          <span>{{ q.question }}</span>
          <input v-model="answers[q.slot]" class="input" :placeholder="'回答第 ' + (q.slot + 1) + ' 题'" />
        </label>
        <div v-if="error" class="err">{{ error }}</div>
        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          <span v-if="busy" class="spinner"></span>
          <span>{{ busy ? '验证中…' : '验证答案' }}</span>
        </button>
      </form>

      <!-- ========== 第 3 步：设新密码 ========== -->
      <form v-else @submit.prevent="doReset">
        <p class="hint">验证通过。设一个新密码，旧密码立刻失效，所有设备都会被登出。</p>
        <label>
          <span>新密码</span>
          <input v-model="newPw" class="input" type="password" placeholder="至少 8 位" />
        </label>
        <label>
          <span>再输一次</span>
          <input v-model="newPw2" class="input" type="password" placeholder="确认新密码" />
        </label>
        <div v-if="error" class="err">{{ error }}</div>
        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          <span v-if="busy" class="spinner"></span>
          <span>{{ busy ? '提交中…' : '设置新密码' }}</span>
        </button>
      </form>

      <!-- 账号没设过安全问题：不能编题让他答，如实说明 -->
      <div v-if="noQuestions" class="noq">
        这个账号还没有设置安全问题，没法用这种方式找回。<br />
        请联系管理员（超级管理员）帮你重置密码。
      </div>

      <div class="foot">
        <router-link to="/login">← 返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'

const router = useRouter()

const stepLabels = ['填邮箱', '答问题', '设新密码']
const step = ref(1)
const email = ref('')
const questions = ref([])
const answers = reactive({})
const ticket = ref('')
const newPw = ref('')
const newPw2 = ref('')
const busy = ref(false)
const error = ref('')
const noQuestions = ref(false)

async function loadQuestions() {
  error.value = ''
  if (!email.value.trim()) { error.value = '请填写邮箱'; return }
  busy.value = true
  try {
    const r = await api.forgotQuestions(email.value.trim())
    questions.value = r.questions || []
    if (!questions.value.length) {
      // 后端如实返回空列表 = 这个账号没设过安全问题
      noQuestions.value = true
      return
    }
    for (const q of questions.value) answers[q.slot] = ''
    step.value = 2
  } catch (e) {
    error.value = e.message || '查询失败'
  } finally { busy.value = false }
}

async function submitAnswers() {
  error.value = ''
  const filled = questions.value.filter(q => (answers[q.slot] || '').trim())
  if (filled.length < questions.value.length) { error.value = '每一题都要填'; return }
  busy.value = true
  try {
    // 只提交非空项，键转成数字（后端是 Map<Integer,String>）
    const payload = {}
    for (const q of questions.value) payload[q.slot] = answers[q.slot]
    const r = await api.forgotVerify(email.value.trim(), payload)
    ticket.value = r.ticket
    step.value = 3
  } catch (e) {
    error.value = e.message || '答案不正确'
  } finally { busy.value = false }
}

async function doReset() {
  error.value = ''
  if (newPw.value.length < 8) { error.value = '新密码至少 8 位'; return }
  if (newPw.value !== newPw2.value) { error.value = '两次输入不一致'; return }
  busy.value = true
  try {
    await api.forgotReset(ticket.value, newPw.value)
    // 不自动登录：让用户自己用新密码登一次，顺便确认记住了
    router.push({ path: '/login', query: { reset: '1' } })
  } catch (e) {
    error.value = e.message || '重置失败'
  } finally { busy.value = false }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh; display: grid; place-items: center;
  background: linear-gradient(160deg, #f4f6fd 0%, #eef1fb 45%, #f7f5fd 100%);
  padding: 24px;
}
.login-card {
  width: 100%; max-width: 400px; background: #fff;
  border: 1px solid var(--line); border-radius: 18px;
  padding: 30px 32px 24px;
  box-shadow: 0 18px 46px rgba(30,38,80,.09);
}
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 22px; }
.logo { width: 42px; height: 42px; }
.brand h1 { font-size: 19px; font-weight: 650; color: var(--ink); margin: 0; }
.muted { font-size: 12.5px; color: var(--ink-3); margin: 3px 0 0; }

/* 步骤条 */
.steps { display: flex; gap: 6px; margin-bottom: 22px; }
.step {
  flex: 1; display: flex; align-items: center; gap: 6px;
  font-size: 11.5px; color: var(--ink-3);
  padding-bottom: 7px; border-bottom: 2px solid #eef0f7;
}
.step.on { color: var(--brand-deep); border-bottom-color: var(--brand); }
.step.done { color: #12805c; border-bottom-color: #b8e8d4; }
.step-dot {
  width: 16px; height: 16px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; font-size: 10px; font-weight: 600;
  background: #eef0f7; color: var(--ink-3);
}
.step.on .step-dot { background: var(--brand); color: #fff; }
.step.done .step-dot { background: #d9f5e8; color: #12805c; }
.step-txt { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

label { display: block; margin-bottom: 14px; }
label > span {
  display: block; font-size: 12.5px; font-weight: 600;
  color: var(--ink-2); margin-bottom: 6px;
}
.input {
  width: 100%; height: 40px; padding: 0 12px;
  border: 1px solid var(--line); border-radius: 9px; background: #fbfcfe;
  font-size: 14px; color: var(--ink); font: inherit; outline: none;
  transition: border-color .16s, background .16s, box-shadow .16s;
}
.input:focus {
  border-color: #b9c4f5; background: #fff;
  box-shadow: 0 0 0 3px rgba(91,118,247,.12);
}

.hint {
  font-size: 12.5px; color: var(--ink-3); line-height: 1.6;
  margin: -4px 0 14px;
}
.err {
  font-size: 12.5px; color: var(--danger); background: #fff6f5;
  border: 1px solid #ffe0dc; border-radius: 8px;
  padding: 8px 11px; margin-bottom: 13px; line-height: 1.5;
}

.submit {
  width: 100%; height: 42px; margin-top: 4px;
  display: flex; align-items: center; justify-content: center; gap: 8px;
}
.spinner {
  width: 14px; height: 14px; border-radius: 50%;
  border: 2px solid rgba(255,255,255,.4); border-top-color: #fff;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.noq {
  font-size: 13px; color: var(--ink-2); line-height: 1.75;
  background: #fffbf0; border: 1px solid #ffe9bd; border-radius: 10px;
  padding: 14px 16px; margin-top: 8px;
}

.foot { margin-top: 18px; text-align: center; font-size: 12.5px; }
.foot a { color: var(--ink-3); text-decoration: none; }
.foot a:hover { color: var(--brand-deep); }
</style>