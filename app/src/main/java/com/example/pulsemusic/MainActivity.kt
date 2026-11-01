package com.example.pulsemusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val base = Color(0xFF101010)
private val surface = Color(0xFF232323)
private val surfaceAlt = Color(0xFF303030)
private val ink = Color(0xFFF7F8FA)
private val muted = Color(0xFFB3B3B3)
private val accent = Color(0xFF1ED760)
private val colors = listOf(
    listOf(Color(0xFF5B3BBF), Color(0xFFCB6AA4), Color(0xFFEC9C73)),
    listOf(Color(0xFF1B507F), Color(0xFF4C84C6), Color(0xFF53C5B7)),
    listOf(Color(0xFF654C40), Color(0xFFB28B69), Color(0xFFCFB6A2)),
    listOf(Color(0xFF15584F), Color(0xFF32908B), Color(0xFF8AC8A6)),
    listOf(Color(0xFF5B2853), Color(0xFFAE477C), Color(0xFFEA8179))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 16, 16)
        window.navigationBarColor = android.graphics.Color.rgb(16, 16, 16)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = accent, onPrimary = base, background = base,
                surface = surface, onSurface = ink,
                onBackground = ink, secondary = Color(0xFFBEA8FF)
            )) { MusicApp() }
        }
    }
}

private enum class Page { HOME, SEARCH, LIBRARY }
private enum class LibraryFilter { ALL, DOWNLOADED, FAVORITES }

