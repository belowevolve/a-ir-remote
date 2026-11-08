package belowevolve.airremote

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// Fixed synthetic data only. Keep identical to clipboard-fixture.ps1.
internal const val CLIPBOARD_FIXTURE_TEXT =
    "Air Remote clipboard fixture v1\nРусский: Привет, мир! 👋\nSymbols: \"quotes\" \\ slash · 二\nEND"

/** Opt-in only: requires the guarded Windows fixture to be armed before invocation. */
internal fun Instrumentation.checkClipboardFixture(results: Bundle) {
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
    val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
    var previous: ClipData? = null
    var seeded = false
    val success = "Текст на ПК · Ctrl+V отправлен"
    val replies = setOf(success, "Текст скопирован на ПК. Нажми Ctrl+V вручную",
        "Буфер ПК занят. Попробуй ещё раз", "ПК отклонил текст",
        "Не удалось записать текст в буфер ПК", "Не удалось подтвердить вставку. Проверь ПК")

    fun ownRoot(): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return root.takeIf { it.packageName?.toString() == targetContext.packageName }
    }
    fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, label)?.let { return it }
        }
        return null
    }
    fun awaitNode(label: String, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            ownRoot()?.let { root -> find(root, label)?.let { if (it.isEnabled) return it } }
            SystemClock.sleep(50)
        }
        error("Expected Air Remote control unavailable: $label")
    }
    fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target != null && target.isEnabled && target.isVisibleToUser &&
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Fixture UI action was not accepted" }
        waitForIdleSync()
    }

    try {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var focused = false
        var ownWindow = false
        while ((!focused || !ownWindow) && SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync { focused = activity.window.decorView.hasWindowFocus() }
            ownWindow = ownRoot() != null
            if (!focused || !ownWindow) SystemClock.sleep(50)
        }
        check(focused && ownWindow) { "MainActivity foreground required (focus=$focused, accessibility=$ownWindow)" }
        // The real PC tab and the real capability-gated production button are used.
        if (ownRoot()?.let { find(it, "Вставить на ПК") } == null) {
            click(awaitNode("ПК", 5_000))
        }
        val paste = awaitNode("Вставить на ПК", 15_000)
        runOnMainSync {
            check(activity.window.decorView.hasWindowFocus()) { "MainActivity lost foreground" }
            previous = clipboard.primaryClip // Memory only; never logged or exported.
            clipboard.setPrimaryClip(ClipData.newPlainText("Air Remote synthetic fixture", CLIPBOARD_FIXTURE_TEXT))
            seeded = true
        }
        sendStatus(0, Bundle().apply { putString("stream", "CLIPBOARD_FIXTURE: known text seeded; clicking production button once\n") })
        val event = automation.executeAndWaitForEvent(
            { check(ownRoot() != null) { "Air Remote lost foreground before paste" }; click(paste) },
            { event -> event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.text.any { it.toString() in replies } },
            12_000,
        )
        check(event.text.any { it.toString() == success }) {
            "PC did not acknowledge both clipboard copy and Ctrl+V injection; inspect fixture result"
        }
        results.putString("stream", "CLIPBOARD_FIXTURE: production button acknowledged clipboard + Ctrl+V; Windows fixture result is authoritative for exact text\n")
    } finally {
        // Restore only our own seeded data, never overwrite a subsequent user clipboard change.
        if (seeded) runOnMainSync {
            if (activity.window.decorView.hasWindowFocus()) {
                val current = clipboard.primaryClip
                if (current?.itemCount == 1 && current.getItemAt(0).text?.toString() == CLIPBOARD_FIXTURE_TEXT) {
                    previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                }
            }
        }
        // Keep the original production screen intact; do not replace Compose content.
    }
}
