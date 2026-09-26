/*
 * nova_mpv.cpp — JNI bridge to libmpv for Nova Player.
 *
 * Adapted in structure from mpv-android's app/src/main/jni (MIT) — see
 * THIRD-PARTY-NOTICES.md. The contract that matters:
 *
 *   attachSurface(Surface) sets mpv's "wid" to the Surface jobject pointer
 *   reinterpreted as an int64. That is how libmpv's Android vo reaches a
 *   SurfaceView. detachSurface sets wid back to 0.
 *
 *   av_jni_set_java_vm + av_jni_set_android_app_ctx give ffmpeg's MediaCodec
 *   hwdec a JVM and an Android app Context. It is NOT a content:// opener —
 *   content-URI resolution is done in Kotlin via fd://<fd> instead.
 *
 * A dedicated thread pumps mpv_wait_event and forwards events to Kotlin, so a
 * blocking get_property on the main thread can never deadlock the engine.
 */

#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <locale.h>
#include <pthread.h>
#include <atomic>

#include <mpv/client.h>

/* ffmpeg's JNI entry points. Declared here rather than pulling in
 * <libavcodec/jni.h> so this file does not depend on ffmpeg headers being on
 * the include path — the symbols resolve at link time against libavcodec.so,
 * which libmpv already ships. av_jni_set_android_app_ctx gives MediaCodec hwdec
 * an app Context; it is NOT a content:// opener. */
extern "C" {
int av_jni_set_java_vm(void *vm, void *log_ctx);
int av_jni_set_android_app_ctx(void *app_ctx, void *log_ctx);
}

#include <android/log.h>

#define TAG "NovaMpv"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define ALOGV(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define CHECK(cond, ...) do { if (!(cond)) { ALOGE(__VA_ARGS__); return; } } while (0)

static JavaVM *g_vm = NULL;
static mpv_handle *g_mpv = NULL;
static jobject g_appctx = NULL;
static pthread_t g_event_thread;
static std::atomic_bool g_event_exit{false};
static bool g_thread_started = false;
static jobject g_surface = NULL;
static jclass g_cb_class = NULL;
static jmethodID g_cb_event = NULL;

/* ---------------------------------------------------------------- helpers */

