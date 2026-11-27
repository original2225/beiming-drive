<template>
  <div class="page">
    <h2>个人信息</h2>
    <p class="muted sub">账号、头像和安全设置</p>

    <div v-if="loading" class="sk-wrap" aria-hidden="true">
      <div class="card sk-hero">
        <div class="sk-ava"></div>
        <div class="sk-line w30"></div>
        <div class="sk-line w50 thin"></div>
      </div>
    </div>

    <template v-else-if="me">
      <!-- ===== 头像 + 身份 ===== -->
      <div class="card hero">
        <!-- 头像 + 常显的「更换」入口。
             只靠悬停出相机遮罩是不够的 —— 头像看着像展示图，
             用户不会想到去悬停，更不会想到还能右键移除。
             所以下面挂一行常显文字链接。 -->
        <div class="ava-col">
          <button class="ava" :class="{ hasImg: !!avatarUrl }"
                  title="点击更换头像"
                  @click="pickAvatar"
                  @contextmenu.prevent="askRemoveAvatar">
            <img v-if="avatarUrl" :src="avatarUrl" alt="" />
            <span v-else class="ava-letter">{{ letter }}</span>
            <span class="cam" :class="{ busy: avatarBusy }">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"
                   stroke-linecap="round" stroke-linejoin="round">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                <circle cx="12" cy="13" r="4"/>
              </svg>
            </span>
          </button>
          <button type="button" class="ava-link" @click="pickAvatar">
            {{ avatarBusy ? '上传中…' : '更换头像' }}
          </button>
          <button v-if="avatarUrl" type="button" class="ava-link danger"
                  @click="askRemoveAvatar">移除</button>
        </div>
        <input ref="avatarInput" type="file"
               accept="image/png,image/jpeg,image/webp,image/gif"
               class="ava-input" @change="onAvatarPicked" />



        <div class="hero-main">
          <div class="hero-name">
            {{ me.nickname }}
            <span v-if="me.role === 'ADMIN'" class="badge">管理员</span>
          </div>
          <div class="hero-mail muted">{{ me.email }}</div>
          <div class="hero-meta muted">
            加入于 {{ fmtDate(me.createdAt) }} · 已用 {{ size(me.storageUsed) }} / {{ size(me.storageQuota) }}
          </div>
        </div>


      </div>

      <!-- ===== 昵称 ===== -->
      <div class="card">
        <div class="row-head">
          <div>
            <div class="row-title">昵称</div>
            <div class="row-desc muted">显示在侧栏和分享页上</div>
          </div>
        </div>
        <div class="row-edit">
          <input v-model="nickInput" class="ipt" maxlength="64"
                 :placeholder="me.nickname" @keyup.enter="saveNickname" />
          <button class="btn btn-primary btn-sm"
                  :disabled="nickBusy || !nickChanged"
                  @click="saveNickname">
            {{ nickBusy ? '保存中…' : '保存' }}
          </button>
        </div>
        <div v-if="nickMsg" class="msg" :class="nickOk ? 'ok' : 'err'">{{ nickMsg }}</div>
      </div>

      <!-- ===== 邮箱（只读） ===== -->
      <div class="card">
        <div class="row-head">
          <div>
            <div class="row-title">邮箱</div>
            <div class="row-desc muted">登录账号，目前不支持修改</div>
          </div>

        </div>
        <div class="ro">{{ me.email }}</div>
      </div>

      <!-- ===== 密码 ===== -->
      <div class="card">
        <div class="row-head">
          <div>
            <div class="row-title">修改密码</div>
            <div class="row-desc muted">改完不会踢掉当前登录，但其他设备要重新登录</div>
          </div>
        </div>
        <div class="pw-grid">
          <input v-model="pwOld" type="password" class="ipt" placeholder="当前密码"
                 autocomplete="current-password" />
          <input v-model="pwNew" type="password" class="ipt" placeholder="新密码（至少 8 位）"
                 autocomplete="new-password" />
          <input v-model="pwNew2" type="password" class="ipt" placeholder="再输一次新密码"
                 autocomplete="new-password" @keyup.enter="savePassword" />
        </div>
        <div class="pw-foot">
          <button class="btn btn-sm" :disabled="pwBusy" @click="savePassword">
            {{ pwBusy ? '提交中…' : '修改密码' }}
          </button>
          <span v-if="pwMsg" class="msg" :class="pwOk ? 'ok' : 'err'">{{ pwMsg }}</span>
        </div>
      </div>

      <!-- ===== 退出 ===== -->
      <div class="card">
        <div class="row-head">
          <div>
            <div class="row-title">退出登录</div>
            <div class="row-desc muted">退出后需要重新输入邮箱和密码</div>
          </div>
          <button class="btn btn-sm btn-danger" @click="askLogout">退出登录</button>
        </div>
      </div>
    </template>

    <!-- ===== 确认弹窗（不用原生 confirm：会阻塞且没法套样式） ===== -->
    <transition name="dlg">
      <div v-if="dialog" class="dlg-mask" @click.self="dialog = null">
        <div class="dlg">
          <div class="dlg-title">{{ dialog.title }}</div>
          <div class="dlg-text muted">{{ dialog.text }}</div>
          <div class="dlg-ops">
            <button class="btn btn-sm" @click="dialog = null">取消</button>
            <button class="btn btn-sm" :class="dialog.danger ? 'btn-danger' : 'btn-primary'"
                    @click="dialog.run">{{ dialog.okText || '确定' }}</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 轻提示 -->
    <transition name="toast">
      <div v-if="toast" class="toast">{{ toast }}</div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, getToken, setToken } from '../api.js'

