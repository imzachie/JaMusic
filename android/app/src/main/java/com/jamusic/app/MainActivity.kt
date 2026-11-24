package com.jamusic.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.google.gson.JsonParser
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

private val Ink = Color(0xFF111216)
private val Panel = Color(0xFF1D1F26)
private val Cloud = Color(0xFFF5F1FA)
private val Violet = Color(0xFFC6B6FF)
private val Lime = Color(0xFFD8F278)
private val Muted = Color(0xFFB9B7C2)
private val httpClient = OkHttpClient()
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    var signedInAs by mutableStateOf<String?>(null)
        private set
    var authNotice by mutableStateOf<String?>(null)
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        signedInAs = getSharedPreferences("session", MODE_PRIVATE).getString("email", null)
        setContent {
            JaTheme {
                JaMusicApp(
                    player = player,
                    loadTracks = { loadPublicTracks() },
                    signedInAs = signedInAs,
                    authNotice = authNotice,
                    onEmailAuth = { email, password, create -> authenticateEmail(email, password, create) },
                    onGoogleAuth = { beginGoogleSignIn() },
                    onSignOut = { signOut() }
                )
            }
        }
    }

    private suspend fun loadPublicTracks(): List<Track> = withContext(Dispatchers.IO) {
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()) {
            "The app build is missing its Supabase URL or publishable key. Configure them for the release build."
        }
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/tracks?select=*,profiles(display_name,username)&order=created_at.desc&limit=100"
        val request = Request.Builder().url(url)
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Supabase returned ${response.code}. Check public track access and app credentials.")
            val array = JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray
            array.mapNotNull { row ->
                val obj = row.asJsonObject
                val audio = obj.get("audio_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (audio.isBlank()) null else {
                    val profile = obj.get("profiles")?.takeUnless { it.isJsonNull }?.asJsonObject
                    val artist = profile?.get("display_name")?.takeUnless { it.isJsonNull }?.asString
                        ?: profile?.get("username")?.takeUnless { it.isJsonNull }?.asString ?: "JaMusic artist"
                    Track(
                        id = obj.get("id")?.asString.orEmpty(),
                        title = obj.get("title")?.asString ?: "Untitled",
                        artist = artist,
                        album = obj.get("album")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                        cover = obj.get("cover_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                        audio = audio,
                        lyrics = obj.get("lyrics")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                        duration = obj.get("duration")?.asLong ?: 0L
                    )
                }
            }
        }
    }

    private suspend fun authenticateEmail(email: String, password: String, createAccount: Boolean): String = withContext(Dispatchers.IO) {
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()) { "Supabase app credentials aren't configured in this release build." }
        val endpoint = if (createAccount) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val body = JSONObject().put("email", email.trim()).put("password", password).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + endpoint)
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val payload = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            if (!response.isSuccessful) throw IllegalArgumentException(payload.get("msg")?.asString ?: payload.get("message")?.asString ?: "Sign-in failed (${response.code}).")
            val accessToken = payload.get("access_token")?.takeUnless { it.isJsonNull }?.asString
            if (accessToken.isNullOrBlank()) throw IllegalStateException("Check your email to confirm the new account, then sign in here.")
            val user = payload.getAsJsonObject("user")
            val userEmail = user?.get("email")?.takeUnless { it.isJsonNull }?.asString ?: email.trim()
            getSharedPreferences("session", MODE_PRIVATE).edit().putString("email", userEmail).putString("access_token", accessToken).putString("refresh_token", payload.get("refresh_token")?.takeUnless { it.isJsonNull }?.asString).apply()
            signedInAs = userEmail
            userEmail
        }
    }

    private fun beginGoogleSignIn() {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            authNotice = "Google sign-in needs the JaMusic Supabase URL and publishable key in the release build."
            return
        }
        val callback = "jamusic://auth-callback"
        val authUrl = Uri.parse(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", callback)
            .appendQueryParameter("flow_type", "implicit")
            .appendQueryParameter("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .build()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, authUrl)) }
            .onFailure { authNotice = "Couldn't open Google sign-in. Check that a browser is installed." }
    }

    private fun signOut() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        signedInAs = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data ?: return
        val params = (uri.fragment ?: "").split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) Uri.decode(pair[0]) to Uri.decode(pair[1]) else null
        }.toMap()
        val accessToken = params["access_token"] ?: uri.getQueryParameter("access_token")
        val error = params["error_description"] ?: uri.getQueryParameter("error_description")
        if (accessToken != null) {
            val email = params["user_email"] ?: runCatching {
                val claims = String(Base64.getUrlDecoder().decode(accessToken.split('.')[1]))
                JSONObject(claims).optString("email", "Google account")
            }.getOrDefault("Google account")
            getSharedPreferences("session", MODE_PRIVATE).edit().putString("access_token", accessToken).putString("email", email).apply()
            signedInAs = email
            authNotice = "Signed in with Google."
        } else if (error != null) authNotice = error
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
private fun JaMusicApp(
    player: ExoPlayer,
    loadTracks: suspend () -> List<Track>,
    signedInAs: String?,
    authNotice: String?,
    onEmailAuth: suspend (String, String, Boolean) -> String,
    onGoogleAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var activeTrack by remember { mutableStateOf<Track?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var feedFilter by remember { mutableStateOf("Latest") }
    var favorites by remember { mutableStateOf(emptySet<String>()) }
    var showAuthPrompt by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creatingAccount by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshTracks() {
        loading = true
        loadError = null
        try { tracks = loadTracks() } catch (error: Exception) { loadError = error.message ?: "Couldn't load uploaded songs." }
        loading = false
    }
    LaunchedEffect(Unit) { refreshTracks() }
    LaunchedEffect(signedInAs) { if (signedInAs != null) showAuthPrompt = false }
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

    val searched = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    val filtered = when (feedFilter) {
        "Liked" -> searched.filter { it.id in favorites }
        "Latest" -> searched.take(8)
        else -> searched
    }
    val latestTrack = tracks.firstOrNull()
    val visibleTracks = if (tab == 0 && feedFilter == "Latest" && latestTrack != null) filtered.drop(1) else filtered

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Column {
                activeTrack?.let { track -> MiniPlayer(track, playing, onToggle = { if (playing) player.pause() else player.play() }, onOpen = { playerOpen = true }) }
                NavigationBar(containerColor = Color(0xFF17181D), tonalElevation = 0.dp) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2; if (signedInAs == null) { authError = null; showAuthPrompt = true } }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 22.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("JaMusic", style = MaterialTheme.typography.headlineSmall, color = Cloud, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { authError = null; showAuthPrompt = true }) {
                        Surface(shape = CircleShape, color = Panel) { Icon(Icons.Default.AccountCircle, contentDescription = "Account and sign in", modifier = Modifier.padding(8.dp), tint = Violet) }
                    }
                }
            }
            if (tab == 2) {
                item {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                        Text("Your library", fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold, color = Cloud)
                        Spacer(Modifier.height(8.dp))
                        Text(if (signedInAs == null) "Sign in to save music and manage your account." else "Signed in as $signedInAs", color = Muted)
                        Spacer(Modifier.height(18.dp))
                        if (signedInAs == null) Button(onClick = { authError = null; showAuthPrompt = true }, shape = RoundedCornerShape(24.dp)) { Text("Sign in to JaMusic") }
                        else OutlinedButton(onClick = onSignOut, shape = RoundedCornerShape(24.dp)) { Text("Sign out") }
                    }
                }
                item {
                    Text("Liked songs", Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp), color = Cloud, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                itemsIndexed(tracks.filter { it.id in favorites }) { index, track ->
                    TrackRow(track, index, isFavorite = true, onFavorite = { favorites = favorites - track.id }, onClick = { startTrack(player, track) { activeTrack = it; playerOpen = true } })
                }
                if (tracks.none { it.id in favorites }) item { Text("Tap the heart on a song to keep it here.", Modifier.padding(horizontal = 22.dp, vertical = 16.dp), color = Muted) }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                        Text(if (tab == 1) "Search" else "Latest uploads", fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold, color = Cloud)
                        Text(if (tab == 1) "Find a song from the JaMusic community." else "Fresh tracks, straight from JaMusic artists.", Modifier.padding(top = 7.dp), color = Muted, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (tab == 1) item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search songs or artists") }, shape = RoundedCornerShape(24.dp), singleLine = true)
                }
                if (tab == 0 && feedFilter == "Latest" && latestTrack != null) item {
                    LatestUploadCard(latestTrack, onClick = { startTrack(player, latestTrack) { activeTrack = it; playerOpen = true } })
                }
                if (tab == 0 || tab == 1) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf("Latest", "All", "Liked").forEach { filter ->
                            FilterChip(selected = feedFilter == filter, onClick = { feedFilter = filter }, label = { Text(filter) }, shape = RoundedCornerShape(50))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { feedFilter = "All"; tab = 0 }) { Text("See all") }
                    }
                }
                item {
                    Text(if (tab == 1) "Results" else if (feedFilter == "Latest") "More latest uploads" else "Latest uploads", Modifier.padding(start = 22.dp, top = 5.dp, bottom = 8.dp), color = Cloud, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                if (loadError != null) item {
                    Column(Modifier.padding(22.dp)) {
                        Text("Couldn't load your uploaded songs", color = Cloud, style = MaterialTheme.typography.titleMedium)
                        Text(loadError.orEmpty(), Modifier.padding(top = 6.dp), color = Muted)
                        TextButton(onClick = { scope.launch { refreshTracks() } }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Try again") }
                    }
                }
                itemsIndexed(visibleTracks) { index, track ->
                    TrackRow(track, index, isFavorite = track.id in favorites,
                        onFavorite = { favorites = if (track.id in favorites) favorites - track.id else favorites + track.id },
                        onClick = { startTrack(player, track) { activeTrack = it; playerOpen = true } })
                }
                if (!loading && loadError == null && filtered.isEmpty()) item {
                    Text(if (tracks.isEmpty()) "No public uploads yet." else if (feedFilter == "Liked") "No liked tracks yet. Tap a heart to add one." else "No matching uploads.", Modifier.padding(22.dp), color = Muted)
                }
            }
        }
    }

    if (playerOpen) activeTrack?.let { track ->
        PlayerSheet(
            track = track, player = player, playing = playing, currentMs = currentMs, durationMs = durationMs,
            isFavorite = track.id in favorites,
            onFavorite = { favorites = if (track.id in favorites) favorites - track.id else favorites + track.id },
            onDismiss = { playerOpen = false }, onToggle = { if (playing) player.pause() else player.play() },
            onSeek = { player.seekTo(it) },
            onPrev = { playRelative(tracks, track, -1, player) { activeTrack = it } },
            onNext = { playRelative(tracks, track, 1, player) { activeTrack = it } }
        )
    }

    if (showAuthPrompt) {
        AuthPrompt(
            email = email, onEmailChange = { email = it }, password = password, onPasswordChange = { password = it },
            creatingAccount = creatingAccount, onToggleMode = { creatingAccount = !creatingAccount; authError = null },
            busy = authBusy, error = authError ?: authNotice,
            onGoogle = { onGoogleAuth() },
            onEmailSubmit = {
                authError = null
                if (!email.contains('@') || password.length < 6) authError = "Enter a valid email and a password with at least 6 characters."
                else scope.launch {
                    authBusy = true
                    try { onEmailAuth(email, password, creatingAccount); showAuthPrompt = false }
                    catch (error: Exception) { authError = error.message ?: "Sign-in failed." }
                    finally { authBusy = false }
                }
            },
            onDismiss = { showAuthPrompt = false }
        )
    }
}

