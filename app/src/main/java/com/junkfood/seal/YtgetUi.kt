package com.junkfood.seal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.FileUtil
import com.tencent.mmkv.MMKV
import org.koin.compose.koinInject

/** 앱 화면 안에 보이는 버전. 파일 이름의 버전과 같게 맞춘다 */
const val YTGET_VERSION = "1.0"

/** 이번에 앱을 켠 뒤 검색 화면·공유로 시작한 받기 (첫 화면 '받는 중'에 보여 줄 것) */
object SessionTasks {
    val ids = mutableStateListOf<String>()

    fun add(id: String) {
        if (id !in ids) ids.add(id)
    }

    fun remove(id: String) {
        ids.remove(id)
    }
}

/** 최근 검색어 (최대 8개) */
object RecentSearches {
    private const val KEY = "ytget_recent_searches"
    private val kv get() = MMKV.defaultMMKV()

    fun load(): List<String> =
        kv.decodeString(KEY, "").orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun add(q: String): List<String> {
        val list = (listOf(q) + load().filter { it != q }).take(8)
        kv.encode(KEY, list.joinToString("\n"))
        return list
    }

    fun remove(q: String): List<String> {
        val list = load().filter { it != q }
        kv.encode(KEY, list.joinToString("\n"))
        return list
    }
}

/** 엔진이 내는 영어 오류를 무엇을 하면 되는지 알려 주는 한국어로 바꾼다 */
fun friendlyError(raw: String?): String {
    val m = raw.orEmpty()
    return when {
        m.contains("429") || m.contains("Too Many Requests", true) ->
            "유튜브가 잠시 요청을 막았어요. 한두 시간 뒤에 다시 해 보세요"
        m.contains("Sign in to confirm", true) || m.contains("not a bot", true) ->
            "유튜브가 로봇인지 확인하려고 막았어요. 와이파이를 바꾸거나 잠시 뒤에 다시 해 보세요"
        m.contains("Private video", true) -> "비공개 영상이라 받을 수 없어요"
        m.contains("age", true) && m.contains("confirm", true) ->
            "나이 제한 영상이라 받을 수 없어요"
        m.contains("Video unavailable", true) || m.contains("not available", true) ->
            "볼 수 없는 영상이에요. 삭제됐거나 우리나라에서 막힌 영상일 수 있어요"
        m.contains("Requested format is not available", true) ->
            "고른 화질이 없어요. ⚙ 설정에서 화질을 '가장 좋게'로 바꿔 보세요"
        m.contains("no such option", true) || m.contains("Only images are available", true) ||
            m.contains("challenge", true) || m.contains("nsig", true) ->
            "받기 엔진이 옛 버전이에요. ⚙ 설정 → 엔진 업데이트를 누른 뒤 다시 받아 보세요"
        m.contains("Unable to resolve host", true) || m.contains("timed out", true) ||
            m.contains("Network", true) || m.contains("Connection", true) ->
            "인터넷 연결이 불안정해요. 연결을 확인하고 다시 해 보세요"
        m.contains("No space", true) -> "휴대폰 저장 공간이 부족해요"
        m.contains("Unsupported URL", true) -> "받을 수 없는 주소예요. 영상 주소가 맞는지 확인하세요"
        else -> {
            val line = m.lineSequence().lastOrNull { it.contains("ERROR") } ?: m.lineSequence().firstOrNull().orEmpty()
            "받지 못했어요. 엔진 업데이트 뒤 다시 해 보세요 (${line.removePrefix("ERROR: ").take(80)})"
        }
    }
}

/** 첫 화면 맨 위 '받는 중' — 진행 막대, 취소, 다 받으면 열기 */
@Composable
fun DownloadingPanel(modifier: Modifier = Modifier, downloader: DownloaderV2 = koinInject()) {
    val map = downloader.getTaskStateMap()
    val shown =
        map.entries
            .filter { (task, state) ->
                task.id in SessionTasks.ids ||
                    state.downloadState is Task.DownloadState.Running ||
                    state.downloadState is Task.DownloadState.FetchingInfo
            }
            .sortedByDescending { it.key.timeCreated }
    if (shown.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (task, state) -> TaskRow(task, state, downloader) }
    }
}

@Composable
private fun TaskRow(task: Task, state: Task.State, downloader: DownloaderV2) {
    val ds = state.downloadState
    val title = state.viewState.title.ifBlank { task.url }
    val audio = task.preferences.extractAudio
    val icon = if (audio) "🎵" else "🎬"

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$icon $title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (ds) {
                        is Task.DownloadState.Running ->
                            if (ds.progress >= 0f) "받는 중 ${(ds.progress * 100).toInt()}%" else "받는 중…"
                        is Task.DownloadState.FetchingInfo,
                        Task.DownloadState.Idle,
                        Task.DownloadState.ReadyWithInfo -> "준비 중…"
                        is Task.DownloadState.Completed -> "다 받았어요"
                        is Task.DownloadState.Canceled -> "취소했어요"
                        is Task.DownloadState.Error -> friendlyError(ds.throwable.message)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (ds is Task.DownloadState.Error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (ds) {
                is Task.DownloadState.Cancelable ->
                    OutlinedButton(onClick = { downloader.cancel(task) }) { Text("✕ 취소") }
                is Task.DownloadState.Completed -> {
                    val path = ds.filePath
                    if (path != null) {
                        Button(onClick = { FileUtil.openFile(path) {} }) { Text("▶ 열기") }
                    }
                    TextButton(onClick = { SessionTasks.remove(task.id) }) { Text("✕") }
                }
                is Task.DownloadState.Restartable -> {
                    FilledTonalButton(onClick = { downloader.restart(task) }) { Text("다시") }
                    TextButton(onClick = { SessionTasks.remove(task.id); downloader.remove(task) }) {
                        Text("✕")
                    }
                }
                else -> {}
            }
        }
        if (ds is Task.DownloadState.Running) {
            if (ds.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { ds.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp))
            }
        } else if (ds !is Task.DownloadState.Completed && ds !is Task.DownloadState.Restartable) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp))
        }
    }
}

/** 다른 앱(유튜브 등)에서 공유로 들어왔을 때 무엇을 받을지 고르는 창 */
@Composable
fun ShareChoiceDialog(
    url: String,
    onVideo: () -> Unit,
    onAudio: () -> Unit,
    onSubs: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("무엇을 받을까요?") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = onVideo, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("🎬 영상 받기")
                }
                FilledTonalButton(onClick = onAudio, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("🎵 음원만 받기")
                }
                OutlinedButton(onClick = onSubs, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("💬 자막·가사만 받기")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
