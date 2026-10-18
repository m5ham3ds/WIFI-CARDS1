# PHASE 8 — Comparative Forensic Audit
## WIFI-CARD1-main (original) vs WIFI-CARD-NEW (updated)

**Audit status: `BLOCKED — DO NOT ACCEPT PHASE 8 AS COMPLETE`**

**Scope:** Android source-level review of the original archive `WIFI-CARD1-main.zip` against the updated archive `WIFI-CARD-NEW.zip`. The review covers test-session flow, navigation, Settings functionality, language switching/localization, theme/color propagation, result persistence, and the Phase 8 completion report.

**Important verification boundary:** This is a source audit, not a physical-device/router end-to-end test. I attempted `bash ./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`; the wrapper could not download Gradle 9.3.1 because the environment could not resolve `services.gradle.org` (`UnknownHostException`). Therefore, I do **not** independently certify the report's claimed 98 passing tests, successful APK, or lint result. No source files in either project were modified during this audit.

---

## 1. Executive verdict

The updated app retains the main testing architecture (foreground `TestService`, router-specific strategies, WebView execution, result persistence, service-backed state), and several settings are genuinely wired to code used by the engine. However, the new project is **not yet demonstrably equivalent to the original in runtime correctness**, and Phase 8 has several confirmed defects that conflict with its acceptance criteria.

The most important finding is in the card lifecycle: `ensureFreshLoginPage()` returns whether the login page was actually verified, but callers ignore that Boolean and proceed as though the page were ready. In the worker loop, it is followed unconditionally by `LOGIN_PAGE_READY`; during finalization it is followed by `READY_FOR_NEXT` even when the fresh login page was not confirmed. This reopens the exact class of failure where the next card can be submitted before a valid login page is ready.

The localization completion claim is also contradicted by the current source. The English resource XML is structurally complete, but many screen labels and dialog strings remain hardcoded in Arabic. The new Settings screen is particularly affected. The color selector is present and has a confirmation flow, but the app's primary color values differ between Compose and the enum/XML theme, and the bottom-navigation overlay is still red-specific. Two settings toggles—success vibration and success sound—persist their values but have no runtime consumer that performs vibration or plays sound.

### Severity summary

| Severity | Finding | Status |
|---|---|---|
| **P0 / critical** | Login-page verification result is ignored, so a worker can proceed without a confirmed fresh login page | Confirmed in source |
| **P1 / high** | English UI contains hardcoded Arabic across Settings and multiple screens/dialogs | Confirmed in source |
| **P1 / high** | Clear-history action can delete the active session record while `TestService` continues running | Confirmed risk in source |
| **P1 / high** | Navigation handlers can push duplicate top-level destinations onto the back stack | Confirmed in source |
| **P1 / high** | “Vibrate on success” and “Sound on success” save preferences but do not perform the advertised action | Confirmed in source; appears to have existed in original too |
| **P1 / high** | Primary colors are not represented by one consistent palette; bottom navigation remains red | Confirmed in source |
| **P1 / high** | Result subreason is inferred from message text after persistence, rather than persisted as an authoritative field | Confirmed in source |
| **P2 / medium** | Language selector offers a third “System default” option despite the requested two-language contract | Confirmed in source |
| **P2 / medium** | Thread/page pool setting is misleading: `5` is accepted in UI but capped to 3 or 1 by the engine; summary label is not reactive | Confirmed in source |
| **P2 / medium** | Export serializes card codes and only displays a filename toast; there is no share/open action in the Settings UI | Confirmed in source |
| **P2 / medium** | Several delay options exposed by the original settings resources are omitted or changed in the updated dialog | Confirmed comparison |
| **P3 / low** | Current-version Settings row looks actionable but has an empty click callback | Confirmed in source |

---

## 2. Testing workflow comparison

### 2.1 What remains in place

The updated project still contains the main runtime path:

`Test UI → TestViewModel → TestService → router/session repositories → RouterStrategyFactory → router strategy → WebView → ResultChecker / CardTestOutcome → Room result persistence → service state/progress`.

