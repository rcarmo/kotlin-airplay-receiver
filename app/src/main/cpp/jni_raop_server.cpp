#include <jni.h>
#include <pthread.h>
#include <stddef.h>
#include <cstring>
#include <cstdlib>

#include "lib/raop.h"
#include "log.h"
#include "lib/stream.h"
#include "lib/logger.h"

static JavaVM* g_JavaVM;
static pthread_key_t g_JniEnvKey;
static pthread_once_t g_JniEnvKeyOnce = PTHREAD_ONCE_INIT;

struct raop_jni_context {
    jobject server;
    jmethodID on_recv_audio_data;
    jmethodID on_recv_video_data;
    jmethodID get_video_width;
    jmethodID get_video_height;
    jmethodID on_stream_stopped;
    jmethodID on_set_audio_volume;
    jmethodID on_audio_flush;
    jmethodID on_audio_metadata;
    jmethodID on_audio_coverart;
    jmethodID on_audio_remote_control_id;
    jmethodID on_audio_progress;
    jmethodID should_accept_sender;
    jmethodID is_verbose_logging_enabled;
};

static void DetachJniThread(void*) {
    if (g_JavaVM != NULL) {
        g_JavaVM->DetachCurrentThread();
    }
}

static void CreateJniEnvKey() {
    pthread_key_create(&g_JniEnvKey, DetachJniThread);
}

static JNIEnv* GetJniEnv() {
    JNIEnv* env = NULL;
    if (g_JavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }

    pthread_once(&g_JniEnvKeyOnce, CreateJniEnvKey);
    if (g_JavaVM->AttachCurrentThread(&env, NULL) != JNI_OK) {
        return NULL;
    }
    pthread_setspecific(g_JniEnvKey, reinterpret_cast<void*>(1));
    return env;
}

static void* CopyPacketData(const void* data, size_t size) {
    void* buffer = malloc(size);
    if (buffer == NULL) {
        return NULL;
    }
    memcpy(buffer, data, size);
    return buffer;
}

void OnRecvAudioData(void *observer, pcm_data_struct *data) {
    raop_jni_context* context = static_cast<raop_jni_context*>(observer);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL) {
        return;
    }

    const int byteSize = data->data_len * static_cast<int>(sizeof(unsigned short));
    void* buffer = CopyPacketData(data->data, static_cast<size_t>(byteSize));
    if (buffer == NULL) {
        return;
    }
    jobject byteBuffer = jniEnv->NewDirectByteBuffer(buffer, byteSize);
    if (byteBuffer == NULL) {
        free(buffer);
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_recv_audio_data, byteBuffer, byteSize,
                           reinterpret_cast<jlong>(buffer), static_cast<jlong>(data->pts));
    jniEnv->DeleteLocalRef(byteBuffer);
}


void OnRecvVideoData(void *observer, h264_decode_struct *data) {
    raop_jni_context* context = static_cast<raop_jni_context*>(observer);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL) {
        return;
    }

    void* buffer = CopyPacketData(data->data, static_cast<size_t>(data->data_len));
    if (buffer == NULL) {
        return;
    }
    jobject byteBuffer = jniEnv->NewDirectByteBuffer(buffer, data->data_len);
    if (byteBuffer == NULL) {
        free(buffer);
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_recv_video_data, byteBuffer, data->data_len,
                           reinterpret_cast<jlong>(buffer), data->frame_type,
                           static_cast<jlong>(data->nTimeStamp), static_cast<jlong>(data->pts));
    jniEnv->DeleteLocalRef(byteBuffer);
}

extern "C" void
audio_process(void *cls, pcm_data_struct *data)
{
    OnRecvAudioData(cls, data);
}

extern "C" int
video_width(void *cls)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->get_video_width == NULL) {
        return 1280;
    }
    int width = jniEnv->CallIntMethod(context->server, context->get_video_width);
    return width > 0 ? width : 1280;
}

extern "C" int
video_height(void *cls)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->get_video_height == NULL) {
        return 720;
    }
    int height = jniEnv->CallIntMethod(context->server, context->get_video_height);
    return height > 0 ? height : 720;
}

