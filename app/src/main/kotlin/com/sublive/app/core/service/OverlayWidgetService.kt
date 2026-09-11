package com.sublive.app.core.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sublive.app.ui.theme.*

class OverlayWidgetService : LifecycleService() {

    companion object {
        val isWidgetActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private var params: WindowManager.LayoutParams? = null
    
    private val savedStateRegistryOwner by lazy { ServiceSavedStateRegistryOwner(this) }
    private val viewModelStoreOwner by lazy { ServiceViewModelStoreOwner() }

    override fun onCreate() {
        super.onCreate()
        isWidgetActive.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        params = WindowManager.LayoutParams(
            (screenWidth * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 120
        }

        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SubLiveTheme {
                    SubtitleOverlayContent(
                        onDrag = { dx, dy ->
                            params?.x = (params?.x ?: 0) + dx.toInt()
                            params?.y = (params?.y ?: 0) - dy.toInt() // Gravity is BOTTOM, so -dy moves upward
                            windowManager.updateViewLayout(composeView, params)
                        },
                        onClose = { stopSelf() },
                        onToggle = { isCurrentlyRunning ->
                            if (isCurrentlyRunning) {
                                val intent = Intent(this@OverlayWidgetService, AudioDubbingForegroundService::class.java).apply {
                                    action = AudioDubbingForegroundService.ACTION_STOP
                                }
                                startService(intent)
                            } else {
                                val intent = Intent(this@OverlayWidgetService, com.sublive.app.TransparentCaptureActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                            }
                        }
                    )
                }
            }
        }
        
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

        windowManager.addView(composeView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        isWidgetActive.value = false
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
    }
}

@Composable
fun SubtitleOverlayContent(
    onDrag: (Float, Float) -> Unit,
    onClose: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val isRunning by AudioDubbingForegroundService.isRunning.collectAsState()
    val subtitleText by AudioDubbingForegroundService.liveSubtitleText.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Compact top bar: drag handle + buttons on same row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag bar indicator
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play / Stop Mini Button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onToggle(isRunning)
                    },
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) NeonRose.copy(alpha = 0.25f) else NeonCyan.copy(alpha = 0.25f))
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Toggle",
                        tint = if (isRunning) NeonRose else NeonCyan,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Close Button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onClose()
                    },
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // Subtitle Text — single line, no extra spacing
        val displayText = when {
            subtitleText.isNotEmpty() -> subtitleText
            isRunning -> "در حال دریافت..."
            else -> "آماده"
        }

        Text(
            text = displayText,
            color = if (subtitleText.isNotEmpty()) Color(0xFFFFF275) else TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp)
        )
    }
}
