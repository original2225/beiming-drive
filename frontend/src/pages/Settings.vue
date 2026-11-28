<template>
  <div class="page settings-page">
    <header class="st-head">
      <h1>设置</h1>
      <p class="st-sub">账户、安全、显示和传输都在这。改完立刻生效，不用点保存。</p>
    </header>

    <div class="st-body">
      <!-- 左侧 tab 竖排。用请求参数记录当前 tab，刷新回到同一处 -->
      <nav class="st-tabs">
        <button v-for="t in tabs" :key="t.key"
                class="st-tab" :class="{ on: tab === t.key }" @click="switchTab(t.key)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="t.icon" /></svg>
          <span>{{ t.label }}</span>
        </button>
      </nav>

      <section class="st-panel">
        <!-- ============ 账户 ============ -->
        <div v-if="tab === 'account'" class="st-group">
          <div class="avatar-row">
            <button class="big-ava" :class="{ hasImg: !!avatarUrl }" title="点击更换头像"
                    @click="avatarInput?.click()">
              <img v-if="avatarUrl" :src="avatarUrl" alt="" />
              <span v-else>{{ letter }}</span>
              <span v-if="avatarBusy" class="ava-spin"></span>
            </button>
            <input ref="avatarInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif"
                   class="hidden-input" @change="onAvatarPicked" />
            <div class="avatar-side">
              <div class="avatar-name">{{ form.nickname || '未命名' }}</div>
              <div class="avatar-hint">点左边的头像换一张，1 MB 以内；右键头像可以移除。</div>
            </div>
          </div>

          <label class="field">
            <span class="field-label">昵称</span>
            <input v-model="form.nickname" class="ipt" maxlength="64" placeholder="显示在侧栏和分享里" />
          </label>

          <label class="field">
            <span class="field-label">个人简介</span>
            <textarea v-model="form.bio" class="ipt ta" maxlength="500" rows="3"
                      placeholder="想让别人知道的一句话"></textarea>
            <span class="counter">{{ (form.bio || '').length }} / 500</span>
          </label>

          <!-- 绑定方式：邮箱已在注册时确定，只读；QQ 号可改 -->
          <div class="field">
            <span class="field-label">登录方式</span>
            <div class="bind">
              <div class="bind-row">
                <span class="bind-key">邮箱</span>
                <span class="bind-val">{{ me?.email }}</span>
                <span class="badge ok">已绑定</span>
              </div>
              <div class="bind-row">
                <span class="bind-key">QQ 号</span>
                <input v-model="form.qq" class="ipt ipt-inline" maxlength="12"
                       placeholder="绑定后可以用 QQ 找回" />
              </div>
            </div>
            <div class="tip">绑定 QQ 号需要你本人在 QQ 上确认，这里先填上，验证功能还没做。</div>
          </div>

          <div class="actions">
            <button class="btn btn-primary" :disabled="saving" @click="saveProfile">
              {{ saving ? '保存中…' : '保存' }}
            </button>
            <span v-if="savedTip" class="saved-tip">{{ savedTip }}</span>
          </div>
        </div>

        <!-- ============ 安全 ============ -->
        <div v-if="tab === 'security'" class="st-group">
          <h3 class="grp-title">修改密码</h3>
          <div class="field">
            <input v-model="pw.old" type="password" class="ipt" placeholder="当前密码" />
          </div>
          <div class="field">
            <input v-model="pw.next" type="password" class="ipt" placeholder="新密码（至少 8 位）" />
          </div>
          <div class="field">
            <input v-model="pw.again" type="password" class="ipt" placeholder="再输一次新密码" />
          </div>
          <div class="actions">
            <button class="btn" :disabled="pwBusy" @click="changePassword">
              {{ pwBusy ? '提交中…' : '修改密码' }}
            </button>
            <span v-if="pwTip" class="saved-tip" :class="{ err: pwErr }">{{ pwTip }}</span>
          </div>

          <div class="divider"></div>

          <h3 class="grp-title">安全问题</h3>
          <p class="grp-desc">忘记密码时用来证明「你是你」。至少设两个，答案不区分大小写。</p>
          <div v-for="(q, i) in sq" :key="i" class="sq-row">
            <input v-model="q.question" class="ipt sq-q" maxlength="100"
                   :placeholder="`问题 ${i + 1}`" />
            <input v-model="q.answer" class="ipt sq-a" type="password"
                   placeholder="答案" />
            <button v-if="sq.length > 2" class="btn btn-sm btn-ghost" @click="sq.splice(i, 1)">删</button>
          </div>
          <div class="actions">
            <button class="btn btn-sm" @click="sq.push({ question: '', answer: '' })"
                    :disabled="sq.length >= 5">+ 再加一条</button>
            <button class="btn btn-primary btn-sm" :disabled="sqBusy" @click="saveSecurity">
              {{ sqBusy ? '保存中…' : '保存问题' }}
            </button>
            <span v-if="sqTip" class="saved-tip">{{ sqTip }}</span>
          </div>

          <div class="divider"></div>

          <h3 class="grp-title">登录设备</h3>
          <p class="grp-desc">发现不认识的设备就把它踢下线。</p>
          <div class="device-list">
            <div v-for="d in devices" :key="d.id" class="device">
              <div class="dev-main">
                <span class="dev-ua">{{ shortUA(d.userAgent) }}</span>
                <span v-if="d.current" class="badge brand">这台</span>
                <span class="dev-ip">{{ d.ip || '未知 IP' }}</span>
              </div>
              <div class="dev-side">
                <span class="dev-time">{{ fmtTime(d.createdAt) }}</span>
                <button v-if="!d.current" class="btn btn-sm btn-ghost" @click="kick(d)">下线</button>
              </div>
            </div>
            <div v-if="!devices.length" class="empty-mini">没有其他登录设备</div>
          </div>
          <div class="actions">
            <button class="btn btn-sm btn-danger-ghost" :disabled="devBusy" @click="kickAll">
              {{ devBusy ? '处理中…' : '退出其他所有设备' }}
            </button>
          </div>
        </div>

        <!-- ============ 显示 ============ -->
        <div v-if="tab === 'display'" class="st-group">
          <h3 class="grp-title">列表</h3>
          <div class="field">
            <span class="field-label">列表密度</span>
            <div class="seg">
              <button v-for="o in densities" :key="o.v" class="seg-btn"
                      :class="{ on: display.density === o.v }" @click="setDisplay('density', o.v)">
                {{ o.label }}
              </button>
            </div>
          </div>
          <div class="field">
            <span class="field-label">默认排序</span>
            <ThemeSelect v-model="display.sort" :options="sortOpts"
                         @change="v => setDisplay('sort', v)" />
          </div>
          <div class="field">
            <span class="field-label">默认列表样式</span>
            <div class="seg">
              <button v-for="o in views" :key="o.v" class="seg-btn"
                      :class="{ on: display.view === o.v }" @click="setDisplay('view', o.v)">
                {{ o.label }}
              </button>
            </div>
          </div>

          <div class="divider"></div>
          <h3 class="grp-title">语言与时间</h3>
          <div class="field">
            <span class="field-label">时间显示</span>
            <div class="seg">
              <button v-for="o in zones" :key="o.v" class="seg-btn"
                      :class="{ on: display.zone === o.v }" @click="setDisplay('zone', o.v)">
                {{ o.label }}
              </button>
            </div>
          </div>
          <div class="field">
            <span class="field-label">时间格式</span>
            <div class="seg">
              <button v-for="o in timeFmts" :key="o.v" class="seg-btn"
                      :class="{ on: display.timeFmt === o.v }" @click="setDisplay('timeFmt', o.v)">
                {{ o.label }}
              </button>
            </div>
          </div>
        </div>

        <!-- ============ 传输 ============ -->
        <div v-if="tab === 'transfer'" class="st-group">
          <h3 class="grp-title">下载</h3>
          <div class="field">
            <span class="field-label">下载线程数</span>
            <div class="seg">
              <button v-for="n in [4, 8, 16]" :key="n" class="seg-btn"
                      :class="{ on: transfer.threads === n }" @click="setTransfer('threads', n)">
                {{ n }} 线程
              </button>
            </div>
            <div class="tip">线程越多越快，但对服务器和网络压力也越大。8 是最稳的档。</div>
          </div>

          <div class="divider"></div>
          <h3 class="grp-title">上传</h3>
          <div class="field">
            <span class="field-label">重名时怎么办</span>
            <ThemeSelect v-model="transfer.dup" :options="dupOpts"
                         @change="v => setTransfer('dup', v)" />
            <div class="tip">默认「自动改名」——我最推荐，不会误删东西。</div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getToken } from '../api.js'
