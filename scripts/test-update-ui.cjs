const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const { app, BrowserWindow } = require('electron');
app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false, width: 1200, height: 800, webPreferences: { nodeIntegration: true, contextIsolation: false, backgroundThrottling: false, offscreen: true } });
  try {
    for (const page of ['index', 'player']) {
      let html = fs.readFileSync(`src/renderer/${page}.html`, 'utf8');
      html = html.replace(/<meta http-equiv="Content-Security-Policy"[^>]*>/, '').replace('<head>', '<head><base href="../src/renderer/">');
      html = html.replace(/<script src="(?:app|player|sync-player)\.js"><\/script>/, '');
      const mock = `<script>
      window.calls=[];window.api={updateState:async()=>({status:'ready',version:'1.3.2'}),updateInstall:async()=>{calls.push('install');return {status:'installing'}},updateCheck:async()=>({status:'downloading',version:'1.3.2',percent:15}),updateDownloadPage:async()=>calls.push('download'),onUpdate:cb=>window.pushUpdate=cb};
      window.${page === 'index' ? 'nova' : 'player'}=api;
      </script>`;
      html = html.replace('<script src="updates.js">', mock + '<script src="updates.js">');
      const file = path.resolve(`dist/update-test-${page}.html`);
      fs.writeFileSync(file, html);
      await win.loadFile(file);
      await win.webContents.insertCSS('* { animation: none !important; transition: none !important; } body { background: #0d1017; }');
      const result = await win.webContents.executeJavaScript(`(async()=>{
        const banner=document.querySelector('#update-banner');
        const action=banner.querySelector('.update-action');
        const check=(ok,msg)=>{if(!ok)throw Error(msg)};
        check(!banner.classList.contains('hidden'),'ready banner visible');
        check(action.textContent==='Restart & update','explicit install label');
        action.click();await new Promise(r=>setTimeout(r,0));check(calls[0]==='install','install action');
        pushUpdate({status:'error',error:'Blocked'});check(action.textContent==='Retry','retry label');
        action.click();await new Promise(r=>setTimeout(r,0));check(banner.textContent.includes('15%'),'retry reports download progress');
        pushUpdate({status:'available',version:'1.3.2',portable:true});action.click();await new Promise(r=>setTimeout(r,0));check(calls.includes('download'),'portable download action');
        pushUpdate({status:'current'});check(banner.classList.contains('hidden'),'current version hides banner');
        pushUpdate({status:'ready',version:'1.3.2'});
        return true;
      })()`);
      assert(result);
      await new Promise(r => setTimeout(r, 150));
      fs.writeFileSync(`dist/update-banner-${page}.png`, (await win.webContents.capturePage()).toPNG());
      win.setSize(860, 540);
      await new Promise(r => setTimeout(r, 150));
      const fits = await win.webContents.executeJavaScript(`(()=>{const r=document.querySelector('#update-banner').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight})()`);
      assert(fits, page + ' banner fits minimum window');
      win.setSize(1200,800);
    }
    console.log('PASS: library/player update banners, restart, progress, retry, portable download, visibility, minimum window layout');
    app.exit(0);
  } catch (e) { console.error(e); app.exit(1); }
});
