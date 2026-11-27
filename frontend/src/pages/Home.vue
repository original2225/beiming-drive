<template>
  <div class="page home-page">

    <!-- 主区内容。外层的 shell/side/main 由 AppShell 提供，
         这里不要再套 <main>，否则会出现两层 main、padding 叠一次。 -->
    <div class="content" :class="{ 'intro-main': entering }">
      <header class="topbar">
        <div class="crumbs">
          <template v-if="view === 'files'">
            <span class="crumb" :class="{ cur: trail.length === 0 }" @click="goHome">我的文件</span>
            <template v-for="(c, i) in trail" :key="c.id">
              <span class="sep">/</span>
              <span class="crumb" :class="{ cur: i === trail.length - 1 }"
                    @click="jumpTo(i)">{{ c.name }}</span>
            </template>
          </template>
          <span v-else class="crumb cur">回收站</span>
        </div>

        <div class="top-actions">
                  <button class="icon-btn" title="刷新" @click="refresh">↻</button>

                  <!-- 新建：圆角方框 + 粗十字，点开才展开具体动作 -->
                  <div class="new-wrap">
                    <button class="new-fab" :class="{ open: newMenu }" title="新建"
                            @click.stop="newMenu = !newMenu">
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M12 5.2v13.6M5.2 12h13.6" />
                      </svg>
                    </button>
                    <transition name="new-pop">
                      <!-- 注意：这里不能写 @click.stop，它会截住子项的点击，菜单项永远收不到 click。
                         要挡住的是"点菜单内部别关菜单"，交给 closeMenu 里判断 .new-wrap 即可。 -->
                      <div v-if="newMenu" class="new-menu">
                        <div class="new-item" @click="newAction('upload-files')">
                          <i class="ni-ic">📄</i><span>上传文件</span>
                        </div>
                        <div class="new-item" @click="newAction('upload-folder')">
                          <i class="ni-ic">📁</i><span>上传文件夹</span>
                        </div>
                        <div class="new-sep"></div>
                        <div class="new-item" @click="newAction('mkdir')">
                          <i class="ni-ic">🗂</i><span>新建文件夹</span>
                        </div>
                        <div class="new-item" @click="newAction('readme')">
                          <i class="ni-ic">📝</i><span>新建 README</span>
                        </div>
                      </div>
                    </transition>
                  </div>
                </div>
              </header>

      <div class="bar-info">
        <span class="muted">
          <template v-if="searching">搜索「{{ searchKw }}」命中 {{ items.length }} 项</template>
          <template v-else-if="view === 'trash'">回收站 {{ items.length }} 项</template>
          <template v-else>{{ items.length }} 项</template>
        </span>
        <div class="right-side">
          <button v-if="searching" class="btn btn-sm" @click="clearSearch">返回目录</button>
        </div>
      </div>

      <div v-if="loading" class="empty-plain">加载中…</div>

      <div v-else-if="items.length === 0" class="empty">
        <div class="empty-icon">{{ view === 'trash' ? '🗑' : '📂' }}</div>
        <p v-if="view === 'trash'">回收站是空的</p>
        <p v-else-if="searching">没有匹配的文件</p>
        <p v-else>这里还空着，上传文件或新建文件夹</p>
      </div>

      <div v-else class="list" @contextmenu.self.prevent="openCtx(null, $event)">
        <div class="thead">
          <div class="th th-name-group" @click="setSort('name')">
            <span class="th-icon-inline"></span>
            <span>名称<i v-if="orderBy === 'name'">{{ dir === 'asc' ? '↑' : '↓' }}</i></span>
          </div>
          <div class="th th-path" v-if="searching">所在目录</div>
          <div class="th th-type" v-else>类型</div>
          <div class="th th-size" @click="setSort('size')">
            大小<i v-if="orderBy === 'size'">{{ dir === 'asc' ? '↑' : '↓' }}</i>
          </div>
          <div class="th th-time" @click="setSort('updatedAt')">
            修改时间<i v-if="orderBy === 'updatedAt'">{{ dir === 'asc' ? '↑' : '↓' }}</i>
          </div>
          <div class="th th-ops">操作</div>
        </div>
        <div v-for="it in items" :key="it.id" class="row" @click="onItemClick(it)"
             :class="{ 'ctx-target': ctxMenu && ctxMenu.node && ctxMenu.node.id === it.id }"
             @contextmenu.prevent.stop="openCtx(it, $event)">
          <div class="row-icon">{{ it.kind === 'dir' ? '📁' : fileIcon(it.name) }}</div>
          <div class="row-name" :title="it.name">{{ it.name }}</div>
          <div class="row-path muted" v-if="searching" :title="parentPathOf(it.path)">
            {{ parentPathOf(it.path) }}
          </div>
          <div class="row-type muted">{{ it.kind === 'dir' ? '文件夹' : extOf(it.name) }}</div>
          <div class="row-size muted">{{ it.kind === 'dir' ? '—' : sizeText(it.size) }}</div>
          <div class="row-time muted">{{ fmtDate(it.updatedAt) }}</div>
          <div class="row-ops" @click.stop>
            <template v-if="view === 'trash'">
              <button class="btn btn-sm" @click="restore(it)">恢复</button>
              <button class="btn btn-sm btn-danger" @click="purge(it)">彻底删除</button>
            </template>
            <template v-else>
              <button v-if="it.kind === 'file' && canPreview(it)" class="btn btn-sm" @click="preview(it)">预览</button>
              <button v-if="it.kind === 'file'" class="btn btn-sm" @click="download(it)">下载</button>
              <button class="btn btn-sm" @click="share(it)">分享</button>
              <div class="more-wrap">
                <button class="btn btn-sm icon-only" @click.stop="toggleMenu(it)">⋯</button>
                <div v-if="menuFor === it.id" class="menu" @click.stop>
                  <div v-if="it.kind === 'file' && canPreview(it)" class="menu-item"
                       @click="preview(it)">在线预览</div>
                  <div class="menu-item" @click="doRename(it)">重命名</div>
                  <div class="menu-item" @click="openMove(it)">移动到…</div>
                  <div class="menu-sep"></div>
                  <div class="menu-item danger" @click="remove(it)">移入回收站</div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <div v-if="uploads.length" class="uploads">
        <div class="up-title">上传队列 <span class="muted">{{ uploads.length }}</span></div>
        <div v-for="u in uploads" :key="u.key" class="up-row">
          <div class="up-name" :title="u.name">{{ u.name }}</div>
          <div class="up-bar"><div class="up-in" :class="{ err: u.pct < 0 }"
                                  :style="{ width: Math.max(u.pct, 0) + '%' }"></div></div>
          <div class="up-pct">{{ u.pct < 0 ? '失败' : u.pct + '%' }}</div>
        </div>
      </div>
    </div>

    <!-- ===== 右侧竖栏 =====
         折叠：顶部头像、底部放大镜，中间空白区提示「可以向左打开」
         展开：头像 / 姓名 / 邮箱 / 用户信息 / 设置 / 主题，最下面放大镜 + 胶囊搜索框 -->
    <aside class="rail" :class="{ open: railOpen }">
      <!-- 折叠态的血条：只有头像和放大镜 -->
      <transition name="rail-fade">
        <div v-if="!railOpen" class="rail-mini">
          <button class="mini-avatar" :title="me?.nickname || '账户'" @click="railOpen = true">
            <img v-if="avatarUrl" :src="avatarUrl" :alt="me?.nickname || '头像'" />
            <span v-else>{{ (me?.nickname || 'U').slice(0, 1) }}</span>
          </button>

          <!-- 中间空白：悬停提示可以向左打开 -->
          <div class="rail-gap" @click="railOpen = true">
            <span class="gap-tip">
              <svg viewBox="0 0 24 24" class="gap-arrow" aria-hidden="true">
                <path d="M15 6l-6 6 6 6" />
              </svg>
              <em>打开</em>
            </span>
          </div>

          <button class="mini-search" title="搜索" @click="railOpen = true; nextTick(() => railSearch && railSearch.focus())">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="6.2" />
              <path d="M15.2 15.2L20 20" />
            </svg>
          </button>
        </div>
      </transition>

      <!-- 展开态 -->
      <transition name="rail-slide">
        <div v-if="railOpen" class="rail-full">
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
            <button class="rail-close" title="收起" @click="railOpen = false">
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
                   @keyup.enter="doSearch" />
            <button v-if="keyword" class="rs-clear" @click="clearSearch">×</button>
          </div>
        </div>
      </transition>
    </aside>

    <input ref="fileInput" type="file" multiple hidden @change="onPick" />
    <input ref="folderInput" type="file" webkitdirectory directory multiple hidden @change="onPick" />

    <!-- 右键菜单：定位到鼠标处，超出视口自动翻转 -->
    <div v-if="ctxMenu" ref="ctxEl" class="ctx-menu" :style="ctxStyle" @click.stop>
      <!-- 右键点在某个条目上：ctxMenu.node 有值 -->
      <template v-if="ctxMenu.node">
        <template v-if="view === 'trash'">
          <div class="ctx-item" @click="ctxDo(restore)">恢复</div>
          <div class="ctx-sep"></div>
          <div class="ctx-item danger" @click="ctxDo(purge)">彻底删除</div>
        </template>
        <template v-else>
          <div v-if="ctxMenu.node.kind === 'dir'" class="ctx-item"
               @click="ctxDo(enterDir)">打开</div>
          <template v-else>
            <div v-if="canPreview(ctxMenu.node)" class="ctx-item" @click="ctxDo(preview)">在线预览</div>
            <div class="ctx-item" @click="ctxDo(download)">下载</div>
          </template>
          <div class="ctx-item" @click="ctxDo(share)">分享…</div>
          <div class="ctx-sep"></div>
          <div class="ctx-item" @click="ctxDo(doRename)">重命名</div>
          <div class="ctx-item" @click="ctxDo(openMove)">移动到…</div>
          <div class="ctx-sep"></div>
          <div class="ctx-item danger" @click="ctxDo(remove)">移入回收站</div>
        </template>
      </template>

      <!-- 空白处右键 -->
      <template v-else>
        <div class="ctx-item" @click="ctxDo(() => fileInput.click())">上传文件</div>
        <div class="ctx-item" @click="ctxDo(newFolder)">新建文件夹</div>
        <div class="ctx-sep"></div>
        <div class="ctx-item" @click="ctxDo(refresh)">刷新</div>
      </template>
      <div class="ctx-sep"></div>
      <div class="ctx-item" @click="ctxMenu = null">取消</div>
    </div>

    <!-- 轻提示：替代原生 alert（原生会阻塞页面） -->
    <transition name="toast">
      <div v-if="toast" class="toast" :class="toast.kind">{{ toast.text }}</div>
    </transition>

    <!-- 通用输入框（替代原生 prompt）：改名 / 新建文件夹共用 -->
    <div v-if="promptBox" class="mask" @click.self="closePrompt">
      <div class="modal modal-sm">
        <h3>{{ promptBox.title }}</h3>
        <p v-if="promptBox.hint" class="muted tip-line">{{ promptBox.hint }}</p>
        <input ref="promptInput" class="input" type="text" v-model="promptBox.value"
               :placeholder="promptBox.placeholder"
               @keydown.enter.prevent="doPrompt"
               @keydown.esc.prevent="closePrompt" />
        <p v-if="promptBox.err" class="err-line">{{ promptBox.err }}</p>
        <div class="modal-foot">
          <button class="btn" @click="closePrompt">取消</button>
          <button class="btn btn-primary" :disabled="promptBox.busy" @click="doPrompt">
            {{ promptBox.busy ? '处理中…' : promptBox.okText }}
          </button>
        </div>
      </div>
    </div>

    <!-- 通用确认框（替代原生 confirm） -->
    <div v-if="confirmBox" class="mask" @click.self="confirmBox = null">
      <div class="modal modal-sm">
        <h3>{{ confirmBox.title }}</h3>
        <p class="tip-line">{{ confirmBox.text }}</p>
        <div class="modal-foot">
          <button class="btn" @click="confirmBox = null">取消</button>
          <button class="btn" :class="confirmBox.danger ? 'btn-danger' : 'btn-primary'"
                  @click="runConfirm">{{ confirmBox.okText }}</button>
        </div>
      </div>
    </div>

    <div v-if="shareBox" class="mask" @click.self="shareBox = null">
      <div class="modal">
        <h3>分享「{{ shareBox.node.name }}」</h3>
        <label><span>提取码（留空则免密）</span>
          <input class="input" v-model="shareBox.password" placeholder="可选" />
        </label>
        <label><span>有效期（小时，留空永久）</span>
          <input class="input" v-model.number="shareBox.hours" type="number" placeholder="如 24" />
        </label>
        <div v-if="shareBox.result" class="share-out">
          <div class="link-row">
            <input class="input" :value="shareBox.result" readonly @focus="$event.target.select()" />
            <button class="btn btn-sm" @click="copy(shareBox.result)">复制</button>
          </div>
          <p class="muted tip">把这条链接发给对方就能下载</p>
        </div>
        <div class="modal-foot">
          <button class="btn" @click="shareBox = null">关闭</button>
          <button v-if="!shareBox.result" class="btn btn-primary" @click="doShare">创建分享</button>
        </div>
      </div>
    </div>

    <!-- 移动对话框 -->
    <div v-if="moveBox" class="mask" @click.self="moveBox = null">
      <div class="modal">
        <h3>移动「{{ moveBox.node.name }}」</h3>
        <p class="muted move-tip">选择目标目录</p>
        <div class="dir-picker">
          <div class="dp-crumb">
            <span class="dp-link" @click="dpGo(null)">我的文件</span>
            <template v-for="(c, i) in moveBox.trail" :key="c.id">
              <span class="dp-sep">/</span>
              <span class="dp-link" @click="dpJump(i)">{{ c.name }}</span>
            </template>
          </div>
          <div class="dp-list">
            <div v-if="moveBox.dirs.length === 0" class="dp-empty muted">这个目录下没有子文件夹</div>
            <div v-for="d in moveBox.dirs" :key="d.id" class="dp-item"
                 :class="{ disabled: moveBox.node.kind === 'dir' && d.path.startsWith(moveBox.node.path + '/') }"
                 @click="dpEnter(d)">
              <span class="dp-ic">📁</span>
              <span class="dp-nm">{{ d.name }}</span>
              <span class="dp-arrow" v-if="!moveBox.node.path.startsWith(d.path + '/')">›</span>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn" @click="moveBox = null">取消</button>
          <button class="btn btn-primary" @click="doMove">移动到这里</button>
        </div>
      </div>
    </div>

    <!-- 预览弹窗 -->
    <div v-if="previewBox" class="mask darker" @click.self="previewBox = null">
      <div class="preview-modal">
        <div class="pv-head">
          <div class="pv-name" :title="previewBox.node.name">{{ previewBox.node.name }}</div>
          <div class="pv-actions">
            <button class="btn btn-sm" @click="download(previewBox.node)">下载</button>
            <button class="btn btn-sm" @click="previewBox = null">关闭</button>
          </div>
        </div>
        <div class="pv-body">
          <img v-if="previewBox.kind === 'image'" :src="previewBox.url" :alt="previewBox.node.name" />
          <video v-else-if="previewBox.kind === 'video'" :src="previewBox.url" controls autoplay></video>
          <audio v-else-if="previewBox.kind === 'audio'" :src="previewBox.url" controls></audio>
          <iframe v-else-if="previewBox.kind === 'pdf'" :src="previewBox.url"></iframe>
          <pre v-else-if="previewBox.kind === 'text'">{{ previewBox.text }}</pre>
          <div v-else class="pv-unsupported muted">这种文件不支持在线预览，请下载后查看</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken, getToken, downloadUrl, previewUrl } from '../api.js'

