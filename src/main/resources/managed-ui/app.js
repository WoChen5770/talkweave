'use strict';
const $ = id => document.getElementById(id);
let csrfHeader = '', csrfToken = '', signedIn = false;
const messages = {UNAUTHENTICATED:'登录已失效，请重新登录。',FORBIDDEN:'请求校验失败，请刷新页面后重试。',INVALID_CREDENTIALS:'用户名或密码错误，或管理员尚未初始化。',LOGIN_THROTTLED:'登录过于频繁，请一分钟后重试。',INVALID_SETTINGS:'配置无效，请检查对应字段。',INVALID_INPUT:'输入无效，请检查格式和取值范围。',NOT_FOUND:'记录不存在或不属于所选用户。',UNAVAILABLE:'服务暂不可用，请稍后重试。',CONFLICT:'任务已被其他操作更新，请关闭弹窗并刷新用户列表后重试。',EXPIRED:'二维码任务已过期，请刷新二维码。',UNAUTHORIZED:'用户已停用或授权已变化，请刷新用户列表。',BACKLOG:'当前绑定任务已达上限，请稍后重试。'};
function notice(text) { $('notice').textContent = text; }
function view(authenticated) {
  signedIn = authenticated; $('login-panel').hidden = authenticated; $('dashboard').hidden = !authenticated; $('logout').hidden = !authenticated;
  if (!authenticated) { closeBinding(); closeDetails(); clearAudit(); $('runtime-status').textContent = ''; $('users').replaceChildren(); $('audit').replaceChildren(); $('usage').hidden = true; $('model-form').reset(); $('model-form').elements.apiKey.value = ''; }
}
async function api(path, method = 'GET', body, options = {}) {
  const headers = {}; if (method !== 'GET') headers[csrfHeader] = csrfToken;
  if (body && !(body instanceof URLSearchParams)) headers['Content-Type'] = 'application/json';
  const res = await fetch('/api/admin' + path, {method, headers, credentials:'same-origin', cache:'no-store', signal:options.signal, body:body instanceof URLSearchParams ? body : body === undefined ? undefined : JSON.stringify(body)});
  if (res.ok && options.image) return res.blob();
  let data; try { const text = await res.text(); data = text ? JSON.parse(text) : null; } catch { throw new Error('服务器返回了无法识别的响应。'); }
  if (!res.ok) {
    if (res.status === 401 && path !== '/login') { view(false); notice(messages.UNAUTHENTICATED); if (path !== '/session') await session(); }
    const error = new Error((messages[data?.code] || '操作失败，请稍后重试。') + (data?.field ? ' 字段：' + data.field : ''));
    error.status = res.status; error.code = data?.code; throw error;
  }
  return data;
}
async function session() { const value = await api('/session'); csrfHeader = value.csrfHeader; csrfToken = value.csrfToken; view(value.authenticated); }
async function action(work, button) { if (button) button.disabled = true; try { await work(); } catch (error) { if (error.name !== 'AbortError') notice(error.message); } finally { if (button) button.disabled = false; } }
function cell(row, text) { const td = document.createElement('td'); td.textContent = String(text); row.append(td); return td; }
function button(parent, text, handler) { const el = document.createElement('button'); el.type = 'button'; el.textContent = text; el.addEventListener('click', () => action(handler, el)); parent.append(el); }
function form(id, handler) { $(id).addEventListener('submit', event => { event.preventDefault(); action(() => handler(event.currentTarget), event.submitter); }); }
form('login-form', async form => { try { const fresh = await api('/session'); csrfHeader = fresh.csrfHeader; csrfToken = fresh.csrfToken; await api('/login','POST',new URLSearchParams(new FormData(form))); await session(); await load(); notice('已登录。'); } finally { form.elements.password.value = ''; } });
$('logout').addEventListener('click', () => action(async () => { await api('/logout','POST'); view(false); await session(); notice('已退出登录。'); }));
async function load() { await Promise.all([loadUsers(), loadSettings(), loadAudit()]); }
const connectionStates = {STARTING:'正在启动',CONNECTED:'已连接',RECONNECTING:'正在重连',USER_BACKLOG:'用户积压，暂停接收',GLOBAL_BACKLOG:'全局积压，暂停接收',STORAGE_PAUSED:'存储异常，暂停接收',REAUTH_REQUIRED:'需要原身份重新认证',FAULTED:'处理已中断',STOPPED:'未运行'};
const connectionErrors = {USER_DISABLED:'用户已停用',UNBOUND:'尚未绑定',CREDENTIALS_INACTIVE:'凭据失效',CONNECTION_LIMIT:'达到连接数上限',DATABASE_UNAVAILABLE:'数据库不可用',WORK_INTERRUPTED:'请检查服务后重新认证',BATCH_BACKLOG:'批次超过队列余量',STORAGE_UNAVAILABLE:'存储不可用'};
function connectionLabel(value) {
  if (!value) return '状态未知';
  return (connectionStates[value.state] || '状态未知') + (value.error ? '（' + (connectionErrors[value.error] || (value.error.startsWith('MODEL_') ? '模型请求异常' : '微信连接异常')) + '）' : '');
}
async function loadUsers() {
  const [users, runtime] = await Promise.all([api('/users'), api('/runtime')]); if (!signedIn) return;
  const healthNames = {STARTING:'正在启动',RUNNING:'正常',DATABASE_UNAVAILABLE:'数据库不可用，暂停接收',LOW_DISK:'私有材料目录空间不足，暂停接收',GLOBAL_BACKLOG:'全局消息积压，暂停新增接收',CONNECTION_LIMIT:'连接数已达上限',STOPPED:'已停止'};
  const cacheNames = {AVAILABLE:'可用',DEGRADED:'退化，使用 MySQL 历史',DISABLED:'已关闭，使用 MySQL 历史'};
  const cacheStatus = runtime.historyCache ? ' · 历史缓存：' + (cacheNames[runtime.historyCache.state] || '状态未知') : '';
  $('runtime-status').textContent = '运行管理器：' + (healthNames[runtime.health] || '状态未知') + ' · 已加载连接 ' + runtime.activeConnections + ' / ' + runtime.maxConnections + cacheStatus;
  $('users').replaceChildren(); $('users-empty').hidden = users.length !== 0;
  const auditUser=$('audit-user').value; $('audit-user').replaceChildren(); option($('audit-user'),'','全部操作');
  for (const user of users) option($('audit-user'),user.id,user.label);
  if (users.some(user=>user.id===auditUser)) $('audit-user').value=auditUser;
  for (const user of users) {
    const row = document.createElement('tr'); cell(row,user.label); cell(row,(user.enabled ? '已启用' : '已停用') + ' · ' + (user.bound ? '已绑定' : '未绑定') + (user.currentAttempt ? ' · ' + (phases[user.currentAttempt.phase] || '未知状态') : '') + ' · ' + connectionLabel(user.connection)); cell(row,user.lastActivity == null ? '暂无' : new Date(user.lastActivity).toLocaleString('zh-CN'));
    const actions = cell(row,''); userActions(actions,user);
    button(actions,'详情与用量',() => loadUsage(user)); $('users').append(row);
  }
}
function userActions(actions,user) {
  button(actions, user.enabled ? '停用' : '恢复', async () => {
      if (!confirm(user.enabled ? '确认停用此用户？已排队或在途任务将失效。' : '确认恢复此用户？恢复不会跳过微信身份校验。')) return;
      await api('/users/' + encodeURIComponent(user.id) + '/enabled','PUT',{enabled:!user.enabled}); await loadUsers(); if (detail?.user.id === user.id) await loadUsage(user); notice('用户状态已更新。');
    });
    if (user.enabled) {
      if (user.currentAttempt && openPhases.has(user.currentAttempt.phase)) button(actions,'查看绑定任务',() => openBinding(user, null));
      else button(actions,user.bound ? '重新认证原身份' : '绑定微信',() => openBinding(user, user.bound ? 'REAUTHENTICATE' : 'INITIAL'));
      if (user.bound) button(actions,'更换微信身份',async () => {
        if (!confirm('确认更换微信身份？创建任务即撤销旧连接和在途权限，旧历史不会交给新身份；取消也不会恢复旧权限。')) return;
        await openBinding(user,'REPLACE');
      });
    }
}
form('user-form', async form => { await api('/users','POST',{label:form.elements.label.value}); form.reset(); await loadUsers(); notice('用户已创建。点击“绑定微信”生成独立二维码；仅创建用户不会发起微信登录。'); });
$('reload-users').addEventListener('click', () => action(loadUsers));
// User details expose only metadata and accounting. Each view owns cancellable requests.
let detail = null;
function detailLive(ctx) { return signedIn && detail === ctx && !ctx.controller.signal.aborted; }
function closeDetails() {
  if (detail) { detail.controller.abort(); detail.usageController?.abort(); }
  detail = null; $('usage').hidden = true;
  for (const id of ['usage-values','user-values','user-actions','conversation-options']) $(id).replaceChildren();
  $('usage-title').textContent = ''; $('usage-status').textContent = ''; $('usage-form').reset();
}
function definitions(id, entries) {
  $(id).replaceChildren();
  for (const [label,value] of entries) {
    const dt=document.createElement('dt'), dd=document.createElement('dd');
    dt.textContent=label; dd.textContent=value == null ? '未知 / 无记录' : String(value); $(id).append(dt,dd);
  }
}
function option(parent,value,label) { const item=document.createElement('option'); item.value=value; item.textContent=label; parent.append(item); }
function dateLabel(value) { return value == null ? '暂无' : new Date(value).toLocaleString('zh-CN'); }
async function loadUsage(user) {
  closeDetails(); const ctx={user,controller:new AbortController(),usageSerial:0,before:null,loadingConversations:false}; detail=ctx;
  $('usage').hidden=false; $('usage-title').textContent=user.label + ' · 详情与用量'; $('usage-status').textContent='正在读取…';
  option($('conversation-options'),'','全部会话（含历史绑定）'); $('conversation-more').hidden=true;
  try {
    const current=await api('/users/'+encodeURIComponent(user.id),'GET',undefined,{signal:ctx.controller.signal});
    if (!detailLive(ctx)) return; ctx.user=current;
    $('usage-title').textContent=current.label + ' · 详情与用量';
    definitions('user-values',[['系统用户 ID',current.id],['状态',current.enabled?'已启用':'已停用'],['绑定',current.bound?'已绑定':'未绑定'],
      ['连接',connectionLabel(current.connection)],['创建时间',dateLabel(current.createdAt)],['最近有效消息',dateLabel(current.lastActivity)]]);
    userActions($('user-actions'),current);
    await Promise.all([loadConversations(ctx),queryUsage(ctx)]);
  } catch (error) { if (detailLive(ctx) && error.name !== 'AbortError') $('usage-status').textContent=error.message; }
}
async function loadConversations(ctx) {
  if (!detailLive(ctx) || ctx.loadingConversations) return;
  ctx.loadingConversations=true; $('conversation-more').disabled=true;
  try {
    const query=new URLSearchParams({limit:'50'}); if (ctx.before != null) query.set('before',ctx.before);
    const rows=await api('/users/'+encodeURIComponent(ctx.user.id)+'/conversations?'+query,'GET',undefined,{signal:ctx.controller.signal});
    if (!detailLive(ctx)) return;
    for (const item of rows) option($('conversation-options'),item.id,
      dateLabel(item.createdAt)+' · 绑定 v'+item.bindingVersion+(item.bindingCurrent?'':'（历史身份）')+' · '+item.id);
    ctx.before=rows.length ? rows[rows.length-1].sequence : ctx.before;
    $('conversation-more').hidden=rows.length<50;
  } catch (error) { if (detailLive(ctx) && error.name !== 'AbortError') $('usage-status').textContent=error.message; }
  finally { if (detailLive(ctx)) { ctx.loadingConversations=false; $('conversation-more').disabled=false; } }
}
function usageQuery(form) {
  const query=new URLSearchParams(); const conversation=form.elements.conversationId.value;
  if (conversation) query.set('conversationId',conversation);
  const from=form.elements.from.value ? new Date(form.elements.from.value).getTime() : 0;
  const to=form.elements.to.value ? new Date(form.elements.to.value).getTime() : null;
  if (!Number.isSafeInteger(from) || from<0 || to != null && (!Number.isSafeInteger(to) || to<=from)) throw new Error('时间范围无效：结束时间须晚于开始时间。');
  if (from) query.set('from',String(from)); if (to != null) query.set('to',String(to)); return query;
}
async function queryUsage(ctx=detail) {
  if (!ctx || !detailLive(ctx)) return;
  ctx.usageController?.abort(); const serial=++ctx.usageSerial; ctx.usageController=new AbortController();
  $('usage-values').replaceChildren(); $('usage-status').textContent='正在查询…';
  try {
    const query=usageQuery($('usage-form'));
    const value=await api('/users/'+encodeURIComponent(ctx.user.id)+'/usage?'+query,'GET',undefined,{signal:ctx.usageController.signal});
    if (!detailLive(ctx) || serial!==ctx.usageSerial) return;
    $('usage-status').textContent=value.attempts===0 ? '此范围没有已记录的模型尝试。' : value.incompleteAttempts>0
      ? '部分统计：含未报告、缺失或异常用量，以下已知数值不是全部实际用量。' : '所选范围的用量字段完整；命中率仅代表下列覆盖范围。';
    definitions('usage-values', [['实际尝试数',value.attempts],['未报告用量的尝试',value.unreportedAttempts],['统计不完整的尝试',value.incompleteAttempts],
      ['包含异常用量的尝试',value.invalidAttempts],['已知输入 token',value.knownInputTokens],['已知输出 token',value.knownOutputTokens],
      ['已知缓存输入 token',value.knownCachedInputTokens],['命中率覆盖的尝试',value.ratioCoveredAttempts],
      ['覆盖范围内命中率',value.coveredCacheHitRatio==null?null:(value.coveredCacheHitRatio*100).toFixed(1)+'%']]);
  } catch (error) { if (detailLive(ctx) && serial===ctx.usageSerial && error.name !== 'AbortError') $('usage-status').textContent=error.message; }
}
$('usage-close').addEventListener('click',closeDetails);
$('conversation-more').addEventListener('click',()=> { if (detail) loadConversations(detail); });
form('usage-form',()=>queryUsage());
$('usage-reset').addEventListener('click',()=> { $('usage-form').reset(); queryUsage(); });
window.addEventListener('pagehide',closeDetails);
// End user details.
async function loadSettings() {
  const data = await api('/settings'); if (!signedIn) return; $('idle-form').elements.minutes.value = data.settings.idleMinutes;
  const form = $('model-form'); form.elements.apiKey.value = ''; $('key-status').textContent = data.model ? '已保存 Key；留空保留，输入新值替换。' : '尚未配置模型；首次保存必须填写 Key。';
  if (data.model) for (const [key,value] of Object.entries(data.model)) { const input = form.elements.namedItem(key); if (!input || key === 'apiKey') continue; if (input.type === 'checkbox') input.checked = value; else input.value = value; }
}
form('idle-form', async form => { await api('/settings/idle-timeout','PUT',{minutes:Number(form.elements.minutes.value)}); notice('会话超时已保存。'); });
form('model-form', async form => {
  const value = {}; for (const name of ['apiBaseUrl','name','systemPrompt']) value[name] = form.elements.namedItem(name).value;
  for (const name of ['contextCapacity','outputBudget','safetyMargin','historyRounds','requestTimeoutSeconds','totalTimeBudgetSeconds','maxRetries']) value[name] = Number(form.elements.namedItem(name).value);
  value.allowInsecureLocalHttp = form.elements.allowInsecureLocalHttp.checked; if (form.elements.apiKey.value) value.apiKey = form.elements.apiKey.value;
  try { await api('/settings/model','PUT',value); await loadSettings(); notice('模型配置已保存，未发起测试或付费请求。'); } finally { form.elements.apiKey.value = ''; delete value.apiKey; }
});
const actions = {ADMIN_INITIALIZED:'管理员初始化',LOGIN_SUCCEEDED:'登录成功',LOGIN_FAILED:'登录失败',LOGIN_THROTTLED:'登录限流',LOGOUT:'退出登录',USER_CREATED:'新增用户',USER_ENABLED:'恢复用户',USER_DISABLED:'停用用户',SESSION_EXPIRED:'登录凭据失效',MODEL_CONFIGURATION_UPDATED:'更新模型配置',IDLE_TIMEOUT_UPDATED:'更新会话超时',BINDING_INITIAL:'首次绑定',BINDING_REAUTHENTICATE:'重新认证',BINDING_REPLACE:'更换身份',BINDING_CANCELLED:'取消绑定',BINDING_ACTIVATED:'激活绑定',BINDING_FINISHED:'结束绑定'};
let auditController=null, auditSerial=0, auditBefore=null;
function clearAudit() { auditController?.abort(); auditSerial++; auditBefore=null; $('audit').replaceChildren(); $('audit-status').textContent=''; $('audit-user').replaceChildren(); }
async function loadAudit(more=false) {
  auditController?.abort(); const serial=++auditSerial; auditController=new AbortController();
  if (!more) { auditBefore=null; $('audit').replaceChildren(); }
  $('audit-more').disabled=true; $('audit-status').textContent='正在读取…';
  try {
    const query=new URLSearchParams({limit:'50'}); if (more && auditBefore!=null) query.set('before',auditBefore);
    if ($('audit-user').value) query.set('userId',$('audit-user').value);
    const data=await api('/audit?'+query,'GET',undefined,{signal:auditController.signal});
    if (!signedIn || serial!==auditSerial) return;
    for (const entry of data) { const row=document.createElement('tr'); cell(row,dateLabel(entry.occurredAt)); cell(row,entry.actor==='administrator'?'管理员':entry.actor); cell(row,actions[entry.action]||entry.action); cell(row,entry.target); cell(row,entry.result==='SUCCEEDED'?'成功':(phases[entry.result]||'已拒绝')); $('audit').append(row); }
    auditBefore=data.length ? data[data.length-1].sequence : auditBefore;
    $('audit-more').hidden=data.length<50; $('audit-status').textContent=data.length?'按操作记录倒序；每页最多 50 条。':'没有更多操作记录。';
  } catch (error) { if (signedIn && serial===auditSerial && error.name!=='AbortError') $('audit-status').textContent=error.message; }
  finally { if (serial===auditSerial) $('audit-more').disabled=false; }
}
$('reload-audit').addEventListener('click',()=>loadAudit());
$('audit-more').addEventListener('click',()=>loadAudit(true));
$('audit-user').addEventListener('change',()=>loadAudit());
window.addEventListener('pagehide',clearAudit);
for (const tab of document.querySelectorAll('[data-tab]')) tab.addEventListener('click', () => { for (const panel of document.querySelectorAll('.tab')) panel.hidden = panel.id !== tab.dataset.tab; for (const other of document.querySelectorAll('[data-tab]')) other.classList.toggle('active',other === tab); });


