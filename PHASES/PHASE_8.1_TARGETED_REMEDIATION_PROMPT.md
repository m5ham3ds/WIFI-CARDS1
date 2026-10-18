# PHASE 8.1 — Verified Remediation Only

Use the current updated WiFi Card Master Pro project as the source of truth. This is a narrow correction phase based on a source audit. Do not redesign the app, do not replace the test engine, and do not remove existing router strategies.

## Absolute rules

1. Preserve the current design except where a confirmed bug must be fixed.
2. Do not rewrite the router strategy logic or modify portal selectors/challenge logic unrelated to the findings below.
3. Keep Arabic and English as the two supported app languages. Remove the System language choice.
4. Do not claim tests/build/lint passed unless the commands were actually run and their outputs exist.
5. Create `PHASE_8.1_TARGETED_REMEDIATION_REPORT.md` with evidence, paths, line ranges, real test counts, and any blocked items.

## A. P0 — Enforce fresh-login-page verification

File: `app/src/main/java/com/example/service/TestService.kt`

`ensureFreshLoginPage(...)` returns `Boolean`, but several callers ignore the result. Fix every call site.

- A worker must not set `LOGIN_PAGE_READY` unless the function returns true.
- Finalization must not set `READY_FOR_NEXT` unless successful login-page verification is established.
- If verification fails, use bounded retries with a clear timeout. Never inject credentials into an unknown/stale page.
- If recovery fails, do not classify the card as invalid. Record/emit a technical network/engine outcome appropriately, then pause or stop the session safely so the queue cannot continue against the wrong page.
- Ensure cancellation remains cancellable and stale WebView callbacks cannot satisfy a newer navigation generation.
- Cover every call site: initial pool login, pre-card worker entry, retry, global relogin, and post-result reset.

Add regression tests for:
1. page verified true;
2. page verification returns false;
3. timeout / missing username selector;
4. stale DOM/old callback arrives after a new navigation;
5. no next card is submitted until the fresh login page is verified;
6. no progress/counter update is falsely emitted as a card rejection on technical navigation failure.

## B. P1 — Make result identity authoritative

Files: `TestService.kt`, `TestResultEntity.kt`, `TestResultMapper.kt`, related Room schema/migrations, result classification/UI.

- Do not infer `TWO_DEVICES_SUCCESS` from translated display-message substrings after persistence.
- Persist category and subreason from the authoritative `CardTestOutcome` into the database result model, including `SUCCESS + TWO_DEVICES_SUCCESS`.
- Keep technical outcomes (`TIMEOUT`, `NETWORK_ERROR`, `ENGINE_ERROR`) separate from invalid/expired card outcomes in session counters and history statistics.
- Create a Room migration safely; do not use destructive fallback or fabricate historical meanings. Backfill only where evidence is reliable and document unknown legacy reasons.
- Make sure existing Two-Devices amber styling and normal-success green styling remain distinct.

## C. P1 — Protect active history

File: `SettingsViewModel.kt`, settings UI, session/result repositories.

- `Clear History` must not delete the active session while `TestService` is running.
- Either disable the action with a localized explanation while active or require an explicit safe stop/finish before deletion.
- Ensure result writes cannot create orphan rows after session deletion.
- Perform the deletion atomically where appropriate.
- Add tests for clearing while idle, clearing while running, and post-clear state.

## D. P1 — Localize all user-visible screens/dialogs

The XML resource key sets are already aligned, but that does not fix hardcoded Compose/Kotlin UI text.

Replace hardcoded user-facing strings with resources in at least:
- `SettingsScreenCompose.kt`
- `SettingsFragment.kt`
- `MainActivity.kt` header/destination subtitles
- `HomeScreenCompose.kt`
- `DrawerMenuCompose.kt`
- `TestScreenCompose.kt`
- `RouterFormScreenCompose.kt`
- `HistoryScreenCompose.kt`
- `SessionDetailCompose.kt`
- `RouterManagerScreenCompose.kt`
- `SecurityScreenCompose.kt`
- `LockedScreenCompose.kt`
- any other visible screen/dialog/toast/validation/loading/empty/accessibility text found by a source scan.

Rules:
- `values/strings.xml` and `values-en/strings.xml` must be English; `values-ar/strings.xml` must be Arabic; all keys must match.
- Use `stringResource(...)` in Compose and `getString(...)`/resources in Android views and dialogs.
- Do not translate portal parsing fixtures/expected router text if they must match the real portal. Separate machine matching constants from user-facing messages.
- Use exactly two language choices: Arabic (`ar`) and English (`en`). Remove `system` from the Settings language selector and make `AppLanguage`/locale preference handling consistent with one source of truth.
- Verify toolbar subtitles, Toasts, confirmation dialogs, export messages, validations, result labels, accessibility descriptions, and error states too.

