package com.borizon.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Borizon application class.
 * Hilt provides singleton instances of Database and PreferencesManager via AppModule.
 */
@HiltAndroidApp
class BorizonApplication : Application()
