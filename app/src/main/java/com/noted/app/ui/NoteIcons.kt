package com.jot.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.jot.app.data.Note
import com.jot.app.data.NotesRepository
import java.io.File

/** Íconos incluidos para elegir en la lista de notas. La clave se guarda en la nota. */
val NOTE_ICONS: List<Pair<String, ImageVector>> = listOf(
    "note" to Icons.Outlined.Description,
    "star" to Icons.Outlined.Star,
    "favorite" to Icons.Outlined.Favorite,
    "bookmark" to Icons.Outlined.Bookmark,
    "flag" to Icons.Outlined.Flag,
    "idea" to Icons.Outlined.Lightbulb,
    "bolt" to Icons.Outlined.Bolt,
    "checklist" to Icons.Outlined.Checklist,
    "task" to Icons.Outlined.TaskAlt,
    "calendar" to Icons.Outlined.CalendarMonth,
    "alarm" to Icons.Outlined.Alarm,
    "folder" to Icons.Outlined.Folder,
    "work" to Icons.Outlined.Work,
    "home" to Icons.Outlined.Home,
    "school" to Icons.Outlined.School,
    "book" to Icons.Outlined.Book,
    "reading" to Icons.Outlined.MenuBook,
    "science" to Icons.Outlined.Science,
    "mind" to Icons.Outlined.Psychology,
    "code" to Icons.Outlined.Code,
    "terminal" to Icons.Outlined.Terminal,
    "settings" to Icons.Outlined.Settings,
    "build" to Icons.Outlined.Build,
    "laptop" to Icons.Outlined.Laptop,
    "phone" to Icons.Outlined.Smartphone,
    "tv" to Icons.Outlined.Tv,
    "games" to Icons.Outlined.SportsEsports,
    "music" to Icons.Outlined.MusicNote,
    "headphones" to Icons.Outlined.Headphones,
    "movie" to Icons.Outlined.Movie,
    "camera" to Icons.Outlined.PhotoCamera,
    "art" to Icons.Outlined.Palette,
    "brush" to Icons.Outlined.Brush,
    "money" to Icons.Outlined.AttachMoney,
    "bank" to Icons.Outlined.AccountBalance,
    "shopping" to Icons.Outlined.ShoppingCart,
    "food" to Icons.Outlined.Restaurant,
    "coffee" to Icons.Outlined.LocalCafe,
    "cake" to Icons.Outlined.Cake,
    "party" to Icons.Outlined.Celebration,
    "fitness" to Icons.Outlined.FitnessCenter,
    "health" to Icons.Outlined.LocalHospital,
    "nature" to Icons.Outlined.Park,
    "sun" to Icons.Outlined.WbSunny,
    "pets" to Icons.Outlined.Pets,
    "travel" to Icons.Outlined.Flight,
    "car" to Icons.Outlined.DirectionsCar,
    "world" to Icons.Outlined.Public,
    "rocket" to Icons.Outlined.RocketLaunch,
    "person" to Icons.Outlined.Person,
    "group" to Icons.Outlined.Group,
    "call" to Icons.Outlined.Phone,
    "email" to Icons.Outlined.Email,
    "key" to Icons.Outlined.Key,
    "lock" to Icons.Outlined.Lock
)

private val noteIconsByKey = NOTE_ICONS.toMap()

/** ImageLoader de Coil con soporte de SVG, compartido por toda la app. */
internal object NoteIconLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
            .also { loader = it }
    }
}

/** El emoji de un ícono `emoji:…`, o null si el ícono es de otro tipo. */
fun emojiOf(icon: String?): String? =
    icon?.takeIf { it.startsWith(NotesRepository.ICON_EMOJI) }
        ?.removePrefix(NotesRepository.ICON_EMOJI)
        ?.takeIf { it.isNotBlank() }

/**
 * Ícono de la nota en la lista: el incluido que se eligió, el PNG/SVG propio o,
 * si no tiene ninguno, un ícono genérico gris.
 */
@Composable
fun NoteIcon(note: Note, iconFile: File?, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val context = LocalContext.current
    val builtin = note.icon
        ?.takeIf { it.startsWith(NotesRepository.ICON_BUILTIN) }
        ?.removePrefix(NotesRepository.ICON_BUILTIN)
        ?.let { noteIconsByKey[it] }
    val emoji = emojiOf(note.icon)
    // Si el archivo propio falta o no se puede dibujar, va el ícono genérico en vez
    // de un hueco.
    var fileFailed by remember(iconFile) { mutableStateOf(iconFile?.exists() == false) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            iconFile != null && !fileFailed -> AsyncImage(
                model = iconFile,
                imageLoader = remember(context) { NoteIconLoader.get(context) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { fileFailed = true },
                modifier = Modifier.size(size)
            )
            builtin != null -> Icon(
                builtin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size)
            )
            emoji != null -> Text(
                emoji,
                fontSize = with(LocalDensity.current) { (size * 0.85f).toSp() }
            )
            else -> Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(size)
            )
        }
    }
}
