package com.example.pulsemusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.geometry.Offset
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
    val engine = remember { PlayerEngine(context, repository) }
    var deviceSongs by remember { mutableStateOf(repository.importedFiles()) }
    var favorites by remember { mutableStateOf(repository.favorites()) }
    val downloaded = remember {
        mutableStateMapOf<String, Boolean>().apply {
            publicDomainDemos.forEach { put(it.id, repository.offlinePath(it) != null) }
        }
    }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val songs = remember(deviceSongs) { publicDomainDemos + deviceSongs }
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
    DisposableEffect(engine) {
        engine.connect()
        onDispose { engine.release() }
    }
    LaunchedEffect(engine) {
        while (isActive) {
            engine.updatePosition()
            delay(500)
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
                    modifier = Modifier.padding(padding)
                )
                Page.LIBRARY -> LibraryScreen(
                    songs = songs,
                    favorites = favorites,
                    downloaded = downloaded,
                    progress = progress,
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

@Composable
private fun MusicCover(song: Song, size: Dp, modifier: Modifier = Modifier, radius: Dp = 5.dp) {
    val palette = colors[song.gradient % colors.size]
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(palette[0], palette[1], palette[2]))),
        contentAlignment = Alignment.Center
    ) {
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
                Text(song.title.uppercase(),
                    fontSize = (size.value * .16f).coerceIn(11f, 29f).sp,
                    fontWeight = FontWeight.Black, lineHeight = (size.value * .16f).coerceIn(11f, 30f).sp,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist.substringBefore("·").trim(),
                    fontSize = (size.value * .07f).coerceIn(7f, 11f).sp,
                    color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val listing = if (homeFilter == "Downloaded") songs.filter { it.isDeviceFile || offline[it.id] == true } else songs
    LazyColumn(
        modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF242D24), base, base), endY = 850f)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(39.dp).clip(CircleShape).background(Color(0xFF8E55BD)),
                    contentAlignment = Alignment.Center) {
                    Text("P", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                listOf("All", "Music", "Downloaded").forEach { value ->
                    val chosen = homeFilter == value
                    Box(Modifier.clip(CircleShape)
                        .background(if (chosen) accent else Color(0xFF303730))
                        .clickable { homeFilter = value }
                        .padding(horizontal = 17.dp, vertical = 10.dp)) {
                        Text(value, color = if (chosen) Color.Black else ink, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            val first = songs.firstOrNull()
            val second = songs.getOrNull(1) ?: first
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Liked Songs", null, Color(0xFF44318D), Modifier.weight(1f), onGoLibrary)
                    if (first != null) QuickTile(first.title, first, surface, Modifier.weight(1f)) { onPlay(first) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Your downloads", null, Color(0xFF235E54), Modifier.weight(1f), onGoLibrary)
                    if (second != null) QuickTile(second.title, second, surface, Modifier.weight(1f)) { onPlay(second) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Local files", null, Color(0xFF695147), Modifier.weight(1f), onImport)
                    QuickTile("Your Library", null, Color(0xFF434B61), Modifier.weight(1f), onGoLibrary)
                }
            }
        }
        if (listing.isEmpty()) item {
            Text("No downloads yet. Choose a track and tap Download to save it offline.", color = muted)
        }
        item {
            SectionHeading("Jump back in", "")
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(listing.take(12), key = { it.id }) { song ->
                    Column(Modifier.width(148.dp).clickable { onPlay(song) }) {
                        MusicCover(song, 148.dp)
                        Spacer(Modifier.height(9.dp))
                        Text(song.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            SectionHeading("Fresh music for you", "")
            Spacer(Modifier.height(15.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(publicDomainDemos, key = { it.id }) { song ->
                    Column(Modifier.width(164.dp).clickable { onPlay(song) }) {
                        MusicCover(song, 164.dp)
                        Spacer(Modifier.height(9.dp))
                        Text(song.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Free to stream · credited recording", color = muted, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
        }
        item {
            SectionHeading("Your tracks", "")
            Spacer(Modifier.height(10.dp))
        }
        items(listing, key = { it.id }) { song ->
            SongRow(song, favorite = song.id in favorites, offline = offline[song.id] == true || song.isDeviceFile,
                progress = progress[song.id], onPlay = { onPlay(song) },
                onFavorite = { onFavorite(song) }, onDownload = { onDownload(song) })
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(surface)
                .clickable(onClick = onImport).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, "Import from phone", tint = accent)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Add songs from your phone", color = ink, fontWeight = FontWeight.SemiBold)
                    Text("MP3, FLAC, OGG and other supported files", color = muted, fontSize = 12.sp)
                }
            }
        }
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
                else if (title == "Your downloads") Icons.Default.DownloadDone
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
    onPlay: () -> Unit, onFavorite: (() -> Unit)? = null, onDownload: () -> Unit
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
                Text(song.artist, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onPlay: (Song) -> Unit, onDownload: (Song) -> Unit, modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val results = songs.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
    LazyColumn(modifier.fillMaxSize().background(base), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item { Text("Search", fontSize = 34.sp, color = ink, fontWeight = FontWeight.Black) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Songs and artists", color = muted) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = accent) },
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = surface, unfocusedContainerColor = surface,
                    focusedTextColor = ink, unfocusedTextColor = ink,
                    focusedBorderColor = accent, unfocusedBorderColor = Color.Transparent
                )
            )
        }
        item {
            Text("Starter catalog + imported device tracks", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(10.dp))
            Text(if (query.isBlank()) "Explore music" else "${results.size} result(s)", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        if (results.isEmpty()) item {
            Text("No match yet. The initial online catalog contains two public-domain recordings; more licensed sources come later.", color = muted)
        }
        items(results, key = { it.id }) { song ->
            SongRow(song, favorite = false, offline = offline[song.id] == true || song.isDeviceFile,
                progress = progress[song.id], onPlay = { onPlay(song) }, onDownload = { onDownload(song) })
        }
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>, favorites: Set<String>, downloaded: Map<String, Boolean>, progress: Map<String, Float>,
    onPlay: (Song, List<Song>) -> Unit, onFavorite: (Song) -> Unit,
    onDownload: (Song) -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val visible = songs.filter {
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.DOWNLOADED -> it.isDeviceFile || downloaded[it.id] == true
            LibraryFilter.FAVORITES -> it.id in favorites
        }
    }
    LazyColumn(modifier.fillMaxSize().background(base), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your Library", fontSize = 31.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onImport) { Icon(Icons.Default.AddCircle, "Import local audio", tint = accent) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    LibraryFilter.ALL to "All", LibraryFilter.DOWNLOADED to "Downloaded", LibraryFilter.FAVORITES to "Liked"
                ).forEach { (item, label) ->
                    FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = base))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surface).clickable(onClick = onImport).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, null, tint = accent)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Import music from device", fontWeight = FontWeight.Bold)
                    Text("Your audio files stay on your device", fontSize = 12.sp, color = muted)
                }
            }
        }
        item { Text("${visible.size} tracks", fontSize = 13.sp, color = muted) }
        if (visible.isEmpty()) item {
            Text("Nothing here yet. Add some music, download a demo song or mark favorites.", color = muted)
        }
        items(visible, key = { it.id }) { song ->
            SongRow(song, favorite = song.id in favorites, offline = downloaded[song.id] == true || song.isDeviceFile,
                progress = progress[song.id], onPlay = { onPlay(song, visible) },
                onFavorite = { onFavorite(song) }, onDownload = { onDownload(song) })
        }
    }
}

@Composable
private fun MiniPlayer(song: Song, engine: PlayerEngine, onExpand: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF303749))) {
        Row(Modifier.fillMaxWidth().height(63.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 6.dp).clickable(onClick = onExpand)) { MusicCover(song, 50.dp, radius = 8.dp) }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f).clickable(onClick = onExpand)) {
                Text(song.title, color = ink, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(song.artist, color = Color(0xFFD1D6E2), maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = engine::toggle) {
                Icon(if (engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (engine.isPlaying) "Pause" else "Play", tint = ink, modifier = Modifier.size(31.dp))
            }
            IconButton(onClick = engine::next) { Icon(Icons.Default.SkipNext, "Next", tint = ink) }
        }
        val fraction = if (engine.duration > 0) (engine.position.toFloat() / engine.duration).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp), color = accent,
            trackColor = Color.White.copy(alpha = .18f))
    }
}

