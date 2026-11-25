<template>
  <div class="login-wrap">
    <div class="login-card card">
      <div class="brand">
        <img src="/whale-flat-128.png" alt="北冥云盘" class="logo" />
        <div>
          <h1>北冥云盘</h1>
          <p class="muted">北冥有鱼，其名为鲲</p>
        </div>
      </div>

      <div class="tabs">
        <button :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>

      <form @submit.prevent="submit">
        <label>
          <span>邮箱</span>
          <input class="input" v-model="email" type="email" placeholder="you@beiming.games" />
        </label>
        <label v-if="mode === 'register'">
          <span>昵称</span>
          <input class="input" v-model="nickname" placeholder="怎么称呼你" />
        </label>
        <label>
          <span>密码</span>
          <input class="input" v-model="password" type="password"
                 :placeholder="mode === 'register' ? '至少 8 位' : '输入密码'" />
        </label>

        <div v-if="error" class="err">{{ error }}</div>

        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          <span v-if="busy" class="spinner"></span>
          <span>{{ busy ? '处理中…' : (mode === 'login' ? '登录' : '创建账号') }}</span>
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../api.js'

const router = useRouter()
const mode = ref('login')
const email = ref('')
const nickname = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (!email.value.trim()) { error.value = '请填写邮箱'; return }
  if (!password.value) { error.value = '请填写密码'; return }
  if (mode.value === 'register' && !nickname.value.trim()) { error.value = '请填写昵称'; return }
  busy.value = true
  try {
    const r = mode.value === 'login'
      ? await api.login(email.value, password.value)
      : await api.register(email.value, nickname.value, password.value)
    setToken(r.token)
    router.push('/')
  } catch (e) {
    error.value = e.message || '请求失败'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 600px at 10% -10%, #e8ecff 0%, transparent 60%),
    radial-gradient(900px 500px at 110% 110%, #eef6ff 0%, transparent 55%),
    var(--bg);
  padding: 24px;
}
.login-card { width: 100%; max-width: 400px; padding: 34px 32px 30px; }
.brand { display: flex; align-items: center; gap: 14px; margin-bottom: 26px; }
.logo { width: 52px; height: 52px; border-radius: 50%; }
.brand h1 { margin: 0; font-size: 21px; letter-spacing: .5px; }
.brand p { margin: 3px 0 0; font-size: 12px; }

.tabs { display: flex; gap: 4px; background: #f2f3f9; padding: 4px; border-radius: 10px; margin-bottom: 22px; }
.tabs button {
  flex: 1; height: 32px; border: 0; background: transparent; border-radius: 8px;
  font-size: 13.5px; color: var(--ink-2); font-weight: 500; transition: .16s;
}
.tabs button.on { background: #fff; color: var(--ink); box-shadow: 0 1px 3px rgba(22,25,42,.08); }

label { display: block; margin-bottom: 15px; }
label span { display: block; font-size: 12.5px; color: var(--ink-2); margin-bottom: 7px; font-weight: 500; }
.submit { width: 100%; height: 40px; justify-content: center; margin-top: 8px; }
.err {
  background: #fff5f4; color: var(--danger); border: 1px solid #ffd5d2;
  padding: 9px 12px; border-radius: 9px; font-size: 12.5px; margin-bottom: 14px;
}
</style>