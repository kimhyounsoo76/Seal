package com.junkfood.seal.util

import com.junkfood.seal.App
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 유튜브는 영상 주소를 풀 때 자바스크립트 계산(n 챌린지)을 요구한다.
 * 앱에 함께 넣은 QuickJS(libqjs.so)를 yt-dlp가 쓰도록 알려 준다.
 *
 * --js-runtimes 옵션은 yt-dlp 2025.11.12부터 있다. 처음 설치한 직후처럼
 * 엔진이 옛 버전이면 이 옵션 때문에 "no such option"으로 받기가 통째로 실패하므로,
 * 먼저 엔진을 최신으로 올려 보고, 그래도 옛 버전이면 옵션을 넣지 않는다.
 */
object JsRuntime {
    private const val MIN_VERSION = "2025.11.12"

    private val qjs: File? by lazy {
        runCatching {
            File(App.context.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.exists() }
        }.getOrNull()
    }

    @Volatile private var supported: Boolean? = null

    private fun currentVersion(): String =
        runCatching { YoutubeDL.getInstance().version(App.context) }.getOrNull().orEmpty().trim()

    private fun isNew(v: String) = v.isNotEmpty() && v >= MIN_VERSION

    @Synchronized
    private fun checkSupported(): Boolean {
        supported?.let { return it }
        var v = currentVersion()
        if (!isNew(v)) {
            // 엔진이 옛 버전이면 한 번 업데이트를 시도한다
            runCatching { runBlocking { UpdateUtil.updateYtDlp() } }
            v = currentVersion()
        }
        if (v.isNotEmpty()) PreferenceUtil.encodeString(YT_DLP_VERSION, v)
        return isNew(v).also { if (it) supported = true }
    }

    fun apply(request: YoutubeDLRequest) {
        val bin = qjs ?: return
        if (!checkSupported()) return
        request.addOption("--js-runtimes", "quickjs:${bin.absolutePath}")
        // 풀이 스크립트가 엔진에 없을 때 GitHub에서 받아 쓰도록 허용
        request.addOption("--remote-components", "ejs:github")
    }
}
