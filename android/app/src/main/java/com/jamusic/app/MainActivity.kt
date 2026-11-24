package com.jamusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF111216)
private val Panel = Color(0xFF1D1F26)
private val Cloud = Color(0xFFF5F1FA)
private val Violet = Color(0xFFC6B6FF)
private val Lime = Color(0xFFD8F278)
private val Muted = Color(0xFFB9B7C2)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent {
            JaTheme {
                JaMusicApp(player = player, loadTracks = { loadPublicTracks() })
            }
        }
    }

    private suspend fun loadPublicTracks(): List<Track> = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) return@withContext demoTracks
        try {
            val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/tracks?select=*&order=created_at.desc&limit=50"
            val request = Request.Builder().url(url)
                .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                .header("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
                .build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext demoTracks
                val array = JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray
                array.mapNotNull { row ->
                    val obj = row.asJsonObject
                    val audio = obj.get("audio_url")?.asString.orEmpty()
                    if (audio.isBlank()) null else Track(
                        id = obj.get("id")?.asString.orEmpty(),
                        title = obj.get("title")?.asString ?: "Untitled",
                        artist = "JaMusic artist",
                        album = obj.get("album")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                        cover = obj.get("cover_url")?.asString.orEmpty(),
                        audio = audio,
                        lyrics = obj.get("lyrics")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                        duration = obj.get("duration")?.asLong ?: 0L
                    )
                }.ifEmpty { demoTracks }
            }
        } catch (_: Exception) { demoTracks }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun JaTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Violet, onPrimary = Ink, secondary = Lime, onSecondary = Ink,
        background = Ink, surface = Panel, onSurface = Cloud, onBackground = Cloud,
        surfaceVariant = Color(0xFF292A32), onSurfaceVariant = Muted
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

private data class Track(
    val id: String, val title: String, val artist: String, val album: String,
    val cover: String, val audio: String, val lyrics: String, val duration: Long
)
private data class LyricWord(val text: String, val at: Double)
private data class LyricLine(val text: String, val at: Double, val words: List<LyricWord>)

private val demoTracks = listOf(
    Track("a", "Midnight City", "M83", "Hurry Up, We're Dreaming", "", "", "[00:37.65]<00:37.65>Waiting <00:38.23>in <00:38.48>the <00:38.83>car\n[00:42.19]<00:42.19>Waiting <00:42.72>for <00:42.94>a <00:43.17>ride <00:43.70>in <00:44.04>the <00:44.70>dark", 244),
    Track("b", "Afterglow", "Mira Sol", "Afterglow EP", "", "", "[00:04.00]Lights are coming up\n[00:08.00]We can stay a little longer", 202),
    Track("c", "Soft Focus", "Juniper", "Soft Focus", "", "", "[00:02.00]Take it slow tonight", 188)
)

