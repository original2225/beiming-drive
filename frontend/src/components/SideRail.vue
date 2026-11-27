<template>
  <!-- ===== 右侧竖栏 =====
           折叠：顶部头像、底部放大镜，中间空白区提示「可以向左打开」
           展开：头像 / 姓名 / 邮箱 / 用户信息 / 设置 / 主题，最下面放大镜 + 胶囊搜索框 -->
      <aside class="rail" :class="{ open: open }">
        <!-- 折叠态的血条：只有头像和放大镜 -->
        <transition name="rail-fade">
          <div v-if="!open" class="rail-mini">
            <button class="mini-avatar" :title="me?.nickname || '账户'" @click="open = true">
              <img v-if="avatarUrl" :src="avatarUrl" :alt="me?.nickname || '头像'" />
              <span v-else>{{ (me?.nickname || 'U').slice(0, 1) }}</span>
            </button>

            <!-- 中间空白：悬停提示可以向左打开 -->
            <div class="rail-gap" @click="open = true">
              <span class="gap-tip">
                <svg viewBox="0 0 24 24" class="gap-arrow" aria-hidden="true">
                  <path d="M15 6l-6 6 6 6" />
                </svg>
                <em>打开</em>
              </span>
            </div>

            <button class="mini-search" title="搜索" @click="open = true; nextTick(() => railSearch && railSearch.focus())">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="10.5" cy="10.5" r="6.2" />
                <path d="M15.2 15.2L20 20" />
              </svg>
            </button>
          </div>
        </transition>

        <!-- 展开态 -->
        <transition name="rail-slide">
          <div v-if="open" class="rail-full">
            <div class="rail-head">
              <!-- 点头像 → 出现相机遮罩；点遮罩打开文件选择 -->
              <button class="rail-avatar" :class="{ hasImg: !!avatarUrl }" title="更换头像"
                      @click="pickAvatar" @contextmenu.prevent="askRemoveAvatar">
                <img v-if="avatarUrl" :src="avatarUrl" :alt="me?.nickname || '头像'" />
                <span v-else>{{ (me?.nickname || 'U').slice(0, 1) }}</span>
                <span class="avatar-cam" aria-hidden="true">
                  <svg viewBox="0 0 24 24">
                    <path d="M4 8.5A2.5 2.5 0 0 1 6.5 6h1.6l1-1.6h4.8L14.9 6h1.6A2.5 2.5 0 0 1 19 8.5v8A2.5 2.5 0 0 1 16.5 19h-10A2.5 2.5 0 0 1 4 16.5v-8Z" />
                    <circle cx="11.5" cy="12.5" r="3.1" />
                  </svg>
                </span>
                <span v-if="avatarBusy" class="avatar-spin" aria-hidden="true"></span>
              </button>
              <!-- 隐藏的文件选择器：点头像触发，选完立刻上传 -->
              <input ref="avatarInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif"
                     class="avatar-input" @change="onAvatarPicked" />
              <div class="rail-who">
                <div class="rail-name">{{ me?.nickname || '未登录' }}</div>
                <div class="rail-mail" :title="me?.email">{{ me?.email }}</div>
              </div>
              <button class="rail-close" title="收起" @click="open = false">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
              </button>
            </div>

            <div class="rail-quota">
              <div class="rail-quota-bar"><div :style="{ width: barPct + '%' }"></div></div>
              <div class="rail-quota-num">{{ usedText }} / {{ quotaText }}</div>
            </div>

            <nav class="rail-nav">
              <button class="rail-item" @click="railMenuAction('profile')">
                <svg viewBox="0 0 24 24"><circle cx="12" cy="8.2" r="3.6" /><path d="M4.8 20c.9-3.6 3.8-5.6 7.2-5.6s6.3 2 7.2 5.6" /></svg>
                <span>用户信息</span>
              </button>
              <button class="rail-item" @click="railMenuAction('settings')">
                <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.1" /><path d="M12 3.6v2.6M12 17.8v2.6M20.4 12h-2.6M6.2 12H3.6M18 6l-1.8 1.8M7.8 16.2L6 18M18 18l-1.8-1.8M7.8 7.8L6 6" /></svg>
                <span>设置</span>
              </button>
              <button class="rail-item" @click="railMenuAction('theme')">
                <svg viewBox="0 0 24 24"><path d="M12 3.8a8.2 8.2 0 1 0 0 16.4 2 2 0 0 0 0-4 1.4 1.4 0 0 1 0-2.8h3.6A4.6 4.6 0 0 0 20.2 9 8.2 8.2 0 0 0 12 3.8z" /></svg>
                <span>主题</span>
              </button>
              <div class="rail-sep"></div>
              <button class="rail-item" @click="railMenuAction('switch')">
                <svg viewBox="0 0 24 24"><path d="M8 7h11M15.5 3.5L19 7l-3.5 3.5M16 17H5M8.5 13.5L5 17l3.5 3.5" /></svg>
                <span>切换账号</span>
              </button>
              <button class="rail-item danger" @click="railMenuAction('logout')">
                <svg viewBox="0 0 24 24"><path d="M14.5 5.2H7.8A1.8 1.8 0 0 0 6 7v10a1.8 1.8 0 0 0 1.8 1.8h6.7M17.5 12H10M14.6 8.7L18 12l-3.4 3.3" /></svg>
                <span>退出登录</span>
              </button>
            </nav>

            <!-- 最下面：放大镜 + 胶囊搜索框 -->
            <div class="rail-search">
              <svg viewBox="0 0 24 24" class="rs-ic" aria-hidden="true">
                <circle cx="10.5" cy="10.5" r="6.2" /><path d="M15.2 15.2L20 20" />
              </svg>
              <input ref="railSearch" v-model="keyword" placeholder="搜索全部文件…"
                     @keyup.enter="$emit('search', keyword)" />
              <button v-if="keyword" class="rs-clear" @click="clear">×</button>
            </div>
          </div>
        </transition>
        <transition name="rail-toast">
        <div v-if="toast" class="rail-toast">{{ toast }}</div>
      </transition>
    </aside>