The three router strategies remain present (`AbashaTestStrategy`, `MotasemTestStrategy`, and `BelloTestStrategy`), and the updated project adds an explicit strategy interface and a generic strategy. The profile entity carries `strategyId` and `passwordEnabled`; the strategy factory dispatches using the selected strategy identity instead of relying solely on a shared hostname. The router-specific delay preferences are read by the strategies, and the service reads preload/thread preferences when a session starts.

This is positive evidence that the test engine was not simply replaced by a mock UI. It is **not**, by itself, proof that each portal succeeds on a real router; that requires a runtime test against the authorized router/fixture.

### 2.2 Critical lifecycle defect: failed login-page verification does not block card submission

**Files:**
- `app/src/main/java/com/example/service/TestService.kt:370–399`
- `app/src/main/java/com/example/service/TestService.kt:533–537`
- `app/src/main/java/com/example/service/TestService.kt:602–607`
- `app/src/main/java/com/example/service/TestService.kt:668–673`
- `app/src/main/java/com/example/service/TestService.kt:751–785`

`ensureFreshLoginPage(...)` returns `Boolean` and can return `false` when the page was not verified. But these call sites invoke it without checking the return value. In the worker path, the code then sets `lifecycleState = LOGIN_PAGE_READY` and proceeds to the card submission. In finalization, the code calls `ensureFreshLoginPage(...)`, waits, and sets `READY_FOR_NEXT` unconditionally.

That means the current “only READY_FOR_NEXT may consume the next card” check is not a sufficient barrier: the code itself sets `READY_FOR_NEXT` even if the page was not verified.

**Impact:** slow/failed navigation, an unexpected portal state, a stale DOM, or a login page that is not ready can allow a card to be submitted too early. This is a concrete contradiction of Phase 6.2's stated lifecycle guarantee and means the updated source cannot yet be signed off as maintaining the previous test flow correctly.

**Required fix:** every call site must handle `false`. Do not mark a page `LOGIN_PAGE_READY` or a worker `READY_FOR_NEXT` until the strategy verifies the fresh login page. Use bounded retry/recovery. If a fresh login page cannot be established, classify the attempt as a technical engine/network problem (not an invalid card), stop or safely pause the affected worker/session, and do not feed it the next card. Add tests for a timeout, missing login selector, stale DOM, and late `onPageFinished` callback.

### 2.3 Navigation while a session is running

**Files:**
- `app/src/main/java/com/example/presentation/MainActivity.kt:155–182`
- `app/src/main/java/com/example/presentation/test/TestFragment.kt:65–70`
- `app/src/main/java/com/example/presentation/test/TestViewModel.kt` (service-backed state flow)
- `app/src/main/java/com/example/service/TestService.kt`

The service is separate from the screen and the UI observes service state. There is no evidence in the inspected screen lifecycle path that merely destroying a Test screen automatically sends `ACTION_CANCEL`; cancellation is wired to an explicit user action. MainActivity also attempts stale-session cleanup only when `TestService.isRunning` is false. These are good design signs for continuity while navigating/recreating the activity.

However, drawer navigation uses direct `navController.navigate(R.id.nav_...)` for top-level destinations without `launchSingleTop`/`popUpTo` safeguards, and the Test screen's “go home” action calls `navigate(nav_home_fragment)` directly. This can build duplicate top-level entries. Going Home → Settings → History → Test can produce a back stack different from the intended one, and Back may return to old screen instances.

The Phase 8 test `testActiveSessionNavigationContinuity()` only constructs a `ServiceState` object and checks its values; it does not exercise a real NavController, Fragment lifecycle, language recreation, or a running foreground service. So the report's stronger navigation guarantee is not proven by that unit test.

**Required fix:** define top-level navigation behavior explicitly with `popUpTo(graph start or top-level graph) { saveState = true }`, `launchSingleTop = true`, and `restoreState = true` as appropriate. Keep the running service state as the source of truth and write instrumentation/integration tests (or a testable navigation wrapper) that assert no service cancellation/restart and no duplicate destinations across the requested navigation matrix.

### 2.4 Active session plus Clear History is unsafe