@Composable
private fun LatestUploadCard(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF37364C), Color(0xFF24252D)))).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track, Modifier.size(72.dp), track.title.length)
        Column(Modifier.weight(1f).padding(horizontal = 15.dp)) {
            Text("LATEST UPLOAD", color = Lime, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
            Text(track.title, Modifier.padding(top = 4.dp), color = Cloud, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledIconButton(onClick = onClick, shape = CircleShape) { Icon(Icons.Default.PlayArrow, "Play latest upload") }
    }
}

@Composable
private fun AuthPrompt(
    email: String, onEmailChange: (String) -> Unit, password: String, onPasswordChange: (String) -> Unit,
    creatingAccount: Boolean, onToggleMode: () -> Unit, busy: Boolean, error: String?,
    onGoogle: () -> Unit, onEmailSubmit: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creatingAccount) "Create your JaMusic account" else "Sign in to JaMusic", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your music library, in one place.", color = Muted)
                OutlinedButton(onClick = onGoogle, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text("Continue with Google") }
                HorizontalDivider()
                OutlinedTextField(email, onEmailChange, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(password, onPasswordChange, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp))
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onToggleMode) { Text(if (creatingAccount) "Already have an account? Sign in" else "New to JaMusic? Create account") }
            }
        },
        confirmButton = { Button(onClick = onEmailSubmit, enabled = !busy, shape = RoundedCornerShape(18.dp)) { Text(if (busy) "Please wait…" else if (creatingAccount) "Create account" else "Sign in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
private fun TrackRow(track: Track, index: Int, isFavorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track, Modifier.size(58.dp), index)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, color = Cloud, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist + if (track.album.isBlank()) "" else " · ${track.album}", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = if (isFavorite) "Unlike ${track.title}" else "Like ${track.title}", tint = if (isFavorite) Violet else Muted, modifier = Modifier.size(21.dp)) }
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
private fun PlayerSheet(track: Track, player: ExoPlayer, playing: Boolean, currentMs: Long, durationMs: Long, isFavorite: Boolean, onFavorite: () -> Unit, onDismiss: () -> Unit, onToggle: () -> Unit, onSeek: (Long) -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    val lines = remember(track.lyrics) { parseLyrics(track.lyrics) }
    val currentSeconds = currentMs / 1000.0
    val currentLine = lines.indexOfLast { it.at <= currentSeconds }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink, contentColor = Cloud, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = Muted) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 24.dp)) {
            Cover(track, Modifier.fillMaxWidth().aspectRatio(1f), track.title.length)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(track.title, style = MaterialTheme.typography.headlineSmall, color = Cloud, fontWeight = FontWeight.SemiBold); Text(track.artist, color = Muted) }
                IconButton(onClick = onFavorite) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Like this track", tint = if (isFavorite) Violet else Muted) }
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
                    if (index < words.lastIndex && word.text.lastOrNull()?.isWhitespace() != true) append(" ")
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
