package com.example.service

import android.os.SystemClock
import android.webkit.WebView
import com.example.data.local.entity.RouterProfileEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

object GenericTestStrategy : RouterTestStrategy {
    override val strategyName: String = "Generic"

    override suspend fun verifyFreshLoginPage(
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String
    ): Boolean {
        val uSelJson = InjectionManager.quote(router.usernameSelector)
        val checkJs = """
        (function() {
            if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
            var html = (document.documentElement.innerHTML || '').toLowerCase();
            var bodyText = (document.body.innerText || '').toLowerCase();
            if (html.indexOf('logout') !== -1 || html.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('تسجيل الخروج') !== -1) {
                return 'on_logout_page';
            }
            var uSel = $uSelJson;
            var u = uSel ? document.querySelector(uSel) : null;
            if (!u) {
                u = document.querySelector('input[name="username"]') ||
                    document.querySelector('#username') ||
                    document.querySelector('#uname') ||
                    document.querySelector('input[type="text"]:not([type="hidden"])');
            }
            return u ? 'ready' : 'not_ready';
        })();
        """.trimIndent()
        val res = evaluateJsSafely(checkJs)
        return res == "ready"
    }

    override suspend fun detectPreflightState(
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String
    ): PreflightSessionState {
        val lSelJson = InjectionManager.quote(router.logoutSelector)
        val checkJs = """
        (function() {
            if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
            var html = (document.documentElement.innerHTML || '').toLowerCase();
            var bodyText = (document.body.innerText || '').toLowerCase();
            var title = (document.title || '').toLowerCase();

            var isNetErr = title.indexOf('webpage not available') !== -1 ||
                           document.getElementById('main-frame-error') !== null ||
                           document.querySelector('.neterror') !== null;
            if (isNetErr) return 'network_error';

            var lSel = $lSelJson;
            if (lSel && document.querySelector(lSel) !== null) return 'active_session';

            if (html.indexOf('logout') !== -1 || html.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('تسجيل الخروج') !== -1) {
                if (document.getElementById('mForm') || 
                    document.querySelector('form[action*="logout"], form[name="logout"]') || 
                    document.getElementById('timeLeft') || 
                    document.getElementById('card')) {
                    return 'active_session';
                }
                if (bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1 || bodyText.indexOf('عنوان ip') !== -1) {
                    return 'active_session';
                }
            }

            var u = document.querySelector('input[name="username"]') ||
                    document.querySelector('#username') ||
                    document.querySelector('#uname') ||
                    document.querySelector('input[type="text"]:not([type="hidden"])');
            if (u) return 'login_page_ready';

            return 'unknown_page';
        })();
        """.trimIndent()

        val res = evaluateJsSafely(checkJs)
        return when (res) {
            "network_error" -> PreflightSessionState.NETWORK_ERROR
            "active_session" -> PreflightSessionState.ACTIVE_SESSION
            "login_page_ready" -> PreflightSessionState.LOGIN_PAGE_READY
            else -> PreflightSessionState.UNKNOWN_PAGE
        }
    }

    override suspend fun executePortalLogout(
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String
    ): Boolean {
        val lJs = InjectionManager.buildLogoutJs(router.logoutSelector)
        val res = evaluateJsSafely(lJs)
        return res != "not_found" && !res.startsWith("error")
    }

    override suspend fun testCard(
        card: String,
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String,
        pauseCondition: suspend () -> Unit,
        isPreloaded: Boolean,
        onRequiresGlobalRelogin: (suspend () -> Unit)?,
        isBlockedBySuccess: () -> Boolean
    ): Boolean {
        return testCardWithDetails(
            card = card,
            router = router,
            webView = webView,
            evaluateJsSafely = evaluateJsSafely,
            pauseCondition = pauseCondition,
            isPreloaded = isPreloaded,
            onRequiresGlobalRelogin = onRequiresGlobalRelogin,
            isBlockedBySuccess = isBlockedBySuccess
        ).isSuccess
    }

