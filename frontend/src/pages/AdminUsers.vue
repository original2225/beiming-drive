<template>
  <div class="page admin-page">
    <header class="ad-head">
      <div>
        <h1>用户管理</h1>
        <p class="ad-sub">在这里指派管理员、封禁账号、调整每个人的空间配额。</p>
      </div>
      <div class="ad-count">{{ users.length }} 个账号</div>
    </header>

    <!-- 没有权限时不装样子：直接说清楚，不显示一个点了会报错的列表 -->
    <div v-if="!allowed" class="no-perm">
      <div class="np-icon">🔒</div>
      <div class="np-title">这里只有管理员能进</div>
      <div class="np-sub">如果你是管理员但看到这个，可能是账号角色还没刷新，重新登录一次试试。</div>
    </div>

    <template v-else>
      <div class="ad-bar">
        <input v-model="kw" class="ipt search" placeholder="搜索邮箱或昵称" @keyup.enter="load" />
        <button class="btn btn-sm" @click="load">搜索</button>
      </div>

      <div class="ad-table">
        <div class="th">
          <span>账号</span><span>角色</span><span>状态</span><span>已用 / 配额</span><span>操作</span>
        </div>
        <div v-for="u in users" :key="u.id" class="tr" :class="{ self: u.id === myId }">
          <div class="col-user">
            <div class="u-name">{{ u.nickname }}</div>
            <div class="u-mail">{{ u.email }}</div>
          </div>
          <div class="col-role">
            <span class="role-chip" :class="roleClass(u.role)">{{ u.roleLabel }}</span>
          </div>
          <div class="col-status">
            <span class="dot" :class="{ off: u.status !== 'ACTIVE' }"></span>
            {{ u.status === 'ACTIVE' ? '正常' : '已封禁' }}
          </div>
          <div class="col-quota">
            <div class="q-bar"><div :style="{ width: pct(u) + '%' }"></div></div>
            <div class="q-num">{{ size(u.used) }} / {{ size(u.quota) }}</div>
          </div>
          <div class="col-act">
            <!-- 角色下拉：只列「当前操作者有权指派」的选项，选不了的干脆不出现 -->
            <select v-if="canAssign(u)" class="mini-sel" :value="u.role"
                    @change="e => changeRole(u, e.target.value)">
              <option v-for="r in assignable()" :key="r.value" :value="r.value">{{ r.label }}</option>
            </select>
            <button v-if="canTune(u)" class="btn btn-sm btn-ghost" @click="editQuota(u)">配额</button>
            <button v-if="canTune(u)" class="btn btn-sm btn-ghost" @click="toggleStatus(u)">
              {{ u.status === 'ACTIVE' ? '封禁' : '解封' }}
            </button>
            <span v-if="!canTune(u) && !canAssign(u)" class="muted-mini">—</span>
          </div>
        </div>
        <div v-if="!users.length" class="empty-mini">没有匹配的账号</div>
      </div>

      <div class="ad-foot">
        <div class="legend">
          <span class="role-chip is-super">超级管理员</span>
          全站最高权限，能指派管理员，一共只有一个
          <span class="sep">·</span>
          <span class="role-chip is-admin">管理员</span>
          管用户、改配额、用维护工具
          <span class="sep">·</span>
          <span class="role-chip is-user">用户</span>
          只管自己的文件
          <span class="sep">·</span>
          <span class="role-chip is-guest">游客</span>
          未登录，只能下载公开分享
        </div>
      </div>
    </template>

    <!-- 配额编辑：自绘小弹窗，不用原生 prompt（原生弹窗会卡死自动化，也不好看） -->
    <transition name="pop">
      <div v-if="quotaBox" class="mask" @click.self="quotaBox = null">
        <div class="box">
          <div class="box-title">调整配额 · {{ quotaBox.nickname }}</div>
          <div class="box-desc">当前已用 {{ size(quotaBox.used) }}，新配额不能小于它。</div>
          <div class="box-row">
            <input v-model.number="quotaGB" type="number" min="1" step="1" class="ipt"
                   placeholder="GB" />
            <span class="unit">GB</span>
          </div>
          <div class="box-err" v-if="quotaErr">{{ quotaErr }}</div>
          <div class="box-act">
            <button class="btn" @click="quotaBox = null">取消</button>
            <button class="btn btn-primary" :disabled="quotaBusy" @click="saveQuota">
              {{ quotaBusy ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'

const users = ref([])
const kw = ref('')
const me = ref(null)
const allowed = ref(true)

const myId = computed(() => me.value?.id)
const myRole = computed(() => me.value?.role || 'GUEST')

/* 谁能指派谁 —— 和后端 Role.canAssign 保持同一套规则。
   前端这份只用来「少显示点选不了的按钮」，真正的闸门在后端。 */
function canAssign(u) {
  if (u.id === myId.value) return false                       // 不能改自己
  if (myRole.value === 'SUPER_ADMIN') return u.role !== 'SUPER_ADMIN'
  return false                                                 // 管理员不能指派任何人
}

function canTune(u) {
  if (u.id === myId.value) return false
  if (myRole.value === 'SUPER_ADMIN') return u.role !== 'SUPER_ADMIN'
  if (myRole.value === 'ADMIN') return u.role === 'USER'
  return false
}

function assignable() {
  return myRole.value === 'SUPER_ADMIN'
    ? [{ value: 'ADMIN', label: '管理员' }, { value: 'USER', label: '用户' }]
    : []
}

async function load() {
  try {
    me.value = await api.me()
    users.value = await api.listUsers(kw.value)
    allowed.value = true
  } catch (e) {
    // 403 是「有账号但不是管理员」，不是网络错，别显示成错误
    if (e.code === 403 || e.code === 43000) allowed.value = false
  }
}

function roleClass(r) {
  return { 'is-super': r === 'SUPER_ADMIN', 'is-admin': r === 'ADMIN',
           'is-user': r === 'USER', 'is-guest': r === 'GUEST' }
}

function size(b) {
  const n = Number(b) || 0
  if (n < 1024) return n + ' B'
  const u = ['KB', 'MB', 'GB', 'TB']
  let v = n / 1024, i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + u[i]
}

function pct(u) {
  if (!u.quota) return 0
  return Math.min(100, Math.max(0, (u.used / u.quota) * 100))
}

async function changeRole(u, role) {
  if (role === u.role) return
  try {
    await api.changeUserRole(u.id, role)
    await load()
  } catch (e) {
    alertBox(e.message || '改角色失败')
    await load()   // 失败要把下拉框弹回真实值
  }
}

async function toggleStatus(u) {
  const next = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const verb = next === 'DISABLED' ? '封禁' : '解封'
  if (!window.confirm(`${verb}账号「${u.nickname}」？${next === 'DISABLED' ? '封禁会立刻把他踢下线。' : ''}`)) return
  try {
    await api.setUserStatus(u.id, next)
    await load()
  } catch (e) {
    alertBox(e.message || `${verb}失败`)
  }
}

/* ---------- 配额弹窗 ---------- */
const quotaBox = ref(null)
const quotaGB = ref(50)
const quotaErr = ref('')
const quotaBusy = ref(false)

function editQuota(u) {
  quotaBox.value = u
  quotaGB.value = Math.max(1, Math.round(u.quota / 1073741824))
  quotaErr.value = ''
}

async function saveQuota() {
  quotaErr.value = ''
  const gb = Number(quotaGB.value)
  if (!gb || gb < 1) { quotaErr.value = '至少要 1 GB'; return }
  const bytes = gb * 1073741824
  if (bytes < quotaBox.value.used) { quotaErr.value = '小于已用量，会立刻超额'; return }
  quotaBusy.value = true
  try {
    await api.setUserQuota(quotaBox.value.id, bytes)
    quotaBox.value = null
    await load()
  } catch (e) {
    quotaErr.value = e.message || '保存失败'
  } finally { quotaBusy.value = false }
}

function alertBox(msg) { window.alert(msg) }

onMounted(load)
</script>

<style scoped>
.admin-page { padding: 30px var(--page-pad) 46px; }
.ad-head { display: flex; align-items: flex-start; justify-content: space-between;
           gap: 20px; max-width: var(--content-max); }
.ad-head h1 { font-size: 24px; font-weight: 650; color: var(--ink); margin: 0; }
.ad-sub { font-size: 13.5px; color: var(--ink-3); margin: 7px 0 20px; }
.ad-count { font-size: 12.5px; color: var(--ink-3); background: #f2f4fb;
            padding: 5px 11px; border-radius: 7px; white-space: nowrap; }

.no-perm { max-width: var(--content-max); background: #fff; border: 1px solid var(--line);
           border-radius: 14px; padding: 48px 24px; text-align: center; }
.np-icon { font-size: 30px; margin-bottom: 10px; }
.np-title { font-size: 15px; font-weight: 650; color: var(--ink); }
.np-sub { font-size: 13px; color: var(--ink-3); margin-top: 7px; }

.ad-bar { display: flex; gap: 8px; margin-bottom: 14px; max-width: var(--content-max); }
.ipt { height: 34px; padding: 0 11px; border: 1px solid var(--line); border-radius: 9px;
       background: #fbfcfe; font-size: 13.5px; color: var(--ink); font: inherit; outline: none; }
.ipt:focus { border-color: #b9c4f5; background: #fff; box-shadow: 0 0 0 3px rgba(91,118,247,.12); }
.search { width: 260px; }

.ad-table { background: #fff; border: 1px solid var(--line); border-radius: 14px;
            overflow: hidden; max-width: var(--content-max); }
.th, .tr { display: grid;
           grid-template-columns: minmax(0,1.6fr) 96px 88px minmax(0,1.2fr) 200px;
           gap: 12px; align-items: center; padding: 11px 16px; }
.th { font-size: 11.5px; color: var(--ink-3); font-weight: 600;
      background: #fafbfd; border-bottom: 1px solid var(--line); }
.tr { border-bottom: 1px solid #f1f3f9; }
.tr:last-child { border-bottom: 0; }
.tr:hover { background: #fbfcfe; }
.tr.self { background: #f7f9ff; }

.u-name { font-size: 13.5px; font-weight: 600; color: var(--ink);
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.u-mail { font-size: 11.5px; color: var(--ink-3); margin-top: 2px;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.role-chip { font-size: 11.5px; font-weight: 600; padding: 3px 8px; border-radius: 6px; }
.role-chip.is-super { background: #f3ecff; color: #6b3fd4; }
.role-chip.is-admin { background: #eaf0ff; color: #3b52c9; }
.role-chip.is-user { background: #f0f2f8; color: #5b6478; }
.role-chip.is-guest { background: #f7f7fa; color: #8a90a6; }

.col-status { font-size: 12.5px; color: var(--ink-2); display: flex; align-items: center; gap: 6px; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: #17b26a; flex-shrink: 0; }
.dot.off { background: #b0b5c5; }

.q-bar { height: 4px; border-radius: 3px; background: #eef0f7; overflow: hidden; }
.q-bar > div { height: 100%; border-radius: 3px;
               background: linear-gradient(90deg, #5b76f7, #7b5bf7); }
.q-num { font-size: 11px; color: var(--ink-3); margin-top: 4px;
         font-variant-numeric: tabular-nums; }

.col-act { display: flex; align-items: center; gap: 6px; justify-content: flex-end; }
.mini-sel { height: 28px; padding: 0 6px; border: 1px solid var(--line); border-radius: 7px;
            background: #fff; font-size: 12.5px; color: var(--ink-2); font: inherit; cursor: pointer; }
.btn { height: 28px; padding: 0 10px; border-radius: 7px; border: 1px solid transparent;
       background: transparent; font-size: 12.5px; color: var(--ink-2); cursor: pointer;
       transition: background .15s, color .15s; }
.btn:hover { background: #f2f4fb; color: var(--ink); }
.btn-sm { height: 28px; padding: 0 10px; font-size: 12.5px; }
.btn-ghost { border-color: transparent; background: transparent; }
.btn-primary { background: linear-gradient(135deg, #5b76f7, #7b5bf7); color: #fff; }
.muted-mini { font-size: 12px; color: #c3c7d4; }
.empty-mini { padding: 26px; text-align: center; font-size: 13px; color: var(--ink-3); }

.ad-foot { max-width: var(--content-max); margin-top: 16px; }
.legend { font-size: 11.5px; color: var(--ink-3); line-height: 2; }
.legend .sep { margin: 0 8px; color: #d7dae5; }
.legend .role-chip { margin: 0 3px; }

/* 配额弹窗 */
.mask { position: fixed; inset: 0; background: rgba(20,24,40,.34);
        display: grid; place-items: center; z-index: 60; }
.box { width: 340px; background: #fff; border-radius: 14px; padding: 20px 22px;
       box-shadow: 0 18px 50px rgba(20,24,40,.24); }
.box-title { font-size: 15px; font-weight: 650; color: var(--ink); }
.box-desc { font-size: 12.5px; color: var(--ink-3); margin: 6px 0 14px; line-height: 1.6; }
.box-row { display: flex; align-items: center; gap: 8px; }
.box-row .ipt { flex: 1; }
.unit { font-size: 13px; color: var(--ink-3); }
.box-err { font-size: 12.5px; color: var(--danger); margin-top: 8px; }
.box-act { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.pop-enter-active, .pop-leave-active { transition: opacity .16s ease; }
.pop-enter-from, .pop-leave-to { opacity: 0; }

@media (max-width: 900px) {
  .th, .tr { grid-template-columns: minmax(0,1.4fr) 88px 80px 120px; }
  .col-act { grid-column: 1 / -1; justify-content: flex-start; margin-top: 6px; }
  .th > :last-child { display: none; }
}
</style>