@Composable
private fun MusicApp() {
    val context = LocalContext.current
    val repository = remember { MusicRepository(context) }
    val playlistStore = remember { PlaylistImportStore(context) }
    val localPlaylistStore = remember { LocalPlaylists(context) }
    var localPlaylists by remember { mutableStateOf(localPlaylistStore.list()) }
    var importedPlaylists by remember { mutableStateOf(playlistStore.savedPlaylists()) }
    val engine = remember { PlayerEngine(context, repository) }
    var deviceSongs by remember { mutableStateOf(repository.importedFiles()) }
    var discoveredSongs by remember { mutableStateOf(repository.discoveredTracks()) }
    var favorites by remember { mutableStateOf(repository.favorites()) }
    val downloaded = remember {
        mutableStateMapOf<String, Boolean>().apply {
            (starterCatalog + repository.discoveredTracks()).forEach { put(it.id, repository.offlinePath(it) != null) }
        }
    }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val songs = remember(deviceSongs, discoveredSongs) {
        (starterCatalog + deviceSongs + discoveredSongs).distinctBy { it.id }
    }
    var page by remember { mutableStateOf(Page.HOME) }
    var fullPlayer by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            deviceSongs = repository.addDeviceFiles(uris)
            page = Page.LIBRARY
            scope.launch { snackbar.showSnackbar("Added ${uris.size} audio file(s) to your library") }
        }
    }
    val spotifyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { playlistStore.importSpotifyExport(uri) }
                .onSuccess { playlists ->
                    importedPlaylists = playlists
                    page = Page.LIBRARY
                    snackbar.showSnackbar("Imported ${playlists.size} playlist(s) · titles and artists only")
                }
                .onFailure { error ->
                    snackbar.showSnackbar("Import failed: ${error.message ?: "Invalid playlist file"}")
                }
        }
    }
    DisposableEffect(engine) {
        engine.connect()
        onDispose { engine.release() }
    }
    LaunchedEffect(engine) {
        while (isActive) {
            engine.updatePosition()
            // Update the timeline once per second when playing; no rapid redraws when paused.
            delay(if (engine.isPlaying) 1_000 else 2_500)
        }
    }
    LaunchedEffect(songs) { engine.setTracks(songs) }
    LaunchedEffect(engine.error) {
        val message = engine.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        engine.dismissError()
    }

    fun toggleFavorite(song: Song) {
        favorites = if (song.id in favorites) favorites - song.id else favorites + song.id
        repository.setFavorites(favorites)
    }
    fun startDownload(song: Song) {
        if (song.isDeviceFile || downloaded[song.id] == true || progress.containsKey(song.id)) return
        progress[song.id] = 0f
        scope.launch {
            try {
                repository.downloadSong(song) { percent ->
                    withContext(Dispatchers.Main) { progress[song.id] = percent }
                }
                downloaded[song.id] = true
                snackbar.showSnackbar("${song.title} saved for offline listening")
            } catch (t: Throwable) {
                snackbar.showSnackbar("Download failed: ${t.message ?: "check your internet"}")
            } finally {
                progress.remove(song.id)
            }
        }
    }
    fun play(song: Song, queue: List<Song>) { engine.play(song, queue) }

    Scaffold(
        containerColor = base,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!fullPlayer) Column(modifier = Modifier.navigationBarsPadding().background(base)) {
                engine.current?.let { song ->
                    MiniPlayer(song, engine, onExpand = { fullPlayer = true })
                }
                Row(
                    Modifier.fillMaxWidth().height(68.dp).background(base),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NavItem("Home", Icons.Default.Home, page == Page.HOME) { page = Page.HOME }
                    NavItem("Search", Icons.Default.Search, page == Page.SEARCH) { page = Page.SEARCH }
                    NavItem("Your Library", Icons.Default.LibraryMusic, page == Page.LIBRARY) { page = Page.LIBRARY }
                }
            }
        }
    ) { padding ->
        if (fullPlayer && engine.current != null) {
            FullPlayer(
                song = engine.current!!,
                engine = engine,
                favorite = engine.current!!.id in favorites,
                offline = downloaded[engine.current!!.id] == true,
                progress = progress[engine.current!!.id],
                onBack = { fullPlayer = false },
                onFavorite = { engine.current?.let(::toggleFavorite) },
                onDownload = { engine.current?.let(::startDownload) },
                userPlaylists = localPlaylists,
                onAddToPlaylist = { playlistId ->
                    engine.current?.let { song ->
                        localPlaylists = localPlaylistStore.add(playlistId, song.id)
                        scope.launch { snackbar.showSnackbar("Added to playlist") }
                    }
                },
                onAttribution = { engine.current?.sourcePage?.takeIf(String::isNotBlank)?.let {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                } },
                modifier = Modifier.padding(padding)
            )
        } else {
            when (page) {
                Page.HOME -> HomeScreen(
                    songs = songs,
                    favorites = favorites,
                    offline = downloaded,
                    progress = progress,
                    onPlay = { play(it, songs) },
                    onFavorite = ::toggleFavorite,
                    onDownload = ::startDownload,
                    onImport = { filePicker.launch(arrayOf("audio/*")) },
                    onGoLibrary = { page = Page.LIBRARY },
                    modifier = Modifier.padding(padding)
                )
                Page.SEARCH -> SearchScreen(
                    songs = songs,
                    offline = downloaded,
                    progress = progress,
                    onPlay = { play(it, songs) },
                    onDownload = ::startDownload,
                    onOnlineResults = { results ->
                        if (results.isNotEmpty()) {
                            discoveredSongs = repository.rememberSearchResults(results)
                            results.forEach { song -> downloaded[song.id] = repository.offlinePath(song) != null }
                        }
                    },
                    modifier = Modifier.padding(padding)
                )
                Page.LIBRARY -> LibraryScreen(
                    songs = songs,
                    favorites = favorites,
                    downloaded = downloaded,
                    progress = progress,
                    importedPlaylists = importedPlaylists,
                    localPlaylists = localPlaylists,
                    onCreatePlaylist = { title ->
                        runCatching { localPlaylistStore.create(title) }.onSuccess {
                            localPlaylists = it
                        }.onFailure { error ->
                            scope.launch { snackbar.showSnackbar(error.message ?: "Unable to create playlist") }
                        }
                    },
                    onRemoveFromPlaylist = { playlistId, songId ->
                        localPlaylists = localPlaylistStore.remove(playlistId, songId)
                    },
                    onImportSpotify = { spotifyFilePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onPlay = { song, queue -> play(song, queue) },
                    onFavorite = ::toggleFavorite,
                    onDownload = ::startDownload,
                    onImport = { filePicker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun NavItem(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(105.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, title, tint = if (selected) ink else muted, modifier = Modifier.size(25.dp))
        Text(title, fontSize = 11.sp, color = if (selected) ink else muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** Small, bounded cache so fast scrolling never decodes audio metadata on the UI thread. */
private object AlbumArtwork {
    private val memory = object : LruCache<String, ImageBitmap>(10 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height * 4L) / 1024L).coerceAtLeast(1).toInt()
    }
    private val withoutArtwork = mutableSetOf<String>()

    fun cached(id: String): ImageBitmap? = memory.get(id)

    suspend fun load(context: Context, song: Song): ImageBitmap? {
        if (!song.isDeviceFile) return null
        memory.get(song.id)?.let { return it }
        if (synchronized(withoutArtwork) { song.id in withoutArtwork }) return null

        // MediaMetadataRetriever and decoding can take hundreds of milliseconds on phones.
        // Never perform either operation during composition or a scroll frame.
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                val bytes = try {
                    retriever.setDataSource(context, Uri.parse(song.streamUrl))
                    retriever.embeddedPicture
                } finally {
                    retriever.release()
                }
                bytes?.let { data ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    var factor = 1
                    while (bounds.outWidth / factor > 480 || bounds.outHeight / factor > 480) {
                        factor *= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = factor
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (image == null) synchronized(withoutArtwork) { withoutArtwork.add(song.id) }
        else memory.put(song.id, image)
        return image
    }
}

@Composable
private fun MusicCover(song: Song, size: Dp, modifier: Modifier = Modifier, radius: Dp = 5.dp) {
    val context = LocalContext.current.applicationContext
    val albumArt by produceState<ImageBitmap?>(initialValue = AlbumArtwork.cached(song.id), key1 = song.id) {
        value = AlbumArtwork.load(context, song)
    }
    val palette = colors[song.gradient % colors.size]
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(palette[0], palette[1], palette[2]))),
        contentAlignment = Alignment.Center
    ) {
        val picture = albumArt
        if (picture != null) {
            Image(bitmap = picture, contentDescription = "Artwork for ${song.title}",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.11f),
                    radius = this.size.minDimension * .50f,
                    center = Offset(this.size.width * .83f, this.size.height * .88f)
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.18f),
                    radius = this.size.minDimension * .38f,
                    center = Offset(this.size.width * .18f, this.size.height * .16f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.12f),
                    radius = this.size.minDimension * .17f,
                    center = Offset(this.size.width * .84f, this.size.height * .86f)
                )
            }
            Column(Modifier.fillMaxSize().padding((size.value * .09f).dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (song.isDeviceFile) "YOUR MUSIC" else "PULSE ORIGINALS",
                    fontSize = (size.value * .07f).coerceIn(6f, 11f).sp,
                    fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = .78f),
                    letterSpacing = .5.sp, maxLines = 1)
                Column {
                    Text(song.title,
                        fontSize = (size.value * .09f).coerceIn(10f, 19f).sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = (size.value * .11f).coerceIn(12f, 21f).sp,
                        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.substringBefore("·").trim(),
                        fontSize = (size.value * .07f).coerceIn(7f, 11f).sp,
                        color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    songs: List<Song>, favorites: Set<String>, offline: Map<String, Boolean>, progress: Map<String, Float>,
    onPlay: (Song) -> Unit, onFavorite: (Song) -> Unit, onDownload: (Song) -> Unit,
    onImport: () -> Unit, onGoLibrary: () -> Unit, modifier: Modifier = Modifier
) {
    var homeFilter by remember { mutableStateOf("All") }
    val listed = remember(songs, homeFilter, offline.toMap()) {
        when(homeFilter) {
            "Downloaded" -> songs.filter { it.isDeviceFile || offline[it.id] == true }
            "Music" -> songs.filter { !it.isDeviceFile }
            else -> songs
        }
    }
    val device = listed.filter { it.isDeviceFile }
    val electronic = listed.filter { it.genre == "Electronic" }.take(6)
    val slow = listed.filter { it.genre == "Classical" || it.genre == "Ambient" }.take(6)
    val discovery = listed.filter { !it.isDeviceFile && it.genre == "Indie" } +
        listed.filter { !it.isDeviceFile && it.genre == "Electronic" }.take(3)
    val featured = listed.firstOrNull { it.genre == "Indie" } ?: listed.firstOrNull()

    LazyColumn(
        modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xFF203328), Color(0xFF121914), base, base), endY = 960f
        )),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF7752A4)),
                    contentAlignment = Alignment.Center) {
                    Text("P", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
                listOf("All", "Music", "Downloaded").forEach { item ->
                    val selected = item == homeFilter
                    Box(Modifier.clip(CircleShape)
                        .background(if (selected) accent else Color(0xFF2B302D))
                        .clickable { homeFilter = item }
                        .padding(horizontal = 15.dp, vertical = 9.dp)) {
                        Text(item, color = if (selected) Color.Black else ink,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (homeFilter != "Downloaded") {
            item {
                Row(Modifier.fillMaxWidth().height(166.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF1F5F4B), Color(0xFF204E4B), Color(0xFF202F44))))
                    .clickable { featured?.let(onPlay) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("DISCOVER • NO ADS", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.3.sp, color = Color.White.copy(alpha = .78f))
                        Text("Music for your\nnext moment", color = Color.White, fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(CircleShape).background(accent)
                                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text("Listen now", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Licensed music", color = Color.White.copy(alpha = .72f), fontSize = 10.sp)
                        }
                    }
                    if (featured != null) {
                        Box(Modifier.width(138.dp).fillMaxHeight().padding(9.dp),
                            contentAlignment = Alignment.Center) {
                            MusicCover(featured, 126.dp, radius = 7.dp)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    QuickTile("Liked Songs", null, Color(0xFF4A348A), Modifier.weight(1f), onGoLibrary)
                    QuickTile("Downloads", null, Color(0xFF20564A), Modifier.weight(1f), onGoLibrary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    QuickTile("Local files", null, Color(0xFF5C4B41), Modifier.weight(1f), onImport)
                    QuickTile("Your Library", null, Color(0xFF35445A), Modifier.weight(1f), onGoLibrary)
                }
            }
        }
        if (listed.isEmpty()) {
            item {
                Text("No tracks saved yet. Download a licensed recording or add music from your phone.",
                    color = muted, fontSize = 13.sp)
            }
        }
        if (discovery.isNotEmpty() && homeFilter != "Downloaded") {
            item {
                SectionHeading("Fresh discoveries", "Independent music · full-length tracks")
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(discovery.distinctBy { it.id }, key = { it.id }) { song ->
                        PremiumMusicCard(song, onPlay = { onPlay(song) })
                    }
                }
            }
        }
        if (electronic.isNotEmpty()) {
            item {
                SectionHeading("Electronic energy", "Find your next favorite")
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(electronic, key = { it.id }) { song ->
                        PremiumMusicCard(song, onPlay = { onPlay(song) })
                    }
                }
            }
        }
        if (slow.isNotEmpty()) {
            item {
                SectionHeading("After hours", "Classical and cinematic atmospheres")
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(slow, key = { it.id }) { song ->
                        PremiumMusicCard(song, onPlay = { onPlay(song) })
                    }
                }
            }
        }
        if (device.isNotEmpty()) {
            item { SectionHeading("Your own music", "Stored on your phone") }
            items(device, key = { it.id }) { song ->
                SongRow(song, favorite = song.id in favorites, offline = true,
                    progress = null, onPlay = { onPlay(song) },
                    onFavorite = { onFavorite(song) }, onDownload = { onDownload(song) })
            }
        }
        if (homeFilter == "Downloaded") {
            items(listed.filter { !it.isDeviceFile }, key = { it.id }) { song ->
                SongRow(song, favorite = song.id in favorites,
                    offline = true, progress = progress[song.id],
                    onPlay = { onPlay(song) }, onFavorite = { onFavorite(song) },
                    onDownload = { onDownload(song) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .background(surface).clickable(onClick = onImport)
                .padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, "Import audio", tint = accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add music from your device", fontSize = 13.sp,
                        color = ink, fontWeight = FontWeight.SemiBold)
                    Text("Private, offline and always available", fontSize = 11.sp, color = muted)
                }
                Icon(Icons.Default.ChevronRight, null, tint = muted)
            }
        }
    }
}

@Composable
private fun PremiumMusicCard(song: Song, onPlay: () -> Unit) {
    Column(Modifier.width(152.dp).clickable(onClick = onPlay)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            MusicCover(song, 152.dp, radius = 6.dp)
            Box(Modifier.padding(8.dp).size(36.dp).background(accent, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(song.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, fontSize = 11.sp, color = muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickTile(
    title: String, song: Song?, background: Color, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Row(modifier.height(58.dp).clip(RoundedCornerShape(5.dp))
        .background(background).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        if (song != null) MusicCover(song, 58.dp, radius = 0.dp)
        else Box(Modifier.size(58.dp)
            .background(Brush.linearGradient(listOf(background, Color(0xFF111111).copy(alpha = .35f)))),
            contentAlignment = Alignment.Center) {
            Icon(
                if (title == "Liked Songs") Icons.Default.Favorite
                else if (title == "Your downloads" || title == "Downloads") Icons.Default.DownloadDone
                else if (title == "Local files") Icons.Default.Folder
                else Icons.Default.LibraryMusic,
                contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(title, Modifier.padding(start = 9.dp, end = 5.dp).weight(1f), color = ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, lineHeight = 15.sp)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = muted)
    }
}

@Composable
private fun SongRow(
    song: Song, favorite: Boolean, offline: Boolean, progress: Float?,
    onPlay: () -> Unit, onFavorite: (() -> Unit)? = null, onDownload: () -> Unit,
    onArtist: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable(onClick = onPlay)) { MusicCover(song, 55.dp, radius = 3.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onPlay), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(song.title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (offline) {
                    Icon(Icons.Default.DownloadDone, null, tint = accent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                }
                Text(if (song.isDeviceFile) song.artist else "${song.artist} · ${song.genre}" +
                    if (song.durationSeconds > 0) " · ${song.durationSeconds / 60}:${(song.durationSeconds % 60).toString().padStart(2, '0')}" else "",
                    modifier = Modifier.clickable(enabled = onArtist != null) { onArtist?.invoke() },
                    color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = accent) }
        }
        if (onFavorite != null) {
            IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite", tint = if (favorite) accent else muted, modifier = Modifier.size(19.dp))
            }
        }
        if (!song.isDeviceFile) {
            IconButton(onClick = onDownload, enabled = !offline && progress == null, modifier = Modifier.size(39.dp)) {
                Icon(if (offline) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = if (offline) "Downloaded" else "Download", tint = if (offline) accent else muted,
                    modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun SearchScreen(
    songs: List<Song>, offline: Map<String, Boolean>, progress: Map<String, Float>,
    onPlay: (Song) -> Unit, onDownload: (Song) -> Unit,
    onOnlineResults: (List<Song>) -> Unit, modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val onlineSearch = remember { CommonsMusicSearch() }
    var loadingOnline by remember { mutableStateOf(false) }
    var liveMessage by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        val text = query.trim()
        liveMessage = ""
        loadingOnline = false
        if (text.length < 3) return@LaunchedEffect
        delay(600) // Debounce requests while the user is typing.
        loadingOnline = true
        try {
            val found = onlineSearch.search(text)
            if (isActive) {
                onOnlineResults(found)
                liveMessage = if (found.isEmpty()) {
                    "No confirmed CC0/public-domain Ogg matches for this search."
                } else {
                    "Found ${found.size} licensed recordings from Wikimedia Commons"
                }
            }
        } catch (e: Exception) {
            if (isActive) liveMessage = e.message ?: "Online search is unavailable."
        } finally {
            if (isActive) loadingOnline = false
        }
    }
    var category by remember { mutableStateOf("All") }
    var artistProfile by remember { mutableStateOf<String?>(null) }
    val activeArtist = artistProfile
    if (activeArtist != null) {
        ArtistCatalogScreen(
            artist = activeArtist, songs = songs, offline = offline, progress = progress,
            onPlay = onPlay, onDownload = onDownload,
            onBack = { artistProfile = null }, modifier = modifier
        )
        return
    }
    val results = remember(songs, query, category, offline.toMap()) {
        songs.filter { song ->
            val matchesQuery = query.isBlank() || song.title.contains(query.trim(), true) ||
                song.artist.contains(query.trim(), true) || song.genre.contains(query.trim(), true)
            val matchesCategory = when(category) {
                "Electronic", "Classical", "Indie", "Ambient" -> song.genre == category
                "Downloaded" -> song.isDeviceFile || offline[song.id] == true
                "Local files" -> song.isDeviceFile
                else -> true
            }
            matchesQuery && matchesCategory
        }
    }
    LazyColumn(
        modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xFF191D25), base, base), endY = 680f
        )),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 19.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("Search", fontSize = 31.sp, color = ink, fontWeight = FontWeight.ExtraBold) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().height(55.dp),
                placeholder = {
                    Text("What do you want to listen to?", color = Color(0xFF696969), fontSize = 14.sp)
                }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, "Search", tint = Color(0xFF202020)) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, "Clear search", tint = Color(0xFF333333))
                    }
                },
                shape = RoundedCornerShape(7.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFFF8F8F8),
                    unfocusedContainerColor = Color(0xFFF8F8F8),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = Color.Black
                )
            )
        }
        if (query.isBlank() && category == "All") {
            item { SectionHeading("Browse all", "Pick the sound you're in the mood for") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryTile("Electronic", Color(0xFFBC4265), Modifier.weight(1f)) {
                            category = "Electronic"
                        }
                        CategoryTile("Indie", Color(0xFF276F6B), Modifier.weight(1f)) {
                            category = "Indie"
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryTile("Classical", Color(0xFF5943A1), Modifier.weight(1f)) {
                            category = "Classical"
                        }
                        CategoryTile("Ambient", Color(0xFFB1602B), Modifier.weight(1f)) {
                            category = "Ambient"
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryTile("Downloaded", Color(0xFF355B86), Modifier.weight(1f)) {
                            category = "Downloaded"
                        }
                        CategoryTile("Local files", Color(0xFF546C44), Modifier.weight(1f)) {
                            category = "Local files"
                        }
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (category == "All") "Search results" else category,
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { category = "All"; query = "" }) {
                        Text("Browse all", color = accent, fontSize = 12.sp)
                    }
                }
            }
        }
        if (query.trim().length >= 3) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (loadingOnline) CircularProgressIndicator(
                        modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = accent)
                    else Icon(Icons.Default.Public, null, tint = accent, modifier = Modifier.size(17.dp))
                    Text(if (loadingOnline) "Finding real, reusable recordings…" else
                        liveMessage.ifBlank { "Online music search · Commons CC0/public domain" },
                        color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank() && category == "All") "Popular in Pulse" else "Tracks",
                    fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink,
                    modifier = Modifier.weight(1f))
                Text("${results.size} available", fontSize = 12.sp, color = muted)
            }
        }
        if (results.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.SearchOff, null, tint = muted, modifier = Modifier.size(38.dp))
                    Text("No songs found", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
                    Text("Try a different title or artist. Search covers your library and CC0/public-domain recordings online.",
                        fontSize = 12.sp, color = muted, textAlign = TextAlign.Center)
                }
            }
        }
        items(results, key = { it.id }) { song ->
            SongRow(song, favorite = false, offline = offline[song.id] == true || song.isDeviceFile,
                progress = progress[song.id],
                onPlay = { onPlay(song) }, onDownload = { onDownload(song) },
                onArtist = { artistProfile = song.artist })
        }
    }
}