@Composable
private fun FullPlayer(
    song: Song, engine: PlayerEngine, favorite: Boolean, offline: Boolean, progress: Float?,
    onBack: () -> Unit, onFavorite: () -> Unit, onDownload: () -> Unit, onAttribution: () -> Unit,
    modifier: Modifier = Modifier
) {
    var seekFraction by remember(song.id) { mutableFloatStateOf(-1f) }
    val actual = if (engine.duration > 0L) (engine.position.toFloat() / engine.duration).coerceIn(0f, 1f) else 0f
    Column(
        modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF252838), base), startY = 0f, endY = 1400f))
            .verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Back", tint = ink, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text(if (offline || song.isDeviceFile) "Offline ready" else "Streaming", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.GraphicEq, null, tint = accent, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.height(45.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artSize = maxWidth.coerceAtMost(340.dp)
            MusicCover(song, artSize, Modifier.shadow(25.dp, RoundedCornerShape(23.dp)), radius = 23.dp)
        }
        Spacer(Modifier.height(38.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(song.artist, fontSize = 14.sp, color = muted, maxLines = 2)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Favorite", tint = if (favorite) accent else ink, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Slider(value = if (seekFraction >= 0f) seekFraction else actual,
            onValueChange = { seekFraction = it },
            onValueChangeFinished = { engine.seek(seekFraction); seekFraction = -1f },
            valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = accent,
                inactiveTrackColor = surfaceAlt), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(displayTime(engine.position), color = muted, fontSize = 11.sp)
            Text(displayTime(engine.duration), color = muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = engine::previous) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = ink, modifier = Modifier.size(38.dp))
            }
            Box(Modifier.size(76.dp).clip(CircleShape).background(accent).clickable { engine.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (engine.isPlaying) "Pause" else "Play", tint = base, modifier = Modifier.size(41.dp))
            }
            IconButton(onClick = engine::next) {
                Icon(Icons.Default.SkipNext, "Next", tint = ink, modifier = Modifier.size(38.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        if (!song.isDeviceFile) {
            Button(onClick = onDownload, enabled = !offline && progress == null,
                modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (offline) surfaceAlt else accent,
                    contentColor = if (offline) ink else base, disabledContainerColor = surfaceAlt, disabledContentColor = muted)) {
                Icon(if (offline) Icons.Default.DownloadDone else Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (offline) "Saved for offline listening" else if (progress != null) "Downloading…" else "Download for offline", fontWeight = FontWeight.Bold)
            }
            progress?.let { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = accent) }
        }
        Spacer(Modifier.height(18.dp))
        if (song.license.isNotBlank()) {
            Surface(Modifier.fillMaxWidth().clickable(onClick = onAttribution), color = surface.copy(alpha = .65f), shape = RoundedCornerShape(13.dp)) {
                Column(Modifier.padding(15.dp)) {
                    Text("Music credits & licensing", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
                    Text(song.license, fontSize = 12.sp, color = muted)
                    Text("Tap to open the original source", fontSize = 11.sp, color = muted)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun displayTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
