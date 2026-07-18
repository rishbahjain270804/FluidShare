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

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        android.util.Log.e("Ether", "imagePickerLauncher callback: uri=$uri")
        if (uri != null) {
            android.util.Log.e("Ether", "URI is valid, loading image")
            selectedImageUri = uri
            loadImageFromUri(uri)
            android.util.Log.e("Ether", "Image loaded, calling updateUI")
            updateUI()
        } else {
            android.util.Log.e("Ether", "URI is NULL - user cancelled")
        }
    }

    private fun pickImage() {
        android.util.Log.e("Ether", "pickImage called - launching image picker")
        imagePickerLauncher.launch("image/*")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("Ether", "===== ACTIVITY CREATED =====")
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        android.util.Log.e("Ether", "requestPermissionsIfNeeded called")
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

        android.util.Log.e("Ether", "Missing permissions: ${missingPermissions.size}")
        if (missingPermissions.isNotEmpty()) {
            android.util.Log.e("Ether", "Requesting permissions: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            android.util.Log.e("Ether", "All permissions already granted, initializing Ether")
            initializeEther()
        }
    }

    private fun initializeEther() {
        android.util.Log.e("Ether", "===== INITIALIZING ETHER =====")
        // Initialize Ether components
        val instance = "${Build.MODEL}-${System.currentTimeMillis() % 10000}"
        android.util.Log.e("Ether", "Instance name: $instance")
        receiver = EtherReceiver()
        receiver.start()
        android.util.Log.e("Ether", "Receiver started on port ${receiver.actualPort}")
        discovery = EtherDiscovery(this, instance, receiver.actualPort)
        discovery.start()
        android.util.Log.e("Ether", "Discovery started")
        sender = EtherSender()

        // Handle incoming share intent
        val sharedImageUri = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
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
                        android.util.Log.d("Ether", "Image caught from ${event.info.name}")
                    }
                }

                is EtherReceiver.Complete -> {
                    runOnUiThread {
                        Toast.makeText(this, "✅ Image received!", Toast.LENGTH_SHORT).show()
                        android.util.Log.d("Ether", "Image transfer complete: ${event.info.name}")
                        saveImageToMediaStore(event.info.buffer, event.info.name)
                    }
                }

                is EtherReceiver.Reject -> {
                    runOnUiThread {
                        Toast.makeText(this, "❌ ${event.reason}", Toast.LENGTH_LONG).show()
                        android.util.Log.e("Ether", "Transfer rejected: ${event.reason}")
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
        android.util.Log.d("Ether", "updateUI called. imageBuffer size: ${imageBuffer?.size ?: "null"}")
        setContent {
            EtherTheme {
                if (imageBuffer != null) {
                    android.util.Log.d("Ether", "Rendering FlickShareScreen with image size: ${imageBuffer!!.size}")
                    FlickShareScreen(
                        imageBuffer = imageBuffer!!,
                        imageMime = imageMime,
                        imageName = selectedImageUri?.lastPathSegment ?: "image",
                        peers = discovery.peers,
                        onThrow = { target, motion ->
                            android.util.Log.d("Ether", "Throwing image to ${target.instance} at ${target.host}:${target.port}")
                            lifecycleScope.launch {
                                try {
                                    val result = sender.send(target.host, target.port, imageBuffer!!, imageMime, "flicked.jpg", motion)
                                    if (result.isSuccess) {
                                        android.util.Log.d("Ether", "Send succeeded: ${result.getOrNull()} bytes")
                                        Toast.makeText(this@ShareActivity, "📤 Sent!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.util.Log.e("Ether", "Send failed: ${result.exceptionOrNull()}")
                                        Toast.makeText(this@ShareActivity, "❌ Send failed", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("Ether", "Send exception", e)
                                    Toast.makeText(this@ShareActivity, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                } else {
                    GalleryScreen(
                        peers = discovery.peers,
                        onPickImage = { pickImage() }
                    )
                }
            }
        }
    }

    private fun saveImageToMediaStore(buffer: ByteArray, @Suppress("UNUSED_PARAMETER") name: String) {
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
    @Suppress("UNUSED_PARAMETER") imageMime: String,
    imageName: String,
    peers: StateFlow<Map<String, Peer>>,
    onThrow: (Peer, MotionVector) -> Unit,
) {
    android.util.Log.e("Ether", "===== FLICK SCREEN START =====")
    val peersMap by peers.collectAsState()
    android.util.Log.e("Ether", "peersMap size: ${peersMap.size}")
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isFlinging by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }
    val samples = remember { mutableListOf<PointerSample>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x0B0E14))
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        // Header
        Text(
            "🎯 FLICK TO SHARE",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            if (peersMap.isEmpty()) "🔍 Finding ${peersMap.size} devices..." else "✓ ${peersMap.size} devices found!",
            fontSize = 14.sp,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Image display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(vertical = 12.dp)
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
                        .size(200.dp)
                        .scale(scale)
                        .offset(x = (offsetX / 15).dp, y = (offsetY / 15).dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Peer selection - EXTREME CONTRAST
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(Color.Red, shape = RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    ">>> SELECT DEVICE <<<",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                if (peersMap.isEmpty()) {
                    Text(
                        "SEARCHING FOR DEVICES...",
                        fontSize = 18.sp,
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp),
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    peersMap.forEach { (_, peer) ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .background(
                                    if (selectedPeer?.instance == peer.instance) Color.Yellow else Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedPeer = peer }
                                .padding(12.dp)
                        ) {
                            Text(
                                peer.instance,
                                fontSize = 18.sp,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
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
    android.util.Log.e("Ether", "===== GALLERY SCREEN RENDERING =====")
    val peersMap by peers.collectAsState()
    android.util.Log.e("Ether", "GalleryScreen peers: ${peersMap.size}")

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

        // HUGE Pick image button - IMPOSSIBLE TO MISS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(vertical = 16.dp)
                .background(Color.Red, shape = RoundedCornerShape(16.dp))
                .clickable {
                    android.util.Log.e("Ether", "PICK IMAGE BUTTON CLICKED!!!")
                    onPickImage()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "TAP TO PICK IMAGE",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
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
    @Suppress("UNUSED_VARIABLE")
    val speed = kotlin.math.hypot(vx, vy).coerceIn(0f, 6f)
    val angle = kotlin.math.atan2(vx, kotlin.math.max(vy, 0.0001f))
    return MotionVector(vy, angle, b.x / 1080) // normalized by screen width
}
