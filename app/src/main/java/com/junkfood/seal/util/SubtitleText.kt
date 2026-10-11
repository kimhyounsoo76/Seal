package com.junkfood.seal.util

import android.util.Log
import com.junkfood.seal.util.PreferenceUtil.getInt
import java.io.File

/**
 * 받은 자막(SRT/VTT)을 읽기 편한 글(TXT)로 바꾼다.
 * 유튜브 자동 자막은 두 줄이 굴러 올라가는 방식이라 같은 줄이 여러 번 저장되는데, 그 중복을 없앤다.
 */
object SubtitleText {
    private const val TAG = "SubtitleText"

    /** 저장 형식: 0 = 영상용 자막(SRT)만, 1 = 읽기용 글(TXT)만, 2 = 둘 다 */
    const val OUTPUT_KEY = "ytget_sub_output"
    const val OUTPUT_SRT = 0
    const val OUTPUT_TXT = 1
    const val OUTPUT_BOTH = 2

    private val timeLine = Regex("""^\s*\d{1,2}:\d{2}(:\d{2})?[.,]\d{3}\s*-->.*$""")
    private val indexLine = Regex("""^\s*\d+\s*$""")
    private val tag = Regex("""<[^>]*>""")
    private val bracket = Regex("""\[[^\]]*]""")
    private val spaces = Regex("""\s+""")

    /** 다운로드 폴더에서 이번 영상의 자막 파일을 찾아 글로 바꾼다. 실패해도 다운로드에는 영향 없음 */
    fun processAfterDownload(downloadDir: String, videoId: String?, title: String?, startedAt: Long) {
        val mode = OUTPUT_KEY.getInt(OUTPUT_TXT)
        if (mode == OUTPUT_SRT) return
        runCatching {
            val dir = File(downloadDir)
            val stem = title?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            val subs =
                dir.listFiles()
                    ?.filter { f ->
                        f.isFile &&
                            (f.extension.equals("srt", true) || f.extension.equals("vtt", true)) &&
                            f.lastModified() >= startedAt - 60_000 &&
                            ((videoId != null && f.name.contains(videoId)) ||
                                (stem != null && f.name.startsWith(stem)))
                    }
                    .orEmpty()
            subs.forEach { sub ->
                val text = toPlainText(sub.readText())
                if (text.isNotBlank()) {
                    File(sub.parentFile, sub.nameWithoutExtension + ".txt").writeText(text)
                    if (mode == OUTPUT_TXT) sub.delete()
                }
            }
        }.onFailure { Log.e(TAG, "subtitle to text failed", it) }
    }

    fun toPlainText(raw: String): String {
        val lines = mutableListOf<String>()
        for (rawLine in raw.replace("\r", "").lineSequence()) {
            val l = rawLine.trim()
            if (l.isEmpty() || l == "WEBVTT" || l.startsWith("Kind:") || l.startsWith("Language:")) continue
            if (indexLine.matches(l) || timeLine.matches(l)) continue
            if (l.startsWith("NOTE") || l.startsWith("STYLE")) continue
            val clean = l.replace(tag, "").replace(bracket, "").replace(spaces, " ").trim()
            if (clean.isEmpty() || clean == ">>") {
                if (clean == ">>") lines.add(">>")
                continue
            }
            // 굴러 올라가는 자막: 바로 앞에 넣은 줄과 같으면 건너뜀
            if (lines.isNotEmpty() && lines.last() == clean) continue
            lines.add(clean)
        }
        // ">>"(말하는 사람이 바뀜)를 기준으로 문단을 나눠 한 덩어리 글로 만든다
        val out = StringBuilder()
        val para = StringBuilder()
        fun flush() {
            val p = para.toString().trim()
            if (p.isNotEmpty()) out.append(p).append("\n\n")
            para.clear()
        }
        for (l in lines) {
            var s = l
            if (s.startsWith(">>")) {
                flush()
                s = s.removePrefix(">>").trim()
            }
            if (s.isNotEmpty()) para.append(s).append(' ')
        }
        flush()
        return out.toString().trim() + "\n"
    }
}
