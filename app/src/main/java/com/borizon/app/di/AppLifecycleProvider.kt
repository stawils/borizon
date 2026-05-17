package com.borizon.app.di

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface AppLifecycleProvider {
    val isInForeground: StateFlow<Boolean>
}

class AppLifecycleProviderImpl : AppLifecycleProvider, DefaultLifecycleObserver {
    private val _isInForeground = MutableStateFlow(false)
    override val isInForeground: StateFlow<Boolean> = _isInForeground
    @Volatile private var registered = false

    fun markRegistered() { registered = true }
    val isRegistered: Boolean get() = registered

    override fun onStart(owner: LifecycleOwner) { _isInForeground.value = true }
    override fun onStop(owner: LifecycleOwner) { _isInForeground.value = false }
}