**Files:**
- `app/src/main/java/com/example/presentation/settings/SettingsViewModel.kt:251–257`
- `app/src/main/java/com/example/data/local/database/TestResultDao.kt:45–49`
- `app/src/main/java/com/example/data/local/database/SessionDao.kt:81–85`
- `app/src/main/java/com/example/data/local/entity/TestResultEntity.kt:8–20`

`confirmClearHistory()` unconditionally deletes all result rows and all session rows. It does not check whether `TestService` has an active session, pause the service, or stop it safely. `TestResultEntity` stores `sessionId` as a plain value and does not declare a Room foreign-key relationship that would protect referential integrity.

If the user clears history while a session is running, `TestService` may continue inserting results for the now-deleted session ID. Count updates/mark-finished then target a session row that no longer exists. The result history can contain orphaned results and the active session's record can disappear while processing continues.

**Required fix:** while a session is active, either disable Clear History with an explanatory localized message or require an explicit safe stop/finish workflow before deleting. Perform the deletion transactionally and ensure the service cannot write into deleted session history. Add a test for clear-history while RUNNING.

### 2.5 Result counts and semantic identity

**Files:**
- `app/src/main/java/com/example/service/TestService.kt:454–493`
- `app/src/main/java/com/example/data/mapper/TestResultMapper.kt:12–32`
- `app/src/main/java/com/example/data/mapper/TestResultMapper.kt:44–58`
- `app/src/main/java/com/example/data/local/entity/TestResultEntity.kt:8–20`

The service increments the `failureCounter` for every outcome that is not treated as the one authoritative success, including `Timeout`, `Network_Error`, and `Engine_Error`. It then persists only `state` and `message`; `TestResultMapper` later infers `subReason` by examining those values and matching message text. For `TWO_DEVICES_SUCCESS`, the mapper checks Arabic fragments such as `جهازين` / `جهازان` or a sentinel token rather than reading a persisted `successReason`/`subReason` field.

This leaves two integrity weaknesses:

1. Session-level `failureCount` can include technical failures, contrary to the earlier Phase 7 requirement to distinguish a bad card from a technical/network failure.
2. The two-devices classification is text-dependent after persistence. If the wording changes, is English, or is normalized differently, the same successful result can be mapped to normal success instead of `TWO_DEVICES_SUCCESS`.

**Required fix:** persist a structured category and subreason as fields in the authoritative outcome/result row. Drive counters/statistics from the category; do not classify from translated display text. Ensure the two-devices success signal travels from `CardTestOutcome` through the persisted entity to the UI unchanged.

---

## 3. Settings audit — does each visible option work?

