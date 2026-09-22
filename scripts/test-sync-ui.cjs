const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
app.whenReady().then(async () => {
 const win = new BrowserWindow({ show:false, width:1200, height:900, webPreferences:{ contextIsolation:false,nodeIntegration:true,backgroundThrottling:false,offscreen:true }});
 try {
 const bridge=`<script>
 window.calls=[];window.settings={};window.syncState={status:'idle',message:'Ready to sync',engineReady:true};
 const api={saveSettings:async s=>{Object.assign(settings,s);calls.push(['settings',s]);},subSyncState:async()=>syncState,onSubSync:cb=>window.syncEvent=cb,
 subSyncStart:async o=>{calls.push(['start',o]);syncState={status:'analyzing',message:'Listening for speech…',busy:true};return syncState;},
 subSyncCancel:async()=>{calls.push(['cancel']);return {status:'cancelled',message:'Cancelled',busy:false};},
 subSyncApply:async()=>{calls.push(['apply']);return {...syncState,status:'applied',canUndo:true};},subSyncUndo:async()=>{calls.push(['undo']);return {...syncState,status:'restored',canUndo:false};},subSyncReference:async mode=>{calls.push(['reference',mode]);return null;},subSyncFolder:async()=>calls.push(['folder']),
 cmd:async()=>({}),init:async()=>({props:{'track-list':[{type:'sub',id:1,title:'English',selected:true}]},settings}),onProp:()=>{},onLink:()=>{},onResumed:()=>{},onSettings:()=>{},onAutoSubs:()=>{},
 on:()=>{},getState:async()=>({folders:[],items:{},playlists:[],history:[],prefs:{},settings}),subsContext:async()=>({languages:[['eng','English']],langs:['eng']}),updateState:async()=>({status:'current',currentVersion:'1.4.0'})};
 window.player=api;window.nova=api;
 </script>`;
 async function load(page){let html=fs.readFileSync('src/renderer/'+page+'.html','utf8').replace(/<meta http-equiv="Content-Security-Policy"[^>]*>/,'').replace('<head>','<head><base href="../src/renderer/">').replace('<script src="accents.js">',bridge+'<script src="accents.js">');fs.writeFileSync('dist/sync-ui-'+page+'.html',html);await win.loadFile(path.resolve('dist/sync-ui-'+page+'.html'));await win.webContents.insertCSS('*{animation:none!important;transition:none!important;}body{background:#0d1017!important;}');await new Promise(r=>setTimeout(r,150));}
 await load('player');
 await win.webContents.executeJavaScript(`(async()=>{
 const check=(v,m)=>{if(!v)throw Error(m)};
 document.querySelector('#btn-subs').click();document.querySelector('#auto-sync-options').click();await new Promise(r=>setTimeout(r,0));
 check(!!document.querySelector('#as-start'),'sync panel opens');
 const mode=document.querySelector('#as-mode');mode.value='offset';mode.dispatchEvent(new Event('change'));
 document.querySelector('#as-start').click();await new Promise(r=>setTimeout(r,0));check(calls.at(-1)[1].mode==='offset','selected mode passed');check(!!document.querySelector('#as-cancel'),'cancel visible while busy');
 document.querySelector('#as-cancel').click();await new Promise(r=>setTimeout(r,0));check(calls.at(-1)[0]==='cancel','cancellation');
 syncState={status:'review',message:'Your correction is ready',summary:{lines:240,before:41,after:80,medianShift:-2.7,note:'Please check a few lines.'}};syncEvent(syncState);
 document.querySelector('#as-use').click();await new Promise(r=>setTimeout(r,0));check(!!document.querySelector('#as-undo'),'undo shown after apply');
 document.querySelector('#as-undo').click();await new Promise(r=>setTimeout(r,0));check(calls.at(-1)[0]==='undo','restore action');
 const toggle=document.querySelector('#as-apply');toggle.checked=false;toggle.dispatchEvent(new Event('change'));check(settings.subSyncApply===false,'player preference saved');
 syncEvent({...syncState,status:'review'});
 })()`);
 await new Promise(r=>setTimeout(r,150));fs.writeFileSync('dist/auto-sync-player.png',(await win.webContents.capturePage()).toPNG());
 win.setSize(860,540);await new Promise(r=>setTimeout(r,150));assert(await win.webContents.executeJavaScript(`(()=>{const r=document.querySelector('.sheet-card').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.height<=innerHeight})()`),'panel fits small window');
 win.setSize(1200,900);await load('index');
 await win.webContents.executeJavaScript(`(async()=>{
 const check=(v,m)=>{if(!v)throw Error(m)};document.querySelector('[data-view="settings"]').click();await new Promise(r=>setTimeout(r,0));
 for(const [id,key] of [['downloads','subSyncDownloads'],['local','subSyncLocal'],['apply','subSyncApply'],['reuse','subSyncReuse']]){const e=document.querySelector('#set-sync-'+id);check(!!e,'setting '+id);e.checked=true;e.dispatchEvent(new Event('change'));await new Promise(r=>setTimeout(r,0));check(settings[key]===true,'saved '+key);}
 const mode=document.querySelector('#set-sync-mode');mode.value='gentle';mode.dispatchEvent(new Event('change'));await new Promise(r=>setTimeout(r,0));check(settings.subSyncMode==='gentle','default mode saved');
 document.querySelector('#sync-open-folder').click();check(calls.at(-1)[0]==='folder','saved files action');document.querySelector('#set-sync-downloads').closest('.settings-card').scrollIntoView({block:'start'});
 })()`);
 await new Promise(r=>setTimeout(r,150));fs.writeFileSync('dist/auto-sync-settings.png',(await win.webContents.capturePage()).toPNG());
 console.log('PASS: player modes, start/cancel/apply/undo, player preferences, all settings toggles, saved files, and minimum-window layout');app.exit(0);
 }catch(e){console.error(e);app.exit(1);}
});
