package com.sadik.novaplayer

import android.net.Uri
import com.sadik.novaplayer.core.MpvNative
import com.sadik.novaplayer.core.SubtitleTiming
import com.sadik.novaplayer.core.SubtitleTiming.Cue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.*

data class SyncState(val running:Boolean=false,val message:String="",val result:File?=null,val video:String="",val reliable:Boolean=true,val shift:Double=0.0)
object SubtitleJobs {
    /** "Show.S01E01.en.srt" for the file the user knows, even when the source is a stored copy. */
    private fun originalName(f: File)=f.nameWithoutExtension.removeSuffix(".synced")
    val state=MutableStateFlow(SyncState())
    private var job:Job?=null
    fun cancel(){job?.cancel();runCatching{MpvNative.cancelAnalysis()};if(state.value.running)state.value=SyncState(message="Alignment cancelled. Original subtitle kept.")}
    fun apply(){
        val s=state.value
        if(s.video!=NovaRuntime.state.value.video?.uri)return
        val file=s.result?:return
        if(!NovaRuntime.attachSubtitle(file,false)){state.value=s.copy(message=s.message+"\nThe corrected file could not be loaded. Your original is unchanged.");return}
        // A baked correction replaces any manual nudging (desktop behaviour).
        NovaRuntime.property("sub-delay",0.0);NovaRuntime.property("sub-speed",1.0)
        File(file.path+".approved").writeText("approved")
        state.value=s.copy(message=s.message.substringBefore("\nReady to review").substringBefore("\nLow confidence")+"\nApplied — you're watching the synced subtitle. Your original is kept.",result=null)
        NovaRuntime.notice.value="Subtitles synced"+if(abs(s.shift)>=.05)" · moved ${if(s.shift<0)"earlier" else "later"} by ${"%.1f".format(abs(s.shift))} s" else ""
    }
    /** [manual] = the user pressed a sync button: a reliable result is applied the moment it is ready. */
    fun start(mode:String,reference:File?=null,manual:Boolean=false){
        if(job?.isActive==true)return
        val video=NovaRuntime.state.value.video?:return
        val source=File(video.originalSub.ifBlank{video.externalSub})
        if(!source.isFile || source.extension.lowercase() !in setOf("srt","ass","ssa")){state.value=SyncState(message="Open an external SRT or ASS subtitle before alignment.");return}
        val aid=NovaRuntime.engine.getStr("aid")?:"auto"
        val prior=job
        job=NovaRuntime.scope.launch{
            prior?.join()
            state.value=SyncState(true,"Preparing subtitle alignment…",video=video.uri)
            val pcm=File(NovaRuntime.app.cacheDir,"sync-${System.nanoTime()}.pcm")
            var shift=0.0
            try {
                val result=withContext(Dispatchers.IO){
                    val original=source.readText();val before=SubtitleTiming.cues(original,".${source.extension}")
                    require(SubtitleTiming.valid(before)){"Subtitle timings could not be read"}
                    val key=stableId(video.uri+video.size+source.readBytes().contentHashCode()+mode+aid+(reference?.readText()?:"audio-v1"))
                    val output=NovaRuntime.subtitleFile("sync-$key","${originalName(source)}.synced.${source.extension}")
                    if(NovaRuntime.pref("subSyncReuse",true)&&output.isFile&&File(output.path+".approved").isFile)return@withContext Triple(output,"Reused your previously approved correction.",true)
                    val speech=if(reference!=null) SubtitleTiming.cues(reference.readText(),".${reference.extension}") else {
                        state.value=state.value.copy(message="Listening for speech locally on your phone…")
                        val uri=Uri.parse(video.uri)
                        require(uri.scheme in setOf("file","content")){"Audio alignment needs a local video. Use a reference subtitle for a stream."}
                        val fd=if(uri.scheme=="content")NovaRuntime.app.contentResolver.openFileDescriptor(uri,"r") else null
                        try{val path=if(fd!=null)"fd://${fd.fd}" else uri.path!!;val code=MpvNative.extractAudio(path,aid,pcm.path);ensureActive();require(code>=0){"Audio analysis failed ($code). Try a reference subtitle."};require(pcm.length()>0){"No audio was decoded"};val raw=MpvNative.detectSpeech(pcm.path);raw.toList().chunked(2).map{Cue(it[0],it[1])}}finally{fd?.close()}
                    }
                    if(BuildConfig.DEBUG)android.util.Log.d("NovaSync","speech ${speech.size}: "+speech.take(10).joinToString{"%.2f-%.2f".format(it.start,it.end)}+" | cues: "+before.take(6).joinToString{"%.2f-%.2f".format(it.start,it.end)})
                    ensureActive();require(SubtitleTiming.valid(speech)){"No clear speech timings were detected. Try a reference subtitle."}
                    state.value=state.value.copy(message="Matching dialogue timing · $mode mode…")
                    val corrected=SubtitleAlign.align(before,speech,mode){ensureActive()}
                    val assessment=SubtitleTiming.assess(before,corrected,speech,video.duration.takeIf{it>0}?:Double.POSITIVE_INFINITY)
                    output.parentFile?.mkdirs();output.writeText(SubtitleAlign.rewrite(original,source.extension,corrected))
                    val message="Timing overlap ${assessment.before}% → ${assessment.after}% · ${com.sadik.novaplayer.ui.plural(assessment.lines, "line")}\nTypical shift ${assessment.medianShift}s · ${com.sadik.novaplayer.ui.plural(assessment.sections, "section")}\n"+if(assessment.reliable)"Ready to review. Your original is preserved." else "Low confidence: review carefully before applying. Your original is unchanged."
                    shift=assessment.medianShift
                    Triple(output,message,assessment.reliable)
                }
                ensureActive()
                if(NovaRuntime.state.value.video?.uri==video.uri){
                    state.value=SyncState(false,result.second,result.first,video.uri,result.third,shift)
                    // The whole point of syncing: once it's ready and trustworthy, just use it.
                    if(result.third&&(manual||NovaRuntime.pref("subSyncAutoApply",true)))apply()
                    else if(!result.third)NovaRuntime.snack.value=Snack("Sync finished but isn't confident","Apply anyway",run={apply()})
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){state.value=SyncState(message=e.message?:"Alignment failed; original kept")}finally{pcm.delete()}
        }
    }
}
object SubtitleAlign {
    fun align(cues:List<Cue>,reference:List<Cue>,mode:String,check:()->Unit={}):List<Cue>{
        val step=.1
        val length=((max(reference.maxOf{it.end},cues.maxOf{it.end})+620)/step).toInt().coerceAtMost(2_000_000)
        val bins=BooleanArray(length)
        for(c in reference)for(i in (c.start/step).roundToInt().coerceAtLeast(0) until (c.end/step).roundToInt().coerceAtMost(length))bins[i]=true
        val prefix=IntArray(length+1);for(i in bins.indices)prefix[i+1]=prefix[i]+if(bins[i])1 else 0
        fun score(group:List<Cue>,offset:Double):Double{var hit=0.0;var total=0.0;for(c in group){val a=((c.start+offset)/step).roundToInt();val b=((c.end+offset)/step).roundToInt();total+=max(1,b-a);if(b>0&&a<length)hit+=prefix[b.coerceIn(0,length)]-prefix[a.coerceIn(0,length)]};return hit/total}
        fun find(group:List<Cue>,center:Double,radius:Double,increment:Double):Double{var best=center;var quality=score(group,center);var offset=center-radius;while(offset<=center+radius){check();val value=score(group,offset)-abs(offset-center)*.00001;if(value>quality){quality=value;best=offset};offset+=increment};return best}
        val sample=if(cues.size>500)cues.filterIndexed{i,_->i%(cues.size/500+1)==0}else cues
        val coarse=find(sample,0.0,600.0,.1);val global=find(cues,coarse,1.0,.1)
        if(mode=="offset" || cues.size<24)return cues.map{it.copy(start=max(0.0,it.start+global),end=max(.01,it.end+global))}
        val groupSize=if(mode=="gentle")80 else 35
        var previous=global
        return cues.chunked(groupSize).flatMap{group->val local=find(group,global,if(mode=="gentle")3.0 else 15.0,.1);val improvement=score(group,local)-score(group,global);val shift=if(group.size>=12&&improvement>(if(mode=="gentle").12 else .07))local else global;val bounded=shift.coerceIn(previous-15,previous+15);previous=bounded;group.map{it.copy(start=max(0.0,it.start+bounded),end=max(.01,it.end+bounded))}}
    }
    fun rewrite(source:String,extension:String,cues:List<Cue>):String{
        fun stamp(seconds:Double,ass:Boolean):String{val units=if(ass)100 else 1000;val n=(seconds*units).roundToLong().coerceAtLeast(0);return if(ass)"%d:%02d:%02d.%02d".format(n/units/3600,n/units/60%60,n/units%60,n%units)else "%02d:%02d:%02d,%03d".format(n/units/3600,n/units/60%60,n/units%60,n%units)}
        var index=0
        if(extension.lowercase() !in setOf("ass","ssa"))return Regex("\\d+:\\d{2}:\\d{2}[,.]\\d+\\s*-->\\s*\\d+:\\d{2}:\\d{2}[,.]\\d+").replace(source){val c=cues[index++];"${stamp(c.start,false)} --> ${stamp(c.end,false)}"}
        var fields=listOf("Layer","Start","End","Style","Name","MarginL","MarginR","MarginV","Effect","Text");var events=false
        return source.lines().joinToString("\n"){line->if(line.startsWith("["))events=line.startsWith("[Events]",true);if(events&&line.startsWith("Format:",true))fields=line.substringAfter(':').split(',').map{it.trim()};if(events&&line.startsWith("Dialogue:",true)){val row=line.substringAfter(':').trimStart().split(',',limit=fields.size).toMutableList();val c=cues[index++];row[fields.indexOfFirst{it.equals("Start",true)}]=stamp(c.start,true);row[fields.indexOfFirst{it.equals("End",true)}]=stamp(c.end,true);"Dialogue: "+row.joinToString(",")}else line}
    }
}


