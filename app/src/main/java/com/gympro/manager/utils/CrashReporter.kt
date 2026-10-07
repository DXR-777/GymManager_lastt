package com.gympro.manager.utils

import com.gympro.manager.BuildConfig

object CrashReporter {

    private val crashlytics: Any? by lazy {
        if (!BuildConfig.HAS_FIREBASE) null else try {
            Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
                .getMethod("getInstance").invoke(null)
        } catch (e: Throwable) {
            null
        }
    }

    fun init() {
        val c = crashlytics ?: return
        try {
            c.javaClass.getMethod("setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
                .invoke(c, !BuildConfig.DEBUG)
        } catch (_: Throwable) {
        }
    }

    fun record(t: Throwable) {
        val c = crashlytics ?: return
        try {
            c.javaClass.getMethod("recordException", Throwable::class.java).invoke(c, t)
        } catch (_: Throwable) {
        }
    }

    fun log(message: String) {
        val c = crashlytics ?: return
        try {
            c.javaClass.getMethod("log", String::class.java).invoke(c, message)
        } catch (_: Throwable) {
        }
    }
}