const router = useRouter()
const me = ref(null)
const loading = ref(true)

const avatarUrl = ref('')
const avatarBusy = ref(false)
const avatarInput = ref(null)

const nickInput = ref('')
const nickBusy = ref(false)
const nickMsg = ref('')
const nickOk = ref(false)

const pwOld = ref('')
const pwNew = ref('')
const pwNew2 = ref('')
const pwBusy = ref(false)
const pwMsg = ref('')
const pwOk = ref(false)

const dialog = ref(null)
const toast = ref('')

const letter = computed(() => (me.value?.nickname || '?').trim().charAt(0).toUpperCase())
const nickChanged = computed(() =>
  nickInput.value.trim() && nickInput.value.trim() !== me.value?.nickname)

function size(bytes) {
  const b = Number(bytes) || 0
  if (b < 1024) return b + ' B'
  const u = ['KB', 'MB', 'GB', 'TB']
  let v = b / 1024, i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + u[i]
}

function fmtDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d)) return '—'
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

function flash(text) {
  toast.value = text
  setTimeout(() => { toast.value = '' }, 1800)
}

/// 通知外壳刷新它自己那份 me（侧栏昵称/头像要跟着变）。
/// 用自定义事件而不是共享状态：AppShell 是上层组件，
/// 让子页面直接改它的数据会形成反向依赖，事件是单向的。
function notifyShell() {
  window.dispatchEvent(new CustomEvent('drive:profile-changed'))
}

/* ---------- 头像 ---------- */

/// 和侧栏同一套：URL 带 avatarKey 破缓存，并附 access_token
/// （<img src> 发不出 Authorization 头，拦截器支持查询参数形式）
function syncAvatar() {
  const key = me.value?.avatarKey
  if (!key) { avatarUrl.value = ''; return }
  const q = new URLSearchParams({ v: key, access_token: getToken() || '' })
  avatarUrl.value = `/api/v1/auth/avatar?${q.toString()}`
}

function pickAvatar() { avatarInput.value?.click() }

async function onAvatarPicked(e) {
  const file = e.target.files?.[0]
  e.target.value = ''            // 允许重选同一个文件
  if (!file) return
  if (file.size > 1024 * 1024) { flash('图片不能超过 1 MB'); return }
  if (!/^image\/(png|jpeg|webp|gif)$/.test(file.type)) { flash('只支持 PNG / JPEG / WebP / GIF'); return }

  avatarBusy.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    await api.uploadAvatar(fd)
    me.value = await api.profile()
    syncAvatar()
    notifyShell()
    flash('头像已更新')
  } catch (err) {
    flash(err?.message || '上传失败')
  } finally {
    avatarBusy.value = false
  }
}

function askRemoveAvatar() {
  if (!me.value?.avatarKey) return
  dialog.value = {
    title: '移除头像',
    text: '移除后会显示昵称首字。',
    danger: true,
    okText: '移除',
    run: async () => {
      dialog.value = null
      try {
        await api.deleteAvatar()
        me.value = await api.profile()
        syncAvatar()
        notifyShell()
        flash('头像已移除')
      } catch (err) { flash(err?.message || '移除失败') }
    },
  }
}