| Settings item | Source-level result | Finding |
|---|---|---|
| Light / Dark / System theme | **Partially works** | Selection is persisted, `AppCompatDelegate` is updated, and the Activity is recreated. However, option labels are hardcoded as mixed Arabic/English, and Compose/XML theme palettes do not fully agree. |
| App language | **Partially works** | Language preference is persisted and locale application/activity recreation are wired. But many visible strings remain hardcoded Arabic, and a third “System default” option remains despite the two-language requirement. |
| Red / Blue / Purple / Yellow / Green primary color | **Partially works** | All five choices, preview, confirmation dialog, persistence, and activity recreation are wired. However, color definitions diverge by rendering layer, and bottom navigation still references red-specific color resources. |
| Page-load delay | **Works, next load/session behavior** | Saves the selected value; strategies read `page_load_delay`. The dialog's allowed values are narrower than the original resource options. |
| Card-result wait | **Works, next card/session behavior** | Saves the selected value; strategies read `card_test_delay`. Motasem has a strategy fallback of 3500 ms while the Settings default/reset is 3000 ms, so its default behavior differs if no explicit preference exists. |
| Screenshot/live-preview refresh delay | **Works** | Service reads `screenshot_delay`. The new Settings dialog offers 1/2/3/4 seconds, whereas the original resource list offered 0.5/1/2/3/5 seconds. |
| Reset delays | **Works** | Writes SharedPreferences and DataStore defaults for page load, card result, and screenshot. |
| Background preload | **Works at next session initialization** | The service reads this flag before creating the WebView pool; toggling it does not resize an already-running pool. |
| Background page/thread count | **Partially works, misleading UI** | The service reads the preference, but caps the pool at 3 for standard-RAM devices or 1 for low-RAM devices. The UI offers “5 pages” without explaining this cap. `threadCountVal` is read once from SharedPreferences rather than observed as state, so the displayed value may remain stale after selection until recomposition/recreation. |
| Vibrate on successful card | **Does not perform the advertised action** | The value is saved to SharedPreferences/DataStore, but there is no `Vibrator`/`VibrationEffect` call or success-result consumer in the application source. This appears to be a pre-existing dead setting in the original as well. |
| Play sound on success | **Does not perform the advertised action** | The value is saved, but there is no `SoundPool`, `MediaPlayer`, or other success sound playback path in the application source. This appears pre-existing in the original as well. |
| Manage router profiles | **Wired** | Settings callback navigates to the router manager destination. Router form functionality still requires real UI verification. |
| Clear all history | **Dangerous while running** | Confirmation exists, but deletion is unconditional and can erase the active session row while the service continues. |
| Export all results | **Partially works, usability/privacy concern** | Generates a JSON file under app-specific external files (or internal files fallback) and displays only the filename. Settings does not offer a share/open/file-picker action. `TestResultMapper` passes the raw `cardCode` into the exported model; confirm whether codes should be masked or export should be opt-in with a clear sensitive-data warning. |
| Current version | **No action** | The row is wired with `onClick = {}`. In the original, version was displayed as a preference summary rather than a functional action, so this is not a new engine regression, but the new row looks clickable. |
| GitHub repository | **Wired** | Opens the external URL with an intent. An `ActivityNotFoundException` guard would make this more robust, but it is not a main blocker. |

### Evidence notes for settings

- The new Settings UI is actually wired from `SettingsFragment` through `ComposeView` to `SettingsScreenCompose`.
- Theme and language dialogs live in `SettingsFragment`; they are **not** translated fully because their option arrays contain Arabic literals.
- `SettingsViewModel` setters prove preference persistence, but persistence alone does not prove the advertised feature executes. The sound and vibration switches are the clearest example.
- Delay arrays in the original `values/arrays.xml` include 1, 1.5, 2, 2.5, 3, 4, and 5 seconds; the updated dialog uses only 1, 2, 3, and 5 seconds for load/result waits. The original screenshot list includes 0.5 seconds and 5 seconds; the updated dialog uses 1, 2, 3, and 4 seconds. These are changes in user-visible options that should be deliberate, not accidental.

---

## 4. Arabic / English localization audit

### 4.1 The XML string resources are complete, but the UI is not

The source scan found:

- `values/strings.xml`: 233 string keys
- `values-en/strings.xml`: 233 keys
- `values-ar/strings.xml`: 233 keys
- No missing/extra keys between those three files

That is good resource-level parity. It does **not** mean the actual interface is fully localized. The updated code scan found Arabic characters on 461 source lines across 33 Kotlin files. Some hits are expected portal phrases/logging used by the router engine, but many hits are direct visible UI strings in Compose/Activity/Fragment files.

Confirmed user-facing examples include:

- `presentation/compose/SettingsScreenCompose.kt`: section headings, row titles, subtitles, theme labels, language labels, notifications, delay labels, data-management labels, export labels, About/version/repository labels are hardcoded Arabic.
- `presentation/settings/SettingsFragment.kt`: language choices, theme options, delay options, dialog titles and Toasts are hardcoded Arabic or mixed Arabic/English.
- `presentation/MainActivity.kt`: destination subtitles include hardcoded Arabic such as session logs, system appearance customization, hotspot portal profiles, router profile, and connection/portal configuration.
- `presentation/compose/HomeScreenCompose.kt`, `DrawerMenuCompose.kt`, `TestScreenCompose.kt`, `RouterFormScreenCompose.kt`, `HistoryScreenCompose.kt`, `SessionDetailCompose.kt`, `RouterManagerScreenCompose.kt`, `SecurityScreenCompose.kt`, and `LockedScreenCompose.kt` also contain Arabic UI literals.

