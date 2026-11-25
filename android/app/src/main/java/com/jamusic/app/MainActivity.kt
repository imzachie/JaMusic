package com.jamusic.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

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
    private lateinit var mediaSession: MediaSessionCompat
    private var notificationTrack: Track? = null
    private var notificationPlaying = false
    private var notificationArtwork: Bitmap? = null
    private var artworkRequestTrackId: String? = null
    private var artworkAttemptedTrackId: String? = null
    var signedInAs by mutableStateOf<String?>(null)
        private set
    var signedInUserId by mutableStateOf<String?>(null)
        private set
    var authNotice by mutableStateOf<String?>(null)
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setupMediaSession()
        val session = getSharedPreferences("session", MODE_PRIVATE)
        signedInAs = session.getString("email", null)
        signedInUserId = session.getString("user_id", null)
        setContent {
            JaTheme {
                JaMusicApp(
                    player = player,
                    loadTracks = { loadPublicTracks() },
                    signedInAs = signedInAs,
                    signedInUserId = signedInUserId,
                    authNotice = authNotice,
                    loadProfile = { id -> loadOrCreateProfile(id) },
                    saveProfile = { profile, avatar, banner -> updateProfile(profile, avatar, banner) },
                    uploadTrack = { title, album, genre, lyricMode, lyricText, audioUri, coverUri -> uploadTrack(title, album, genre, lyricMode, lyricText, audioUri, coverUri) },
                    saveLyrics = { id, lyrics -> saveTrackLyrics(id, lyrics) },
                    onNowPlayingChanged = { track, playing -> updateMediaNotification(track, playing) },
                    onEmailAuth = { email, password, create -> authenticateEmail(email, password, create) },
                    onGoogleAuth = { beginGoogleSignIn() },
                    onSignOut = { signOut() }
                )
            }
        }
    }

    private fun setupMediaSession() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(MEDIA_CHANNEL, "JaMusic playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls for JaMusic"
                setShowBadge(false)
            })
        }
        mediaSession = MediaSessionCompat(this, "JaMusic").apply {
            setSessionActivity(PendingIntent.getActivity(this@MainActivity, 0, Intent(this@MainActivity, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { player.play() }
                override fun onPause() { player.pause() }
                override fun onSkipToNext() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                override fun onSkipToPrevious() { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0) }
                override fun onSeekTo(pos: Long) { player.seekTo(pos) }
            })
        }
        MediaActionHandler.callback = { action ->
            runOnUiThread {
                when (action) {
                    MediaActionHandler.PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                    MediaActionHandler.NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    MediaActionHandler.PREVIOUS -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
                }
            }
        }
    }

    private fun updateMediaNotification(track: Track?, playing: Boolean) {
        if (notificationTrack?.id != track?.id) {
            notificationArtwork = null
            artworkRequestTrackId = null
            artworkAttemptedTrackId = null
        }
        notificationTrack = track
        notificationPlaying = playing
        if (track == null) {
            (getSystemService(NotificationManager::class.java)).cancel(PLAYBACK_NOTIFICATION_ID)
            mediaSession.isActive = false
            return
        }
        mediaSession.isActive = true
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration.takeIf { it > 0 } ?: track.duration * 1000L)
        notificationArtwork?.let {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        }
        mediaSession.setMetadata(metadata.build())
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, player.currentPosition, if (playing) 1f else 0f)
            .build())
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, MEDIA_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album.takeIf { it.isNotBlank() })
            .setLargeIcon(notificationArtwork)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", mediaActionIntent(MediaActionHandler.PREVIOUS, 1))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", mediaActionIntent(MediaActionHandler.PLAY_PAUSE, 2))
            .addAction(android.R.drawable.ic_media_next, "Next", mediaActionIntent(MediaActionHandler.NEXT, 3))
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        getSystemService(NotificationManager::class.java).notify(PLAYBACK_NOTIFICATION_ID, notification)
        loadNotificationArtwork(track)
    }

    private fun mediaActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun loadNotificationArtwork(track: Track) {
        if (track.cover.isBlank() || artworkRequestTrackId == track.id || artworkAttemptedTrackId == track.id) return
        artworkRequestTrackId = track.id
        artworkAttemptedTrackId = track.id
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = runCatching { java.net.URL(track.cover).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            withContext(Dispatchers.Main) {
                if (notificationTrack?.id == track.id) {
                    notificationArtwork = bitmap
                    artworkRequestTrackId = null
                    updateMediaNotification(track, notificationPlaying)
                }
            }
        }
    }

    companion object {
        private const val MEDIA_CHANNEL = "jamusic_media_playback"
        private const val PLAYBACK_NOTIFICATION_ID = 2048
    }

    private suspend fun loadPublicTracks(): List<Track> = withContext(Dispatchers.IO) {
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()) {
            "The app build is missing its Supabase URL or publishable key. Configure them for the release build."
        }
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val headers = { builder: Request.Builder -> builder.header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}") }
        // Keep this as a plain tracks query. PostgREST relationship embeds were returning HTTP 300
        // in the app, so artist profiles are resolved with one explicit id-in query below.
        val tracksRequest = headers(Request.Builder().url("$baseUrl/rest/v1/tracks?select=*&order=created_at.desc&limit=100")).build()
        val tracks = httpClient.newCall(tracksRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Supabase tracks query returned ${response.code}: ${body.take(220)}")
            JsonParser.parseString(body).asJsonArray.mapNotNull { row ->
                val obj = row.asJsonObject
                val audio = obj.get("audio_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (audio.isBlank()) null else Track(
                    id = obj.get("id")?.asString.orEmpty(),
                    ownerId = obj.get("user_id")?.asString.orEmpty(),
                    title = obj.get("title")?.asString ?: "Untitled",
                    artist = "JaMusic artist",
                    album = obj.get("album")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    cover = obj.get("cover_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    audio = audio,
                    lyrics = obj.get("lyrics")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    duration = obj.get("duration")?.asLong ?: 0L
                )
            }
        }
        val ownerIds = tracks.map { it.ownerId }.filter { it.isNotBlank() }.distinct()
        if (ownerIds.isEmpty()) return@withContext tracks
        val profileUrl = "$baseUrl/rest/v1/profiles?select=id,display_name,username&id=in.(${ownerIds.joinToString(",")})"
        val profileRequest = headers(Request.Builder().url(profileUrl)).build()
        val artists = httpClient.newCall(profileRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) emptyMap() else JsonParser.parseString(body).asJsonArray.associate { row ->
                val profile = row.asJsonObject
                val id = profile.get("id")?.asString.orEmpty()
                id to (profile.get("display_name")?.takeUnless { it.isJsonNull }?.asString
                    ?: profile.get("username")?.takeUnless { it.isJsonNull }?.asString ?: "JaMusic artist")
            }
        }
        tracks.map { it.copy(artist = artists[it.ownerId] ?: "JaMusic artist") }
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
            val userId = user?.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val expiry = System.currentTimeMillis() / 1000 + (payload.get("expires_in")?.takeUnless { it.isJsonNull }?.asLong ?: 3600L)
            getSharedPreferences("session", MODE_PRIVATE).edit().putString("email", userEmail).putString("user_id", userId)
                .putString("access_token", accessToken).putString("refresh_token", payload.get("refresh_token")?.takeUnless { it.isJsonNull }?.asString)
                .putLong("expires_at", expiry).apply()
            signedInAs = userEmail
            signedInUserId = userId
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

    private fun savedToken(): String = getSharedPreferences("session", MODE_PRIVATE).getString("access_token", null)
        ?: throw IllegalStateException("Sign in again to continue.")

    private fun authedRequest(url: String): Request.Builder = Request.Builder().url(url)
        .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        .header("Authorization", "Bearer ${savedToken()}")

    private suspend fun refreshSessionIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("session", MODE_PRIVATE)
        val access = prefs.getString("access_token", null) ?: return@withContext
        val claims = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(access.split('.')[1]))) }.getOrNull()
        val expiresAt = prefs.getLong("expires_at", claims?.optLong("exp", 0L) ?: 0L)
        if (expiresAt > System.currentTimeMillis() / 1000 + 90) return@withContext
        val refresh = prefs.getString("refresh_token", null)
            ?: throw IllegalStateException("Your sign-in expired. Please sign in again.")
        val payload = JSONObject().put("refresh_token", refresh).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).post(payload).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Your sign-in expired. Please sign in again. (${response.code})")
            val result = JsonParser.parseString(body).asJsonObject
            val nextAccess = result.get("access_token")?.takeUnless { it.isJsonNull }?.asString
                ?: throw IllegalStateException("Session refresh returned no access token. Please sign in again.")
            val nextRefresh = result.get("refresh_token")?.takeUnless { it.isJsonNull }?.asString ?: refresh
            val expiry = System.currentTimeMillis() / 1000 + (result.get("expires_in")?.asLong ?: 3600L)
            val user = result.getAsJsonObject("user")
            val id = user?.get("id")?.takeUnless { it.isJsonNull }?.asString ?: signedInUserId
            val email = user?.get("email")?.takeUnless { it.isJsonNull }?.asString ?: signedInAs
            prefs.edit().putString("access_token", nextAccess).putString("refresh_token", nextRefresh)
                .putLong("expires_at", expiry).putString("user_id", id).putString("email", email).apply()
            withContext(Dispatchers.Main) { signedInUserId = id; signedInAs = email }
        }
    }

    private suspend fun loadOrCreateProfile(userId: String): Profile = withContext(Dispatchers.IO) {
        refreshSessionIfNeeded()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val get = authedRequest("$base/rest/v1/profiles?select=*&id=eq.$userId").get().build()
        val found = httpClient.newCall(get).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Profile lookup failed (${response.code}): ${body.take(180)}")
            JsonParser.parseString(body).asJsonArray.firstOrNull()?.asJsonObject
        }
        if (found != null) return@withContext profileFromJson(found)
        val email = signedInAs.orEmpty()
        val seed = userId.filter { it.isLetterOrDigit() }.takeLast(6).lowercase().ifBlank { "artist" }
        val baseUsername = email.substringBefore('@').lowercase().replace(Regex("[^a-z0-9_]"), "_").take(12).trim('_').ifBlank { "artist" }
        val username = "${baseUsername}_$seed".take(20)
        val display = email.substringBefore('@').ifBlank { "Artist" }.replaceFirstChar { it.uppercase() }.take(40)
        val payload = JSONObject().put("id", userId).put("username", username).put("display_name", display)
        val create = authedRequest("$base/rest/v1/profiles").post(payload.toString().toRequestBody(jsonMediaType)).header("Prefer", "return=representation").build()
        httpClient.newCall(create).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Profile setup failed (${response.code}): ${body.take(180)}")
            profileFromJson(JsonParser.parseString(body).asJsonArray.first().asJsonObject)
        }
    }

    private fun profileFromJson(obj: com.google.gson.JsonObject) = Profile(
        id = obj.get("id")?.asString.orEmpty(),
        username = obj.get("username")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        displayName = obj.get("display_name")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        pronouns = obj.get("pronouns")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        bio = obj.get("bio")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        avatarUrl = obj.get("avatar_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        bannerUrl = obj.get("banner_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    )

    private suspend fun uploadObject(uri: Uri, bucket: String, userId: String, maxBytes: Int): Pair<String, String> = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read the selected file.")
        if (bytes.size > maxBytes) throw IllegalArgumentException("That file is too large (${maxBytes / 1024 / 1024} MB max).")
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"; "audio/ogg" -> "ogg"; "audio/wav" -> "wav"; else -> mime.substringAfter('/', "bin").substringBefore(';') }
        val path = "$userId/${java.util.UUID.randomUUID()}.$ext"
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val url = "$base/storage/v1/object/$bucket/${Uri.encode(path, "/")}"
        val request = authedRequest(url).post(bytes.toRequestBody(mime.toMediaType())).header("x-upsert", "false").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Upload to $bucket failed (${response.code}): ${body.take(180)}")
        }
        path to "$base/storage/v1/object/public/$bucket/${Uri.encode(path, "/")}"
    }

    private suspend fun updateProfile(profile: Profile, avatarUri: Uri?, bannerUri: Uri?): Profile = withContext(Dispatchers.IO) {
        refreshSessionIfNeeded()
        var avatar = profile.avatarUrl
        var banner = profile.bannerUrl
        if (avatarUri != null) avatar = uploadObject(avatarUri, "profiles", profile.id, 5 * 1024 * 1024).second
        if (bannerUri != null) banner = uploadObject(bannerUri, "profiles", profile.id, 5 * 1024 * 1024).second
        val payload = JSONObject().put("username", profile.username).put("display_name", profile.displayName)
            .put("pronouns", profile.pronouns.ifBlank { JSONObject.NULL }).put("bio", profile.bio.ifBlank { JSONObject.NULL })
            .put("avatar_url", avatar.ifBlank { JSONObject.NULL }).put("banner_url", banner.ifBlank { JSONObject.NULL })
        val request = authedRequest("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?id=eq.${profile.id}")
            .patch(payload.toString().toRequestBody(jsonMediaType)).header("Prefer", "return=minimal").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Profile save failed (${response.code}): ${body.take(180)}")
        }
        profile.copy(avatarUrl = avatar, bannerUrl = banner)
    }

    private suspend fun saveTrackLyrics(trackId: String, lyrics: String) = withContext(Dispatchers.IO) {
        refreshSessionIfNeeded()
        val payload = JSONObject().put("lyrics", lyrics.ifBlank { JSONObject.NULL })
        val request = authedRequest("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/tracks?id=eq.$trackId")
            .patch(payload.toString().toRequestBody(jsonMediaType)).header("Prefer", "return=minimal").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Lyrics save failed (${response.code}): ${body.take(180)}")
        }
    }

    private suspend fun uploadTrack(title: String, album: String, genre: String, lyricMode: String, lyricText: String, audioUri: Uri?, coverUri: Uri?): String = withContext(Dispatchers.IO) {
        refreshSessionIfNeeded()
        val userId = signedInUserId ?: throw IllegalStateException("Sign in before uploading.")
        val audio = audioUri ?: throw IllegalArgumentException("Choose an audio file.")
        val cover = coverUri ?: throw IllegalArgumentException("Choose cover artwork.")
        require(title.isNotBlank()) { "Enter a track title." }
        val coverUpload = uploadObject(cover, "artwork", userId, 5 * 1024 * 1024)
        val audioUpload = uploadObject(audio, "audio", userId, 50 * 1024 * 1024)
        var lyrics = when (lyricMode) {
            "instrumental" -> "This song is instrumental"
            "manual" -> lyricText.trim().takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Enter or import lyrics first.")
            else -> null
        }
        val durationMs = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, audio)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { retriever.release() }
        }.getOrDefault(0L)
        if (lyricMode == "auto") {
            val payload = JSONObject().put("audio_path", audioUpload.first)
            val request = authedRequest("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/generate-lyrics")
                .post(payload.toString().toRequestBody(jsonMediaType)).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("Auto lyrics failed (${response.code}): ${body.take(180)}")
                lyrics = JsonParser.parseString(body).asJsonObject.get("lyrics")?.asString
            }
        }
        val row = JSONObject().put("user_id", userId).put("title", title.trim()).put("album", album.trim().ifBlank { JSONObject.NULL })
            .put("genre", genre.trim().ifBlank { JSONObject.NULL }).put("lyrics", lyrics ?: JSONObject.NULL)
            .put("audio_url", audioUpload.second).put("audio_path", audioUpload.first)
            .put("cover_url", coverUpload.second).put("cover_path", coverUpload.first)
            .put("duration", durationMs / 1000)
        val insert = authedRequest("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/tracks")
            .post(row.toString().toRequestBody(jsonMediaType)).header("Prefer", "return=minimal").build()
        httpClient.newCall(insert).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Track publish failed (${response.code}): ${body.take(180)}")
        }
        "Track uploaded."
    }

    private fun signOut() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        signedInAs = null
        signedInUserId = null
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
            val claims = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(accessToken.split('.')[1]))) }.getOrNull()
            val email = params["user_email"] ?: claims?.optString("email")?.takeIf { it.isNotBlank() } ?: "Google account"
            val userId = claims?.optString("sub").orEmpty()
            val refreshToken = params["refresh_token"] ?: uri.getQueryParameter("refresh_token")
            val expiresIn = (params["expires_in"] ?: uri.getQueryParameter("expires_in"))?.toLongOrNull() ?: 3600L
            getSharedPreferences("session", MODE_PRIVATE).edit().putString("access_token", accessToken).putString("user_id", userId).putString("email", email)
                .putString("refresh_token", refreshToken).putLong("expires_at", System.currentTimeMillis() / 1000 + expiresIn).apply()
            signedInAs = email
            signedInUserId = userId
            authNotice = "Signed in with Google."
        } else if (error != null) authNotice = error
    }

    override fun onDestroy() {
        MediaActionHandler.callback = null
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) {
            getSystemService(NotificationManager::class.java).cancel(PLAYBACK_NOTIFICATION_ID)
            player.release()
        }
        super.onDestroy()
    }
}

