const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
app.whenReady().then(async () => {
 const win = new BrowserWindow({show:false,width:1200,height:900,webPreferences:{contextIsolation:false,nodeIntegration:true,backgroundThrottling:false,offscreen:true}});
 try {
 const html=fs.readFileSync('src/renderer/player.html','utf8');
 const mock=`<script>window.commands=[];window.player={cmd:async (...c)=>{commands.push(c);return {};},init:async()=>({props:{'sub-delay':0,'sub-speed':1,'track-list':[{type:'sub',id:1,title:'English',selected:true}]},settings:{}}),onProp:cb=>window.propCallback=cb,onLink:()=>{},onResumed:()=>{},onSettings:()=>{},onAutoSubs:()=>{}};void 0;</script>`;
 fs.mkdirSync('dist',{recursive:true});
 const testFile=path.resolve('dist/timing-test.html');
 fs.writeFileSync(testFile,html.replace(/<meta http-equiv="Content-Security-Policy"[^>]*>/,'').replace('<head>','<head><base href="../src/renderer/">').replace('<script src="accents.js">',mock+'<script src="accents.js">'));
 await win.loadFile(testFile);
 await new Promise(r=>setTimeout(r,500));
 const result=await win.webContents.executeJavaScript(`(async()=>{
 const assert=(ok,msg)=>{if(!ok)throw Error(msg)};
 document.querySelector('#btn-subs').click();
 const input=document.querySelector('.sync-number');input.focus();input.value='4';input.dispatchEvent(new Event('change'));
 assert(commands.at(-1)[2]===4,'direct four second entry');
 document.querySelector('[data-nudge="-5"]').click();assert(commands.at(-1)[2]===-1,'five second nudge');
 for(let i=0;i<10;i++)document.querySelector('[data-nudge="0.1"]').click();assert(commands.at(-1)[2]===0,'rapid nudges accumulate');
 input.value='700';input.dispatchEvent(new Event('change'));assert(commands.at(-1)[2]===0,'invalid offset rejected');
 input.value='-4';input.dispatchEvent(new Event('change'));assert(commands.at(-1)[2]===-4,'negative offset');
 propCallback({name:'sub-delay',data:-4});assert(document.querySelector('.sync-number')===input,'focused input survives engine response');
 const rate=document.querySelector('#sub-rate');rate.value='125';rate.dispatchEvent(new Event('change'));assert(commands.at(-1)[1]==='sub-speed'&&commands.at(-1)[2]===0.8,'speed maps to reciprocal timestamp scale');
 document.querySelector('#rate-reset').click();assert(commands.at(-1)[2]===1,'speed reset');
 document.querySelector('.sync-reset').click();assert(commands.at(-1)[2]===0,'timing reset');
 document.querySelector('#btn-audio').click();const audio=document.querySelector('.sync-number');audio.value='2';audio.dispatchEvent(new Event('change'));assert(commands.at(-1)[1]==='audio-delay'&&commands.at(-1)[2]===2,'audio entry');
 document.querySelector('#btn-subs').click();
 return 'PASS: direct entry, negative offsets, large and rapid nudges, validation, focus stability, subtitle speed, resets, audio timing';
 })()`);
 console.log(result);
 await win.webContents.executeJavaScript("document.body.style.background='#10141d'");
 await win.webContents.insertCSS('* { animation: none !important; transition: none !important; }');
 await new Promise(r=>setTimeout(r,350));
 fs.mkdirSync('dist',{recursive:true});
 fs.writeFileSync('dist/subtitle-timing-preview.png',(await win.webContents.capturePage()).toPNG());
 app.exit(0);
 }catch(e){console.error(e);app.exit(1);}
});

