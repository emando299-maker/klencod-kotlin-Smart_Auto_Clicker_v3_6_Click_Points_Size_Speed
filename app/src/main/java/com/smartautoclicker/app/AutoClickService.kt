package com.smartautoclicker.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class AutoClickService : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_FLOATING_WINDOW = "com.smartautoclicker.app.SHOW_FLOATING_WINDOW"
    }
    private val showWindowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHOW_FLOATING_WINDOW) {
                prefs.edit().putBoolean("floating_hidden", false).apply()
                try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                collapsedView = null
                try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                overlayView = null
                showFloatingWindow()
                refreshPointMarkers()
            }
        }
    }
    private var receiverRegistered = false
    private var lastClickTime = 0L
    @Volatile private var searchInProgress = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var overlayView: View? = null
    private var speedLabel: TextView? = null
    private var collapsedView: View? = null
    private var statusLabel: TextView? = null
    private val pointMarkerViews = mutableListOf<View>()
    private var windowManager: WindowManager? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val clickDelay: Long
        get() = prefs.getInt("click_delay_ms", 1000).toLong().coerceIn(1L, 5000L)

    // Keep searching even when the target app/game emits no accessibility events.
    private val retryScan = Runnable {
        if (prefs.getBoolean("running", false) && !searchInProgress) {
            onAccessibilityEvent(null)
        }
    }

    private fun scheduleRetry(delayMs: Long = 650L) {
        mainHandler.removeCallbacks(retryScan)
        if (prefs.getBoolean("running", false)) {
            mainHandler.postDelayed(retryScan, delayMs.coerceAtLeast(250L))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_SHOW_FLOATING_WINDOW)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(showWindowReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(showWindowReceiver, filter)
        }
        receiverRegistered = true
        if (!prefs.getBoolean("floating_hidden", false)) showFloatingWindow()
        refreshPointMarkers()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefs.getBoolean("running", false)) {
            mainHandler.removeCallbacks(retryScan)
            updateStatus("متوقف")
            return
        }
        if (searchInProgress) return
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickDelay) {
            scheduleRetry(clickDelay - (now - lastClickTime))
            return
        }

        if (prefs.getBoolean("point_mode", false)) {
            clickNextConfiguredPoint()
            return
        }

        val currentSlot = currentEnabledSlot() ?: run {
            updateStatus("فعّل خانة بحث واحدة على الأقل")
            return
        }
        val word = prefs.getString("slot_word_${currentSlot + 1}", "")?.trim().orEmpty()
        if (word.isBlank()) {
            updateStatus("اكتب كلمة للخانة ${currentSlot + 1}")
            return
        }

        val root = rootInActiveWindow
        if (root != null && word.isNotBlank() && searchAndClick(root, word)) {
            lastClickTime = now
            advanceSequence(currentSlot)
            scheduleRetry(clickDelay)
            return
        }

        // Accessibility text does not expose words drawn inside images/canvas.
        // On Android 11+, take an accessibility screenshot and OCR it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            searchInProgress = true
            updateStatus("يفحص الشاشة عن الكلمة ${currentSlot + 1}: $word")
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executor { command -> mainHandler.post(command) },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap?.recycle()
                            hardwareBuffer.close()
                            screenshotInProgress(bitmap, word, currentSlot)
                        }
                        override fun onFailure(errorCode: Int) {
                            searchInProgress = false
                            updateStatus("تعذّر فحص الشاشة (رمز $errorCode) • سيعيد المحاولة")
                            scheduleRetry()
                        }
                    })
            } catch (_: Exception) {
                searchInProgress = false
                updateStatus("تعذّر فحص الشاشة • سيعيد المحاولة")
                scheduleRetry()
            }
        } else {
            updateStatus("في انتظار الكلمة ${currentSlot + 1}: $word")
            scheduleRetry(1500L)
        }
    }

    private fun clickNextConfiguredPoint() {
        val start = prefs.getInt("point_sequence_index", 0).coerceIn(0, 4)
        var chosen = -1
        for (offset in 0 until 5) {
            val i = (start + offset) % 5
            if (prefs.getBoolean("point_enabled_${i + 1}", false)) { chosen = i; break }
        }
        if (chosen < 0) {
            updateStatus("لا توجد نقاط نقر مفعّلة")
            prefs.edit().putBoolean("running", false).apply()
            return
        }
        val x = prefs.getInt("point_x_${chosen + 1}", 0)
        val y = prefs.getInt("point_y_${chosen + 1}", 0)
        val displaySize = android.graphics.Point()
        try {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(displaySize)
        } catch (_: Exception) {}
        val w = if (displaySize.x > 0) displaySize.x else resources.displayMetrics.widthPixels
        val h = if (displaySize.y > 0) displaySize.y else resources.displayMetrics.heightPixels
        if (x !in 0 until w || y !in 0 until h) {
            updateStatus("إحداثيات النقطة ${chosen + 1} خارج الشاشة")
            prefs.edit().putBoolean("running", false).apply()
            return
        }
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val next = (chosen + 1) % 5
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                lastClickTime = System.currentTimeMillis()
                prefs.edit().putInt("point_sequence_index", next).apply()
                updateStatus("تم إرسال النقرة للنقطة ${chosen + 1} عند ($x, $y)")
                scheduleRetry(clickDelay)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                updateStatus("أُلغيت نقرة النقطة ${chosen + 1}")
                scheduleRetry()
            }
        }, mainHandler)
        if (!sent) {
            updateStatus("تعذّر إرسال نقرة النقطة ${chosen + 1}")
            scheduleRetry()
        }
    }

    private fun refreshPointMarkers() {
        val wm = windowManager ?: run {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager
        } ?: return
        pointMarkerViews.toList().forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        pointMarkerViews.clear()
        if (!prefs.getBoolean("point_mode", false)) return
        val sizeDp = prefs.getInt("point_marker_size_dp", 24).coerceIn(12, 56)
        val colors = intArrayOf(Color.rgb(255, 70, 80), Color.rgb(0, 200, 130), Color.rgb(255, 195, 0), Color.rgb(150, 75, 255), Color.rgb(35, 165, 255))
        for (i in 0 until 5) {
            if (!prefs.getBoolean("point_enabled_${i + 1}", false)) continue
            val x = prefs.getInt("point_x_${i + 1}", 0)
            val y = prefs.getInt("point_y_${i + 1}", 0)
            val marker = TextView(this).apply {
                text = "${i + 1}"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors[i]); setStroke(dp(2), Color.WHITE) }
                elevation = dp(8).toFloat()
            }
            val params = WindowManager.LayoutParams(dp(sizeDp), dp(sizeDp), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; this.x = x - dp(sizeDp) / 2; this.y = y - dp(sizeDp) / 2 }
            try { wm.addView(marker, params); pointMarkerViews.add(marker) } catch (_: Exception) {}
        }
    }

    private fun screenshotInProgress(bitmap: Bitmap?, word: String, currentSlot: Int) {
        if (bitmap == null) {
            searchInProgress = false
            updateStatus("تعذّر قراءة صورة الشاشة • سيعيد المحاولة")
            scheduleRetry()
            return
        }
        runOcr(bitmap, word, currentSlot)
    }

    private fun runOcr(bitmap: Bitmap, word: String, currentSlot: Int) {
        if (word.isBlank()) {
            updateStatus("اكتب كلمة للخانة ${currentSlot + 1}")
            searchInProgress = false
            bitmap.recycle()
            return
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                val match = result.textBlocks.asSequence()
                    .flatMap { it.lines.asSequence() }
                    .firstOrNull { targetMatches(it.text, word) && it.boundingBox != null }
                if (match == null) {
                    updateStatus("لم يجد الكلمة ${currentSlot + 1}: $word • يعيد الفحص تلقائيًا")
                    searchInProgress = false
                    bitmap.recycle()
                    scheduleRetry()
                    return@addOnSuccessListener
                }
                val bounds = Rect(match.boundingBox)
                clickAt(bounds.exactCenterX(), bounds.exactCenterY(), currentSlot, bitmap)
            }
            .addOnFailureListener {
                updateStatus("فشل التعرف البصري على النص • سيعيد المحاولة")
                searchInProgress = false
                bitmap.recycle()
                scheduleRetry()
            }
    }

    private fun clickAt(x: Float, y: Float, currentSlot: Int, bitmap: Bitmap) {
        updateStatus("تم العثور على الهدف ${currentSlot + 1} • إحداثيات الصورة: ${x.toInt()},${y.toInt()} • يرسل النقرة…")
        // Screenshot buffer dimensions are not guaranteed to equal the logical
        // display coordinate space used by AccessibilityService.dispatchGesture.
        // Convert the matched screenshot point to display pixels before dispatching.
        val displaySize = android.graphics.Point()
        try {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(displaySize)
        } catch (_: Exception) { }
        val displayW = if (displaySize.x > 0) displaySize.x else resources.displayMetrics.widthPixels
        val displayH = if (displaySize.y > 0) displaySize.y else resources.displayMetrics.heightPixels
        val mappedX = x * displayW.toFloat() / bitmap.width.toFloat()
        val mappedY = y * displayH.toFloat() / bitmap.height.toFloat()
        val safeX = mappedX.coerceIn(1f, (displayW - 2).coerceAtLeast(1).toFloat())
        val safeY = mappedY.coerceIn(1f, (displayH - 2).coerceAtLeast(1).toFloat())
        val path = android.graphics.Path().apply { moveTo(safeX, safeY) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 160))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                lastClickTime = System.currentTimeMillis()
                advanceSequence(currentSlot)
                searchInProgress = false
                bitmap.recycle()
                scheduleRetry(clickDelay)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                updateStatus("تم العثور على الهدف لكن تعذّر النقر • سيعيد المحاولة")
                searchInProgress = false
                bitmap.recycle()
                scheduleRetry()
            }
        }, mainHandler)
        if (!dispatched) {
            updateStatus("تم العثور على الهدف لكن تعذّر إرسال النقر • سيعيد المحاولة")
            searchInProgress = false
            bitmap.recycle()
            scheduleRetry()
        }
    }

    private fun advanceSequence(currentSlot: Int) {
        val nextSlot = nextEnabledSlot(currentSlot)
        prefs.edit().putInt("sequence_index", nextSlot).apply()
        updateStatus("اكتمل إرسال الإيماءة للخانة ${currentSlot + 1} • التالية ${nextSlot + 1}")
    }

    private fun currentEnabledSlot(): Int? {
        val start = prefs.getInt("sequence_index", 0).coerceIn(0, 4)
        for (offset in 0 until 5) {
            val slot = (start + offset) % 5
            val enabled = prefs.getBoolean("slot_enabled_${slot + 1}", slot == 0)
            val word = prefs.getString("slot_word_${slot + 1}", if (slot == 0) prefs.getString("word", "") else "")?.trim().orEmpty()
            if (enabled && word.isNotBlank()) {
                if (slot != start) prefs.edit().putInt("sequence_index", slot).apply()
                return slot
            }
        }
        return null
    }

    private fun nextEnabledSlot(current: Int): Int {
        for (offset in 1..5) {
            val slot = (current + offset) % 5
            val enabled = prefs.getBoolean("slot_enabled_${slot + 1}", slot == 0)
            val word = prefs.getString("slot_word_${slot + 1}", if (slot == 0) prefs.getString("word", "") else "")?.trim().orEmpty()
            if (enabled && word.isNotBlank()) return slot
        }
        return current
    }

    /**
     * Lightweight on-device intent matching. This is not a generative AI model:
     * it expands common Arabic/English action goals into practical button labels,
     * without a network connection or API key. Exact text matching always wins.
     */
    private fun targetMatches(visibleText: String, goal: String): Boolean {
        if (visibleText.isBlank() || goal.isBlank()) return false
        if (visibleText.contains(goal, ignoreCase = true)) return true
        if (!prefs.getBoolean("smart_mode", true)) return false

        fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[\u064B-\u065F\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        val g = normalize(goal)
        val t = normalize(visibleText)
        if (g.isBlank() || t.isBlank()) return false
        if (t.contains(g)) return true

        val groups = listOf(
            listOf("ابدأ", "ابدا", "بدء", "start", "play", "العب", "تشغيل", "ابدأ اللعب"),
            listOf("متابعة", "استمرار", "التالي", "next", "continue", "تابع", "موافق", "ok"),
            listOf("رجوع", "عودة", "back", "عودة للقائمة", "القائمة"),
            listOf("إغلاق", "اغلاق", "close", "خروج", "exit", "انهاء", "إلغاء", "الغاء", "cancel"),
            listOf("إعادة", "اعادة", "retry", "restart", "حاول مرة أخرى", "حاول مره اخري"),
            listOf("شراء", "buy", "شراء الآن", "اشتر", "تسوق", "shop"),
            listOf("بحث", "search", "ابحث", "بحث عن"),
            listOf("جمع", "claim", "استلام", "مكافأة", "مكافاه", "reward", "الحصول على")
        )
        for (group in groups) {
            val normalizedGroup = group.map(::normalize)
            if (normalizedGroup.any { g.contains(it) } && normalizedGroup.any { t.contains(it) }) return true
        }
        // For longer natural-language instructions, allow the key action word to
        // match, but avoid treating generic words such as "زر" or "اضغط" as targets.
        val stopWords = setOf("اضغط", "على", "زر", "الزر", "من", "في", "ثم", "بعد", "الشاشة", "اريد", "عايز", "لو", "كلمة")
        val goalWords = g.split(Regex("\\s+")).filter { it.length >= 3 && it !in stopWords }
        return goalWords.any { t.contains(it) }
    }

    private fun searchAndClick(node: AccessibilityNodeInfo, word: String): Boolean {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (targetMatches(text, word) || targetMatches(description, word)) {
            // Try the matching node and each clickable ancestor. Do not stop on the
            // first clickable ancestor if its ACTION_CLICK returns false.
            var target: AccessibilityNodeInfo? = node
            var depth = 0
            while (target != null && depth < 10) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                target = target.parent
                depth++
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = searchAndClick(child, word)
            child.recycle()
            if (clicked) return true
        }
        return false
    }

    private fun showFloatingWindow() {
        if (overlayView != null || collapsedView != null || prefs.getBoolean("floating_hidden", false)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        refreshPointMarkers()

        val params = WindowManager.LayoutParams(
            dp(178), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(10); y = dp(100) }

        fun roundedBackground(color: Int, radiusDp: Int = 18): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
            }

        fun makeCollapsedBubble() {
            if (collapsedView != null) return
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null
            val bubble = TextView(this).apply {
                text = "▶"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(35, 125, 220))
                    shape = GradientDrawable.OVAL
                }
                contentDescription = "إظهار لوحة النقر"
                setOnClickListener {
                    try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                    collapsedView = null
                    showFloatingWindow()
                }
            }
            val bubbleParams = WindowManager.LayoutParams(
                dp(48), dp(48),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = params.gravity; x = params.x; y = params.y }
            bubble.setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var startX = 0
                private var startY = 0
                private var moved = false
                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY
                            startX = bubbleParams.x; startY = bubbleParams.y; moved = false
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downX).toInt()
                            val dy = (event.rawY - downY).toInt()
                            if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) moved = true
                            bubbleParams.x = startX - dx
                            bubbleParams.y = startY + dy
                            try { windowManager?.updateViewLayout(bubble, bubbleParams) } catch (_: Exception) {}
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (!moved) v.performClick()
                            return true
                        }
                    }
                    return false
                }
            })
            try {
                windowManager?.addView(bubble, bubbleParams)
                collapsedView = bubble
            } catch (_: Exception) { collapsedView = null }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(Color.rgb(28, 35, 45), 18)
        }
        val headingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "النقر التلقائي"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        headingRow.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(TextView(this).apply {
            text = "−"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "إخفاء النافذة"
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { makeCollapsedBubble() }
        })
        headingRow.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 115, 125))
            contentDescription = "إغلاق النافذة العائمة نهائيًا"
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener {
                prefs.edit().putBoolean("floating_hidden", true).apply()
                try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                overlayView = null
                collapsedView = null
                Toast.makeText(this@AutoClickService, "تم إغلاق النافذة العائمة. يمكنك إظهارها من التطبيق.", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(headingRow)

        statusLabel = TextView(this).apply {
            text = if (prefs.getBoolean("running", false)) "يعمل" else "متوقف"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 11f
        }
        panel.addView(statusLabel)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(Button(this).apply {
            text = "تشغيل"; isAllCaps = false; textSize = 11f
            setPadding(dp(2), 0, dp(2), 0)
            minHeight = dp(34)
            setOnClickListener {
                if (!prefs.getBoolean("point_mode", false) && currentEnabledSlot() == null) {
                    Toast.makeText(this@AutoClickService, "افتح التطبيق واحفظ خانة بحث مفعّلة على الأقل", Toast.LENGTH_SHORT).show()
                    updateStatus("لا توجد خانات بحث صالحة")
                } else if (prefs.getBoolean("point_mode", false) && (0 until 5).none { prefs.getBoolean("point_enabled_${it + 1}", false) }) {
                    Toast.makeText(this@AutoClickService, "افتح التطبيق وأضف نقطة نقر واحدة على الأقل", Toast.LENGTH_SHORT).show()
                    updateStatus("لا توجد نقاط نقر")
                } else {
                    prefs.edit().putBoolean("running", true).apply()
                    updateStatus("بدأ الفحص التلقائي")
                    mainHandler.removeCallbacks(retryScan)
                    mainHandler.post(retryScan)
                }
            }
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        controls.addView(Button(this).apply {
            text = "إيقاف"; isAllCaps = false; textSize = 11f
            setPadding(dp(2), 0, dp(2), 0)
            minHeight = dp(34)
            setOnClickListener {
                prefs.edit().putBoolean("running", false).apply()
                mainHandler.removeCallbacks(retryScan)
                updateStatus("متوقف")
            }
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        panel.addView(controls)
        panel.addView(Button(this).apply {
            text = "فحص النصوص"; isAllCaps = false; textSize = 11f
            setPadding(dp(2), 0, dp(2), 0); minHeight = dp(38)
            setOnClickListener { updateStatus("يبحث عن كلمات الخانات المحفوظة") ; mainHandler.removeCallbacks(retryScan); mainHandler.post(retryScan) }
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(5) })

        val delay = clickDelay.toInt()
        speedLabel = TextView(this).apply {
            text = "الفاصل: ${delay} ms"
            setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        panel.addView(speedLabel)
        panel.addView(SeekBar(this).apply {
            max = 4999
            progress = delay - 1
            contentDescription = "الفاصل بين النقرات من 1 إلى 5000 ملي ثانية"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = progress + 1
                    prefs.edit().putInt("click_delay_ms", ms).apply()
                    speedLabel?.text = "الفاصل: ${ms} ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        panel.addView(TextView(this).apply {
            text = "اسحب العنوان للتحريك • − للتصغير • × للإغلاق"
            setTextColor(Color.LTGRAY); textSize = 9f; gravity = Gravity.CENTER
        })

        heading.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = startX - (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try { windowManager?.updateViewLayout(panel, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
        try {
            windowManager?.addView(panel, params)
            overlayView = panel
        } catch (_: Exception) { overlayView = null }
    }


    private fun updateStatus(message: String) { statusLabel?.text = message }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() {
        prefs.edit().putBoolean("running", false).apply()
        mainHandler.removeCallbacks(retryScan)
        updateStatus("متوقف")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(retryScan)
        if (receiverRegistered) {
            try { unregisterReceiver(showWindowReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        pointMarkerViews.toList().forEach { try { windowManager?.removeView(it) } catch (_: Exception) {} }; pointMarkerViews.clear()
        overlayView = null
        collapsedView = null
        super.onDestroy()
    }
}
