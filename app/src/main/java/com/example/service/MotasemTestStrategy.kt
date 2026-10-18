package com.example.service

import android.os.SystemClock
import android.webkit.WebView
import com.example.data.local.entity.RouterProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Test Strategy for Motasem Net captive portals (شبكة معتصم نت).
 *
 * Implements the authentic Motasem captive portal contracts:
 * 1. Login page: Form 'login' posting to http://wifi.sd.net/login, visible username input,
 *    password input (width 0), hidden 'sendin' form, and portal's CHAP authentication function
 *    'doLogin()' utilizing 'hexMD5(\'\264\' + password + challenge)'.
 * 2. Intermediate redirect page: Displays "سيتم الآن تحويلك الى الموقع المطلوب" and refreshes/navigates
 *    to 'status.html' after approximately 4,000 ms.
 * 3. Status page: Authenticated session indicators including '#timeLeft', '.section.username',
 *    usage details ("تفاصيل الأستخدام"), and confirmed logout form targeting http://wifi.sd.net/logout.
 * 4. Two Devices Notification: Explicit "لا يمكن استعمال البطاقة في جهازين" alert/DOM message
 *    indicating a valid, active card being used on another client.
 */
object MotasemTestStrategy : RouterTestStrategy {
    override val strategyName: String = "Motasem"

