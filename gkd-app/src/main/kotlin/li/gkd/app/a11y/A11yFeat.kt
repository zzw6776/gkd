package li.gkd.app.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import li.gkd.app.app
import li.gkd.app.appScope
import li.gkd.app.priv.IScreenshotListener
import li.gkd.app.priv.IScreenshotFileListener
import li.gkd.app.priv.ScreenshotMonitorState
import li.gkd.app.priv.PrivilegeContext
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ScreenUtils
import li.gkd.app.snapshot.SnapshotCapture
import li.gkd.app.data.subscription.SubscriptionResult
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.util.UpdateTimeOption
import li.gkd.app.util.launchLogged
import li.gkd.app.util.mapState
import li.gkd.selector.MatchOptions
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import li.gkd.selector.NodeAdapter


fun onA11yFeatEvent(event: AccessibilityEvent) = event.run {
    if (event.eventType == STATE_CHANGED) {
        watchCaptureScreenshot()
        if (event.packageName == launcherAppId) {
            watchAutoUpdateSubs()
        }
    }
}

private var tempEventSelector = "" to (null as Selector?)
private fun AccessibilityEvent.getEventAttr(name: String): Any? = when (name) {
    "name" -> className
    "desc" -> contentDescription
    "text" -> text
    else -> null
}

private object A11yEventNodeAdapter : NodeAdapter<AccessibilityEvent>() {
    override fun getAttr(target: Any, name: String): Any? = when (target) {
        is AccessibilityEvent -> target.getEventAttr(name)
        is List<*> -> when (name) {
            "size" -> target.size
            else -> null
        }

        else -> null
    }

    override fun getInvoke(target: Any, name: String, args: List<Any>): Any? = when (target) {
        is List<*> -> when (name) {
            "get" -> (args.singleOrNull() as? Int)?.let(target::getOrNull)
            else -> null
        }

        else -> null
    }

    override fun getName(node: AccessibilityEvent): String? = node.className?.toString()

    override fun getChildCount(node: AccessibilityEvent): Int = 0

    override fun getChild(node: AccessibilityEvent, index: Int): AccessibilityEvent? = null

    override fun getParent(node: AccessibilityEvent): AccessibilityEvent? = null

    override fun getNodeKey(node: AccessibilityEvent): Any = node
}

private val a11yEventAdapter = A11yEventNodeAdapter

context(event: AccessibilityEvent)
private fun watchCaptureScreenshot() {
    if (!storeFlow.value.captureScreenshot) return
    if (privilegedScreenshotListenerActive.value) return
    if (SnapshotCapture.isCapturing) return
    if (event.packageName != storeFlow.value.screenshotTargetAppId) return
    if (tempEventSelector.first != storeFlow.value.screenshotEventSelector) {
        val result = Selector.compile(storeFlow.value.screenshotEventSelector)
        tempEventSelector = storeFlow.value.screenshotEventSelector to
            (result as? SelectorCompileResult.Success)?.value
    }
    val selector = tempEventSelector.second ?: return
    selector.match(event, a11yEventAdapter, MatchOptions(fastQuery = false)).let {
        if (it == null) return
    }
    appScope.launchLogged {
        SnapshotCapture.capture()
    }
}

private val privilegedScreenshotListenerActive = atomic(false)

private val snapshotKeyListener = object : IScreenshotListener.Stub() {
    override fun onScreenshot() {
        appScope.launchLogged(Dispatchers.IO) {
            if (!storeFlow.value.capturePowerVolumeUp || ScreenUtils.isScreenLock()) return@launchLogged
            if (SnapshotCapture.isCapturing) return@launchLogged
            val expectedAppId = privilegeContextFlow.value?.topCpn()?.packageName
                ?: currentTopActivity.appId
            if (expectedAppId.isEmpty()) return@launchLogged
            LogUtils.d("组合键触发快照: $expectedAppId")
            SnapshotCapture.captureForApp(expectedAppId)
        }
    }
}

private fun initSnapshotKeyMonitor() {
    appScope.launchLogged(Dispatchers.IO) {
        var registeredContext: PrivilegeContext? = null
        combine(privilegeContextFlow, storeFlow) { context, store ->
            context to (store.capturePowerVolumeUp && context?.serverInfo?.uid == 0)
        }.distinctUntilChanged().collect { (context, enabled) ->
            registeredContext?.let { runCatching { it.clearSnapshotKeyListener() } }
            registeredContext = null
            if (context != null && enabled) {
                runCatching { check(context.setSnapshotKeyListener(snapshotKeyListener)) }
                    .onSuccess {
                        registeredContext = context
                        LogUtils.d("Root 组合键监听已启动")
                    }.onFailure {
                        LogUtils.d("Root 组合键监听启动失败", it)
                        li.gkd.app.util.ToastUtils.toast("组合键监听启动失败，请检查 Root 连接")
                    }
            }
        }
    }
}

private val screenshotMonitorGeneration = atomic(0)

