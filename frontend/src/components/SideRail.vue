<template>
  <!-- ===== 右侧竖栏 =====
           折叠：顶部头像、底部放大镜，中间空白区提示「可以向左打开」
           展开：头像 / 姓名 / 邮箱 / 用户信息 / 设置 / 主题，最下面放大镜 + 胶囊搜索框 -->
      <aside class="rail" :class="{ open }">
    <!-- 头部：头像 + 姓名邮箱。收起态头像居中、姓名宽度为 0；
         展开时头像 38→42、姓名从 0 宽铺开。同一套 DOM，不换块。 -->
    <div class="rail-head">
      <button class="rail-avatar" :class="{ hasImg: !!avatarUrl }"
              :title="open ? '更换头像（右键移除）' : (me?.nickname || '账户')"
              @click="onAvatarClick" @contextmenu.prevent="askRemoveAvatar">
        <img v-if="avatarUrl" :src="avatarUrl" :alt="me?.nickname || '头像'" />
        <span v-else class="avatar-letter">{{ (me?.nickname || 'U').slice(0, 1) }}</span>
        <!-- 相机遮罩只在展开态出现：收起时点头像是「打开抽屉」，不是「换头像」 -->
        <span class="avatar-cam" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M4 8.5A2.5 2.5 0 0 1 6.5 6h1.6l1-1.6h4.8L14.9 6h1.6A2.5 2.5 0 0 1 19 8.5v8A2.5 2.5 0 0 1 16.5 19h-10A2.5 2.5 0 0 1 4 16.5v-8Z" />
            <circle cx="11.5" cy="12.5" r="3.1" />
          </svg>
        </span>
        <span v-if="avatarBusy" class="avatar-spin" aria-hidden="true"></span>
      </button>
      <!-- 隐藏的文件选择器 -->
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

    <!-- 收起态那段留白：展开时让位给菜单（flex-grow 收回 0） -->
    <div class="rail-gap" @click="open = true">
      <span class="gap-tip">
        <svg viewBox="0 0 24 24" class="gap-arrow" aria-hidden="true">
          <path d="M15 6l-6 6 6 6" />
        </svg>
        <em>打开</em>
      </span>
    </div>

    <!-- 展开才有的内容。用 max-height + opacity 过渡，不用 v-if：
         v-if 会在宽度还在长的中途整块凭空出现，那就是闪的来源。 -->
    <div class="rail-body" :aria-hidden="!open">

            <div class="rail-quota">
              <div class="rail-quota-bar"><div :style="{ width: barPct + '%' }"></div></div>
              <div class="rail-quota-num">{{ usedText }} / {{ quotaText }}</div>
            </div>

            <!-- ===== 下载记录 =====
                 默认折叠成一行标题，点开才拉列表。理由：抽屉本体已经很窄，
                 两个列表都默认展开会把菜单挤出去，反而不好用。 -->
            <section class="rail-sec" :class="{ folded: !dlOpen }">
              <button class="rail-sec-head" @click="toggleDl">
                <svg viewBox="0 0 24 24" class="sec-ic"><path d="M12 4v10m0 0l-4-4m4 4l4-4M5 19h14" /></svg>
                <span class="sec-title">下载记录</span>
                <span v-if="downloads.length" class="sec-badge">{{ downloads.length }}</span>
                <svg viewBox="0 0 24 24" class="sec-caret"><path d="M6 9l6 6 6-6" /></svg>
              </button>
              <div class="sec-list">
                <div v-for="d in downloads" :key="d.id" class="sec-row">
                  <button class="row-main" :title="d.path || d.filename"
                          @click="openFile(d.nodeId, d.parentId)">
                    <span class="row-name">{{ d.filename }}</span>
                    <span class="row-meta">
                      {{ fmtSize(d.size) }} · {{ fmtWhen(d.lastAt) }}<template v-if="d.times > 1"> · {{ d.times }} 次</template>
                    </span>
                  </button>
                  <button class="row-del" title="从记录里移除" @click.stop="removeDl(d)">×</button>
                </div>
                <div v-if="!downloads.length" class="sec-empty">还没有下载过文件</div>
                <button v-if="downloads.length" class="sec-more" @click="clearDl">清空记录</button>
              </div>
            </section>

            <!-- ===== 收藏夹 ===== -->
            <section class="rail-sec" :class="{ folded: !starOpen }">
              <button class="rail-sec-head" @click="toggleStar">
                <svg viewBox="0 0 24 24" class="sec-ic star-ic"><path d="M12 4l2.5 5.2 5.7.8-4.1 4 1 5.7-5.1-2.7-5.1 2.7 1-5.7-4.1-4 5.7-.8z" /></svg>
                <span class="sec-title">收藏夹</span>
                <span v-if="stars.length" class="sec-badge">{{ stars.length }}</span>
                <svg viewBox="0 0 24 24" class="sec-caret"><path d="M6 9l6 6 6-6" /></svg>
              </button>
              <div class="sec-list">
                <div v-for="s in stars" :key="s.nodeId" class="sec-row">
                  <button class="row-main" :title="s.path" @click="openFile(s.nodeId, s.path)">
                    <span class="row-name">{{ s.filename }}</span>
                    <span class="row-meta">{{ s.isDir ? '文件夹' : fmtSize(s.size) }}</span>
                  </button>
                  <button class="row-del" title="取消收藏" @click.stop="unstar(s)">×</button>
                </div>
                <div v-if="!stars.length" class="sec-empty">还没有收藏文件</div>
              </div>
            </section>

            <nav class="rail-nav">
              <button class="rail-item" @click="railMenuAction('profile')">
                <svg viewBox="0 0 24 24"><circle cx="12" cy="8.2" r="3.6" /><path d="M4.8 20c.9-3.6 3.8-5.6 7.2-5.6s6.3 2 7.2 5.6" /></svg>
                <span>用户信息</span>
              </button>
              <button class="rail-item" @click="railMenuAction('settings')">
                <svg viewBox="0 0 24 24"><path d="M12 3.6v2.6M12 17.8v2.6M20.4 12h-2.6M6.2 12H3.6M18 6l-1.8 1.8M7.8 16.2L6 18M18 18l-1.8-1.8M7.8 7.8L6 6" /><circle cx="12" cy="12" r="3.1" /></svg>
                <span>设置</span>
              </button>
              <!-- 用户管理只给管理员看。前端藏起来是为了不误导，
                   真正的闸门在后端 AccountController.requireAdmin()。 -->
              <button v-if="isStaff" class="rail-item" @click="railMenuAction('adminUsers')">
                <svg viewBox="0 0 24 24"><circle cx="9" cy="8.2" r="3.1" /><path d="M2.8 19c.8-3.2 3.2-5 6.2-5s5.4 1.8 6.2 5" /><circle cx="17.5" cy="9.5" r="2.4" /><path d="M15.6 14.4c2.6.2 4.6 1.9 5.4 4.6" /></svg>
                <span>用户管理</span>
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
    </div>

    <!-- 底部：收起时是圆形放大镜按钮，展开时膨胀成胶囊搜索框。
         同一个元素，只改宽高/圆角/对齐，所以是「长大」不是「换一个」。 -->
    <div class="rail-search" :class="{ 'is-open': open }" @click="onSearchClick">
      <svg viewBox="0 0 24 24" class="rs-ic" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.2" /><path d="M15.2 15.2L20 20" />
      </svg>
      <input ref="railSearch" v-model="keyword" placeholder="搜索全部文件…"
             :tabindex="open ? 0 : -1"
             @keyup.enter="$emit('search', keyword)" />
      <button v-if="keyword" class="rs-clear" @click.stop="clear">×</button>
    </div>

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

