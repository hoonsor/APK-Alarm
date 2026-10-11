package com.hoonsor.alarm.ringing

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.ui.theme.AlarmTheme
import kotlinx.coroutines.delay

/** 鎖屏上也能顯示的響鈴畫面；響鈴結束（任何來源）就自動關閉。 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        val settings = Graph.settings
        setContent {
            AlarmTheme {
                val info by RingingService.current.collectAsState()
                BackHandler { /* 返回鍵不關閉響鈴，避免誤觸 */ }
                var seen by remember { mutableStateOf(false) }
                LaunchedEffect(info) {
                    if (info != null) {
                        seen = true
                    } else {
                        // 響鈴結束立刻關閉；剛開啟還沒收到內容時多等一下服務啟動
                        delay(if (seen) 150 else 1500)
                        if (RingingService.current.value == null) finish()
                    }
                }
                info?.let {
                    RingingScreen(
                        info = it,
                        vignetteShape = settings.vignetteShape,
                        vignetteStrength = settings.vignetteStrength,
                        onSnooze = { minutes -> startService(RingingService.snoozeIntent(this, minutes)) },
                        onStop = { startService(RingingService.dismissIntent(this)) },
                    )
                }
            }
        }
    }
}
