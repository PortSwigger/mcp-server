package net.portswigger.mcp.config

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class McpConfig(storage: PersistedObject, private val logging: Logging) {

    var enabled by storage.boolean(true)
    var configEditingTooling by storage.boolean(false)
    var host by storage.string("127.0.0.1")
    var port by storage.int(9876)
    var requireHttpRequestApproval by storage.boolean(true)
    var requireHistoryAccessApproval by storage.boolean(true)

    private var _alwaysAllowHttpHistory by storage.boolean(false)
    var alwaysAllowHttpHistory: Boolean
        get() = _alwaysAllowHttpHistory
        set(value) {
            if (_alwaysAllowHttpHistory != value) {
                _alwaysAllowHttpHistory = value
                notifyHistoryAccessChanged()
            }
        }

    private var _alwaysAllowWebSocketHistory by storage.boolean(false)
    var alwaysAllowWebSocketHistory: Boolean
        get() = _alwaysAllowWebSocketHistory
        set(value) {
            if (_alwaysAllowWebSocketHistory != value) {
                _alwaysAllowWebSocketHistory = value
                notifyHistoryAccessChanged()
            }
        }

    private var _autoApproveTargets by storage.stringList("")
    private val targetsChangeListeners = CopyOnWriteArrayList<ListenerRegistration>()
    private val historyAccessChangeListeners = CopyOnWriteArrayList<ListenerRegistration>()

    var autoApproveTargets: String
        get() = _autoApproveTargets
        set(value) {
            if (_autoApproveTargets != value) {
                _autoApproveTargets = value
                notifyTargetsChanged()
            }
        }

    fun addAutoApproveTarget(target: String): Boolean {
        val currentTargets = getAutoApproveTargetsList()
        if (target.trim().isNotEmpty() && !currentTargets.contains(target.trim())) {
            val newTargets = currentTargets + target.trim()
            autoApproveTargets = newTargets.joinToString(",")
            return true
        }
        return false
    }

    fun removeAutoApproveTarget(target: String): Boolean {
        val currentTargets = getAutoApproveTargetsList()
        val newTargets = currentTargets.filter { it != target.trim() }
        if (newTargets.size != currentTargets.size) {
            autoApproveTargets = newTargets.joinToString(",")
            return true
        }
        return false
    }

    fun getAutoApproveTargetsList(): List<String> {
        return if (_autoApproveTargets.isBlank()) {
            emptyList()
        } else {
            _autoApproveTargets.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun clearAutoApproveTargets() {
        autoApproveTargets = ""
    }

    fun addTargetsChangeListener(listener: () -> Unit): ListenerHandle {
        val registration = ListenerRegistration(listener)
        targetsChangeListeners.add(registration)
        return ListenerHandle { removeTargetsChangeListener(registration) }
    }

    private fun removeTargetsChangeListener(registration: ListenerRegistration) {
        targetsChangeListeners.remove(registration)
    }

    private fun notifyTargetsChanged() {
        cleanupStaleListeners(targetsChangeListeners)
        val listeners = targetsChangeListeners.mapNotNull { it.listener.get() }
        listeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logging.logToError("Targets change listener failed: ${e.message}")
            }
        }
    }

    fun addHistoryAccessChangeListener(listener: () -> Unit): ListenerHandle {
        val registration = ListenerRegistration(listener)
        historyAccessChangeListeners.add(registration)
        return ListenerHandle { removeHistoryAccessChangeListener(registration) }
    }

    private fun removeHistoryAccessChangeListener(registration: ListenerRegistration) {
        historyAccessChangeListeners.remove(registration)
    }

    private fun notifyHistoryAccessChanged() {
        cleanupStaleListeners(historyAccessChangeListeners)
        val listeners = historyAccessChangeListeners.mapNotNull { it.listener.get() }
        listeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logging.logToError("History access change listener failed: ${e.message}")
            }
        }
    }

    private fun cleanupStaleListeners(listenerList: CopyOnWriteArrayList<ListenerRegistration>) {
        val staleListeners = listenerList.filter { it.listener.get() == null }
        listenerList.removeAll(staleListeners)
    }

    fun cleanup() {
        targetsChangeListeners.clear()
        historyAccessChangeListeners.clear()
    }
}

fun PersistedObject.boolean(default: Boolean = false) =
    PersistedDelegate(getter = { key -> getBoolean(key) ?: default }, setter = { key, value -> setBoolean(key, value) })

fun PersistedObject.string(default: String) =
    PersistedDelegate(getter = { key -> getString(key) ?: default }, setter = { key, value -> setString(key, value) })

fun PersistedObject.int(default: Int) =
    PersistedDelegate(getter = { key -> getInteger(key) ?: default }, setter = { key, value -> setInteger(key, value) })

fun PersistedObject.stringList(default: String) =
    PersistedDelegate(getter = { key -> getString(key) ?: default }, setter = { key, value -> setString(key, value) })

class PersistedDelegate<T>(
    private val getter: (name: String) -> T, private val setter: (name: String, value: T) -> Unit
) : ReadWriteProperty<Any, T> {
    override fun getValue(thisRef: Any, property: KProperty<*>) = getter(property.name)
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = setter(property.name, value)
}

class ListenerRegistration(listener: () -> Unit) {
    val listener: WeakReference<() -> Unit> = WeakReference(listener)
}

fun interface ListenerHandle {
    fun remove()
}