package com.jot.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.jot.app.R
import com.jot.app.data.Attachment
import com.jot.app.util.humanSize
import com.jot.app.util.mimeIcon
import com.jot.app.util.openExternally
import com.jot.app.util.shareFile
import com.jot.app.util.typeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AttachmentViewerScreen(
    attachment: Attachment,
    file: File,
    onBack: () -> Unit
) {
    when {
        attachment.isVideo -> MediaScreen(attachment, file, onBack, audioOnly = false)
        attachment.isAudio -> MediaScreen(attachment, file, onBack, audioOnly = true)
        attachment.isPdf -> PdfScreen(attachment, file, onBack)
        attachment.isImage -> ImageScreen(attachment, file, onBack)
        else -> FileScreen(attachment, file, onBack)
    }
}

// ------------------------------------------------------------------ galería

/**
 * Galería de fotos y videos de una nota. Se abre al tocar cualquier foto o
 * video y permite pasar al anterior/siguiente deslizando o con las flechas.
 */
@Composable
fun MediaGalleryScreen(
    attachments: List<Attachment>,
    initialAttachmentId: String,
    fileFor: (Attachment) -> File,
    onBack: () -> Unit
) {
    if (attachments.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val initialIndex = attachments.indexOfFirst { it.id == initialAttachmentId }
        .takeIf { it >= 0 }
        ?: 0
    val pagerState = rememberPagerState(initialPage = initialIndex) { attachments.size }
    val page = pagerState.currentPage.coerceIn(0, attachments.lastIndex)
    val current = attachments[page]
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    var immersive by remember { mutableStateOf(false) }

    LaunchedEffect(immersive) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    ViewerScaffold(
        attachment = current,
        file = fileFor(current),
        onBack = onBack,
        darkContent = true,
        showTopBar = !immersive
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapPositionalThreshold = 0.2f
                )
            ) { index ->
                val item = attachments[index]
                if (item.isVideo) {
                    GalleryVideoPage(
                        attachment = item,
                        file = fileFor(item),
                        isActive = index == page,
                        immersive = immersive,
                        onToggleImmersive = { immersive = !immersive }
                    )
                } else {
                    GalleryImagePage(
                        attachment = item,
                        file = fileFor(item),
                        onTap = { immersive = !immersive }
                    )
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                Text(
                    text = "${page + 1} / ${attachments.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            if (page > 0) {
                IconButton(
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(page - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                ) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "Anterior",
                        tint = Color.White
                    )
                }
            }

            if (page < attachments.lastIndex) {
                IconButton(
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "Siguiente",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Contenedor con zoom por pellizco y arrastre. Con un solo dedo y escala 1x
 * no consume el gesto, así el HorizontalPager recibe el swipe nativo.
 * Si recibe [onTap], avisa los toques simples (sin swipe ni pellizco).
 */
@Composable
private fun ZoomableBox(
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var zooming = scale > 1f
                    var moved = false
                    var maxPointers = 1
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed
                        if (pressed >= 2) zooming = true
                        if (!zooming) {
                            event.changes.forEach { change ->
                                if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    moved = true
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                            continue
                        }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (abs(zoomChange - 1f) > 0.01f || panChange.getDistance() > 1f) {
                            moved = true
                        }
                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                        val maxX = (containerSize.width * (newScale - 1f)) / 2f
                        val maxY = (containerSize.height * (newScale - 1f)) / 2f
                        scale = newScale
                        offset = Offset(
                            (offset.x + panChange.x).coerceIn(-maxX, maxX),
                            (offset.y + panChange.y).coerceIn(-maxY, maxY)
                        )
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                    if (!moved && maxPointers == 1) currentOnTap?.invoke()
                }
            }
    ) {
        content(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
    }
}

@Composable
private fun GalleryImagePage(
    attachment: Attachment,
    file: File,
    onTap: () -> Unit
) {
    ZoomableBox(Modifier.fillMaxSize(), onTap = onTap) { imageModifier ->
        AsyncImage(
            model = file,
            contentDescription = attachment.name,
            contentScale = ContentScale.Fit,
            modifier = imageModifier.fillMaxSize()
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun GalleryVideoPage(
    attachment: Attachment,
    file: File,
    isActive: Boolean,
    immersive: Boolean,
    onToggleImmersive: () -> Unit
) {
    val context = LocalContext.current
    // Se prepara recién cuando su página es la actual: antes se preparaban hasta
    // tres videos a la vez y con videos pesados alguno quedaba en negro.
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = false
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Sin autoplay: el video se queda pausado hasta que el usuario toca play.
    // Al salir de su página se pausa y deja de ocupar el decodificador.
    LaunchedEffect(isActive) {
        if (isActive) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        } else {
            player.pause()
            if (player.playbackState != Player.STATE_IDLE) player.stop()
            failed = false
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                position = player.currentPosition.coerceAtLeast(0L)
                if (duration <= 0L) duration = player.duration.coerceAtLeast(0L)
            }
            delay(300)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ZoomableBox(
            modifier = Modifier.fillMaxSize(),
            onTap = { onToggleImmersive() }
        ) { videoModifier ->
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_texture_view, null) as PlayerView).apply {
                        this.player = player
                        // El pager tiene que quedarse con el arrastre horizontal.
                        setOnTouchListener { _, _ -> false }
                    }
                },
                // La pantalla queda encendida solo mientras el video se reproduce.
                update = { view -> view.keepScreenOn = isPlaying },
                modifier = videoModifier.fillMaxSize()
            )
        }

        if (failed) {
            Text(
                text = "No se pudo reproducir este video.",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
            )
        }

        if (!immersive) {
            IconButton(
                onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED ||
                            (player.duration > 0 && player.currentPosition >= player.duration - 500)
                        ) {
                            player.seekTo(0)
                        }
                        player.play()
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            val max = duration.toFloat().coerceAtLeast(1f)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Slider(
                    value = (if (scrubbing) scrubPosition else position.toFloat()).coerceIn(0f, max),
                    onValueChange = {
                        scrubPosition = it
                        scrubbing = true
                    },
                    onValueChangeFinished = {
                        player.seekTo(scrubPosition.toLong())
                        position = scrubPosition.toLong()
                        scrubbing = false
                    },
                    valueRange = 0f..max,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(if (scrubbing) scrubPosition.toLong() else position),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = formatTime(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// --------------------------------------------------------------------- común

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScaffold(
    attachment: Attachment,
    file: File,
    onBack: () -> Unit,
    darkContent: Boolean = false,
    showTopBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        containerColor = if (darkContent) Color.Black else MaterialTheme.colorScheme.background,
        topBar = {
            if (showTopBar) {
                TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (darkContent) Color.Black.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.surface,
                    titleContentColor = if (darkContent) Color.White else MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = if (darkContent) Color.White else MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = if (darkContent) Color.White else MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = attachment.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "${typeLabel(attachment.mime)} · ${humanSize(attachment.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { openExternally(context, file, attachment.mime) }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "Abrir con otra app")
                    }
                    IconButton(onClick = { shareFile(context, file, attachment.mime) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Compartir")
                    }
                    actions()
                }
            )
            }
        },
        content = content
    )
}

// --------------------------------------------------------------- video/audio

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaScreen(
    attachment: Attachment,
    file: File,
    onBack: () -> Unit,
    audioOnly: Boolean
) {
    val context = LocalContext.current

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ViewerScaffold(
        attachment = attachment,
        file = file,
        onBack = onBack,
        darkContent = !audioOnly
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(if (audioOnly) MaterialTheme.colorScheme.background else Color.Black)
        ) {
            if (audioOnly) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = mimeIcon(attachment.mime),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = humanSize(attachment.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { view -> view.keepScreenOn = isPlaying },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (audioOnly) Modifier.height(140.dp) else Modifier.fillMaxSize())
            )
        }
    }
}

// ------------------------------------------------------------------------ PDF

private sealed interface PdfUiState {
    data object Loading : PdfUiState
    data class Ready(val pageCount: Int) : PdfUiState
    data class Error(val message: String) : PdfUiState
}

private sealed interface PdfPage {
    data object Loading : PdfPage
    data class Ready(val bitmap: Bitmap) : PdfPage
    data object Failed : PdfPage
}

/**
 * Lector de un PDF. Abrir, dibujar y cerrar pasan por el mismo candado: si se sale
 * del visor mientras abre o dibuja una página, el lector se cierra igual al
 * terminar (antes quedaba abierto).
 */
private class PdfHolder {
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var closed = false
    private val mutex = Mutex()

    suspend fun open(file: File): PdfUiState = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed) return@withContext PdfUiState.Loading
            if (file.length() == 0L) {
                return@withContext PdfUiState.Error("El archivo está vacío.")
            }
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor = pfd
                val pdf = PdfRenderer(pfd)
                renderer = pdf
                if (pdf.pageCount == 0) PdfUiState.Error("El PDF no tiene páginas.")
                else PdfUiState.Ready(pdf.pageCount)
            } catch (e: Exception) {
                release()
                PdfUiState.Error(
                    when (e) {
                        is SecurityException -> "Este PDF está protegido con contraseña."
                        else -> "No se pudo abrir el PDF: el archivo está dañado o no es un PDF."
                    }
                )
            }
        }
    }

    suspend fun render(index: Int, targetWidth: Int): PdfPage = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pdf = renderer ?: return@withContext PdfPage.Failed
            try {
                pdf.openPage(index).use { page ->
                    val scale = pdfRenderScale(page.width, page.height, targetWidth)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    PdfPage.Ready(bitmap)
                }
            } catch (_: Exception) {
                PdfPage.Failed
            } catch (_: OutOfMemoryError) {
                PdfPage.Failed
            }
        }
    }

    /** Se llama al salir del visor; espera a que termine lo que se esté dibujando. */
    fun close() {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            mutex.withLock {
                closed = true
                release()
            }
        }
    }

    private fun release() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }
}