const router = useRouter()
const CHUNK = 8 * 1024 * 1024

const me = ref(null)
const items = ref([])
const loading = ref(false)
const view = ref('files')
const currentId = ref(null)
const trail = ref([])
const uploads = ref([])
// 右侧竖栏是否展开（默认收起：顶部头像、底部放大镜）
const railOpen = ref(false)

/// 内容层的身份标识：变了才播过渡。
/// 「我的文件」内部切目录也算一次内容变化（列表整块换掉），
/// 所以把 currentId 拼进来；回收站单独一个 key。
const contentKey = computed(() => {
  if (view.value === 'trash') return 'trash'
  return 'files:' + (currentId.value ?? 'root')
})


// ===== 头像 =====
/// 头像地址带版本参数，换完立刻能看到新图（浏览器不会用旧缓存）
const avatarUrl = ref(null)
const avatarBusy = ref(false)
const avatarInput = ref(null)

function syncAvatarFromMe() {
  const key = me.value?.avatarKey
  if (!key) { avatarUrl.value = null; return }
  // 两个要点：
  //  1) v= 用 key（带时间戳），换图后 URL 变 → 浏览器不会拿旧缓存
  //  2) 必须带 access_token：<img src> 发不出 Authorization 头，
  //     不带 token 这个请求会被拦截器判 401，图直接裂。拦截器本来就认
  //     access_token 查询参数（文件预览/下载都靠它），这里沿用它。
  const t = encodeURIComponent(getToken())
  avatarUrl.value = `/api/v1/auth/avatar?v=${encodeURIComponent(key)}&access_token=${t}`
}

