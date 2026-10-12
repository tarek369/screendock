package com.cressu.screendock.ui

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview

/** Launches the Play in-app review flow; failures are logged, never surfaced. */
suspend fun requestInAppReview(activity: Activity) {
    try {
        val manager = ReviewManagerFactory.create(activity)
        val info = manager.requestReview()
        manager.launchReview(activity, info)
    } catch (e: Exception) {
        Log.w("AirplayMainActivity", "in-app review unavailable: ${e.message}")
    }
}
