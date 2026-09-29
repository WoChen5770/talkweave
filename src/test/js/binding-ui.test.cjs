'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname,'../../main/resources/managed-ui/app.js'),'utf8');

// Executes the shipped binding UI logic, not a duplicate of its state machine.
function fixture() {
  const nodes = new Map(), revoked = [], cleared = [], windows = new Map();
  let next = 0;
  function $(id) {
    if (!nodes.has(id)) nodes.set(id, {hidden:false,disabled:false,textContent:'',open:false,src:null,
      elements:{code:{value:''}}, handlers:new Map(), child:{disabled:false},
      addEventListener(event,fn) { this.handlers.set(event,fn); },
      removeAttribute(name) { this[name] = null; },
      querySelector() { return this.child; }, reset() { this.elements.code.value = ''; },
      showModal() { this.open = true; }, close() { this.open = false; }
    });
    return nodes.get(id);
  }
  const context = vm.createContext({$,signedIn:true,AbortController,Date,Set,Error,encodeURIComponent,
    URL:{createObjectURL:() => 'blob:test-' + ++next,revokeObjectURL:url => revoked.push(url)},
    setTimeout:() => ++next,setInterval:() => ++next,clearTimeout:id => cleared.push(id),clearInterval:id => cleared.push(id),
    window:{addEventListener:(event,fn) => windows.set(event,fn)},confirm:() => true,
    api:async () => { throw new Error('Unexpected API call'); }, loadUsers:async () => {}, action:() => {}
  });
  vm.runInContext(source.slice(source.indexOf('const phases =')),context);
  const hooks = vm.runInContext('({openBinding,closeBinding,showBindingStatus,bindingControls,bindingError,pollBinding,bindingMutation,get:()=>binding})',context);
  const status = (userId,id,phase='QR_READY') => ({userId,id,phase,mode:'INITIAL',authEpoch:1,expiresAt:Date.now()+60000,imageReady:true});
  return {$,context,hooks,status,revoked,cleared,windows};
}

test('late QR for A cannot render in B dialog and close cancels its requests',async () => {
  const f=fixture(); let resolveImage, signalA, imageStarted;
  const entered=new Promise(resolve => imageStarted=resolve);
  f.context.api=async (url,method,body,options) => {
    if (options.image) { signalA=options.signal; return new Promise(resolve => { resolveImage=resolve; imageStarted(); }); }
    return f.status('A','task-A');
  };
  const opening=f.hooks.openBinding({id:'A',label:'<img onerror=alert(1)>',authEpoch:1},'INITIAL');
  await entered;
  assert.equal(f.$('binding-title').textContent,'<img onerror=alert(1)>');
  f.context.api=async () => ({...f.status('B','task-B','REQUESTING_QR'),imageReady:false});
  await f.hooks.openBinding({id:'B',label:'B',authEpoch:1},'INITIAL');
  resolveImage({type:'image/png'}); await opening;
  assert.equal(signalA.aborted,true);
  assert.equal(f.$('binding-title').textContent,'B');
  assert.equal(f.$('binding-image').src,null);
  assert.equal(f.hooks.get().status.id,'task-B');
});

test('expiry hides and revokes image but permits explicit refresh',async () => {
  const f=fixture(); f.context.api=async (url,method,body,options) => options.image ? {type:'image/png'} : f.status('A','one');
  await f.hooks.openBinding({id:'A',label:'A',authEpoch:1},'INITIAL');
  const old=f.$('binding-image').src, ctx=f.hooks.get(); ctx.status.expiresAt=Date.now()-1;
  f.hooks.bindingControls(ctx);
  assert.deepEqual(f.revoked,[old]); assert.equal(f.$('binding-image').hidden,true);
  assert.equal(f.$('binding-refresh').disabled,false); assert.equal(f.$('binding-cancel').disabled,true);
  f.hooks.bindingError(ctx,{status:409,code:'EXPIRED',message:'expired'});
  assert.equal(ctx.conflict,false); assert.equal(f.$('binding-refresh').disabled,false);
});

test('stale-tab conflict prevents mutations without adopting another task',async () => {
  const f=fixture(); f.context.api=async (url,method,body,options) => options.image ? {type:'image/png'} : f.status('A','one');
  await f.hooks.openBinding({id:'A',label:'A',authEpoch:1},'INITIAL');
  f.hooks.bindingError(f.hooks.get(),{status:409,code:'CONFLICT',message:'stale'});
  assert.equal(f.$('binding-refresh').disabled,true); assert.equal(f.$('binding-cancel').disabled,true);
  assert.equal(f.$('binding-image').src,null); assert.equal(f.hooks.get().status.id,'one');
});

test('terminal and close remove image and pairing code references',async () => {
  const f=fixture(); f.context.api=async (url,method,body,options) => options.image ? {type:'image/png'} : f.status('A','one','NEED_PAIRING');
  await f.hooks.openBinding({id:'A',label:'A',authEpoch:1},'INITIAL');
  const ctx=f.hooks.get(); f.$('pairing-form').elements.code.value='123456';
  await f.hooks.showBindingStatus(ctx,{...f.status('A','one','IDENTITY_UNVERIFIED'),imageReady:false},ctx.serial);
  assert.equal(f.$('binding-image').src,null); assert.equal(f.$('pairing-form').hidden,true);
  f.hooks.closeBinding(); assert.equal(f.$('pairing-form').elements.code.value,'');
  assert.equal(ctx.controller.signal.aborted,true); assert.equal(f.hooks.get(),null); assert.ok(f.cleared.length>=2);
});

test('pairing challenge reappearing enables corrected code input',async () => {
  const f=fixture(); f.context.api=async () => ({...f.status('A','one','SCANNED'),imageReady:false});
  await f.hooks.openBinding({id:'A',label:'A',authEpoch:1},'INITIAL'); const ctx=f.hooks.get(); ctx.pairSubmitted=true;
  await f.hooks.showBindingStatus(ctx,{...f.status('A','one','NEED_PAIRING'),imageReady:false},ctx.serial);
  assert.equal(ctx.pairSubmitted,false); assert.equal(f.$('pairing-form').hidden,false);
  assert.equal(f.$('pairing-form').child.disabled,false);
});

test('stale poll sequence cannot overwrite a newer task mutation',async () => {
  const f=fixture(); f.context.api=async () => ({...f.status('A','one','SCANNED'),imageReady:false});
  await f.hooks.openBinding({id:'A',label:'A',authEpoch:1},'INITIAL'); const ctx=f.hooks.get(), previous=ctx.serial;
  ctx.serial++;
  await f.hooks.showBindingStatus(ctx,{...f.status('A','one','NEED_PAIRING'),imageReady:false},previous);
  assert.equal(ctx.status.phase,'SCANNED');
  await assert.rejects(f.hooks.showBindingStatus(ctx,f.status('B','one'),ctx.serial));
});
