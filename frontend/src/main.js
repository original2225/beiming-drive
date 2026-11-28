import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import './styles/page-layout.css'
import App from './App.vue'
import Login from './pages/Login.vue'
import Home from './pages/Home.vue'
import Shares from './pages/Shares.vue'
import Downloader from './pages/Downloader.vue'
import Storage from './pages/Storage.vue'
import Profile from './pages/Profile.vue'
import Settings from './pages/Settings.vue'
import AdminUsers from './pages/AdminUsers.vue'
import ShareView from './pages/ShareView.vue'
import { getToken } from './api.js'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
    { path: '/forgot', component: () => import('./pages/ForgotPassword.vue'), meta: { public: true } },
    { path: '/public', component: () => import('./pages/PublicHome.vue'), meta: { public: true } },
    { path: '/', component: Home },
    { path: '/shares', component: Shares },
    { path: '/downloader', component: Downloader },
    { path: '/storage', component: Storage },
    { path: '/profile', component: Profile },
    { path: '/settings', component: Settings },
    { path: '/admin/users', component: AdminUsers },
    { path: '/s/:id', component: ShareView, meta: { public: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  if (to.path === '/login' && getToken()) return '/'
  // 游客（无 token）访问首页 → 引导到公开内容页（不弹登录）。
  // 其它非公开页面仍然要求登录。
  if (!to.meta.public && !getToken()) {
    return to.path === '/' ? '/public' : '/login'
  }
  return true
})

createApp(App).use(router).mount('#app')