</template>

<script setup>
/// 右侧常驻竖栏：折叠只占 62px，展开到 268px。
///
/// 以前这段写在 Home.vue 里，所以只有「我的文件」页有 —— 切到分享/下载器/
/// 存储/个人信息时右边突然空掉，像少了一块。现在挂在 AppShell 上常驻。
///
/// 搜索是唯一和页面内容相关的动作：这里只负责收输入，具体搜什么由
/// 当前页面通过 @search 决定（Home 处理文件搜索，其它页可以忽略或跳转）。
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { api, getToken, setToken } from '../api.js'

const emit = defineEmits(['search'])
const router = useRouter()

const open = ref(false)
const keyword = ref('')
const railSearch = ref(null)
const me = ref(null)
const saved = ref('')
const saving = ref(false)
const toast = ref('')
let toastTimer = null

function sizeText(b) {
  const n = Number(b) || 0
  if (n < 1024) return n + ' B'
  const u = ['KB', 'MB', 'GB', 'TB']
  let v = n / 1024, i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + u[i]
}

const usedText = computed(() => sizeText(me.value?.storageUsed || 0))
const quotaText = computed(() => sizeText(me.value?.storageQuota || 0))
const barPct = computed(() => {
  const m = me.value
  if (!m || !m.storageQuota) return 0
  return Math.min(100, (m.storageUsed / m.storageQuota) * 100)
})

function clear() {
  keyword.value = ''
  emit('search', '')
}

async function loadMe() {
  try { me.value = await api.me() } catch (e) { /* 401 交给路由守卫 */ }
}

/* ---------- 竖栏菜单 ---------- */

function showToast(msg) {
  // 侧栏自己做轻提示，不依赖父页面（父页面不一定有 toast）
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 1800)
}