extern "C" int
sender_should_connect(void *cls, const char *sender_id, const char *display_name)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->should_accept_sender == NULL) {
        return 1;
    }
    jstring id = sender_id ? jniEnv->NewStringUTF(sender_id) : NULL;
    jstring name = display_name ? jniEnv->NewStringUTF(display_name) : NULL;
    jboolean accepted = jniEnv->CallBooleanMethod(context->server, context->should_accept_sender, id, name);
    if (id != NULL) {
        jniEnv->DeleteLocalRef(id);
    }
    if (name != NULL) {
        jniEnv->DeleteLocalRef(name);
    }
    return accepted == JNI_TRUE ? 1 : 0;
}

extern "C" void
audio_set_volume(void *cls, void *opaque, float volume)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_set_audio_volume == NULL) {
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_set_audio_volume, static_cast<jfloat>(volume));
}

extern "C" void
audio_flush(void *cls, void *opaque)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_audio_flush == NULL) {
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_audio_flush);
}

extern "C" void
audio_set_metadata(void *cls, void *opaque, const void *buffer, int buflen)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_audio_metadata == NULL || buffer == NULL || buflen <= 0) {
        return;
    }
    jbyteArray bytes = jniEnv->NewByteArray(buflen);
    if (bytes == NULL) {
        return;
    }
    jniEnv->SetByteArrayRegion(bytes, 0, buflen, reinterpret_cast<const jbyte*>(buffer));
    jniEnv->CallVoidMethod(context->server, context->on_audio_metadata, bytes);
    jniEnv->DeleteLocalRef(bytes);
}

extern "C" void
audio_set_coverart(void *cls, void *opaque, const void *buffer, int buflen)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_audio_coverart == NULL || buffer == NULL || buflen <= 0) {
        return;
    }
    jbyteArray bytes = jniEnv->NewByteArray(buflen);
    if (bytes == NULL) {
        return;
    }
    jniEnv->SetByteArrayRegion(bytes, 0, buflen, reinterpret_cast<const jbyte*>(buffer));
    jniEnv->CallVoidMethod(context->server, context->on_audio_coverart, bytes);
    jniEnv->DeleteLocalRef(bytes);
}

extern "C" void
audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_audio_remote_control_id == NULL) {
        return;
    }
    jstring dacp = dacp_id ? jniEnv->NewStringUTF(dacp_id) : NULL;
    jstring active = active_remote_header ? jniEnv->NewStringUTF(active_remote_header) : NULL;
    jniEnv->CallVoidMethod(context->server, context->on_audio_remote_control_id, dacp, active);
    if (dacp != NULL) {
        jniEnv->DeleteLocalRef(dacp);
    }
    if (active != NULL) {
        jniEnv->DeleteLocalRef(active);
    }
}

extern "C" void
audio_set_progress(void *cls, void *opaque, unsigned int start, unsigned int curr, unsigned int end)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_audio_progress == NULL) {
        return;
    }
    jniEnv->CallVoidMethod(
        context->server,
        context->on_audio_progress,
        static_cast<jlong>(start),
        static_cast<jlong>(curr),
        static_cast<jlong>(end)
    );
}

extern "C" void
stream_stopped(void *cls)
{
    raop_jni_context* context = static_cast<raop_jni_context*>(cls);
    JNIEnv* jniEnv = GetJniEnv();
    if (jniEnv == NULL || context == NULL || context->on_stream_stopped == NULL) {
        return;
    }
    jniEnv->CallVoidMethod(context->server, context->on_stream_stopped);
}

extern "C" void
video_process(void *cls, h264_decode_struct *data)
{
    OnRecvVideoData(cls, data);
}

