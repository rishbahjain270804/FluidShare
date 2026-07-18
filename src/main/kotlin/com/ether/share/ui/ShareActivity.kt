package com.ether.share.ui

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ether.share.network.*
import com.ether.share.protocol.MotionVector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ShareActivity : ComponentActivity() {
    private lateinit var discovery: EtherDiscovery
    private lateinit var receiver: EtherReceiver
    private lateinit var sender: EtherSender
    private var selectedImageUri: Uri? = null
    private var imageBuffer: ByteArray? = null
    private var imageMime: String = "image/jpeg"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeEther()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            loadImageFromUri(uri)
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initializeEther()
        }
    }

    private fun initializeEther() {
        // Initialize Ether components
        val instance = "${Build.MODEL}-${System.currentTimeMillis() % 10000}"
        receiver = EtherReceiver()
        receiver.start()
        discovery = EtherDiscovery(this, instance, receiver.actualPort)
        discovery.start()
        sender = EtherSender()

        // Handle incoming share intent
        val sharedImageUri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }

        if (sharedImageUri != null) {
            selectedImageUri = sharedImageUri
            loadImageFromUri(sharedImageUri)
        }

        updateUI()

        // Listen for received images
        receiver.onEvent { event ->
            when (event) {
                is EtherReceiver.Catch -> {
                    runOnUiThread {
                        Toast.makeText(this, "📨 Image caught!", Toast.LENGTH_SHORT).show()
                    }
                }

                is EtherReceiver.Complete -> {
                    runOnUiThread {
                        saveImageToMediaStore(event.info.buffer, event.info.name)
                    }
                }

                is EtherReceiver.Reject -> {
                    runOnUiThread {
                        Toast.makeText(this, "❌ Transfer rejected: ${event.reason}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun detectImageMime(buffer: ByteArray?): String {
        if (buffer == null || buffer.size < 3) return "image/jpeg"
        return when {
            buffer.size >= 3 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xD8.toByte() -> "image/jpeg"
            buffer.size >= 4 && buffer[0] == 0x89.toByte() && buffer[1] == 0x50.toByte() -> "image/png"
            buffer.size >= 4 && buffer[0] == 0x47.toByte() && buffer[1] == 0x49.toByte() -> "image/gif"
            buffer.size >= 12 && buffer.sliceArray(8..11).contentEquals("WEBP".toByteArray()) -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            imageBuffer = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            imageMime = contentResolver.getType(uri) ?: detectImageMime(imageBuffer)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        setContent {
            EtherTheme {
                if (imageBuffer != null) {
                    FlickShareScreen(
                        imageBuffer = imageBuffer!!,
                        imageMime = imageMime,
                        imageName = selectedImageUri?.lastPathSegment ?: "image",
                        peers = discovery.peers,
                        onThrow = { target, motion ->
                            lifecycleScope.launch {
                                sender.send(target.host, target.port, imageBuffer!!, imageMime, "flicked.jpg", motion)
                            }
                        },
                    )
                } else {
                    GalleryScreen(
                        peers = discovery.peers,
                        onPickImage = {
                            imagePickerLauncher.launch(PickVisualMedia.ImageOnly)
                        }
                    )
                }
            }
        }
    }

    private fun saveImageToMediaStore(buffer: ByteArray, name: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "Ether_$timestamp.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ether")
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    out.write(buffer)
                    out.flush()
                }
            }

            Toast.makeText(this, "Image saved to Pictures/Ether", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver.close()
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
    var isFlinging by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }
    val samples = remember { mutableListOf<PointerSample>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x0B0E14)),
    ) {
        // Header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = Color.Transparent,
        ) {
            Column {
                Text(
                    "🎯 Flick to Share",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFFF),
                )
                Text(
                    if (peersMap.isEmpty()) "🔍 Finding devices..." else "✓ ${peersMap.size} device${if (peersMap.size != 1) "s" else ""} nearby",
                    fontSize = 12.sp,
                    color = Color(0x8A97B1),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Main content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            isFlinging = false
                            samples.clear()
                            offsetX = 0f
                            offsetY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            samples.add(PointerSample(change.position.x, change.position.y, System.currentTimeMillis()))
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y

                            if (offsetY < -150 && selectedPeer != null) {
                                val motion = estimateVelocity(samples)
                                if (motion.velocity > 1.5f) {
                                    isFlinging = true
                                    selectedPeer?.let { onThrow(it, motion) }
                                    isDragging = false
                                    selectedPeer = null
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
            val bitmap = BitmapFactory.decodeByteArray(imageBuffer, 0, imageBuffer.size)
            if (bitmap != null) {
                val scale = if (isDragging) 0.95f else if (isFlinging) 0.8f else 1f
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = imageName,
                    modifier = Modifier
                        .size(280.dp)
                        .scale(scale)
                        .offset(x = (offsetX / 50).dp, y = (offsetY / 50).dp),
                    contentScale = ContentScale.Crop,
                )
            }

            if (peersMap.isEmpty()) {
                Text(
                    "🔍 Searching for devices...",
                    color = Color(0x8A97B1),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            } else if (selectedPeer != null && isDragging && offsetY < -100) {
                Text(
                    "⬆️ Keep pulling to send!",
                    color = Color(0x5B8CFF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Peer selection
        if (peersMap.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0x1F2937),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "📱 Select Device to Send To",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0x8A97B1),
                    )
                    peersMap.forEach { (_, peer) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable { selectedPeer = peer }
                                .background(
                                    if (selectedPeer?.instance == peer.instance) Color(0x5B8CFF) else Color(0x0B0E14),
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedPeer?.instance == peer.instance) Color(0x5B8CFF) else Color(0x262D3D),
                        ) {
                            Text(
                                "✓ ${peer.instance}",
                                fontSize = 14.sp,
                                color = Color(0xFFFFFF),
                                modifier = Modifier.padding(12.dp),
                                fontWeight = if (selectedPeer?.instance == peer.instance) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryScreen(peers: StateFlow<Map<String, Peer>>, onPickImage: () -> Unit) {
    val peersMap by peers.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x0B0E14))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "🎨 Ether",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0x5B8CFF),
        )

        Text(
            "Fluid image sharing",
            fontSize = 14.sp,
            color = Color(0x8A97B1),
            modifier = Modifier.padding(top = 8.dp),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0x1F2937),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "📱 Status",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0x8A97B1),
                )
                Text(
                    if (peersMap.isEmpty()) "🔍 Looking for nearby devices..." else "✓ ${peersMap.size} device${if (peersMap.size != 1) "s" else ""} found!",
                    fontSize = 14.sp,
                    color = if (peersMap.isEmpty()) Color(0x8A97B1) else Color(0x5B8CFF),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Pick image button
        Button(
            onClick = onPickImage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0x5B8CFF),
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                "📸 Pick Image to Share",
                modifier = Modifier.padding(8.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            "📸 Or use Share Sheet:",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFFF),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 16.dp),
        )

        Text(
            "1. Open Gallery app → pick photo\n2. Tap Share → Ether\n3. Flick image upward to send\n4. Watch it drop on another device!",
            fontSize = 12.sp,
            color = Color(0x8A97B1),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 8.dp),
            lineHeight = 20.sp,
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
