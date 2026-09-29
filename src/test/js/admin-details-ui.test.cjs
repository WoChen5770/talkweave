'use strict';
const test=require('node:test'), assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm'), path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/managed-ui/app.js'),'utf8');
function fixture() {
  const nodes=new Map();
  function node() { return {hidden:false,disabled:false,textContent:'',value:'',children:[],handlers:new Map(),
    append(...items) { this.children.push(...items); }, replaceChildren(...items) { this.children=items; this.value=''; },
    addEventListener(event,fn) { this.handlers.set(event,fn); }, reset() { for(const el of Object.values(this.elements||{})) el.value=''; }}; }
  function $(id) { if(!nodes.has(id)) nodes.set(id,node()); return nodes.get(id); }
  $('usage-form').elements={conversationId:$('conversation-options'),from:node(),to:node()};
  const context=vm.createContext({$,signedIn:true,AbortController,URLSearchParams,Date,Number,Error,encodeURIComponent,
    document:{createElement:()=>node()},window:{addEventListener:()=>{}},form:()=>{},userActions:()=>{},connectionLabel:()=> '已停用',
    api:async()=>{throw Error('unexpected request');}});
  vm.runInContext(source.slice(source.indexOf('// User details expose'),source.indexOf('// End user details.')),context);
  const hooks=vm.runInContext('({loadUsage,queryUsage,closeDetails,loadConversations,usageQuery,get:()=>detail})',context);
  const user=id=>({id,label:id,enabled:false,bound:true,createdAt:0,lastActivity:null});
  const summary={attempts:1,unreportedAttempts:1,incompleteAttempts:1,invalidAttempts:0,knownInputTokens:null,knownOutputTokens:null,knownCachedInputTokens:null,ratioCoveredAttempts:0,coveredCacheHitRatio:null};
  function routes(id,usage=summary) { context.api=async url=>url.includes('/conversations?')?[]:url.includes('/usage?')?usage:user(id); }
  const values=()=>Object.fromEntries($('usage-values').children.reduce((all,node,i,rows)=> { if(i%2===0) all.push([node.textContent,rows[i+1].textContent]); return all; },[]));
  context.actions={}; context.phases={}; context.cell=(row,text)=>{const td=node();td.textContent=String(text);row.append(td);};
  vm.runInContext(source.slice(source.indexOf('let auditController='),source.indexOf('for (const tab of')),context);
  const audit=vm.runInContext('({loadAudit,clearAudit})',context);
  return {$,context,hooks,user,summary,routes,values,audit};
}

test('late detail response from A cannot replace B and labels remain text',async()=>{
  const f=fixture(); let resolveA,signal;
  f.context.api=async(url,method,body,options)=>{signal=options.signal;return new Promise(r=>resolveA=r);};
  const a=f.hooks.loadUsage(f.user('A')); await Promise.resolve();
  f.routes('B'); await f.hooks.loadUsage({...f.user('B'),label:'<img onerror=alert(1)>'});
  resolveA({...f.user('A'),label:'SECRET-A'}); await a;
  assert.equal(signal.aborted,true); assert.equal(f.hooks.get().user.id,'B'); assert.equal(f.$('usage-title').textContent,'B · 详情与用量');
  f.routes('<img onerror=alert(1)>'); await f.hooks.loadUsage(f.user('C'));
  assert.equal(f.$('usage-title').textContent,'<img onerror=alert(1)> · 详情与用量');
});

test('newer filter wins even when cancelled older usage request returns',async()=>{
  const f=fixture(); f.routes('A'); await f.hooks.loadUsage(f.user('A'));
  let resolveOld,oldSignal; f.context.api=async(url,method,body,options)=>{oldSignal=options.signal;return new Promise(r=>resolveOld=r);};
  const older=f.hooks.queryUsage(); await Promise.resolve();
  let queried; f.context.api=async url=>{queried=url;return {...f.summary,attempts:3};};
  f.$('usage-form').elements.conversationId.value='conversation-B'; await f.hooks.queryUsage();
  resolveOld({...f.summary,attempts:99}); await older;
  assert.equal(oldSignal.aborted,true); assert.ok(queried.includes('conversationId=conversation-B')); assert.equal(f.values()['实际尝试数'],'3');
});

test('closing view or logout aborts and clears statistics before late results',async()=>{
  const f=fixture(); f.routes('A'); await f.hooks.loadUsage(f.user('A'));
  let resolve,signal; f.context.api=async(url,method,body,options)=>{signal=options.signal;return new Promise(r=>resolve=r);};
  const pending=f.hooks.queryUsage(); await Promise.resolve(); f.context.signedIn=false; f.hooks.closeDetails();
  resolve({...f.summary,attempts:123}); await pending;
  assert.equal(signal.aborted,true); assert.equal(f.$('usage').hidden,true); assert.equal(f.$('usage-values').children.length,0); assert.equal(f.hooks.get(),null);
});