private fun parseLyrics(raw: String): List<LyricLine> = raw.lineSequence().mapNotNull { row ->
    val line = Regex("^\\[(\\d{2,3}):(\\d{2})\\.(\\d{2})\\](.*)$").matchEntire(row.trim()) ?: return@mapNotNull null
    val start = line.groupValues[1].toDouble() * 60 + line.groupValues[2].toDouble() + line.groupValues[3].toDouble() / 100
    val body = line.groupValues[4]
    val cue = Regex("<(\\d{2}):(\\d{2})\\.(\\d{2})>")
    val matches = cue.findAll(body).toList()
    val words = matches.mapIndexedNotNull { index, match ->
        val end = matches.getOrNull(index + 1)?.range?.first ?: body.length
        val text = body.substring(match.range.last + 1, end)
        if (text.isBlank()) null else LyricWord(text, match.groupValues[1].toDouble() * 60 + match.groupValues[2].toDouble() + match.groupValues[3].toDouble() / 100)
    }
    val plain = if (words.isEmpty()) body.trim() else words.joinToString("") { it.text }.trim()
    LyricLine(plain, start, words)
}.toList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JaMusicApp(player: ExoPlayer, loadTracks: suspend () -> List<Track>) {
    var tracks by remember { mutableStateOf(demoTracks) }
    var activeTrack by remember { mutableStateOf<Track?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { tracks = loadTracks(); loading = false }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentMs = 0; durationMs = player.duration.coerceAtLeast(0) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(playing, activeTrack) {
        while (true) {
            currentMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            delay(90)
        }
    }

    val filtered = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Column {
                activeTrack?.let { track -> MiniPlayer(track, playing, onToggle = { if (playing) player.pause() else player.play() }, onOpen = { playerOpen = true }) }
                NavigationBar(containerColor = Color(0xFF17181D), tonalElevation = 0.dp) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Listen") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 22.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("JAMUSIC", style = MaterialTheme.typography.labelLarge, color = Violet, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Surface(shape = CircleShape, color = Panel) { Text("J", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = Lime, fontWeight = FontWeight.Bold) }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Text(if (tab == 1) "Find your\nnext feeling." else "A little more\nyou, in sound.", fontSize = 40.sp, lineHeight = 43.sp, letterSpacing = (-1.6).sp, fontWeight = FontWeight.SemiBold, color = Cloud)
                    Spacer(Modifier.height(10.dp))
                    Text("A soundtrack for wherever you are.", color = Muted, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("For you", "New", "Made for night").forEachIndexed { index, label ->
                        FilterChip(selected = index == 0, onClick = { }, label = { Text(label) }, shape = RoundedCornerShape(50))
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(184.dp).clickable { tracks.firstOrNull()?.let { startTrack(player, it) { activeTrack = it; playerOpen = true } } }, shape = RoundedCornerShape(30.dp), color = Panel) {
                    Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF48416C), Color(0xFF1D2029), Color(0xFF384333))))) {
                        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                            Text("FEATURED MIX · 38 MIN", color = Lime, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(5.dp)); Text("Midnight\nstate of mind", fontSize = 27.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, color = Cloud)
                        }
                        Surface(Modifier.align(Alignment.TopEnd).padding(18.dp).size(46.dp), shape = CircleShape, color = Lime) { Icon(Icons.Default.PlayArrow, null, Modifier.padding(10.dp), tint = Ink) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tab == 2) "Your rotation" else "Picked for you", style = MaterialTheme.typography.headlineSmall, color = Cloud, fontWeight = FontWeight.SemiBold)
                    Text(if (loading) "Loading" else "See all", style = MaterialTheme.typography.labelLarge, color = Violet)
                }
            }
            if (tab == 1) item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Artists, tracks, moods") }, shape = RoundedCornerShape(24.dp), singleLine = true)
            }
            itemsIndexed(filtered) { index, track ->
                TrackRow(track, index, onClick = { startTrack(player, track) { activeTrack = it; playerOpen = true } })
            }
            if (!loading && filtered.isEmpty()) item { Text("No tracks found yet. Check your Supabase connection.", Modifier.padding(24.dp), color = Muted) }
        }
    }

    if (playerOpen) activeTrack?.let { track ->
        PlayerSheet(
            track = track, player = player, playing = playing, currentMs = currentMs, durationMs = durationMs,
            onDismiss = { playerOpen = false },
            onToggle = { if (playing) player.pause() else player.play() },
            onSeek = { player.seekTo(it) },
            onPrev = { playRelative(tracks, track, -1, player) { activeTrack = it } },
            onNext = { playRelative(tracks, track, 1, player) { activeTrack = it } }
        )
    }
}

private fun startTrack(player: ExoPlayer, track: Track, select: (Track) -> Unit) {
    select(track)
    if (track.audio.isNotBlank()) {
        player.setMediaItem(MediaItem.fromUri(track.audio))
        player.prepare()
        player.playWhenReady = true
    }
}
private fun playRelative(tracks: List<Track>, current: Track, step: Int, player: ExoPlayer, select: (Track) -> Unit) {
    val index = tracks.indexOfFirst { it.id == current.id }.let { if (it < 0) 0 else it }
    startTrack(player, tracks[(index + step + tracks.size) % tracks.size], select)
}