private object MediaActionHandler {
    const val PLAY_PAUSE = "com.jamusic.app.MEDIA_PLAY_PAUSE"
    const val NEXT = "com.jamusic.app.MEDIA_NEXT"
    const val PREVIOUS = "com.jamusic.app.MEDIA_PREVIOUS"
    @Volatile var callback: ((String) -> Unit)? = null
}

class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MediaActionHandler.callback?.invoke(intent.action.orEmpty())
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
    val cover: String, val audio: String, val lyrics: String, val duration: Long,
    val ownerId: String = ""
)
private data class Profile(
    val id: String, val username: String, val displayName: String, val pronouns: String,
    val bio: String, val avatarUrl: String, val bannerUrl: String
)
private data class LyricWord(val text: String, val at: Double)
private data class LyricLine(val text: String, val at: Double, val words: List<LyricWord>, val timed: Boolean = true)

private fun formatCue(seconds: Double): String {
    val centiseconds = (seconds.coerceAtLeast(0.0) * 100).toLong()
    val minutes = centiseconds / 6000
    val secs = (centiseconds / 100) % 60
    val hundredths = centiseconds % 100
    return "%02d:%02d.%02d".format(minutes, secs, hundredths)
}

private fun cueSeconds(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> {
        val match = Regex("^(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?$").matchEntire(value.trim())
        match?.let {
            val fraction = (it.groupValues[3].ifBlank { "0" } + "00").take(2).toIntOrNull() ?: 0
            it.groupValues[1].toDouble() * 60 + it.groupValues[2].toDouble() + fraction / 100.0
        }
    }
    else -> null
}