    /**
     * Verifies that the current WebView DOM is on a fresh, ready-to-authenticate Motasem login page.
     * Rejects logout/status pages, intermediate redirect pages, and ensures both login DOM inputs
     * and the portal's CHAP hashing scripts (doLogin and hexMD5) are present.
     */
    override suspend fun verifyFreshLoginPage(
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String
    ): Boolean {
        val checkJs = """
        (function() {
            if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
            var html = (document.documentElement.innerHTML || '').toLowerCase();
            var bodyText = (document.body.innerText || '').toLowerCase();

            // 1. If currently on status or logout page, it is not a fresh login page
            if (document.getElementById('timeLeft') !== null ||
                html.indexOf('wifi.sd.net/logout') !== -1 ||
                html.indexOf('r.com/logout') !== -1 ||
                (html.indexOf('logout') !== -1 && (html.indexOf('status') !== -1 || bodyText.indexOf('تفاصيل الأستخدام') !== -1)) ||
                bodyText.indexOf('تفاصيل الأستخدام') !== -1 ||
                bodyText.indexOf('تفاصيل الاستخدام') !== -1) {
                return 'on_logout_page';
            }

            // 2. If currently on intermediate redirect page, not ready yet
            if (bodyText.indexOf('سيتم الآن تحويلك') !== -1 ||
                bodyText.indexOf('سيتم الان تحويلك') !== -1 ||
                html.indexOf('تحويلك الى الموقع المطلوب') !== -1 ||
                (html.indexOf('status.html') !== -1 && html.indexOf('refresh') !== -1)) {
                return 'redirecting';
            }

            // 3. Verify presence of login username input
            var u = document.querySelector('#username') ||
                    document.querySelector('form[name="login"] input[name="username"]') ||
                    document.querySelector('input[name="username"]') ||
                    (document.login && document.login.username);
            if (!u) return 'not_ready';

            // 4. Verify presence of required CHAP authentication scripts
            if (typeof doLogin !== 'function' || typeof hexMD5 !== 'function') {
                return 'not_ready';
            }

            return 'ready';
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
        val checkJs = """
        (function() {
            if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
            var html = (document.documentElement.innerHTML || '').toLowerCase();
            var bodyText = (document.body.innerText || '').toLowerCase();
            var title = (document.title || '').toLowerCase();

            // 1. Genuine browser network error pages
            var isNetErr = title.indexOf('webpage not available') !== -1 ||
                           document.getElementById('main-frame-error') !== null ||
                           document.querySelector('.neterror') !== null;
            if (isNetErr) return 'network_error';

            // 2. Intermediate redirect page
            if (bodyText.indexOf('سيتم الآن تحويلك') !== -1 ||
                bodyText.indexOf('سيتم الان تحويلك') !== -1 ||
                html.indexOf('تحويلك الى الموقع المطلوب') !== -1 ||
                html.indexOf('content="4;url=status.html"') !== -1 ||
                (html.indexOf('status.html') !== -1 && html.indexOf('refresh') !== -1)) {
                return 'redirecting';
            }

            // 3. Active session indicators on Motasem Net portal
            var hasTimeLeft = document.getElementById('timeLeft') !== null;
            var hasLogoutForm = document.querySelector('form[name="logout"]') !== null ||
                                document.querySelector('form[action*="logout"]') !== null ||
                                html.indexOf('wifi.sd.net/logout') !== -1 ||
                                html.indexOf('r.com/logout') !== -1;
            var hasUsageDetails = bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('تفاصيل الاستخدام') !== -1;
            var hasUserSection = document.querySelector('.section.username') !== null || document.querySelector('.section.remain') !== null;

            if (hasTimeLeft || hasLogoutForm || (hasUsageDetails && (bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1))) {
                return 'active_session';
            }

            // 4. Explicit logged-out landing page from the supplied Motasem portal HTML.
            // This page asks the user to press "تسجيل الدخول" again but deliberately has
            // no username field and therefore is NOT the fresh credential-entry page.
            var hasLoginForm = document.querySelector('form[name="login"]') !== null;
            var hasVisibleUsername = document.querySelector(
                'form[name="login"] input[name="username"]:not([type="hidden"]), #username, input[name="username"]:not([type="hidden"])'
            ) !== null;
            var logoutLandingText = bodyText.indexOf('للدخول الى الشبكة مرة اخرى') !== -1 ||
                                    bodyText.indexOf('للدخول إلى الشبكة مرة أخرى') !== -1 ||
                                    bodyText.indexOf('يرجى الضغط على تسجيل الدخول بالاسفل') !== -1 ||
                                    bodyText.indexOf('يرجى الضغط على تسجيل الدخول بالأسفل') !== -1;
            if (hasLoginForm && !hasVisibleUsername && logoutLandingText &&
                !hasTimeLeft && !hasLogoutForm && !hasUsageDetails) {
                return 'logout_in_progress';
            }

            // 5. Fresh login page ready
            var u = document.querySelector('#username') ||
                    document.querySelector('form[name="login"] input[name="username"]') ||
                    document.querySelector('input[name="username"]') ||
                    (document.login && document.login.username);
            if (u && typeof doLogin === 'function' && typeof hexMD5 === 'function') {
                return 'login_page_ready';
            }

            return 'unknown_page';
        })();
        """.trimIndent()

        val res = evaluateJsSafely(checkJs)
        return when (res) {
            "network_error" -> PreflightSessionState.NETWORK_ERROR
            "redirecting" -> PreflightSessionState.REDIRECTING
            "active_session" -> PreflightSessionState.ACTIVE_SESSION
            "logout_in_progress" -> PreflightSessionState.LOGOUT_IN_PROGRESS
            "login_page_ready" -> PreflightSessionState.LOGIN_PAGE_READY
            else -> PreflightSessionState.UNKNOWN_PAGE
        }
    }

    override suspend fun executePortalLogout(
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String
    ): Boolean {
        val logoutJs = """
        (function() {
            try {
                // The captured Motasem status page defines onsubmit="return openLogout()".
                // That handler opens a named popup and closes the current window when
                // window.name == 'hotspot_status'. The app WebView does not implement
                // popup creation, so clicking that button can report success without
                // actually sending the logout request. Submit the portal's OWN form in
                // the same WebView instead; native form submission preserves its action,
                // method, hidden erase-cookie field, and WebView cookies while bypassing
                // only the fragile popup/close handler.
                var logoutForm = document.querySelector('form[name="logout"]') ||
                                 document.querySelector('form[action*="/logout"]') ||
                                 document.querySelector('form[action*="logout"]');
                if (!logoutForm) return 'logout_form_not_found';

                var action = (logoutForm.action || logoutForm.getAttribute('action') || '').toLowerCase();
                if (action.indexOf('logout') === -1) return 'refused_unverified_logout_action';

                var btn = logoutForm.querySelector('button[type="submit"], input[type="submit"]');
                if (btn) {
                    btn.click();
                    return 'clicked_submit_btn';
                }

                HTMLFormElement.prototype.submit.call(logoutForm);
                return 'submitted_portal_logout_form';
            } catch(e) {
                return 'logout_error:' + (e && e.message ? e.message : String(e));
            }
        })();
        """.trimIndent()

        val res = evaluateJsSafely(logoutJs)
        Timber.d("[Motasem] executePortalLogout dispatch result: $res")
        return res in listOf("clicked_submit_btn", "submitted_portal_logout_form")
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
                    Timber.w("[Motasem] Login page did not become ready; refusing to inject a card into an incomplete page.")
                    return@withContext CardTestOutcome.timeout(
                        message = "لم تصبح صفحة تسجيل الدخول جاهزة؛ لم يتم إرسال البطاقة لتجنب تخطي خطوات البوابة",
                        durationMs = duration
                    )
                }

                // Pre-flight check: Detect active Motasem Net session
                val preFlightJs = """
                (function() {
                    var html = document.documentElement.innerHTML.toLowerCase();
                    var bodyText = (document.body.innerText || '').toLowerCase();
                    var title = document.title.toLowerCase();

                    if (title.indexOf('شبكة معتصم نت') !== -1 && (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || document.getElementById('timeLeft'))) return 'logged_in';
                    if (html.indexOf('wifi.sd.net/logout') !== -1 || html.indexOf('r.com/logout') !== -1 || document.querySelector('form[action*="logout"]')) return 'logged_in';
                    if (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1) return 'logged_in';
                    if (document.querySelector('.section.username, .section.remain')) return 'logged_in';

                    return 'login_page';
                })();
                """.trimIndent()

                val preFlightResult = evaluateJsSafely(preFlightJs)
                if (preFlightResult == "logged_in") {
                    if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                    Timber.d("[Motasem] Pre-flight showed ALREADY LOGGED IN. Forcing logout...")
                    if (onRequiresGlobalRelogin != null) {
                        onRequiresGlobalRelogin()
                    } else {
                        executePortalLogout(router, wv, evaluateJsSafely)
                        delay(1500L)
                        if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                        wv.loadUrl(url)
                        delay(1000L)
                    }
                }

                // Wait for login form and required CHAP functions to be fully ready
                val checkReadyJs = """
                (function() {
                    if (document.readyState !== 'complete' && document.readyState !== 'interactive') return 'not_ready';
                    var u = document.querySelector('#username') || 
                            document.querySelector('form[name="login"] input[name="username"]') || 
                            document.querySelector('input[name="username"]') || 
                            (document.login && document.login.username);
                    if (!u) return 'not_ready';
                    if (typeof doLogin !== 'function' || typeof hexMD5 !== 'function') return 'not_ready';
                    return 'ready';
                })();
                """.trimIndent()

                val maxFormWaitMs = PortalTiming.formReadyTimeout(pageLoadDelay)
                val formStartTime = SystemClock.elapsedRealtime()
                var isFormReady = false
                while (SystemClock.elapsedRealtime() - formStartTime < maxFormWaitMs) {
                    if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                    val readyState = evaluateJsSafely(checkReadyJs)
                    if (readyState == "ready") {
                        isFormReady = true
                        break
                    }
                    delay(PortalTiming.POLL_INTERVAL_MS)
                }

                if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                if (!isFormReady) {
                    return@withContext CardTestOutcome.timeout(
                        message = "لم يكتمل نموذج تسجيل الدخول في بوابة معتصم؛ لم يتم إرسال البطاقة",
                        durationMs = SystemClock.elapsedRealtime() - startTime
                    )
                }

                // Card Normalization: Trim surrounding whitespace without changing valid internal characters
                val normalizedCard = card.trim()
                val safeCardJson = InjectionManager.quote(normalizedCard)
                val effectivePassword = if (router.passwordEnabled) router.getEffectivePassword() else ""
                val safePasswordJson = InjectionManager.quote(effectivePassword)

                // Motasem CHAP Login Contract:
                // Strictly fills document.login and invokes the portal's original doLogin() function.
                // NEVER falls back to submitting raw/un-hashed credentials through hidden sendin form,
                // and NEVER bypasses onsubmit by calling form.submit() directly.
                val injectionJs = """
                (function() {
                    try {
                        function triggerEvents(el) {
                            if (!el) return;
                            try {
                                var ev1 = document.createEvent('Event'); ev1.initEvent('input', true, true); el.dispatchEvent(ev1);
                                var ev2 = document.createEvent('Event'); ev2.initEvent('change', true, true); el.dispatchEvent(ev2);
                            } catch(e) {}
                        }

                        var cardVal = $safeCardJson;
                        var pwdVal = $safePasswordJson;

                        // 1. Verify required CHAP functions
                        if (typeof doLogin !== 'function') {
                            return 'error: CHAP doLogin function is missing on portal page';
                        }
                        if (typeof hexMD5 !== 'function') {
                            return 'error: CHAP hexMD5 hashing function is missing on portal page';
                        }

                        // 2. Set username on visible login form
                        var uInput = (document.login && document.login.username) ||
                                     document.querySelector('form[name="login"] input[name="username"]') ||
                                     document.querySelector('#username') ||
                                     document.querySelector('input[name="username"]:not([type="hidden"])');
                        if (!uInput) {
                            return 'error: Username input not found on portal page';
                        }
                        uInput.value = cardVal;
                        triggerEvents(uInput);

                        // 3. Set password on visible login form (empty string when password use is disabled)
                        var pInput = (document.login && document.login.password) ||
                                     document.querySelector('form[name="login"] input[name="password"]') ||
                                     document.querySelector('input[type="password"]');
                        if (pInput) {
                            pInput.value = pwdVal;
                            triggerEvents(pInput);
                        }

                        // 4. Invoke portal's original doLogin() strictly
                        // Never bypass CHAP or fall back to raw sendin submission
                        try {
                            doLogin();
                            return 'injected_dologin';
                        } catch(chapErr) {
                            return 'error: doLogin execution threw: ' + (chapErr.message || chapErr);
                        }
                    } catch(e) {
                        return 'error: ' + (e.message || e);
                    }
                })();
                """.trimIndent()

                if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")
                val injectResult = evaluateJsSafely(injectionJs)
                Timber.d("[Motasem] Injection result: $injectResult")

                if (!(injectResult == "injected_dologin")) {
                    val duration = SystemClock.elapsedRealtime() - startTime
                    Timber.e("[Motasem] Submission was not positively confirmed; result observation will not start. result=$injectResult")
                    return@withContext CardTestOutcome.engineError(
                        message = "لم تؤكد بوابة معتصم إرسال طلب تسجيل الدخول بالطريقة المطلوبة؛ لم يتم تخمين النتيجة أو الانتقال للخطوة التالية",
                        durationMs = duration,
                        isJs = true
                    )
                }

                // If CHAP injection encountered a typed script error, return typed engine error immediately
                if (injectResult.startsWith("error:")) {
                    val duration = SystemClock.elapsedRealtime() - startTime
                    Timber.e("[Motasem] Injection failed with typed error: $injectResult")
                    return@withContext CardTestOutcome.engineError(
                        message = "خطأ في دالة تشفير البوابة (CHAP): $injectResult",
                        durationMs = duration,
                        isJs = true
                    )
                }

                // Result classification script implementing centralized precedence:
                // 1. Explicit Two Devices Notification (DOM / alert) -> 'success_two_devices'
                // 2. Authoritative Status DOM (#timeLeft, .section.username, usage details) -> 'status_success'
                // 3. Confirmed Portal Failure / Rejection -> 'failure'
                // 4. Authorizing Intermediate State -> 'authorizing'
                // 5. Intermediate Redirect Page ("سيتم الآن تحويلك الى الموقع المطلوب", meta refresh 4s) -> 'redirecting'
                // 6. Unrecognized / pending -> 'unknown'
                val checkJs = """
                (function() {
                    try {
                        var html = (document.documentElement.innerHTML || '').toLowerCase();
                        var bodyText = (document.body.innerText || '').toLowerCase();
                        var title = (document.title || '').toLowerCase();

                        // 1. Priority 1: Explicit Two Devices Notification
                        if (bodyText.indexOf('لا يمكن استعمال البطاقة في جهازين') !== -1 ||
                            bodyText.indexOf('لا يمكن استخدام البطاقة في جهازين') !== -1 ||
                            bodyText.indexOf('لا يمكن استعمال الكرت في جهازين') !== -1 ||
                            bodyText.indexOf('لا يمكن استخدام الكرت في جهازين') !== -1 ||
                            html.indexOf('لا يمكن استعمال البطاقة في جهازين') !== -1 ||
                            html.indexOf('لا يمكن استخدام البطاقة في جهازين') !== -1 ||
                            html.indexOf('لا يمكن استعمال الكرت في جهازين') !== -1 ||
                            html.indexOf('لا يمكن استخدام الكرت في جهازين') !== -1 ||
                            ((bodyText.indexOf('لا يمكن') !== -1 || html.indexOf('لا يمكن') !== -1) && 
                             (bodyText.indexOf('جهازين') !== -1 || html.indexOf('جهازين') !== -1) && 
                             (bodyText.indexOf('بطاق') !== -1 || bodyText.indexOf('كرت') !== -1 || html.indexOf('بطاق') !== -1 || html.indexOf('كرت') !== -1))) {
                            return 'success_two_devices';
                        }

                        // 2. Priority 2: Authoritative Motasem Status Page (Normal Login Success)
                        var hasTimeLeft = document.getElementById('timeLeft') !== null;
                        var hasUsernameSection = document.querySelector('.section.username') !== null;
                        var hasUsageDetails = bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('تفاصيل الاستخدام') !== -1;
                        var hasLogoutLink = html.indexOf('wifi.sd.net/logout') !== -1 || html.indexOf('r.com/logout') !== -1 || document.querySelector('form[action*="logout"]') !== null;

                        if (hasTimeLeft && (hasUsernameSection || hasUsageDetails || hasLogoutLink)) {
                            return 'status_success';
                        }
                        if (hasUsageDetails && (bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1)) {
                            return 'status_success';
                        }
                        if (title.indexOf('شبكة معتصم نت') !== -1 && (hasTimeLeft || hasUsageDetails)) {
                            return 'status_success';
                        }
                        if (document.querySelector('.section.remain') && html.indexOf('readablizebytes') !== -1) {
                            return 'status_success';
                        }

                        // 3. Priority 3: Confirmed Portal Failure / Rejection
                        var failureKeywords = ['خطأ', 'فشل', 'غير صحيح', 'invalid', 'not found', 'incorrect', 'expired', 'منتهي', 'نفذ الرصيد', 'رفض'];
                        for (var i = 0; i < failureKeywords.length; i++) {
                            if (bodyText.indexOf(failureKeywords[i]) !== -1 || html.indexOf(failureKeywords[i]) !== -1) {
                                return 'failure';
                            }
                        }
                        if ((bodyText.indexOf('لا يمكن') !== -1 || html.indexOf('لا يمكن') !== -1) && 
                            bodyText.indexOf('جهازين') === -1 && html.indexOf('جهازين') === -1) {
                            return 'failure';
                        }

                        // 4. Priority 4: Authorizing Intermediate State
                        if (bodyText.indexOf('already authorizing') !== -1 || html.indexOf('already authorizing') !== -1 ||
                            bodyText.indexOf('جاري التحقق') !== -1 || bodyText.indexOf('يرجى الانتظار') !== -1) {
                            return 'authorizing';
                        }

                        // 5. Priority 5: Intermediate Redirect Page (4s delay navigating to status.html)
                        if (bodyText.indexOf('سيتم الآن تحويلك') !== -1 || 
                            bodyText.indexOf('سيتم الان تحويلك') !== -1 ||
                            html.indexOf('سيتم الآن تحويلك') !== -1 ||
                            html.indexOf('سيتم الان تحويلك') !== -1 ||
                            html.indexOf('تحويلك الى الموقع المطلوب') !== -1 ||
                            html.indexOf('تحويلك إلى الموقع المطلوب') !== -1 ||
                            html.indexOf('content="4;url=status.html"') !== -1 ||
                            (html.indexOf('status.html') !== -1 && html.indexOf('refresh') !== -1)) {
                            return 'redirecting';
                        }

                        return 'unknown';
                    } catch(e) {
                        return 'error: ' + (e.message || e);
                    }
                })();
                """.trimIndent()

                // State-aware bounded timing formula:
                // - Normal wait: baseWaitMs = cardTestDelay.coerceAtLeast(1000L) (default 3000ms from user preference)
                // - If intermediate redirect page is detected: grant bounded redirect grace period of 7,000 ms
                //   (sufficient for the observed 4-second delay + navigation + DOM rendering headroom).
                // - Hard ceiling: maxAbsoluteDeadlineMs = 15,000 ms to guarantee termination under all circumstances.
                var baseWaitMs = PortalTiming.resultTimeout(cardTestDelay)
                val redirectGraceMs = maxOf(7000L, PortalTiming.resultTimeout(cardTestDelay))
                var maxAbsoluteDeadlineMs = maxOf(15000L, baseWaitMs)

                val loopStartTime = SystemClock.elapsedRealtime()
                var redirectDetectedAt: Long? = null
                var authorizingObserved = false
                var finalState = "unknown"

                delay(150L) // Initial yield for form dispatch

                while (true) {
                    if (isBlockedBySuccess()) return@withContext CardTestOutcome.failure("مقاطعة بنجاح بطاقة أخرى")

                    val now = SystemClock.elapsedRealtime()
                    val totalElapsed = now - loopStartTime

                    // 1. Check synchronous captured alert
                    val alertText = capturedAlert()
                    if (ResultChecker.isTwoDevicesSuccess("", alertText)) {
                        finalState = "success_two_devices"
                        break
                    }

                    // 2. Evaluate DOM state
                    val domState = evaluateJsSafely(checkJs)

                    when (domState) {
                        "success_two_devices" -> {
                            finalState = "success_two_devices"
                            break
                        }
                        "status_success" -> {
                            finalState = "status_success"
                            break
                        }
                        "failure" -> {
                            finalState = "failure"
                            break
                        }
                        "authorizing" -> {
                            // Submission was received by the portal. Keep waiting for the
                            // authoritative result; do not relogin or end this attempt after 1s.
                            if (!authorizingObserved) {
                                authorizingObserved = true
                                // `evaluateJsSafely` can itself take time; measure from the
                                // moment acknowledgement was actually observed, not the loop's
                                // earlier timestamp captured before evaluating the DOM.
                                val acknowledgedElapsed = SystemClock.elapsedRealtime() - loopStartTime
                                baseWaitMs = PortalTiming.acknowledgedDeadline(cardTestDelay, acknowledgedElapsed)
                                maxAbsoluteDeadlineMs = maxOf(maxAbsoluteDeadlineMs, baseWaitMs)
                            }
                            finalState = "authorizing"
                        }
                        "redirecting" -> {
                            if (redirectDetectedAt == null) {
                                redirectDetectedAt = now
                                Timber.d("[Motasem] Intermediate redirect page detected ('سيتم الآن تحويلك'). Granting redirect grace period...")
                            }
                            // Do NOT call stopLoading() or force reload while redirect is legitimately in progress!
                        }
                    }

                    // Check timeout condition
                    val isExpired = if (redirectDetectedAt != null) {
                        val redirectElapsed = now - redirectDetectedAt
                        // A redirect may have started before the portal acknowledged the form.
                        // Once acknowledged, honour the result deadline instead of letting the
                        // earlier redirect timer cut off the acknowledged operation.
                        (!authorizingObserved && redirectElapsed >= redirectGraceMs) ||
                            totalElapsed >= maxAbsoluteDeadlineMs
                    } else {
                        totalElapsed >= baseWaitMs
                    }

                    if (isExpired) {
                        finalState = if (redirectDetectedAt != null) "redirect_timeout" else "timeout"
                        break
                    }

                    delay(150L)
                }

                if (authorizingObserved && finalState !in listOf("status_success", "success_two_devices", "failure")) {
                    Timber.w("[Motasem] Portal stayed pending until the safe result deadline; recovering session only after waiting.")
                    onRequiresGlobalRelogin?.invoke()
                    finalState = "timeout"
                }

                val duration = SystemClock.elapsedRealtime() - startTime
                Timber.d("[Motasem] Final state evaluated: $finalState (duration: ${duration}ms, redirectDetected: ${redirectDetectedAt != null})")

                return@withContext when (finalState) {
                    "success_two_devices" -> CardTestOutcome.twoDevicesSuccess(
                        message = "تم التحقق بنجاح: لا يمكن استعمال البطاقة في جهازين (البطاقة صالحة ونشطة)",
                        durationMs = duration
                    )
                    "status_success" -> CardTestOutcome.success(
                        message = "تم اختبار البطاقة بنجاح: تم تسجيل الدخول إلى شبكة معتصم نت",
                        durationMs = duration
                    )
                    "failure" -> CardTestOutcome.failure(
                        message = "فشلت عملية الاختبار: بطاقة غير صالحة أو منتهية",
                        durationMs = duration
                    )
                    "redirect_timeout" -> CardTestOutcome.timeout(
                        message = "انتهت مهلة استجابة بوابة معتصم نت أثناء التحويل إلى صفحة الحالة (Redirect Timeout)",
                        durationMs = duration
                    )
                    else -> CardTestOutcome.timeout(
                        message = "انتهت مهلة استجابة بوابة معتصم نت (Timeout)",
                        durationMs = duration
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val duration = SystemClock.elapsedRealtime() - startTime
                Timber.e(e, "Error in Motasem specific test")
                return@withContext CardTestOutcome.engineError("خطأ غير متوقع: ${e.localizedMessage}", duration)
            }
        }
    }
}
