package com.musiccut.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.musiccut.app.data.LogicProjectImporter
import com.musiccut.app.drive.DriveAuthorizer
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.importLogicProject
import com.musiccut.app.ui.screens.DawScreen
import com.musiccut.app.ui.theme.MusicCutTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: DawViewModel by viewModels()
    private lateinit var driveAuthorizer: DriveAuthorizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        driveAuthorizer = DriveAuthorizer(this)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            MusicCutTheme {
                DawScreen(
                    vm = vm,
                    driveToken = { driveAuthorizer.accessToken() },
                    onShare = ::share,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStop() {
        super.onStop()
        // Don't keep the audio device busy in the background unless recording.
        if (!vm.isRecording) vm.stop()
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri?.let {
            if (LogicProjectImporter.looksLikeLogicProject(this, it)) vm.importLogicProject(it)
            else vm.importAudio(it)
        }
    }

    private fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "공유"))
    }
}
