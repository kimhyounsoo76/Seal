package com.junkfood.seal.util

import com.junkfood.seal.App
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * 유튜브는 영상 주소를 풀 때 자바스크립트 계산(n 챌린지)을 요구한다.
 * 앱에 함께 넣은 QuickJS(libqjs.so)를 yt-dlp가 쓰도록 알려 준다.
 * 없으면 아무것도 하지 않으므로 기존 동작은 그대로다.
 */
object JsRuntime {
    private val qjs: File? by lazy {
        runCatching {
            File(App.context.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.exists() }
        }.getOrNull()
    }

    fun apply(request: YoutubeDLRequest) {
        val bin = qjs ?: return
        request.addOption("--js-runtimes", "quickjs:${bin.absolutePath}")
        // 풀이 스크립트가 엔진에 없을 때 GitHub에서 받아 쓰도록 허용
        request.addOption("--remote-components", "ejs:github")
    }
}