extern "C" void
log_callback(void *cls, int level, const char *msg) {
    switch (level) {
        case LOGGER_DEBUG: {
            LOGD("%s", msg);
            break;
        }
        case LOGGER_INFO:
        case LOGGER_NOTICE: {
            LOGI("%s", msg);
            break;
        }
        case LOGGER_WARNING: {
            LOGW("%s", msg);
            break;
        }
        case LOGGER_ERR: {
            LOGE("%s", msg);
            break;
        }
        default:break;
    }

}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_JavaVM = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_carmo_airplay_receiver_RaopServer_start(JNIEnv* env, jobject object) {
    raop_jni_context* context = new raop_jni_context();
    context->server = env->NewGlobalRef(object);

    jclass cls = env->GetObjectClass(object);
    context->on_recv_audio_data = env->GetMethodID(cls, "onRecvAudioData", "(Ljava/nio/ByteBuffer;IJJ)V");
    context->on_recv_video_data = env->GetMethodID(cls, "onRecvVideoData", "(Ljava/nio/ByteBuffer;IJIJJ)V");
    context->get_video_width = env->GetMethodID(cls, "getVideoWidth", "()I");
    context->get_video_height = env->GetMethodID(cls, "getVideoHeight", "()I");
    context->on_stream_stopped = env->GetMethodID(cls, "onStreamStopped", "()V");
    context->on_set_audio_volume = env->GetMethodID(cls, "onSetAudioVolume", "(F)V");
    context->on_audio_flush = env->GetMethodID(cls, "onAudioFlush", "()V");
    context->on_audio_metadata = env->GetMethodID(cls, "onAudioMetadata", "([B)V");
    context->on_audio_coverart = env->GetMethodID(cls, "onAudioCoverArt", "([B)V");
    context->on_audio_remote_control_id = env->GetMethodID(cls, "onAudioRemoteControlId", "(Ljava/lang/String;Ljava/lang/String;)V");
    context->on_audio_progress = env->GetMethodID(cls, "onAudioProgress", "(JJJ)V");
    context->should_accept_sender = env->GetMethodID(cls, "shouldAcceptSender", "(Ljava/lang/String;Ljava/lang/String;)Z");
    context->is_verbose_logging_enabled = env->GetMethodID(cls, "isVerboseLoggingEnabled", "()Z");
    env->DeleteLocalRef(cls);

    if (context->server == NULL ||
        context->on_recv_audio_data == NULL ||
        context->on_recv_video_data == NULL ||
        context->get_video_width == NULL ||
        context->get_video_height == NULL ||
        context->on_stream_stopped == NULL ||
        context->on_set_audio_volume == NULL ||
        context->on_audio_flush == NULL ||
        context->on_audio_metadata == NULL ||
        context->on_audio_coverart == NULL ||
        context->on_audio_remote_control_id == NULL ||
        context->on_audio_progress == NULL ||
        context->should_accept_sender == NULL ||
        context->is_verbose_logging_enabled == NULL) {
        if (context->server != NULL) {
            env->DeleteGlobalRef(context->server);
        }
        delete context;
        return 0;
    }

    raop_callbacks_t raop_cbs;
    memset(&raop_cbs, 0, sizeof(raop_cbs));
    raop_cbs.cls = context;
    raop_cbs.audio_process = audio_process;
    raop_cbs.video_width = video_width;
    raop_cbs.video_height = video_height;
    raop_cbs.sender_should_connect = sender_should_connect;
    raop_cbs.audio_flush = audio_flush;
    raop_cbs.audio_set_volume = audio_set_volume;
    raop_cbs.audio_set_metadata = audio_set_metadata;
    raop_cbs.audio_set_coverart = audio_set_coverart;
    raop_cbs.audio_remote_control_id = audio_remote_control_id;
    raop_cbs.audio_set_progress = audio_set_progress;
    raop_cbs.stream_stopped = stream_stopped;
    raop_cbs.video_process = video_process;

    raop_t *raop = raop_init(10, &raop_cbs);
    if (raop == NULL) {
        LOGE("raop = NULL");
        env->DeleteGlobalRef(context->server);
        delete context;
        return 0;
    }

    raop_set_log_callback(raop, log_callback, NULL);
    jboolean verbose_logging = env->CallBooleanMethod(context->server, context->is_verbose_logging_enabled);
    raop_set_log_level(raop, verbose_logging ? RAOP_LOG_DEBUG : RAOP_LOG_INFO);

    unsigned short port = 0;
    raop_start(raop, &port);
    raop_set_port(raop, port);
    LOGD("raop port = % d", raop_get_port(raop));
    return reinterpret_cast<jlong>(raop);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_carmo_airplay_receiver_RaopServer_getPort(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = reinterpret_cast<raop_t *>(opaque);
    return raop_get_port(raop);
}

extern "C" JNIEXPORT void JNICALL
Java_io_carmo_airplay_receiver_RaopServer_stop(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = reinterpret_cast<raop_t *>(opaque);
    raop_jni_context* context = static_cast<raop_jni_context*>(raop_get_callback_cls(raop));
    raop_destroy(raop);
    if (context != NULL) {
        env->DeleteGlobalRef(context->server);
        delete context;
    }
    LOGD("raop stopped");
}

extern "C" JNIEXPORT void JNICALL
Java_io_carmo_airplay_receiver_model_NativeMemory_free(JNIEnv* env, jobject object, jlong pointer) {
    (void) env;
    (void) object;
    if (pointer != 0) {
        free(reinterpret_cast<void*>(pointer));
    }
}