/// 是不是后台员工（超管/管理员）。只用来决定「用户管理」这一项显不显示，
/// 真正的权限判断在后端 —— 前端藏起来只是不误导，不是安全边界。
const isStaff = computed(() => me.value?.role === 'SUPER_ADMIN' || me.value?.role === 'ADMIN')

/* ==================== 下载记录 / 收藏夹 ==================== */

const downloads = ref([])
const stars = ref([])
const dlOpen = ref(false)
const starOpen = ref(false)

function fmtSize(b) { return sizeText(b) }

/// 「3 分钟前 / 昨天 / 3-15」。侧栏很窄，放不下完整日期时间。
function fmtWhen(iso) {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  const diff = Date.now() - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + ' 小时前'
  if (diff < 172_800_000) return '昨天'
  const d = new Date(iso)
  return `${d.getMonth() + 1}-${d.getDate()}`
}

async function loadDownloads() {
  try { downloads.value = await api.downloads(20) } catch (e) { downloads.value = [] }
}
async function loadStars() {
  try { stars.value = await api.stars(30) } catch (e) { stars.value = [] }
}

/// 两个列表都是「点开才拉」，避免侧栏每次展开都发两个请求。
async function toggleDl() {
  dlOpen.value = !dlOpen.value
  if (dlOpen.value && !downloads.value.length) await loadDownloads()
}
async function toggleStar() {
  starOpen.value = !starOpen.value
  if (starOpen.value && !stars.value.length) await loadStars()
}