    override suspend fun testCardWithDetails(
        card: String,
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String,
        pauseCondition: suspend () -> Unit,
        isPreloaded: Boolean,
        onRequiresGlobalRelogin: (suspend () -> Unit)?,
        isBlockedBySuccess: () -> Boolean,
        capturedAlert: () -> String
    ): CardTestOutcome {
        val wv = webView ?: return CardTestOutcome.engineError("WebView null")
        val context = wv.context ?: return CardTestOutcome.engineError("Context null")

        return withContext(Dispatchers.Main) {
            val startTime = SystemClock.elapsedRealtime()
            try {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val pageLoadDelay = try {
                    prefs.getString("page_load_delay", "2000")?.toLongOrNull() ?: 2000L
                } catch (e: Exception) {
                    try { prefs.getLong("page_load_delay", 2000L) } catch (_: Exception) { 2000L }
                }
                val cardTestDelay = try {
                    prefs.getString("card_test_delay", "3000")?.toLongOrNull() ?: 3000L
                } catch (e: Exception) {
                    try { prefs.getLong("card_test_delay", 3000L) } catch (_: Exception) { 3000L }
                }

                pauseCondition()
                if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("تم مقاطعة الفحص بنجاح بطاقة أخرى")

                val url = router.getFullLoginUrl()
                Timber.d("[Generic] Testing URL: $url preloaded: $isPreloaded")

                // Confirm fresh login page readiness. A preference value is a timeout hint,
                // not permission to continue with an incomplete document.
                var isLoginReady = verifyFreshLoginPage(router, wv, evaluateJsSafely)
                if (!isLoginReady) {
                    wv.stopLoading()
                    wv.loadUrl(url)
                    val maxWait = PortalTiming.pageReadyTimeout(pageLoadDelay)
                    val waitStart = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - waitStart < maxWait) {
                        if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                        delay(PortalTiming.POLL_INTERVAL_MS)
                        if (verifyFreshLoginPage(router, wv, evaluateJsSafely)) {
                            isLoginReady = true
                            break
                        }
                    }
                }

                if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                if (!isLoginReady) {
                    val duration = SystemClock.elapsedRealtime() - startTime
                    Timber.w("[Generic] Login page did not become ready; refusing to inject a card into an incomplete page.")
                    return@withContext CardTestOutcome.timeout(
                        message = "لم تصبح صفحة تسجيل الدخول جاهزة؛ لم يتم إرسال البطاقة لتجنب تخطي خطوات البوابة",
                        durationMs = duration
                    )
                }

                // Pre-flight check
                val ensureFormJs = """
                (function() {
                    if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
                    
                    var html = (document.documentElement.innerHTML || '').toLowerCase();
                    var bodyText = (document.body.innerText || '').toLowerCase();
                    if (html.indexOf('logout') !== -1 || html.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('تسجيل الخروج') !== -1) {
                         var f = document.getElementById('mForm');
                         if (f) { f.submit(); return 'mForm'; }
                         var f2 = document.querySelector('form[action*="logout"], form[name="logout"]');
                         if (f2) { f2.submit(); return 'logout_form'; }
                         var links = document.querySelectorAll('a, button, input[type="submit"], input[type="button"]');
                         for (var i = 0; i < links.length; i++) {
                             var text = (links[i].textContent || '').toLowerCase();
                             var val = (links[i].value || '').toLowerCase();
                             if (text.indexOf('تسجيل الخروج') !== -1 || val.indexOf('تسجيل الخروج') !== -1 || text.indexOf('logout') !== -1 || val.indexOf('logout') !== -1) {
                                 links[i].click();
                                 return 'clicked_logout';
                             }
                         }
                         if (typeof openLogout === 'function') { openLogout(); return 'openLogout'; }
                    }
                    
                    var u1 = document.querySelector('input[name="username"]');
                    var u2 = document.querySelector('input[type="text"]:not([type="hidden"])');
                    if (u1 || u2) return 'form_ready';
                    
                    return 'no_form';
                })();
                """.trimIndent()

                val maxFormWaitMs = PortalTiming.formReadyTimeout(pageLoadDelay)
                val formStartTime = SystemClock.elapsedRealtime()
                var isFormReady = false
                while (SystemClock.elapsedRealtime() - formStartTime < maxFormWaitMs) {
                    if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                    val formState = evaluateJsSafely(ensureFormJs)
                    if (formState == "form_ready") {
                        isFormReady = true
                        break
                    }
                    if (formState in listOf("clicked_logout", "mForm", "logout_form", "openLogout")) {
                        Timber.d("[Generic] Found logout before injecting. Triggered logout; waiting for the real login form...")
                        delay(1500L)
                        wv.loadUrl(url)
                        delay(1000L)
                    }
                    delay(PortalTiming.POLL_INTERVAL_MS)
                }

                if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                if (!isFormReady) {
                    return@withContext CardTestOutcome.timeout(
                        message = "لم يكتمل نموذج تسجيل الدخول في بوابة الراوتر؛ لم يتم إرسال البطاقة",
                        durationMs = SystemClock.elapsedRealtime() - startTime
                    )
                }

                // Inject credentials
                val passwordToSubmit = if (router.passwordEnabled) router.getEffectivePassword() else ""
                val js = InjectionManager.buildInjectionJs(
                    card = card,
                    usernameSel = router.usernameSelector,
                    passwordSel = router.passwordSelector,
                    submitSel = router.submitSelector,
                    password = passwordToSubmit
                )
                val injectResult = evaluateJsSafely(js)
                Timber.d("[Generic] Injection result: $injectResult")

                if (!(injectResult == "injected_dologin" || injectResult == "injected_clicked" || injectResult == "injected_sendin" || injectResult == "injected_form" || injectResult == "injected")) {
                    val duration = SystemClock.elapsedRealtime() - startTime
                    Timber.e("[Generic] Submission was not positively confirmed; result observation will not start. result=$injectResult")
                    return@withContext CardTestOutcome.engineError(
                        message = "لم يتم العثور على إجراء صالح لإرسال نموذج تسجيل الدخول؛ لم يتم تخمين النتيجة أو الانتقال للخطوة التالية",
                        durationMs = duration,
                        isJs = true
                    )
                }

                if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")

                val checkJs = InjectionManager.buildCheckResultJs(
                    router.successIndicator,
                    router.failureIndicator,
                    router.submitSelector,
                    router.logoutSelector
                )
                var resolvedState = "unknown"
                var maxResultWaitMs = PortalTiming.resultTimeout(cardTestDelay)
                var authorizingObserved = false
                val checkStartTime = SystemClock.elapsedRealtime()

                delay(150L) // Initial short yield for form dispatch

                while (SystemClock.elapsedRealtime() - checkStartTime < maxResultWaitMs) {
                    if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")

                    val alertText = capturedAlert()
                    if (ResultChecker.isTwoDevicesSuccess("", alertText)) {
                        resolvedState = "success_two_devices"
                        break
                    }

                    val currentState = evaluateJsSafely(checkJs)

                    if (currentState == "success_two_devices" || currentState == "success" || currentState == "failure") {
                        resolvedState = currentState
                        break
                    } else if (currentState == "authorizing") {
                        // Once the portal acknowledges submission, grant a full bounded window
                        // from the first acknowledgement without extending the deadline every poll.
                        resolvedState = "authorizing"
                        if (!authorizingObserved) {
                            authorizingObserved = true
                            maxResultWaitMs = PortalTiming.acknowledgedDeadline(
                                cardTestDelay,
                                SystemClock.elapsedRealtime() - checkStartTime
                            )
                        }
                        delay(PortalTiming.POLL_INTERVAL_MS)
                    } else {
                        delay(PortalTiming.POLL_INTERVAL_MS)
                    }
                }

                if (resolvedState == "authorizing" && onRequiresGlobalRelogin != null) {
                    Timber.w("[Generic] Portal remained authorizing until the safe result deadline; recovering the session after the wait completed.")
                    onRequiresGlobalRelogin()
                }

                val duration = SystemClock.elapsedRealtime() - startTime
                Timber.d("[Generic] Resolved state for card: $resolvedState (duration: ${duration}ms)")

                return@withContext when (resolvedState) {
                    "success_two_devices" -> CardTestOutcome.twoDevicesSuccess(
                        message = "تم التحقق بنجاح: لا يمكن استعمال البطاقة في جهازين (البطاقة صالحة ونشطة)",
                        durationMs = duration
                    )
                    "success" -> CardTestOutcome.success(
                        message = "تم اختبار البطاقة بنجاح: تم تسجيل الدخول إلى الشبكة",
                        durationMs = duration
                    )
                    "failure" -> CardTestOutcome.failure(
                        message = "فشلت عملية الاختبار: بطاقة غير صالحة أو منتهية",
                        durationMs = duration
                    )
                    else -> CardTestOutcome.timeout(
                        message = "انتهت مهلة استجابة الراوتر (Timeout)",
                        durationMs = duration
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val duration = SystemClock.elapsedRealtime() - startTime
                Timber.e(e, "Error in Generic test strategy")
                return@withContext CardTestOutcome.engineError("خطأ غير متوقع: ${e.localizedMessage}", duration)
            }
        }
    }
}