private fun createScreenshotListener(generation: Int) = object : IScreenshotFileListener.Stub() {
    override fun onStateChanged(watching: Boolean, message: String) {
        if (screenshotMonitorGeneration.value != generation) return
        privilegedScreenshotListenerActive.value = watching
        ScreenshotMonitorState.update(message)
    }

    override fun onScreenshot() {
        appScope.launchLogged(Dispatchers.IO) {
            if (screenshotMonitorGeneration.value != generation) return@launchLogged
            val store = storeFlow.value
            if (!store.captureScreenshot || !store.captureScreenshotByPrivilege) return@launchLogged
            if (SnapshotCapture.isCapturing) return@launchLogged
            val expectedAppId = privilegeContextFlow.value?.topCpn()?.packageName
                ?: currentTopActivity.appId
            if (expectedAppId.isEmpty()) return@launchLogged
            LogUtils.d("检测到系统截图文件事件: $expectedAppId")
            SnapshotCapture.captureForApp(expectedAppId)
        }
    }
}

private fun initCaptureScreenshotFileMonitor() {
    appScope.launchLogged(Dispatchers.IO) {
        var registeredContext: PrivilegeContext? = null
        combine(privilegeContextFlow, storeFlow) { context, store ->
            context to (
                    store.captureScreenshot &&
                            store.captureScreenshotByPrivilege &&
                            context?.serverInfo?.uid == 0
                    )
        }.distinctUntilChanged().collect { (context, enabled) ->
            val generation = screenshotMonitorGeneration.incrementAndGet()
            registeredContext?.let { oldContext ->
                runCatching { oldContext.clearScreenshotFileListener() }
            }
            registeredContext = null
            privilegedScreenshotListenerActive.value = false
            ScreenshotMonitorState.update(if (enabled) "正在注册目录监听" else "未启动")
            if (context == null || !enabled) return@collect
            val result = runCatching {
                check(context.setScreenshotFileListener(createScreenshotListener(generation)))
            }
            val active = result.isSuccess
            if (active) {
                registeredContext = context
                LogUtils.d("Root 截图文件监听已启动")
            } else {
                ScreenshotMonitorState.update("监听启动失败，请检查存储目录后重新开启")
                LogUtils.d("Root 截图文件监听启动失败", result.exceptionOrNull())
            }
        }
    }
}

private var lastUpdateSubsTime = 0L
private var autoRefreshPending = false
private fun watchAutoUpdateSubs() {
    val interval = storeFlow.value.updateSubsInterval
    if (interval <= 0 || autoRefreshPending) return
    val currentTime = System.currentTimeMillis()
    if (
        currentTime - lastUpdateSubsTime <=
        interval.coerceAtLeast(UpdateTimeOption.Everyday.value)
    ) return
    autoRefreshPending = true
    appScope.launchLogged {
        try {
            val result = SubscriptionRepository.refresh()
            if (result !is SubscriptionResult.Busy) {
                lastUpdateSubsTime = currentTime
            }
        } finally {
            autoRefreshPending = false
        }
    }
}

private fun initRuleChangedLog() {
    appScope.launch(Dispatchers.Default) {
        activityRuleFlow.debounce(300).drop(1).collect {
            if (storeFlow.value.enableMatch && it.currentRules.isNotEmpty()) {
                LogUtils.d(it.topActivity, *it.currentRules.map { r ->
                    r.statusText()
                }.toTypedArray())
            }
        }
    }
}

private const val volumeChangedAction = "android.media.VOLUME_CHANGED_ACTION"
private fun createVolumeReceiver() = object : BroadcastReceiver() {
    var lastVolumeTriggerTime = -1L
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == volumeChangedAction) {
            val t = System.currentTimeMillis()
            if (t - lastVolumeTriggerTime > 3000 && !ScreenUtils.isScreenLock()) {
                lastVolumeTriggerTime = t
                appScope.launchLogged {
                    SnapshotCapture.capture()
                }
            }
        }
    }
}

private fun initCaptureVolume() {
    var captureVolumeReceiver: BroadcastReceiver? = null
    val changeRegister: (Boolean) -> Unit = {
        captureVolumeReceiver?.let(app::unregisterReceiver)
        captureVolumeReceiver = if (it) {
            createVolumeReceiver().apply {
                ContextCompat.registerReceiver(
                    app,
                    this,
                    IntentFilter(volumeChangedAction),
                    ContextCompat.RECEIVER_EXPORTED
                )
            }
        } else {
            null
        }
    }
    appScope.launch(Dispatchers.IO) {
        storeFlow.mapState(appScope) { s -> s.captureVolumeChange }.collect(changeRegister)
    }
}

var isInteractive = true
    private set
private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?
    ) {
        val action = intent?.action ?: return
        LogUtils.d("screenStateReceiver->${action}")
        isInteractive = when (action) {
            Intent.ACTION_SCREEN_ON -> true
            Intent.ACTION_SCREEN_OFF -> false
            Intent.ACTION_USER_PRESENT -> true
            else -> isInteractive
        }
        if (isInteractive) {
            val t = System.currentTimeMillis()
            if (t - appChangeTime > 500) { // 37.872(a11y) -> 38.228(onReceive)
                A11yRuntime.onScreenForcedActive()
            }
        }
    }
}

private fun initScreenStateReceiver() {
    isInteractive = app.powerManager.isInteractive
    ContextCompat.registerReceiver(
        app,
        screenStateReceiver,
        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        },
        ContextCompat.RECEIVER_EXPORTED
    )
}

fun initA11yFeat() {
    initRuleChangedLog()
    initCaptureScreenshotFileMonitor()
    initSnapshotKeyMonitor()
    initCaptureVolume()
    initScreenStateReceiver()
}