Therefore, when English is selected, a number of visible controls and dialogs will remain Arabic. This directly matches the user's reported symptom and directly contradicts the Phase 8 report's claim that no Arabic UI text leaks into English.

### 4.2 The `AppLanguage` enum is not the runtime source of truth

`AppLanguage.kt` defines `AR`/`EN`, and the Phase 8 unit test verifies that enum. But the runtime code path uses a string preference (`app_language`) and `LocaleHelper`; `AppLanguage` is not used by the actual screen language-selection flow as a canonical state model. Passing a test for the enum therefore does not establish that all screens use the chosen locale.

### 4.3 Third language option conflicts with the requested product contract

`SettingsFragment.showLanguageSelectionDialog()` exposes `العربية`, `English`, and `افتراضي النظام` (`ar`, `en`, `system`). The requested application contract is exactly Arabic and English. In addition, `LocaleHelper` resolves the system case to English only when the system language is English, and otherwise falls back to Arabic; that is not a general system-locale mode, and it adds an extra state the product did not ask for.

### 4.4 Required localization fix

- Move every visible UI string into `strings.xml` / `values-en/strings.xml` / `values-ar/strings.xml` and retrieve it through localized resources (including Compose via `stringResource`).
- Localize dialog options, toasts, accessibility descriptions, validation, empty/loading/error states, toolbar subtitles, router forms, history, test controls, and security/locked screens—not only the standard XML resources.
- Keep router portal matching phrases separate from UI labels; portal matching may need to recognize the router's actual Arabic text, but messages shown to the user should be rendered from `ResultCategory`/`ResultSubReason` using the selected locale.
- Remove the third System language option if the decision is strictly two languages, or obtain explicit product approval to keep it. For this request, the expected answer is to remove it.
- Add a source/UI test that scans user-facing Compose and Kotlin literals for Arabic text (allowlist only technically required portal phrases), plus screenshot/instrumentation tests for English UI. The existing test checks formatted result strings only; it does not inspect the Settings screen or the rest of the interface.

---

## 5. Primary color and theme audit

### What works

- `AppPrimaryColor` contains five colors, with RED as `DEFAULT`.
- The color picker lists the five options and previews the selection.
- Confirm applies the preference, writes through the theme preference API, and recreates the Activity.
- `MainActivity` calls `setTheme(primaryColor.styleResId)` before creating the view hierarchy.
- Semantic result color tokens exist separately from the primary palette.

### Defects

1. **Palette mismatch across layers.** `AppPrimaryColor.kt` defines Red `#FFE53935`, Blue `#FF1976D2`, Purple `#FF5E35B1`, Yellow `#FFFBC02D`, Green `#FF2E7D32`. The Compose `Theme.kt` separately defines Blue `#FF2979FF`, Purple `#FF7C4DFF`, Yellow `#FFFFB300`, Green `#FF00E676`, and Red `#FFFF1E27` (with different dark shades). So Compose colors can differ from color picker preview / XML themes.
2. **Bottom navigation remains red-specific.** `values/themes.xml` and `values-night/themes.xml` set `ThemeOverlay.App.BottomNavigationView` to `theme_red_transparent` / `theme_red_primary`. Selecting Blue, Purple, Yellow, or Green will not fully recolor the selected bottom-navigation item.
3. **Yellow contrast mismatch.** `SettingsFragment` deliberately sets the yellow preview button's text to black, but Compose `Theme.kt` uses `onPrimary = Color.White` for all primary colors. Yellow's actual Compose primary therefore can have poor contrast on white text, and the preview does not match actual UI.
4. **Hardcoded selection accents.** The color picker selection border uses the red literal `#FFFF1E27`, even when another primary color is selected. This may be acceptable as a fixed selection affordance only if deliberately styled that way; otherwise it violates the dynamic-primary requirement.
5. **The Phase 8 unit test does not verify actual palette convergence.** It checks enum values and a handful of tokens, not that Compose, AppCompat XML, bottom navigation, preview, and every primary-dependent UI element have the same selected color.

### Required color fix

