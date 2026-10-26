/*
 * eSpeak NG 的 JNI 桥：把中文文本合成成 16bit 单声道 PCM，交给 Kotlin 侧用 AudioTrack 播放。
 *
 * 为什么自己写这一层：espeak-ng 的音频输出后端（pcaudiolib）在 Android 上没有可用实现，
 * 所以我们用 AUDIO_OUTPUT_SYNCHRONOUS 让合成同步跑，把回调里的 PCM 攒起来一次性返回，
 * 播放完全由 App 控制（暂停、语速、进度都好做）。
 *
 * v1.6.3 修的两个坑：
 * 1. espeak_Initialize 的 options 以前传 0，而 espeak_api.c 里初始化失败会直接 exit(1) ——
 *    在 App 里等于瞬间杀进程，Java 层连堆栈都留不下。现在传 espeakINITIALIZE_DONT_EXIT，
 *    失败只返回负数，交给 Kotlin 侧显示提示。
 * 2. 缓冲区以前只增不减、异常路径也不复位；现在加了上限，每次合成前强制复位。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include "espeak-ng/speak_lib.h"

/* 单次合成最多缓存多少采样：22050Hz 下约 6 分钟，正常一句话远到不了，纯粹防跑飞 */
#define MAX_SAMPLES (8u * 1024u * 1024u)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static short *g_pcm = NULL;
static size_t g_len = 0;   /* 已写入的采样数 */
static size_t g_cap = 0;   /* 缓冲区容量（采样数） */
static int g_inited = 0;
static int g_voice_ok = 0;
static int g_sample_rate = 22050;

static int append_samples(const short *wav, int count) {
    if (count <= 0) return 0;
    if (g_len + (size_t) count > MAX_SAMPLES) return 1; /* 超限直接中止这次合成 */
    if (g_len + (size_t) count > g_cap) {
        size_t cap = (g_len + (size_t) count) * 2;
        if (cap > MAX_SAMPLES) cap = MAX_SAMPLES;
        short *grown = (short *) realloc(g_pcm, cap * sizeof(short));
        if (grown == NULL) return 1;
        g_pcm = grown;
        g_cap = cap;
    }
    memcpy(g_pcm + g_len, wav, (size_t) count * sizeof(short));
    g_len += (size_t) count;
    return 0;
}

/* 合成回调：espeak 边合成边回调，这里只负责收集 */
static int synth_callback(short *wav, int numsamples, espeak_EVENT *events) {
    (void) events;
    if (wav != NULL && numsamples > 0) {
        if (append_samples(wav, numsamples) != 0) return 1; /* 1 = abort */
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_fanqie_wear_tts_EspeakEngine_nativeInit(JNIEnv *env, jobject thiz, jstring data_parent) {
    (void) thiz;
    if (data_parent == NULL) return -1;
    pthread_mutex_lock(&g_lock);

    if (g_inited) {
        espeak_Terminate();
        g_inited = 0;
        g_voice_ok = 0;
    }

    const char *path = (*env)->GetStringUTFChars(env, data_parent, NULL);
    if (path == NULL) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }

    /* DONT_EXIT 是关键：否则 espeak 内部初始化失败会 exit(1)，直接杀掉整个 App */
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 200, path,
                                 espeakINITIALIZE_DONT_EXIT);
    (*env)->ReleaseStringUTFChars(env, data_parent, path);

    if (rate <= 0) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }

    g_sample_rate = rate;
    espeak_SetSynthCallback(synth_callback);
    g_inited = 1;
    g_len = 0;
    pthread_mutex_unlock(&g_lock);
    return rate;
}

/* 设置音色。放在锁里，避免和正在合成的线程抢 espeak 全局状态 */
JNIEXPORT jint JNICALL
Java_com_fanqie_wear_tts_EspeakEngine_nativeSetVoice(JNIEnv *env, jobject thiz, jstring name) {
    (void) thiz;
    if (name == NULL) return -1;
    pthread_mutex_lock(&g_lock);
    if (!g_inited) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    const char *voice = (*env)->GetStringUTFChars(env, name, NULL);
    if (voice == NULL) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    int rc = espeak_SetVoiceByName(voice);
    (*env)->ReleaseStringUTFChars(env, name, voice);
    if (rc == EE_OK) g_voice_ok = 1;
    pthread_mutex_unlock(&g_lock);
    return rc == EE_OK ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_fanqie_wear_tts_EspeakEngine_nativeSetParams(JNIEnv *env, jobject thiz, jint rate, jint pitch) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    if (!g_inited) {
        pthread_mutex_unlock(&g_lock);
        return -1;
    }
    espeak_SetParameter(espeakRATE, rate, 0);
    espeak_SetParameter(espeakPITCH, pitch, 0);
    pthread_mutex_unlock(&g_lock);
    return 0;
}

/*
 * 合成一段文本，返回 PCM（16bit 单声道）。
 * 合成期间持有锁，保证 native 侧不会并发写同一块缓冲区。
 * 音色没设成功时直接返回 NULL —— 用默认音色合成时如果缺词典，espeak 会反复报错，
 * 宁可这里就失败，让上层给出明确提示。
 */
JNIEXPORT jshortArray JNICALL
Java_com_fanqie_wear_tts_EspeakEngine_nativeSynth(
        JNIEnv *env, jobject thiz, jstring text, jint rate, jint pitch) {
    (void) thiz;
    if (text == NULL) return NULL;

    pthread_mutex_lock(&g_lock);
    if (!g_inited || !g_voice_ok) {
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    g_len = 0;
    espeak_SetParameter(espeakRATE, rate, 0);
    espeak_SetParameter(espeakPITCH, pitch, 0);

    const char *utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (utf8 == NULL) {
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    espeak_Synth(utf8, strlen(utf8) + 1, 0, POS_CHARACTER, 0,
                 espeakCHARS_UTF8, NULL, NULL);
    /* SYNCHRONOUS 模式下这里已经把整段合成完并回调完了 */
    (*env)->ReleaseStringUTFChars(env, text, utf8);

    jshortArray out = NULL;
    if (g_len > 0) {
        out = (*env)->NewShortArray(env, (jsize) g_len);
        if (out != NULL) {
            (*env)->SetShortArrayRegion(env, out, 0, (jsize) g_len, g_pcm);
        }
    }
    g_len = 0;
    pthread_mutex_unlock(&g_lock);
    return out;
}

JNIEXPORT void JNICALL
Java_com_fanqie_wear_tts_EspeakEngine_nativeRelease(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    if (g_inited) {
        espeak_Terminate();
        g_inited = 0;
        g_voice_ok = 0;
    }
    free(g_pcm);
    g_pcm = NULL;
    g_len = 0;
    g_cap = 0;
    pthread_mutex_unlock(&g_lock);
}

/* 只回一个静态版本串，不碰引擎内部状态（避免和初始化线程抢资源） */
JNIEXPORT jstring JNICALL
Java_com_fanqie_wear_tts_EspeakEngine_nativeVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, "1.52.0");
}
