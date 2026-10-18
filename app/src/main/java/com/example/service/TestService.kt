package com.example.service

// Synced to trigger GitHub changes

// Synced project files for native translation handling
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.local.entity.RouterProfileEntity
import com.example.data.local.entity.TestResultEntity
import com.example.data.local.preferences.AppPreferences
import com.example.domain.model.LogLevel
import com.example.domain.model.ResultCategory
import com.example.domain.model.ResultSubReason
import com.example.domain.repository.IRouterRepository
import com.example.domain.repository.ISessionRepository
import com.example.domain.repository.ITestResultRepository
import com.example.util.SecurityUtils
import com.example.util.WifiConnectivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.app.ActivityManager
import com.example.service.BelloTestStrategy
import com.example.service.AbashaTestStrategy
import com.example.service.MotasemTestStrategy
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class TestService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val ACTION_RETRY_LOAD = "ACTION_RETRY_LOAD"

        const val EXTRA_ROUTER_ID = "EXTRA_ROUTER_ID"
        const val EXTRA_CARD_LIST = "EXTRA_CARD_LIST"
        const val EXTRA_DELAY_MS = "EXTRA_DELAY_MS"

        private val _serviceState = MutableStateFlow(ServiceState())
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private val routerRepository: IRouterRepository by inject()
    private val sessionRepository: ISessionRepository by inject()
    private val testResultRepository: ITestResultRepository by inject()
    private val appPreferences: AppPreferences by inject()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val webViewPool = mutableListOf<WebView>()
    private data class NavigationToken(
        val generation: Long,
        val deferred: CompletableDeferred<Unit>,
        val targetUrl: String,
        @Volatile var pageStarted: Boolean = false
    )

    /**
     * A newer navigation replaced this WebView's previous pending navigation.
     * This is not cancellation of the worker/test job; callers may recover by
     * checking the DOM or retrying the navigation.
     */
    private class NavigationSupersededException(message: String) : Exception(message)
    private val activeNavigations = java.util.concurrent.ConcurrentHashMap<WebView, NavigationToken>()
    private val navigationGenerationSeq = AtomicLong(1000L)
    private var currentWebViewIndex = 0

    private val activeWebView: WebView?
        get() = webViewPool.getOrNull(currentWebViewIndex)

    private var testJob: Job? = null
    private var screenshotJob: Job? = null
    private val binder = ServiceBinder(this)
    private lateinit var notificationHelper: NotificationHelper

    private var retryDeferred: CompletableDeferred<Boolean>? = null
    private var currentTargetRouterIp: String? = null
    private val lastNotificationUpdateTime = AtomicLong(0L)
    private var currentSessionId: Long? = null
    private var serviceConnectivityManager: ConnectivityManager? = null
    private var serviceNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val wifiLossGeneration = AtomicLong(0L)
    private val wifiStateGuard = Any()
    @Volatile private var lastKnownWifiConnected = false
    private val wifiWaitMessage = "انقطع اتصال Wi-Fi؛ ستُستأنف البطاقة الحالية تلقائياً بعد عودة الاتصال"
    private val capturedAlerts = java.util.concurrent.ConcurrentHashMap<WebView, Pair<Long, String>>()
    private val finalizedAttempts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val stopRequestedDueToSuccess = java.util.concurrent.atomic.AtomicBoolean(false)

    private data class CardAttempt(
        val sessionId: Long,
        val cardIndex: Int,
        val cardCode: String,
        val workerId: Int,
        val attemptSequence: Int,
        val generationToken: Long,
        val wifiGenerationAtStart: Long
    )

    private fun maskCard(card: String): String =
        if (card.length > 4) card.take(2) + "***" + card.takeLast(2) else "***"

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        notificationHelper = NotificationHelper(this)
        registerWifiNetworkMonitor()
    }

    private fun isActiveWifiConnectedNow(): Boolean =
        WifiConnectivity.isActiveWifiConnected(applicationContext)

    private fun registerWifiNetworkMonitor() {
        serviceConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        lastKnownWifiConnected = isActiveWifiConnectedNow()
        val manager = serviceConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshWifiConnectionState()
            override fun onLost(network: Network) = refreshWifiConnectionState()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshWifiConnectionState()
        }
        serviceNetworkCallback = callback
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Timber.e(e, "Unable to monitor active Wi-Fi network; polling remains enabled during the test")
        }
    }

    /**
     * Tracks loss of the *active* Wi-Fi route. A captive portal does not need INTERNET or
     * VALIDATED capabilities; transport Wi-Fi is the gate because the router login page is local.
     */
    private fun refreshWifiConnectionState() {
        val connected = isActiveWifiConnectedNow()
        var lost = false
        synchronized(wifiStateGuard) {
            if (lastKnownWifiConnected && !connected) {
                wifiLossGeneration.incrementAndGet()
                lost = true
            }
            lastKnownWifiConnected = connected
        }

        if (lost && testJob?.isActive == true) {
            _serviceState.update { state ->
                if (state.status == "RUNNING" || state.status == "WAITING_WIFI" || state.status == "PAUSED") {
                    state.copy(status = "WAITING_WIFI", sessionState = com.example.domain.model.TestSessionState.PAUSED, error = wifiWaitMessage)
                } else state
            }
            notificationHelper.updateNotification(_serviceState.value)
            Timber.w("Active Wi-Fi route lost; preserving current card and pausing network-dependent work")
        } else if (connected && _serviceState.value.status == "WAITING_WIFI") {
            _serviceState.update { state ->
                if (state.status == "WAITING_WIFI") {
                    state.copy(
                        status = if (state.isPaused) "PAUSED" else "RUNNING",
                        sessionState = if (state.isPaused) com.example.domain.model.TestSessionState.PAUSED else com.example.domain.model.TestSessionState.RUNNING,
                        error = if (state.error == wifiWaitMessage) null else state.error
                    )
                } else state
            }
            notificationHelper.updateNotification(_serviceState.value)
        }
    }

    /** Suspends without ending the session or consuming a queued card until active Wi-Fi returns. */
    private suspend fun awaitActiveWifi(reason: String) {
        var announced = false
        while (true) {
            currentCoroutineContext().ensureActive()
            refreshWifiConnectionState()
            if (isActiveWifiConnectedNow()) {
                if (_serviceState.value.status == "WAITING_WIFI") {
                    _serviceState.update { state ->
                        if (state.status == "WAITING_WIFI") {
                            state.copy(
                                status = if (state.isPaused) "PAUSED" else "RUNNING",
                                sessionState = if (state.isPaused) com.example.domain.model.TestSessionState.PAUSED else com.example.domain.model.TestSessionState.RUNNING,
                                error = if (state.error == wifiWaitMessage) null else state.error
                            )
                        } else state
                    }
                    notificationHelper.updateNotification(_serviceState.value)
                }
                if (announced) Timber.i("Active Wi-Fi restored; resuming $reason")
                return
            }

            if (!announced) {
                announced = true
                Timber.w("Waiting for active Wi-Fi before continuing: $reason")
            }
            _serviceState.update { state ->
                if (state.status == "DONE" || state.status == "CANCELLED" || state.status == "LOAD_ERROR") state
                else state.copy(status = "WAITING_WIFI", sessionState = com.example.domain.model.TestSessionState.PAUSED, error = wifiWaitMessage)
            }
            notificationHelper.updateNotification(_serviceState.value)
            delay(500L)
        }
    }

    private fun wasWifiInterruptedSince(generation: Long): Boolean =
        !isActiveWifiConnectedNow() || wifiLossGeneration.get() != generation

    private fun createWebViewInstance(): WebView {
        return WebView(applicationContext).apply {
            val dm: DisplayMetrics = resources.displayMetrics
            layout(0, 0, dm.widthPixels, dm.heightPixels)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                // Security hardening: restrict filesystem & content access
                allowFileAccess = false
                allowContentAccess = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (view != null && message != null) {
                        val gen = activeNavigations[view]?.generation ?: navigationGenerationSeq.get()
                        capturedAlerts[view] = Pair(gen, message)
                        Timber.d("Captured onJsAlert (gen $gen): $message on URL: $url")
                    }
                    result?.confirm()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view != null && !url.isNullOrBlank()) {
                        val nav = activeNavigations[view]
                        if (nav != null) {
                            val callbackUrl = url.substringBefore("#").trimEnd('/')
                            val requestedUrl = nav.targetUrl.substringBefore("#").trimEnd('/')
                            // Only the start callback for this request can arm its completion
                            // token. A late finish event from the previous load is ignored.
                            if (callbackUrl.equals(requestedUrl, ignoreCase = true)) {
                                nav.pageStarted = true
                                Timber.d("Navigation generation ${nav.generation} started: $url")
                            }
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Timber.d("WebView page loaded: $url")
                    
                    // Auto-click reload button ONLY if present on genuine browser network error pages
                    val autoReloadJs = """
                        (function() {
                            var isNetErr = (document.title || '').toLowerCase().indexOf('webpage not available') !== -1 ||
                                           document.getElementById('main-frame-error') !== null ||
                                           document.querySelector('.neterror') !== null;
                            if (!isNetErr) return false;
                            var buttons = document.querySelectorAll('button, a, input[type="button"]');
                            for (var i = 0; i < buttons.length; i++) {
                                var text = (buttons[i].innerText || buttons[i].value || '').toLowerCase();
                                if (text.indexOf('إعادة تحميل') !== -1 || text.indexOf('retry') !== -1 || text.indexOf('reload') !== -1 || text.indexOf('تحديث') !== -1) {
                                    buttons[i].click();
                                    return true;
                                }
                            }
                            return false;
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(autoReloadJs) { result ->
                        if (result == "true") {
                            Timber.d("Auto-clicked reload button on network error page")
                        }
                    }

                    if (view != null) {
                        val callbackUrl = url.orEmpty()
                        val currentUrl = view.url.orEmpty()
                        // WebView can deliver a late onPageFinished for a URL that was
                        // replaced by a newer loadUrl(). Never let that stale callback
                        // complete the newer navigation's deferred.
                        if (callbackUrl.isNotBlank() && callbackUrl == currentUrl) {
                            val nav = activeNavigations[view]
                            if (nav != null && nav.pageStarted && nav.deferred.isActive) {
                                Timber.d("Completing navigation generation ${nav.generation} for current URL $currentUrl")
                                nav.deferred.complete(Unit)
                            } else if (nav != null && !nav.pageStarted) {
                                Timber.d("Ignoring finish callback before current navigation start (generation=${nav.generation})")
                            }
                        } else {
                            Timber.d("Ignoring stale onPageFinished callback: callback=$callbackUrl current=$currentUrl")
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?, handler: SslErrorHandler?, error: SslError?
                ) {
                    val failingUrl = error?.url ?: view?.url ?: ""
                    val isAllowedRouterHost = SecurityUtils.isPrivateNetworkOrRouterHost(failingUrl, currentTargetRouterIp)
                    if (isAllowedRouterHost) {
                        Timber.w("Proceeding with self-signed SSL certificate for authorized router endpoint: primaryError=${error?.primaryError} host=$failingUrl")
                        handler?.proceed()
                    } else {
                        Timber.e("REJECTING insecure SSL certificate for external/unverified host: primaryError=${error?.primaryError} host=$failingUrl")
                        handler?.cancel()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Timber.e("WebView error: ${error?.description} code: ${error?.errorCode}")
                    if (request?.isForMainFrame == true && view != null) {
                        if (error?.errorCode == WebViewClient.ERROR_UNKNOWN || error?.description?.contains("net::ERR_ABORTED", ignoreCase = true) == true) {
                            Timber.d("Ignoring ERR_ABORTED for main frame")
                            return
                        }
                        val callbackUrl = request?.url?.toString().orEmpty()
                        val currentUrl = view.url.orEmpty()
                        // Ignore late errors from a navigation that is no longer current.
                        if (callbackUrl.isBlank() || callbackUrl == currentUrl) {
                            val nav = activeNavigations[view]
                            if (nav != null && nav.deferred.isActive) {
                                val errMsg = error?.description?.toString() ?: "فشل تحميل الصفحة"
                                nav.deferred.completeExceptionally(Exception(errMsg))
                            }
                        } else {
                            Timber.d("Ignoring stale WebView error: callback=$callbackUrl current=$currentUrl")
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }
        val action = intent.action
        Timber.d("onStartCommand action: $action")

        if (action == ACTION_START && testJob?.isActive != true && !isActiveWifiConnectedNow()) {
            val errorMessage = "اتصل بشبكة Wi-Fi قبل بدء عملية الاختبار"
            _serviceState.value = ServiceState(
                status = "LOAD_ERROR",
                sessionState = com.example.domain.model.TestSessionState.FAILED,
                error = errorMessage
            )
            Timber.e("Rejected ACTION_START because the active Android network is not Wi-Fi")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = false),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start foreground with custom view, falling back to standard view")
                    try {
                        startForeground(
                            NotificationHelper.NOTIFICATION_ID,
                            notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = true),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } catch (firstLevelEx: Exception) {
                        Timber.e(firstLevelEx, "Critical: Start foreground failed even with standard layout")
                    }
                }
            } else {
                try {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = false)
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start foreground with custom view, falling back to standard view")
                    try {
                        startForeground(
                            NotificationHelper.NOTIFICATION_ID,
                            notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = true)
                        )
                    } catch (secondLevelEx: Exception) {
                        Timber.e(secondLevelEx, "Critical: Start foreground failed even with standard layout on older SDK")
                    }
                }
            }
        }

        when (action) {
            ACTION_START -> {
                // ACTION_START may be delivered more than once while the UI is navigating
                // or the service is being recreated. Never cancel/replace an active test
                // silently; the current test owns its queue and results until it terminates.
                if (testJob?.isActive == true) {
                    Timber.w("Ignoring duplicate ACTION_START because a test job is already active; status=${_serviceState.value.status}")
                    notificationHelper.updateNotification(_serviceState.value)
                    return START_NOT_STICKY
                }
                val routerId = intent.getLongExtra(EXTRA_ROUTER_ID, -1L)
                val cardList = intent.getStringArrayListExtra(EXTRA_CARD_LIST) ?: emptyList()
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 500L)
                startTestLoop(routerId, cardList, delayMs)
            }
            ACTION_PAUSE -> {
                _serviceState.update { it.copy(isPaused = true, status = "PAUSED", sessionState = com.example.domain.model.TestSessionState.PAUSED) }
                notificationHelper.updateNotification(_serviceState.value)
            }
            ACTION_RESUME -> {
                // Snapshot connection once so the visible state, session state, and message
                // cannot contradict one another if Android changes the default route mid-update.
                val wifiConnected = isActiveWifiConnectedNow()
                _serviceState.update { state ->
                    state.copy(
                        isPaused = false,
                        status = if (wifiConnected) "RUNNING" else "WAITING_WIFI",
                        sessionState = if (wifiConnected) com.example.domain.model.TestSessionState.RUNNING else com.example.domain.model.TestSessionState.PAUSED,
                        error = if (wifiConnected && state.error == wifiWaitMessage) null else if (!wifiConnected) wifiWaitMessage else state.error
                    )
                }
                notificationHelper.updateNotification(_serviceState.value)
            }
            ACTION_CANCEL -> {
                val pendingRetry = retryDeferred
                if (pendingRetry != null && pendingRetry.isActive) {
                    pendingRetry.complete(false)
                } else {
                    cancelTest()
                }
            }
            ACTION_RETRY_LOAD -> {
                retryDeferred?.complete(true)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun loadUrlWithGeneration(
        webView: WebView,
        url: String,
        timeoutMs: Long = 15000L
    ): Boolean {
        while (true) {
            awaitActiveWifi("loading router page")
            val generationAtStart = wifiLossGeneration.get()
            val loaded = loadUrlWithGenerationOnce(webView, url, timeoutMs)
            if (loaded) return true
            if (!wasWifiInterruptedSince(generationAtStart)) return false
            Timber.w("Navigation to $url overlapped a Wi-Fi interruption; waiting and retrying the same URL")
            awaitActiveWifi("retrying router page after Wi-Fi recovery")
        }
    }

    private suspend fun loadUrlWithGenerationOnce(
        webView: WebView,
        url: String,
        timeoutMs: Long = 15000L
    ): Boolean = withContext(Dispatchers.Main) {
        val gen = navigationGenerationSeq.incrementAndGet()
        val def = CompletableDeferred<Unit>()
        val token = NavigationToken(gen, def, url)

        // Retire the previous token BEFORE stopLoading(). Its late callbacks then have no
        // active token to complete, and replacing it cannot cancel the old worker coroutine.
        activeNavigations[webView]?.let { previous ->
            activeNavigations.remove(webView, previous)
            if (previous.deferred.isActive) {
                previous.deferred.completeExceptionally(
                    NavigationSupersededException(
                        "Navigation generation ${previous.generation} was superseded by generation $gen"
                    )
                )
            }
        }
        try {
            webView.stopLoading()
            activeNavigations[webView] = token
            webView.loadUrl(url)
            kotlinx.coroutines.withTimeout(timeoutMs) { def.await() }
            true
        } catch (e: TimeoutCancellationException) {
            // Distinguish our navigation deadline from cancellation of the actual test job.
            // If the parent was cancelled at the same time, ensureActive rethrows it.
            currentCoroutineContext().ensureActive()
            Timber.w("Navigation timed out after ${timeoutMs}ms for $url (generation=$gen)")
            false
        } catch (e: CancellationException) {
            // CompletableDeferred cancellation can originate from a superseded navigation.
            // Only propagate when the caller/test coroutine itself has been cancelled.
            currentCoroutineContext().ensureActive()
            Timber.w("Navigation wait was cancelled independently of the test job for $url (generation=$gen); treating it as recoverable")
            false
        } catch (e: Exception) {
            // A portal/WebView navigation error is recoverable; real parent cancellation is not.
            currentCoroutineContext().ensureActive()
            Timber.w("Navigation error for $url (generation=$gen): ${e.message}")
            false
        } finally {
            // Do not remove a newer token installed by a concurrent recovery/navigation.
            activeNavigations.remove(webView, token)
        }
    }

    suspend fun runLogoutPreflight(
        primaryWebView: WebView,
        router: RouterProfileEntity,
        strategy: RouterTestStrategy,
        maxRetries: Int = 3
    ): LogoutPreflightResult = withContext(Dispatchers.Main) {
        val url = router.getFullLoginUrl()
        Timber.d("[Preflight] Initiating mandatory preflight check on $url (${strategy.strategyName})")

        var lastDetectedState = PreflightSessionState.UNKNOWN_PAGE

        for (attempt in 1..maxRetries) {
            awaitActiveWifi("router preflight")
            if (testJob?.isActive != true) {
                return@withContext LogoutPreflightResult.Error(
                    "تم إلغاء عملية الفحص قبل اكتمال التحقق الأولي",
                    PreflightSessionState.UNKNOWN_PAGE
                )
            }

            // MOTASEM-specific critical rule: inspect the currently displayed page BEFORE
            // navigating to /login. The previous implementation always loaded /login first,
            // which could destroy the authenticated status page and make a subsequent fresh
            // login page look like proof that logout had succeeded. After a successful card,
            // we must inspect the redirect/status DOM and send its real logout form first.
            val isMotasem = strategy === MotasemTestStrategy
            val currentUrl = primaryWebView.url.orEmpty()
            val isBlankPage = currentUrl.isBlank() || currentUrl.equals("about:blank", ignoreCase = true)
            val shouldInspectCurrentPage = isMotasem && !isBlankPage

            if (shouldInspectCurrentPage) {
                lastDetectedState = strategy.detectPreflightState(router, primaryWebView) { js ->
                    evaluateJsSafely(primaryWebView, js)
                }
                Timber.d("[Preflight] Existing MOTASEM page state before navigation (attempt $attempt): $lastDetectedState; url=$currentUrl")

                // Do not overwrite an unrecognized, already-loaded portal page. It may be an
                // intermediate response needed to complete the session transition. Retry its
                // classification and fail safely if it remains unknown.
                if (lastDetectedState == PreflightSessionState.UNKNOWN_PAGE) {
                    if (attempt < maxRetries) {
                        delay(350L)
                        continue
                    }
                    return@withContext LogoutPreflightResult.Error(
                        "تعذر تحديد حالة صفحة بوابة معتصم الحالية؛ لم تتم إعادة تحميلها حتى لا نفقد دليل حالة الجلسة",
                        PreflightSessionState.UNKNOWN_PAGE
                    )
                }

                // A detected browser network-error page may be recovered by navigating to the
                // portal's configured login URL. Do not do this for a status/redirect/logout page.
                if (lastDetectedState == PreflightSessionState.NETWORK_ERROR) {
                    val recoveredLoad = loadUrlWithGeneration(primaryWebView, url, timeoutMs = 15000L)
                    if (!recoveredLoad) {
                        if (attempt == maxRetries) {
                            return@withContext LogoutPreflightResult.Error(
                                "فشل الاتصال ببوابة الراوتر (${router.name}): تعذر تحميل الصفحة بعد $maxRetries محاولات",
                                PreflightSessionState.NETWORK_ERROR
                            )
                        }
                        delay(700L)
                        continue
                    }
                    delay(300L)
                    lastDetectedState = strategy.detectPreflightState(router, primaryWebView) { js ->
                        evaluateJsSafely(primaryWebView, js)
                    }
                }
            } else {
                // Other strategies retain their existing preflight behavior. For MOTASEM,
                // only a genuinely blank WebView is navigated immediately to the login URL.
                val isLoaded = loadUrlWithGeneration(primaryWebView, url, timeoutMs = 15000L)
                if (!isLoaded) {
                    Timber.w("[Preflight] Portal load failed on attempt $attempt/$maxRetries")
                    if (attempt == maxRetries) {
                        return@withContext LogoutPreflightResult.Error(
                            "فشل الاتصال ببوابة الراوتر (${router.name}): تعذر تحميل الصفحة بعد $maxRetries محاولات",
                            PreflightSessionState.NETWORK_ERROR
                        )
                    }
                    delay(1000L)
                    continue
                }

                delay(300L) // DOM settle
                lastDetectedState = strategy.detectPreflightState(router, primaryWebView) { js ->
                    evaluateJsSafely(primaryWebView, js)
                }
            }
            Timber.d("[Preflight] Attempt $attempt state: $lastDetectedState")

            when (lastDetectedState) {
                PreflightSessionState.LOGIN_PAGE_READY -> {
                    Timber.d("[Preflight] Login page is confirmed fresh and ready. Preflight complete.")
                    return@withContext LogoutPreflightResult.Ready
                }

                PreflightSessionState.REDIRECTING -> {
                    Timber.d("[Preflight] Portal is redirecting, waiting for redirect to complete...")
                    val redirectStart = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - redirectStart < 7000L && testJob?.isActive == true) {
                        delay(350L)
                        val recheck = strategy.detectPreflightState(router, primaryWebView) { js ->
                            evaluateJsSafely(primaryWebView, js)
                        }
                        if (recheck != PreflightSessionState.REDIRECTING) {
                            lastDetectedState = recheck
                            break
                        }
                    }
                    if (lastDetectedState == PreflightSessionState.LOGIN_PAGE_READY) {
                        return@withContext LogoutPreflightResult.Ready
                    }
                    if (lastDetectedState != PreflightSessionState.ACTIVE_SESSION) {
                        delay(500L)
                        continue
                    }
                }

                PreflightSessionState.ACTIVE_SESSION -> {
                    Timber.w("[Preflight] Active previous session detected! Performing actual logout before testing...")

                    val logoutDispatched = strategy.executePortalLogout(router, primaryWebView) { js ->
                        evaluateJsSafely(primaryWebView, js)
                    }

                    if (!logoutDispatched) {
                        Timber.e("[Preflight] Logout request was not dispatched on attempt $attempt/$maxRetries")
                        if (strategy === MotasemTestStrategy && attempt < maxRetries) {
                            delay(700L)
                            continue
                        }
                        return@withContext LogoutPreflightResult.Error(
                            if (strategy === MotasemTestStrategy)
                                "تعذر إرسال طلب تسجيل الخروج الفعلي في بوابة معتصم بعد $maxRetries محاولات"
                            else
                                "تعذر العثور على زر تسجيل الخروج الفعلي في بوابة الراوتر (${router.name})",
                            PreflightSessionState.ACTIVE_SESSION
                        )
                    }

                    // 4. Verification of session termination using cancellable polling (A4)
                    val verifyStart = SystemClock.elapsedRealtime()
                    val maxWaitMs = 12000L
                    var logoutConfirmed = false
                    delay(500L)

                    while (SystemClock.elapsedRealtime() - verifyStart < maxWaitMs && testJob?.isActive == true) {
                        val state = strategy.detectPreflightState(router, primaryWebView) { js ->
                            evaluateJsSafely(primaryWebView, js)
                        }

                        if (state == PreflightSessionState.LOGIN_PAGE_READY) {
                            logoutConfirmed = true
                            break
                        }
                        if (state == PreflightSessionState.LOGOUT_IN_PROGRESS &&
                            strategy === MotasemTestStrategy &&
                            advanceMotasemLogoutLanding(primaryWebView, strategy, router)) {
                            logoutConfirmed = true
                            break
                        }

                        // Try reloading clean login URL if no longer on active session
                        if (SystemClock.elapsedRealtime() - verifyStart > 3500L && state != PreflightSessionState.ACTIVE_SESSION) {
                            loadUrlWithGeneration(primaryWebView, url, timeoutMs = 6000L)
                            delay(300L)
                            val stateAfterReload = strategy.detectPreflightState(router, primaryWebView) { js ->
                                evaluateJsSafely(primaryWebView, js)
                            }
                            if (stateAfterReload == PreflightSessionState.LOGIN_PAGE_READY) {
                                logoutConfirmed = true
                                break
                            }
                        }

                        delay(300L)
                    }

                    if (logoutConfirmed) {
                        Timber.d("[Preflight] Session termination successfully confirmed and verified.")
                        return@withContext LogoutPreflightResult.Ready
                    } else {
                        Timber.e("[Preflight] Session termination could not be confirmed within timeout on attempt $attempt/$maxRetries")
                        if (strategy === MotasemTestStrategy && attempt < maxRetries) {
                            delay(700L)
                            continue
                        }
                        return@withContext LogoutPreflightResult.Error(
                            if (strategy === MotasemTestStrategy)
                                "تعذر تأكيد تسجيل الخروج وانتهاء الجلسة السابقة في بوابة معتصم بعد $maxRetries محاولات"
                            else
                                "تعذر تأكيد تسجيل الخروج وانتهاء الجلسة السابقة في بوابة الراوتر بعد محاولة الخروج",
                            PreflightSessionState.ACTIVE_SESSION
                        )
                    }
                }

                PreflightSessionState.NETWORK_ERROR -> {
                    Timber.w("[Preflight] Network error detected on attempt $attempt")
                    if (attempt == maxRetries) {
                        return@withContext LogoutPreflightResult.Error(
                            "خطأ في الشبكة: تعذر الوصول إلى بوابة الراوتر (${router.name})",
                            PreflightSessionState.NETWORK_ERROR
                        )
                    }
                    delay(1000L)
                }

                PreflightSessionState.LOGOUT_IN_PROGRESS -> {
                    // The captured Motasem logout landing page is not yet the credential
                    // page: it contains a form[name="login"] but no username field. Advance
                    // that portal-owned form and wait for the actual login form instead of
                    // reloading the same landing page repeatedly.
                    if (strategy === MotasemTestStrategy && advanceMotasemLogoutLanding(primaryWebView, strategy, router)) {
                        return@withContext LogoutPreflightResult.Ready
                    }
                    delay(500L)
                }

                PreflightSessionState.UNKNOWN_PAGE -> {
                    Timber.w("[Preflight] Unknown page state on attempt $attempt")
                    if (attempt == maxRetries) {
                        return@withContext LogoutPreflightResult.Error(
                            "حالة بوابة الراوتر غير معروفة، لا يمكن بدء الاختبار بأمان",
                            PreflightSessionState.UNKNOWN_PAGE
                        )
                    }
                    delay(1000L)
                }
            }
        }

        return@withContext LogoutPreflightResult.Error(
            "فشل التحقق الأولي من جاهزية البوابة بعد $maxRetries محاولات",
            lastDetectedState
        )
    }

    /**
     * Repeats portal preflight only when a detected Wi-Fi loss overlapped the attempt.
     * Real portal/credential-state errors while Wi-Fi remains stable are still returned.
     */
    private suspend fun runLogoutPreflightWithWifiRecovery(
        primaryWebView: WebView,
        router: RouterProfileEntity,
        strategy: RouterTestStrategy,
        maxRetries: Int = 3,
        knownWifiInterruption: Boolean = false
    ): LogoutPreflightResult {
        var recoveryRetriesRemaining = if (knownWifiInterruption) maxRetries else 0
        while (true) {
            awaitActiveWifi("router session validation")
            val generationAtStart = wifiLossGeneration.get()
            val result = runLogoutPreflight(primaryWebView, router, strategy, maxRetries)
            if (result is LogoutPreflightResult.Ready) return result
            if (wasWifiInterruptedSince(generationAtStart) || recoveryRetriesRemaining > 0) {
                if (recoveryRetriesRemaining > 0) recoveryRetriesRemaining--
                Timber.w("Portal preflight was interrupted by Wi-Fi loss or is recovering from a known loss; retrying after reconnection")
                awaitActiveWifi("retrying router session validation")
                delay(350L)
                continue
            }
            return result
        }
    }

    /**
     * Advances the exact post-logout landing page captured from Motasem Net to its real
     * credential-entry page. This is intentionally portal-specific and refuses to submit
     * arbitrary login forms or a form that already contains a visible username field.
     */
    private suspend fun advanceMotasemLogoutLanding(
        webView: WebView,
        strategy: RouterTestStrategy,
        router: RouterProfileEntity
    ): Boolean {
        val submitResult = evaluateJsSafely(
            webView,
            """
            (function() {
                try {
                    var text = (document.body && document.body.innerText) ? document.body.innerText : '';
                    var lowerText = text.toLowerCase();
                    var isLogoutLanding = lowerText.indexOf('للدخول الى الشبكة مرة اخرى') !== -1 ||
                        lowerText.indexOf('للدخول إلى الشبكة مرة أخرى') !== -1 ||
                        lowerText.indexOf('يرجى الضغط على تسجيل الدخول بالاسفل') !== -1 ||
                        lowerText.indexOf('يرجى الضغط على تسجيل الدخول بالأسفل') !== -1;
                    if (!isLogoutLanding) return 'not_motasem_logout_landing';

                    var form = document.querySelector('form[name="login"]');
                    if (!form) return 'login_form_missing';
                    var visibleUsername = form.querySelector('input[name="username"]:not([type="hidden"]), #username');
                    if (visibleUsername) return 'credential_page_already_present';

                    var action = (form.action || form.getAttribute('action') || '').toLowerCase();
                    if (action.indexOf('/login') === -1) return 'refused_unverified_login_action';

                    HTMLFormElement.prototype.submit.call(form);
                    return 'submitted_post_logout_login_form';
                } catch (e) {
                    return 'post_logout_login_error:' + (e && e.message ? e.message : String(e));
                }
            })();
            """.trimIndent()
        )
        if (submitResult != "submitted_post_logout_login_form") {
            Timber.d("[Motasem] Post-logout landing was not advanced: $submitResult")
            return false
        }

        val waitStart = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - waitStart < 8000L && testJob?.isActive == true) {
            delay(250L)
            when (strategy.detectPreflightState(router, webView) { js -> evaluateJsSafely(webView, js) }) {
                PreflightSessionState.LOGIN_PAGE_READY -> {
                    Timber.d("[Motasem] Post-logout landing advanced to the real login page.")
                    return true
                }
                PreflightSessionState.NETWORK_ERROR -> return false
                else -> Unit
            }
        }
        Timber.w("[Motasem] Timed out waiting for the login page after submitting the post-logout form.")
        return false
    }

    private suspend fun ensureFreshLoginPage(
        webView: WebView,
        router: RouterProfileEntity,
        strategy: RouterTestStrategy,
        maxWaitMs: Long = PortalTiming.MIN_PAGE_READY_TIMEOUT_MS
    ): Boolean {
        // Navigate with a fresh generation token to ensure clean DOM
        loadUrlWithGeneration(webView, router.getFullLoginUrl(), timeoutMs = maxWaitMs)

        // Purge stale inputs and DOM alerts from previous card
        val cleanJs = """
        (function() {
            try {
                var inputs = document.querySelectorAll('input[type="text"], input[type="password"], #username, #uname, #password');
                for (var i = 0; i < inputs.length; i++) { inputs[i].value = ''; }
                var oldAlerts = document.querySelectorAll('.alert, .error, .alert-back, #danger');
                for (var j = 0; j < oldAlerts.length; j++) { oldAlerts[j].style.display = 'none'; }
            } catch(e) {}
        })();
        """.trimIndent()
        evaluateJsSafely(webView, cleanJs)

        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < maxWaitMs) {
            if (strategy.verifyFreshLoginPage(router, webView) { js -> evaluateJsSafely(webView, js) }) {
                return true
            }
            delay(150L)
        }
        return strategy.verifyFreshLoginPage(router, webView) { js -> evaluateJsSafely(webView, js) }
    }

    suspend fun ensureFreshLoginPageWithRecovery(
        webView: WebView,
        router: RouterProfileEntity,
        strategy: RouterTestStrategy,
        maxRetries: Int = 3,
        perAttemptTimeoutMs: Long = 4000L
    ): Boolean {
        val configuredPageWait = try { appPreferences.pageLoadDelay.first() } catch (_: Exception) { 2000L }
        val effectiveWait = maxOf(perAttemptTimeoutMs, PortalTiming.pageReadyTimeout(configuredPageWait))
        var attempt = 1
        while (attempt <= maxRetries) {
            awaitActiveWifi("preparing the fresh router login page")
            val generationAtStart = wifiLossGeneration.get()
            val isReady = ensureFreshLoginPage(webView, router, strategy, maxWaitMs = effectiveWait)
            if (isReady) return true
            if (wasWifiInterruptedSince(generationAtStart)) {
                Timber.w("Login-page recovery overlapped a Wi-Fi interruption; not consuming a recovery attempt")
                awaitActiveWifi("recovering the same card's login page")
                delay(350L)
                continue
            }
            Timber.w("Fresh login page check failed on attempt $attempt/$maxRetries. Attempting recovery...")
            attempt++
            delay(500L)
        }
        return false
    }

    private fun triggerVibrationOnSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(500L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(500L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(500L)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to vibrate on success")
        }
    }

    private fun playSuccessSound() {
        try {
            val notificationUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to play success sound")
        }
    }

    private suspend fun evaluateJsSafely(webViewToUse: WebView?, js: String): String {
        if (webViewToUse == null) return "unknown"
        return kotlinx.coroutines.withTimeoutOrNull(5000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                webViewToUse.evaluateJavascript(js) { res ->
                    if (continuation.isActive) {
                        val cleanRes = res?.trim('"')?.trim() ?: "unknown"
                        continuation.resume(cleanRes)
                    }
                }
            }
        } ?: "unknown"
    }

    private suspend fun finalizeCardAttempt(
        attempt: CardAttempt,
        outcome: CardTestOutcome,
        router: RouterProfileEntity,
        strategy: RouterTestStrategy,
        webView: WebView,
        progressCounter: AtomicInteger,
        successCounter: AtomicInteger,
        failureCounter: AtomicInteger,
        isBlockedBySuccess: AtomicBoolean,
        stateMutex: Mutex,
        cardListSize: Int,
        delayMs: Long,
        lifecycleStateConsumer: (CardLifecycleState) -> Unit,
        abortCurrentRun: AtomicBoolean? = null
    ): CardLifecycleState {
        val isTechnicalOutcome = outcome.category == ResultCategory.TIMEOUT ||
            outcome.category == ResultCategory.NETWORK_ERROR ||
            outcome.category == ResultCategory.ENGINE_ERROR
        // Any non-success result is ambiguous if Wi-Fi was interrupted during this attempt.
        // Recheck at the lifecycle barrier (not just in the worker) so no late failure outcome
        // can be persisted or counted after an outage races the worker's first connectivity check.
        if (!outcome.isSuccess && wasWifiInterruptedSince(attempt.wifiGenerationAtStart)) {
            Timber.w("Refusing to finalize ${if (isTechnicalOutcome) "technical" else "non-success"} outcome for ${maskCard(attempt.cardCode)} because Wi-Fi was lost during the card; requesting same-card retry")
            lifecycleStateConsumer(CardLifecycleState.RETRY_CURRENT_CARD)
            return CardLifecycleState.RETRY_CURRENT_CARD
        }

        val attemptKey = "${attempt.sessionId}_${attempt.cardIndex}_${attempt.workerId}_${attempt.attemptSequence}"
        if (!finalizedAttempts.add(attemptKey)) {
            Timber.w("Duplicate finalization detected for $attemptKey. Ignoring duplicate callback.")
            return CardLifecycleState.READY_FOR_NEXT
        }

        val terminalState = when (outcome.state) {
            "Success" -> CardLifecycleState.SUCCESS
            "Timeout" -> CardLifecycleState.TIMEOUT
            "Network_Error" -> CardLifecycleState.NETWORK_ERROR
            "Engine_Error" -> CardLifecycleState.ENGINE_ERROR
            else -> CardLifecycleState.FAILURE
        }
        lifecycleStateConsumer(terminalState)

        var isAuthoritativeSuccess = false
        if (outcome.isSuccess) {
            isAuthoritativeSuccess = isBlockedBySuccess.compareAndSet(false, true)
            if (!isAuthoritativeSuccess) {
                Timber.w("Card ${maskCard(attempt.cardCode)} reported success, but discarded as false positive (another worker claimed success first)")
            }
        }

        // 1. Authoritative persistence in Room SQLite FIRST (Barrier Step 1 & 2)
        val structuredCategory = if (isAuthoritativeSuccess) ResultCategory.SUCCESS.name else outcome.category.name
        val structuredSubReason = if (isAuthoritativeSuccess) {
            if (outcome.isTwoDevicesSuccess) ResultSubReason.TWO_DEVICES_SUCCESS.name else ResultSubReason.NORMAL_LOGIN_SUCCESS.name
        } else outcome.subReason.name
        val structuredSuccessReason = if (isAuthoritativeSuccess) outcome.successReason else null

        withContext(Dispatchers.IO) {
            testResultRepository.insertResult(
                TestResultEntity(
                    sessionId = attempt.sessionId,
                    cardCode = attempt.cardCode,
                    routerId = router.id,
                    routerName = router.name,
                    state = if (isAuthoritativeSuccess) "Success" else outcome.state,
                    message = outcome.message,
                    durationMs = outcome.durationMs,
                    testedAt = System.currentTimeMillis(),
                    category = structuredCategory,
                    subReason = structuredSubReason,
                    successReason = structuredSuccessReason
                )
            )
        }

        // 2. Increment progress and update counters AFTER authoritative persistence (Barrier Step 3)
        val currentProgress = progressCounter.incrementAndGet()
        if (isAuthoritativeSuccess) {
            val currentSuccess = successCounter.incrementAndGet()
            stateMutex.withLock {
                _serviceState.update {
                    it.copy(
                        progress = currentProgress,
                        successCount = currentSuccess
                    )
                }
                notificationHelper.showResultNotification(attempt.cardCode, true)
            }
            // Real vibration & audio feedback on confirmed success, respecting settings & not repeating
            try {
                if (appPreferences.vibrateOnSuccess.first()) {
                    triggerVibrationOnSuccess()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error triggering vibrateOnSuccess")
            }
            try {
                if (appPreferences.soundOnSuccess.first()) {
                    playSuccessSound()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error playing soundOnSuccess")
            }
        } else {
            // Technical errors (TIMEOUT, NETWORK_ERROR, ENGINE_ERROR) are NOT card failures
            val isTechnicalError = outcome.category == ResultCategory.TIMEOUT ||
                    outcome.category == ResultCategory.NETWORK_ERROR ||
                    outcome.category == ResultCategory.ENGINE_ERROR
            val currentFailure = if (!isTechnicalError) failureCounter.incrementAndGet() else failureCounter.get()
            stateMutex.withLock {
                _serviceState.update {
                    it.copy(
                        progress = currentProgress,
                        failureCount = currentFailure
                    )
                }
            }
        }

        val now = SystemClock.elapsedRealtime()
        val lastPost = lastNotificationUpdateTime.get()
        if (now - lastPost >= 800L || currentProgress >= cardListSize || isAuthoritativeSuccess) {
            lastNotificationUpdateTime.set(now)
            notificationHelper.updateNotification(_serviceState.value)
        }

        val shouldSyncSession = isAuthoritativeSuccess || (currentProgress % 10 == 0) || (currentProgress >= cardListSize)
        if (shouldSyncSession) {
            withContext(Dispatchers.IO) {
                sessionRepository.updateCounts(
                    sessionId = attempt.sessionId,
                    successCount = successCounter.get(),
                    failureCount = failureCounter.get()
                )
            }
        }

        // 3. Clean session transition & logout (Barrier Step 5 & 6)
        if (isAuthoritativeSuccess) {
            if (!outcome.isTwoDevicesSuccess) {
                lifecycleStateConsumer(CardLifecycleState.LOGOUT_REQUESTED)
                if (strategy === MotasemTestStrategy) {
                    // Motasem's captured status page routes its button through openLogout(),
                    // which may open an unsupported named popup. Reuse the actual form and
                    // verify the portal reaches a clean login page before the next card.
                    Timber.d("Motasem success: requesting and verifying portal logout before next card...")
                    delay(350L)
                    val logoutResult = runLogoutPreflightWithWifiRecovery(
                        webView, router, strategy, maxRetries = 3,
                        knownWifiInterruption = wasWifiInterruptedSince(attempt.wifiGenerationAtStart)
                    )
                    if (logoutResult !is LogoutPreflightResult.Ready) {
                        val logoutError = (logoutResult as? LogoutPreflightResult.Error)?.message
                            ?: "تعذر تأكيد تسجيل الخروج من بوابة معتصم"
                        Timber.e("Motasem verified logout barrier failed: $logoutError")
                        abortCurrentRun?.set(true)
                        _serviceState.update {
                            it.copy(
                                status = "LOAD_ERROR",
                                sessionState = com.example.domain.model.TestSessionState.FAILED,
                                error = logoutError
                            )
                        }
                        notificationHelper.updateNotification(_serviceState.value)
                        // Release waiting workers only after marking the entire run aborted.
                        isBlockedBySuccess.set(false)
                        lifecycleStateConsumer(CardLifecycleState.FAILED_BARRIER)
                        return CardLifecycleState.FAILED_BARRIER
                    }
                } else {
                    // Preserve the existing logout strategy/dispatch behavior for ALBASHA,
                    // BELLO and GENERIC; this remediation is specific to Motasem's portal.
                    Timber.d("Normal login success. Logging out before next card using ${strategy.strategyName} strategy...")
                    delay(500L)
                    val generationBeforeLogout = wifiLossGeneration.get()
                    withContext(Dispatchers.Main) {
                        val dispatched = strategy.executePortalLogout(router, webView) { js -> evaluateJsSafely(webView, js) }
                        if (!dispatched) Timber.w("${strategy.strategyName} logout dispatch was not confirmed; retaining existing recovery behavior")
                        delay(2000L)
                    }
                    if (wasWifiInterruptedSince(generationBeforeLogout) || wasWifiInterruptedSince(attempt.wifiGenerationAtStart)) {
                        awaitActiveWifi("resuming logout for the current card")
                        val recoveredLogout = runLogoutPreflightWithWifiRecovery(
                            webView, router, strategy, maxRetries = 3,
                            knownWifiInterruption = wasWifiInterruptedSince(attempt.wifiGenerationAtStart)
                        )
                        if (recoveredLogout !is LogoutPreflightResult.Ready) {
                            val message = (recoveredLogout as? LogoutPreflightResult.Error)?.message
                                ?: "تعذر استعادة جلسة الراوتر بعد عودة Wi-Fi"
                            abortCurrentRun?.set(true)
                            _serviceState.update { it.copy(status = "LOAD_ERROR", sessionState = com.example.domain.model.TestSessionState.FAILED, error = message) }
                            notificationHelper.updateNotification(_serviceState.value)
                            isBlockedBySuccess.set(false)
                            lifecycleStateConsumer(CardLifecycleState.FAILED_BARRIER)
                            return CardLifecycleState.FAILED_BARRIER
                        }
                    }
                }
                lifecycleStateConsumer(CardLifecycleState.LOGOUT_CONFIRMED)
            } else {
                Timber.d("Card is twoDevicesSuccess. No router logout needed (session was not established on router).")
            }
        }

        // 4. Resetting WebView & Confirming Fresh Login Page (Barrier Step 7)
        lifecycleStateConsumer(CardLifecycleState.RESETTING)
        var resetSuccess = ensureFreshLoginPageWithRecovery(webView, router, strategy)
        while (!resetSuccess && wasWifiInterruptedSince(attempt.wifiGenerationAtStart)) {
            Timber.w("Keeping ${maskCard(attempt.cardCode)} active: login-page reset failed after Wi-Fi loss; retrying the reset instead of aborting the run")
            awaitActiveWifi("restoring router session after Wi-Fi recovery")
            delay(700L)
            resetSuccess = ensureFreshLoginPageWithRecovery(webView, router, strategy)
        }

        // 5. Readiness confirmed (Barrier Step 8)
        if (!resetSuccess) {
            val barrierError = "تعذر استعادة صفحة الدخول بعد معالجة البطاقة؛ أوقف الاختبار لمنع اختبار البطاقات التالية في جلسة غير مؤكدة"
            Timber.e("Failed to restore fresh login page during card finalize. Suspending transition to READY_FOR_NEXT.")
            abortCurrentRun?.set(true)
            _serviceState.update {
                it.copy(status = "LOAD_ERROR", sessionState = com.example.domain.model.TestSessionState.FAILED, error = barrierError)
            }
            notificationHelper.updateNotification(_serviceState.value)
            isBlockedBySuccess.set(false)
            lifecycleStateConsumer(CardLifecycleState.FAILED_BARRIER)
            return CardLifecycleState.FAILED_BARRIER
        }

        // Only apply the configurable inter-card pause after all portal barriers have passed.
        // Reducing it can speed up the next card, but cannot skip logout/reset/result checks.
        if (delayMs > 0L) delay(delayMs)

        // Keep sibling workers blocked until logout AND login-page reset are confirmed.
        if (isAuthoritativeSuccess) isBlockedBySuccess.set(false)
        lifecycleStateConsumer(CardLifecycleState.READY_FOR_NEXT)
        return CardLifecycleState.READY_FOR_NEXT
    }

    private fun startTestLoop(routerId: Long, cardList: List<String>, delayMs: Long) {
        testJob = serviceScope.launch {
            try {
                if (!isActiveWifiConnectedNow()) {
                    val errorMessage = "اتصل بشبكة Wi-Fi قبل بدء عملية الاختبار"
                    _serviceState.update {
                        it.copy(
                            status = "LOAD_ERROR",
                            sessionState = com.example.domain.model.TestSessionState.FAILED,
                            error = errorMessage
                        )
                    }
                    Timber.e("startTestLoop rejected: device is not connected to active Wi-Fi")
                    stopSelf()
                    return@launch
                }
                val enablePreload = appPreferences.enablePreload.first()
                val requestedPoolSize = if (enablePreload) appPreferences.threadCount.first() else 1
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val isLowRam = activityManager?.isLowRamDevice == true
                val maxSafePool = if (isLowRam) 1 else 3
                val poolSize = requestedPoolSize.coerceIn(1, maxSafePool)
                // card_test_delay is a strategy result deadline, not a second inter-card sleep.
                // A zero/sentinel delay keeps the run fast after the lifecycle barrier is verified.
                val effectiveDelay = if (delayMs > 0L && delayMs != 1200L) delayMs else 0L
                
                val router = withContext(Dispatchers.IO) {
                    routerRepository.getById(routerId)
                } ?: run {
                    _serviceState.update { it.copy(status = "LOAD_ERROR", sessionState = com.example.domain.model.TestSessionState.FAILED, error = "Router profile not found") }
                    return@launch
                }
                currentTargetRouterIp = router.ip
                val strategy = RouterStrategyFactory.getStrategy(router)
                // MOTASEM is a stateful captive portal: every successful card requires a
                // confirmed logout and a fresh login page before the next card. Multiple
                // WebViews on the same device share the router/client session and can race
                // each other's logout/reset flow, causing the whole run to abort. Keep this
                // strategy sequential without changing concurrency settings for other routers.
                val effectivePoolSize = if (strategy === MotasemTestStrategy) 1 else poolSize
                if (effectivePoolSize != poolSize) {
                    Timber.w("MOTASEM requires sequential card lifecycle; requested pool=$poolSize, effective pool=$effectivePoolSize")
                }

                val sessionId = withContext(Dispatchers.IO) {
                    sessionRepository.createSession(routerId, router.name)
                }
                currentSessionId = sessionId

                _serviceState.update {
                    ServiceState(
                        total = cardList.size,
                        status = "RUNNING",
                        sessionState = com.example.domain.model.TestSessionState.RUNNING,
                        progress = 0,
                    )
                }

                startScreenshotLoop()
                
                val url = router.getFullLoginUrl()

                // Initialize primary WebView for mandatory preflight
                withContext(Dispatchers.Main) {
                    webViewPool.forEach { 
                        try {
                            it.stopLoading()
                            it.destroy()
                        } catch (_: Throwable) {}
                    }
                    webViewPool.clear()
                    activeNavigations.clear()
                    
                    val primaryWv = createWebViewInstance()
                    webViewPool.add(primaryWv)
                }

                // Mandatory centralized preflight. If connectivity disappears during a portal
                // transition, keep this session alive and rerun preflight after Wi-Fi returns.
                var preflightResult: LogoutPreflightResult
                while (true) {
                    awaitActiveWifi("initial router validation")
                    val preflightGeneration = wifiLossGeneration.get()
                    preflightResult = runLogoutPreflight(webViewPool[0], router, strategy)
                    if (preflightResult is LogoutPreflightResult.Ready) break
                    if (wasWifiInterruptedSince(preflightGeneration)) {
                        Timber.w("Initial preflight overlapped a Wi-Fi interruption; retrying preflight without starting a card")
                        awaitActiveWifi("retrying initial router validation")
                        delay(350L)
                        continue
                    }
                    break
                }
                if (preflightResult is LogoutPreflightResult.Error) {
                    Timber.e("Mandatory preflight failed: ${preflightResult.message}")
                    _serviceState.update {
                        it.copy(
                            status = "LOAD_ERROR",
                            sessionState = com.example.domain.model.TestSessionState.FAILED,
                            error = preflightResult.message
                        )
                    }
                    withContext(Dispatchers.IO) {
                        sessionRepository.markFinished(
                            sessionId = sessionId,
                            successCount = 0,
                            failureCount = 0
                        )
                    }
                    screenshotJob?.cancel()
                    notificationHelper.updateNotification(_serviceState.value)
                    stopSelf()
                    return@launch
                }

                // Once primary preflight confirmed login page readiness, initialize remaining pool WebViews if poolSize > 1
                if (effectivePoolSize > 1) {
                    withContext(Dispatchers.Main) {
                        for (i in 1 until effectivePoolSize) {
                            val wv = createWebViewInstance()
                            webViewPool.add(wv)
                            loadUrlWithGeneration(wv, url, timeoutMs = 15000L)
                            ensureFreshLoginPageWithRecovery(wv, router, strategy)
                        }
                    }
                }

                val cardQueue = Channel<String>(capacity = 64)
                val feederJob = launch {
                    try {
                        for (card in cardList) {
                            cardQueue.send(card)
                        }
                    } finally {
                        cardQueue.close()
                    }
                }

                val progressCounter = AtomicInteger(0)
                val successCounter = AtomicInteger(0)
                val failureCounter = AtomicInteger(0)
                val isBlockedBySuccess = AtomicBoolean(false)
                // A failed logout/readiness barrier must stop every worker and must never
                // be reported as a completed run with unprocessed cards.
                val abortCurrentRun = AtomicBoolean(false)
                val stateMutex = Mutex()
                val isRelogging = AtomicBoolean(false)

                val workers = webViewPool.mapIndexed { wvIndex, webView ->
                    launch {
                        val attemptSeq = AtomicInteger(0)
                        var cardIndex = 0

                        for (rawCard in cardQueue) {
                            if (abortCurrentRun.get()) break
                            val currentCard = rawCard
                            cardIndex++
                            var retryAfterWifiLoss = false

                            // Retry an interrupted card inside this loop. Replacing the next
                            // rawCard with a retry would silently drop that rawCard from the
                            // Channel and leave progress permanently below total.
                            while (true) {
                            if (abortCurrentRun.get()) break
                            var lifecycleState = CardLifecycleState.QUEUED

                            // Wi-Fi loss is a suspend condition, not a test cancellation. Wait
                            // before consuming any router-dependent step while keeping currentCard.
                            awaitActiveWifi("continuing current card ${maskCard(currentCard)}")
                            while (!abortCurrentRun.get() && (_serviceState.value.isPaused || isBlockedBySuccess.get() || isRelogging.get())) {
                                delay(500)
                            }
                            if (abortCurrentRun.get()) break

                            // Deterministic internal identity & generation token, including the
                            // active-Wi-Fi generation so a transient error cannot be persisted if
                            // connectivity disappears before the finalization barrier starts.
                            val wifiGenerationAtAttemptStart = wifiLossGeneration.get()
                            val currentGen = navigationGenerationSeq.incrementAndGet()
                            val attempt = CardAttempt(
                                sessionId = sessionId,
                                cardIndex = cardIndex,
                                cardCode = currentCard,
                                workerId = wvIndex,
                                attemptSequence = attemptSeq.incrementAndGet(),
                                generationToken = currentGen,
                                wifiGenerationAtStart = wifiGenerationAtAttemptStart
                            )

                            // Update active card display ONLY (do NOT increment progress yet!)
                            stateMutex.withLock {
                                _serviceState.update {
                                    it.copy(currentCard = currentCard)
                                }
                            }

                            currentWebViewIndex = wvIndex // For screenshots
                            capturedAlerts[webView] = Pair(currentGen, "")

                            lifecycleState = CardLifecycleState.NAVIGATING
                            val startTime = SystemClock.elapsedRealtime()
                            var wasInterrupted = false

                            // Verify actual login page readiness. If Wi-Fi disappeared during
                            // this navigation, keep this same card queued in the inner retry loop.
                            val isPageReady = ensureFreshLoginPageWithRecovery(webView, router, strategy)
                            if (!isPageReady && (retryAfterWifiLoss || wasWifiInterruptedSince(wifiGenerationAtAttemptStart))) {
                                retryAfterWifiLoss = true
                                Timber.w("Login-page readiness overlapped Wi-Fi loss for ${maskCard(currentCard)}; waiting and retrying this same card")
                                awaitActiveWifi("restoring login page for current card")
                                delay(500L)
                                continue
                            }
                            if (!isPageReady) {
                                Timber.e("Login page not ready for card ${maskCard(currentCard)}. Aborting the run with a technical error; no subsequent cards may start.")
                                abortCurrentRun.set(true)
                                val pageReadyError = "صفحة الدخول غير جاهزة بعد محاولات الاستعادة؛ أُوقف الاختبار قبل تجربة بطاقات أخرى"
                                _serviceState.update {
                                    it.copy(
                                        status = "LOAD_ERROR",
                                        sessionState = com.example.domain.model.TestSessionState.FAILED,
                                        error = pageReadyError
                                    )
                                }
                                notificationHelper.updateNotification(_serviceState.value)
                                val duration = SystemClock.elapsedRealtime() - startTime
                                val errorOutcome = CardTestOutcome.networkError(
                                    message = "صفحة الدخول غير جاهزة بعد محاولات الاستعادة",
                                    durationMs = duration
                                )
                                finalizeCardAttempt(
                                    attempt = attempt,
                                    outcome = errorOutcome,
                                    router = router,
                                    strategy = strategy,
                                    webView = webView,
                                    progressCounter = progressCounter,
                                    successCounter = successCounter,
                                    failureCounter = failureCounter,
                                    isBlockedBySuccess = isBlockedBySuccess,
                                    stateMutex = stateMutex,
                                    cardListSize = cardList.size,
                                    delayMs = effectiveDelay,
                                    lifecycleStateConsumer = { st -> lifecycleState = st },
                                    abortCurrentRun = abortCurrentRun
                                )
                                // Safely suspend/stop worker, do NOT classify as card failure and do NOT test next card
                                break
                            }

                            lifecycleState = CardLifecycleState.LOGIN_PAGE_READY
                            // The transient interrupted attempt has now been discarded and the
                            // current card is about to be tried from a verified login page.
                            retryAfterWifiLoss = false

                            val outcome = try {
                                if (isBlockedBySuccess.get()) {
                                    wasInterrupted = true
                                    throw IllegalStateException("Interrupted by success")
                                }
                                
                                // Check for reload buttons ONLY on genuine network error pages
                                val checkReloadJs = """
                                    (function() {
                                        if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
                                        var isNetErr = (document.title || '').toLowerCase().indexOf('webpage not available') !== -1 ||
                                                       document.getElementById('main-frame-error') !== null;
                                        if (!isNetErr) return 'ok';
                                        var buttons = document.querySelectorAll('button, a, input[type="button"]');
                                        for (var i = 0; i < buttons.length; i++) {
                                            var text = (buttons[i].innerText || buttons[i].value || '').toLowerCase();
                                            if (text.indexOf('إعادة تحميل') !== -1 || text.indexOf('retry') !== -1 || text.indexOf('reload') !== -1) {
                                                buttons[i].click();
                                                return 'clicked_reload';
                                            }
                                        }
                                        return 'ok';
                                    })();
                                """.trimIndent()
                                val reloadStatus = evaluateJsSafely(webView, checkReloadJs)
                                if (reloadStatus == "clicked_reload") {
                                    Timber.d("Clicked reload button during test loop on error page, waiting for reload...")
                                    delay(300L)
                                    ensureFreshLoginPageWithRecovery(webView, router, strategy)
                                }
                                
                                val onRequiresGlobalRelogin: suspend () -> Unit = {
                                    if (isRelogging.compareAndSet(false, true)) {
                                        try {
                                            Timber.d("Global relogin triggered by a webview. Pausing all others...")
                                            delay(500)
                                            if (strategy === MotasemTestStrategy) {
                                                // MOTASEM must use the verified portal state machine. A fixed delay
                                                // followed by loading /login can leave the old session active and
                                                // incorrectly release the next card into an unknown session.
                                                val logoutResult = runLogoutPreflightWithWifiRecovery(
                                                    webView, router, strategy, maxRetries = 3,
                                                    knownWifiInterruption = wasWifiInterruptedSince(attempt.wifiGenerationAtStart)
                                                )
                                                if (logoutResult !is LogoutPreflightResult.Ready) {
                                                    val message = (logoutResult as? LogoutPreflightResult.Error)?.message
                                                        ?: "تعذر التحقق من جلسة معتصم قبل متابعة الاختبار"
                                                    abortCurrentRun.set(true)
                                                    _serviceState.update {
                                                        it.copy(status = "LOAD_ERROR", sessionState = com.example.domain.model.TestSessionState.FAILED, error = message)
                                                    }
                                                    notificationHelper.updateNotification(_serviceState.value)
                                                }
                                            } else {
                                                // Preserve the established behavior of non-MOTASEM strategies.
                                                strategy.executePortalLogout(router, webView) { js -> evaluateJsSafely(webView, js) }
                                                delay(2000)
                                                webViewPool.forEach { wv ->
                                                    loadUrlWithGeneration(wv, router.getFullLoginUrl(), timeoutMs = 15000L)
                                                }
                                                webViewPool.forEach { wv ->
                                                    ensureFreshLoginPageWithRecovery(wv, router, strategy)
                                                }
                                                delay(1000)
                                            }
                                            Timber.d("Global relogin flow finished; abort=${abortCurrentRun.get()}")
                                        } finally {
                                            isRelogging.set(false)
                                        }
                                    } else {
                                        while (isRelogging.get() && !abortCurrentRun.get()) { delay(250) }
                                    }
                                }
                                
                                lifecycleState = CardLifecycleState.SUBMITTING
                                lifecycleState = CardLifecycleState.WAITING_RESULT

                                strategy.testCardWithDetails(
                                    card = currentCard,
                                    router = router,
                                    webView = webView,
                                    evaluateJsSafely = { js -> evaluateJsSafely(webView, js) },
                                    pauseCondition = {
                                        awaitActiveWifi("inside card ${maskCard(currentCard)}")
                                        while (_serviceState.value.isPaused) { delay(500) }
                                        awaitActiveWifi("resuming card ${maskCard(currentCard)}")
                                    },
                                    isPreloaded = enablePreload,
                                    onRequiresGlobalRelogin = onRequiresGlobalRelogin,
                                    isBlockedBySuccess = { isBlockedBySuccess.get() },
                                    capturedAlert = {
                                        val alertPair = capturedAlerts[webView]
                                        if (alertPair != null && alertPair.first >= attempt.generationToken) alertPair.second else ""
                                    }
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // Preserve real user/service cancellation. A timeout caused by a
                                // slow or interrupted WebView is an outcome, not permission for
                                // this worker to vanish and make the entire run incomplete.
                                currentCoroutineContext().ensureActive()
                                when {
                                    wasWifiInterruptedSince(wifiGenerationAtAttemptStart) -> {
                                        Timber.w("Card ${maskCard(currentCard)} interrupted by Wi-Fi loss; it will be retried without recording a result")
                                        CardTestOutcome.networkError("انقطع اتصال Wi-Fi أثناء اختبار البطاقة؛ ستتم إعادة المحاولة", 0L)
                                    }
                                    e is TimeoutCancellationException -> CardTestOutcome.timeout(
                                        "انتهت مهلة الاستجابة أثناء اختبار البطاقة؛ لم يتم تخطي البطاقة",
                                        0L
                                    )
                                    else -> throw e
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Worker $wvIndex failed for card: ${maskCard(currentCard)}")
                                if (wasWifiInterruptedSince(wifiGenerationAtAttemptStart)) {
                                    CardTestOutcome.networkError("انقطع اتصال Wi-Fi أثناء اختبار البطاقة؛ ستتم إعادة المحاولة", 0L)
                                } else {
                                    CardTestOutcome.engineError(e.localizedMessage ?: "فشل في محرك الاختبار", 0L)
                                }
                            }
                            val duration = SystemClock.elapsedRealtime() - startTime
                            val finalOutcome = outcome.copy(durationMs = duration)

                            // If connectivity changed at any point during a non-success outcome,
                            // discard that transient outcome. Do not persist it, increment progress,
                            // count it as failure, or consume the next card. The inner loop keeps
                            // currentCard and retries from a clean login page after Wi-Fi returns.
                            if (!finalOutcome.isSuccess && wasWifiInterruptedSince(wifiGenerationAtAttemptStart)) {
                                retryAfterWifiLoss = true
                                Timber.w("Not finalizing ${maskCard(currentCard)} because its result is a transient technical outcome after Wi-Fi loss; waiting and retrying the same card")
                                awaitActiveWifi("retrying current card after connection recovery")
                                delay(500L)
                                // Reconcile router session state: if the submission reached the router before the drop,
                                // the session may already be authenticated!
                                val preflightState = strategy.detectPreflightState(router, webView) { js -> evaluateJsSafely(webView, js) }
                                Timber.d("Router state upon Wi-Fi recovery for ${maskCard(currentCard)}: $preflightState")
                                if (preflightState == PreflightSessionState.ACTIVE_SESSION) {
                                    Timber.i("Active session detected on router for ${maskCard(currentCard)} after Wi-Fi recovery! Reconciling as verified success.")
                                    val recoveredSuccess = CardTestOutcome.success(
                                        message = "تم تسجيل الدخول بنجاح وتأكيد الجلسة بعد استعادة اتصال Wi-Fi",
                                        durationMs = duration,
                                        successReason = "RECONCILED_ACTIVE_SESSION_AFTER_WIFI_RECOVERY"
                                    )
                                    lifecycleState = finalizeCardAttempt(
                                        attempt = attempt,
                                        outcome = recoveredSuccess,
                                        router = router,
                                        strategy = strategy,
                                        webView = webView,
                                        progressCounter = progressCounter,
                                        successCounter = successCounter,
                                        failureCounter = failureCounter,
                                        isBlockedBySuccess = isBlockedBySuccess,
                                        stateMutex = stateMutex,
                                        cardListSize = cardList.size,
                                        delayMs = effectiveDelay,
                                        lifecycleStateConsumer = { st -> lifecycleState = st },
                                        abortCurrentRun = abortCurrentRun
                                    )
                                    if (lifecycleState == CardLifecycleState.READY_FOR_NEXT) {
                                        break
                                    }
                                }
                                continue
                            }

                            // Check if interrupted by another worker claiming success
                            val attemptKey = "${attempt.sessionId}_${attempt.cardIndex}_${attempt.workerId}_${attempt.attemptSequence}"
                            if (finalOutcome.isSuccess && isBlockedBySuccess.get() && !finalizedAttempts.contains(attemptKey)) {
                                wasInterrupted = true
                            }
                            // Strategies may return an ordinary Failure outcome when another
                            // worker wins a success race. That is an interruption, not an
                            // invalid card, so requeue it without persisting a false failure.
                            if (!finalOutcome.isSuccess && isBlockedBySuccess.get() &&
                                finalOutcome.message.contains("مقاطعة بنجاح")) {
                                wasInterrupted = true
                            }

                            if (abortCurrentRun.get()) break

                            if (wasInterrupted) {
                                Timber.d("Card ${maskCard(currentCard)} was interrupted by another thread's success. Will retry later.")
                                while (isBlockedBySuccess.get() && !abortCurrentRun.get()) { delay(250) }
                                if (abortCurrentRun.get()) break
                                val retryPageReady = ensureFreshLoginPageWithRecovery(webView, router, strategy)
                                if (!retryPageReady) {
                                    abortCurrentRun.set(true)
                                    val recoveryError = "تعذر استعادة صفحة الدخول بعد مقاطعة البطاقة بسبب نجاح بطاقة أخرى"
                                    _serviceState.update { it.copy(status = "LOAD_ERROR", sessionState = com.example.domain.model.TestSessionState.FAILED, error = recoveryError) }
                                    notificationHelper.updateNotification(_serviceState.value)
                                    break
                                }
                                delay(effectiveDelay)
                                continue
                            }

                            // Strict terminal finalization barrier (authoritative result -> DB -> progress -> logout -> reset -> ready)
                            lifecycleState = finalizeCardAttempt(
                                attempt = attempt,
                                outcome = finalOutcome,
                                router = router,
                                strategy = strategy,
                                webView = webView,
                                progressCounter = progressCounter,
                                successCounter = successCounter,
                                failureCounter = failureCounter,
                                isBlockedBySuccess = isBlockedBySuccess,
                                stateMutex = stateMutex,
                                cardListSize = cardList.size,
                                delayMs = effectiveDelay,
                                lifecycleStateConsumer = { st -> lifecycleState = st },
                                abortCurrentRun = abortCurrentRun
                            )

                            // A Wi-Fi race detected inside the finalization barrier returns an
                            // explicit same-card retry state. It is not a failed card or an aborted run.
                            if (lifecycleState == CardLifecycleState.RETRY_CURRENT_CARD) {
                                retryAfterWifiLoss = true
                                awaitActiveWifi("retrying current card after finalization detected Wi-Fi loss")
                                delay(500L)
                                continue
                            }

                            // Terminal barrier rule: READY_FOR_NEXT is the ONLY state permitting consumption of the next card
                            if (lifecycleState != CardLifecycleState.READY_FOR_NEXT) {
                                Timber.e("Card ${maskCard(currentCard)} attempt $attempt suspended/failed lifecycle barrier with state $lifecycleState. Stopping worker safely.")
                                abortCurrentRun.set(true)
                                break
                            }
                            // This card is fully finalized and the login page is ready.
                            // Exit the retry loop so the outer Channel loop consumes the next
                            // original card. A `continue` above retries the SAME card.
                            break
                            }
                        }
                    }
                }

                workers.forEach { it.join() }
                feederJob.cancel()

                val processedCount = progressCounter.get()
                if (abortCurrentRun.get() || processedCount < cardList.size) {
                    val partialError = _serviceState.value.error
                        ?: "توقف الاختبار قبل إكمال جميع البطاقات: تمت معالجة $processedCount من ${cardList.size}. لم يتم إعلان الاكتمال لأن حاجز الجاهزية فشل."
                    _serviceState.update {
                        it.copy(
                            status = "LOAD_ERROR",
                            sessionState = com.example.domain.model.TestSessionState.FAILED,
                            error = partialError
                        )
                    }
                    withContext(Dispatchers.IO) {
                        sessionRepository.markFinished(
                            sessionId = sessionId,
                            successCount = successCounter.get(),
                            failureCount = failureCounter.get()
                        )
                    }
                    screenshotJob?.cancel()
                    notificationHelper.updateNotification(_serviceState.value)
                    Timber.e("Test run stopped safely with partial progress ($processedCount/${cardList.size}); never marking it DONE.")
                    stopSelf()
                    return@launch
                }

                _serviceState.update { it.copy(status = "COMPLETING", sessionState = com.example.domain.model.TestSessionState.COMPLETING) }
                withContext(Dispatchers.IO) {
                    sessionRepository.markFinished(
                        sessionId = sessionId,
                        successCount = successCounter.get(),
                        failureCount = failureCounter.get()
                    )
                }

                _serviceState.update { it.copy(status = "DONE", sessionState = com.example.domain.model.TestSessionState.COMPLETED) }
                notificationHelper.updateNotification(_serviceState.value)
                stopSelf()

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Error during test loop")
                _serviceState.update { it.copy(status = "LOAD_ERROR", sessionState = com.example.domain.model.TestSessionState.FAILED, error = e.localizedMessage) }
            }
        }
    }

    private suspend fun testCard(
        card: String,
        router: RouterProfileEntity,
        isPreloaded: Boolean,
        webViewToUse: WebView? = activeWebView,
        onRequiresGlobalRelogin: (suspend () -> Unit)? = null,
        isBlockedBySuccess: () -> Boolean = { false }
    ): Boolean {
        val wv = webViewToUse ?: activeWebView
        val strategy = RouterStrategyFactory.getStrategy(router)
        Timber.d("Testing card on router ${router.name} using strategy: ${strategy.strategyName}")
        return strategy.testCard(
            card = card,
            router = router,
            webView = wv,
            evaluateJsSafely = { js -> evaluateJsSafely(wv, js) },
            pauseCondition = { while (_serviceState.value.isPaused) { delay(500) } },
            isPreloaded = isPreloaded,
            onRequiresGlobalRelogin = onRequiresGlobalRelogin,
            isBlockedBySuccess = isBlockedBySuccess
        )
    }

    private fun startScreenshotLoop() {
        screenshotJob?.cancel()
        screenshotJob = serviceScope.launch {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@TestService)
            while (true) {
                val configuredScreenshotDelay = try { appPreferences.screenshotDelay.first() } catch (_: Exception) { 2000L }
                val delayMs = prefs.getString("screenshot_delay", null)?.toLongOrNull() ?: configuredScreenshotDelay
                delay(delayMs)
                val bitmap = captureScreenshot()
                if (bitmap != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                            val bytes = stream.toByteArray()
                            _serviceState.update { it.copy(screenshotBytes = bytes) }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to compress screenshot")
                        } finally {
                            try { bitmap.recycle() } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
    }

    private fun captureScreenshot(): Bitmap? {
        val view = activeWebView ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return try {
            val scale = 360f / w.coerceAtLeast(1)
            val targetWidth = 360
            val targetHeight = (h * scale).toInt().coerceIn(1, 800)
            
            val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun cancelTest() {
        currentTargetRouterIp = null
        testJob?.cancel()
        screenshotJob?.cancel()
        _serviceState.update { it.copy(status = "CANCELLED", sessionState = com.example.domain.model.TestSessionState.STOPPED) }
        notificationHelper.updateNotification(_serviceState.value)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        currentTargetRouterIp = null
        testJob?.cancel()
        screenshotJob?.cancel()
        serviceScope.cancel()
        serviceNetworkCallback?.let { callback ->
            try { serviceConnectivityManager?.unregisterNetworkCallback(callback) }
            catch (e: Exception) { Timber.w(e, "Failed to unregister service Wi-Fi callback") }
        }
        serviceNetworkCallback = null
        serviceConnectivityManager = null
        try {
            capturedAlerts.clear()
            activeNavigations.clear()
            finalizedAttempts.clear()
            webViewPool.forEach { 
                try {
                    it.stopLoading()
                    it.destroy()
                } catch (_: Throwable) {}
            }
            webViewPool.clear()
        } catch (e: Throwable) {
            Timber.e(e, "Error destroying webview pool in onDestroy")
        }
        // Keep the terminal DONE / CANCELLED / LOAD_ERROR state observable. A new ACTION_START
        // replaces it with RUNNING, so resetting here only hid the true terminal outcome.
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

