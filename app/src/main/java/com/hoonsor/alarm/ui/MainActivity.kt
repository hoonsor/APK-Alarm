package com.hoonsor.alarm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ui.alarms.AlarmEditorSheet
import com.hoonsor.alarm.ui.alarms.AlarmSelectionBar
import com.hoonsor.alarm.ui.alarms.AlarmsScreen
import com.hoonsor.alarm.ui.alarms.AlarmsViewModel
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.health.HealthChecker
import com.hoonsor.alarm.ui.health.HealthScreen
import com.hoonsor.alarm.ui.settings.SettingsScreen
import com.hoonsor.alarm.ui.stopwatch.StopwatchScreen
import com.hoonsor.alarm.ui.theme.AlarmTheme
import com.hoonsor.alarm.ui.theme.C
import com.hoonsor.alarm.ui.timer.TimerScreen
import com.hoonsor.alarm.ui.timer.TimerSelection
import com.hoonsor.alarm.ui.timer.TimerSelectionBar
import com.hoonsor.alarm.voice.VoiceScreen
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val glyph: Glyph) {
    ALARMS("鬧鐘", Glyph.Alarm), TIMER("計時器", Glyph.Hourglass), STOPWATCH("碼錶", Glyph.Stopwatch)
}

private enum class Overlay { NONE, HEALTH, SETTINGS, VOICE }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleExternalIntent(intent)
        setContent { AlarmTheme { MainScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 每次開啟 App 都全量重排一次，確保系統登記與資料庫一致
        lifecycleScope.launch { Graph.scheduler.rescheduleAll("開啟 App") }
    }

    /** 支援 Google 助理與其他自動化工具送來的標準 SET_ALARM / SET_TIMER Intent */
    private fun handleExternalIntent(intent: Intent?) {
        when (intent?.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                if (!intent.hasExtra(AlarmClock.EXTRA_HOUR)) return
                val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, 0).coerceIn(0, 23)
                val minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
                val memo = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE) ?: ""
                @Suppress("DEPRECATION")
                val days = (intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS) ?: arrayListOf())
                    .fold(0) { mask, d -> mask or (1 shl Days.fromCalendarDay(d)) }
                lifecycleScope.launch {
                    Graph.repo.addAlarm(AlarmEntity(hour = hour, minute = minute, daysMask = days, memo = memo))
                    Toast.makeText(this@MainActivity, "已建立 ${Fmt.hm(hour, minute)} 鬧鐘", Toast.LENGTH_SHORT).show()
                }
            }
            AlarmClock.ACTION_SET_TIMER -> {
                val seconds = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
                if (seconds <= 0) return
                val memo = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE) ?: ""
                lifecycleScope.launch {
                    Graph.repo.startTimer(seconds * 1000L, memo)
                    Toast.makeText(this@MainActivity, "已開始 ${Fmt.duration(seconds * 1000L)} 計時", Toast.LENGTH_SHORT).show()
                }
            }
        }
        intent?.action = Intent.ACTION_MAIN // 避免旋轉螢幕時重複建立
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val vm: AlarmsViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.ALARMS) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    /** 從計時器頁開啟語音：只說時長時優先建立計時器 */
    var voiceForTimer by rememberSaveable { mutableStateOf(false) }
    val timerSelection = remember { TimerSelection() }
    var healthIssues by remember { mutableIntStateOf(0) }
    var healthRefresh by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { healthRefresh++ }
    LaunchedEffect(healthRefresh, overlay) {
        if (overlay == Overlay.NONE) healthIssues = HealthChecker.run(context).issues
    }

    // 首次啟動要求通知權限（Android 13+）
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { healthRefresh++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = overlay != Overlay.NONE || vm.selecting || timerSelection.active) {
        when {
            overlay != Overlay.NONE -> overlay = Overlay.NONE
            timerSelection.active -> timerSelection.exit()
            else -> vm.exitSelect()
        }
    }

    fun showUndo(removed: List<AlarmEntity>) {
        if (removed.isEmpty()) return
        scope.launch {
            val r = snackbar.showSnackbar("已刪除 ${removed.size} 個鬧鐘", actionLabel = "復原", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.restore(removed) else vm.purgeFiles(removed)
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
        when (overlay) {
            Overlay.HEALTH -> HealthScreen(onBack = { overlay = Overlay.NONE })
            Overlay.SETTINGS -> SettingsScreen(onBack = { overlay = Overlay.NONE })
            Overlay.VOICE -> VoiceScreen(
                preferTimer = voiceForTimer,
                onClose = { overlay = Overlay.NONE },
                onCreated = { msg ->
                    overlay = Overlay.NONE
                    tab = if (msg.contains("計時")) Tab.TIMER else Tab.ALARMS
                    scope.launch { snackbar.showSnackbar(msg) }
                },
            )
            Overlay.NONE -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.ALARMS -> AlarmsScreen(
                            vm = vm,
                            healthIssues = healthIssues,
                            onOpenHealth = { overlay = Overlay.HEALTH },
                            onOpenSettings = { overlay = Overlay.SETTINGS },
                            onVoice = { voiceForTimer = false; overlay = Overlay.VOICE },
                        )
                        Tab.TIMER -> TimerScreen(timerSelection, onVoice = { voiceForTimer = true; overlay = Overlay.VOICE })
                        Tab.STOPWATCH -> StopwatchScreen()
                    }
                }
                HorizontalDivider(color = C.border)
                if (tab == Tab.ALARMS && vm.selecting) {
                    AlarmSelectionBar(vm, onDelete = { scope.launch { showUndo(vm.bulkDelete()) } })
                } else if (tab == Tab.TIMER && timerSelection.active) {
                    TimerSelectionBar(timerSelection)
                } else {
                    BottomNav(tab) { tab = it; vm.exitSelect(); timerSelection.exit() }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    val alarms by vm.alarms.collectAsState()
    val editing = vm.editingId?.let { id -> alarms.firstOrNull { it.id == id } }
    if (editing != null && overlay == Overlay.NONE) {
        key(editing.id) {
            AlarmEditorSheet(
                alarm = editing,
                vm = vm,
                onDelete = { id -> scope.launch { showUndo(vm.deleteOne(id)) } },
            )
        }
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().height(76.dp).background(C.bgDeep), horizontalArrangement = Arrangement.SpaceEvenly) {
        Tab.entries.forEach { t ->
            val sel = t == current
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GlyphIcon(t.glyph, null, tint = if (sel) C.accent else C.nav)
                Text(
                    t.label, color = if (sel) C.accent else C.nav, fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