/// 「切换账号 / 退出登录」都要清 token 再回登录页
async function doLogout() {
  try { await api.logout() } catch (e) { /* 服务端吊销失败也要本地清干净 */ }
  setToken('')
  router.push('/login')
}

function railMenuAction(kind) {
  if (kind === 'profile') { router.push('/profile'); open.value = false; return }
  if (kind === 'settings') { router.push('/profile'); open.value = false; return }
  if (kind === 'theme') { showToast('主题切换还在做'); return }
  if (kind === 'switch') {
    if (confirm('切换会退出当前账号，回到登录页重新登录。确定吗？')) doLogout()
    return
  }
  if (kind === 'logout') {
    if (confirm('确定退出当前账号？')) doLogout()
  }
}

/* ---------- 头像 ---------- */

const avatarBusy = ref(false)
const avatarInput = ref(null)

/// 头像地址带 avatarKey 破缓存，并附 access_token ——
/// <img src> 发不出 Authorization 头，拦截器支持查询参数形式。
const avatarUrl = computed(() => {
  const key = me.value?.avatarKey
  if (!key) return ''
  const q = new URLSearchParams({ v: key, access_token: getToken() || '' })
  return `/api/v1/auth/avatar?${q.toString()}`
})

function pickAvatar() { avatarInput.value?.click() }

async function onAvatarPicked(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (file.size > 1024 * 1024) { showToast('图片不能超过 1 MB'); return }
  if (!/^image\/(png|jpeg|webp|gif)$/.test(file.type)) { showToast('只支持 PNG / JPEG / WebP / GIF'); return }
  avatarBusy.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    await api.uploadAvatar(fd)
    await loadMe()
    window.dispatchEvent(new CustomEvent('drive:profile-changed'))
    showToast('头像已更新')
  } catch (err) {
    showToast(err?.message || '上传失败')
  } finally {
    avatarBusy.value = false
  }
}

function askRemoveAvatar() {
  if (!me.value?.avatarKey) return
  if (!confirm('移除头像？移除后会显示昵称首字。')) return
  api.deleteAvatar().then(async () => {
    await loadMe()
    window.dispatchEvent(new CustomEvent('drive:profile-changed'))
    showToast('头像已移除')
  }).catch(err => showToast(err?.message || '移除失败'))
}

/// 别处改了资料（个人信息页）后，这里要跟着刷新
onMounted(() => {
  window.addEventListener('drive:profile-changed', loadMe)
})

onMounted(loadMe)
</script>

<style scoped>
/* ================= 右侧竖栏 =================
   收起时只占 62px：顶一个头像、底一个放大镜，中间是留白。
   展开时宽到 268px，从右侧「向左推出」——用 width 过渡而不是 transform，
   因为 transform 平移不会改变布局，会把主区内容盖住。 */
.rail {
  flex-shrink: 0;
  width: 62px;
  background: #fff;
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width .32s cubic-bezier(.3, .6, .35, 1);
}
.rail.open { width: 268px; }

