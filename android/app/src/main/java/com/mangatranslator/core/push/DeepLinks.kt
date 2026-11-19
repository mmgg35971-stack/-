package com.mangatranslator.core.push

import kotlinx.coroutines.flow.MutableStateFlow

/** A chapter to open after the user taps a push notification. Consumed once by the navigation host. */
object DeepLinks {
    val pendingJobId = MutableStateFlow<String?>(null)
}
