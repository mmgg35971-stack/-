package com.mangatranslator.features.notifications

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mangatranslator.features.settings.NotificationsContent
import com.mangatranslator.features.settings.NotificationsViewModel

/** In-app notification list (job finished / failed). Push via FCM is a later phase. */
@Composable
fun NotificationsScreen(nav: NavController, vm: NotificationsViewModel = hiltViewModel()) = NotificationsContent(nav, vm)