async function removeDl(d) {
  try {
    await api.deleteDownload(d.id)
    downloads.value = downloads.value.filter(x => x.id !== d.id)
  } catch (e) { showToast('移除失败') }
}

async function clearDl() {
  if (!confirm('清空全部下载记录？文件本身不会被删。')) return
  try {
    await api.clearDownloads()
    downloads.value = []
  } catch (e) { showToast('清空失败') }
}

async function unstar(s) {
  try {
    await api.unstar(s.nodeId)
    stars.value = stars.value.filter(x => x.nodeId !== s.nodeId)
    // 通知列表页把那一行的星标也灭掉（两边同时可见，必须同步）
    window.dispatchEvent(new CustomEvent('drive:stars-changed'))
  } catch (e) { showToast('取消收藏失败') }
}

/* 文件列表里点星标时派这个事件过来 —— 收藏夹可能是展开状态，
 * 不重拉就会和列表显示不一致（列表已取消、这里还留着）。 */
async function onStarsChanged() {
  if (starOpen.value) await loadStars()
}

onMounted(() => {
  window.addEventListener('drive:stars-changed', onStarsChanged)
})
onBeforeUnmount(() => {
  window.removeEventListener('drive:stars-changed', onStarsChanged)
})

/// 点记录行 = 「去那个文件所在的地方」。
///
/// 侧栏是全站常驻的，用户可能在「我的分享」「下载器」页点一条记录 ——
/// 那里没有文件列表，什么都不会发生。所以统一跳回「我的文件」并进到
/// 该文件所在的目录，再把 nodeId 交给 Home 高亮。
///
/// 用后端给的 parentId 而不是从 path 剥目录名：文件名里带 `/`
/// 时剥法会出错，而且 path 的格式将来可能改。
function openFile(nodeId, parentId) {
  if (!nodeId) { showToast('这个文件已经被删除了'); return }
  router.push({
    path: '/',
    query: parentId ? { parent: parentId, node: nodeId } : { node: nodeId },
  })
  open.value = false
  nextTick(() => window.dispatchEvent(
    new CustomEvent('drive:focus-node', { detail: { nodeId } })))
}


function clear() {
  keyword.value = ''
  emit('search', '')
}

async function loadMe() {
  try { me.value = await api.me() } catch (e) { /* 401 交给路由守卫 */ }
}

/* ---------- 打开 / 收起 ---------- */

