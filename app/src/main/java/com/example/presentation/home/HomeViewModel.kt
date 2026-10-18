package com.example.presentation.home

// View model decoded natively for UI sync
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.RouterProfileEntity
import com.example.data.local.preferences.AppPreferences
import com.example.domain.model.LogEntry
import com.example.domain.model.Statistics
import com.example.domain.repository.ISessionRepository
import com.example.domain.repository.ITestResultRepository
import com.example.domain.usecase.GenerateCardsUseCase
import com.example.domain.usecase.ManageRoutersUseCase
import com.example.presentation.common.BaseViewModel
import com.example.service.TestService
import com.example.util.WifiConnectivity
import com.example.data.mapper.TestResultMapper.toLogEntries
import com.example.data.mapper.TestResultMapper.toStatistics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val generateCardsUseCase: GenerateCardsUseCase,
    private val manageRoutersUseCase: ManageRoutersUseCase,
    private val sessionRepository: ISessionRepository,
    private val testResultRepository: ITestResultRepository,
    private val appPreferences: AppPreferences
) : BaseViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Testing : UiState()
        data class Finished(val message: String) : UiState()
        data class Error(val error: String) : UiState()
    }

    sealed class ConnectionState {
        object CONNECTED : ConnectionState()
        object DISCONNECTED : ConnectionState()
    }

    private val _selectedRouterId = MutableStateFlow<Long>(-1L)
    val selectedRouterId: StateFlow<Long> = _selectedRouterId.asStateFlow()

    val routers: StateFlow<List<RouterProfileEntity>> = manageRoutersUseCase.allRouters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastCardPrefix = appPreferences.lastCardPrefix
    val lastCardLength = appPreferences.lastCardLength
    val lastCardCount = appPreferences.lastCardCount
    val lastCharset = appPreferences.lastCharset
    val defaultRouterId = appPreferences.defaultRouterId

    private var lastObservedDefaultId: Long = -1L

    suspend fun getInitialSettings(): InitialSettings {
        val defRouter = defaultRouterId.first()
        val lastOpRouter = appPreferences.lastOperationRouterId.first()
        val effectiveRouter = if (defRouter > 0L) defRouter else (if (lastOpRouter > 0L) lastOpRouter else 0L)
        return InitialSettings(
            prefix = lastCardPrefix.first(),
            length = lastCardLength.first(),
            count = lastCardCount.first(),
            charset = lastCharset.first(),
            defaultRouterId = effectiveRouter
        )
    }

    data class InitialSettings(
        val prefix: String,
        val length: Int,
        val count: Int,
        val charset: String,
        val defaultRouterId: Long
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _statistics = MutableStateFlow(Statistics())
    val statistics: StateFlow<Statistics> = _statistics.asStateFlow()

    private val _startTestEvent = MutableSharedFlow<TestStartConfig>()
    val startTestEvent: SharedFlow<TestStartConfig> = _startTestEvent.asSharedFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        observeLatestSession()
        observeDefaultRouter()
    }

    private fun observeDefaultRouter() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                manageRoutersUseCase.allRouters,
                appPreferences.defaultRouterId
            ) { routerList, prefDefaultId ->
                Pair(routerList, prefDefaultId)
            }.collect { (routerList, prefDefaultId) ->
                if (routerList.isNotEmpty()) {
                    // Check persistent user preference first
                    val savedPrefId = if (prefDefaultId > 0L && routerList.any { it.id == prefDefaultId }) {
                        prefDefaultId
                    } else null

                    // If no preference saved, MOTASEM is the initial default only
                    val effectiveDefault = savedPrefId ?: run {
                        val motasem = routerList.find { it.strategyId.equals("motasem", ignoreCase = true) || it.name.contains("معتصم") }
                        motasem?.id ?: routerList.firstOrNull { it.isDefault }?.id ?: routerList.first().id
                    }

                    if (_selectedRouterId.value == -1L || !routerList.any { it.id == _selectedRouterId.value }) {
                        lastObservedDefaultId = effectiveDefault
                        _selectedRouterId.value = effectiveDefault
                    } else if (lastObservedDefaultId != effectiveDefault && savedPrefId != null) {
                        lastObservedDefaultId = effectiveDefault
                        _selectedRouterId.value = effectiveDefault
                    }
                }
            }
        }
    }

    private fun observeLatestSession() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                sessionRepository.allSessions,
                TestService.isRunning
            ) { sessions, isServiceRunning ->
                Pair(sessions, isServiceRunning)
            }.flatMapLatest { (sessions, isServiceRunning) ->
                val latest = sessions.firstOrNull()
                when {
                    latest == null -> flowOf(Triple(emptyList<LogEntry>(), Statistics(), UiState.Idle))
                    latest.isRunning && isServiceRunning -> testResultRepository.getResultsBySession(latest.id)
                        .map { results -> Triple(results.toLogEntries(), results.toStatistics(), UiState.Testing) }
                    else -> testResultRepository.getResultsBySession(latest.id)
                        .map { results ->
                            val isEn = java.util.Locale.getDefault().language.equals("en", ignoreCase = true)
                            val state = if (results.isNotEmpty()) UiState.Finished(if (isEn) "Scan completed" else "اكتمل الفحص") else UiState.Idle
                            Triple(results.toLogEntries(), results.toStatistics(), state)
                        }
                }
            }.collect { (logs, stats, state) ->
                _logEntries.value = logs
                _statistics.value = stats
                _uiState.value = state
            }
        }
    }

    fun clearLogs() {
        if (TestService.isRunning.value) {
            Timber.w("Cannot clear logs while test service is running")
            return
        }
        viewModelScope.launch {
            try {
                testResultRepository.deleteAll()
                sessionRepository.deleteAll()
                _logEntries.value = emptyList()
                _statistics.value = Statistics()
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear logs")
            }
        }
    }

    fun selectRouter(id: Long) {
        _selectedRouterId.value = id
        lastObservedDefaultId = id
        viewModelScope.launch {
            appPreferences.setDefaultRouterId(id)
            try {
                manageRoutersUseCase.setDefault(id)
            } catch (e: Exception) {
                Timber.w(e, "Could not update default router in database")
            }
        }
    }

    fun isServiceRunning(): StateFlow<Boolean> {
        return TestService.isRunning
    }

    fun startMonitoringConnection(context: Context) {
        if (connectivityManager != null) return
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refreshActiveNetwork() {
                _connectionState.value = if (WifiConnectivity.isActiveWifiConnected(context)) {
                    ConnectionState.CONNECTED
                } else {
                    ConnectionState.DISCONNECTED
                }
            }

            override fun onAvailable(network: Network) = refreshActiveNetwork()
            override fun onLost(network: Network) = refreshActiveNetwork()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshActiveNetwork()
        }
        networkCallback = callback
        try {
            // Monitor the active/default route, not any Wi-Fi network that happens to exist.
            // A Wi-Fi network in the background must not make the home banner say connected
            // while Android is actually routing traffic over cellular.
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to register default network callback")
        }

        _connectionState.value = if (WifiConnectivity.isActiveWifiConnected(context)) {
            ConnectionState.CONNECTED
        } else {
            ConnectionState.DISCONNECTED
        }
    }

    fun stopMonitoringConnection() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Throwable) {
                Timber.e(e, "Failed to unregister network callback")
            }
        }
        networkCallback = null
        connectivityManager = null
    }

    fun saveSettingsQuickly(prefix: String, length: Int, count: Int, charset: String, routerId: Long) {
        viewModelScope.launch {
            appPreferences.saveHomeSettings(prefix, length, count, charset, routerId)
        }
    }

    fun generateAndStart(
        context: Context,
        prefix: String,
        length: Int,
        count: Int,
        charset: String,
        delayMs: Long
    ) {
        val isEn = java.util.Locale.getDefault().language.equals("en", ignoreCase = true)
        if (!WifiConnectivity.isActiveWifiConnected(context)) {
            _connectionState.value = ConnectionState.DISCONNECTED
            viewModelScope.launch {
                _errorFlow.emit(if (isEn) "Connect to a Wi-Fi network before starting the test" else "اتصل بشبكة Wi-Fi قبل بدء عملية الاختبار")
            }
            return
        }
        var routerId = _selectedRouterId.value
        if (routerId == -1L) {
            val fallback = routers.value.firstOrNull { it.isDefault }?.id ?: routers.value.firstOrNull()?.id ?: 1L
            routerId = fallback
            _selectedRouterId.value = fallback
        }
        if (routerId == -1L) {
            viewModelScope.launch {
                _errorFlow.emit(if (isEn) "Please select a router first" else "الرجاء اختيار راوتر أولاً")
            }
            return
        }

        viewModelScope.launch {
            try {
                // Save settings locally
                appPreferences.saveLastOperationSnapshot(prefix, length, count, charset, routerId)

                // Generate
                val cards = generateCardsUseCase(prefix, length, count, charset)
                if (cards.isEmpty()) {
                    _errorFlow.emit(if (isEn) "Failed to generate cards" else "فشل توليد البطاقات")
                    return@launch
                }
                
                val plist = ArrayList(cards.map { it.code })
                // The settings page's card_test_delay is consumed by each router strategy
                // as a result-observation deadline. Do not also reuse it as an artificial
                // pause between cards; that duplicates the same timeout and slows the run.
                // HomeScreenCompose passes 1200L as a legacy sentinel, not a requested pause.
                val interCardPauseMs = if (delayMs > 0L && delayMs != 1200L) delayMs else 0L
                if (!WifiConnectivity.isActiveWifiConnected(context)) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _errorFlow.emit(if (isEn) "Wi-Fi disconnected before the test could start; reconnect and try again" else "انقطع اتصال Wi-Fi قبل بدء الاختبار؛ أعد الاتصال ثم حاول مجدداً")
                    return@launch
                }
                _startTestEvent.emit(TestStartConfig(routerId, plist, interCardPauseMs))
            } catch (e: Exception) {
                _errorFlow.emit(if (isEn) "An error occurred during startup: ${e.message}" else "حدث خطأ أثناء البدء: ${e.message}")
            }
        }
    }

    data class TestStartConfig(
        val routerId: Long,
        val cardList: ArrayList<String>,
        val delayMs: Long
    )
}
