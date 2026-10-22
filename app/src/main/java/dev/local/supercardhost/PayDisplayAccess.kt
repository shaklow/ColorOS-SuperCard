package dev.local.supercardhost

import android.content.ComponentName
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.BiConsumer

/** Typed access to the bundled OEM manager; owns no display or activity itself. */
internal class PayDisplayAccess(
    managerType: Class<*>,
    private val sessions: PaySessionState,
    private val failure: BiConsumer<String, Throwable>
) {
    // The bundled DEX uses this exact name. getIsVirtualDisplayActive was a JADX display name.
    private val active = method(managerType, "isVirtualDisplayActive", Boolean::class.javaPrimitiveType!!)
    private val display = method(managerType, "getVirtualDisplayId", Int::class.javaPrimitiveType!!)
    private val task = method(managerType, "getTaskId", Int::class.javaPrimitiveType!!)
    private val setTask = method(managerType, "setTaskId", Void.TYPE, Int::class.javaPrimitiveType!!)
    private val resumed = method(managerType, "handleActivityResumed", Void.TYPE, ComponentName::class.java)
    private val release = method(managerType, "release", Void.TYPE)

    fun id(session: PaySessionState.Snapshot): Int {
        val manager = session.manager ?: return -1
        if (!sessions.isCurrent(session)) return -1
        return try {
            if (active.invoke(manager) != true) return -1
            val id = (display.invoke(manager) as Number).toInt()
            if (id > 0 && sessions.isCurrent(session)) id else -1
        } catch (error: Throwable) { failure.accept("read payment display", error); -1 }
    }

    fun notifyResumed(manager: Any, component: ComponentName, taskId: Int, changed: Boolean, notified: Runnable) {
        if (changed) {
            try { resumed.invoke(manager, component); notified.run() }
            finally { correctTask(manager, taskId) }
        } else correctTask(manager, taskId)
    }

    private fun correctTask(manager: Any, taskId: Int) {
        if ((task.invoke(manager) as Number).toInt() != taskId) setTask.invoke(manager, taskId)
    }

    fun release(manager: Any) {
        try { release.invoke(manager) }
        catch (error: Exception) { failure.accept("release previous PAY user", error) }
    }

    companion object {
        fun method(type: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method {
            var owner: Class<*>? = type
            while (owner != null) {
                try {
                    return owner.getDeclaredMethod(name, *parameters).apply {
                        require(returnType == result && !Modifier.isStatic(modifiers)) { "Original method signature changed: $name" }
                        isAccessible = true
                    }
                } catch (_: NoSuchMethodException) { owner = owner.superclass }
            }
            throw NoSuchMethodException("${type.name}.$name")
        }
    }
}