function pickAvatar() {
  if (avatarBusy.value) return
  avatarInput.value && avatarInput.value.click()
}

async function onAvatarPicked(e) {
  const file = e.target.files && e.target.files[0]
  // 选完立刻清空，否则同一个文件第二次选不会触发 change
  e.target.value = ''
  if (!file) return

  if (!/^image\/(png|jpeg|webp|gif)$/.test(file.type)) {
    showToast('只支持 PNG / JPEG / WebP / GIF 图片', 'err')
    return
  }
  if (file.size > 1024 * 1024) {
    showToast('头像不能超过 1 MB', 'err')
    return
  }

  const fd = new FormData()
  fd.append('file', file)
  avatarBusy.value = true
  try {
    const r = await api.uploadAvatar(fd)
    if (me.value) me.value.avatarKey = r.avatarKey
    // 走统一函数拼 URL，避免这里忘了带 access_token
    syncAvatarFromMe()
    showToast('头像已更新')
  } catch (err) {
    showToast(err.message || '头像上传失败', 'err')
  } finally {
    avatarBusy.value = false
  }
}

function askRemoveAvatar() {
  if (!avatarUrl.value || avatarBusy.value) return
  // askConfirm 是回调式：把动作塞进 run，确认后才执行
  askConfirm({
    title: '删除头像',
    text: '删除当前头像？删除后显示姓名首字。',
    okText: '删除头像',
    danger: true,
    run: async () => {
      avatarBusy.value = true
      try {
        await api.deleteAvatar()
        avatarUrl.value = null
        if (me.value) me.value.avatarKey = null
        showToast('头像已删除')
      } finally {
        avatarBusy.value = false
      }
    }
  })
}
// 新建菜单是否打开
const newMenu = ref(false)
const folderInput = ref(null)
const railSearch = ref(null)
const shareBox = ref(null)
const moveBox = ref(null)
const previewBox = ref(null)
const menuFor = ref(null)
// 右键菜单：ctxMenu = { node, x, y }，node 为空表示在空白处右键
const ctxMenu = ref(null)
const ctxEl = ref(null)
const ctxStyle = ref({})
// 首次进入主页时播一次入场动画；从其他页回来不重播（reduced-motion 时直接不播）
const entering = ref(false)
const SKIP_INTRO = (() => {
  try {
    return sessionStorage.getItem('drive_intro_played') === '1'
      || window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch { return false }
})()
// 通用输入框：{ title, hint, value, placeholder, okText, busy, err, submit }
const promptBox = ref(null)
const promptInput = ref(null)
// 通用确认框：{ title, text, okText, danger, run }
const confirmBox = ref(null)
// 轻提示（替代 alert）
const toast = ref(null)
let toastTimer = 0
const fileInput = ref(null)

const keyword = ref('')
const searchKw = ref('')
const searching = ref(false)
const orderBy = ref('name')
const dir = ref('asc')
const sorts = [
  { key: 'name', label: '名称' },
  { key: 'size', label: '大小' },
  { key: 'updatedAt', label: '时间' },
]

const pct = computed(() => {
  const q = me.value?.storageQuota || 0
  if (!q) return '0.0'
  return ((me.value.storageUsed / q) * 100).toFixed(1)
})
const barPct = computed(() => {
  const p = (me.value?.storageUsed / me.value?.storageQuota) * 100 || 0
  return p > 0 ? Math.max(p, 1.2) : 0
})
const usedText = computed(() => sizeText(me.value?.storageUsed || 0))
const quotaText = computed(() => sizeText(me.value?.storageQuota || 0))

function sizeText(b) {
  if (!b) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0, n = b
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`
}
function extOf(name) {
  const i = name.lastIndexOf('.')
  if (i < 0) return '文件'
  return name.slice(i + 1).toUpperCase()
}
function fileIcon(name) {
  const e = (name.split('.').pop() || '').toLowerCase()
  if (['md', 'txt'].includes(e)) return '📄'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(e)) return '🖼'
  if (['mp4', 'mkv', 'avi', 'mov'].includes(e)) return '🎬'
  if (['mp3', 'flac', 'wav', 'm4a'].includes(e)) return '🎵'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(e)) return '📦'
  if (e === 'pdf') return '📕'
  return '📄'
}
function parentPathOf(p) {
  const i = p.lastIndexOf('/')
  return i <= 0 ? '/' : p.slice(0, i)
}
function fmtDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  const diff = (Date.now() - d.getTime()) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)} 天前`
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function loadMe() {
  try {
    me.value = await api.me()
    syncAvatarFromMe()
  } catch (e) { /* 401 交给路由守卫 */ }
}

async function refresh() {
  if (view.value === 'trash') return openTrash()
  searching.value = false
  loading.value = true
  try {
    const d = await api.list(currentId.value, orderBy.value, dir.value)
    items.value = d.items || []
    await loadMe()
  } catch (e) {
    console.warn(e)
  } finally {
    loading.value = false
  }
}

async function openTrash() {
  view.value = 'trash'
  // 同步到地址栏：从别的页面点「回收站」是 router.push('/?view=trash')，
  // 反过来在这里也补上 query，两者保持一致，刷新后仍停在回收站。
  if (router.currentRoute.value.query.view !== 'trash') {
    router.replace({ path: '/', query: { view: 'trash' } })
  }
  searching.value = false
  menuFor.value = null
  loading.value = true
  try {
    items.value = await api.trashList()
    await loadMe()
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function goHome() {
  view.value = 'files'
  // 从回收站回文件列表时把 query 去掉，否则刷新会又跳回回收站
  if (router.currentRoute.value.query.view) {
    router.replace({ path: '/', query: {} })
  }
  currentId.value = null
  trail.value = []
  keyword.value = ''
  refresh()
}

function jumpTo(i) {
  const t = trail.value[i]
  trail.value = trail.value.slice(0, i + 1)
  currentId.value = t.id
  refresh()
}

function open(node) {
  view.value = 'files'
  currentId.value = node.id
  trail.value = [...trail.value, node]
  menuFor.value = null
  refresh()
}

function onItemClick(it) {
  if (menuFor.value) { menuFor.value = null; return }
  if (view.value === 'trash') return
  if (it.kind === 'dir') open(it)
}

// ---------- 右键菜单 ----------
async function openCtx(node, ev) {
  menuFor.value = null
  // 先记坐标，等 DOM 出来再量尺寸决定是否翻转到鼠标左上
  ctxMenu.value = { node, x: ev.clientX, y: ev.clientY }
  await nextTick()
  const el = ctxEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  let x = ev.clientX, y = ev.clientY
  // 贴边时翻到另一侧，否则菜单会出框看不见
  if (x + r.width > window.innerWidth - 8) x = Math.max(8, ev.clientX - r.width)
  if (y + r.height > window.innerHeight - 8) y = Math.max(8, ev.clientY - r.height)
  ctxStyle.value = { left: x + 'px', top: y + 'px' }
}

/** 点了菜单项：先关菜单，再执行动作。有 node 就传进去，没有就无参调用。 */
function ctxDo(fn) {
  const node = ctxMenu.value?.node
  ctxMenu.value = null
  if (node) fn(node)
  else fn()
}

function enterDir(it) { open(it) }

function closeCtx(e) {
  if (!ctxMenu.value) return
  // 点在菜单内部不关（交给 ctx-item 自己处理）
  if (e && e.target && e.target.closest && e.target.closest('.ctx-menu')) return
  ctxMenu.value = null
}

function setSort(key) {
  if (orderBy.value === key) dir.value = dir.value === 'asc' ? 'desc' : 'asc'
  else { orderBy.value = key; dir.value = 'asc' }
  refresh()
}

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) { refresh(); return }
  loading.value = true
  searching.value = true
  searchKw.value = q
  menuFor.value = null
  try {
    const d = await api.search(q)
    items.value = d.items || []
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function clearSearch() {
  keyword.value = ''
  searching.value = false
  refresh()
}

async function newFolder() {
  openPrompt({
    title: '新建文件夹',
    placeholder: '文件夹名称',
    okText: '创建',
    value: '',
    submit: async (name) => {
      await api.mkdir(currentId.value, name)
      await refresh()
    },
  })
}

/** 轻提示：2.6 秒后自动消失，不阻塞页面 */
function showToast(text, kind = 'info') {
  toast.value = { text, kind }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = null }, 2600)
}

// ---------- 通用输入框 / 确认框 ----------
function openPrompt(cfg) {
  promptBox.value = { hint: '', placeholder: '', okText: '确定', busy: false, err: '', ...cfg }
  nextTick(() => promptInput.value && promptInput.value.focus())
}

function closePrompt() {
  if (promptBox.value && promptBox.value.busy) return
  promptBox.value = null
}

async function doPrompt() {
  const b = promptBox.value
  if (!b || b.busy) return
  const v = (b.value || '').trim()
  // 名字类校验放在这里统一做，比每个调用点各写一遍靠谱
  if (!v) { b.err = '不能为空'; return }
  if (/[\\/:*?"<>|]/.test(v)) { b.err = '不能包含 / \\ : * ? " < > |'; return }
  if (v.length > 120) { b.err = '名字太长（最多 120 字）'; return }
  b.err = ''
  b.busy = true
  try {
    await b.submit(v)
    promptBox.value = null
  } catch (e) {
    b.err = e.message || '操作失败'
    b.busy = false
  }
}

/** 确认框：run 返回 Promise 就等它，抛错就提示 */
function askConfirm(cfg) { confirmBox.value = { okText: '确定', danger: false, ...cfg } }

async function runConfirm() {
  const c = confirmBox.value
  if (!c) return
  confirmBox.value = null
  try { await c.run() } catch (e) { showToast(e.message || '操作失败', 'err') }
}

function pickUpload() { fileInput.value?.click() }

// ---------- 新建菜单 ----------
function newAction(kind) {
  newMenu.value = false
  if (kind === 'upload-files') { pickUpload(); return }
  if (kind === 'upload-folder') { folderInput.value?.click(); return }
  if (kind === 'mkdir') { newFolder(); return }
  if (kind === 'readme') { createReadme(); return }
}

/** 新建 README：不新增后端接口，直接上传一个带初始内容的文本文件 */
function createReadme() {
  openPrompt({
    title: '新建 README',
    hint: '会创建一个 Markdown 文件，可以直接在里面写',
    value: 'README.md',
    okText: '创建',
    submit: async (name) => {
      if (!/\.(md|markdown)$/i.test(name)) name += '.md'
      const body = `# ${name.replace(/\.(md|markdown)$/i, '')}\n\n`
      const file = new File([body], name, { type: 'text/markdown' })
      await uploadOne(file)
      newMenu.value = false
    },
  })
}

// ---------- 右侧竖栏菜单 ----------
function railMenuAction(kind) {
  if (kind === 'profile') { showToast('个人主页还在做，先把用户信息放在这里'); railOpen.value = false; return }
  if (kind === 'settings') { showToast('设置页还没做'); return }
  if (kind === 'theme') { showToast('主题切换还没做'); return }
  if (kind === 'switch') {
    askConfirm({
      title: '切换账号',
      text: '切换会退出当前账号，回到登录页重新登录。确定吗？',
      okText: '去登录页',
      run: async () => { await logout() },
    })
    return
  }
  if (kind === 'logout') {
    askConfirm({
      title: '退出登录',
      text: '确定退出当前账号？',
      okText: '退出',
      danger: true,
      run: async () => { await logout() },
    })
  }
}

async function onPick(ev) {
  const files = [...ev.target.files]
  ev.target.value = ''
  for (const f of files) {
    // 选文件夹时浏览器会给出 webkitRelativePath（如 "相册/2024/a.jpg"）。
    // 只靠文件名会把所有文件平铺在根目录，所以先按路径逐级建目录。
    const rel = f.webkitRelativePath || ''
    if (rel) await uploadWithDir(f, rel)
    else await uploadOne(f)
  }
}

/** 按相对路径把目录建出来，再往最深一级上传 */
async function uploadWithDir(file, rel) {
  const parts = rel.split('/').filter(Boolean)
  const dirs = parts.slice(0, -1)
  let parent = currentId.value
  for (const d of dirs) {
    parent = await ensureDir(parent, d)
    if (parent == null) return
  }
  await uploadOne(file, parent)
}

/** 目录已存在就复用，不存在就建。返回目录 id */
async function ensureDir(parentId, name) {
  try {
    const list = await api.list(parentId, 'name', 'asc')
    const items = list.items || []
    const hit = items.find(x => x.kind === 'dir' && x.name === name)
    if (hit) return hit.id
  } catch (e) { /* 列失败就继续往下建 */ }
  try {
    const r = await api.mkdir(parentId, name)
    return r && r.id != null ? r.id : null
  } catch (e) {
    showToast(`建目录「${name}」失败：${e.message}`, 'err')
    return null
  }
}

async function uploadOne(file, parent = currentId.value) {
  const key = file.name + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6)
  const rec = { key, name: file.name, pct: 0 }
  uploads.value.push(rec)
  try {
    if (file.size <= CHUNK) {
      await api.uploadSmall(parent, file)
      rec.pct = 100
    } else {
      const total = Math.ceil(file.size / CHUNK)
      const sess = await api.createUpload(parent, file.name, file.size, CHUNK)
      let finished = 0, next = 0
      const CONC = 3
      async function worker() {
        while (true) {
          const idx = next++
          if (idx >= total) return
          const start = idx * CHUNK
          const blob = file.slice(start, Math.min(start + CHUNK, file.size))
          await api.putChunk(sess.id, idx, blob)
          finished++
          rec.pct = Math.round((finished / total) * 100)
        }
      }
      await Promise.all(Array.from({ length: Math.min(CONC, total) }, worker))
      await api.completeUpload(sess.id)
      rec.pct = 100
    }
    if (view.value === 'files') await refresh()
  } catch (e) {
    rec.pct = -1
  } finally {
    setTimeout(() => { uploads.value = uploads.value.filter(u => u.key !== key) }, 1600)
  }
}

function download(it) { window.open(downloadUrl(it.id), '_blank') }

function toggleMenu(it) { menuFor.value = menuFor.value === it.id ? null : it.id }

async function doRename(it) {
  menuFor.value = null
  ctxMenu.value = null
  openPrompt({
    title: '重命名',
    hint: `原名称：${it.name}`,
    value: it.name,
    okText: '保存',
    submit: async (name) => {
      if (name === it.name) return
      await api.rename(it.id, name)
      await refresh()
    },
  })
}

async function remove(it) {
  menuFor.value = null
  ctxMenu.value = null
  askConfirm({
    title: '移入回收站',
    text: `把「${it.name}」移入回收站？之后可以在回收站里恢复。`,
    okText: '移入回收站',
    danger: true,
    run: async () => {
      await api.trash(it.id)
      await refresh()
    },
  })
}

// ---------- 移动 ----------
async function openMove(it) {
  menuFor.value = null
  // 从根开始选目标，避免一进来就困在当前目录里
  moveBox.value = { node: it, trail: [], dirs: [], targetId: null }
  await loadDirs(null)
}

async function loadDirs(parentId) {
  try {
    const d = await api.list(parentId, 'name', 'asc')
    moveBox.value.dirs = (d.items || []).filter(x => x.kind === 'dir')
    moveBox.value.targetId = parentId
  } catch (e) {
    moveBox.value.dirs = []
  }
}

async function dpEnter(d) {
  // 不许走进自身的子树，否则移动会成环
  const n = moveBox.value.node
  if (n.kind === 'dir' && d.path.startsWith(n.path + '/')) return
  moveBox.value.trail = [...moveBox.value.trail, d]
  await loadDirs(d.id)
}

async function dpGo() {
  moveBox.value.trail = []
  await loadDirs(null)
}

function dpJump(i) {
  const t = moveBox.value.trail[i]
  moveBox.value.trail = moveBox.value.trail.slice(0, i + 1)
  loadDirs(t.id)
}

async function doMove() {
  const box = moveBox.value
  try {
    await api.move(box.node.id, box.targetId)
    moveBox.value = null
    await refresh()
  } catch (e) { showToast(e.message, 'err') }
}

// ---------- 预览 ----------
function previewKind(name, mime) {
  const e = (name.split('.').pop() || '').toLowerCase()
  const m = (mime || '').toLowerCase()
  if (m.startsWith('image/') || ['png','jpg','jpeg','gif','webp','bmp'].includes(e)) return 'image'
  if (m.startsWith('video/') || ['mp4','webm','mov','mkv'].includes(e)) return 'video'
  if (m.startsWith('audio/') || ['mp3','wav','flac','m4a','ogg'].includes(e)) return 'audio'
  if (m === 'application/pdf' || e === 'pdf') return 'pdf'
  if (m.startsWith('text/') || ['md','txt','json','log','csv','py','js','css'].includes(e)) return 'text'
  return null
}

function canPreview(it) { return it.kind === 'file' && previewKind(it.name, it.mime) != null }

async function preview(it) {
  menuFor.value = null
  const kind = previewKind(it.name, it.mime)
  if (!kind) { showToast('这种文件不支持在线预览', 'err'); return }
  const url = previewUrl(it.id)

  if (kind === 'text') {
    // 文本自己 read 回来渲染，别丢进 iframe 显示原始字节
    try {
      const r = await fetch(url)
      if (!r.ok) throw new Error('HTTP ' + r.status)
      const text = await r.text()
      const MAX = 400 * 1024  // 超大文本截断，防止拖死浏览器
      previewBox.value = { node: it, kind, url,
        text: text.length > MAX
          ? text.slice(0, MAX) + `\n\n…（已截断，全文 ${text.length} 字符，请下载查看）`
          : text }
    } catch (e) { showToast('读取失败：' + e.message, 'err') }
    return
  }
  previewBox.value = { node: it, kind, url }
}

async function restore(it) {
  ctxMenu.value = null
  try { await api.restore(it.id); await openTrash(); showToast('已恢复') }
  catch (e) { showToast(e.message, 'err') }
}

function purge(it) {
  ctxMenu.value = null
  askConfirm({
    title: '彻底删除',
    text: `「${it.name}」将被永久删除，无法恢复。确定吗？`,
    okText: '彻底删除',
    danger: true,
    run: async () => {
      await api.purge(it.id)
      await openTrash()
    },
  })
}

function share(it) {
  shareBox.value = { node: it, password: '', hours: null, result: '' }
}
async function doShare() {
  const s = shareBox.value
  try {
    const r = await api.createShare(s.node.id, s.password || null, s.hours || null, null)
    s.result = `${location.origin}/s/${r.share.id}`
  } catch (e) { showToast(e.message, 'err') }
}
function copy(txt) { navigator.clipboard.writeText(txt).then(() => showToast('已复制')) }

async function logout() {
  try { await api.logout() } catch (e) {}
  setToken('')
  router.push('/login')
}

function closeMenu(e) {
  if (!e.target.closest || !e.target.closest('.more-wrap')) menuFor.value = null
  // 新建菜单：点菜单外关掉（button 自己用 .stop 处理开关）
  if (newMenu.value && (!e.target.closest || !e.target.closest('.new-wrap'))) newMenu.value = false
}

/** Esc 逐层关闭：右键菜单 → ⋯ 菜单 → 预览 → 移动框 */
function onKey(e) {
  if (e.key !== 'Escape') return
  if (promptBox.value) { closePrompt(); return }
  if (confirmBox.value) { confirmBox.value = null; return }
  if (ctxMenu.value) { ctxMenu.value = null; return }
  if (menuFor.value) { menuFor.value = null; return }
  if (previewBox.value) { previewBox.value = null; return }
  if (moveBox.value) { moveBox.value = null }
}

onMounted(() => {
  // 带 ?view=trash 进来（比如在「我的分享」页点了回收站）就直接进回收站。
  // 注意要用 openTrash 而不是 refresh，两者取的数据源不同。
  const wantTrash = router.currentRoute.value.query.view === 'trash'
  if (wantTrash) {
    openTrash()
  } else {
    refresh()
  }

  // 入场：整页从薄雾里显形，侧栏与主区分层错开
  if (!SKIP_INTRO) {
    entering.value = true
    // 只播一次：回到本页（切目录、进出子页）不再重来
    try { sessionStorage.setItem('drive_intro_played', '1') } catch { /* 隐私模式忽略 */ }
    // 必须晚于最长的那条动画（.main 1.06s），否则 class 被摘掉、动画半路断掉
    setTimeout(() => { entering.value = false }, 1200)
  }

  document.addEventListener('click', closeMenu)
  document.addEventListener('click', closeCtx)
  // 右键用 capture 阶段：先关掉旧菜单，再让目标自己的 handler 打开新菜单。
  // 用冒泡会被自己关掉（新菜单刚设上就被这个监听清空）——踩过。
  document.addEventListener('contextmenu', closeCtx, true)
  document.addEventListener('scroll', closeCtx, true)
  document.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  document.removeEventListener('click', closeCtx)
  document.removeEventListener('contextmenu', closeCtx, true)
  document.removeEventListener('scroll', closeCtx, true)
  document.removeEventListener('keydown', onKey)
})
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

/* ---------- 顶栏「新建」按钮：圆角方框 + 粗十字 ---------- */
.new-wrap { position: relative; }
.new-fab {
  width: 34px; height: 34px; border-radius: 10px;
  border: 1px solid var(--line); background: #fff;
  display: grid; place-items: center; padding: 0;
  transition: background .18s, border-color .18s, box-shadow .18s, transform .18s;
}
.new-fab svg {
  width: 17px; height: 17px; fill: none; stroke: var(--ink);
  stroke-width: 2.6; stroke-linecap: round;
  transition: transform .22s cubic-bezier(.3, .6, .35, 1);
}
.new-fab:hover { border-color: #cfd6ea; background: #f7f8fc; box-shadow: 0 2px 8px rgba(20,26,48,.08); }
.new-fab.open { background: #f0f3ff; border-color: #b9c4f5; }
.new-fab.open svg { transform: rotate(45deg); }

.new-menu {
  position: absolute; right: 0; top: 42px; z-index: 60;
  min-width: 176px; padding: 5px;
  background: #fff; border: 1px solid var(--line); border-radius: 11px;
  box-shadow: 0 14px 34px rgba(20, 26, 48, .16), 0 2px 6px rgba(20, 26, 48, .06);
}
.new-item {
  display: flex; align-items: center; gap: 9px;
  padding: 8px 10px; border-radius: 8px; font-size: 14px; cursor: pointer;
  transition: background .14s;
}
.new-item:hover { background: #f3f5fd; }
.ni-ic { font-style: normal; font-size: 14px; width: 16px; text-align: center; }
.new-sep { height: 1px; background: var(--line); margin: 4px 2px; }
.new-pop-enter-active { transition: opacity .16s ease, transform .18s cubic-bezier(.3, .6, .35, 1); }
.new-pop-leave-active { transition: opacity .1s ease, transform .1s ease; }
.new-pop-enter-from, .new-pop-leave-to { opacity: 0; transform: translateY(-6px) scale(.97); }

/* ---------- 进入主页的显化动画 ----------
   显形要慢到能看见：整页从浓雾里一层层浮出来，约 1.2 秒。
   起点 0.15、模糊 14px —— 一开始只是隐约轮廓，然后逐步收紧、减雾、落定。

   曲线教训：cubic-bezier(.16,.72,...) 这类「前段极快」的曲线，会让进度在
   中段就冲到顶然后停住 —— 实测 opacity 卡在 0.46 不动了 260ms，看着像卡顿。
   改成分段均匀的关键帧 + 温和曲线，全程都在变化。 */
@keyframes shell-rise {
  0%   { opacity: .15; filter: blur(14px)  saturate(.75); transform: scale(1.022); }
  20%  { opacity: .34; filter: blur(10.5px) saturate(.82); transform: scale(1.017); }
  40%  { opacity: .55; filter: blur(7px)   saturate(.88); transform: scale(1.012); }
  60%  { opacity: .74; filter: blur(4.2px) saturate(.93); transform: scale(1.007); }
  80%  { opacity: .90; filter: blur(1.9px) saturate(.97); transform: scale(1.003); }
  100% { opacity: 1;   filter: blur(0)     saturate(1);    transform: scale(1); }
}
/* 侧栏与主区各自错开一点，让显形有「层层展开」的层次，不是一块雾整体散开 */
@keyframes side-rise {
  0%   { opacity: 0;    transform: translateX(-16px); }
  100% { opacity: 1;    transform: none; }
}
.content.intro-main { animation: main-rise .92s cubic-bezier(.3, .55, .4, .95) .16s both; }
@keyframes main-rise {
  0%   { opacity: 0;   transform: translateY(14px); }
  100% { opacity: 1;   transform: none; }
}

@media (prefers-reduced-motion: reduce) {
}


.plus { font-size: 17px; line-height: 1; margin-top: -1px; }


.bar { height: 5px; background: #e3e6f0; border-radius: 4px; overflow: hidden; }
.bar-in { height: 100%; border-radius: 4px;
          background: linear-gradient(90deg, var(--brand), #8b9bff); transition: width .35s; }

.avatar {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  background: linear-gradient(135deg, var(--brand), #8b5cf6);
  color: #fff; display: grid; place-items: center; font-weight: 600; font-size: 14px;
}
.who { flex: 1; min-width: 0; }
.name { font-size: 14px; font-weight: 600; }
.mail { font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.icon-btn {
  width: 30px; height: 30px; border-radius: 8px; border: 1px solid var(--line);
  background: #fff; color: var(--ink-3); font-size: 15px; display: grid; place-items: center;
}
.icon-btn:hover { border-color: #cfd4e4; color: var(--ink); background: #fafbff; }

.home-page {
  /* 这一层是「主区里的横向容器」：左边内容、右边竖栏。
     AppShell 给的 .main 是纵向 flex，所以这里必须自己声明横向，
     否则竖栏会被挤到内容下面变成左右两段。 */
  flex: 1;
  display: flex;
  flex-direction: row;
  min-height: 0;
  overflow: hidden;
  padding: 0;            /* 覆盖通用 .page 的 padding */
}
.content { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-height: 0; }

.topbar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 15px 34px; background: #fff; border-bottom: 1px solid var(--line);
}
.crumbs { display: flex; align-items: center; gap: 7px; font-size: 14.5px;
          min-width: 0; overflow: hidden; }
.crumb { cursor: pointer; color: var(--ink-3); white-space: nowrap;
         overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.crumb:hover { color: var(--brand); }
.crumb.cur { color: var(--ink); font-weight: 600; cursor: default; }
.sep { color: #ced3e2; }

.top-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.search-box {
  display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 10px;
  background: #f5f6fb; border: 1px solid transparent; border-radius: 9px;
  width: 210px; transition: .16s;
}
.search-box:focus-within { background: #fff; border-color: var(--brand);
                           box-shadow: 0 0 0 3px rgba(91,108,255,.10); }
.search-ic { color: var(--ink-3); font-size: 15px; }
.search-box input { flex: 1; border: 0; background: transparent; outline: none;
                    font-size: 14px; color: var(--ink); min-width: 0; }
.search-box input::placeholder { color: var(--ink-3); }
.clear { border: 0; background: transparent; color: var(--ink-3); font-size: 15px;
         line-height: 1; padding: 0 2px; }

.bar-info {
  display: flex; align-items: center; justify-content: space-between;
  padding: 11px 34px 8px; font-size: 14px; gap: 12px; flex-shrink: 0;
}
.right-side { display: flex; align-items: center; gap: 10px; }
.sort-group { display: flex; align-items: center; gap: 4px; }
.sort-btn {
  border: 0; background: transparent; color: var(--ink-3); font-size: 14px;
  padding: 4px 9px; border-radius: 7px; transition: .15s;
}
.sort-btn:hover { background: #eef0f8; color: var(--ink); }
.sort-btn.on { background: var(--brand-soft); color: var(--brand-deep); font-weight: 600; }
.sort-btn i { font-style: normal; margin-left: 3px; font-size: 11.5px; }

.list { flex: 1; overflow-y: auto; padding: 0 34px 30px; }

/* 表头：列宽必须与 .row 完全一致，否则文字会跟数据错位 */
.thead {
  display: flex; align-items: center; gap: 12px; padding: 0 14px 7px;
  font-size: 12.5px; color: var(--ink-3); position: sticky; top: 0;
  background: var(--bg); z-index: 5;
}
.th { white-space: nowrap; user-select: none; }
/* 名称表头横跨「图标 + 文件名」两列，这样文字起点和文件名左缘对齐 */
.th-name-group { flex: 1 1 0; min-width: 144px; cursor: pointer; display: flex;
                 align-items: center; gap: 12px; }
.th-icon-inline { width: 24px; flex-shrink: 0; }
.th-path { flex: 1 1 0; min-width: 60px; }
.th-type { flex: 0 0 56px; text-align: right; }
.th-size { flex: 0 0 70px; text-align: right; cursor: pointer; }
.th-time { flex: 0 0 78px; text-align: right; cursor: pointer; }
.th i, .sort-btn i { font-style: normal; margin-left: 3px; font-size: 11.5px; }
.th-name:hover, .th-size:hover, .th-time:hover { color: var(--ink); }
.th-ops { flex: 0 0 132px; text-align: right; }
@media (max-width: 1100px) { .th-time, .row-time { display: none; } }
@media (max-width: 900px) { .th-type, .row-type { display: none; } }
.row {
  display: flex; align-items: center; gap: 12px; padding: 10px 14px;
  background: #fff; border: 1px solid #eef0f7; border-radius: 10px;
  margin-bottom: 7px; transition: .14s; cursor: pointer; font-size: 14px;
}
.row:hover { border-color: #d8dcef; box-shadow: 0 2px 10px rgba(22,25,42,.05); }
.row { position: relative; }
.row-icon { font-size: 19px; line-height: 1; width: 24px; text-align: center; flex-shrink: 0; }
/* 名称是主信息，必须优先拿宽度：flex-basis 0 + 高 grow + min-width 下限，
   否则窄窗口下会被固定列挤成 0（实测 766px 窗口时宽度真的是 0）。 */
.row-name { flex: 1 1 0; min-width: 120px; font-weight: 500; overflow: hidden;
            text-overflow: ellipsis; white-space: nowrap; }
.row-path { flex: 1 1 0; min-width: 60px; font-size: 12.5px; overflow: hidden;
            text-overflow: ellipsis; white-space: nowrap; }
.row-type { flex: 0 0 56px; font-size: 12.5px; text-align: right; }
.row-size { flex: 0 0 70px; font-size: 12.5px; text-align: right; }
.row-time { flex: 0 0 78px; font-size: 12.5px; text-align: right; }
/* 操作区不参与 flex 分配（绝对定位在右侧），改由 hover 覆盖，
   这样窄窗口下也不会把名称挤没。 */
/* 操作列固定占位，避免和大小列视觉粘连；hover 才完全显形 */
.row-ops { flex: 0 0 132px; display: flex; gap: 4px; justify-content: flex-end;
           opacity: .45; transition: opacity .14s; flex-shrink: 0; }
.row:hover .row-ops { opacity: 1; }
/* 窗口窄时隐藏次要列，保住名称 */
@media (max-width: 1100px) {
  .row-time { display: none; }
}
@media (max-width: 900px) {
  .row-type { display: none; }
}
.row:hover .row-ops { opacity: 1; }
.icon-only { padding: 0; width: 30px; justify-content: center; }

.more-wrap { position: relative; }
.menu {
  position: absolute; right: 0; top: 34px; min-width: 130px; z-index: 40;
  background: #fff; border: 1px solid var(--line); border-radius: 10px;
  box-shadow: 0 8px 26px rgba(22,25,42,.13); padding: 5px;
}
.menu-item { padding: 8px 11px; font-size: 14px; border-radius: 7px; cursor: pointer; }
.menu-item:hover { background: #f5f6fb; }
.menu-item.danger { color: var(--danger); }
.menu-item.danger:hover { background: #fff5f4; }
.menu-sep { height: 1px; background: var(--line); margin: 4px 0; }

/* ---------- 右键菜单 ---------- */
.ctx-menu {
  position: fixed; z-index: 900; min-width: 168px;
  background: #fff; border: 1px solid var(--line); border-radius: 10px;
  box-shadow: 0 12px 32px rgba(20, 26, 48, .16), 0 2px 6px rgba(20, 26, 48, .06);
  padding: 5px; font-size: 14px; user-select: none;
  animation: ctx-in .09s ease-out;
}
@keyframes ctx-in {
  from { opacity: 0; transform: scale(.96) translateY(-2px); }
  to   { opacity: 1; transform: none; }
}
.ctx-item {
  padding: 7px 11px; border-radius: 7px; cursor: pointer;
  display: flex; align-items: center; gap: 6px;
}
.ctx-item:hover { background: #f2f4fb; }
.ctx-item.danger { color: var(--danger); }
.ctx-item.danger:hover { background: #fff5f4; }
.ctx-item.off { opacity: .4; cursor: default; }
.ctx-item.off:hover { background: transparent; }
.ctx-sep { height: 1px; background: var(--line); margin: 4px 2px; }

/* 右键选中的那一行给个底色，让人知道菜单作用在谁身上 */
.row.ctx-target { background: #f0f3ff; }

/* ---------- 移动对话框 ---------- */
.mask.darker { background: rgba(10, 14, 24, .72); }
.move-tip { font-size: 14px; margin: -6px 0 10px; }
.dir-picker { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.dp-crumb {
  display: flex; align-items: center; flex-wrap: wrap; gap: 2px;
  padding: 9px 12px; font-size: 14px; border-bottom: 1px solid var(--line);
  background: #fafbfd;
}
.dp-link { color: var(--accent); cursor: pointer; }
.dp-link:hover { text-decoration: underline; }
.dp-sep { color: var(--ink-3); }
.dp-list { max-height: 264px; overflow-y: auto; padding: 6px; }
.dp-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border-radius: 8px; cursor: pointer; font-size: 14.5px;
}
.dp-item:hover { background: #f5f6fb; }
.dp-item.disabled { opacity: .35; cursor: not-allowed; }
.dp-item.disabled:hover { background: transparent; }
.dp-ic { font-size: 15px; }
.dp-nm { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dp-arrow { color: var(--ink-3); font-size: 15px; }
.dp-empty { padding: 18px 10px; text-align: center; font-size: 14px; }

/* ---------- 预览弹窗 ---------- */
.preview-modal {
  width: min(1040px, 94vw); max-height: 90vh; display: flex; flex-direction: column;
  background: #fff; border-radius: 14px; overflow: hidden;
  box-shadow: 0 24px 70px rgba(0, 0, 0, .45);
}
.pv-head {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
  border-bottom: 1px solid var(--line); flex-shrink: 0;
}
.pv-name { flex: 1; font-size: 14.5px; font-weight: 500;
           overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pv-actions { display: flex; gap: 6px; flex-shrink: 0; }
.pv-body {
  flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center;
  background: #f7f8fb; overflow: auto; padding: 16px;
}
.pv-body img { max-width: 100%; max-height: 76vh; object-fit: contain; border-radius: 6px; }
.pv-body video { max-width: 100%; max-height: 76vh; border-radius: 6px; }
.pv-body audio { width: 100%; max-width: 560px; }
.pv-body iframe { width: 100%; height: 76vh; border: 0; border-radius: 6px; background: #fff; }
.pv-body pre {
  width: 100%; align-self: flex-start; margin: 0; padding: 4px;
  font-size: 14px; line-height: 1.62; white-space: pre-wrap; word-break: break-word;
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace; color: var(--ink);
}
.pv-unsupported { padding: 40px; font-size: 14px; }

.empty, .empty-plain { flex: 1; display: grid; place-content: center;
                        text-align: center; color: var(--ink-3); font-size: 14px; gap: 8px; }
.empty-icon { font-size: 40px; opacity: .45; }

.uploads {
  position: fixed; right: 22px; bottom: 22px; width: 310px; z-index: 60;
  background: #fff; border: 1px solid var(--line); border-radius: 12px;
  box-shadow: 0 10px 34px rgba(22,25,42,.14); padding: 13px 15px;
}
.up-title { font-size: 14px; font-weight: 600; margin-bottom: 9px; color: var(--ink-2);
            display: flex; justify-content: space-between; }
.up-row { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; font-size: 12.5px; }
.up-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.up-bar { width: 84px; height: 4px; background: #e6e8f0; border-radius: 3px;
          overflow: hidden; flex-shrink: 0; }
.up-in { height: 100%; background: var(--brand); transition: width .2s; }
.up-in.err { background: var(--danger); }
.up-pct { width: 32px; text-align: right; color: var(--ink-3); flex-shrink: 0; }

.mask { position: fixed; inset: 0; background: rgba(16,18,32,.38);
        display: grid; place-items: center; z-index: 100; backdrop-filter: blur(2px); }
.modal { background: #fff; border-radius: 14px; width: 400px; padding: 24px;
         box-shadow: 0 20px 60px rgba(22,25,42,.22); }
.modal h3 { margin: 0 0 18px; font-size: 15.5px; }
.modal label { display: block; margin-bottom: 13px; }
.modal label span { display: block; font-size: 13px; color: var(--ink-2); margin-bottom: 6px; }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.modal-sm { width: 340px; }
.modal-sm h3 { margin-bottom: 10px; }
.tip-line { font-size: 14px; color: var(--ink-2); margin: 0 0 12px; line-height: 1.6; }
.err-line { font-size: 13px; color: var(--danger); margin: 8px 0 0; }

/* 轻提示 */
.toast {
  position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%);
  z-index: 2000; padding: 10px 18px; border-radius: 9px; font-size: 14px;
  background: #2b3144; color: #fff; box-shadow: 0 10px 28px rgba(16, 20, 40, .26);
  max-width: 420px; text-align: center; pointer-events: none;
}
.toast.err { background: #c0392b; }
.toast.ok { background: #1f8a55; }
.toast-enter-active, .toast-leave-active { transition: opacity .18s, transform .18s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(8px); }
.share-out { margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--line); }
.link-row { display: flex; gap: 8px; }
.tip { font-size: 12.5px; margin: 8px 0 0; }
</style>