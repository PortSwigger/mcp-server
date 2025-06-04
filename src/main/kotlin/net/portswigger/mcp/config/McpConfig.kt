package net.portswigger.mcp.config

import burp.api.montoya.persistence.PersistedObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class McpConfig(storage: PersistedObject) {

    var enabled by storage.boolean(true)
    var configEditingTooling by storage.boolean(false)
    var host by storage.string("127.0.0.1")
    var port by storage.int(9876)
    var requireHttpRequestApproval by storage.boolean(true)
    
    private var _autoApproveTargets by storage.stringList("")
    private val targetsChangeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    
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
            _autoApproveTargets.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
    
    fun clearAutoApproveTargets() {
        autoApproveTargets = ""
    }
    
    fun addTargetsChangeListener(listener: () -> Unit) {
        targetsChangeListeners.add(listener)
    }
    
    fun removeTargetsChangeListener(listener: () -> Unit) {
        targetsChangeListeners.remove(listener)
    }

    private fun notifyTargetsChanged() {
        val listeners = targetsChangeListeners.toList()
        listeners.forEach { 
            try {
                it()
            } catch (e: Exception) {
                println("Warning: Targets change listener failed: ${e.message}")
            }
        }
    }
}

fun PersistedObject.boolean(default: Boolean = false) =
    PersistedDelegate(
        getter = { key -> getBoolean(key) ?: default },
        setter = { key, value -> setBoolean(key, value) }
    )

fun PersistedObject.string(default: String) =
    PersistedDelegate(
        getter = { key -> getString(key) ?: default },
        setter = { key, value -> setString(key, value) }
    )

fun PersistedObject.int(default: Int) =
    PersistedDelegate(
        getter = { key -> getInteger(key) ?: default },
        setter = { key, value -> setInteger(key, value) }
    )

fun PersistedObject.stringList(default: String) =
    PersistedDelegate(
        getter = { key -> getString(key) ?: default },
        setter = { key, value -> setString(key, value) }
    )

class PersistedDelegate<T>(
    private val getter: (name: String) -> T,
    private val setter: (name: String, value: T) -> Unit
) : ReadWriteProperty<Any, T> {
    override fun getValue(thisRef: Any, property: KProperty<*>) = getter(property.name)
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = setter(property.name, value)
}