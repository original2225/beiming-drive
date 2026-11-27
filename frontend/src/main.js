import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Login from './pages/Login.vue'
import Home from './pages/Home.vue'
import Shares from './pages/Shares.vue'
import Downloader from './pages/Downloader.vue'
import Storage from './pages/Storage.vue'
import Profile from './pages/Profile.vue'
import ShareView from './pages/ShareView.vue'
import { getToken } from './api.js'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
    { path: '/', component: Home },
    { path: '/shares', component: Shares },
    { path: '/downloader', component: Downloader },
    { path: '/storage', component: Storage },
    { path: '/profile', component: Profile },
    { path: '/s/:id', component: ShareView, meta: { public: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) return '/login'
  if (to.path === '/login' && getToken()) return '/'
  return true
})

createApp(App).use(router).mount('#app')