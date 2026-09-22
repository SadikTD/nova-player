const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {execFileSync}=require('child_process');
const {SubtitleSync}=require('../src/main/subtitle-sync');
const timing=require('../src/main/subtitle-timing');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'dist/speech-fixture'),tools=process.env.NOVA_SYNC_TOOLS || path.join(root,'vendor/sync');
fs.mkdirSync(dir,{recursive:true});
if(!fs.existsSync(path.join(dir,'voice-11.wav'))) execFileSync('powershell.exe',['-NoProfile','-File',path.join(__dirname,'make-sync-speech-fixture.ps1'),'-OutputDirectory',dir],{windowsHide:true});
const run=(exe,args)=>execFileSync(path.join(tools,exe),args,{windowsHide:true,stdio:['ignore','pipe','pipe']}).toString();
function stamp(t){const ms=Math.round(t*1000);return `${String(Math.floor(ms/3600000)).padStart(2,'0')}:${String(Math.floor(ms/60000)%60).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')},${String(ms%1000).padStart(3,'0')}`;}
let time=0,lines=[],list=[];
for(let i=0;i<24;i++){
 const gap=1+(i*7%5)*.7;
 const silence=path.join(dir,`silence-${i}.wav`),voice=path.join(dir,`normalized-${i%12}.wav`);
 run('ffmpeg.exe',['-v','error','-y','-f','lavfi','-i','anullsrc=r=16000:cl=mono','-t',String(gap),silence]);
 if(i<12)run('ffmpeg.exe',['-v','error','-y','-i',path.join(dir,`voice-${i}.wav`),'-ar','16000','-ac','1',voice]);
 const duration=Number(run('ffprobe.exe',['-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',voice]));
 time+=gap;lines.push({start:time,end:time+duration,text:`Test dialogue ${i+1}`});time+=duration;
 list.push(silence,voice);
}
fs.writeFileSync(path.join(dir,'concat.txt'),list.map(f=>`file '${f.replace(/\\/g,'/')}'`).join('\n'));
const video=path.join(dir,'dialogue.wav');run('ffmpeg.exe',['-v','error','-y','-f','concat','-safe','0','-i',path.join(dir,'concat.txt'),video]);
const source=path.join(dir,'dialogue.srt');
fs.writeFileSync(source,lines.map((c,i)=>`${i+1}\n${stamp(c.start+(i<12?2:5))} --> ${stamp(c.end+(i<12?2:5))}\n${c.text}\n`).join('\n'));
let selected=source;
const engine={isActive:()=>true,getProp:async p=>({path:video,'track-list':[{type:'sub',selected:true,external:true,'external-filename':selected},{type:'audio',selected:true,'ff-index':0}],duration:time,'sub-delay':0,'sub-speed':1})[p],addSubtitle:async f=>selected=f,exec:async()=>{}};
const sync=new SubtitleSync({engine:()=>engine,settings:()=>({subSyncApply:false}),cacheDir:fs.mkdtempSync(path.join(dir,'cache-')),toolsDir:tools,notify:()=>{}});
(async()=>{await sync.start();await sync.job?.done;assert.equal(sync.state.status,'review',sync.state.message);const result=timing.cues(fs.readFileSync(sync.result.output,'utf8'));const error=result.map((c,i)=>Math.abs(c.start-lines[i].start)).sort((a,b)=>a-b);assert(error[Math.floor(error.length/2)]<.7,`median timing error ${error[Math.floor(error.length/2)]}`);console.log('PASS: real speech detection and segmented audio alignment',JSON.stringify(sync.state.summary));})().catch(e=>{console.error(e);process.exitCode=1;});