private fun importLyricsFile(name: String, source: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension == "lrc") {
        val output = source.lineSequence().mapNotNull { raw ->
            val m = Regex("^\\[(\\d{1,3}):(\\d{2})\\.(\\d{1,3})\\](.*)$").matchEntire(raw.trim()) ?: return@mapNotNull null
            val fraction = (m.groupValues[3] + "00").take(2).toIntOrNull() ?: 0
            val at = m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble() + fraction / 100.0
            var body = m.groupValues[4].trim()
            body = Regex("<(\\d{1,3}):(\\d{2})\\.(\\d{1,3})>").replace(body) { match ->
                val hundredths = (match.groupValues[3] + "00").take(2).toIntOrNull() ?: 0
                val wordAt = match.groupValues[1].toDouble() * 60 + match.groupValues[2].toDouble() + hundredths / 100.0
                "<${formatCue(wordAt)}>"
            }
            "[${formatCue(at)}]${if (body.startsWith("<")) "" else " "}$body"
        }.toList()
        if (output.isEmpty()) throw IllegalArgumentException("No timestamped lines found in that LRC file.")
        return output.joinToString("\n")
    }
    if (extension != "yaml" && extension != "yml") throw IllegalArgumentException("Choose a .lrc, .yaml, or .yml file.")
    val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(source) as? Map<*, *>
        ?: throw IllegalArgumentException("YAML must contain a top-level lines list.")
    val rows = root["lines"] as? List<*> ?: throw IllegalArgumentException("YAML must contain a top-level lines list.")
    if (rows.isEmpty()) throw IllegalArgumentException("The YAML lines list is empty.")
    return rows.mapIndexed { index, raw ->
        val line = raw as? Map<*, *> ?: throw IllegalArgumentException("YAML line ${index + 1} must be an object.")
        val at = if (line["start_ms"] is Number) (line["start_ms"] as Number).toDouble() / 1000.0 else cueSeconds(line["time"] ?: line["timestamp"])
            ?: throw IllegalArgumentException("YAML line ${index + 1} needs start_ms or time.")
        val words = line["words"] as? List<*>
        val text = if (!words.isNullOrEmpty()) {
            words.mapIndexed { wordIndex, rawWord ->
                val word = rawWord as? Map<*, *> ?: throw IllegalArgumentException("Invalid word on line ${index + 1}.")
                val wordAt = if (word["start_ms"] is Number) (word["start_ms"] as Number).toDouble() / 1000.0 else cueSeconds(word["time"])
                    ?: throw IllegalArgumentException("Word on line ${index + 1} needs start_ms or time.")
                val wordText = word["text"]?.toString() ?: throw IllegalArgumentException("Word on line ${index + 1} needs text.")
                "<${formatCue(wordAt)}>$wordText${if (wordIndex < words.lastIndex && wordText.lastOrNull()?.isWhitespace() != true) " " else ""}"
            }.joinToString("")
        } else (line["text"] as? String)?.trim().orEmpty().ifBlank { throw IllegalArgumentException("YAML line ${index + 1} needs text or words.") }
        "[${formatCue(at)}]${if (text.startsWith("<")) "" else " "}$text"
    }.joinToString("\n")
}