@Composable
private fun ArtistCatalogScreen(
    artist: String, songs: List<Song>, offline: Map<String, Boolean>, progress: Map<String, Float>,
    onPlay: (Song) -> Unit, onDownload: (Song) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier
) {
    val catalog = remember(artist, songs) { songs.filter { it.artist == artist } }
    LazyColumn(modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFF29473F), base, base), endY = 850f)),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back to search", tint = ink)
            }
        }
        item {
            Box(Modifier.size(176.dp).clip(CircleShape).background(
                Brush.linearGradient(listOf(Color(0xFF3B8974), Color(0xFF36436A)))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(78.dp))
            }
        }
        item {
            Text(artist, fontSize = 30.sp, fontWeight = FontWeight.Black, color = ink,
                maxLines = 3, lineHeight = 34.sp)
            Spacer(Modifier.height(6.dp))
            Text("Artist / contributor · ${catalog.size} available recording(s)",
                color = muted, fontSize = 13.sp)
        }
        if (catalog.isNotEmpty()) {
            item {
                Button(onClick = { onPlay(catalog.first()) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent,
                        contentColor = Color.Black)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Play", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("Available tracks", fontSize = 22.sp, color = ink, fontWeight = FontWeight.Bold) }
        items(catalog, key = { it.id }) { song ->
            SongRow(song, favorite = false, offline = song.isDeviceFile || offline[song.id] == true,
                progress = progress[song.id], onPlay = { onPlay(song) },
                onDownload = { onDownload(song) })
        }
        item {
            Text("Song rights and credits belong to the original creators. Open each song's source page from Now Playing.",
                color = muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CategoryTile(title: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(105.dp).clip(RoundedCornerShape(8.dp))
        .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = .78f))))
        .clickable(onClick = onClick)) {
        Text(title, modifier = Modifier.align(Alignment.TopStart).padding(13.dp),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 2, lineHeight = 21.sp)
        Box(Modifier.size(78.dp).align(Alignment.BottomEnd)
            .offset(x = 17.dp, y = 18.dp).rotate(19f)
            .background(Color.Black.copy(alpha = .24f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) {
            val graphic = when(title) {
                "Downloaded" -> Icons.Default.DownloadDone
                "Local files" -> Icons.Default.Folder
                "Electronic" -> Icons.Default.GraphicEq
                "Classical" -> Icons.Default.Piano
                "Ambient" -> Icons.Default.NightsStay
                else -> Icons.Default.Album
            }
            Icon(graphic, null, tint = Color.White.copy(alpha = .88f),
                modifier = Modifier.size(35.dp))
        }
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>, favorites: Set<String>, downloaded: Map<String, Boolean>, progress: Map<String, Float>,
    onPlay: (Song, List<Song>) -> Unit, onFavorite: (Song) -> Unit,
    onDownload: (Song) -> Unit, onImport: () -> Unit,
    importedPlaylists: List<ImportedPlaylist>, onImportSpotify: () -> Unit,
    localPlaylists: List<LocalPlaylist>, onCreatePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLocal by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    if (showCreatePlaylist) {
        AlertDialog(onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it.take(65) },
                    label = { Text("Playlist name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = newPlaylistName.isNotBlank(),
                    onClick = {
                        onCreatePlaylist(newPlaylistName)
                        newPlaylistName = ""
                        showCreatePlaylist = false
                    }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") }
            }
        )
    }
    val activeLocal = localPlaylists.firstOrNull { it.id == selectedLocal }
    if (activeLocal != null) {
        LocalPlaylistScreen(activeLocal, songs, onPlay, onDownload, onRemoveFromPlaylist,
            onBack = { selectedLocal = null }, modifier = modifier)
        return
    }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    val displayedPlaylist = importedPlaylists.firstOrNull { it.name == selectedPlaylist }
    if (displayedPlaylist != null) {
        ImportedPlaylistScreen(displayedPlaylist, songs, onPlay, onBack = { selectedPlaylist = null }, modifier)
        return
    }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var searchOpen by remember { mutableStateOf(false) }
    var libraryQuery by remember { mutableStateOf("") }
    val visible = songs.filter {
        (when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.DOWNLOADED -> it.isDeviceFile || downloaded[it.id] == true
            LibraryFilter.FAVORITES -> it.id in favorites
        }) && (libraryQuery.isBlank() || it.title.contains(libraryQuery, true) || it.artist.contains(libraryQuery, true))
    }
    LazyColumn(modifier.fillMaxSize().background(base), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF8254A6)),
                    contentAlignment = Alignment.Center) {
                    Text("P", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text("Your Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) libraryQuery = "" }) {
                    Icon(Icons.Default.Search, "Search library", tint = ink)
                }
                IconButton(onClick = onImport) { Icon(Icons.Default.Add, "Import music", tint = ink) }
            }
        }
        if (searchOpen) item {
            OutlinedTextField(value = libraryQuery, onValueChange = { libraryQuery = it },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search your library") },
                singleLine = true, shape = RoundedCornerShape(7.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(LibraryFilter.ALL to "All", LibraryFilter.DOWNLOADED to "Downloaded",
                    LibraryFilter.FAVORITES to "Playlists & Liked")) { (value, label) ->
                    val selected = filter == value
                    Box(Modifier.clip(CircleShape)
                        .background(if (selected) Color(0xFF225D35) else surfaceAlt)
                        .clickable { filter = value }
                        .padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(label, fontSize = 13.sp, color = if (selected) accent else ink)
                    }
                }
            }
        }
        if (filter == LibraryFilter.ALL && libraryQuery.isBlank()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recents", fontSize = 14.sp, color = muted, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.GridView, "Library grid view", tint = muted, modifier = Modifier.size(20.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { filter = LibraryFilter.FAVORITES },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).background(
                        Brush.linearGradient(listOf(Color(0xFF4F3AAA), Color(0xFF9CA8E4)))),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Liked Songs", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
                        Text("Pinned · ${favorites.size} liked tracks", fontSize = 12.sp, color = muted)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { filter = LibraryFilter.DOWNLOADED },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).background(Color(0xFF28554C)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.DownloadDone, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Your downloads", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
                        Text("Offline music", fontSize = 12.sp, color = muted)
                    }
                }
            }
        }
        if (filter != LibraryFilter.DOWNLOADED) {
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                    .background(surface).clickable { showCreatePlaylist = true }
                    .padding(horizontal = 13.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AddCircle, "Create playlist", tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Text("Create your own playlist", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null, tint = muted)
                }
            }
            if (localPlaylists.isNotEmpty()) {
                item {
                    Text("Your playlists", fontSize = 20.sp, color = ink, fontWeight = FontWeight.Bold)
                }
                items(localPlaylists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clickable { selectedLocal = playlist.id }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(58.dp).background(Color(0xFF446E61)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LibraryMusic, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${playlist.trackIds.size} tracks · private playlist",
                                fontSize = 12.sp, color = muted)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = muted)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(surface).clickable(onClick = onImportSpotify)
                    .padding(horizontal = 13.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlaylistAdd, null, tint = accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Import Spotify playlist file", color = ink, fontWeight = FontWeight.SemiBold)
                        Text("Private JSON import · titles & artists only", color = muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = muted)
                }
            }
            if (importedPlaylists.isNotEmpty()) {
                item { Text("Imported playlists", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                items(importedPlaylists, key = { it.name }) { playlist ->
                    Row(Modifier.fillMaxWidth().clickable { selectedPlaylist = playlist.name }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(58.dp).background(
                            Brush.linearGradient(listOf(Color(0xFF316A51), Color(0xFF163F32)))),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = ink, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("Imported · ${playlist.tracks.size} entries · metadata only",
                                color = muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = muted)
                    }
                }
            }
        }
        item {
            Text(when(filter) {
                LibraryFilter.ALL -> "All tracks"
                LibraryFilter.DOWNLOADED -> "Downloaded tracks"
                LibraryFilter.FAVORITES -> "Liked tracks"
            }, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        if (visible.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No tracks here yet", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Add songs, like a track or download an available recording.", color = muted, fontSize = 13.sp)
            }
        }
        items(visible, key = { it.id }) { song ->
            SongRow(song, favorite = song.id in favorites, offline = downloaded[song.id] == true || song.isDeviceFile,
                progress = progress[song.id], onPlay = { onPlay(song, visible) },
                onFavorite = { onFavorite(song) }, onDownload = { onDownload(song) })
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onImport).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddCircleOutline, "Add local files", tint = ink)
                Spacer(Modifier.width(12.dp))
                Text("Import audio files from device", color = ink, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LocalPlaylistScreen(
    playlist: LocalPlaylist, songs: List<Song>,
    onPlay: (Song, List<Song>) -> Unit, onDownload: (Song) -> Unit,
    onRemove: (String, String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier
) {
    val queue = remember(playlist, songs) {
        playlist.trackIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }
    }
    LazyColumn(modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFF315A4A), base), endY = 700f)),
        contentPadding = PaddingValues(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ink) }
        }
        item {
            Box(Modifier.size(175.dp).background(
                Brush.linearGradient(listOf(Color(0xFF3A695A), Color(0xFF314B74)))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LibraryMusic, null, tint = Color.White, modifier = Modifier.size(75.dp))
            }
        }
        item {
            Text(playlist.name, fontSize = 29.sp, fontWeight = FontWeight.Black)
            Text("${queue.size} available tracks · saved on this phone",
                color = muted, fontSize = 12.sp)
        }
        if (queue.isNotEmpty()) {
            item {
                Button(onClick = { onPlay(queue.first(), queue) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Play playlist")
                }
            }
        } else {
            item {
                Text("No tracks yet. Open Now Playing on a song and select Add to playlist.",
                    color = muted, fontSize = 13.sp)
            }
        }
        items(queue, key = { it.id }) { song ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SongRow(song, favorite = false, offline = song.isDeviceFile,
                        progress = null, onPlay = { onPlay(song, queue) }, onDownload = { onDownload(song) })
                }
                IconButton(onClick = { onRemove(playlist.id, song.id) }) {
                    Icon(Icons.Default.RemoveCircleOutline, "Remove from playlist", tint = muted)
                }
            }
        }
    }
}

