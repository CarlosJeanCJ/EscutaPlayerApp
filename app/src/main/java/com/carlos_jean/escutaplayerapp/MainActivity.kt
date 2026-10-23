package com.carlos_jean.escutaplayerapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) {
                    PlayerApp(controllerState.value)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            { controllerState.value = future.get() },
            ContextCompat.getMainExecutor(this)
        )
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerState.value = null
        super.onStop()
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasAudioPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerApp(player: MediaController?) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasAudioPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = hasAudioPermission(context) }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(requiredPermissions())
    }

    if (hasPermission) {
        MusicScreen(player)
    } else {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Preciso de acesso às músicas do celular para listá-las.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(requiredPermissions()) }) { Text("Permitir") }
        }
    }
}

@Composable
fun MusicScreen(player: MediaController?) {
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlists by remember { mutableStateOf(store.all()) }
    var tab by remember { mutableIntStateOf(0) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        songs = withContext(Dispatchers.IO) { loadSongs(context) }
    }

    fun playList(list: List<Song>, index: Int) {
        val p = player ?: return
        p.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        p.prepare()
        p.play()
    }

    Scaffold(bottomBar = { NowPlayingBar(player) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Músicas") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
            }

            if (tab == 0) {
                val filtered = if (query.isBlank()) songs else songs.filter {
                    it.title.contains(query, ignoreCase = true) ||
                            it.artist.contains(query, ignoreCase = true)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar música ou artista") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
                Text(
                    "Segure uma música para adicionar a uma playlist",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SongList(
                    songs = filtered,
                    onClick = { i -> playList(filtered, i) },
                    onLongClick = { songToAdd = it }
                )
            } else {
                val open = openPlaylist
                if (open == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showNewPlaylist = true }) { Text("+ Nova playlist") }
                    }
                    if (playlists.isEmpty()) {
                        Text("Nenhuma playlist ainda.", Modifier.padding(16.dp))
                    }
                    LazyColumn {
                        items(playlists.keys.toList()) { name ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { openPlaylist = name }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${playlists[name].orEmpty().size} músicas",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = {
                                    store.delete(name)
                                    playlists = store.all()
                                }) { Text("Apagar") }
                            }
                        }
                    }
                } else {
                    val byId = songs.associateBy { it.id }
                    val list = playlists[open].orEmpty().mapNotNull { byId[it] }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { openPlaylist = null }) { Text("← Voltar") }
                        Text(
                            open,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = { if (list.isNotEmpty()) playList(list, 0) }) {
                            Text("▶ Tocar tudo")
                        }
                    }
                    Text(
                        "Segure uma música para removê-la da playlist",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    SongList(
                        songs = list,
                        onClick = { i -> playList(list, i) },
                        onLongClick = { s ->
                            store.removeSong(open, s.id)
                            playlists = store.all()
                        }
                    )
                }
            }
        }
    }

    if (showNewPlaylist) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPlaylist = false },
            title = { Text("Nova playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nome") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        store.create(name.trim())
                        playlists = store.all()
                    }
                    showNewPlaylist = false
                }) { Text("Criar") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylist = false }) { Text("Cancelar") }
            }
        )
    }

    songToAdd?.let { song ->
        AlertDialog(
            onDismissRequest = { songToAdd = null },
            title = { Text("Adicionar a qual playlist?") },
            text = {
                if (playlists.isEmpty()) {
                    Text("Crie uma playlist primeiro, na aba Playlists.")
                } else {
                    Column {
                        playlists.keys.forEach { n ->
                            TextButton(onClick = {
                                store.addSong(n, song.id)
                                playlists = store.all()
                                songToAdd = null
                            }) { Text(n) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { songToAdd = null }) { Text("Fechar") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(songs: List<Song>, onClick: (Int) -> Unit, onLongClick: (Song) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            Column(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { onClick(index) },
                        onLongClick = { onLongClick(song) }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun NowPlayingBar(player: MediaController?) {
    var title by remember { mutableStateOf("Nada tocando") }
    var artist by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun sync() {
            title = player.mediaMetadata.title?.toString() ?: "Nada tocando"
            artist = player.mediaMetadata.artist?.toString() ?: ""
            isPlaying = player.isPlaying
            shuffle = player.shuffleModeEnabled
            repeat = player.repeatMode
            duration = player.duration.coerceAtLeast(0L)
        }
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = sync()
        }
        player.addListener(listener)
        sync()
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, isPlaying) {
        if (player == null) return@LaunchedEffect
        position = player.currentPosition
        while (isPlaying) {
            delay(500)
            position = player.currentPosition
        }
    }

    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val repeatLabel = when (repeat) {
        Player.REPEAT_MODE_ALL -> "🔁 Todas"
        Player.REPEAT_MODE_ONE -> "🔂 Uma"
        else -> "🔁 Off"
    }

    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = progress,
                onValueChange = { position = (it * duration).toLong() },
                onValueChangeFinished = { player?.seekTo(position) }
            )
            Text(
                "${fmt(position)} / ${fmt(duration)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { player?.seekToPrevious() }) { Text("⏮") }
                Button(onClick = {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                }) { Text(if (isPlaying) "⏸" else "▶") }
                Button(onClick = { player?.seekToNext() }) { Text("⏭") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = {
                    player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                }) { Text(if (shuffle) "🔀 Aleatório: on" else "🔀 Aleatório: off") }
                TextButton(onClick = {
                    player?.let {
                        it.repeatMode = when (it.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                }) { Text(repeatLabel) }
            }
        }
    }
}