package com.rkremote.remote

import android.Manifest
import android.content.Intent
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
    var pendingCameraFacing by remember { mutableStateOf(CameraCharacteristics.LENS_FACING_BACK) }
    var audioListening by remember { mutableStateOf(false) }
    var pttPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var pttActive by remember { mutableStateOf(false) }
    var pttJob by remember { mutableStateOf<Job?>(null) }
    var pttRecorder by remember { mutableStateOf<AudioRecord?>(null) }

    val pttPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pttPermissionGranted = granted
        onStatus(if (granted) "Microphone permission granted. Hold the push-to-talk control to speak." else "Microphone permission was denied.")
    }

    val beginPtt: () -> Unit = {
        if (!pttActive && targetIp.isNotBlank() && targetToken.isNotBlank()) {
            pttActive = true
            val ip = targetIp.trim()
            val token = targetToken.trim()
            pttJob = scope.launch(Dispatchers.IO) {
                var recorder: AudioRecord? = null
                var aec: AcousticEchoCanceler? = null
                var ns: NoiseSuppressor? = null
                try {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        MicrophoneCaptureService.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    require(minBuffer > 0) { "Microphone PCM format is not supported" }
                    val audio = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        MicrophoneCaptureService.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer * 2, 8192)
                    )
                    require(audio.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize microphone" }
                    recorder = audio
                    // Enable available echo/noise effects without switching Android into call mode.
                    aec = if (AcousticEchoCanceler.isAvailable()) {
                        runCatching { AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true } }.getOrNull()
                    } else null
                    ns = if (NoiseSuppressor.isAvailable()) {
                        runCatching { NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true } }.getOrNull()
                    } else null
                    withContext(Dispatchers.Main) { pttRecorder = audio }
                    audio.startRecording()
                    val buffer = ByteArray(3200) // approximately 100 ms at 16 kHz / 16-bit mono
                    while (isActive) {
                        val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0 && isActive) {
                            val accepted = LanControllerClient.postPttChunk(ip, token, buffer.copyOf(count))
                            if (!accepted) throw IllegalStateException("Target PTT speaker is not enabled")
                        } else if (count < 0) {
                            throw IllegalStateException("Microphone read failed: $count")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) { onStatus("PTT stopped: ${error.message}") }
                } finally {
                    recorder?.let { audio ->
                        runCatching { audio.stop() }
                        audio.release()
                    }
                    runCatching { aec?.release() }
                    runCatching { ns?.release() }
                    withContext(Dispatchers.Main) {
                        pttRecorder = null
                        pttActive = false
                        pttJob = null
                    }
                }
            }
        }
    }
    val endPtt: () -> Unit = {
        pttRecorder?.let { audio -> runCatching { audio.stop() } }
        pttJob?.cancel()
        onStatus("PTT transmission stopped.")
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCameraCapture(context, pendingCameraFacing, onStatus)
        else onStatus("Camera permission was denied.")
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
            pttRecorder?.let { runCatching { it.stop() } }
            pttJob?.cancel()
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
                    pendingCameraFacing = CameraCharacteristics.LENS_FACING_BACK
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraCapture(context, pendingCameraFacing, onStatus)
                    } else cameraPermission.launch(Manifest.permission.CAMERA)
                }) { Text("ثبت عکس با دوربین پشت") }
                OutlinedButton(onClick = {
                    pendingCameraFacing = CameraCharacteristics.LENS_FACING_FRONT
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraCapture(context, pendingCameraFacing, onStatus)
                    } else cameraPermission.launch(Manifest.permission.CAMERA)
                }) { Text("ثبت عکس با دوربین جلو") }
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

            Text("TARGET PHONE · PTT SPEAKER", fontWeight = FontWeight.SemiBold)
            Text(
                "The target owner must enable this speaker locally. Incoming voice cannot play while it is disabled.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    runCatching {
                        ContextCompat.startForegroundService(context, Intent(context, PttPlaybackService::class.java))
                    }.onSuccess { onStatus("PTT speaker enabled locally. A persistent stop notification is visible.") }
                        .onFailure { onStatus("Could not enable PTT speaker: ${it.message}") }
                }) { Text("Enable PTT speaker") }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, PttPlaybackService::class.java).setAction(PttPlaybackService.ACTION_STOP))
                    onStatus("PTT speaker disabled locally.")
                }) { Text("Disable PTT speaker") }
            }

            Text("PART 5 · TWO-WAY AUDIO / PUSH-TO-TALK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Half-duplex walkie-talkie: hold to stream your voice live to the target speaker. This is not a phone call and the two directions are not active at once.", style = MaterialTheme.typography.bodySmall)
            Text("CONTROLLER PHONE · LIVE PUSH TO TALK", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth().pointerInput(pttPermissionGranted, targetIp, targetToken) {
                    detectTapGestures(onPress = {
                        if (!pttPermissionGranted) {
                            onStatus("Grant microphone permission before using push-to-talk.")
                            pttPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else if (targetIp.isNotBlank() && targetToken.isNotBlank()) {
                            beginPtt()
                            tryAwaitRelease()
                            endPtt()
                        }
                    })
                }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (pttActive) "TRANSMITTING LIVE · RELEASE TO STOP" else "HOLD TO TALK",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Audio is sent in short live chunks while you hold the button and plays on the target as each chunk arrives. It is not recorded first.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text("CONTROLLER PHONE · SINGLE PHOTO", fontWeight = FontWeight.SemiBold)
            OutlinedButton(enabled = !cameraPreview && targetIp.isNotBlank() && targetToken.isNotBlank(), onClick = {
                cameraPreview = true
                val ip = targetIp.trim()
                val token = targetToken.trim()
                cameraJob = scope.launch {
                    try {
                        val photo = withContext(Dispatchers.IO) {
                            val bytes = LanControllerClient.requestBytes(ip, token, "/camera.jpg")
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: error("Camera returned an invalid photo")
                        }
                        cameraBitmap = photo
                        onStatus("One photo received.")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onStatus("Photo request failed: ${error.message}")
                    } finally {
                        cameraPreview = false
                        cameraJob = null
                    }
                }
            }) { Text("دریافت یک عکس") }
            cameraBitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "Owner-approved single remote photo",
                    modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }

            Text("CONTROLLER PHONE · RECEIVE AUDIO (SIMULTANEOUS)", fontWeight = FontWeight.SemiBold)
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
                "PTT sends mono PCM, 16 kHz, 16-bit in live chunks while held; the target plays each chunk as it arrives through its main loudspeaker. The separate Section 4 microphone monitor is not part of PTT and should not be used simultaneously. Android echo cancellation and noise suppression are enabled when available, but cannot guarantee zero feedback. Camera frames are JPEG-compressed at quality 82 before transfer. This is a LAN prototype, not encrypted internet streaming.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


private fun startCameraCapture(
    context: Context,
    lensFacing: Int,
    onStatus: (String) -> Unit
) {
    val intent = Intent(context, CameraCaptureService::class.java)
        .putExtra(CameraCaptureService.EXTRA_LENS_FACING, lensFacing)
    runCatching {
        ContextCompat.startForegroundService(context, intent)
    }.onSuccess {
        val cameraName = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Rear"
        onStatus(cameraName + " camera started. Its persistent notification is visible.")
    }.onFailure { error ->
        onStatus("Could not start camera: " + (error.message ?: "unknown error"))
    }
}