private fun parseLyrics(raw: String): List<LyricLine> {
    val linePattern = Regex("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)$")
    val rows = raw.lineSequence().toList()
    val timedTrack = rows.any { linePattern.matches(it.trim()) }
    return rows.mapNotNull { rawRow ->
        val row = rawRow.trim()
        if (row.isBlank()) return@mapNotNull null
        val line = linePattern.matchEntire(row)
        if (line == null) {
            if (timedTrack && row.startsWith("[") && row.contains(":") && row.contains("]")) return@mapNotNull null
            return@mapNotNull LyricLine(row, 0.0, emptyList(), timed = false)
        }
        fun seconds(min: String, sec: String, fraction: String): Double {
            val hundredths = (fraction + "00").take(2).toIntOrNull() ?: 0
            return min.toDouble() * 60 + sec.toDouble() + hundredths / 100.0
        }
        val start = seconds(line.groupValues[1], line.groupValues[2], line.groupValues[3])
        val body = line.groupValues[4]
        val cue = Regex("<(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?>")
        val matches = cue.findAll(body).toList()
        val words = matches.mapIndexedNotNull { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val text = body.substring(match.range.last + 1, end)
            if (text.isBlank()) null else LyricWord(text, seconds(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        }
        val plain = if (words.isEmpty()) body.trim() else words.joinToString("") { it.text }.trim()
        if (plain.isBlank()) null else LyricLine(plain, start, words)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JaMusicApp(
    player: ExoPlayer,
    loadTracks: suspend () -> List<Track>,
    signedInAs: String?,
    signedInUserId: String?,
    authNotice: String?,
    loadProfile: suspend (String) -> Profile,
    saveProfile: suspend (Profile, Uri?, Uri?) -> Profile,
    uploadTrack: suspend (String, String, String, String, String, Uri?, Uri?) -> String,
    saveLyrics: suspend (String, String) -> Unit,
    onNowPlayingChanged: (Track?, Boolean) -> Unit,
    onEmailAuth: suspend (String, String, Boolean) -> String,
    onGoogleAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var activeTrack by remember { mutableStateOf<Track?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
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
    var profile by remember { mutableStateOf<Profile?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var profileSaving by remember { mutableStateOf(false) }
    var showUploadPrompt by remember { mutableStateOf(false) }
    var lyricsEditing by remember { mutableStateOf(false) }
    var lyricsSaving by remember { mutableStateOf(false) }
    var lyricsError by remember { mutableStateOf<String?>(null) }
    var uploadBusy by remember { mutableStateOf(false) }
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshTracks() {
        loading = true
        loadError = null
        try { tracks = loadTracks() } catch (error: Exception) { loadError = error.message ?: "Couldn't load uploaded songs." }
        loading = false
    }
    LaunchedEffect(Unit) { refreshTracks() }
    LaunchedEffect(signedInAs) { if (signedInAs != null) showAuthPrompt = false }
    LaunchedEffect(signedInUserId) {
        profile = null
        profileError = null
        if (signedInUserId != null) {
            try { profile = loadProfile(signedInUserId) }
            catch (error: Exception) { profileError = error.message ?: "Couldn't load your profile." }
        }
    }
    LaunchedEffect(activeTrack, playing) { onNowPlayingChanged(activeTrack, playing) }
    LaunchedEffect(shuffleEnabled, repeatMode) {
        player.shuffleModeEnabled = shuffleEnabled
        player.repeatMode = repeatMode
    }
    DisposableEffect(player, tracks) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMs = 0
                durationMs = player.duration.coerceAtLeast(0)
                tracks.firstOrNull { it.id == mediaItem?.mediaId }?.let { activeTrack = it }
            }
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
                    IconButton(onClick = { if (signedInAs == null) { authError = null; showAuthPrompt = true } else showProfileEditor = true }) {
                        Surface(shape = CircleShape, color = Panel, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                val avatar = profile?.avatarUrl.orEmpty()
                                if (avatar.isNotBlank()) AsyncImage(avatar, contentDescription = "Your profile", modifier = Modifier.fillMaxSize().clip(CircleShape))
                                else Text(profile?.displayName?.take(1)?.uppercase() ?: "J", color = Violet, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (tab == 2) {
                item {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                        Text("Your library", fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold, color = Cloud)
                        Spacer(Modifier.height(8.dp))
                        if (signedInAs == null) Text("Sign in to upload music and manage your artist profile.", color = Muted)
                        else {
                            Text(profile?.displayName ?: signedInAs, color = Muted, style = MaterialTheme.typography.titleMedium)
                            profile?.let { Text("@${it.username}", color = Violet, style = MaterialTheme.typography.bodySmall) }
                        }
                        profileError?.let { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(16.dp))
                        if (signedInAs == null) Button(onClick = { authError = null; showAuthPrompt = true }, shape = RoundedCornerShape(24.dp)) { Text("Sign in to JaMusic") }
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { uploadMessage = null; showUploadPrompt = true }, shape = RoundedCornerShape(22.dp)) { Text("Upload track") }
                            OutlinedButton(onClick = { showProfileEditor = true }, shape = RoundedCornerShape(22.dp), enabled = profile != null) { Text("Edit profile") }
                            TextButton(onClick = onSignOut) { Text("Sign out") }
                        }
                    }
                }
                item {
                    Text("Liked songs", Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp), color = Cloud, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                itemsIndexed(tracks.filter { it.id in favorites }) { index, track ->
                    TrackRow(track, index, isFavorite = true, onFavorite = { favorites = favorites - track.id }, onClick = { startTrack(player, track, tracks) { activeTrack = it; playerOpen = true } })
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
                    LatestUploadCard(latestTrack, onClick = { startTrack(player, latestTrack, tracks) { activeTrack = it; playerOpen = true } })
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
                        onClick = { startTrack(player, track, tracks) { activeTrack = it; playerOpen = true } })
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
            onPrev = {
                if (player.currentPosition > 3_000L) player.seekTo(0)
                else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                else playRelative(tracks, track, -1, player) { activeTrack = it }
            },
            onNext = {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                else playRelative(tracks, track, 1, player) { activeTrack = it }
            },
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            onToggleShuffle = { shuffleEnabled = !shuffleEnabled },
            onCycleRepeat = { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } },
            canEditLyrics = signedInUserId != null && signedInUserId == track.ownerId,
            onEditLyrics = { lyricsEditing = true; lyricsError = null }
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
    if (showProfileEditor && profile != null) {
        ProfileEditor(
            profile = profile!!, busy = profileSaving, error = profileError,
            onDismiss = { showProfileEditor = false },
            onSave = { changed, avatar, banner ->
                scope.launch {
                    profileSaving = true; profileError = null
                    try { profile = saveProfile(changed, avatar, banner); showProfileEditor = false }
                    catch (error: Exception) { profileError = error.message ?: "Couldn't save profile." }
                    finally { profileSaving = false }
                }
            }
        )
    }
    if (lyricsEditing && activeTrack != null) {
        LyricsEditorDialog(
            initialLyrics = activeTrack!!.lyrics, busy = lyricsSaving, error = lyricsError,
            onDismiss = { lyricsEditing = false },
            onSave = { value ->
                scope.launch {
                    lyricsSaving = true; lyricsError = null
                    try {
                        saveLyrics(activeTrack!!.id, value)
                        activeTrack = activeTrack!!.copy(lyrics = value)
                        tracks = tracks.map { if (it.id == activeTrack!!.id) it.copy(lyrics = value) else it }
                        lyricsEditing = false
                    } catch (error: Exception) { lyricsError = error.message ?: "Couldn't save lyrics." }
                    finally { lyricsSaving = false }
                }
            }
        )
    }
    if (showUploadPrompt) {
        UploadPrompt(
            busy = uploadBusy, message = uploadMessage,
            onDismiss = { if (!uploadBusy) showUploadPrompt = false },
            onUpload = { title, album, genre, mode, text, audio, cover ->
                scope.launch {
                    uploadBusy = true; uploadMessage = "Uploading files…"
                    try {
                        uploadMessage = uploadTrack(title, album, genre, mode, text, audio, cover)
                        refreshTracks()
                        showUploadPrompt = false
                    } catch (error: Exception) { uploadMessage = error.message ?: "Couldn't upload this track." }
                    finally { uploadBusy = false }
                }
            }
        )
    }
}

@Composable
private fun LyricsEditorDialog(initialLyrics: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initialLyrics) { mutableStateOf(initialLyrics) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit synced lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Edit lyrics only. Keep timestamps in [MM:SS.hh] format; enhanced word cues are preserved.", color = Muted)
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 440.dp), label = { Text("Lyrics") }, minLines = 8, maxLines = 18)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { onSave(value) }, enabled = !busy) { Text(if (busy) "Saving…" else "Save lyrics") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun ProfileEditor(profile: Profile, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (Profile, Uri?, Uri?) -> Unit) {
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var display by remember(profile.id) { mutableStateOf(profile.displayName) }
    var pronouns by remember(profile.id) { mutableStateOf(profile.pronouns) }
    var bio by remember(profile.id) { mutableStateOf(profile.bio) }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var bannerUri by remember { mutableStateOf<Uri?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { avatarUri = it }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { bannerUri = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit your profile") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val context = LocalContext.current
                val avatarModel: Any? = avatarUri ?: profile.avatarUrl.takeIf { it.isNotBlank() }
                Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Violet, Panel))), contentAlignment = Alignment.Center) {
                    val bannerModel: Any? = bannerUri ?: profile.bannerUrl.takeIf { it.isNotBlank() }
                    if (bannerModel != null) AsyncImage(bannerModel, null, Modifier.fillMaxSize())
                    Text("Profile banner", color = Cloud)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { bannerPicker.launch(arrayOf("image/*")) }) { Text(if (bannerUri != null || profile.bannerUrl.isNotBlank()) "Change banner" else "Add banner") }
                    OutlinedButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) { Text(if (avatarUri != null || profile.avatarUrl.isNotBlank()) "Change photo" else "Add photo") }
                }
                if (avatarModel != null) AsyncImage(avatarModel, "Profile photo", Modifier.size(72.dp).clip(CircleShape))
                OutlinedTextField(display, { display = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                OutlinedTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) }, modifier = Modifier.fillMaxWidth(), label = { Text("Username") }, prefix = { Text("@") }, singleLine = true)
                OutlinedTextField(pronouns, { pronouns = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pronouns") }, singleLine = true)
                OutlinedTextField(bio, { bio = it.take(280) }, modifier = Modifier.fillMaxWidth(), label = { Text("Bio") }, minLines = 2, maxLines = 4)
                (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (username.length !in 3..20 || display.isBlank()) localError = "Enter a display name and a 3–20 character username."
                else onSave(profile.copy(username = username, displayName = display.trim(), pronouns = pronouns.trim(), bio = bio.trim()), avatarUri, bannerUri)
            }, enabled = !busy) { Text(if (busy) "Saving…" else "Save profile") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun UploadPrompt(busy: Boolean, message: String?, onDismiss: () -> Unit, onUpload: (String, String, String, String, String, Uri?, Uri?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var lyricError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { audioUri = it }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { coverUri = it }
    val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else uri.lastPathSegment.orEmpty()
                } ?: uri.lastPathSegment.orEmpty()
                val source = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Couldn't read that lyrics file.")
                lyrics = importLyricsFile(name, source)
                lyricError = "Imported $name. Review the cues before uploading."
            } catch (error: Exception) { lyricError = error.message ?: "Couldn't import that lyrics file." }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Upload a track") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Upload audio and artwork, add metadata, and choose how lyrics are added.", color = Muted)
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Track title") }, singleLine = true)
                OutlinedTextField(album, { album = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Album (optional)") }, singleLine = true)
                OutlinedTextField(genre, { genre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Genre (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { coverPicker.launch("image/*") }) { Text(if (coverUri == null) "Choose artwork" else "Artwork selected") }
                    OutlinedButton(onClick = { audioPicker.launch("audio/*") }) { Text(if (audioUri == null) "Choose audio" else "Audio selected") }
                }
                Text("LYRICS", color = Violet, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.6.sp)
                listOf("auto" to "Auto-generate with JaMusic", "manual" to "Type or import lyrics", "instrumental" to "Instrumental").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { mode = value }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == value, onClick = { mode = value })
                        Text(label, color = Cloud)
                    }
                }
                if (mode == "manual") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { lyricPicker.launch(arrayOf("*/*")) }) { Text("Import LRC / YAML") }
                    }
                    OutlinedTextField(lyrics, { lyrics = it; lyricError = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Timestamped lyrics") }, placeholder = { Text("[00:12.34] Words here") }, minLines = 4, maxLines = 8)
                    Text("LRC, YAML start_ms word cues, or [MM:SS.hh] lines are supported.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    lyricError?.let { Text(it, color = if (it.startsWith("Imported")) Lime else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                message?.let { Text(it, color = if (busy) Violet else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isBlank()) lyricError = "Enter a track title."
                else if (coverUri == null) lyricError = "Choose artwork."
                else if (audioUri == null) lyricError = "Choose an audio file."
                else if (mode == "manual" && lyrics.isBlank()) lyricError = "Type lyrics or import an LRC/YAML file."
                else onUpload(title, album, genre, mode, lyrics, audioUri, coverUri)
            }, enabled = !busy) { Text(if (busy) "Uploading…" else "Upload") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
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
private fun startTrack(player: ExoPlayer, track: Track, queue: List<Track>, select: (Track) -> Unit) {
    select(track)
    if (track.audio.isNotBlank()) {
        val playlist = (queue.ifEmpty { listOf(track) }).filter { it.audio.isNotBlank() }
        val selectedIndex = playlist.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val mediaItems = playlist.map { item ->
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(item.title).setArtist(item.artist)
                .apply { if (item.cover.isNotBlank()) setArtworkUri(Uri.parse(item.cover)) }
                .build()
            MediaItem.Builder().setMediaId(item.id).setUri(item.audio).setMediaMetadata(metadata).build()
        }
        player.setMediaItems(mediaItems, selectedIndex, 0L)
        player.prepare()
        player.playWhenReady = true
    }
}
private fun playRelative(tracks: List<Track>, current: Track, step: Int, player: ExoPlayer, select: (Track) -> Unit) {
    if (tracks.isEmpty()) return
    val index = tracks.indexOfFirst { it.id == current.id }.let { if (it < 0) 0 else it }
    startTrack(player, tracks[(index + step + tracks.size) % tracks.size], tracks, select)
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
        if (track.cover.isNotBlank()) AsyncImage(track.cover, contentDescription = "${track.title} cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
private fun PlayerSheet(track: Track, player: ExoPlayer, playing: Boolean, currentMs: Long, durationMs: Long, isFavorite: Boolean, onFavorite: () -> Unit, onDismiss: () -> Unit, onToggle: () -> Unit, onSeek: (Long) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, shuffleEnabled: Boolean, repeatMode: Int, onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit, canEditLyrics: Boolean, onEditLyrics: () -> Unit) {
    val lines = remember(track.lyrics) { parseLyrics(track.lyrics) }
    val currentSeconds = currentMs / 1000.0
    val currentLine = if (lines.any { it.timed }) lines.indexOfLast { it.timed && it.at <= currentSeconds } else -1
    val lyricsListState = rememberLazyListState()
    LaunchedEffect(currentLine, lines.size) {
        if (currentLine >= 0 && currentLine < lines.size) lyricsListState.animateScrollToItem(currentLine)
    }
    val coverSize = minOf(280.dp, LocalConfiguration.current.screenHeightDp.dp * .29f)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink, contentColor = Cloud, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = Muted) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 24.dp)) {
            Cover(track, Modifier.size(coverSize).align(Alignment.CenterHorizontally), track.title.length)
            Spacer(Modifier.height(12.dp))
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
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleShuffle) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = if (shuffleEnabled) Lime else Muted)
                    Spacer(Modifier.width(6.dp)); Text("Shuffle", color = if (shuffleEnabled) Lime else Muted)
                }
                TextButton(onClick = onCycleRepeat) {
                    Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, contentDescription = null, tint = if (repeatMode == Player.REPEAT_MODE_OFF) Muted else Lime)
                    Spacer(Modifier.width(6.dp)); Text(when (repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat queue"; Player.REPEAT_MODE_ONE -> "Repeat once"; else -> "Repeat off" }, color = if (repeatMode == Player.REPEAT_MODE_OFF) Muted else Lime)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LYRICS", Modifier.weight(1f), color = Violet, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                if (canEditLyrics) TextButton(onClick = onEditLyrics) { Text("Edit lyrics") }
            }
            if (lines.isEmpty()) Text("No lyrics yet", color = Muted, style = MaterialTheme.typography.headlineSmall)
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lyricsListState, contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                itemsIndexed(lines) { index, line ->
                    val isCurrent = index == currentLine
                    val lineColor by animateColorAsState(if (isCurrent) Cloud else Muted.copy(alpha = if (index < currentLine) .43f else .58f), animationSpec = spring(stiffness = Spring.StiffnessLow), label = "line-color")
                    Column(Modifier.fillMaxWidth().then(if (line.timed) Modifier.clickable { onSeek((line.at * 1000).toLong()) } else Modifier).padding(vertical = 1.dp)) {
                        if (line.words.isEmpty()) Text(line.text, color = lineColor, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium)
                        else WordLine(line.words, currentSeconds, lineColor) { wordAt -> onSeek(wordAt) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordLine(words: List<LyricWord>, now: Double, lineColor: Color, onSeek: (Long) -> Unit) {
    Row { // FlowRow is intentionally avoided: word order and spaces remain stable across line breaks.
        val annotatedLyrics = androidx.compose.ui.text.buildAnnotatedString {
                words.forEachIndexed { index, word ->
                    val lit = word.at <= now
                    val active = lit && (words.getOrNull(index + 1)?.at ?: Double.POSITIVE_INFINITY) > now
                    val color = when { active -> Lime; lit -> Cloud; else -> lineColor.copy(alpha = .52f) }
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium))
                    pushStringAnnotation("word-time", word.at.toString())
                    append(word.text)
                    pop()
                    pop()
                    if (index < words.lastIndex && word.text.lastOrNull()?.isWhitespace() != true) append(" ")
                }
        }
        androidx.compose.foundation.text.ClickableText(
            text = annotatedLyrics,
            style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 33.sp),
            onClick = { offset ->
                annotatedLyrics.getStringAnnotations("word-time", offset, offset).firstOrNull()?.item?.toDoubleOrNull()?.let { onSeek((it * 1000).toLong()) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