const phases = {REQUESTING_QR:'正在申请二维码',QR_READY:'等待扫码',SCANNED:'已扫码，等待微信确认',NEED_PAIRING:'需要输入微信配对码',VERIFYING_IDENTITY:'正在核验扫码身份',SUCCEEDED:'绑定成功',EXPIRED:'二维码已过期',CANCELLED:'任务已取消',CONFLICT:'绑定冲突',IDENTITY_UNVERIFIED:'无法确认身份，未授予权限',FAILED:'绑定失败，请重新申请二维码'};
const openPhases = new Set(['REQUESTING_QR','QR_READY','SCANNED','NEED_PAIRING','VERIFYING_IDENTITY']);
const modeNames = {INITIAL:'首次绑定',REAUTHENTICATE:'重新认证：必须使用原微信身份，不能借此更换主人',REPLACE:'更换身份：旧权限已撤销，新身份不继承旧历史'};
let binding = null;
function bindingPath(ctx, suffix = '') { return '/users/' + encodeURIComponent(ctx.user.id) + '/binding-attempts' + suffix; }
function bindingLive(ctx, serial = ctx.serial) { return binding === ctx && serial === ctx.serial && signedIn && !ctx.controller.signal.aborted; }
function releaseQr() {
  $('binding-image').hidden = true; $('binding-image').removeAttribute('src');
  if (binding?.imageUrl) { URL.revokeObjectURL(binding.imageUrl); binding.imageUrl = null; }
}
function closeBinding() {
  if (binding) { clearTimeout(binding.timer); clearInterval(binding.countdown); binding.controller.abort(); releaseQr(); binding = null; }
  $('pairing-form').reset(); $('pairing-form').hidden = true;
  if ($('binding-dialog').open) $('binding-dialog').close();
}
function bindingError(ctx, error) {
  if (!bindingLive(ctx) || error.name === 'AbortError') return;
  $('binding-error').textContent = error.message; $('binding-error').hidden = false;
  if ((error.status === 409 && error.code !== 'EXPIRED' && error.code !== 'BACKLOG') || error.status === 403 || error.status === 404) { ctx.conflict = true; releaseQr(); clearTimeout(ctx.timer); $('binding-phase').textContent = '无法继续当前任务'; $('binding-placeholder').hidden = false; $('binding-placeholder').textContent = '请关闭弹窗并刷新用户列表'; }
  if (error.code === 'EXPIRED') { releaseQr(); ctx.expired = true; }
  bindingControls(ctx);
}
function bindingControls(ctx) {
  if (!bindingLive(ctx)) return;
  const value = ctx.status, open = value && openPhases.has(value.phase), expired = value && Date.now() >= value.expiresAt;
  ctx.expired = !!expired;
  $('binding-refresh').disabled = ctx.busy || ctx.conflict || !value || value.phase === 'SUCCEEDED';
  $('binding-cancel').disabled = ctx.busy || ctx.conflict || !open || expired;
  $('pairing-form').hidden = !open || value.phase !== 'NEED_PAIRING' || expired || ctx.conflict;
  $('pairing-form').querySelector('button').disabled = ctx.busy || ctx.pairSubmitted;
  if (value) {
    $('binding-countdown').textContent = open ? (expired ? '本地倒计时已结束，旧码已隐藏。可刷新二维码。' : '剩余 ' + Math.ceil((value.expiresAt - Date.now()) / 1000) + ' 秒') : '此任务已结束，二维码已释放。';
    if (expired || !open || ctx.conflict) { $('pairing-form').reset(); releaseQr(); $('binding-placeholder').hidden = false; $('binding-placeholder').textContent = '当前没有可用二维码'; }
  }
}
async function showBindingStatus(ctx, value, serial) {
  if (!bindingLive(ctx, serial)) return;
  if (!value || value.userId !== ctx.user.id || (ctx.status && value.id !== ctx.status.id)) throw new Error('任务状态已变化，请关闭后重新打开。');
  const changed = ctx.status?.phase !== value.phase;
  if (changed) ctx.pairSubmitted = false;
  ctx.status = value;
  $('binding-phase').textContent = phases[value.phase] || '未知状态';
  $('binding-mode').textContent = modeNames[value.mode] || '';
  $('binding-task').textContent = '任务：' + value.id;
  bindingControls(ctx);
  if (value.imageReady && openPhases.has(value.phase) && !ctx.expired && !ctx.conflict && !ctx.imageUrl) {
    const blob = await api(bindingPath(ctx,'/' + encodeURIComponent(value.id) + '/qr'),'GET',undefined,{signal:ctx.controller.signal,image:true});
    if (!bindingLive(ctx,serial) || ctx.expired || Date.now() >= value.expiresAt || ctx.conflict) return;
    if (blob.type !== 'image/png') throw new Error('二维码图片格式无效。');
    ctx.imageUrl = URL.createObjectURL(blob); $('binding-image').src = ctx.imageUrl; $('binding-image').hidden = false; $('binding-placeholder').hidden = true;
  }
}
function scheduleBinding(ctx) {
  clearTimeout(ctx.timer);
  if (bindingLive(ctx) && !ctx.busy && !ctx.conflict && ctx.status && openPhases.has(ctx.status.phase)) ctx.timer = setTimeout(() => pollBinding(ctx), 1500);
}
async function pollBinding(ctx) {
  if (!bindingLive(ctx) || ctx.busy) return;
  const serial = ++ctx.serial;
  try {
    const value = await api(bindingPath(ctx,'/' + encodeURIComponent(ctx.status.id)),'GET',undefined,{signal:ctx.controller.signal});
    await showBindingStatus(ctx,value,serial);
    if (bindingLive(ctx,serial) && !openPhases.has(value.phase)) await loadUsers();
  } catch (error) { if (bindingLive(ctx,serial)) bindingError(ctx,error); }
  finally { if (bindingLive(ctx,serial)) scheduleBinding(ctx); }
}
async function bindingMutation(ctx, work) {
  if (!bindingLive(ctx) || ctx.busy) return;
  ctx.busy = true; clearTimeout(ctx.timer); const serial = ++ctx.serial;
  $('binding-error').hidden = true; bindingControls(ctx);
  try { await work(serial); }
  catch (error) { if (bindingLive(ctx,serial)) bindingError(ctx,error); }
  finally { if (bindingLive(ctx,serial)) { ctx.busy = false; bindingControls(ctx); scheduleBinding(ctx); } }
}
async function openBinding(user, mode) {
  closeBinding();
  const ctx = {user,status:null,serial:0,controller:new AbortController(),busy:false,conflict:false,pairSubmitted:false,imageUrl:null}; binding = ctx;
  $('binding-title').textContent = user.label; $('binding-mode').textContent = ''; $('binding-task').textContent = ''; $('binding-countdown').textContent = '';
  $('binding-error').hidden = true; $('binding-phase').textContent = '正在读取任务…'; $('binding-placeholder').textContent = '正在准备二维码…'; $('binding-placeholder').hidden = false;
  $('binding-dialog').showModal(); ctx.countdown = setInterval(() => bindingControls(ctx),1000);
  await bindingMutation(ctx,async serial => {
    const value = mode
      ? await api(bindingPath(ctx),'POST',{mode,authEpoch:user.authEpoch,previousAttempt:null,confirmReplacement:mode === 'REPLACE'},{signal:ctx.controller.signal})
      : await api(bindingPath(ctx,'/current'),'GET',undefined,{signal:ctx.controller.signal});
    await showBindingStatus(ctx,value,serial);
    if (bindingLive(ctx,serial)) await loadUsers();
  });
}
$('binding-close').addEventListener('click',closeBinding);
$('binding-dialog').addEventListener('cancel',event => { event.preventDefault(); closeBinding(); });
$('binding-dialog').addEventListener('close',() => { if (!$('binding-dialog').open) closeBinding(); });
window.addEventListener('pagehide',closeBinding);
$('binding-refresh').addEventListener('click',() => {
  const ctx = binding; if (!ctx?.status || ctx.busy || ctx.conflict) return;
  if (!confirm('刷新后旧二维码和配对码立即失效，确认继续？')) return;
  bindingMutation(ctx,async serial => {
    const old = ctx.status; releaseQr(); $('pairing-form').reset(); ctx.pairSubmitted = false;
    const value = await api(bindingPath(ctx),'POST',{mode:old.mode,authEpoch:old.authEpoch,previousAttempt:old.id,confirmReplacement:old.mode === 'REPLACE'},{signal:ctx.controller.signal});
    if (!bindingLive(ctx,serial)) return;
    ctx.status = null; $('binding-placeholder').hidden = false; $('binding-placeholder').textContent = '正在申请新二维码…';
    await showBindingStatus(ctx,value,serial); if (bindingLive(ctx,serial)) await loadUsers();
  });
});
$('binding-cancel').addEventListener('click',() => {
  const ctx = binding; if (!ctx?.status || ctx.busy || ctx.conflict) return;
  bindingMutation(ctx,async serial => {
    const value = await api(bindingPath(ctx,'/' + encodeURIComponent(ctx.status.id) + '/cancel'),'POST',undefined,{signal:ctx.controller.signal});
    await showBindingStatus(ctx,value,serial); if (bindingLive(ctx,serial)) await loadUsers();
  });
});
$('pairing-form').addEventListener('submit',event => {
  event.preventDefault(); const ctx = binding;
  if (!ctx?.status || ctx.busy || ctx.pairSubmitted || ctx.conflict) return;
  let code = event.currentTarget.elements.code.value; event.currentTarget.reset();
  bindingMutation(ctx,async serial => {
    try {
      const value = await api(bindingPath(ctx,'/' + encodeURIComponent(ctx.status.id) + '/pairing'),'POST',{code},{signal:ctx.controller.signal});
      if (bindingLive(ctx,serial)) { ctx.pairSubmitted = true; await showBindingStatus(ctx,value,serial); }
    } finally { code = ''; }
  });
});
action(async () => { await session(); if (signedIn) await load(); });