Add automated localization checks for UI resource usage and an English UI scan that detects Arabic glyphs in user-facing resources/literals, with a narrow explicit allowlist for router fixture/matching phrases that are not shown to the user. The existing result-token-only check is insufficient.

## E. P1 — Make theme colors truly centralized

Files include `AppPrimaryColor.kt`, Compose `Theme.kt`, `MainActivity.kt`, `values/themes.xml`, `values-night/themes.xml`, and color-picker assets.

- Define one authoritative palette for RED/BLUE/PURPLE/YELLOW/GREEN, including dark variants and per-color `onPrimary` contrast values.
- Ensure Compose and AppCompat/XML values are consistent with that palette.
- Replace the red-only colors in `ThemeOverlay.App.BottomNavigationView` with colors that follow the selected primary.
- Ensure the picker preview matches the actual app after confirmation.
- Ensure Yellow uses a readable foreground (dark text where needed) in Compose, XML, buttons and dialogs.
- Keep primary colors independent from result-semantic colors (`SUCCESS_NORMAL` and `SUCCESS_TWO_DEVICES`).
- Keep RED as the first-install default and keep confirmation before applying a different color.
- Add tests/integration evidence that check all five colors in light and dark mode, including bottom navigation and contrast-sensitive controls.

## F. P1 — Fix navigation stack without stopping the test service

File: `MainActivity.kt` and test navigation callbacks.

- Prevent duplicate top-level destinations when selecting Home/Test/History/Routers/Settings from the drawer or bottom nav.
- Use correct `popUpTo`, `launchSingleTop`, and saved-state restoration semantics for this graph.
- Going from the Test page to Home must not accidentally leave duplicate Home/Test entries.
- Navigation or Activity recreation must not send `ACTION_CANCEL`, restart the service, clear progress, or enqueue duplicate cards.
- Return to Test must observe the current service state/session instead of creating a new session.

Add an integration-testable navigation policy and assertions for idle and active sessions. A test that merely creates a `ServiceState` value is not enough to prove navigation continuity.

## G. P2 — Correct Settings behavior

### Vibration and sound

The current toggles save flags but have no code that vibrates/plays a sound. Implement the actual success notification effects with correct Android APIs, permission/lifecycle handling, and preference checks. If a feature cannot safely be implemented in this phase, remove/disable its toggle and report that explicitly rather than leaving a fake setting.

### Background page count

- Explain the effective safe cap to the user or show the effective pool size.
- Since the engine limits to 1 on low-RAM and 3 otherwise, do not imply that 5 concurrent pages will actually be used.
- Make the displayed setting reactive by observing the preference rather than taking a one-time SharedPreferences snapshot.
- Keep changes safe for the current running session; document that the next session uses the new pool size if that is the intended contract.

### Delay options

Compare with the original settings resources. Restore the original delay options unless there is an intentional documented reason to change them. In particular, review 1.5s/2.5s options and the original screenshot delay choices (0.5s/1s/2s/3s/5s). Localize every option.

### Export and About

- Ensure export is discoverable to the user via a Share/Open/Save action or documented file location.
- Treat raw card codes as sensitive; require explicit informed export and mask if that is the intended privacy contract.
- Make the version row non-clickable if it has no action, or wire a meaningful details dialog.

## H. Verification gate

Run and report actual results for:

- `./gradlew testDebugUnitTest --no-daemon`
- `./gradlew assembleDebug --no-daemon`
- `./gradlew lintDebug --no-daemon`

Also add tests for:
- fresh-login-page fail-closed behavior;
- technical vs card-failure counters;
- clear-history protection during active session;
- English visible UI contains no Arabic glyphs;
- Arabic UI resources are complete and direction is RTL;
- language change during active session;
- navigation stack uniqueness;
- persistence/defaults of five primary colors in light/dark;
- selected color updates bottom navigation;
- sound/vibration settings execute the advertised action;
- active TestService is not restarted/canceled by navigation, language, or theme change.

If network/toolchain availability prevents execution, document it as `BLOCKED`; do not mark `COMPLETE` or claim tests passed based only on authored tests.

## Final status rule

Use `COMPLETE` only if all P0/P1 gates above are implemented and verified. Otherwise use `COMPLETE_WITH_BLOCKERS` or `BLOCKED` and list precise remaining defects. Do not perform unrelated redesign work.