@Composable
private fun ImportedPlaylistScreen(
    playlist: ImportedPlaylist, songs: List<Song>,
    onPlay: (Song, List<Song>) -> Unit, onBack: () -> Unit, modifier: Modifier
) {
    val matches = remember(playlist, songs) {
        playlist.tracks.map { track -> track to findPlayableMatch(track, songs) }
    }
    val playable = remember(matches) { matches.mapNotNull { it.second }.distinctBy { it.id } }
    LazyColumn(modifier.fillMaxSize().background(base),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to library", tint = ink) }
        }
        item {
            Box(Modifier.size(188.dp).background(
                Brush.linearGradient(listOf(Color(0xFF336B51), Color(0xFF23364A)))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(76.dp))
            }
        }
        item {
            Text(playlist.name, fontSize = 30.sp, fontWeight = FontWeight.Black, color = ink)
            Text("${playlist.tracks.size} tracks imported · ${playable.size} playable matches",
                fontSize = 13.sp, color = muted)
            Spacer(Modifier.height(7.dp))
            Text("Imported from a Spotify account-data file. Original audio isn't included. Unavailable tracks are shown for reference only.",
                fontSize = 12.sp, color = muted)
        }
        if (playable.isNotEmpty()) item {
            Button(onClick = { onPlay(playable.first(), playable) },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                shape = CircleShape) {
                Icon(Icons.Default.PlayArrow, null)
                Text("Play available tracks", fontWeight = FontWeight.Bold)
            }
        }
        items(matches) { (track, match) ->
            Row(Modifier.fillMaxWidth().then(if (match != null) Modifier.clickable { onPlay(match, playable) } else Modifier)
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (match != null) MusicCover(match, 51.dp)
                else Box(Modifier.size(51.dp).background(surfaceAlt), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = muted)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = ink, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (match == null) "Not in current licensed catalog" else "Playable",
                        color = if (match == null) muted else accent, fontSize = 10.sp)
                }
                if (match != null) Icon(Icons.Default.PlayArrow, "Play", tint = accent)
            }
        }
    }
}

