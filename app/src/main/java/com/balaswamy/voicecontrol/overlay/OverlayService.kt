package com.balaswamy.voicecontrol.overlay

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.content.pm.ServiceInfo
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.balaswamy.voicecontrol.automation.AutomationAccessState
import com.balaswamy.voicecontrol.automation.AutomationEngine
import com.balaswamy.voicecontrol.automation.AutomationResult
import com.balaswamy.voicecontrol.accessibility.ActionResult
import com.balaswamy.voicecontrol.accessibility.VoiceAccessibilityService
import com.balaswamy.voicecontrol.commands.VerifiedVoiceCommand
import com.balaswamy.voicecontrol.commands.VoiceCommand
import com.balaswamy.voicecontrol.commands.VoiceCommandParser
import com.balaswamy.voicecontrol.commands.safeDisplayText
import com.balaswamy.voicecontrol.data.gemini.ValidatedAutomationAction
import com.balaswamy.voicecontrol.data.history.AutomationHistoryRepository
import com.balaswamy.voicecontrol.voice.AssistantState
import com.balaswamy.voicecontrol.voice.CommandExecutionResult
import com.balaswamy.voicecontrol.voice.SpeechRecognitionManager
import com.balaswamy.voicecontrol.voice.UnconfiguredSpeakerVerifier
import com.balaswamy.voicecontrol.voice.VoiceCommandPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var statusView: TextView
    private lateinit var commandView: TextView
    private lateinit var listenButton: Button
    private var overlayView: View? = null
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var isListening = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private lateinit var automationEngine: AutomationEngine
    private lateinit var historyRepository: AutomationHistoryRepository

    companion object {
        @Volatile
        var currentStatus: String = "Stopped"
            private set
        private val _assistantStatus = MutableStateFlow("Stopped")
        val assistantStatus = _assistantStatus.asStateFlow()
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private const val NOTIFICATION_CHANNEL_ID = "voice_control_assistant"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        historyRepository = AutomationHistoryRepository(applicationContext)
        automationEngine = AutomationEngine(
            accessState = {
                AutomationAccessState(
                    accessibilityPermissionGranted = VoiceAccessibilityService.isServiceEnabled(this),
                    accessibilityServiceConnected = VoiceAccessibilityService.instance != null,
                )
            },
            securityControlVisible = {
                VoiceAccessibilityService.instance?.securityControlVisible() ?: false
            },
            performAction = ::performAccessibilityAction,
        )
        createOverlay()
        _isRunning.value = true
        speechRecognitionManager = SpeechRecognitionManager(this, object : SpeechRecognitionManager.Listener {
            override fun onResult(text: String) {
                isListening = false
                listenButton.text = "Listen"
                commandView.text = "Command: ${VoiceCommandParser.parse(text).safeDisplayText()}"
                updateStatus("Verifying owner...")
                serviceScope.launch {
                    pipeline().handleRecognizedCommand(text)
                }
            }

            override fun onError(@Suppress("UNUSED_PARAMETER") message: String) {
                isListening = false
                listenButton.text = "Listen"
                updateStatus("Listening unavailable")
            }
        })
        updateStatus("Ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startAsForegroundService()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechRecognitionManager?.destroy()
        speechRecognitionManager = null
        serviceScope.cancel()
        overlayView?.let(windowManager::removeView)
        overlayView = null
        currentStatus = "Stopped"
        _assistantStatus.value = "Stopped"
        _isRunning.value = false
        super.onDestroy()
    }

    private fun createOverlay() {
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            setBackgroundColor(0xEE202124.toInt())
        }

        val header = TextView(this).apply {
            text = "VoiceControl Assistant"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        statusView = TextView(this).apply {
            text = "● Ready"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        }
        commandView = TextView(this).apply {
            text = "Command: —"
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        listenButton = Button(this).apply {
            text = "Listen"
            setOnClickListener {
                if (isListening) {
                    speechRecognitionManager?.stopListening()
                    isListening = false
                    text = "Listen"
                    updateStatus("Ready")
                } else {
                    isListening = true
                    text = "Stop"
                    updateStatus("Listening...")
                    speechRecognitionManager?.startListening()
                }
            }
        }
        val closeButton = Button(this).apply {
            text = "Close Assistant"
            setOnClickListener { stopSelf() }
        }
        panel.addView(header)
        panel.addView(statusView)
        panel.addView(commandView)
        panel.addView(listenButton)
        panel.addView(closeButton)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            (280 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }
        windowManager.addView(panel, layoutParams)
        overlayView = panel
        this.layoutParams = layoutParams
        enableDragging(header)
    }

    private fun enableDragging(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            val layout = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - touchX).toInt()
                    layout.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layout)
                    true
                }
                else -> false
            }
        }
    }

    private fun startAsForegroundService() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "VoiceControl Assistant",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceControl Assistant")
            .setContentText("Floating assistant is available.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun pipeline() = VoiceCommandPipeline(
        speakerVerifier = UnconfiguredSpeakerVerifier(),
        executeAuthorized = ::executeAuthorizedCommand,
        onStateChanged = { state, message ->
            val label = when (state) {
                AssistantState.IDLE -> "Ready"
                AssistantState.LISTENING -> "Listening"
                AssistantState.VERIFYING -> "Verifying owner..."
                AssistantState.AUTHORIZED -> "Authorized"
                AssistantState.EXECUTING -> "Executing..."
                AssistantState.ERROR -> "Error"
            }
            val visibleStatus = when {
                state == AssistantState.IDLE && message.startsWith("Command completed") -> "Success"
                message.contains("Manual verification required", ignoreCase = true) ->
                    "Manual verification required"
                message.contains("confirmation required", ignoreCase = true) ->
                    "Confirmation required"
                message.contains("permission", ignoreCase = true) ->
                    "Permission required"
                message.contains("not configured", ignoreCase = true) ->
                    "Owner voice verification not configured"
                message.contains("not executed", ignoreCase = true) -> "Not executed"
                state == AssistantState.IDLE -> "Ready"
                state == AssistantState.ERROR && message.startsWith("Unrecognized") -> "Command not recognized"
                state == AssistantState.ERROR -> "Not completed"
                else -> label
            }
            updateStatus(visibleStatus)
        },
    )

    private fun executeAuthorizedCommand(command: VerifiedVoiceCommand): CommandExecutionResult {
        val result = when (val voiceCommand = command.command) {
            VoiceCommand.Confirm -> automationEngine.confirmPendingAction(ownerAuthenticated = true)
            VoiceCommand.Cancel -> {
                if (automationEngine.cancelPendingAction()) {
                    AutomationResult.Success("Pending action cancelled.")
                } else {
                    AutomationResult.Failure("There is no pending action to cancel.")
                }
            }
            is VoiceCommand.Click -> automationEngine.execute(
                ValidatedAutomationAction.Click(target = voiceCommand.target),
                ownerAuthenticated = true,
            )
            is VoiceCommand.Type -> automationEngine.execute(
                ValidatedAutomationAction.Type(voiceCommand.field.orEmpty(), voiceCommand.text),
                ownerAuthenticated = true,
            )
            is VoiceCommand.Scroll -> automationEngine.execute(
                when (voiceCommand.direction) {
                    VoiceCommand.Scroll.Direction.UP -> ValidatedAutomationAction.ScrollUp
                    VoiceCommand.Scroll.Direction.DOWN -> ValidatedAutomationAction.ScrollDown
                },
                ownerAuthenticated = true,
            )
            is VoiceCommand.OpenApp -> automationEngine.execute(
                ValidatedAutomationAction.OpenApp(voiceCommand.name),
                ownerAuthenticated = true,
            )
            is VoiceCommand.GenerateCode,
            is VoiceCommand.AskAI,
            is VoiceCommand.Unknown,
            -> AutomationResult.Failure("This command is not an accessibility action.")
        }
        try {
            historyRepository.record(command.command, result)
        } catch (exception: IllegalStateException) {
            updateStatus("History unavailable")
        } catch (exception: SecurityException) {
            updateStatus("History unavailable")
        }
        return when (result) {
            is AutomationResult.Success -> CommandExecutionResult(true, "Command completed successfully.")
            is AutomationResult.Failure -> CommandExecutionResult(false, result.message)
            is AutomationResult.RequiresConfirmation -> CommandExecutionResult(false, result.message)
            is AutomationResult.PermissionRequired -> CommandExecutionResult(false, result.message)
            is AutomationResult.AuthenticationRequired -> CommandExecutionResult(false, result.message)
            is AutomationResult.ManualInterventionRequired -> CommandExecutionResult(false, result.message)
        }
    }
    private fun performAccessibilityAction(action: ValidatedAutomationAction): AutomationResult {
        val service = VoiceAccessibilityService.instance
            ?: return AutomationResult.PermissionRequired("Accessibility Service is not connected.")
        return when (val result = service.execute(action)) {
            is ActionResult.Success -> AutomationResult.Success(result.message)
            is ActionResult.Failure -> {
                if (result.message == "Manual verification required.") {
                    AutomationResult.ManualInterventionRequired(result.message)
                } else {
                    AutomationResult.Failure(result.message)
                }
            }
        }
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        _assistantStatus.value = status
        if (::statusView.isInitialized) {
            statusView.text = when (status) {
                "Ready", "Success" -> "● $status"
                "Listening..." -> "● Listening..."
                "Verifying owner..." -> "● Verifying owner..."
                "Executing..." -> "● Executing..."
                else -> "● $status"
            }
        }
    }

}