import ThemeSelect from '../components/ThemeSelect.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  { key: 'account', label: '账户', icon: 'M12 3.6a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4.8 20c.9-3.6 3.8-5.6 7.2-5.6s6.3 2 7.2 5.6' },
  { key: 'security', label: '安全', icon: 'M12 3l7 3v5.5c0 4.3-3 8-7 9.5-4-1.5-7-5.2-7-9.5V6l7-3z' },
  { key: 'display', label: '显示', icon: 'M4 5h16v11H4zM9 20h6M12 16v4' },
  { key: 'transfer', label: '传输', icon: 'M12 4v10m0 0l-4-4m4 4l4-4M4 19h16' },
]
const tab = ref(normalizeTab(route.query.tab))

function normalizeTab(t) {
  return tabs.some(x => x.key === t) ? t : 'account'
}
function switchTab(k) {
  tab.value = k
  router.replace({ query: { ...route.query, tab: k } })
}
// 浏览器前进后退时 tab 也要跟着走
watch(() => route.query.tab, t => { tab.value = normalizeTab(t) })

/* ---------- 账户 ---------- */
const me = ref(null)
const form = reactive({ nickname: '', bio: '', qq: '' })
const saving = ref(false)
const savedTip = ref('')

const letter = computed(() => (me.value?.nickname || '?').trim().charAt(0).toUpperCase())

