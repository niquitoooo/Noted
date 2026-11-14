package com.noted.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.noted.app.ui.NotesViewModel
import com.noted.app.ui.NotedApp
import com.noted.app.ui.NotedTheme

class MainActivity : ComponentActivity() {

    private val viewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            NotedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotedApp(viewModel)
                }
            }
        }
    }

    // Cambios hechos fuera del editor (papelera, fijar, reordenar) también se
    // guardan al pasar a segundo plano, sin esperar el retardo.
    override fun onStop() {
        super.onStop()
        viewModel.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Abrir desde recientes vuelve a entregar el último intent de compartir:
        // no hay que crear la nota otra vez.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // Algunas apps mandan el texto con formato (no como String).
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = collectStreams(intent)
                if (uris.isNotEmpty() || !text.isNullOrBlank()) {
                    viewModel.shareIncoming(uris, text)
                }
            }
            else -> Unit
        }

        intent.setAction(null)
    }

    private fun collectStreams(intent: Intent): List<Uri> {
        intent.clipData?.let { clip ->
            if (clip.itemCount > 0) {
                return (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
            }
        }

        @Suppress("DEPRECATION")
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)

            else ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
