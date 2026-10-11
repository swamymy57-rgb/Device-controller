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
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import java.util.Locale
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

class OverlayService : Service(), TextToSpeech.OnInitListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var listenButton: Button
    private var overlayView: View? = null
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var textToSpeech: TextToSpeech? = null
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
        
        // Text-To-Speech ఇనిషియలైజేషన్ (స్పీకర్ కోసం)
        textToSpeech = TextToSpeech(this, this)

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
                val parsedText = VoiceCommandParser.parse(text).safeDisplayText()
                speakOut("స్క్రీన్ పై ఉన్న సమాచారం: $parsedText")
                
                serviceScope.launch {
                    pipeline().handleRecognizedCommand(text)
                }
                
                // మైక్ ఎప్పుడూ ఆన్‌లో ఉండేలా (Continuous Listening) మళ్లీ స్టార్ట్ చేయడం
                startContinuousListening()
            }

            override fun onError(message: String) {
                // ఎర్రర్ వచ్చినప్పటికీ మైక్ కంటిన్యూగా వినేలా రీస్టార్ట్ చేయడం
                startContinuousListening()
            }
        })
        
        // సర్వీస్ స్టార్ట్ అవ్వగానే ఆటోమేటిక్‌గా మైక్ ఆన్ అవుతుంది
        startContinuousListening()
        updateStatus("Ready")
    }

    private fun startContinuousListening() {
        if (!isListening) {
            isListening = true
            speechRecognitionManager?.startListening()
            updateStatus("Listening...")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale("te", "IN") // తెలుగు సపోర్ట్ కోసం
        }
    }

    private fun speakOut(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
        textToSpeech?.stop()
        textToSpeech?.shutdown()
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
        
        // రౌండ్ షేప్ సర్కిల్ బ్యాక్‌గ్రౌండ్ (Floating Icon)
        val circleDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVA
            setColor(0xEE202124.toInt())
        }

        // చిన్న రౌండ్ ఫ్లోటింగ్ ఐకాన్ లేఅవుట్
        val panel = FrameLayout(this).apply {
            background = circleDrawable
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        listenButton = Button(this).apply {
            text = "🎙️"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            background = null // డిఫాల్ట్ బటన్ బాక్స్ పోయి రౌండ్‌గా ఉండటానికి
            setOnClickListener {
                if (isListening) {
                    speechRecognitionManager?.stopListening()
                    isListening = false
                    updateStatus("Paused")
                } else {
                    startContinuousListening()
                }
            }
        }

        val params = FrameLayout.LayoutParams(
            (55 * density).toInt(),
            (55 * density).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }

        panel.addView(listenButton, params)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val layoutParams = WindowManager.LayoutParams(
            (65 * density).toInt(),
            (65 * density).toInt(),
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
        enableDragging(panel)
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
            .setContentText("Continuous listening active.")
            .setOngoing(true)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun pipeline() = VoiceCommandPipeline(
        speakerVerifier = UnconfiguredSpeakerVerifier(),
        executeAuthorized = ::executeAuthorizedCommand,
        onStateChanged = { _, _ -> },
    )

    private fun executeAuthorizedCommand(command: VerifiedVoiceCommand): CommandExecutionResult {
        val result = when (val voiceCommand = command.command) {
            VoiceCommand.Confirm -> automationEngine.confirmPendingAction(ownerAuthenticated = true)
            VoiceCommand.Cancel -> {
                if (automationEngine.cancelPendingAction()) {
                    AutomationResult.Success("ధృవీకరణ రద్దు చేయబడింది.")
                } else {
                    AutomationResult.Failure("రద్దు చేయడానికి ఏమీ లేదు.")
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
            -> AutomationResult.Failure("ఈ కమాండ్ యాక్సెసిబిలిటీకి సంబంధించినది కాదు.")
        }
        
        // ఫలితాన్ని స్పీకర్ ద్వారా వినిపించడం
        when (result) {
            is AutomationResult.Success -> speakOut(result.message)
            is AutomationResult.Failure -> speakOut(result.message)
            else -> {}
        }

        return when (result) {
            is AutomationResult.Success -> CommandExecutionResult(true, result.message)
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
            is ActionResult.Failure -> AutomationResult.Failure(result.message)
        }
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        _assistantStatus.value = status
    }
}
