// 后端统一响应：{ code, message, data }
const BASE = '/api/v1';

let token = localStorage.getItem('drive_token') || '';

export function getToken() {
  return token;
}

export function setToken(t) {
  token = t || '';
  if (t) localStorage.setItem('drive_token', t);
  else localStorage.removeItem('drive_token');
}

class ApiError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

async function request(path, { method = 'GET', body, raw = false, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h['Authorization'] = 'Bearer ' + token;
  let payload;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    h['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }

  const res = await fetch(BASE + path, { method, headers: h, body: payload });
  if (raw) return res;

  let json = null;
  try {
    json = await res.json();
  } catch (e) {
    throw new ApiError(res.status, '服务端返回非 JSON');
  }
  if (!res.ok || json.code !== 0) {
    if (json.code === 41000) {
      setToken('');
    }
    throw new ApiError(json.code ?? res.status, json.message || '请求失败');
  }
  return json.data;
}

export const api = {
  // --- auth ---
  login: (email, password) => request('/auth/login', { method: 'POST', body: { email, password } }),
  register: (email, nickname, password) =>
    request('/auth/register', { method: 'POST', body: { email, nickname, password } }),
  logout: () => request('/auth/logout', { method: 'POST' }),
  me: () => request('/auth/me'),
  // 换头像：FormData 走 multipart，request 里已经识别 FormData，不要手动设 Content-Type
  profile: () => request('/auth/profile'),
  updateNickname: (nickname) => request('/auth/nickname', { method: 'PUT', body: { nickname } }),
  changePassword: (oldPassword, newPassword) =>
    request('/auth/password', { method: 'PUT', body: { oldPassword, newPassword } }),
  storage: () => request('/files/storage'),
  uploadAvatar: (formData) => request('/auth/avatar', { method: 'POST', body: formData }),
  deleteAvatar: () => request('/auth/avatar', { method: 'DELETE' }),

  // --- files ---
  list: (parentId, orderBy, direction) => {
    const q = new URLSearchParams()
    if (parentId != null) q.set('parentId', parentId)
    if (orderBy) q.set('orderBy', orderBy)
    if (direction) q.set('direction', direction)
    const s = q.toString()
    return request('/files' + (s ? '?' + s : ''))
  },
  search: (kw) => request('/files/search?q=' + encodeURIComponent(kw)),
  node: (id) => request(`/files/${id}`),
  mkdir: (parentId, name) => request('/files/mkdir', { method: 'POST', body: { parentId, name } }),
  rename: (id, name) => request(`/files/${id}/rename`, { method: 'PATCH', body: { name } }),
  move: (id, targetParentId) =>
    request(`/files/${id}/move`, { method: 'POST', body: { targetParentId } }),
  trash: (id) => request(`/files/${id}`, { method: 'DELETE' }),
  restore: (id) => request(`/files/${id}/restore`, { method: 'POST' }),
  purge: (id) => request(`/files/${id}/purge`, { method: 'DELETE' }),
  /** 清空回收站。返回 { purgedEntries, blobsReleased, freedBytes } */
  emptyTrash: () => request('/files/trash', { method: 'DELETE' }),
  trashList: () => request('/files/trash'),
  trashSummary: () => request('/files/trash/summary'),

  /** 小文件直传。
   *  parentId 为空时**不要拼进 URL** —— 拼成 `parentId=null` 会被后端当字符串
   *  解析成 Long 而报 NumberFormatException（表面是 500）。省略即落根目录。 */
  uploadSmall: (parentId, file) => {
    const fd = new FormData();
    fd.append('file', file);
    const q = parentId != null ? `?parentId=${parentId}` : '';
    return request(`/files/upload${q}`, { method: 'POST', body: fd });
  },

  // --- 分片上传 ---
  createUpload: (parentId, name, totalSize, chunkSize) =>
    request('/upload', { method: 'POST', body: { parentId, name, totalSize, chunkSize } }),
  uploadStatus: (id) => request(`/upload/${id}/status`),
  putChunk: (id, idx, blob) => {
    const fd = new FormData();
    fd.append('chunk', blob);
    return request(`/upload/${id}/${idx}`, { method: 'PUT', body: fd });
  },
  completeUpload: (id) => request(`/upload/${id}/complete`, { method: 'POST' }),
  abortUpload: (id) => request(`/upload/${id}`, { method: 'DELETE' }),

  // --- 分享 ---
  createShare: (nodeId, password, expireHours, maxDownloads) =>
    request('/shares', { method: 'POST', body: { nodeId, password, expireHours, maxDownloads } }),
  myShares: () => request('/shares'),
  revokeShare: (id) => request(`/shares/${id}`, { method: 'DELETE' }),

  // --- 远程下载 ---
  createRemote: (url, filename, targetParentId, workers) =>
    request('/remote/jobs', { method: 'POST', body: { url, filename, targetParentId, workers } }),
  remoteJobs: () => request('/remote/jobs'),
  cancelRemote: (id) => request(`/remote/jobs/${id}/cancel`, { method: 'POST' }),
  deleteRemote: (id) => request(`/remote/jobs/${id}`, { method: 'DELETE' }),

  // --- 账号与设置（AccountController） ---
  accountProfile: () => request('/account/profile'),
  updateAccountProfile: (body) =>
    request('/account/profile', { method: 'PUT', body }),

  securityQuestions: () => request('/account/security-questions'),
  setSecurityQuestions: (items) =>
    request('/account/security-questions', { method: 'PUT', body: items }),

  devices: () => request('/account/devices'),
  revokeOtherDevices: () => request('/account/devices/revoke-others', { method: 'POST' }),
  revokeDevice: (id) => request(`/account/devices/${id}`, { method: 'DELETE' }),

  // --- 用户管理（管理员 / 超管） ---
  listUsers: (keyword) =>
    request('/account/users' + (keyword ? `?keyword=${encodeURIComponent(keyword)}` : '')),
  changeUserRole: (id, role) =>
    request(`/account/users/${id}/role`, { method: 'PUT', body: { role } }),
  setUserStatus: (id, status) =>
    request(`/account/users/${id}/status`, { method: 'PUT', body: { status } }),
  setUserQuota: (id, quota) =>
    request(`/account/users/${id}/quota`, { method: 'PUT', body: { quota } }),
};

export function downloadUrl(id) {
  return `${BASE}/files/${id}/download?access_token=${encodeURIComponent(token)}`;
}

export function previewUrl(id) {
  return `${BASE}/files/${id}/preview?access_token=${encodeURIComponent(token)}`;
}

export { ApiError };