static JNIEnv *get_env() {
    JNIEnv *env = NULL;
    if (g_vm && g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    return NULL;
}

/* ------------------------------------------------------------ event pump */

static void emit_message(JNIEnv *env, const char *name, const char *value) {
    if (!g_cb_class || !g_cb_event) return;
    jstring jname = env->NewStringUTF(name);
    jstring jvalue = value ? env->NewStringUTF(value) : NULL;
    env->CallStaticVoidMethod(g_cb_class, g_cb_event, jname, jvalue);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    env->DeleteLocalRef(jname);
    if (jvalue) env->DeleteLocalRef(jvalue);
}

static void dispatch_event(JNIEnv *env, mpv_event *ev) {
    if (!g_cb_class || !g_cb_event) return;

    switch (ev->event_id) {
        case MPV_EVENT_END_FILE: {
            auto *end = static_cast<mpv_event_end_file *>(ev->data);
            if (end && end->reason == MPV_END_FILE_REASON_ERROR)
                emit_message(env, "@load-error", mpv_error_string(end->error));
            emit_message(env, "@event", "end-file");
            break;
        }
        case MPV_EVENT_PROPERTY_CHANGE: {
            mpv_event_property *prop = (mpv_event_property *) ev->data;
            if (!prop || !prop->name) break;

            /* Always hand Kotlin two Strings (name, value). A null value means
             * "property unavailable". Stringifying here keeps exactly one JNI
             * method signature instead of one per mpv_format — passing a jlong
             * through a (String,String) method ID would misread the bits. */
            char buf[64];
            const char *val = NULL;
            switch (prop->format) {
                case MPV_FORMAT_STRING:
                    val = prop->data ? *(char **) prop->data : "";
                    break;
                case MPV_FORMAT_FLAG:
                    snprintf(buf, sizeof buf, "%d", (*(int *) prop->data) != 0);
                    val = buf;
                    break;
                case MPV_FORMAT_INT64:
                    snprintf(buf, sizeof buf, "%lld", (long long) (*(int64_t *) prop->data));
                    val = buf;
                    break;
                case MPV_FORMAT_DOUBLE:
                    snprintf(buf, sizeof buf, "%.6f", *(double *) prop->data);
                    val = buf;
                    break;
                default:
                    val = NULL; /* MPV_FORMAT_NONE */
                    break;
            }

            jstring jname = env->NewStringUTF(prop->name);
            jstring jval = val ? env->NewStringUTF(val) : NULL;
            env->CallStaticVoidMethod(g_cb_class, g_cb_event, jname, jval);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jname);
            if (jval) env->DeleteLocalRef(jval);
            break;
        }
        case MPV_EVENT_LOG_MESSAGE: {
            mpv_event_log_message *m = (mpv_event_log_message *) ev->data;
            /* libmpv reports load failures here, not as a command return code —
             * the same lesson the desktop learned with mpv's stdout. */
            if (m && m->text) {
                int prio = ANDROID_LOG_INFO;
                if (m->log_level <= MPV_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
                else if (m->log_level <= MPV_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
                __android_log_print(prio, "mpv", "[%s] %s", m->prefix ? m->prefix : "", m->text);
                if (m->log_level <= MPV_LOG_LEVEL_ERROR) {
                    char message[2048];
                    snprintf(message, sizeof message, "[%s] %s", m->prefix ? m->prefix : "mpv", m->text);
                    emit_message(env, "@log-error", message);
                }
            }
            break;
        }
        default: {
            /* Forward named events (file-loaded, end-file, ...) as a string on
             * the special name "@event". */
            const char *name = mpv_event_name(ev->event_id);
            if (name) {
                jstring jname = env->NewStringUTF("@event");
                jstring jval = env->NewStringUTF(name);
                env->CallStaticVoidMethod(g_cb_class, g_cb_event, jname, jval);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                env->DeleteLocalRef(jname);
                env->DeleteLocalRef(jval);
            }
            break;
        }
    }
}

static void *event_thread(void *) {
    JNIEnv *env = NULL;
    /* Attach this thread to the JVM so it can call back into Kotlin. */
    if (g_vm->AttachCurrentThread(&env, NULL) != JNI_OK) {
        ALOGE("event thread failed to attach to JVM");
        return NULL;
    }
    while (!g_event_exit.load() && g_mpv) {
        mpv_event *ev = mpv_wait_event(g_mpv, -1.0);
        if (!ev || ev->event_id == MPV_EVENT_NONE) continue;
        if (ev->event_id == MPV_EVENT_SHUTDOWN) break;
        dispatch_event(env, ev);
    }
    g_vm->DetachCurrentThread();
    return NULL;
}

/* -------------------------------------------------------------- lifecycle */

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_create(JNIEnv *env, jclass, jobject appctx) {
    CHECK(!g_mpv, "mpv is already created");

    env->GetJavaVM(&g_vm);
    setlocale(LC_NUMERIC, "C");

    if (g_appctx) env->DeleteGlobalRef(g_appctx);
    g_appctx = env->NewGlobalRef(appctx);

    /* MediaCodec hwdec needs the JVM and an app Context. */
    av_jni_set_java_vm((void *) g_vm, NULL);
    av_jni_set_android_app_ctx((void *) g_appctx, NULL);

    g_mpv = mpv_create();
    CHECK(g_mpv, "mpv_create failed");

    mpv_request_log_messages(g_mpv, "terminal-default");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sadik_novaplayer_core_MpvNative_initialize(JNIEnv *env, jclass, jclass callback) {
    if (!g_mpv) return MPV_ERROR_UNINITIALIZED;

    if (g_cb_class) env->DeleteGlobalRef(g_cb_class);
    g_cb_class = (jclass) env->NewGlobalRef(callback);
    g_cb_event = env->GetStaticMethodID(g_cb_class, "onEvent",
                                       "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!g_cb_event) {
        ALOGE("onEvent(String,String) not found");
        return MPV_ERROR_GENERIC;
    }

    int init_result = mpv_initialize(g_mpv);
    if (init_result < 0) {
        ALOGE("mpv_initialize failed");
        return init_result;
    }

    g_event_exit.store(false);
    if (pthread_create(&g_event_thread, NULL, event_thread, NULL) != 0) {
        ALOGE("event thread create failed");
        return MPV_ERROR_GENERIC;
    }
    g_thread_started = true;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_destroy(JNIEnv *, jclass) {
    if (!g_mpv) return;

    g_event_exit.store(true);
    mpv_wakeup(g_mpv);
    if (g_thread_started) pthread_join(g_event_thread, NULL);
    g_thread_started = false;

    mpv_terminate_destroy(g_mpv);
    g_mpv = NULL;

    JNIEnv *env = get_env();
    if (env) {
        if (g_surface) { env->DeleteGlobalRef(g_surface); g_surface = NULL; }
        if (g_cb_class) { env->DeleteGlobalRef(g_cb_class); g_cb_class = NULL; }
        if (g_appctx) { env->DeleteGlobalRef(g_appctx); g_appctx = NULL; }
    }
}

/* ------------------------------------------------------------- rendering */

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_attachSurface(JNIEnv *env, jclass, jobject surface) {
    CHECK(g_mpv, "mpv not created");
    jobject held = env->NewGlobalRef(surface);
    if (!held) return;
    int64_t wid = (int64_t) (intptr_t) held;
    int r = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) ALOGE("mpv_set_option(wid) = %s", mpv_error_string(r));
    if (r < 0) { env->DeleteGlobalRef(held); return; }
    if (g_surface) env->DeleteGlobalRef(g_surface);
    g_surface = held;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_detachSurface(JNIEnv *env, jclass) {
    CHECK(g_mpv, "mpv not created");
    int64_t wid = 0;
    int r = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) ALOGE("mpv_set_option(wid=0) = %s", mpv_error_string(r));
    if (r >= 0 && g_surface) { env->DeleteGlobalRef(g_surface); g_surface = NULL; }
}

/* --------------------------------------------------------- options/cmds */

extern "C" JNIEXPORT jint JNICALL
Java_com_sadik_novaplayer_core_MpvNative_setOptionString(JNIEnv *env, jclass,
                                                         jstring jname, jstring jvalue) {
    if (!g_mpv) return MPV_ERROR_UNINITIALIZED;
    const char *name = env->GetStringUTFChars(jname, NULL);
    const char *value = env->GetStringUTFChars(jvalue, NULL);
    int r = mpv_set_option_string(g_mpv, name, value);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jvalue, value);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sadik_novaplayer_core_MpvNative_command(JNIEnv *env, jclass, jobjectArray jargs) {
    if (!g_mpv) return MPV_ERROR_UNINITIALIZED;
    jsize n = env->GetArrayLength(jargs);
    /* mpv_command wants a NULL-terminated argv. */
    const char **argv = (const char **) calloc((size_t) n + 1, sizeof(char *));
    jstring *held = (jstring *) calloc((size_t) n, sizeof(jstring));
    if (!argv || !held) { free((void *) argv); free(held); return MPV_ERROR_NOMEM; }

    for (jsize i = 0; i < n; i++) {
        held[i] = (jstring) env->GetObjectArrayElement(jargs, i);
        argv[i] = env->GetStringUTFChars(held[i], NULL);
    }
    int result = mpv_command(g_mpv, (const char **) argv);
    for (jsize i = 0; i < n; i++) {
        env->ReleaseStringUTFChars(held[i], argv[i]);
        env->DeleteLocalRef(held[i]);
    }
    free((void *) argv);
    free(held);
    return result;
}

/* ------------------------------------------------------------ properties */

#define GETPROP_BODY(fmt, ctype, jtype, call)                              \
    if (!g_mpv) return (jtype) 0;                                        \
    const char *name = env->GetStringUTFChars(jname, NULL);                \
    ctype val = 0;                                                         \
    int r = mpv_get_property(g_mpv, name, fmt, &val);                      \
    env->ReleaseStringUTFChars(jname, name);                               \
    if (r < 0) return (jtype) 0;                                           \
    return (jtype) (call);                                                 \

extern "C" JNIEXPORT jlong JNICALL
Java_com_sadik_novaplayer_core_MpvNative_getPropertyLong(JNIEnv *env, jclass, jstring jname) {
    GETPROP_BODY(MPV_FORMAT_INT64, int64_t, jlong, val)
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_sadik_novaplayer_core_MpvNative_getPropertyDouble(JNIEnv *env, jclass, jstring jname) {
    GETPROP_BODY(MPV_FORMAT_DOUBLE, double, jdouble, val)
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sadik_novaplayer_core_MpvNative_getPropertyFlag(JNIEnv *env, jclass, jstring jname) {
    if (!g_mpv) return JNI_FALSE;
    const char *name = env->GetStringUTFChars(jname, NULL);
    int val = 0;
    int r = mpv_get_property(g_mpv, name, MPV_FORMAT_FLAG, &val);
    env->ReleaseStringUTFChars(jname, name);
    return r < 0 ? JNI_FALSE : (val != 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sadik_novaplayer_core_MpvNative_getPropertyString(JNIEnv *env, jclass, jstring jname) {
    if (!g_mpv) return NULL;
    const char *name = env->GetStringUTFChars(jname, NULL);
    char *val = NULL;
    int r = mpv_get_property(g_mpv, name, MPV_FORMAT_STRING, &val);
    env->ReleaseStringUTFChars(jname, name);
    if (r < 0) return NULL;
    jstring out = env->NewStringUTF(val ? val : "");
    mpv_free(val);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_setPropertyLong(JNIEnv *env, jclass, jstring jname, jlong v) {
    if (!g_mpv) return;
    const char *name = env->GetStringUTFChars(jname, NULL);
    int64_t val = (int64_t) v;
    mpv_set_property(g_mpv, name, MPV_FORMAT_INT64, &val);
    env->ReleaseStringUTFChars(jname, name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_setPropertyDouble(JNIEnv *env, jclass, jstring jname, jdouble v) {
    if (!g_mpv) return;
    const char *name = env->GetStringUTFChars(jname, NULL);
    double val = v;
    mpv_set_property(g_mpv, name, MPV_FORMAT_DOUBLE, &val);
    env->ReleaseStringUTFChars(jname, name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_setPropertyFlag(JNIEnv *env, jclass, jstring jname, jboolean v) {
    if (!g_mpv) return;
    const char *name = env->GetStringUTFChars(jname, NULL);
    int val = v ? 1 : 0;
    mpv_set_property(g_mpv, name, MPV_FORMAT_FLAG, &val);
    env->ReleaseStringUTFChars(jname, name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_setPropertyString(JNIEnv *env, jclass, jstring jname, jstring jv) {
    if (!g_mpv) return;
    const char *name = env->GetStringUTFChars(jname, NULL);
    const char *val = env->GetStringUTFChars(jv, NULL);
    mpv_set_property_string(g_mpv, name, val);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jv, val);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_observeProperty(JNIEnv *env, jclass, jstring jname, jint fmt) {
    if (!g_mpv) return;
    const char *name = env->GetStringUTFChars(jname, NULL);
    mpv_observe_property(g_mpv, 0, name, (mpv_format) fmt);
    env->ReleaseStringUTFChars(jname, name);
}

// Independent decoder for cancellable, on-device subtitle alignment.
#include <vector>
#include <chrono>
extern "C" {
#include "fvad.h"
}
static std::atomic_bool sync_cancel{false};
extern "C" JNIEXPORT void JNICALL
Java_com_sadik_novaplayer_core_MpvNative_cancelAnalysis(JNIEnv*, jobject) { sync_cancel = true; }
extern "C" JNIEXPORT jint JNICALL
Java_com_sadik_novaplayer_core_MpvNative_extractAudio(JNIEnv* env, jobject, jstring jpath, jstring jaid, jstring jout) {
    sync_cancel = false;
    mpv_handle* handle = mpv_create(); if (!handle) return -1;
    const char* path=env->GetStringUTFChars(jpath,nullptr);
    const char* aid=env->GetStringUTFChars(jaid,nullptr);
    const char* output=env->GetStringUTFChars(jout,nullptr);
    const char* options[][2]={{"config","no"},{"vid","no"},{"sid","no"},{"vo","null"},{"ao","pcm"},{"ao-pcm-waveheader","no"},{"audio-format","s16"},{"audio-samplerate","16000"},{"audio-channels","mono"},{"keep-open","no"},{"idle","yes"},{"terminal","no"}};
    int result=0;
    for (auto &opt:options) {int r=mpv_set_option_string(handle,opt[0],opt[1]);if(r<0) result=r;}
    mpv_set_option_string(handle,"ao-pcm-file",output);
    mpv_set_option_string(handle,"aid",aid);
    if(result>=0)result=mpv_initialize(handle);
    if(result>=0){const char* args[]={"loadfile",path,nullptr};result=mpv_command(handle,args);}
    auto deadline=std::chrono::steady_clock::now()+std::chrono::minutes(15);
    while(result>=0 && !sync_cancel){
        mpv_event* event=mpv_wait_event(handle,.2);
        if(event->event_id==MPV_EVENT_END_FILE){auto* end=(mpv_event_end_file*)event->data;result=end->reason==MPV_END_FILE_REASON_ERROR?end->error:0;break;}
        if(event->event_id==MPV_EVENT_SHUTDOWN){result=-1;break;}
        if(std::chrono::steady_clock::now()>deadline){result=-1;break;}
    }
    if(sync_cancel)result=-1000;
    mpv_terminate_destroy(handle);
    env->ReleaseStringUTFChars(jpath,path);env->ReleaseStringUTFChars(jaid,aid);env->ReleaseStringUTFChars(jout,output);
    return result;
}
// Mode 3 (most aggressive): mode 2 marks ~70% of a TV episode as speech (music, effects),
// which leaves the aligner too little to go on; mode 3 marks ~37% and aligns far better.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sadik_novaplayer_core_MpvNative_detectSpeech(JNIEnv* env,jobject,jstring jpath) {
    const char* path=env->GetStringUTFChars(jpath,nullptr);FILE* file=fopen(path,"rb");env->ReleaseStringUTFChars(jpath,path);
    std::vector<double> intervals;
    if(file){Fvad* vad=fvad_new();fvad_set_sample_rate(vad,16000);fvad_set_mode(vad,3);int16_t samples[480];double time=0,start=-1,last=0;
        while(!sync_cancel && fread(samples,sizeof(int16_t),480,file)==480){bool voiced=fvad_process(vad,samples,480)==1;if(voiced){if(start<0)start=time;last=time+.03;}else if(start>=0 && time-last>.18){if(last-start>=.09){intervals.push_back(start);intervals.push_back(last);}start=-1;}time+=.03;}
        if(start>=0 && last>start){intervals.push_back(start);intervals.push_back(last);}fvad_free(vad);fclose(file);
    }
    jdoubleArray out=env->NewDoubleArray(intervals.size());if(!intervals.empty())env->SetDoubleArrayRegion(out,0,intervals.size(),intervals.data());return out;
}
