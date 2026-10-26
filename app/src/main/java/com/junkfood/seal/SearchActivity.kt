package com.junkfood.seal

import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.junkfood.seal.util.AUTO_SUBTITLE
import com.junkfood.seal.util.AUTO_TRANSLATED_SUBTITLES
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.JsRuntime
import com.junkfood.seal.util.OUTPUT_TEMPLATE
import com.junkfood.seal.util.CONVERT_SRT
import com.junkfood.seal.util.CONVERT_SUBTITLE
import com.junkfood.seal.util.EMBED_SUBTITLE
import com.junkfood.seal.util.EXTRACT_AUDIO
import com.junkfood.seal.util.AUDIO_CONVERT
import com.junkfood.seal.util.AUDIO_CONVERSION_FORMAT
import com.junkfood.seal.util.CONVERT_MP3
import com.junkfood.seal.util.DOWNLOAD_TYPE_INITIALIZATION
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.FORMAT_COMPATIBILITY
import com.junkfood.seal.util.FORMAT_QUALITY
import com.junkfood.seal.util.FORMAT_SELECTION
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.SubtitleText
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.USE_PREVIOUS_SELECTION
import com.junkfood.seal.util.VIDEO_FORMAT
import com.junkfood.seal.util.VIDEO_QUALITY
import com.junkfood.seal.util.YT_DLP_VERSION
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.SUBTITLE
import com.junkfood.seal.util.SUBTITLE_LANGUAGE
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 앱 첫 화면: 유튜브 검색 → 결과를 누르면 Seal의 받기 창이 바로 뜸 */
data class SearchItem(
    val id: String,
    val title: String,
    val channel: String,
    val duration: String,
    val thumb: String,
    val url: String,
)

class SearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SearchScreen(
                        onPick = { item -> openDownload(item.url) },
                        onUrl = { url -> openDownload(url) },
                        onOpenSeal = {
                            startActivity(Intent(this, MainActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    private fun openDownload(url: String) {
        val i = Intent(this, QuickDownloadActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(i)
    }
}

private fun fmtDuration(sec: Double): String {
    if (sec <= 0) return ""
    val s = sec.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

private suspend fun searchYoutube(query: String): List<SearchItem> = withContext(Dispatchers.IO) {
    val ydl = YoutubeDL.getInstance()
    // 앱 시작 직후엔 엔진 준비가 덜 됐을 수 있어 몇 번 기다림
    var lastError: Throwable? = null
    repeat(5) {
        try {
            runCatching { ydl.init(App.context) }
            val req = YoutubeDLRequest("ytsearch25:$query")
            JsRuntime.apply(req)
            req.addOption("--flat-playlist")
            req.addOption("--dump-single-json")
            req.addOption("--socket-timeout", "10")
            val out = ydl.execute(req, "search-${System.currentTimeMillis()}").out
            val entries = JSONObject(out).optJSONArray("entries") ?: return@withContext emptyList()
            val list = mutableListOf<SearchItem>()
            for (n in 0 until entries.length()) {
                val e = entries.optJSONObject(n) ?: continue
                val id = e.optString("id")
                if (id.length != 11) continue
                val rawUrl = e.optString("url")
                val isShorts = rawUrl.contains("/shorts/")
                val thumbs = e.optJSONArray("thumbnails")
                var thumb = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                if (thumbs != null && thumbs.length() > 0) {
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")?.let {
                        if (it.isNotBlank()) thumb = it
                    }
                }
                list.add(
                    SearchItem(
                        id = id,
                        title = e.optString("title", "(제목 없음)"),
                        channel = e.optString("channel", e.optString("uploader", "")),
                        duration = fmtDuration(e.optDouble("duration", 0.0)),
                        thumb = thumb,
                        url = if (isShorts) "https://www.youtube.com/shorts/$id"
                              else "https://www.youtube.com/watch?v=$id",
                    )
                )
            }
            return@withContext list.distinctBy { it.id }
        } catch (t: Throwable) {
            lastError = t
            delay(1500)
        }
    }
    throw lastError ?: IllegalStateException("검색 실패")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(onPick: (SearchItem) -> Unit, onUrl: (String) -> Unit, onOpenSeal: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val linkRegex = remember { Regex("""https?://\S+""") }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("낱말을 넣고 찾기를 누르세요. 결과를 누르면 미리 볼 수 있어요.\n영상 주소(링크)를 붙여넣고 받기를 누르면 바로 받아요.") }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var showSettings by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<SearchItem?>(null) }
    previewItem?.let { item ->
        PreviewDialog(
            item = item,
            onDismiss = { previewItem = null },
            onDownload = { previewItem = null; onPick(item) },
            onSubsOnly = { fetchSubtitlesOnly(item.url, scope) },
        )
    }
    var optionsKey by remember { mutableStateOf(0) }
    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false; optionsKey++ },
            onOpenAll = { showSettings = false; onOpenSeal() },
        )
    }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        // 주소(링크)를 넣었으면 검색하지 않고 바로 받기 창을 연다 (유튜브·인스타 릴스 등)
        linkRegex.find(q)?.let { m ->
            focus.clearFocus()
            onUrl(m.value.trimEnd(')', ']', '>', '"', '\'', ',', '.'))
            return
        }
        focus.clearFocus()
        loading = true
        message = ""
        scope.launch {
            try {
                val r = searchYoutube(q)
                results = r
                if (r.isEmpty()) message = "검색 결과가 없어요. 다른 낱말로 찾아보세요."
            } catch (t: Throwable) {
                results = emptyList()
                message = "검색하지 못했어요. 인터넷 연결을 확인하고 다시 해 보세요.\n(${t.message?.lineSequence()?.firstOrNull()?.take(120) ?: ""})"
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "영상 찾아받기",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showSettings = true }) { Text("⚙ 설정") }
            TextButton(onClick = onOpenSeal) { Text("받은 목록") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("찾을 낱말 또는 영상 주소") },
                trailingIcon = {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { query = it }
                    }) { Text("붙여넣기") }
                },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { doSearch() },
                enabled = !loading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(56.dp),
            ) { Text(if (linkRegex.containsMatchIn(query)) "받기" else "찾기") }
        }

        linkRegex.find(query)?.let { m ->
            OutlinedButton(
                onClick = { fetchSubtitlesOnly(m.value, scope) },
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            ) { Text("이 링크의 자막만 받기 (이미 받은 영상에 붙이기)") }
        }
        key(optionsKey) { QuickOptions() }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }

        // 휴대폰 1줄, 태블릿·가로 화면은 2~3줄
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.id }) { item ->
                ResultCard(item, onPreview = { previewItem = item }, onDownload = { onPick(item) })
            }
        }
    }
}

