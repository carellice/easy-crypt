package com.easycrypt.keyboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/** La sessione vive solo in memoria: uscire dall'app o terminare il processo la blocca. */
class AppLockSession {
    var authenticated = false
        private set
    private var started = 0

    fun authenticate() { authenticated = true }
    fun paused(stayingInApp: Boolean) {
        if (!stayingInApp) authenticated = false
    }
    fun started() { started++ }
    fun stopped(changingConfiguration: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !changingConfiguration) authenticated = false
    }
}

class AppLock(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
}

class EasyCryptApplication : Application(), Application.ActivityLifecycleCallbacks {
    val lockSession = AppLockSession()
    private val activities = mutableSetOf<Activity>()

    fun returningInsideApp(activity: Activity): Boolean = activities.any {
        it !== activity && !it.isFinishing && !it.isDestroyed && it.taskId == activity.taskId
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) { lockSession.started() }
    override fun onActivityStopped(activity: Activity) { lockSession.stopped(activity.isChangingConfigurations) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.add(activity) }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
}
