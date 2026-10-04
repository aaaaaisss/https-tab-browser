package com.example.httpsbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activity = MainActivity.pipActivity ?: return
        when (intent.action) {
            MainActivity.ACTION_PIP_SEEK_BACK, MainActivity.ACTION_PIP_SEEK_FORWARD ->
                activity.handlePipSeekFromReceiver(intent.getIntExtra(MainActivity.EXTRA_PIP_SEEK_SECONDS, 0))
        }
    }
}