const avatarUrl = computed(() => {
  const key = me.value?.avatarKey
  if (!key) return ''
  const q = new URLSearchParams({ v: key, access_token: getToken() || '' })
  return `/api/v1/auth/avatar?${q}`
})

async function loadProfile() {
  try {
    me.value = await api.me()
    form.nickname = me.value.nickname || ''
    form.bio = me.value.bio || ''
    form.qq = me.value.qq || ''
    // 「设置」页拿的账号资料比 /me 多一点，失败也不影响主流程
    try {
      const p = await api.accountProfile()
      form.bio = p.bio || ''
      form.qq = p.qq || ''
    } catch (e) { /* 接口还没上线时静默 */ }
  } catch (e) { /* 401 交给路由守卫 */ }
}

async function saveProfile() {
  saving.value = true
  savedTip.value = ''
  try {
    await api.updateAccountProfile({
      nickname: form.nickname,
      bio: form.bio,
      qq: form.qq,
    })
    await loadProfile()
    window.dispatchEvent(new CustomEvent('drive:profile-changed'))
    savedTip.value = '已保存'
    setTimeout(() => { savedTip.value = '' }, 2200)
  } catch (e) {
    savedTip.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

const avatarInput = ref(null)
const avatarBusy = ref(false)
async function onAvatarPicked(e) {
  const f = e.target.files?.[0]
  e.target.value = ''
  if (!f) return
  if (f.size > 1024 * 1024) { savedTip.value = '头像不能超过 1 MB'; return }
  avatarBusy.value = true
  try {
    const fd = new FormData()
    fd.append('file', f)
    await api.uploadAvatar(fd)
    await loadProfile()
    window.dispatchEvent(new CustomEvent('drive:profile-changed'))
  } catch (err) {
    savedTip.value = err.message || '上传失败'
  } finally { avatarBusy.value = false }
}

/* ---------- 安全 ---------- */
const pw = reactive({ old: '', next: '', again: '' })
const pwBusy = ref(false)
const pwTip = ref('')
const pwErr = ref(false)

async function changePassword() {
  pwTip.value = ''
  pwErr.value = false
  if (!pw.old) { pwTip.value = '请输入当前密码'; pwErr.value = true; return }
  if (pw.next.length < 8) { pwTip.value = '新密码至少 8 位'; pwErr.value = true; return }
  if (pw.next !== pw.again) { pwTip.value = '两次输入的新密码不一致'; pwErr.value = true; return }
  pwBusy.value = true
  try {
    await api.changePassword(pw.old, pw.next)
    pw.old = pw.next = pw.again = ''
    pwTip.value = '密码已修改'
  } catch (e) {
    pwTip.value = e.message || '修改失败'
    pwErr.value = true
  } finally { pwBusy.value = false }
}

const sq = ref([{ question: '', answer: '' }, { question: '', answer: '' }])
const sqBusy = ref(false)
const sqTip = ref('')

async function loadSecurity() {
  try {
    const list = await api.securityQuestions()
    if (list.length) {
      // 已设过：只回显问题，答案栏留空（后端不返回答案，也不该返回）
      sq.value = list.map(x => ({ question: x.question, answer: '' }))
    }
  } catch (e) { /* 没设过就是空 */ }
}

async function saveSecurity() {
  sqTip.value = ''
  const filled = sq.value.filter(x => x.question.trim() && x.answer.trim())
  if (filled.length < 2) { sqTip.value = '至少要填满两个问题'; return }
  sqBusy.value = true
  try {
    await api.setSecurityQuestions(filled.map(x => ({ question: x.question, answer: x.answer })))
    sqTip.value = '已保存'
    setTimeout(() => { sqTip.value = '' }, 2200)
  } catch (e) {
    sqTip.value = e.message || '保存失败'
  } finally { sqBusy.value = false }
}

/* ---------- 设备 ---------- */
const devices = ref([])
const devBusy = ref(false)

async function loadDevices() {
  try { devices.value = await api.devices() } catch (e) { devices.value = [] }
}

function shortUA(ua) {
  if (!ua) return '未知设备'
  if (/Edg\//.test(ua)) return 'Edge 浏览器'
  if (/Chrome\//.test(ua)) return 'Chrome 浏览器'
  if (/Firefox\//.test(ua)) return 'Firefox 浏览器'
  if (/Safari\//.test(ua)) return 'Safari 浏览器'
  if (/curl|okhttp|java/i.test(ua)) return '程序调用'
  return ua.slice(0, 40)
}

function fmtTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function kick(d) {
  devBusy.value = true
  try { await api.revokeDevice(d.id); await loadDevices() }
  catch (e) { /* 忽略 */ }
  finally { devBusy.value = false }
}

async function kickAll() {
  devBusy.value = true
  try {
    const r = await api.revokeOtherDevices()
    await loadDevices()
    savedTip.value = `已下线 ${r.revoked} 台`
  } catch (e) { /* 忽略 */ }
  finally { devBusy.value = false }
}

/* ---------- 显示 / 传输（纯本地偏好，存 localStorage） ---------- */
const DKEY = 'drive_display'
const TKEY = 'drive_transfer'

const densities = [{ v: 'comfortable', label: '舒适' }, { v: 'compact', label: '紧凑' }]
const views = [{ v: 'list', label: '列表' }, { v: 'grid', label: '大图' }]
const zones = [{ v: 'local', label: '本地时间' }, { v: 'utc', label: 'UTC' }]
const timeFmts = [{ v: 'abs', label: '完整时间' }, { v: 'rel', label: '相对时间' }]

// 自绘下拉的选项（原来的 <option> 换到这里）
const sortOpts = [
  { value: 'name', label: '按名称' },
  { value: 'mtime', label: '按修改时间' },
  { value: 'size', label: '按大小' },
]
const dupOpts = [
  { value: 'rename', label: '自动改名（加序号）' },
  { value: 'overwrite', label: '覆盖同名文件' },
  { value: 'skip', label: '跳过不传' },
]

function readPref(key, fallback) {
  try { return { ...fallback, ...JSON.parse(localStorage.getItem(key) || '{}') } }
  catch (e) { return { ...fallback } }
}

const display = reactive(readPref(DKEY, {
  density: 'comfortable', sort: 'name', view: 'list', zone: 'local', timeFmt: 'abs',
}))
const transfer = reactive(readPref(TKEY, { threads: 8, dup: 'rename' }))

function setDisplay(k, v) { display[k] = v; persistDisplay() }
function setTransfer(k, v) { transfer[k] = v; persistTransfer() }
function persistDisplay() { localStorage.setItem(DKEY, JSON.stringify(display)) }
function persistTransfer() { localStorage.setItem(TKEY, JSON.stringify(transfer)) }

/* ---------- 生命周期 ---------- */
onMounted(async () => {
  await loadProfile()
  await loadSecurity()
  await loadDevices()
})
</script>

<style scoped>
.settings-page { padding: 30px var(--page-pad) 46px; }
.st-head { max-width: var(--content-max); }
.st-head h1 { font-size: 24px; font-weight: 650; color: var(--ink); margin: 0; }
.st-sub { font-size: 13.5px; color: var(--ink-3); margin: 7px 0 22px; }

.st-body {
  display: grid; grid-template-columns: 168px minmax(0, 1fr);
  gap: 28px; max-width: var(--content-max);
  align-items: start;
}

/* ---- 左侧 tab ---- */
.st-tabs { display: flex; flex-direction: column; gap: 2px; position: sticky; top: 18px; }
.st-tab {
  display: flex; align-items: center; gap: 9px;
  padding: 9px 11px; border-radius: 9px; border: 0; background: transparent;
  font-size: 14px; color: var(--ink-2); text-align: left; cursor: pointer;
  transition: background .15s, color .15s;
}
.st-tab svg { width: 17px; height: 17px; fill: none; stroke: currentColor;
              stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.st-tab:hover { background: #f4f6fc; color: var(--ink); }
.st-tab.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }

/* ---- 右侧面板 ---- */
.st-panel { min-width: 0; }
.st-group { background: #fff; border: 1px solid var(--line); border-radius: 14px;
            padding: 22px 24px; }
.grp-title { font-size: 14.5px; font-weight: 650; color: var(--ink); margin: 0 0 12px; }
.grp-title:not(:first-child) { margin-top: 4px; }
.grp-desc { font-size: 12.5px; color: var(--ink-3); margin: -6px 0 12px; line-height: 1.6; }
.divider { height: 1px; background: var(--line); margin: 22px 0; }

/* 头像行 */
.avatar-row { display: flex; align-items: center; gap: 16px; margin-bottom: 22px; }
.big-ava {
  width: 64px; height: 64px; border-radius: 50%; flex-shrink: 0;
  border: 0; padding: 0; cursor: pointer; overflow: hidden;
  display: grid; place-items: center; position: relative;
  background: linear-gradient(135deg, #5b76f7, #7b5bf7);
  color: #fff; font-size: 24px; font-weight: 600;
  transition: box-shadow .2s, transform .2s;
}
.big-ava:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(91,118,247,.34); }
.big-ava > img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ava-spin { position: absolute; inset: 0; background: rgba(255,255,255,.6); }
.ava-spin::after {
  content: ''; position: absolute; left: 50%; top: 50%; margin: -9px 0 0 -9px;
  width: 18px; height: 18px; border: 2px solid rgba(91,118,247,.3);
  border-top-color: #5b76f7; border-radius: 50%;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.avatar-side { min-width: 0; }
.avatar-name { font-size: 15px; font-weight: 650; color: var(--ink); }
.avatar-hint { font-size: 12.5px; color: var(--ink-3); margin-top: 4px; }
.hidden-input { display: none; }

/* 字段 */
.field { display: block; margin-bottom: 18px; position: relative; }
.field-label { display: block; font-size: 13px; font-weight: 600; color: var(--ink-2);
               margin-bottom: 7px; }
.ipt {
  width: 100%; height: 38px; padding: 0 12px;
  border: 1px solid var(--line); border-radius: 9px; background: #fbfcfe;
  font-size: 14px; color: var(--ink); font: inherit; outline: none;
  transition: border-color .16s, background .16s, box-shadow .16s;
}
.ipt:focus { border-color: #b9c4f5; background: #fff; box-shadow: 0 0 0 3px rgba(91,118,247,.12); }
.ta { height: auto; padding: 9px 12px; resize: vertical; line-height: 1.6; }
.counter { position: absolute; right: 10px; bottom: 7px; font-size: 11px; color: var(--ink-3); }
.ipt-inline { height: 34px; max-width: 180px; }

/* 绑定方式 */
.bind { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.bind-row { display: flex; align-items: center; gap: 12px; padding: 10px 12px; }
.bind-row + .bind-row { border-top: 1px solid var(--line); }
.bind-key { width: 58px; font-size: 13px; color: var(--ink-3); flex-shrink: 0; }
.bind-val { font-size: 13.5px; color: var(--ink); flex: 1; min-width: 0;
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge { font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 5px; }
.badge.ok { background: #eafaf1; color: #12805c; }
.badge.brand { background: var(--brand-soft); color: var(--brand-deep); }

.tip { font-size: 12px; color: var(--ink-3); margin-top: 6px; line-height: 1.55; }

/* 分段选择 */
.seg { display: inline-flex; border: 1px solid var(--line); border-radius: 9px;
       padding: 2px; background: #fbfcfe; gap: 2px; }
.seg-btn { border: 0; background: transparent; padding: 6px 14px; border-radius: 7px;
           font-size: 13px; color: var(--ink-2); cursor: pointer; }
.seg-btn:hover { color: var(--ink); }
.seg-btn.on { background: #fff; color: var(--brand-deep); font-weight: 600;
              box-shadow: 0 1px 3px rgba(22,25,42,.1); }

/* 安全问题行 */
.sq-row { display: flex; gap: 8px; margin-bottom: 9px; align-items: center; }
.sq-q { flex: 1.6; }
.sq-a { flex: 1; }

/* 设备列表 */
.device-list { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.device { display: flex; align-items: center; justify-content: space-between;
          padding: 10px 12px; }
.device + .device { border-top: 1px solid var(--line); }
.dev-main { display: flex; align-items: center; gap: 8px; min-width: 0; }
.dev-ua { font-size: 13.5px; color: var(--ink); }
.dev-ip { font-size: 11.5px; color: var(--ink-3); }
.dev-side { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.dev-time { font-size: 11.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.empty-mini { padding: 18px; text-align: center; font-size: 13px; color: var(--ink-3); }

/* 动作行 */
.actions { display: flex; align-items: center; gap: 12px; margin-top: 4px; }
.saved-tip { font-size: 12.5px; color: #12805c; }
.saved-tip.err { color: var(--danger); }

.btn { height: 36px; padding: 0 16px; border-radius: 9px; border: 1px solid var(--line);
       background: #fff; font-size: 13.5px; color: var(--ink-2); cursor: pointer;
       transition: background .15s, border-color .15s, color .15s; }
.btn:hover { background: #f7f8fc; color: var(--ink); }
.btn:disabled { opacity: .55; cursor: default; }
.btn-primary { background: linear-gradient(135deg, #5b76f7, #7b5bf7); color: #fff;
               border-color: transparent; }
.btn-primary:hover { color: #fff; filter: brightness(1.06); }
.btn-sm { height: 30px; padding: 0 11px; font-size: 12.5px; }
.btn-ghost { border-color: transparent; background: transparent; }
.btn-ghost:hover { background: #f2f4fb; }
.btn-danger-ghost { color: var(--danger); border-color: #ffd5d2; background: #fff8f7; }
.btn-danger-ghost:hover { background: #fff1ef; color: var(--danger); }

/* 窄窗口：tab 从竖排变横排 */
@media (max-width: 860px) {
  .st-body { grid-template-columns: 1fr; gap: 16px; }
  .st-tabs { flex-direction: row; position: static; overflow-x: auto; }
  .st-tab { flex-shrink: 0; }
}
</style>