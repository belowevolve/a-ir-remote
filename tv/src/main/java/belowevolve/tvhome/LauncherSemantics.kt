package belowevolve.tvhome

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Our key filter only reads the window's package, never virtual UI nodes. Compose otherwise
 * rebuilds its accessibility tree throughout focus/scroll animations just for that filter. Keep the
 * full tree whenever another service (including UI automation) can consume it.
 */
@Composable
internal fun LauncherSemantics(content: @Composable () -> Unit) {
  // Services-state notifications are needed to restore the tree as soon as a reader binds.
  // Older Android versions keep the full tree rather than risk missing that transition.
  if (Build.VERSION.SDK_INT < 33) {
    content()
    return
  }
  val context = LocalContext.current
  val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
  fun exposeTree(): Boolean {
    if (manager.isTouchExplorationEnabled) return true
    val services =
      manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val service = services.singleOrNull()?.resolveInfo?.serviceInfo ?: return true
    return service.packageName != context.packageName ||
      service.name != ColorKeyService::class.java.name
  }
  var expose by remember(manager) { mutableStateOf(exposeTree()) }
  DisposableEffect(manager) {
    val update = { expose = exposeTree() }
    val state = AccessibilityManager.AccessibilityStateChangeListener { update() }
    val touch = AccessibilityManager.TouchExplorationStateChangeListener { update() }
    manager.addAccessibilityStateChangeListener(state)
    manager.addTouchExplorationStateChangeListener(touch)
    val stopServices = ServiceChanges.watch(manager, update)
    onDispose {
      stopServices()
      manager.removeTouchExplorationStateChangeListener(touch)
      manager.removeAccessibilityStateChangeListener(state)
    }
  }
  Box(if (expose) Modifier else Modifier.clearAndSetSemantics {}) { content() }
}

@RequiresApi(33)
private object ServiceChanges {
  fun watch(manager: AccessibilityManager, update: () -> Unit): () -> Unit {
    val listener = AccessibilityManager.AccessibilityServicesStateChangeListener { update() }
    manager.addAccessibilityServicesStateChangeListener(listener)
    return { manager.removeAccessibilityServicesStateChangeListener(listener) }
  }
}