@Composable
private fun ResultCard(item: SearchItem, onPreview: () -> Unit, onDownload: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onPreview)
            .padding(bottom = 6.dp)
    ) {
        Box {
            AsyncImage(
                model = item.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (item.duration.isNotEmpty()) {
                Text(
                    item.duration,
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(androidx.compose.ui.graphics.Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.channel.isNotEmpty()) {
                    Text(
                        item.channel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onDownload) { Text("받기") }
        }
    }
}


private fun isAudioMode(): Boolean =
    PreferenceUtil.getDownloadType(usePreviousType = true) == DownloadType.Audio

private fun setAudioMode(audio: Boolean) {
    // 받기 창이 마지막 선택을 따르도록 맞추고, 그 선택을 소리/영상으로 저장
    DOWNLOAD_TYPE_INITIALIZATION.updateInt(USE_PREVIOUS_SELECTION)
    PreferenceUtil.updateDownloadType(if (audio) DownloadType.Audio else DownloadType.Video)
    EXTRACT_AUDIO.updateBoolean(audio)
}

private fun setSubtitle(on: Boolean) {
    SUBTITLE.updateBoolean(on)
    if (on) {
        // 켤 때 쓰기 편한 값으로 함께 맞춤: 한국어·영어, 자동 자막 포함, SRT 파일, 영상과 따로 저장
        if (SUBTITLE_LANGUAGE.getString().isBlank()) SUBTITLE_LANGUAGE.updateString("ko")
        AUTO_SUBTITLE.updateBoolean(true)
        CONVERT_SUBTITLE.updateInt(CONVERT_SRT)
        EMBED_SUBTITLE.updateBoolean(false)
    }
}

/** 가장 자주 바꾸는 두 가지는 검색창 바로 아래에 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickOptions() {
    var audioOnly by remember { mutableStateOf(isAudioMode()) }
    var subtitle by remember { mutableStateOf(SUBTITLE.getBoolean()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = audioOnly,
            onClick = { audioOnly = !audioOnly; setAudioMode(audioOnly) },
            label = { Text(if (audioOnly) "🎵 소리만 받기" else "🎬 영상으로 받기") },
        )
        FilterChip(
            selected = subtitle,
            onClick = { subtitle = !subtitle; setSubtitle(subtitle) },
            label = { Text(if (subtitle) "💬 자막도 받기" else "💬 자막 안 받기") },
        )
    }
}

/** 흩어진 설정 중 실제로 자주 쓰는 것만 한 화면에 모은 시트 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit, onOpenAll: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var audioOnly by remember { mutableStateOf(isAudioMode()) }
    var quality by remember { mutableStateOf(VIDEO_QUALITY.getInt()) }
    var mp4 by remember { mutableStateOf(VIDEO_FORMAT.getInt() == FORMAT_COMPATIBILITY) }
    var mp3 by remember { mutableStateOf(AUDIO_CONVERT.getBoolean() && AUDIO_CONVERSION_FORMAT.getInt() == CONVERT_MP3) }
    var subtitle by remember { mutableStateOf(SUBTITLE.getBoolean()) }
    var subLang by remember { mutableStateOf(SUBTITLE_LANGUAGE.getString().ifBlank { "ko" }) }
    var askFormat by remember { mutableStateOf(FORMAT_SELECTION.getBoolean()) }
    var engineVersion by remember { mutableStateOf(YT_DLP_VERSION.getString()) }
    var engineMsg by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "자주 바꾸는 것만 모았어요. 바꾸면 바로 저장돼요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            SectionTitle("받는 방법")
            ChoiceRow(
                options = listOf("🎬 영상", "🎵 소리만"),
                selected = if (audioOnly) 1 else 0,
                onSelect = { audioOnly = it == 1; setAudioMode(audioOnly) },
            )

            SectionTitle("영상 화질")
            val qualities = listOf(0 to "가장 좋게", 3 to "1080p", 4 to "720p", 5 to "480p")
            ChoiceRow(
                options = qualities.map { it.second },
                selected = qualities.indexOfFirst { it.first == quality }.coerceAtLeast(0),
                onSelect = { quality = qualities[it].first; VIDEO_QUALITY.updateInt(quality) },
            )
            Text(
                "낮출수록 용량이 작아져요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SwitchRow(
                title = "어디서나 재생되는 MP4로 받기",
                desc = "끄면 화질은 조금 좋아지지만 TV·PC에서 안 열릴 수 있어요",
                checked = mp4,
                onChange = { mp4 = it; VIDEO_FORMAT.updateInt(if (it) FORMAT_COMPATIBILITY else FORMAT_QUALITY) },
            )
            SwitchRow(
                title = "소리는 MP3로 바꾸기",
                desc = "끄면 M4A로 받아요. 대부분 기기에서 둘 다 재생돼요",
                checked = mp3,
                onChange = {
                    mp3 = it
                    AUDIO_CONVERT.updateBoolean(it)
                    if (it) AUDIO_CONVERSION_FORMAT.updateInt(CONVERT_MP3)
                },
            )

            SectionTitle("자막")
            SwitchRow(
                title = "자막도 받기",
                desc = "영상과 같은 폴더에 따로 저장돼요. 자동 자막도 포함해요",
                checked = subtitle,
                onChange = { subtitle = it; setSubtitle(it) },
            )
            if (subtitle) {
                Text("자막 언어 (여러 개 고를 수 있어요)", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                val langs = listOf("ko" to "한국어", "en" to "영어", "ja" to "일본어", "zh-Hans" to "중국어")
                var picked by remember {
                    mutableStateOf(subLang.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    langs.forEach { (code, label) ->
                        FilterChip(
                            selected = code in picked,
                            onClick = {
                                val next = if (code in picked) picked - code else picked + code
                                if (next.isNotEmpty()) {
                                    picked = next
                                    // 고른 순서와 관계없이 목록 순서대로 저장
                                    val joined = langs.map { it.first }.filter { it in next }.joinToString(",")
                                    subLang = joined
                                    SUBTITLE_LANGUAGE.updateString(joined)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                var translated by remember { mutableStateOf(AUTO_TRANSLATED_SUBTITLES.getBoolean()) }
                SwitchRow(
                    title = "번역 자막도 받기",
                    desc = "고른 언어 자막이 없으면 유튜브 자동 번역으로 받아요. 예: 영어 영상 + 한국어 선택 → 한국어 번역 자막",
                    checked = translated,
                    onChange = { translated = it; AUTO_TRANSLATED_SUBTITLES.updateBoolean(it) },
                )

                Text("자막 저장 방식", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                var subOut by remember { mutableStateOf(SubtitleText.OUTPUT_KEY.getInt(SubtitleText.OUTPUT_TXT)) }
                val outs = listOf(
                    SubtitleText.OUTPUT_TXT to "읽기용 글(TXT)",
                    SubtitleText.OUTPUT_SRT to "영상용 자막(SRT)",
                    SubtitleText.OUTPUT_BOTH to "둘 다",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    outs.forEach { (v, label) ->
                        FilterChip(
                            selected = subOut == v,
                            onClick = { subOut = v; SubtitleText.OUTPUT_KEY.updateInt(v) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    when (subOut) {
                        SubtitleText.OUTPUT_TXT -> "겹친 줄을 없애고 말하는 사람이 바뀔 때마다 문단을 나눈 글 파일로 저장해요"
                        SubtitleText.OUTPUT_BOTH -> "영상과 함께 볼 SRT와 읽기용 TXT를 같이 저장해요"
                        else -> "영상 플레이어에서 화면에 띄우는 자막 파일이에요"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionTitle("받을 때")
            SwitchRow(
                title = "받기 전에 형식 직접 고르기",
                desc = "켜면 화질·파일 종류 목록이 매번 떠요",
                checked = askFormat,
                onChange = { askFormat = it; FORMAT_SELECTION.updateBoolean(it) },
            )

            SectionTitle("저장 위치")
            Text(App.videoDownloadDir, style = MaterialTheme.typography.bodyMedium)
            Text(
                "소리만 받은 파일은 이 안의 Audio 폴더에 저장돼요. 위치를 바꾸려면 '모든 설정'에서 하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            SectionTitle("앱 업데이트")
            var appMsg by remember { mutableStateOf("") }
            var appBusy by remember { mutableStateOf(false) }
            Text(
                "브라우저를 거치지 않고 앱 안에서 최신판을 받아 바로 설치해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(
                    enabled = !appBusy,
                    onClick = {
                        appBusy = true
                        appMsg = "받는 중…"
                        scope.launch {
                            val ok = downloadLatestApp { pct -> appMsg = "받는 중… $pct%" }
                            appBusy = false
                            if (ok) {
                                appMsg = "설치 화면을 열었어요"
                                UpdateUtil.installLatestApk()
                            } else {
                                appMsg = "받지 못했어요. 인터넷을 확인하세요"
                            }
                        }
                    },
                ) { Text("새 버전 받기") }
                if (appMsg.isNotEmpty()) {
                    Text(appMsg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }

            SectionTitle("다운로드 엔진")
            Text(
                if (engineVersion.isBlank()) "버전 확인 전" else "지금 버전: $engineVersion",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "유튜브가 바뀌어 받기가 안 되면 여기서 업데이트하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(
                    enabled = !updating,
                    onClick = {
                        updating = true
                        engineMsg = "업데이트하는 중…"
                        scope.launch {
                            engineMsg = try {
                                when (UpdateUtil.updateYtDlp()) {
                                    UpdateStatus.DONE -> "최신으로 바꿨어요"
                                    UpdateStatus.ALREADY_UP_TO_DATE -> "이미 최신이에요"
                                    else -> "업데이트하지 못했어요"
                                }
                            } catch (t: Throwable) {
                                "업데이트하지 못했어요. 인터넷을 확인하세요"
                            }
                            engineVersion = YT_DLP_VERSION.getString()
                            updating = false
                        }
                    },
                ) { Text("엔진 업데이트") }
                if (engineMsg.isNotEmpty()) {
                    Text(engineMsg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
            OutlinedButton(onClick = onOpenAll, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("모든 설정·받은 목록 열기")
            }
            Text(
                "로그인이 필요한 영상(쿠키), 저장 위치 바꾸기 같은 드문 설정은 여기서 하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


/** 결과를 누르면 받기 전에 영상을 먼저 보여 주는 창 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewDialog(item: SearchItem, onDismiss: () -> Unit, onDownload: () -> Unit, onSubsOnly: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val close = {
        webView?.apply { loadUrl("about:blank"); destroy() }
        webView = null
        onDismiss()
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    TextButton(onClick = close) { Text("← 닫기") }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    TextButton(onClick = onSubsOnly) { Text("자막만") }
                    Button(onClick = { webView?.apply { loadUrl("about:blank") }; onDownload() }) {
                        Text("이 영상 받기")
                    }
                }
                // 휴대폰 세로: 화면 폭에 맞춘 16:9 / 태블릿·가로: 남은 공간을 채움
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = settings.userAgentString.replace("; wv", "")
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    // 유튜브 앱으로 튀어 나가는 주소는 막고, 미리 보기 안에서만 본다
                                    val scheme = request.url.scheme ?: return true
                                    return scheme != "http" && scheme != "https"
                                }
                            }
                            loadUrl("https://m.youtube.com/watch?v=${item.id}")
                            webView = this
                        }
                    },
                )
            }
        }
    }
}


/**
 * 영상은 받지 않고 자막만 받는다. 이미 받은 영상과 같은 이름·같은 폴더에 저장되므로
 * VLC·MX 플레이어 등에서 영상을 열면 자막이 자동으로 붙는다.
 */
private fun fetchSubtitlesOnly(url: String, scope: kotlinx.coroutines.CoroutineScope) {
    val ctx = App.context
    android.widget.Toast.makeText(ctx, "자막을 받는 중이에요…", android.widget.Toast.LENGTH_SHORT).show()
    scope.launch {
        val started = System.currentTimeMillis()
        val msg = withContext(Dispatchers.IO) {
            try {
                val ydl = YoutubeDL.getInstance()
                runCatching { ydl.init(ctx) }
                val langs = SUBTITLE_LANGUAGE.getString().ifBlank { "ko" }
                val req = YoutubeDLRequest(url).apply {
                    JsRuntime.apply(this)
                    addOption("--skip-download")
                    addOption("--no-playlist")
                    addOption("--write-subs")
                    addOption("--write-auto-subs")
                    addOption("--sub-langs", langs)
                    addOption("--convert-subs", "srt")
                    addOption("--sleep-subtitles", "3")
                    if (!AUTO_TRANSLATED_SUBTITLES.getBoolean()) {
                        addOption("--extractor-args", "youtube:skip=translated_subs")
                    }
                    addOption("-P", App.videoDownloadDir)
                    addOption("-o", OUTPUT_TEMPLATE.getString().ifBlank { DownloadUtil.OUTPUT_TEMPLATE_DEFAULT })
                    addOption("--print", "after_move:%(id)s")
                }
                val out = ydl.execute(req, "subs-${System.currentTimeMillis()}").out
                val id = out.lines().lastOrNull { it.isNotBlank() }?.trim()
                // 자막만 받았을 때는 SRT를 남겨야 영상에 붙으므로, TXT 모드여도 SRT는 지우지 않고 TXT만 추가
                val mode = SubtitleText.OUTPUT_KEY.getInt(SubtitleText.OUTPUT_TXT)
                if (mode != SubtitleText.OUTPUT_SRT) {
                    SubtitleText.OUTPUT_KEY.updateInt(SubtitleText.OUTPUT_BOTH)
                    SubtitleText.processAfterDownload(App.videoDownloadDir, id, null, started)
                    SubtitleText.OUTPUT_KEY.updateInt(mode)
                }
                val got = java.io.File(App.videoDownloadDir).listFiles()
                    ?.count { it.extension.equals("srt", true) && it.lastModified() >= started - 5_000 } ?: 0
                if (got > 0) "자막 ${got}개를 받았어요. 영상과 같은 폴더에 있어요"
                else "이 언어의 자막이 없어요. ⚙ 설정에서 '번역 자막도 받기'를 켜 보세요"
            } catch (t: Throwable) {
                val line = t.message?.lineSequence()?.lastOrNull { it.contains("ERROR") } ?: t.message ?: ""
                if (line.contains("429")) "유튜브가 자막 요청을 잠시 막았어요. 한두 시간 뒤에 다시 해 보세요"
                else "자막을 받지 못했어요: ${line.take(100)}"
            }
        }
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}


private const val LATEST_APK_URL =
    "https://github.com/kimhyounsoo76/Seal/releases/download/app-latest/YeongsangChajaBatgi.apk"

/** 고정 주소의 최신 APK를 앱 전용 폴더(latest.apk)로 받는다. 성공하면 true */
private suspend fun downloadLatestApp(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val ctx = App.context
        val dest = java.io.File(ctx.getExternalFilesDir("apk"), "latest.apk")
        dest.parentFile?.mkdirs()
        val tmp = java.io.File(dest.parentFile, "latest.apk.part")
        var url = java.net.URL(LATEST_APK_URL)
        var conn: java.net.HttpURLConnection
        var hops = 0
        while (true) {
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = java.net.URL(url, conn.getHeaderField("Location"))
                conn.disconnect(); hops++; continue
            }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        var done = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (total > 0 && done != total) error("incomplete")
        dest.delete()
        tmp.renameTo(dest)
    }.getOrDefault(false)
}
