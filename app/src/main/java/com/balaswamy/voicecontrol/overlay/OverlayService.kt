
package com.balaswamy.voicecontrol.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.speech.tts.TextToSpeech

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
import com.balaswamy.voicecontrol.voice.CommandExecutionResult
import com.balaswamy.voicecontrol.voice.SpeechRecognitionManager
import com.balaswamy.voicecontrol.voice.UnconfiguredSpeakerVerifier
import com.balaswamy.voicecontrol.voice.VoiceCommandPipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import java.util.Locale

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val restartHandler = Handler(Looper.getMainLooper())

    private val restartListeningRunnable = Runnable {
        if (wantsListening && !isListening) {
            startContinuousListening()
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var listenButton: Button

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var textToSpeech: TextToSpeech? = null

    private var isListening = false
    private var wantsListening = true
    private var isDestroyed = false

    private lateinit var automationEngine: AutomationEngine
    private lateinit var historyRepository: AutomationHistoryRepository

    companion object {
        @Volatile
        var currentStatus: String = "Stopped"
            private set

        private val _assistantStatus =
            MutableStateFlow("Stopped")

        val assistantStatus = _assistantStatus.asStateFlow()

        private val _isRunning =
            MutableStateFlow(false)

        val isRunning = _isRunning.asStateFlow()

        private const val NOTIFICATION_CHANNEL_ID =
            "voice_control_assistant"

        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        if (!Settings.canDrawOverlays(this)) {
            updateStatus("ఫ్లోటింగ్ బటన్ అనుమతి అవసరం")
            stopSelf()
            return
        }

        historyRepository =
            AutomationHistoryRepository(applicationContext)

        automationEngine = AutomationEngine(
            accessState = {
                AutomationAccessState(
                    accessibilityPermissionGranted =
                        VoiceAccessibilityService.isServiceEnabled(this),
                    accessibilityServiceConnected =
                        VoiceAccessibilityService.instance != null
                )
            },
            securityControlVisible = {
                VoiceAccessibilityService.instance
                    ?.securityControlVisible() ?: false
            },
            performAction = ::performAccessibilityAction
        )

        createOverlay()

        textToSpeech = TextToSpeech(this, this)

        speechRecognitionManager = SpeechRecognitionManager(
            this,
            object : SpeechRecognitionManager.Listener {

                override fun onResult(text: String) {
                    isListening = false

                    if (isDestroyed || !wantsListening) return

                    val parsedText =
                        VoiceCommandParser.parse(text).safeDisplayText()

                    updateStatus("కమాండ్ గుర్తించబడింది")

                    serviceScope.launch {
                        try {
                            speakOut("గుర్తించిన కమాండ్: $parsedText")
                            pipeline().handleRecognizedCommand(text)
                        } catch (e: Exception) {
                            updateStatus("కమాండ్ అమలులో సమస్య")
                        } finally {
                            scheduleListeningRestart()
                        }
                    }
                }

                override fun onError(message: String) {
                    isListening = false

                    if (!isDestroyed && wantsListening) {
                        updateStatus("మైక్ మళ్లీ ప్రారంభమవుతుంది")
                        scheduleListeningRestart()
                    }
                }
            }
        )

        _isRunning.value = true
        updateStatus("Ready")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (overlayView == null ||
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAsForegroundService()
            wantsListening = true
            startContinuousListening()
        } catch (e: Exception) {
            updateStatus("సర్వీస్ ప్రారంభం కాలేదు")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(
                Locale.forLanguageTag("te-IN")
            )

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                updateStatus("తెలుగు వాయిస్ అందుబాటులో లేదు")
            }
        }
    }

    private fun speakOut(text: String) {
        textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "voice_control_utterance"
        )
    }

    private fun startContinuousListening() {
        if (isDestroyed || !wantsListening || isListening) {
            return
        }

        val manager = speechRecognitionManager ?: return

        try {
            isListening = true
            updateStatus("Listening...")

            manager.startListening()
        } catch (e: Exception) {
            isListening = false
            updateStatus("మైక్ ప్రారంభం కాలేదు")
            scheduleListeningRestart()
        }
    }

    private fun scheduleListeningRestart() {
        if (isDestroyed || !wantsListening) return

        restartHandler.removeCallbacks(
            restartListeningRunnable
        )

        restartHandler.postDelayed(
            restartListeningRunnable,
            1200L
        )
    }

    private fun createOverlay() {
        val density = resources.displayMetrics.density
        val buttonSize = (60 * density).toInt()
        val overlaySize = (72 * density).toInt()

        val circleBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1769FF.toInt())
            setStroke(
                (2 * density).toInt(),
                0xFFFFFFFF.toInt()
            )
        }

        val panel = FrameLayout(this).apply {
            background = circleBackground
            elevation = 10 * density
        }

        listenButton = Button(this).apply {
            text = "🎙"
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
            background = null
            isAllCaps = false
            contentDescription = "మైక్ ఆన్ లేదా ఆఫ్"

            setOnClickListener {
                if (wantsListening) {
                    wantsListening = false

                    restartHandler.removeCallbacks(
                        restartListeningRunnable
                    )

                    try {
                        speechRecognitionManager?.stopListening()
                    } catch (_: Exception) {
                    }

                    isListening = false
                    text = "🔇"
                    updateStatus("Paused")
                } else {
                    wantsListening = true
                    text = "🎙"
                    startContinuousListening()
                }
            }
        }

        val buttonParams = FrameLayout.LayoutParams(
            buttonSize,
            buttonSize
        ).apply {
            gravity = Gravity.CENTER
        }

        panel.addView(listenButton, buttonParams)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            overlaySize,
            overlaySize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }

        windowManager.addView(panel, params)

        overlayView = panel
        layoutParams = params

        enableDragging(panel)
    }

    private fun enableDragging(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handle.setOnTouchListener { _, event ->
            val params = layoutParams
                ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()

                    if (kotlin.math.abs(dx) > 5 ||
                        kotlin.math.abs(dy) > 5
                    ) {
                        moved = true
                    }

                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy

                        try {
                            overlayView?.let {
                                windowManager.updateViewLayout(
                                    it,
                                    params
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        handle.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        handle.isClickable = true
    }

    private fun startAsForegroundService() {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VoiceControl Assistant",
                NotificationManager.IMPORTANCE_LOW
            )

            notificationManager.createNotificationChannel(channel)
        }

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(
                    this,
                    NOTIFICATION_CHANNEL_ID
                )
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceControl Assistant")
            .setContentText("వాయిస్ నియంత్రణ సర్వీస్ నడుస్తోంది")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun pipeline() = VoiceCommandPipeline(
        speakerVerifier = UnconfiguredSpeakerVerifier(),
        executeAuthorized = ::executeAuthorizedCommand,
        onStateChanged = { _, _ -> }
    )

    private fun executeAuthorizedCommand(
        command: VerifiedVoiceCommand
    ): CommandExecutionResult {
        val result = when (val voiceCommand = command.command) {
            VoiceCommand.Confirm ->
                automationEngine.confirmPendingAction(
                    ownerAuthenticated = true
                )

            VoiceCommand.Cancel -> {
                if (automationEngine.cancelPendingAction()) {
                    AutomationResult.Success(
                        "ధృవీకరణ రద్దు చేయబడింది."
                    )
                } else {
                    AutomationResult.Failure(
                        "రద్దు చేయడానికి ఏమీ లేదు."
                    )
                }
            }

            is VoiceCommand.Click ->
                automationEngine.execute(
                    ValidatedAutomationAction.Click(
                        target = voiceCommand.target
                    ),
                    ownerAuthenticated = true
                )

            is VoiceCommand.Type ->
                automationEngine.execute(
                    ValidatedAutomationAction.Type(
                        voiceCommand.field.orEmpty(),
                        voiceCommand.text
                    ),
                    ownerAuthenticated = true
                )

            is VoiceCommand.Scroll ->
                automationEngine.execute(
                    when (voiceCommand.direction) {
                        VoiceCommand.Scroll.Direction.UP ->
                            ValidatedAutomationAction.ScrollUp

                        VoiceCommand.Scroll.Direction.DOWN ->
                            ValidatedAutomationAction.ScrollDown
                    },
                    ownerAuthenticated = true
                )

            is VoiceCommand.OpenApp ->
                automationEngine.execute(
                    ValidatedAutomationAction.OpenApp(
                        voiceCommand.name
                    ),
                    ownerAuthenticated = true
                )

            VoiceCommand.GenerateCode,
            VoiceCommand.AskAI,
            VoiceCommand.Unknown ->
                AutomationResult.Failure(
                    "ఈ కమాండ్‌కు ఈ సర్వీస్‌లో మద్దతు లేదు."
                )
        }

        when (result) {
            is AutomationResult.Success ->
                speakOut(result.message)

            is AutomationResult.Failure ->
                speakOut(result.message)

            else -> Unit
        }

        return when (result) {
            is AutomationResult.Success ->
                CommandExecutionResult(true, result.message)

            is AutomationResult.Failure ->
                CommandExecutionResult(false, result.message)

            is AutomationResult.RequiresConfirmation ->
                CommandExecutionResult(false, result.message)

            is AutomationResult.PermissionRequired ->
                CommandExecutionResult(false, result.message)

            is AutomationResult.AuthenticationRequired ->
                CommandExecutionResult(false, result.message)

            is AutomationResult.ManualInterventionRequired ->
                CommandExecutionResult(false, result.message)
        }
    }

    private fun performAccessibilityAction(
        action: ValidatedAutomationAction
    ): AutomationResult {
        val service = VoiceAccessibilityService.instance
            ?: return AutomationResult.PermissionRequired(
                "Accessibility Service కనెక్ట్ కాలేదు."
            )

        return when (val result = service.execute(action)) {
            is ActionResult.Success ->
                AutomationResult.Success(result.message)

            is ActionResult.Failure ->
                AutomationResult.Failure(result.message)
        }
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        _assistantStatus.value = status
    }

    override fun onDestroy() {
        isDestroyed = true
        wantsListening = false
        isListening = false

        restartHandler.removeCallbacksAndMessages(null)

        try {
            speechRecognitionManager?.stopListening()
        } catch (_: Exception) {
        }

        speechRecognitionManager?.destroy()
        speechRecognitionManager = null

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        serviceScope.cancel()

        try {
            overlayView?.let {
                windowManager.removeView(it)
            }
        } catch (_: Exception) {
        }

        overlayView = null
        layoutParams = null

        stopForeground(STOP_FOREGROUND_REMOVE)

        currentStatus = "Stopped"
        _assistantStatus.value = "Stopped"
        _isRunning.value = false

        super.onDestroy()
    }
}
com.balaswamy.voicecontrol.overlayandroid.app.Notificationandroid.app.NotificationChannelandroid.app.NotificationManagerandroid.app.Serviceandroid.content.Intentandroid.content.pm.ServiceInfoandroid.graphics.PixelFormatandroid.graphics.drawable.GradientDrawableandroid.os.Buildandroid.os.Handlerandroid.os.IBinderandroid.os.Looperandroid.provider.Settingsandroid.view.Gravityandroid.view.MotionEventandroid.view.Viewandroid.view.WindowManagerandroid.widget.Buttonandroid.speech.tts.TextToSpeechandroid.widget.FrameLayoutcom.balaswamy.voicecontrol.automation.AutomationEnginecom.balaswamy.voicecontrol.automation.AutomationAccessStatecom.balaswamy.voicecontrol.accessibility.ActionResultcom.balaswamy.voicecontrol.automation.AutomationResultcom.balaswamy.voicecontrol.commands.VerifiedVoiceCommandcom.balaswamy.voicecontrol.accessibility.VoiceAccessibilityServicecom.balaswamy.voicecontrol.commands.VoiceCommandParsercom.balaswamy.voicecontrol.commands.VoiceCommandcom.balaswamy.voicecontrol.data.gemini.ValidatedAutomationActioncom.balaswamy.voicecontrol.commands.safeDisplayTextcom.balaswamy.voicecontrol.voice.CommandExecutionResultcom.balaswamy.voicecontrol.data.history.AutomationHistoryRepositorycom.balaswamy.voicecontrol.voice.UnconfiguredSpeakerVerifiercom.balaswamy.voicecontrol.voice.SpeechRecognitionManagerkotlinx.coroutines.CoroutineScopecom.balaswamy.voicecontrol.voice.VoiceCommandPipelinekotlinx.coroutines.Dispatcherskotlinx.coroutines.SupervisorJobkotlinx.coroutines.cancelkotlinx.coroutines.flow.MutableStateFlowkotlinx.coroutines.flow.asStateFlowkotlinx.coroutines.launchjava.util.LocaleTextToSpeech.OnInitListenerWindowManager.LayoutParams?1001VoiceAccessibilityService.instanceSpeechRecognitionManager.ListenerserviceScope.launchresources.displayMetrics.densityGradientDrawable.OVAL10Gravity.CENTERPixelFormat.TRANSLUCENTGravity.TOPGravity.START0handle.setOnTouchListenerevent.rawXevent.rawY5handle.isClickableVoiceCommand.ConfirmVoiceCommand.CancelVoiceCommand.ClickvoiceCommand.targetVoiceCommand.TypevoiceCommand.textVoiceCommand.ScrollVoiceCommand.Scroll.Direction.UPValidatedAutomationAction.ScrollUpVoiceCommand.Scroll.Direction.DOWNValidatedAutomationAction.ScrollDownVoiceCommand.OpenAppvoiceCommand.nameAutomationResult.SuccessVoiceCommand.UnknownAutomationResult.RequiresConfirmationAutomationResult.FailureAutomationResult.AuthenticationRequiredAutomationResult.PermissionRequiredAutomationResult.ManualInterventionRequiredActionResult.SuccessActionResult.Failure