/// 收起态点头像 = 打开抽屉；展开态点头像 = 换头像。
/// 不区分的话，两个动作会抢同一个点击 —— 点一下就变了头像。
function onAvatarClick() {
  if (!open.value) { open.value = true; return }
  pickAvatar()
}

/// 收起态点放大镜先打开，等宽度长到位再把光标放进去。
/// 立刻 focus 会让浏览器在 38px 的圆里滚动，看起来像一跳。
function onSearchClick() {
  if (open.value) return
  open.value = true
  setTimeout(() => railSearch.value?.focus(), 340)
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
  if (kind === 'settings') { router.push('/settings'); open.value = false; return }
  if (kind === 'adminUsers') { router.push('/admin/users'); open.value = false; return }
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
  // 只在展开态响应：收起态右键不该弹窗，那是「打开抽屉」的区域
  if (!open.value || !me.value?.avatarKey) return
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
   一条宽度过渡 + 各零件的形态过渡，统一用同一个时长和同一个缓动。
   关键是「同时开始、同时结束」—— 以前宽度 .32s、内容滑入 .3s 还延迟 .04s，
   两边节奏对不上，中途露白就是那个闪。
   width 而不是 transform：transform 不改变布局，展开时会盖住主区内容。 */
.rail {
  --rail-dur: .34s;
  --rail-ease: cubic-bezier(.32, .72, .28, 1);
  flex-shrink: 0;
  width: 62px;
  background: #fff;
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  /* 收起时左右各 12px：62 - 24 = 38，正好装下 38px 的头像和放大镜 */
  padding: 16px 12px;
  position: relative;
  transition: width var(--rail-dur) var(--rail-ease),
              padding var(--rail-dur) var(--rail-ease);
}
.rail.open { width: 268px; padding: 14px; }

/* ---------- 头部 ---------- */
.rail-head {
  display: flex; align-items: center;
  /* 收起态只有头像，靠 center 摆在 38px 的窄条正中；
     展开时靠左，姓名紧接其后。 */
  justify-content: center;
  position: relative;
  flex-shrink: 0;
  min-height: 42px;
}
.rail.open .rail-head { justify-content: flex-start; padding-bottom: 12px; }

.rail-avatar {
  position: relative; overflow: hidden; flex-shrink: 0;
  width: 38px; height: 38px; border-radius: 50%;
  display: grid; place-items: center;
  background: linear-gradient(135deg, #5b76f7, #7b5bf7);
  border: 0; padding: 0; cursor: pointer; color: #fff;
  transition: width var(--rail-dur) var(--rail-ease),
              height var(--rail-dur) var(--rail-ease),
              box-shadow .2s ease;
}
.rail.open .rail-avatar { width: 42px; height: 42px; box-shadow: 0 3px 12px rgba(91,118,247,.3); }
.rail-avatar > img { width: 100%; height: 100%; object-fit: cover; display: block; }
.avatar-letter { font-size: 15px; font-weight: 600; letter-spacing: .5px; }
.rail-avatar:hover { box-shadow: 0 5px 16px rgba(91,118,247,.38); }

/* 相机遮罩：只在展开态出现。收起态点头像是「打开抽屉」，
   显示相机图标会误导。 */
.avatar-cam {
  position: absolute; inset: 0;
  display: grid; place-items: center;
  background: rgba(20, 24, 40, .52);
  opacity: 0; visibility: hidden;
  transition: opacity .18s ease, visibility .18s;
  pointer-events: none;
}
.rail.open .avatar-cam { visibility: visible; }
.avatar-cam svg { width: 19px; height: 19px; fill: none; stroke: #fff;
                  stroke-width: 1.7; stroke-linejoin: round; }
.rail-avatar:hover .avatar-cam { opacity: 1; }
.rail-avatar.hasImg:hover .avatar-cam { opacity: .85; }

/* 上传中：头像压暗 + 转圈 */
.avatar-spin {
  position: absolute; inset: 0; border-radius: 50%;
  background: rgba(255, 255, 255, .55);
}
.avatar-spin::after {
  content: ''; position: absolute; left: 50%; top: 50%;
  width: 16px; height: 16px; margin: -8px 0 0 -8px;
  border: 2px solid rgba(91, 118, 247, .28);
  border-top-color: #5b76f7; border-radius: 50%;
  animation: avatar-spin .7s linear infinite;
}
@keyframes avatar-spin { to { transform: rotate(360deg); } }

.avatar-input { display: none; }

/* 姓名邮箱：从 0 宽铺开。不给 display:none ——
   否则展开时是「突然出现」，还是会闪。 */
.rail-who {
  flex: 0 0 0; width: 0; min-width: 0;
  overflow: hidden; opacity: 0; margin-left: 0;
  transition: opacity .2s ease,
              flex-basis var(--rail-dur) var(--rail-ease),
              margin-left var(--rail-dur) var(--rail-ease);
}
.rail.open .rail-who {
  flex: 1 1 auto; opacity: 1; margin-left: 10px;
  transition: opacity .22s ease .1s,
              flex-basis var(--rail-dur) var(--rail-ease),
              margin-left var(--rail-dur) var(--rail-ease);
}
.rail-name { font-size: 14.5px; font-weight: 600; white-space: nowrap;
             overflow: hidden; text-overflow: ellipsis; }
.rail-mail { font-size: 11.5px; color: var(--ink-3); white-space: nowrap;
             overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }

/* 收起按钮：绝对定位，不参与 flex 布局 ——
   否则收起态它宽 0 但 gap 还在，头像会被挤偏一点。
   展开后鼠标进头部才显示，平时不抢注意力。 */
.rail-close {
  position: absolute; right: 0; top: 50%; transform: translateY(-50%);
  width: 26px; height: 26px; border-radius: 7px;
  border: 1px solid transparent; background: transparent; color: var(--ink-3);
  display: grid; place-items: center; cursor: pointer;
  opacity: 0; pointer-events: none;
  transition: opacity .16s ease, background .16s, border-color .16s, color .16s;
}
.rail.open .rail-close { pointer-events: auto; }
.rail.open .rail-head:hover .rail-close { opacity: 1; }
.rail-close svg { width: 15px; height: 15px; fill: none; stroke: currentColor;
                  stroke-width: 2.1; stroke-linecap: round; stroke-linejoin: round; }
.rail-close:hover { background: #f2f4fb; border-color: var(--line); color: var(--ink); }

/* ---------- 中间留白 ---------- */
.rail-gap {
  flex: 1 1 auto; min-height: 0;
  display: grid; place-items: center;
  cursor: pointer; position: relative;
  transition: opacity .2s ease;
}
/* 展开时它缩到 0 高但不 display:none，父级的 flex 会把它让给 rail-body */
.rail.open .rail-gap { flex-grow: 0; opacity: 0; pointer-events: none; height: 0; }
.gap-tip {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  opacity: 0; transform: translateX(-4px);
  transition: opacity .22s ease, transform .22s ease;
  color: var(--ink-3); font-size: 11.5px;
}
.rail-gap:hover .gap-tip { opacity: 1; transform: none; }
.gap-tip em { font-style: normal; }
.gap-arrow { width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 2;
             stroke-linecap: round; stroke-linejoin: round; }

/* ---------- 展开内容 ----------
   max-height + opacity，不用 v-if：v-if 会让这块在宽度还在长的中途
   整块凭空出现，那就是「闪一下再弹出来」的来源。 */
.rail-body {
  max-height: 0; opacity: 0;
  overflow: hidden; flex-shrink: 0;
  transition: max-height var(--rail-dur) var(--rail-ease), opacity .18s ease;
}
.rail.open .rail-body {
  max-height: 560px; opacity: 1;
  transition: max-height var(--rail-dur) var(--rail-ease), opacity .22s ease .08s;
}
/* 收起时不可聚焦，避免 Tab 键跑进看不见的菜单 */
.rail:not(.open) .rail-body { pointer-events: none; visibility: hidden; }
.rail.open .rail-body { visibility: visible; }

.rail-quota { padding: 0 2px 12px; border-bottom: 1px solid var(--line); margin-bottom: 10px; }
.rail-quota-bar { height: 4px; border-radius: 3px; background: #eef0f7; overflow: hidden; }
.rail-quota-bar > div { height: 100%; border-radius: 3px;
                        background: linear-gradient(90deg, #5b76f7, #7b5bf7);
                        transition: width .3s ease; }
.rail-quota-num { font-size: 11.5px; color: var(--ink-3); margin-top: 6px; }

/* ===== 下载记录 / 收藏夹 =====
   默认折叠成一行标题：抽屉本体只有 268px，两个列表都摊开会把菜单挤掉。 */
.rail-sec { margin-bottom: 6px; }

.rail-sec-head {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 7px 9px; border: 0; border-radius: 8px; background: transparent;
  font: inherit; font-size: 13px; color: var(--ink-2); cursor: pointer;
  transition: background .15s, color .15s;
}
.rail-sec-head:hover { background: #f3f5fd; color: var(--ink); }
.sec-ic { width: 15px; height: 15px; flex-shrink: 0; fill: none; stroke: currentColor;
          stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.star-ic { fill: none; }
.sec-title { flex: 1; text-align: left; }
.sec-badge {
  font-size: 10.5px; font-weight: 600; min-width: 17px; height: 17px;
  padding: 0 5px; border-radius: 9px; background: var(--brand-soft);
  color: var(--brand-deep); display: grid; place-items: center;
  font-variant-numeric: tabular-nums;
}
.sec-caret {
  width: 13px; height: 13px; flex-shrink: 0; fill: none; stroke: currentColor;
  stroke-width: 2; stroke-linecap: round; transition: transform .18s ease;
}
.rail-sec.folded .sec-caret { transform: rotate(-90deg); }

/* 折叠：高度 0 + 不可见。用 max-height 过渡而不是 display:none，
   否则展开时是「啪」地跳出来，和抽屉那种慢慢拉开的感觉不一致。 */
.sec-list {
  overflow: hidden; max-height: 240px;
  transition: max-height .22s ease, opacity .18s ease;
  opacity: 1;
}
.rail-sec.folded .sec-list { max-height: 0; opacity: 0; }

.sec-row { display: flex; align-items: center; gap: 2px; }
.sec-row:hover { background: #f7f9ff; border-radius: 7px; }

.row-main {
  flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px;
  padding: 5px 8px; border: 0; border-radius: 7px; background: transparent;
  font: inherit; text-align: left; cursor: pointer; color: inherit;
}
.row-name {
  font-size: 12.5px; color: var(--ink);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.row-meta { font-size: 10.5px; color: var(--ink-3);
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.row-del {
  width: 18px; height: 18px; flex-shrink: 0; border: 0; border-radius: 5px;
  background: transparent; color: var(--ink-3); font-size: 14px; line-height: 1;
  cursor: pointer; opacity: 0; transition: opacity .15s, background .15s, color .15s;
}
.sec-row:hover .row-del { opacity: 1; }
.row-del:hover { background: #f0f2f8; color: var(--danger); }

.sec-empty {
  padding: 10px 9px 12px; font-size: 11.5px; color: var(--ink-3);
  text-align: center;
}
.sec-more {
  display: block; width: 100%; padding: 5px; margin-top: 2px;
  border: 0; background: transparent; border-radius: 7px;
  font: inherit; font-size: 11px; color: var(--ink-3); cursor: pointer;
}
.sec-more:hover { background: #f5f6fb; color: var(--danger); }

.rail-nav { display: flex; flex-direction: column; gap: 2px; }
.rail-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 9px; border: 0; background: transparent;
  font-size: 14px; color: var(--ink-2); text-align: left; width: 100%;
  cursor: pointer;
  transition: background .15s, color .15s;
}
.rail-item svg { width: 17px; height: 17px; flex-shrink: 0; fill: none;
                 stroke: currentColor; stroke-width: 1.7;
                 stroke-linecap: round; stroke-linejoin: round; }
.rail-item:hover { background: #f3f5fd; color: var(--ink); }
.rail-item.danger { color: var(--danger); }
.rail-item.danger:hover { background: #fff5f4; }
.rail-sep { height: 1px; background: var(--line); margin: 6px 2px; }

/* ---------- 底部：圆形放大镜 → 胶囊搜索框 ----------
   同一个元素改宽高/圆角/对齐，所以是「长大」而不是「换一个」。 */
.rail-search {
  margin-top: auto; flex-shrink: 0;
  display: flex; align-items: center;
  height: 38px; width: 38px; align-self: center;
  border: 1px solid var(--line); background: #f7f8fc;
  border-radius: 19px; padding: 0;
  justify-content: center; gap: 0;
  cursor: pointer; overflow: hidden;
  transition: width var(--rail-dur) var(--rail-ease),
              padding var(--rail-dur) var(--rail-ease),
              border-radius var(--rail-dur) var(--rail-ease),
              border-color .2s, background .2s, box-shadow .2s;
}
.rail-search:hover { border-color: #cfd6ea; background: #fff;
                     box-shadow: 0 4px 14px rgba(20,26,48,.12); }
.rail-search.is-open {
  width: 100%; align-self: stretch;
  border-radius: 999px; padding: 0 4px 0 11px;
  justify-content: flex-start; gap: 7px;
  cursor: text;
}
.rail-search.is-open:hover { box-shadow: none; }
.rail-search:focus-within {
  border-color: #b9c4f5; background: #fff;
  box-shadow: 0 0 0 3px rgba(91,118,247,.12);
}
.rs-ic { width: 18px; height: 18px; flex-shrink: 0; fill: none;
         stroke: var(--ink-2); stroke-width: 1.9; stroke-linecap: round; }
.rail-search.is-open .rs-ic { width: 17px; height: 17px; stroke: var(--ink-3); }

/* 输入框：收起时宽 0、透明、点不到也 tab 不到 */
.rail-search input {
  flex: 1 1 auto; min-width: 0; width: 0;
  border: 0; outline: 0; background: transparent;
  font-size: 14px; color: var(--ink); padding: 0;
  opacity: 0; pointer-events: none;
  transition: opacity .2s ease, width var(--rail-dur) var(--rail-ease);
}
.rail-search.is-open input {
  width: auto; opacity: 1; pointer-events: auto;
  transition: opacity .2s ease .1s, width var(--rail-dur) var(--rail-ease);
}
.rail-search input::placeholder { color: var(--ink-3); }
.rs-clear {
  width: 22px; height: 22px; border-radius: 50%; flex-shrink: 0;
  border: 0; background: #e6e9f3; color: var(--ink-2);
  font-size: 14px; line-height: 1; display: grid; place-items: center;
  cursor: pointer;
}
.rs-clear:hover { background: #d8dcec; }

.rail-toast {
  position: absolute; left: 50%; bottom: 14px; transform: translateX(-50%);
  background: rgba(22,25,42,.9); color: #fff; font-size: 12px;
  padding: 7px 13px; border-radius: 8px; white-space: nowrap; z-index: 5;
}
.rail-toast-enter-active, .rail-toast-leave-active { transition: opacity .18s ease; }
.rail-toast-enter-from, .rail-toast-leave-to { opacity: 0; }

/* 减少动效偏好：直接切，不做过渡 */
@media (prefers-reduced-motion: reduce) {
  .rail, .rail-head, .rail-avatar, .rail-who, .rail-gap, .rail-body, .rail-search,
  .rail-search input, .rail-close, .avatar-cam, .rail-body {
    transition: none !important;
  }
}
</style>
