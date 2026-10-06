package com.junkfood.seal

import android.content.Intent
import android.os.Bundle
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
private fun SearchScreen(onPick: (SearchItem) -> Unit, onOpenSeal: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("검색어를 넣고 찾기를 누르세요.\n결과를 누르면 바로 받을 수 있어요.") }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
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
            TextButton(onClick = onOpenSeal) { Text("받은 목록·설정") }
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
                placeholder = { Text("유튜브에서 찾을 낱말") },
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
            ) { Text("찾기") }
        }

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
                ResultCard(item, onClick = { onPick(item) })
            }
        }
    }
}

@Composable
private fun ResultCard(item: SearchItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
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
            FilledTonalButton(onClick = onClick) { Text("받기") }
        }
    }
}