Make a single source of truth for the five palette values, dark variants, `onPrimary`/contrast colors, and associated XML resource integration. Eliminate duplicate hardcoded palette values. Use color-on-primary that is computed/defined per palette (Yellow should use a dark foreground where contrast demands it). Change the bottom-navigation overlay to use the active primary palette, not red resources. Test all five colors in both light/dark modes, especially bottom navigation, selected chips, buttons, sliders, dialogs, and yellow text contrast. Keep Normal Success and Two-Devices Success tokens independent from primary colors.

---

## 6. Assessment of the embedded Phase 8 report and tests

The bundled `PHASE_8_NAVIGATION_LOCALIZATION_THEME.md` claims complete localization, no Arabic UI leakage, successful navigation continuity, 98 tests passed, lint success, and a successful APK. The source contradicts the localization claim, and the navigation code contradicts the claim that top-level destinations are not pushed redundantly.

The current `Phase8NavigationLocalizationThemeTest.kt` tests useful model-level properties, but its navigation continuity test constructs a `ServiceState` instance in memory; its localization test checks formatted result tokens, not all screen text; and its color tests check enum/palette models without comparing actual theme XML and Compose rendering values. These tests are too narrow to substantiate the report's broader claims.

My local build/test/lint attempt was blocked before Gradle could run because the wrapper distribution could not be fetched due to unavailable DNS/network. This does **not** prove the code fails to compile; it means the claimed successful build/tests were not independently reproduced in this audit environment.

---

## 7. Required remediation order

### Gate 1 — Restore test lifecycle correctness (must fix before acceptance)

1. Respect the return value of `ensureFreshLoginPage()` at every call site.
2. Do not transition to `LOGIN_PAGE_READY` or `READY_FOR_NEXT` without successful strategy verification.
3. Add bounded retry and terminal technical-error behavior; never submit a card against an unverified/stale page.
4. Add regression tests for false return, timeout, late page callback, and no login selector.
5. Re-check progress/results/counters against the persisted result category.

### Gate 2 — Settings data safety and actual behavior

1. Prevent Clear History while testing, or implement an explicit safe stop-and-delete transaction.
2. Implement the advertised success vibration and sound actions, or remove/disable those settings until implementation exists.
3. Display effective WebView pool size/cap (not merely requested size) and observe thread-count preference reactively.
4. Improve export affordance and decide whether exported card codes must be masked.
5. Preserve the user's original delay options unless changing them is intentional and documented.

### Gate 3 — Localization completion

1. Externalize visible UI literals in every Compose/Activity/Fragment screen and dialog.
2. Make Arabic/English the two official choices and remove the inconsistent System option.
3. Ensure `LocaleHelper`, preference storage, and runtime UI use one language source of truth.
4. Ensure direction, alignment, labels, Toasts, content descriptions, and dynamic result labels update at runtime.
5. Add tests that fail when a visible English UI string contains Arabic characters.

### Gate 4 — Theme consistency

1. Consolidate enum, Compose, and XML palette values.
2. Make bottom navigation use the selected primary color.
3. Fix per-color foreground contrast, especially Yellow in both light and dark modes.
4. Test the same selected color through the picker, current screen, navigation, settings, dialogs, and restart persistence.
5. Verify semantic success/failure colors remain unchanged by primary color switches.

### Gate 5 — Navigation and evidence

1. Add `popUpTo` / `launchSingleTop` / state restoration to top-level navigation as appropriate.
2. Verify active test service continuity through Home → Settings → History → Test, both with and without Activity recreation.
3. Add integration/instrumented tests for actual navigation and localization, not only model objects.
4. Run `testDebugUnitTest`, `assembleDebug`, and `lintDebug` in an environment with a working Gradle distribution; attach unedited command output and test counts to the next report.

---

## 8. Acceptance decision

**Phase 8: `NOT ACCEPTED`**

The design update is present, and several settings are wired; but the core test lifecycle barrier is not fail-safe, English localization is incomplete, top-level navigation can duplicate back-stack entries, Clear History is unsafe during an active session, two success-notification settings are nonfunctional, and primary color application is inconsistent.

Do not treat the embedded Phase 8 report as sufficient acceptance evidence. The next step should be a focused **Phase 8.1 — Verified Remediation**, without a broad redesign and without disturbing the existing portal strategies/result behavior.
