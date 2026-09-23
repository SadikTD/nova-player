package com.sadik.novaplayer
import android.app.Instrumentation
import android.os.Bundle
import com.sadik.novaplayer.core.MpvNative
import java.io.File
class NativeAnalysisTest: Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val result=Bundle()
        try{
            val input=File(targetContext.filesDir,"nova-test.mkv");check(input.isFile){"Fixture missing"}
            MpvNative.create(targetContext)
            val output=File(targetContext.cacheDir,"analysis-test.pcm")
            val code=MpvNative.extractAudio(input.path,"auto",output.path)
            check(code==0){"PCM decoder failed: $code"};check(output.length()>32000){"PCM output missing"}
            val speech=MpvNative.detectSpeech(output.path);check(speech.size%2==0)
            result.putString("stream","PASS: native PCM decode (${output.length()} bytes) and WebRTC speech detection (${speech.size/2} intervals).")
            output.delete();MpvNative.destroy();finish(-1,result)
        }catch(e:Throwable){result.putString("stream","FAIL: ${e.stackTraceToString()}");finish(0,result)}
    }
}
