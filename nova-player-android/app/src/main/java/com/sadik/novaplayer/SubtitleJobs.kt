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
                    // A mangled cue (e.g. 00:00:00 → 00:32:19) reads as half an hour of dialogue and ruins alignment.
                    val text=source.readText();val original=if(source.extension.equals("srt",true))SubtitleTiming.repairSrt(text) else text
                    val before=SubtitleTiming.cues(original,".${source.extension}")
                    require(SubtitleTiming.valid(before)){"Subtitle timings could not be read"}
                    val key=stableId(video.uri+video.size+source.readBytes().contentHashCode()+mode+aid+"align-v2"+(reference?.readText()?:"audio"))
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
                    val aligned=SubtitleAlign.fit(before,speech,mode){ensureActive()};val corrected=aligned.cues
                    // Overlap levels depend on how much of the audio the detector calls speech, so trust
                    // comes from the parts of the file agreeing on one timing (SubtitleAlign.Aligned).
                    val assessment=SubtitleTiming.assess(before,corrected,speech,video.duration.takeIf{it>0}?:Double.POSITIVE_INFINITY,minScore=0.0,minCoverage=0.0)
                        .let{it.copy(reliable=it.reliable&&aligned.confidence>=SubtitleAlign.CONFIDENT)}
                    if(BuildConfig.DEBUG)android.util.Log.d("NovaSync","ratio ${aligned.ratio} confidence ${aligned.confidence} ${assessment}")
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
    /** Timeline resolution for both the speech map and the offsets tried. */
    private const val STEP=.05
    /** Speed ratios between common frame rates (23.976, 24, 25). A subtitle made for another
     *  release drifts steadily — 24 vs 23.976 fps is 0.3 s every 5 minutes — which no single
     *  shift can fix. 1.0 must stay first: it wins ties. */
    private val RATIOS=doubleArrayOf(1.0,24/23.976,23.976/24,25/24.0,24/25.0,25/23.976,23.976/25)
    /** How much better (seconds of speech matched) a new section must fit before the timing
     *  may jump there. Smart jumps at ad breaks and trimmed scenes; Smooth only at clear ones.
     *  Tuned on real episodes against desktop alass results (SyncBenchTest): much lower and
     *  lines chase noise in the speech map, much higher and ad-break jumps are missed. */
    fun penalty(mode:String)=if(mode=="gentle")GENTLE_PENALTY else 5.5
    const val GENTLE_PENALTY=9.0
    /** Extra cost per second a jump moves the timing, so a jump across the room needs far
     *  more evidence than a one-second ad-break correction. */
    fun jumpCost(mode:String)=if(mode=="gentle")GENTLE_JUMP else 1.0
    const val GENTLE_JUMP=1.5

    /**
     * Where each cue fits the detected speech, the way alass does it: find the best overall
     * speed and shift, then let the timing change between lines only where the match gains
     * more than [splitPenalty] (dynamic programming over every line and every offset).
     * Lines never swap order, and text is never touched.
     */
    fun align(cues:List<Cue>,reference:List<Cue>,mode:String,splitPenalty:Double=penalty(mode),jumpCost:Double=jumpCost(mode),check:()->Unit={})=
        fit(cues,reference,mode,splitPenalty,jumpCost,check).cues

    /** [confidence]: share of the file's parts that, searched on their own within a minute,
     *  land on the same timing as the whole. Parts of a subtitle for different audio scatter. */
    data class Aligned(val cues:List<Cue>,val confidence:Double,val ratio:Double)
    /** Below this the match is a guess. Measured on real episodes: another episode's subtitle
     *  scores 0-0.25, genuine matches 0.6 and up. */
    const val CONFIDENT=.5

    fun fit(cues:List<Cue>,reference:List<Cue>,mode:String,splitPenalty:Double=penalty(mode),jumpCost:Double=jumpCost(mode),check:()->Unit={}):Aligned{
        val speech=SpeechMap(reference)
        val (ratio,offset,confidence)=overall(cues,speech,if(mode=="offset")doubleArrayOf(1.0) else RATIOS,check)
        val starts=DoubleArray(cues.size){cues[it].start*ratio+offset}
        val ends=DoubleArray(cues.size){cues[it].end*ratio+offset}
        fun result(shift:(Int)->Double)=Aligned(cues.mapIndexed{i,c->val s=max(0.0,starts[i]+shift(i));c.copy(start=s,end=max(s+.01,ends[i]+shift(i)))},confidence,ratio)
        if(mode=="offset"||cues.size<24)return result{0.0}
        // Offsets tried around the overall fit: states k = 0..2K, offset (k-K)*STEP.
        val n=cues.size
        val k0=min(((if(mode=="gentle")20.0 else 60.0)/STEP).toInt(),(12_000_000/n-1)/2).coerceAtLeast(20)
        val states=2*k0+1
        val from=ShortArray(n*states)
        fun origin(i:Int)=(starts[i]/STEP).roundToInt()-k0
        fun width(i:Int)=max(1,((ends[i]-starts[i])/STEP).roundToInt())
        // A jump costs splitPenalty plus jumpCost per second moved: real jumps (ad breaks,
        // a trimmed scene) are mostly small, and far jumps are where noisy speech misleads.
        val perBin=jumpCost*STEP
        var prev=DoubleArray(states){k->speech.sum(origin(0)+k,origin(0)+k+width(0))-if(k==k0)0.0 else splitPenalty+perBin*abs(k-k0)}
        var cur=DoubleArray(states)
        val left=DoubleArray(states);val leftAt=IntArray(states)
        val window=IntArray(states)
        for(i in 1 until n){
            check()
            // left[k] = best prev[k'] + perBin*k' over k' <= k (jumping later: always in order)
            for(k in 0 until states){val v=prev[k]+perBin*k;if(k==0||v>left[k-1]){left[k]=v;leftAt[k]=k}else{left[k]=left[k-1];leftAt[k]=leftAt[k-1]}}
            // Jumping earlier may not pass the previous line: k' <= k+gap.
            val gap=floor((starts[i]-starts[i-1])/STEP).toInt()
            val a=origin(i);val w=width(i)
            var head=0;var tail=0;var added=states
            for(k in states-1 downTo 0){
                var value=prev[k];var source=k
                val j=min(k,k+gap)
                if(j>=0){val v=left[j]-perBin*k-splitPenalty;if(v>value){value=v;source=leftAt[j]}}
                if(gap>0){
                    // Sliding max of prev[k'] - perBin*k' over k < k' <= k+gap.
                    while(added>k+1){added--;val v=prev[added]-perBin*added;while(tail>head&&prev[window[tail-1]]-perBin*window[tail-1]<=v)tail--;window[tail++]=added}
                    while(tail>head&&window[head]>k+gap)head++
                    if(tail>head){val m=window[head];val v=prev[m]-perBin*(m-k)-splitPenalty;if(v>value){value=v;source=m}}
                }
                cur[k]=value+speech.sum(a+k,a+k+w)
                from[i*states+k]=source.toShort()
            }
            val t=prev;prev=cur;cur=t
        }
        val chosen=IntArray(n);chosen[n-1]=prev.indices.maxBy{prev[it]}
        for(i in n-1 downTo 1)chosen[i-1]=from[i*states+chosen[i]].toInt()
        return result{(chosen[it]-k0)*STEP}
    }

    /** Best single speed ratio and shift (up to 10 minutes either way), and the confidence
     *  described at [Aligned]. Another speed must clearly beat 1.0 to be used. */
    internal fun overall(cues:List<Cue>,speech:SpeechMap,ratios:DoubleArray,check:()->Unit):Triple<Double,Double,Double>{
        val sample=if(cues.size>400)cues.filterIndexed{i,_->i%(cues.size/400+1)==0}else cues
        fun rate(list:List<Cue>,ratio:Double,offset:Double)=list.sumOf{speech.rate(it.start*ratio+offset,it.end*ratio+offset)}-abs(offset)*1e-6
        fun search(list:List<Cue>,ratio:Double,center:Double=0.0,radius:Int=3000):Double{
            var best=center;var quality=Double.NEGATIVE_INFINITY
            for(i in -radius..radius){check();val o=center+i*.2;val q=rate(list,ratio,o);if(q>quality){quality=q;best=o}}
            return best
        }
        var ratio=1.0;var offset=0.0;var plain=0.0;var top=Double.NEGATIVE_INFINITY
        for(r in ratios){
            // Coarse scan of every shift on a sample, then refine the winner on all lines.
            val coarse=search(sample,r)
            var o=coarse;var quality=rate(cues,r,o)
            for(k in -6..6){val q=rate(cues,r,coarse+k*STEP);if(q>quality){quality=q;o=coarse+k*STEP}}
            if(r==1.0){plain=quality;top=quality;ratio=r;offset=o}
            else if(quality>top&&quality>plain+max(.15,abs(plain)*.03)){top=quality;ratio=r;offset=o}
        }
        // A few minutes of lines can't pin their timing down across ±10 minutes on their own,
        // but they can within a minute. 2.5 s leaves room for the ad-break jumps fixed later.
        val parts=cues.chunked(max(12,(cues.size+7)/8)).filter{it.size>=12}
        val agree=parts.count{abs(search(it,ratio,offset,300)-offset)<=2.5}
        return Triple(ratio,offset,if(parts.isEmpty())0.0 else agree.toDouble()/parts.size)
    }

    /** Detected speech on a [STEP] grid, centred so a line placed at random scores about zero:
     *  per second, speech counts +(1-p), silence -p (p = share of speech). Past either end counts
     *  as silence. Sums are in seconds. */
    internal class SpeechMap(reference:List<Cue>){
        val size=((reference.maxOf{it.end}+1)/STEP).toInt()
        private val prefix=DoubleArray(size+1)
        private val silence:Double
        init{
            val bins=BooleanArray(size)
            for(c in reference)for(i in (c.start/STEP).roundToInt().coerceAtLeast(0) until (c.end/STEP).roundToInt().coerceAtMost(size))bins[i]=true
            val p=bins.count{it}.toDouble()/size
            silence=-p*STEP
            for(i in 0 until size)prefix[i+1]=prefix[i]+STEP*(if(bins[i])1-p else -p)
        }
        /** Bins [a, b). */
        fun sum(a:Int,b:Int):Double{
            val lo=a.coerceIn(0,size);val hi=b.coerceIn(0,size)
            val inside=max(0,hi-lo)
            return (if(inside>0)prefix[hi]-prefix[lo] else 0.0)+silence*(b-a-inside)
        }
        fun rate(start:Double,end:Double):Double{val a=(start/STEP).roundToInt();return sum(a,max(a+1,(end/STEP).roundToInt()))}
    }
    fun rewrite(source:String,extension:String,cues:List<Cue>):String{
        fun stamp(seconds:Double,ass:Boolean):String{val units=if(ass)100 else 1000;val n=(seconds*units).roundToLong().coerceAtLeast(0);return if(ass)"%d:%02d:%02d.%02d".format(n/units/3600,n/units/60%60,n/units%60,n%units)else "%02d:%02d:%02d,%03d".format(n/units/3600,n/units/60%60,n/units%60,n%units)}
        var index=0
        if(extension.lowercase() !in setOf("ass","ssa"))return Regex("\\d+:\\d{2}:\\d{2}[,.]\\d+\\s*-->\\s*\\d+:\\d{2}:\\d{2}[,.]\\d+").replace(source){val c=cues[index++];"${stamp(c.start,false)} --> ${stamp(c.end,false)}"}
        var fields=listOf("Layer","Start","End","Style","Name","MarginL","MarginR","MarginV","Effect","Text");var events=false
        return source.lines().joinToString("\n"){line->if(line.startsWith("["))events=line.startsWith("[Events]",true);if(events&&line.startsWith("Format:",true))fields=line.substringAfter(':').split(',').map{it.trim()};if(events&&line.startsWith("Dialogue:",true)){val row=line.substringAfter(':').trimStart().split(',',limit=fields.size).toMutableList();val c=cues[index++];row[fields.indexOfFirst{it.equals("Start",true)}]=stamp(c.start,true);row[fields.indexOfFirst{it.equals("End",true)}]=stamp(c.end,true);"Dialogue: "+row.joinToString(",")}else line}
    }
}


