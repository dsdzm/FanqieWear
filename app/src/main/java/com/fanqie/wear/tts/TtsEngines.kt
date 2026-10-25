package com.fanqie.wear.tts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech

/**
 * TTS 引擎安装引导。
 *
 * 手表 ROM 经常把语音引擎删掉（尤其是国内手表），App 自己没法内置一个完整的 TTS 引擎，
 * 所以这里做两件事：
 * 1. 检测当前有没有可用引擎、有没有中文语音包；
 * 2. 给出一键跳转：应用商店安装引擎 / 系统语音设置下载离线语音包。
 */
object TtsEngines {

    data class Candidate(val pkg: String, val label: String, val note: String)

    val candidates = listOf(
        Candidate("com.google.android.tts", "Google 文字转语音", "支持中文，Play 商店可装"),
        Candidate("com.iflytek.speechsuite", "讯飞语记 / 语音引擎", "国内手表常用，商店搜索安装"),
        Candidate("com.baidu.duersdk.opensdk", "百度语音引擎", "部分国产 ROM 预装"),
    )

    fun installedPackages(context: Context): Set<String> = candidates
        .filter { isInstalled(context, it.pkg) }
        .map { it.pkg }
        .toSet()

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** 当前系统默认引擎的包名，取不到就返回 null */
    fun defaultEngine(context: Context): String? = runCatching {
        val tts = TextToSpeech(context) {}
        val e = tts.engines.firstOrNull()?.name
        tts.shutdown()
        e
    }.getOrNull()

    /** 打开应用商店的引擎详情页；没有商店就退回网页 */
    fun openStore(context: Context, pkg: String): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(market); true }
            .recoverCatching { context.startActivity(web); true }
            .getOrDefault(false)
    }

    /** 系统「文字转语音」设置：下载离线语音包、切换引擎都在这里 */
    fun openSettings(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(direct); true }
            .recoverCatching { context.startActivity(tts); true }
            .recoverCatching { context.startActivity(fallback); true }
            .getOrDefault(false)
    }
}