@Composable
private fun TrackRow(track: Track, index: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track, Modifier.size(58.dp), index)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = Cloud, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist + if (track.album.isBlank()) "" else " · ${track.album}", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.FavoriteBorder, null, tint = Muted, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun Cover(track: Track, modifier: Modifier = Modifier, seed: Int = 0) {
    val gradients = listOf(listOf(Color(0xFF8070C1), Color(0xFF3C315B)), listOf(Color(0xFFC47779), Color(0xFF49303D)), listOf(Color(0xFF71978A), Color(0xFF253B39)))
    Box(modifier.clip(RoundedCornerShape(19.dp)).background(Brush.linearGradient(gradients[seed % gradients.size])), contentAlignment = Alignment.Center) {
        if (track.cover.isNotBlank()) AsyncImage(track.cover, contentDescription = "${track.title} cover", modifier = Modifier.fillMaxSize())
        else Text(track.title.take(1).uppercase(), color = Cloud.copy(alpha = .78f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniPlayer(track: Track, playing: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF292A32)).clickable(onClick = onOpen).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track, Modifier.size(42.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(track.title, color = Cloud, maxLines = 1); Text(track.artist, color = Muted, style = MaterialTheme.typography.labelSmall) }
        IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Cloud) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(track: Track, player: ExoPlayer, playing: Boolean, currentMs: Long, durationMs: Long, onDismiss: () -> Unit, onToggle: () -> Unit, onSeek: (Long) -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    val lines = remember(track.lyrics) { parseLyrics(track.lyrics) }
    val currentSeconds = currentMs / 1000.0
    val currentLine = lines.indexOfLast { it.at <= currentSeconds }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink, contentColor = Cloud, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = Muted) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 24.dp)) {
            Cover(track, Modifier.fillMaxWidth().aspectRatio(1f), track.title.length)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(track.title, style = MaterialTheme.typography.headlineSmall, color = Cloud, fontWeight = FontWeight.SemiBold); Text(track.artist, color = Muted) }
                IconButton(onClick = {}) { Icon(Icons.Default.FavoriteBorder, null, tint = Violet) }
            }
            Slider(value = if (durationMs > 0) currentMs.toFloat().coerceIn(0f, durationMs.toFloat()) else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Violet, inactiveTrackColor = Panel))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(currentMs), color = Muted, style = MaterialTheme.typography.labelSmall); Text(clock(durationMs), color = Muted, style = MaterialTheme.typography.labelSmall) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Cloud, modifier = Modifier.size(31.dp)) }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(68.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, null, tint = Cloud, modifier = Modifier.size(31.dp)) }
            }
            Text("LYRICS", Modifier.padding(top = 16.dp, bottom = 8.dp), color = Violet, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            if (lines.isEmpty()) Text("No lyrics yet", color = Muted, style = MaterialTheme.typography.headlineSmall)
            else LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                itemsIndexed(lines) { index, line ->
                    val isCurrent = index == currentLine
                    val lineColor by animateColorAsState(if (isCurrent) Cloud else Muted.copy(alpha = if (index < currentLine) .43f else .58f), animationSpec = spring(stiffness = Spring.StiffnessLow), label = "line-color")
                    Column(Modifier.fillMaxWidth().clickable { onSeek((line.at * 1000).toLong()) }.padding(vertical = 1.dp)) {
                        if (line.words.isEmpty()) Text(line.text, color = lineColor, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium)
                        else WordLine(line.words, currentSeconds, lineColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordLine(words: List<LyricWord>, now: Double, lineColor: Color) {
    Row { // FlowRow is intentionally avoided: word order and spaces remain stable across line breaks.
        androidx.compose.foundation.text.BasicText(
            text = androidx.compose.ui.text.buildAnnotatedString {
                words.forEachIndexed { index, word ->
                    val lit = word.at <= now
                    val active = lit && (words.getOrNull(index + 1)?.at ?: Double.POSITIVE_INFINITY) > now
                    val color = when { active -> Lime; lit -> Cloud; else -> lineColor.copy(alpha = .52f) }
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium))
                    append(word.text)
                    pop()
                    if (index < words.lastIndex && !word.text.lastOrNull().isWhitespace()) append(" ")
                }
            },
            style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 33.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
