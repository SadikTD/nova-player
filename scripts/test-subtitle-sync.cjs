const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const { SubtitleSync } = require('../src/main/subtitle-sync');
const timing = require('../src/main/subtitle-timing');
const root = path.resolve(__dirname, '..');
const tmp = fs.mkdtempSync(path.join(root, 'dist', 'sync-test-'));
function stamp(t) { const ms = Math.round(t * 1000); return `${String(Math.floor(ms / 3600000)).padStart(2,'0')}:${String(Math.floor(ms/60000)%60).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')},${String(ms%1000).padStart(3,'0')}`; }
function srt(list) { return list.map((c,i)=>`${i+1}\n${stamp(c.start)} --> ${stamp(c.end)}\n${c.text}\n`).join('\n'); }
let seed=37; const rand=()=>{seed=(seed*16807)%2147483647;return seed/2147483647;};
let time=3;const good=Array.from({length:80},(_,i)=>{time+=1+rand()*3;const c={start:time,end:time+1+rand()*2,text:`A distinct dialogue line ${i+1}.`};time=c.end;return c;});
const bad=good.map((c,i)=>({...c,start:c.start+(i<40?2:7),end:c.end+(i<40?2:7)}));
const video=path.join(tmp,'episode.mkv'),source=path.join(tmp,'episode.srt'),reference=path.join(tmp,'reference.srt');
fs.writeFileSync(video,'reference-only fixture');fs.writeFileSync(source,srt(bad));fs.writeFileSync(reference,srt(good));
let activeVideo=video,selected=source,delay=2,speed=1.01;
const settings={subSyncMode:'smart',subSyncApply:false};
const other=path.join(tmp,'episode.eng-nova.srt');fs.writeFileSync(other,srt(bad));
const engine={isActive:()=>true,getProp:async p=>({path:activeVideo,'track-list':[{id:1,type:'sub',selected:true,external:true,'external-filename':selected},...(selected===other?[]:[{id:2,type:'sub',selected:false,external:true,'external-filename':other}]),{type:'audio',selected:true,'ff-index':0}],duration:time+20,'sub-delay':delay,'sub-speed':speed})[p],addSubtitle:async file=>{selected=file;},exec:async c=>{if(c[1]==='sid'&&c[2]===2)selected=other;if(c[1]==='sub-delay')delay=c[2];if(c[1]==='sub-speed')speed=c[2];}};
const sync=new SubtitleSync({engine:()=>engine,settings:()=>settings,cacheDir:path.join(tmp,'cache'),toolsDir:path.join(root,'vendor/sync'),notify:()=>{}});
(async()=>{
 const original=fs.readFileSync(source,'utf8');
 await sync.start({reference,mode:'smart'});await sync.job?.done;
 assert.equal(sync.state.status,'review',sync.state.message);
 assert.equal(sync.state.summary.reliable,true);
 const result=timing.cues(fs.readFileSync(sync.result.output,'utf8'));
 assert(result.every((c,i)=>Math.abs(c.start-good[i].start)<.12),'piecewise +2s / +7s correction');
 assert.equal(fs.readFileSync(source,'utf8'),original,'original unchanged');
 await sync.apply();assert.equal(sync.state.status,'applied');assert.equal(delay,0);assert.equal(speed,1);
 settings.subSyncApply=true;sync.fileChanged();selected=source;delay=2;speed=1.01;
 await sync.automatic();assert.equal(sync.state.status,'applied','approved reference correction reused on reopening');assert.equal(sync.job,null,'reuse skips analysis');
 settings.subSyncApply=false;
 const appliedMetadata = sync.result.metadataFile;
 await sync.start({reference,force:true}); sync.cancel(); await sync.job?.done;
 assert.equal(sync.getState().canUndo,true,'cancelled rerun preserves Undo');
 await sync.start({reference,force:true}); await sync.job?.done;
 assert.equal(sync.getState().canUndo,true,'reviewing a rerun preserves Undo');
 await sync.apply();
 await sync.undo();assert.equal(selected,source);assert.equal(delay,2);assert.equal(speed,1.01);
 assert.equal(JSON.parse(fs.readFileSync(appliedMetadata)).disabled,true,'undo disables automatic reuse');
 await sync.start({reference,mode:'smart',force:true});sync.cancel();await sync.job?.done;
 assert.equal(sync.state.status,'cancelled');assert.equal(selected,source);
 await sync.start({reference,force:true});sync.fileChanged();activeVideo=path.join(tmp,'other.mkv');await sync.job?.done;
 assert.equal(sync.state.status,'idle');assert.equal(sync.getState().busy,false);assert.equal(selected,source,'new episode cannot receive old correction');
 const uncertain=timing.assess(good,good,good.map(c=>({...c,start:c.start+10000,end:c.end+10000})),time+20);assert.equal(uncertain.reliable,false);
 assert.throws(()=>timing.assess(good,good.map(c=>({...c,text:'changed'})),good));
 // Trailing spaces are trimmed by alass; that is not a content change.
 assert.equal(timing.assess(good.map(c=>({...c,text:c.text+' '})),good,good).reliable,true,'whitespace-only differences pass');
 // A mangled 00:00:00 -> 30min cue is repaired; untouched cues stay byte-identical.
 const broken=srt([...good.slice(0,50),{...good[50],start:0},...good.slice(51)]);
 const fixed=timing.repairSrt(broken);assert.equal(fixed.repaired,1);
 const fc=timing.cues(fixed.text);assert(fc[50].start>=good[49].end-.001&&Math.abs(fc[50].end-good[50].end)<.002);assert.equal(timing.repairSrt(srt(good)).repaired,0);
 // Reopening restores the most recently applied fix, whichever method made it.
 activeVideo=video;sync.fileChanged();selected=source;delay=0;speed=1;settings.subSyncApply=false;
 await sync.start({reference,mode:'gentle',force:true});await sync.job?.done;await sync.apply();
 await sync.start({reference,mode:'offset',force:true});await sync.job?.done;await sync.apply();
 settings.subSyncApply=true;sync.fileChanged();selected=other;
 await sync.automatic({opened:true});
 assert.equal(sync.state.status,'applied');assert.equal(sync.result.mode,'offset','last applied method is restored, not the default');
 assert.equal(sync.result.source,source,'the subtitle file the fix was made from is reselected');
 // Removing a subtitle deletes every fix made from it.
 assert(sync.forget({source})>=3);assert.equal(sync.lastUsed(require('../src/main/subtitle-sync').signature(video)),null,'no saved fix survives removal');
 // A failed automatic analysis is remembered and not repeated on reopen.
 const realRun=sync.run;let runs=0;sync.run=async()=>{runs++;throw Error('simulated failure');};
 for(let i=0;i<2;i++){sync.fileChanged();selected=source;await sync.start({mode:'smart',automatic:true});await sync.job?.done;}
 assert.equal(runs,1,'failed automatic analysis is not repeated');sync.run=realRun;
 // A subtitle built into the video is copied out and synced like a file.
 const mkv=path.join(tmp,'embedded.mkv'),badSrt=path.join(tmp,'embedded-src.srt');fs.writeFileSync(badSrt,srt(bad));
 require('child_process').execFileSync(path.join(root,'vendor/sync/ffmpeg.exe'),['-v','error','-y','-f','lavfi','-t',String(Math.ceil(time+20)),'-i','anullsrc=r=16000:cl=mono','-i',badSrt,'-map','0:a','-map','1:s','-c:a','pcm_s16le','-c:s','srt',mkv]);
 const embTracks=()=>[{id:1,type:'sub',selected:selected===null,external:false,codec:'subrip','ff-index':1},...(selected?[{id:2,type:'sub',selected:true,external:true,'external-filename':selected}]:[]),{id:1,type:'audio',selected:true,'ff-index':0}];
 const embEngine={...engine,getProp:async p=>p==='track-list'?embTracks():p==='path'?activeVideo:engine.getProp(p),addSubtitle:async file=>{selected=file;},exec:async c=>{if(c[1]==='sid')selected=c[2]===1?null:selected;if(c[1]==='sub-delay')delay=c[2];if(c[1]==='sub-speed')speed=c[2];}};
 const esync=new SubtitleSync({engine:()=>embEngine,settings:()=>settings,cacheDir:path.join(tmp,'cache2'),toolsDir:path.join(root,'vendor/sync'),notify:()=>{}});
 activeVideo=mkv;selected=null;delay=0;speed=1;settings.subSyncApply=false;
 await esync.automatic({opened:true});assert.equal(esync.getState().status,'idle','opening never extracts or analyzes built-in subtitles');
 await esync.start({reference,mode:'smart'});await esync.job?.done;
 assert.equal(esync.state.status,'review',esync.state.message);
 assert(timing.cues(fs.readFileSync(esync.result.output,'utf8')).every((c,i)=>Math.abs(c.start-good[i].start)<.12),'built-in subtitle corrected');
 await esync.apply();assert.equal(esync.state.status,'applied');assert.equal(selected,esync.result.output);
 await esync.undo();assert.equal(selected,null,'undo goes back to the built-in track');
 await esync.start({reference,force:true});await esync.job?.done;await esync.apply();
 settings.subSyncApply=true;esync.fileChanged();selected=null;
 await esync.automatic({opened:true});assert.equal(esync.state.status,'applied','saved fix for a built-in subtitle reapplies on reopen');
 assert.equal(esync.sourceTrack(embTracks(),esync.result).id,1);
 await assert.rejects(esync.extractEmbedded(mkv,'x',{codec:'hdmv_pgs_subtitle'},'1',()=>{}),/pictures/);
 console.log('PASS: built-in subtitle extracted, synced, undone to the built-in track, restored on reopen');
 console.log('PASS: removal deletes fixes, failed automatic sync not repeated');
 console.log('PASS: whitespace-tolerant content check, broken-cue repair, last-used fix restored on reopen');
 console.log('PASS: real alass segmented +2s/+7s alignment, output validation, apply, manual-timing reset, undo, cancellation, episode switch, uncertain-match gate');
})().catch(e=>{console.error(e);process.exitCode=1;});
