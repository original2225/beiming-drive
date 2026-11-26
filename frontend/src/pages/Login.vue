<template>
  <div class="login-wrap" :class="{ leaving }">
    <div class="login-card card" :class="{ leaving }">
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
// 登录成功后先播「卡片收缩 → 淡出」再跳转；跳得太快什么都看不见
const leaving = ref(false)

/** 等动画播完再走，别用固定 setTimeout —— 提前 return 时它会失效 */
function wait(ms) { return new Promise(r => setTimeout(r, ms)) }

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
    leaving.value = true
    // 卡片动画 0.78s，走到约 85%（660ms）就走：
    // 那时它已经很淡很小，主页的雾正好接上，尾巴还能重叠几十毫秒，
    // 不会露出「两边都不存在」的空白。
    await wait(660)
    router.push('/')
  } catch (e) {
    error.value = e.message || '请求失败'
    busy.value = false       // 失败要能再点，不能卡在「处理中…」
    leaving.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--bg);
  padding: 24px;
  position: relative;
  overflow: hidden;
}
/* 装饰渐变放在伪元素上：离场时能单独淡掉它，露出和主页一致的纯底，
   这样切页那一刻不会闪一下白 */
.login-wrap::before {
  content: '';
  position: absolute; inset: 0; pointer-events: none;
  background:
    radial-gradient(1200px 600px at 10% -10%, #e8ecff 0%, transparent 60%),
    radial-gradient(900px 500px at 110% 110%, #eef6ff 0%, transparent 55%);
  transition: opacity .46s ease;
}
.login-wrap.leaving::before { opacity: 0; }
.login-card { width: 100%; max-width: 400px; padding: 34px 32px 30px;
              position: relative; transform-origin: center center; }

/* 离场：缩小为主，淡出为辅。
   两个关键设计：
   1. 前 28% 只缩不变淡 —— 缩放和透明度同时衰减时眼睛只看到「变淡」，缩小被掩盖。
   2. 曲线不能太激进。用 cubic-bezier(.12,.9,...) 这种，前段一口气冲到底、
      后段没有余量，会中间卡住不动几百毫秒（实测 scale 停在 .655 不动），
      看着像「缩一下就僵住了」。这里用平缓的 ease-out，全程都在动。 */
.login-card.leaving {
  animation: card-collapse .8s cubic-bezier(.25, .5, .45, .95) forwards;
  pointer-events: none;
}
@keyframes card-collapse {
  0%   { transform: scale(1)    translateY(0);     opacity: 1;   filter: blur(0); }
  28%  { transform: scale(.845) translateY(-4px);  opacity: 1;   filter: blur(0); }
  50%  { transform: scale(.745) translateY(-8px);  opacity: .9;  filter: blur(1.2px); }
  72%  { transform: scale(.655) translateY(-12px); opacity: .6;  filter: blur(3.2px); }
  88%  { transform: scale(.59)  translateY(-15px); opacity: .25; filter: blur(5.4px); }
  100% { transform: scale(.53)  translateY(-17px); opacity: 0;   filter: blur(7px); }
}
/* 卡片里的内容稍微早于卡片消失（到 62% 就已淡尽），
   这样看到的是「内容先散、框还留着缩小」，不是整张图一起缩 */
.login-card.leaving > * { animation: fade-collapse .5s cubic-bezier(.4, 0, .6, 1) forwards; }
@keyframes fade-collapse {
  0%   { opacity: 1; transform: translateY(0); }
  100% { opacity: 0; transform: translateY(-5px); }
}

@keyframes card-fade-only { to { opacity: 0; } }

@media (prefers-reduced-motion: reduce) {
  .login-card.leaving { animation: card-fade-only .22s ease-in forwards; }
  .login-card.leaving > * { animation: none; }
}
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