test('unknown, invalid, zero and partial usage have distinct truthful presentations',async()=>{
  const f=fixture(); f.routes('A'); await f.hooks.loadUsage(f.user('A'));
  assert.equal(f.values()['已知输入 token'],'未知 / 无记录'); assert.equal(f.values()['覆盖范围内命中率'],'未知 / 无记录');
  assert.match(f.$('usage-status').textContent,/部分统计/);
  f.routes('A',{...f.summary,unreportedAttempts:0,incompleteAttempts:0,knownInputTokens:0,knownOutputTokens:0,knownCachedInputTokens:0});
  await f.hooks.queryUsage(); assert.equal(f.values()['已知输入 token'],'0'); assert.doesNotMatch(f.$('usage-status').textContent,/部分统计/);
  f.routes('A',{...f.summary,attempts:4,incompleteAttempts:2,invalidAttempts:1,knownInputTokens:2020,knownOutputTokens:105,knownCachedInputTokens:1500,ratioCoveredAttempts:2,coveredCacheHitRatio:0.75});
  await f.hooks.queryUsage(); assert.equal(f.values()['包含异常用量的尝试'],'1'); assert.equal(f.values()['已知输入 token'],'2020'); assert.equal(f.values()['覆盖范围内命中率'],'75.0%');
});

test('local time boundaries are encoded exactly and invalid ranges never reach API',async()=>{
  const f=fixture(); f.routes('A'); await f.hooks.loadUsage(f.user('A'));
  const form=f.$('usage-form'); form.elements.from.value='2026-09-29T10:00'; form.elements.to.value='2026-09-29T10:01';
  const query=f.hooks.usageQuery(form); assert.equal(query.get('from'),String(new Date(form.elements.from.value).getTime())); assert.equal(query.get('to'),String(new Date(form.elements.to.value).getTime()));
  form.elements.to.value=form.elements.from.value; let calls=0; f.context.api=async()=>{calls++;};
  await f.hooks.queryUsage(); assert.equal(calls,0); assert.match(f.$('usage-status').textContent,/结束时间/); assert.equal(f.$('usage-values').children.length,0);
  form.elements.to.value='invalid'; assert.throws(()=>f.hooks.usageQuery(form));
});

test('conversation metadata paginates with cursor without replacing selected conversation',async()=>{
  const f=fixture(); let queried;
  f.context.api=async url=>url.includes('/conversations?')?Array.from({length:50},(_,i)=>({id:'c'+i,sequence:100-i,bindingVersion:1,bindingCurrent:false,createdAt:0})):url.includes('/usage?')?f.summary:f.user('A');
  await f.hooks.loadUsage(f.user('A')); f.$('conversation-options').value='c7'; assert.equal(f.$('conversation-more').hidden,false);
  f.context.api=async url=>{queried=url;return [{id:'older',sequence:49,bindingVersion:1,bindingCurrent:false,createdAt:0}];};
  await f.hooks.loadConversations(f.hooks.get()); assert.ok(queried.includes('before=51')); assert.equal(f.$('conversation-options').value,'c7'); assert.equal(f.$('conversation-more').hidden,true);
  assert.equal(f.$('conversation-options').children.length,52); assert.match(f.$('conversation-options').children[1].textContent,/历史身份/);
});


test('audit pagination preserves user filter and renders external values as text',async()=>{
  const f=fixture(); f.$('audit-user').value='A'; let requested;
  f.context.api=async()=>Array.from({length:50},(_,i)=>({sequence:100-i,occurredAt:0,actor:'administrator',action:'USER_CREATED',target:'A/<img onerror=alert(1)>',result:'SUCCEEDED'}));
  await f.audit.loadAudit(); assert.equal(f.$('audit').children.length,50); assert.equal(f.$('audit-more').hidden,false);
  f.context.api=async url=>{requested=url;return [];}; await f.audit.loadAudit(true);
  assert.ok(requested.includes('userId=A')); assert.ok(requested.includes('before=51')); assert.equal(f.$('audit-more').hidden,true);
  assert.equal(f.$('audit').children[0].children[3].textContent,'A/<img onerror=alert(1)>');
});

test('late audit page cannot mix into a new user filter or repopulate after logout',async()=>{
  const f=fixture(); let resolveOld,signal;
  f.context.api=async(url,method,body,options)=>{signal=options.signal;return new Promise(r=>resolveOld=r);};
  const old=f.audit.loadAudit(); await Promise.resolve();
  f.$('audit-user').value='B'; f.context.api=async()=>[{sequence:1,occurredAt:0,actor:'administrator',action:'USER_CREATED',target:'B',result:'SUCCEEDED'}];
  await f.audit.loadAudit(); resolveOld([{target:'A'}]); await old;
  assert.equal(signal.aborted,true); assert.equal(f.$('audit').children.length,1); assert.equal(f.$('audit').children[0].children[3].textContent,'B');
  f.context.api=async()=>new Promise(r=>resolveOld=r); const pending=f.audit.loadAudit(); await Promise.resolve();
  f.context.signedIn=false; f.audit.clearAudit(); resolveOld([{target:'A'}]); await pending;
  assert.equal(f.$('audit').children.length,0);
});
