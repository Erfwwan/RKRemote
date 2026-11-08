package com.rkremote.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner-side capture controls and controller-side live camera/audio preview.
 * The target owner starts each capture locally; network requests only read
 * already-published frames/chunks and cannot turn sensors on.
 */
@Composable
fun RemoteMediaPanel(
    targetIp: String,
    targetToken: String,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cameraJob by remember { mutableStateOf<Job?>(null) }
    var audioJob by remember { mutableStateOf<Job?>(null) }
    var cameraPreview by remember { mutableStateOf(false) }
    var audioListening by remember { mutableStateOf(false) }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CameraCaptureService::class.java))
            }.onSuccess { onStatus("Camera capture started. The target phone shows a persistent stop notification.") }
                .onFailure { onStatus("Could not start camera: ${it.message}") }
        } else onStatus("Camera permission was denied.")
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MicrophoneCaptureService::class.java))
            }.onSuccess { onStatus("Microphone capture started. The target phone shows a persistent stop notification.") }
                .onFailure { onStatus("Could not start microphone: ${it.message}") }
        } else onStatus("Microphone permission was denied.")
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraJob?.cancel()
            audioJob?.cancel()
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("PART 4 · CAMERA & MICROPHONE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Start sensors on the target phone itself. Android permissions and persistent notifications remain visible; the controller cannot activate either sensor remotely.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("TARGET PHONE · LOCAL SENSOR CONTROLS", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        runCatching {
                            ContextCompat.startForegroundService(context, Intent(context, CameraCaptureService::class.java))
                        }.onSuccess { onStatus("Camera capture started.") }
                            .onFailure { onStatus("Could not start camera: ${it.message}") }
                    } else cameraPermission.launch(Manifest.permission.CAMERA)
                }) { Text("Start camera") }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, CameraCaptureService::class.java).setAction(CameraCaptureService.ACTION_STOP))
                    cameraBitmap = null
                    onStatus("Camera stop requested on this phone.")
                }) { Text("Stop camera") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        runCatching {
                            ContextCompat.startForegroundService(context, Intent(context, MicrophoneCaptureService::class.java))
                        }.onSuccess { onStatus("Microphone capture started.") }
                            .onFailure { onStatus("Could not start microphone: ${it.message}") }
                    } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Start microphone") }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, MicrophoneCaptureService::class.java).setAction(MicrophoneCaptureService.ACTION_STOP))
                    onStatus("Microphone stop requested on this phone.")
                }) { Text("Stop microphone") }
            }

            Text("CONTROLLER PHONE · REMOTE CAMERA", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !cameraPreview && targetIp.isNotBlank() && targetToken.isNotBlank(), onClick = {
                    cameraPreview = true
                    val ip = targetIp.trim()
                    val token = targetToken.trim()
                    cameraJob = scope.launch {
                        try {
                            while (isActive) {
                                val frame = withContext(Dispatchers.IO) {
                                    val bytes = LanControllerClient.requestBytes(ip, token, "/camera.jpg")
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        ?: error("Camera returned an invalid JPEG frame")
                                }
                                cameraBitmap = frame
                                delay(650)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            onStatus("Camera preview stopped: ${error.message}")
                        } finally {
                            cameraPreview = false
                            cameraJob = null
                        }
                    }
                }) { Text("View camera") }
                OutlinedButton(enabled = cameraPreview, onClick = {
                    cameraJob?.cancel()
                    onStatus("Stopping remote camera preview…")
                }) { Text("Stop view") }
            }
            cameraBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Owner-approved remote camera preview",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }

            Text("CONTROLLER PHONE · REMOTE MICROPHONE", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !audioListening && targetIp.isNotBlank() && targetToken.isNotBlank(), onClick = {
                    audioListening = true
                    val ip = targetIp.trim()
                    val token = targetToken.trim()
                    audioJob = scope.launch(Dispatchers.IO) {
                        val minBuffer = AudioTrack.getMinBufferSize(
                            MicrophoneCaptureService.SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        ).coerceAtLeast(8192)
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(MicrophoneCaptureService.SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBuffer)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        try {
                            track.play()
                            while (isActive) {
                                val chunk = LanControllerClient.requestBytes(ip, token, "/audio/chunk")
                                if (chunk.isNotEmpty()) track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                                else delay(35)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main) { onStatus("Remote audio stopped: ${error.message}") }
                        } finally {
                            runCatching { track.stop() }
                            track.release()
                            withContext(Dispatchers.Main) {
                                audioListening = false
                                audioJob = null
                            }
                        }
                    }
                }) { Text("Listen to microphone") }
                OutlinedButton(enabled = audioListening, onClick = {
                    audioJob?.cancel()
                    onStatus("Stopping remote microphone playback…")
                }) { Text("Stop listening") }
            }
            Text(
                "Audio format: mono PCM, 16 kHz, 16-bit. Camera preview is low-rate JPEG. These are prototypes, not encrypted internet streaming.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