@Composable
private fun MiniPlayer(song: Song, engine: PlayerEngine, onExpand: () -> Unit) {
    val tint = colors[song.gradient % colors.size][0]
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 2.dp)
        .clip(RoundedCornerShape(9.dp)).background(
            Brush.horizontalGradient(listOf(tint.copy(alpha = .78f), Color(0xFF34383A))))) {
        Row(Modifier.fillMaxWidth().height(57.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 5.dp).clickable(onClick = onExpand)) {
                MusicCover(song, 48.dp, radius = 4.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable(onClick = onExpand)) {
                Text(song.title, color = ink, maxLines = 1, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Color.White.copy(alpha = .78f),
                    maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = engine::toggle, modifier = Modifier.size(43.dp)) {
                Icon(if (engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (engine.isPlaying) "Pause" else "Play", tint = ink, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = engine::next, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.SkipNext, "Next song", tint = ink, modifier = Modifier.size(27.dp))
            }
        }
        val fraction = if (engine.duration > 0) (engine.position.toFloat() / engine.duration).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(2.dp),
            color = Color.White.copy(alpha = .92f), trackColor = Color.White.copy(alpha = .25f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayer(
    song: Song, engine: PlayerEngine, favorite: Boolean, offline: Boolean, progress: Float?,
    onBack: () -> Unit, onFavorite: () -> Unit, onDownload: () -> Unit, onAttribution: () -> Unit,
    userPlaylists: List<LocalPlaylist>, onAddToPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var seekFraction by remember(song.id) { mutableFloatStateOf(-1f) }
    var showQueue by remember { mutableStateOf(false) }

    val actual = if (engine.duration > 0L) (engine.position.toFloat() / engine.duration).coerceIn(0f, 1f) else 0f
    val palette = colors[song.gradient % colors.size]
    Column(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(palette[0].copy(alpha = .83f), Color(0xFF242526), base),
                startY = 0f, endY = 1500f))
            .verticalScroll(rememberScrollState())
            .padding(start = 23.dp, end = 23.dp, top = 9.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowDown, "Close player", tint = ink, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", fontSize = 10.sp, letterSpacing = 1.6.sp,
                    color = Color.White.copy(alpha = .8f), fontWeight = FontWeight.Medium)
                Text(if (song.isDeviceFile) "Your local music" else "Pulse · Licensed recordings",
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ink)
            }
            IconButton(onClick = onAttribution, enabled = song.sourcePage.isNotBlank()) {
                Icon(Icons.Default.MoreVert, "Music information", tint = ink)
            }
        }
        Spacer(Modifier.height(42.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artSize = maxWidth.coerceAtMost(344.dp)
            MusicCover(song, artSize, Modifier.shadow(18.dp, RoundedCornerShape(6.dp)), radius = 6.dp)
        }
        Spacer(Modifier.height(43.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(song.title, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                Text(song.artist, fontSize = 15.sp, color = Color.White.copy(alpha = .74f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Like this song", tint = if (favorite) accent else ink, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Slider(value = if (seekFraction >= 0f) seekFraction else actual,
            onValueChange = { seekFraction = it },
            onValueChangeFinished = { engine.seek(seekFraction); seekFraction = -1f },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = .34f)), modifier = Modifier.fillMaxWidth().height(27.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(displayTime(engine.position), color = Color.White.copy(alpha = .70f), fontSize = 11.sp)
            Text(displayTime(engine.duration), color = Color.White.copy(alpha = .70f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = engine::toggleShuffle) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (engine.shuffle) accent else ink.copy(alpha = .76f),
                    modifier = Modifier.size(23.dp))
            }
            IconButton(onClick = engine::previous) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = ink, modifier = Modifier.size(36.dp))
            }
            Box(Modifier.size(69.dp).clip(CircleShape).background(Color.White)
                .clickable { engine.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (engine.isPlaying) "Pause" else "Play", tint = Color.Black,
                    modifier = Modifier.size(39.dp))
            }
            IconButton(onClick = engine::next) {
                Icon(Icons.Default.SkipNext, "Next", tint = ink, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = engine::toggleRepeat) {
                Icon(Icons.Default.Repeat, "Repeat", tint = if (engine.repeat) accent else ink.copy(alpha = .76f),
                    modifier = Modifier.size(23.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showPlaylistPicker = true }) {
                Icon(Icons.Default.PlaylistAdd, "Add to playlist", tint = ink, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add to playlist", fontSize = 13.sp, color = ink)
            }
            TextButton(onClick = { showQueue = true }) {
                Icon(Icons.Default.QueueMusic, "Open play queue", tint = ink, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(7.dp))
                Text("Up Next", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Headphones, null, tint = Color.White.copy(alpha = .70f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (offline || song.isDeviceFile) "Available offline" else "Streaming online",
                color = Color.White.copy(alpha = .78f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (!song.isDeviceFile) {
                TextButton(onClick = onDownload, enabled = !offline && progress == null) {
                    Icon(if (offline) Icons.Default.DownloadDone else Icons.Default.Download,
                        "Download song", tint = if (offline) accent else ink, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (offline) "Saved" else if (progress != null) "Saving" else "Download",
                        color = if (offline) accent else ink, fontSize = 13.sp)
                }
            }
        }
        progress?.let {
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = accent)
        }
        if (song.license.isNotBlank()) {
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = .24f)).clickable(onClick = onAttribution)
                .padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About this recording", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink)
                Text(song.license, fontSize = 12.sp, color = Color.White.copy(alpha = .75f))
                Text("View source and license", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink)
            }
        }
    }
    if (showPlaylistPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistPicker = false },
            containerColor = surface, contentColor = ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Add to playlist", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(11.dp))
                if (userPlaylists.isEmpty()) {
                    Text("Create a playlist in Your Library first.", color = muted, fontSize = 13.sp)
                }
                userPlaylists.forEach { playlist ->
                    Row(Modifier.fillMaxWidth().clickable {
                        onAddToPlaylist(playlist.id)
                        showPlaylistPicker = false
                    }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LibraryMusic, null, tint = accent)
                        Spacer(Modifier.width(13.dp))
                        Column {
                            Text(playlist.name, fontWeight = FontWeight.SemiBold)
                            Text("${playlist.trackIds.size} tracks", fontSize = 12.sp, color = muted)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            containerColor = surface,
            contentColor = ink,
            dragHandle = { BottomSheetDefaults.DragHandle(color = muted) }
        ) {
            val queue = remember(showQueue, song.id) { engine.currentQueue() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Play queue", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Spacer(Modifier.height(6.dp))
                Text("${queue.size} available tracks · tap to jump",
                    color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(queue, key = { it.id }) { nextSong ->
                        Row(Modifier.fillMaxWidth().clickable {
                            engine.jumpTo(nextSong)
                            showQueue = false
                        }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MusicCover(nextSong, 47.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nextSong.title, color = if (nextSong.id == song.id) accent else ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(nextSong.artist, fontSize = 12.sp, color = muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (nextSong.id == song.id) {
                                Icon(Icons.Default.GraphicEq, "Now playing", tint = accent)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

private fun displayTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
