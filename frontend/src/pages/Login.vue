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

        <div v-if="resetOk" class="ok-tip">密码已改好，请用新密码登录</div>

        <div v-if="error" class="err">{{ error }}</div>

        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          <span v-if="busy" class="spinner"></span>
          <span>{{ busy ? '处理中…' : (mode === 'login' ? '登录' : '创建账号') }}</span>
        </button>

        <div v-if="mode === 'login'" class="forgot-row">
          <router-link to="/forgot" class="forgot-link">忘记密码？</router-link>
        </div>
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
// 从「忘记密码」改完跳回来时带 ?reset=1，给一句成功提示 ——
// 否则用户改完密码回到登录页，看不出刚才到底成没成功。
const resetOk = ref(new URLSearchParams(location.search).get('reset') === '1')
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

/* 离场：不是「原地缩小淡出」，而是像一张卡片翻到背后。
   三个关键设计：
   1. 前 20% 几乎不动 —— 先「抬起来」（轻微上移 + 微放大），
      有这一步才像「被拎起来翻」，直接缩下去就是「憋下去」。
   2. 用 rotateX 做真正的翻转，而不是只缩小。旋转带着透视，
      眼睛读到的动作是「翻过去」，不是「变小了」。必须先有抬升再转，
      否则一转就撞到底下那块白。
   3. transform-origin 设在底部，所以是绕下沿往外翻 —— 像名片翻面。
   曲线用 ease-in-out 的慢起慢收：翻页的物理直觉是先慢（抵抗重力）
   再快（过了临界点）再慢（落定），匀速看着假。 */
.login-card.leaving {
  animation: card-flip-away .82s cubic-bezier(.45, .05, .35, .95) forwards;
  transform-origin: center bottom;
  pointer-events: none;
  backface-visibility: hidden;
}
@keyframes card-flip-away {
  0%   { transform: perspective(1200px) translateY(0)      scale(1)    rotateX(0deg);    opacity: 1; }
  18%  { transform: perspective(1200px) translateY(-10px)  scale(1.03) rotateX(0deg);    opacity: 1; }
  45%  { transform: perspective(1200px) translateY(-14px)  scale(1)    rotateX(24deg);   opacity: .95; }
  72%  { transform: perspective(1200px) translateY(-10px)  scale(.92)  rotateX(58deg);   opacity: .55; }
  88%  { transform: perspective(1200px) translateY(-6px)   scale(.86)  rotateX(78deg);   opacity: .2; }
  100% { transform: perspective(1200px) translateY(0)      scale(.8)   rotateX(86deg);   opacity: 0; }
}

/* 卡片内容比卡片本体早一点退场：翻到一半时字还清清楚楚地挂着，
   会有「贴纸翻面」的违和感。这里让内容先薄下去，留下空框继续翻。 */
.login-card.leaving > * {
  animation: fade-collapse .58s cubic-bezier(.4, 0, .2, 1) forwards;
}
@keyframes fade-collapse {
  0%   { opacity: 1; transform: translateY(0); }
  100% { opacity: 0; transform: translateY(-8px); }
}

@keyframes card-fade-only { to { opacity: 0; } }

@media (prefers-reduced-motion: reduce) {
  .login-card.leaving { animation: card-fade-only .28s ease-in forwards; }
  .login-card.leaving > * { animation: none; }
}
.forgot-row { margin-top: 13px; text-align: center; }
.forgot-link {
  font-size: 12.5px; color: var(--ink-3); text-decoration: none;
  transition: color .16s;
}
.forgot-link:hover { color: var(--brand-deep); }

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
/* 改完密码跳回来的成功提示。用绿而不是紫：紫色是主色，留给「操作」；
   这里是「状态已达成」，成功色更合语义。 */
.ok-tip {
  background: #edfaf4; color: #12805c; border: 1px solid #c6efdd;
  padding: 9px 12px; border-radius: 9px; font-size: 12.5px; margin-bottom: 14px;
}
</style>