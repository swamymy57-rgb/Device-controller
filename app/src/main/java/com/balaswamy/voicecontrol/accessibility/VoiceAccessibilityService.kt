package com.balaswamy.voicecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.balaswamy.voicecontrol.data.gemini.AutomationInputSafety
import com.balaswamy.voicecontrol.data.gemini.ScreenBounds
import com.balaswamy.voicecontrol.data.gemini.ValidatedAutomationAction

class VoiceAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun execute(action: ValidatedAutomationAction): ActionResult {
        if (securityControlVisible()) return ActionResult.Failure("Manual verification required.")
        return when (action) {
            is ValidatedAutomationAction.Click -> click(action.target, action.bounds)
            is ValidatedAutomationAction.Type -> {
                if (AutomationInputSafety.isSensitiveField(action.field)) {
                    ActionResult.Failure("Manual verification required.")
                } else {
                    enterText(action.text, action.field)
                }
            }
            ValidatedAutomationAction.ScrollUp -> scroll(ScrollDirection.UP)
            ValidatedAutomationAction.ScrollDown -> scroll(ScrollDirection.DOWN)
            is ValidatedAutomationAction.OpenApp -> openApp(action.packageName)
            is ValidatedAutomationAction.ConfirmRequired ->
                ActionResult.Failure("Confirmation required: ${action.reason}")
        }
    }

    fun securityControlVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            containsSecurityControl(root)
        } finally {
            root.recycle()
        }
    }

    private fun click(query: String?, requestedBounds: ScreenBounds?): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult.Failure("No active window is available.")
        val matches = if (!query.isNullOrBlank()) {
            findRankedMatches(root, query)
        } else if (requestedBounds != null) {
            findBoundsMatches(root, requestedBounds)
        } else {
            emptyList()
        }
        for (match in matches) {
            val target = clickableAncestor(match) ?: continue
            val targetLabel = listOf(
                match.text?.toString(),
                match.contentDescription?.toString(),
                target.text?.toString(),
                target.contentDescription?.toString(),
            ).filterNotNull().joinToString(" ")
            if (AutomationInputSafety.isDestructiveAction(targetLabel) ||
                AutomationInputSafety.isSystemSettingsAction(targetLabel)
            ) {
                target.recycle()
                matches.forEach(AccessibilityNodeInfo::recycle)
                root.recycle()
                return ActionResult.Failure("Manual verification required.")
            }
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
            if (clicked) {
                matches.forEach { it.recycle() }
                root.recycle()
                return ActionResult.Success(
                    query?.let { "Clicked \"$it\"." } ?: "Clicked the selected screen area.",
                )
            }
        }
        matches.forEach { it.recycle() }
        root.recycle()
        return ActionResult.Failure(
            query?.let { "Could not find a clickable element matching \"$it\"." }
                ?: "Could not find a clickable element at the requested bounds.",
        )
    }

    private fun enterText(text: String, field: String?): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult.Failure("No active window is available.")
        val fieldMatches = if (field.isNullOrBlank()) emptyList() else findRankedMatches(root, field)
        val editable = if (field.isNullOrBlank()) {
            findFocusedEditable(root)
        } else {
            fieldMatches.firstNotNullOfOrNull(::editableAncestor)
        }
        fieldMatches.forEach(AccessibilityNodeInfo::recycle)
        if (editable == null) {
            root.recycle()
            return ActionResult.Failure(
                if (field.isNullOrBlank()) "No editable field is available."
                else "Could not find an editable field matching \"$field\".",
            )
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val updated = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        editable.recycle()
        root.recycle()
        return if (updated) ActionResult.Success("Entered text.")
        else ActionResult.Failure("The selected field did not accept text input.")
    }

    private fun scroll(direction: ScrollDirection): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult.Failure("No active window is available.")
        val action = when (direction) {
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val scrollable = findScrollableNode(root)
        if (scrollable != null && scrollable.performAction(action)) {
            scrollable.recycle()
            root.recycle()
            return ActionResult.Success("Scrolled ${direction.name.lowercase()}.")
        }
        if (scrollable == null) {
            root.recycle()
            return ActionResult.Failure("No scrollable content is available.")
        }

        val bounds = Rect().also(scrollable::getBoundsInScreen)
        scrollable.recycle()
        root.recycle()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || bounds.isEmpty) {
            return ActionResult.Failure("Accessibility scrolling was unavailable and gesture fallback cannot run.")
        }

        val startY = bounds.centerY().toFloat()
        val distance = (bounds.height() * 0.35f).coerceAtLeast(80f)
        val endY = when (direction) {
            ScrollDirection.UP -> (startY - distance).coerceAtLeast(bounds.top.toFloat())
            ScrollDirection.DOWN -> (startY + distance).coerceAtMost(bounds.bottom.toFloat())
        }
        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), startY)
            lineTo(bounds.centerX().toFloat(), endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) ActionResult.Success("Scroll gesture dispatched.")
        else ActionResult.Failure("Could not scroll the current window.")
    }

    private fun openApp(name: String): ActionResult {
        val packageManager = packageManager
        val launchIntent = packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { app ->
                packageManager.getLaunchIntentForPackage(app.packageName) != null &&
                    (packageManager.getApplicationLabel(app).toString().equals(name, ignoreCase = true) ||
                        app.packageName.equals(name, ignoreCase = true))
            }
            .mapNotNull { packageManager.getLaunchIntentForPackage(it.packageName) }
            .firstOrNull()
            ?: return ActionResult.Failure("Could not find an installed app named \"$name\".")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return ActionResult.Success("Opened $name.")
    }

    private fun findRankedMatches(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val ranked = nodes.sortedBy { node ->
            val visibleText = node.text?.toString()?.trim()
            val contentDescription = node.contentDescription?.toString()?.trim()
            when {
                visibleText == query.trim() -> 0
                contentDescription == query.trim() -> 1
                visibleText?.equals(query.trim(), ignoreCase = true) == true -> 2
                contentDescription?.equals(query.trim(), ignoreCase = true) == true -> 3
                visibleText?.contains(query.trim(), ignoreCase = true) == true -> 4
                contentDescription?.contains(query.trim(), ignoreCase = true) == true -> 5
                else -> Int.MAX_VALUE
            }
        }
        val matches = ranked.takeWhile { node ->
            val visibleText = node.text?.toString()?.trim()
            val contentDescription = node.contentDescription?.toString()?.trim()
            visibleText == query.trim() ||
                contentDescription == query.trim() ||
                visibleText?.equals(query.trim(), ignoreCase = true) == true ||
                contentDescription?.equals(query.trim(), ignoreCase = true) == true ||
                visibleText?.contains(query.trim(), ignoreCase = true) == true ||
                contentDescription?.contains(query.trim(), ignoreCase = true) == true
        }
        ranked.drop(matches.size).forEach(AccessibilityNodeInfo::recycle)
        return matches
    }

    private fun findBoundsMatches(
        root: AccessibilityNodeInfo,
        requestedBounds: ScreenBounds,
    ): List<AccessibilityNodeInfo> {
        if (requestedBounds.left < 0 || requestedBounds.top < 0 ||
            requestedBounds.right <= requestedBounds.left ||
            requestedBounds.bottom <= requestedBounds.top
        ) {
            return emptyList()
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val centerX = (requestedBounds.left + requestedBounds.right) / 2
        val centerY = (requestedBounds.top + requestedBounds.bottom) / 2
        val requestedRect = Rect(
            requestedBounds.left,
            requestedBounds.top,
            requestedBounds.right,
            requestedBounds.bottom,
        )
        val matches = nodes.filter { node ->
            val nodeBounds = Rect().also(node::getBoundsInScreen)
            nodeBounds.contains(centerX, centerY) || nodeBounds.intersect(requestedRect)
        }.sortedBy { node ->
            Rect().also(node::getBoundsInScreen).let { it.width().toLong() * it.height() }
        }
        nodes.filterNot(matches::contains).forEach(AccessibilityNodeInfo::recycle)
        return matches
    }

    private fun containsSecurityControl(node: AccessibilityNodeInfo): Boolean {
        val text = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
        if (node.packageName?.toString() == ANDROID_SETTINGS_PACKAGE ||
            node.isPassword ||
            SECURITY_CONTROL_MARKERS.any { text.contains(it, ignoreCase = true) }
        ) {
            return true
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                val found = containsSecurityControl(child)
                child.recycle()
                if (found) return true
            }
        }
        return false
    }

    private fun collectNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        output += AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectNodes(child, output)
                child.recycle()
            }
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_ANCESTORS) {
            val candidate = current ?: return null
            if (candidate.isClickable ||
                candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            ) return candidate
            val parent = candidate.parent
            candidate.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    private fun editableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_ANCESTORS) {
            val candidate = current ?: return null
            if (candidate.isEditable) return candidate
            val parent = candidate.parent
            candidate.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                val found = findFocusedEditable(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                val found = findScrollableNode(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    companion object {
        private const val MAX_ANCESTORS = 20
        private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
        private val SECURITY_CONTROL_MARKERS = listOf(
            "captcha", "cloudflare", "two-factor", "two factor", "2fa",
            "verification code", "recovery code", "one-time password",
            "one time password", "security challenge", "security verification",
            "verify you are human", "password", "passcode", "authenticator",
        )

        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set

        fun isServiceEnabled(context: android.content.Context): Boolean {
            val componentName = ComponentName(context, VoiceAccessibilityService::class.java)
            val expectedComponent = componentName.flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
        }
    }
}

private enum class ScrollDirection { UP, DOWN }

sealed interface ActionResult {
    data class Success(val message: String) : ActionResult
    data class Failure(val message: String) : ActionResult
}