/* ---------- 收起态 ---------- */
.rail-mini {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 0;
  gap: 10px;
}
/* 头像和放大镜：悬停轻微放大 + 加深，给「这里能点」的反馈 */
.mini-avatar, .mini-search {
  width: 38px; height: 38px; border-radius: 50%;
  display: grid; place-items: center; padding: 0;
  border: 1px solid var(--line); background: #f7f8fc; color: var(--ink-2);
  transition: transform .2s cubic-bezier(.3, .6, .35, 1), box-shadow .2s, background .2s, border-color .2s;
}
.mini-avatar span {
  font-size: 15px; font-weight: 600; color: #fff; letter-spacing: .5px;
}
.mini-avatar {
  background: linear-gradient(135deg, #5b76f7, #7b5bf7);
  border-color: transparent;
  overflow: hidden;
}
.mini-avatar > img { width: 100%; height: 100%; object-fit: cover; display: block; border-radius: inherit; }
.mini-avatar:hover {
  transform: scale(1.12);
  box-shadow: 0 6px 18px rgba(91, 118, 247, .38);
}
.mini-search svg { width: 19px; height: 19px; fill: none; stroke: currentColor; stroke-width: 1.9; stroke-linecap: round; }
.mini-search:hover {
  transform: scale(1.14);
  border-color: #cfd6ea;
  background: #fff;
  color: var(--ink);
  box-shadow: 0 4px 14px rgba(20, 26, 48, .12);
}

/* 中间的留白：平时是空的，鼠标进来才浮出「打开」提示 */
.rail-gap {
  flex: 1;
  width: 100%;
  display: grid;
  place-items: center;
  cursor: pointer;
  position: relative;
}
.gap-tip {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  opacity: 0; transform: translateX(-4px);
  transition: opacity .22s ease, transform .22s ease;
  color: var(--ink-3); font-size: 11.5px;
}
.rail-gap:hover .gap-tip { opacity: 1; transform: none; }
.gap-tip em { font-style: normal; }
.gap-arrow {
  width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 2;
  stroke-linecap: round; stroke-linejoin: round;
}
/* 展开态从上往下滑入 */
.rail-slide-enter-active { transition: opacity .24s ease .04s, transform .3s cubic-bezier(.3, .6, .35, 1); }
.rail-slide-leave-active { transition: opacity .14s ease, transform .14s ease; }
.rail-slide-enter-from { opacity: 0; transform: translateX(16px); }
.rail-slide-leave-to { opacity: 0; transform: translateX(10px); }
.rail-fade-leave-active { transition: opacity .12s ease; }
.rail-fade-leave-to { opacity: 0; }

/* ---------- 展开态 ---------- */
.rail-full {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 16px 14px 14px;
  width: 268px;
}
.rail-head { display: flex; align-items: center; gap: 10px; padding: 2px 2px 12px; }
.rail-avatar {
  position: relative; overflow: hidden;
  width: 42px; height: 42px; border-radius: 50%; flex-shrink: 0;
  display: grid; place-items: center; color: #fff; font-size: 16px; font-weight: 600;
  background: linear-gradient(135deg, #5b76f7, #7b5bf7);
  box-shadow: 0 3px 12px rgba(91, 118, 247, .3);
  border: 0; padding: 0; cursor: pointer;
  transition: box-shadow .2s, transform .2s;
}
.rail-avatar > img {
  width: 100%; height: 100%; object-fit: cover; display: block;
}
.rail-avatar:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(91, 118, 247, .4); }

/* 相机遮罩：平时透明，悬停或聚焦时浮出，让「能改」这件事看得见 */
.avatar-cam {
  position: absolute; inset: 0;
  display: grid; place-items: center;
  background: rgba(20, 24, 40, .52);
  opacity: 0; transition: opacity .18s;
  pointer-events: none;
}
.avatar-cam svg {
  width: 19px; height: 19px; fill: none;
  stroke: #fff; stroke-width: 1.7; stroke-linejoin: round;
}
.rail-avatar:hover .avatar-cam,
.rail-avatar:focus-visible .avatar-cam { opacity: 1; }
/* 有图片时遮罩稍微淡一点，别盖住整张脸 */
.rail-avatar.hasImg:hover .avatar-cam { opacity: .85; }

/* 上传中：头像压暗 + 转圈 */
.avatar-spin {
  position: absolute; inset: 0; border-radius: 50%;
  background: rgba(255, 255, 255, .55);
}
.avatar-spin::after {
  content: ''; position: absolute; inset: 50% auto auto 50%;
  width: 16px; height: 16px; margin: -8px 0 0 -8px;
  border: 2px solid rgba(91, 118, 247, .28);
  border-top-color: #5b76f7; border-radius: 50%;
  animation: avatar-spin .7s linear infinite;
}
@keyframes avatar-spin { to { transform: rotate(360deg); } }

.avatar-input { display: none; }
.rail-who { min-width: 0; flex: 1; }
.rail-name { font-size: 14.5px; font-weight: 600; overflow: hidden;
             text-overflow: ellipsis; white-space: nowrap; }
.rail-mail { font-size: 11.5px; color: var(--ink-3); overflow: hidden;
             text-overflow: ellipsis; white-space: nowrap; margin-top: 2px; }
.rail-close {
  width: 26px; height: 26px; border-radius: 7px; flex-shrink: 0;
  border: 1px solid transparent; background: transparent; color: var(--ink-3);
  display: grid; place-items: center; transition: .16s;
}
.rail-close svg { width: 15px; height: 15px; fill: none; stroke: currentColor;
                  stroke-width: 2.1; stroke-linecap: round; stroke-linejoin: round; }
.rail-close:hover { background: #f2f4fb; border-color: var(--line); color: var(--ink); }

.rail-quota { padding: 0 2px 12px; border-bottom: 1px solid var(--line); margin-bottom: 10px; }
.rail-quota-bar { height: 4px; border-radius: 3px; background: #eef0f7; overflow: hidden; }
.rail-quota-bar > div { height: 100%; border-radius: 3px;
                        background: linear-gradient(90deg, #5b76f7, #7b5bf7);
                        transition: width .3s ease; }
.rail-quota-num { font-size: 11.5px; color: var(--ink-3); margin-top: 6px; }

.rail-nav { display: flex; flex-direction: column; gap: 2px; }
.rail-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 9px; border: 0; background: transparent;
  font-size: 14px; color: var(--ink-2); text-align: left; width: 100%;
  transition: background .15s, color .15s;
}
.rail-item svg { width: 17px; height: 17px; flex-shrink: 0; fill: none;
                 stroke: currentColor; stroke-width: 1.7;
                 stroke-linecap: round; stroke-linejoin: round; }
.rail-item:hover { background: #f3f5fd; color: var(--ink); }
.rail-item.danger { color: var(--danger); }
.rail-item.danger:hover { background: #fff5f4; }
.rail-sep { height: 1px; background: var(--line); margin: 6px 2px; }

/* 最下方：放大镜 + 胶囊搜索框 */
.rail-search {
  margin-top: auto;
  display: flex; align-items: center; gap: 6px;
  height: 38px; padding: 0 4px 0 10px;
  border: 1px solid var(--line); border-radius: 999px; background: #f7f8fc;
  transition: border-color .2s, background .2s, box-shadow .2s;
}
.rail-search:focus-within {
  border-color: #b9c4f5; background: #fff;
  box-shadow: 0 0 0 3px rgba(91, 118, 247, .12);
}
.rs-ic { width: 17px; height: 17px; flex-shrink: 0; fill: none;
         stroke: var(--ink-3); stroke-width: 1.9; stroke-linecap: round; }
.rail-search input {
  flex: 1; min-width: 0; border: 0; outline: 0; background: transparent;
  font-size: 14px; color: var(--ink); padding: 0;
}
.rail-search input::placeholder { color: var(--ink-3); }
.rs-clear {
  width: 22px; height: 22px; border-radius: 50%; flex-shrink: 0;
  border: 0; background: #e6e9f3; color: var(--ink-2);
  font-size: 14px; line-height: 1; display: grid; place-items: center;
}
.rs-clear:hover { background: #d8dcec; }

.rail-toast {
  position: absolute; left: 50%; bottom: 14px; transform: translateX(-50%);
  background: rgba(22,25,42,.9); color: #fff; font-size: 12px;
  padding: 7px 13px; border-radius: 8px; white-space: nowrap; z-index: 5;
}
.rail-toast-enter-active, .rail-toast-leave-active { transition: opacity .18s ease; }
.rail-toast-enter-from, .rail-toast-leave-to { opacity: 0; }
</style>
