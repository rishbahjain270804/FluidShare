package com.ether.share.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.ether.share.network.*
import com.ether.share.protocol.MotionVector
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class ShareActivity : ComponentActivity() {
    private lateinit var discovery: EtherDiscovery
    private lateinit var receiver: EtherReceiver
    private lateinit var sender: EtherSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Ether components
        val instance = "${android.os.Build.MODEL}-${System.currentTimeMillis() % 10000}"
        receiver = EtherReceiver()
        receiver.start()
        discovery = EtherDiscovery(this, instance, receiver.actualPort)
        discovery.start()
        sender = EtherSender()

        // Handle incoming share intent
        val imageUri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }

        val imageBuffer = imageUri?.let { uri ->
            contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            }
        }

        setContent {
            EtherTheme {
                if (imageBuffer != null) {
                    FlickShareScreen(
                        imageBuffer = imageBuffer,
                        imageMime = imageUri?.let { contentResolver.getType(it) } ?: "image/jpeg",
                        imageName = imageUri?.lastPathSegment ?: "image",
                        peers = discovery.peers,
                        onThrow = { target, motion ->
                            lifecycleScope.launch {
                                sender.send(target.host, target.port, imageBuffer, "image/jpeg", "flicked.jpg", motion)
                            }
                        },
                    )
                } else {
                    GalleryScreen(discovery.peers)
                }
            }
        }

        // Listen for received images
        receiver.onEvent { event ->
            when (event) {
                is EtherReceiver.Catch -> {
                    // Handle catch animation trigger (will move image to UI thread)
                    runOnUiThread {
                        // Toast or notification
                    }
                }

                is EtherReceiver.Complete -> {
                    runOnUiThread {
                        // Save to media store (using MediaStore API)
                        saveImageToMediaStore(event.info.buffer, event.info.name)
                    }
                }

                is EtherReceiver.Reject -> {
                    runOnUiThread {
                        // Show error
                    }
                }
            }
        }
    }

    private fun saveImageToMediaStore(buffer: ByteArray, name: String) {
        // TODO: Use MediaStore API to save the image to Pictures directory
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver.close()
        discovery.stop()
    }
}

@Composable
fun EtherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0x5B8CFF),
            secondary = Color(0x8F6BFF),
            background = Color(0x0B0E14),
            surface = Color(0x141925),
        ),
        content = content,
    )
}

@Composable
fun FlickShareScreen(
    imageBuffer: ByteArray,
    imageMime: String,
    imageName: String,
    peers: StateFlow<Map<String, Peer>>,
    onThrow: (Peer, MotionVector) -> Unit,
) {
    val peersMap by peers.collectAsState()
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val samples = remember { mutableListOf<PointerSample>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x0B0E14))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        samples.clear()
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        samples.add(PointerSample(change.position.x, change.position.y, System.currentTimeMillis()))
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y

                        // Check if thrown (crossed top edge with velocity)
                        if (offsetY < -100) {
                            val motion = estimateVelocity(samples)
                            if (motion.velocity > 1.2f) {
                                onThrow(peersMap.values.first(), motion)
                                isDragging = false
                            }
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Display image with drag transform
        AsyncImage(
            model = imageBuffer,
            contentDescription = imageName,
            modifier = Modifier
                .size(300.dp)
                .offset(x = (offsetX / 40).dp, y = (offsetY / 40).dp),
            contentScale = ContentScale.Crop,
        )

        if (peersMap.isEmpty()) {
            Text(
                "No nearby devices found",
                color = Color(0x8A97B1),
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
            )
        }
    }
}

@Composable
fun GalleryScreen(peers: StateFlow<Map<String, Peer>>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x0B0E14)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Open a photo to share",
            color = Color(0x8A97B1),
            textAlign = TextAlign.Center,
        )
    }
}

data class PointerSample(val x: Float, val y: Float, val t: Long)

fun estimateVelocity(samples: List<PointerSample>): MotionVector {
    if (samples.size < 2) return MotionVector(0f, 0f, 0f)
    val a = samples.first()
    val b = samples.last()
    val dt = maxOf(b.t - a.t, 1L).toFloat()
    val vx = (b.x - a.x) / dt
    val vy = (a.y - b.y) / dt
    val speed = kotlin.math.hypot(vx, vy).coerceIn(0f, 6f)
    val angle = kotlin.math.atan2(vx, kotlin.math.max(vy, 0.0001f))
    return MotionVector(vy, angle, b.x / 1080) // normalized by screen width
}