@Composable
private fun PdfScreen(attachment: Attachment, file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val holder = remember(file) { PdfHolder() }
    var state by remember(file) { mutableStateOf<PdfUiState>(PdfUiState.Loading) }

    LaunchedEffect(file) {
        state = holder.open(file)
    }

    DisposableEffect(file) {
        onDispose { holder.close() }
    }

    ViewerScaffold(attachment = attachment, file = file, onBack = onBack) { padding ->
        when (val current = state) {
            PdfUiState.Loading -> Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is PdfUiState.Error -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { openExternally(context, file, attachment.mime) }) {
                    Text("Abrir con otra app")
                }
            }

            is PdfUiState.Ready -> PdfPages(holder, current.pageCount, padding)
        }
    }
}

@Composable
private fun PdfPages(holder: PdfHolder, pageCount: Int, padding: PaddingValues) {
    val listState = rememberLazyListState()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = with(density) { (configuration.screenWidthDp.dp - 24.dp).roundToPx() }

    Box(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(pageCount) { index ->
                val pageState by produceState<PdfPage>(PdfPage.Loading, index, widthPx) {
                    value = holder.render(index, widthPx)
                }

                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val page = pageState) {
                        PdfPage.Loading -> Box(
                            Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        PdfPage.Failed -> Box(
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No se pudo mostrar la página ${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        is PdfPage.Ready -> Surface(
                            shape = RoundedCornerShape(4.dp),
                            shadowElevation = 2.dp,
                            color = Color.White
                        ) {
                            Image(
                                bitmap = page.bitmap.asImageBitmap(),
                                contentDescription = "Página ${index + 1}",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.62f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        ) {
            Text(
                text = "${listState.firstVisibleItemIndex + 1} / $pageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

// --------------------------------------------------------------------- imagen

@Composable
private fun ImageScreen(attachment: Attachment, file: File, onBack: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    ViewerScaffold(
        attachment = attachment,
        file = file,
        onBack = onBack,
        darkContent = true
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 8f)
                        scale = next
                        offset = if (next > 1f) offset + pan else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = file,
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }
    }
}

// ------------------------------------------------------------------- archivo

@Composable
private fun FileScreen(attachment: Attachment, file: File, onBack: () -> Unit) {
    val context = LocalContext.current

    ViewerScaffold(attachment = attachment, file = file, onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.size(120.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = mimeIcon(attachment.mime),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${typeLabel(attachment.mime)} · ${humanSize(attachment.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { openExternally(context, file, attachment.mime) }) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Abrir con otra app")
            }
        }
    }
}