/* ---------- 昵称 ---------- */

async function saveNickname() {
  const name = nickInput.value.trim()
  if (!name || !nickChanged.value || nickBusy.value) return
  nickBusy.value = true
  nickMsg.value = ''
  try {
    me.value = await api.updateNickname(name)
    nickInput.value = ''
    nickOk.value = true
    nickMsg.value = '昵称已更新'
    notifyShell()
  } catch (err) {
    nickOk.value = false
    nickMsg.value = err?.message || '保存失败'
  } finally {
    nickBusy.value = false
  }
}

/* ---------- 密码 ---------- */

async function savePassword() {
  pwMsg.value = ''
  if (pwBusy.value) return
  if (!pwOld.value) { pwOk.value = false; pwMsg.value = '请先填当前密码'; return }
  if (pwNew.value.length < 8) { pwOk.value = false; pwMsg.value = '新密码至少 8 位'; return }
  if (pwNew.value !== pwNew2.value) { pwOk.value = false; pwMsg.value = '两次输入的新密码不一致'; return }

  pwBusy.value = true
  try {
    await api.changePassword(pwOld.value, pwNew.value)
    pwOld.value = pwNew.value = pwNew2.value = ''
    pwOk.value = true
    pwMsg.value = '密码已修改'
  } catch (err) {
    pwOk.value = false
    pwMsg.value = err?.message || '修改失败'
  } finally {
    pwBusy.value = false
  }
}

/* ---------- 退出 ---------- */

function askLogout() {
  dialog.value = {
    title: '退出登录',
    text: '需要重新输入邮箱和密码才能进入。',
    danger: true,
    okText: '退出',
    run: async () => {
      dialog.value = null
      try { await api.logout() } catch (e) { /* 服务端吊销失败也要本地清干净 */ }
      setToken('')
      router.push('/login')
    },
  }
}

/* ---------- 初始化 ---------- */

onMounted(async () => {
  try {
    me.value = await api.profile()
    nickInput.value = ''
    syncAvatar()
  } catch (e) { me.value = null }
  finally { loading.value = false }
})
</script>

<style scoped>
.page {
  flex: 1; display: flex; flex-direction: column;
  overflow-y: auto; padding: 26px 30px 44px; min-height: 0;
}
/* 宽屏下不让卡片拉得太宽：表单一行超过 700px 读起来要来回扫 */
.page > * { max-width: 720px; width: 100%; }
h2 { margin: 0 0 4px; font-size: 19px; font-weight: 600; color: var(--ink); }
.sub { margin: 0 0 18px; font-size: 12.5px; }
.muted { color: var(--ink-3); }
.card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px 20px; margin-bottom: 14px;
}

/* ---------- 头部 ---------- */
.hero { display: flex; align-items: center; gap: 18px; position: relative; }

/* 头像按钮：48px 圆，叠相机遮罩。
   遮罩默认透明、悬停 0.85 —— 和侧栏头像同一套观感。 */
