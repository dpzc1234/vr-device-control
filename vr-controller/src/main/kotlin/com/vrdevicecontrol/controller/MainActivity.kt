package com.vrdevicecontrol.controller

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.vrdevicecontrol.controller.ui.ControllerApp
import com.vrdevicecontrol.controller.ui.ControllerViewModel
import com.vrdevicecontrol.controller.ui.theme.ControllerTheme

/**
 * The operator's screen. Sockets and the server live in [ControllerGraph], not here, so activity
 * recreation never touches connections. The screen stays awake during the operator session.
 */
class MainActivity : ComponentActivity() {
    private val vm: ControllerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ControllerTheme { ControllerApp(vm) } }
    }

    /** Any touch counts as activity for developer-mode auto-lock. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        (application as ControllerApplication).graph.access.touch()
        return super.dispatchTouchEvent(ev)
    }
}
