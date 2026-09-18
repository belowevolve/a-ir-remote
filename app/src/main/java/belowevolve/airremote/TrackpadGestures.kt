package belowevolve.airremote

import kotlin.math.hypot

/** Touch recognition independent of Compose and Bluetooth, with times in uptime milliseconds. */
internal class TrackpadGestures(
    private val slop: Float,
    private val doubleTapSlop: Float,
    private val doubleTapTimeout: Long,
    private val holdTimeout: Long,
    private val move: (Float, Float) -> Unit,
    private val scroll: (Float) -> Unit,
    private val click: (Boolean) -> Unit,
    private val button: (Boolean) -> Unit,
) {
    data class Contact(val id: Long, val x: Float, val y: Float, val pressed: Boolean = true)
    private data class Tap(val time: Long, val x: Float, val y: Float)
    private var lastTap: Tap? = null
    private val origins = mutableMapOf<Long, Contact>()
    private var previous = emptyMap<Long, Contact>()
    private var start = 0L
    private var fingers = 0
    private var moved = false
    private var held = false
    private var leftDown = false
    private var pendingX = 0f
    private var pendingY = 0f
    var active = false
        private set

    fun holdDelay(now: Long): Long? =
        if (active && fingers == 1 && !moved && !held && !leftDown)
            (start + holdTimeout - now).coerceAtLeast(1) else null

    fun hold() {
        if (holdDelay(start) != null) {
            held = true
            setButton(true)
        }
    }

    fun frame(time: Long, contacts: List<Contact>) {
        val pressed = contacts.filter { it.pressed }
        if (!active) {
            if (pressed.isEmpty()) return
            val first = pressed.first()
            active = true
            start = time
            fingers = pressed.size
            origins.clear()
            previous = emptyMap()
            moved = false
            held = false
            pendingX = 0f
            pendingY = 0f
            val tap = lastTap
            lastTap = null
            if (fingers == 1 && tap != null && time - tap.time in 0..doubleTapTimeout &&
                hypot(first.x - tap.x, first.y - tap.y) <= doubleTapSlop) {
                setButton(true)
            }
        }
        val count = maxOf(fingers, pressed.size)
        if (count != fingers) {
            pendingX = 0f
            pendingY = 0f
            fingers = count
            setButton(false)
        }
        contacts.forEach { contact ->
            val origin = origins.getOrPut(contact.id) { contact }
            if (hypot(contact.x - origin.x, contact.y - origin.y) >= slop) moved = true
        }
        // Only compare the same contacts: adding/lifting a finger must not jump the cursor.
        val common = contacts.filter { previous.containsKey(it.id) }
        if (common.isNotEmpty()) {
            val dx = common.sumOf { (it.x - previous.getValue(it.id).x).toDouble() }.toFloat() / common.size
            val dy = common.sumOf { (it.y - previous.getValue(it.id).y).toDouble() }.toFloat() / common.size
            if (fingers == 1) {
                pendingX += dx
                pendingY += dy
                if (moved) {
                    move(pendingX, pendingY)
                    pendingX = 0f
                    pendingY = 0f
                }
            } else if (fingers == 2 && common.size == 2) {
                pendingY += dy
                if (moved) {
                    scroll(pendingY)
                    pendingY = 0f
                }
            }
        }
        previous = pressed.associateBy { it.id }
        if (pressed.isEmpty()) {
            val tap = !moved && !held && time - start < holdTimeout
            if (leftDown) {
                setButton(false) // The second tap is already a press; no extra click on release.
            } else if (tap && fingers <= 2) {
                click(fingers == 2)
                if (fingers == 1) {
                    val origin = origins.values.first()
                    lastTap = Tap(time, origin.x, origin.y)
                }
            }
            active = false
        }
    }

    fun cancel() {
        setButton(false)
        active = false
        lastTap = null
        previous = emptyMap()
        origins.clear()
    }

    private fun setButton(pressed: Boolean) {
        if (leftDown != pressed) {
            leftDown = pressed
            button(pressed)
        }
    }
}