.ava {
  position: relative; width: 62px; height: 62px; border-radius: 50%;
  flex-shrink: 0; padding: 0; border: 0; cursor: pointer; overflow: hidden;
  background: linear-gradient(135deg, var(--brand), #7b5bf7);
  display: grid; place-items: center;
  transition: transform .2s cubic-bezier(.3,.6,.35,1), box-shadow .2s;
}
.ava:hover { transform: scale(1.04); box-shadow: 0 8px 22px rgba(91,108,255,.32); }
.ava-col { display: flex; flex-direction: column; align-items: center; gap: 5px; flex-shrink: 0; }
.ava-link {
  border: 0; background: transparent; padding: 0; cursor: pointer;
  font: inherit; font-size: 11px; color: var(--brand);
  transition: color .15s ease;
}
.ava-link:hover { color: var(--brand-deep); text-decoration: underline; }
.ava-link.danger { color: var(--ink-3); }
.ava-link.danger:hover { color: #d64545; }
.ava > img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ava-letter { color: #fff; font-size: 23px; font-weight: 600; letter-spacing: .5px; }

.cam {
  position: absolute; inset: 0; display: grid; place-items: center;
  background: rgba(22, 25, 42, .62); color: #fff;
  opacity: 0; transition: opacity .18s ease;
}
.cam svg { width: 22px; height: 22px; }
.ava:hover .cam { opacity: .85; }
.cam.busy { opacity: .85; }
.cam.busy::after {
  content: ''; position: absolute; width: 26px; height: 26px;
  border-radius: 50%; border: 2px solid rgba(255,255,255,.35);
  border-top-color: #fff; animation: spin .7s linear infinite;
}
.cam.busy svg { opacity: 0; }
@keyframes spin { to { transform: rotate(360deg) } }
.ava-input { display: none; }

.hero-main { flex: 1; min-width: 0; }
.hero-name {
  font-size: 17px; font-weight: 600; color: var(--ink);
  display: flex; align-items: center; gap: 8px;
}
.badge {
  font-size: 10.5px; font-weight: 600; padding: 2px 7px; border-radius: 6px;
  background: var(--brand-soft); color: var(--brand-deep);
}
.hero-mail { font-size: 12.5px; margin-top: 3px; }
.hero-meta { font-size: 11.5px; margin-top: 5px; color: var(--ink-2); opacity: .85; }


/* ---------- 行 ---------- */
.row-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.row-title { font-size: 13.5px; font-weight: 600; color: var(--ink); margin-bottom: 3px; }
.row-desc { font-size: 11.5px; }
.row-edit { display: flex; gap: 9px; align-items: center; }
.ipt {
  flex: 1; min-width: 0; height: 36px; padding: 0 12px;
  border: 1px solid var(--line); border-radius: 9px; background: #fff;
  font-size: 13px; color: var(--ink); outline: none;
  transition: border-color .16s, box-shadow .16s;
}
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px rgba(91,108,255,.11); }
.ro {
  height: 36px; display: flex; align-items: center; padding: 0 12px;
  background: #f7f8fc; border: 1px solid var(--line); border-radius: 9px;
  font-size: 13px; color: var(--ink-2);
}


.pw-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; margin-bottom: 12px; }
.pw-foot { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.msg { font-size: 12px; }
.msg.ok { color: #12946a; }
.msg.err { color: #d64545; }

.btn-danger { color: #d64545; border-color: #f0cccc; }
.btn-danger:hover { background: #fdf3f3; }

/* ---------- 弹窗 ---------- */
.dlg-mask {
  position: fixed; inset: 0; background: rgba(22,25,42,.34);
  display: grid; place-items: center; z-index: 60; backdrop-filter: blur(2px);
}
.dlg {
  width: 328px; background: #fff; border-radius: 14px; padding: 20px 22px 16px;
  box-shadow: 0 22px 60px rgba(22,25,42,.22);
}
.dlg-title { font-size: 14.5px; font-weight: 600; color: var(--ink); margin-bottom: 7px; }
.dlg-text { font-size: 12.5px; line-height: 1.6; margin-bottom: 16px; }
.dlg-ops { display: flex; justify-content: flex-end; gap: 8px; }

.dlg-enter-active, .dlg-leave-active { transition: opacity .16s ease; }
.dlg-enter-active .dlg, .dlg-leave-active .dlg { transition: transform .16s cubic-bezier(.3,.7,.35,1); }
.dlg-enter-from, .dlg-leave-to { opacity: 0; }
.dlg-enter-from .dlg, .dlg-leave-to .dlg { transform: scale(.95); }

/* ---------- 轻提示 ---------- */
.toast {
  position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%);
  background: rgba(22,25,42,.9); color: #fff; font-size: 12.5px;
  padding: 9px 16px; border-radius: 9px; z-index: 70;
}
.toast-enter-active, .toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

/* ---------- 骨架 ---------- */
.sk-hero { display: flex; align-items: center; gap: 16px; }
.sk-ava { width: 62px; height: 62px; border-radius: 50%; background: #eef0f5; flex-shrink: 0; }
.sk-line { height: 11px; border-radius: 5px; background: #eef0f5; margin-bottom: 9px; }
.sk-line.thin { height: 9px; }
.w30 { width: 30%; } .w50 { width: 50%; }
.sk-hero .sk-line { margin: 0 0 9px; }
.sk-wrap .sk-ava, .sk-wrap .sk-line { animation: sk-pulse 1.3s ease-in-out infinite; }
@keyframes sk-pulse { 0%,100% { opacity: .55 } 50% { opacity: 1 } }

@media (max-width: 720px) {
  .pw-grid { grid-template-columns: 1fr; }
}
</style>