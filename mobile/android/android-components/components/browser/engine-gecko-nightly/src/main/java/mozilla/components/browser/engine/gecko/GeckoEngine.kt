/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.engine.gecko

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.VisibleForTesting
import mozilla.components.browser.engine.gecko.integration.LocaleSettingUpdater
import mozilla.components.browser.engine.gecko.mediaquery.from
import mozilla.components.browser.engine.gecko.mediaquery.toGeckoValue
import mozilla.components.browser.engine.gecko.webextension.GeckoWebExtension
import mozilla.components.browser.engine.gecko.webnotifications.GeckoWebNotificationDelegate
import mozilla.components.browser.engine.gecko.webpush.GeckoWebPushDelegate
import mozilla.components.browser.engine.gecko.webpush.GeckoWebPushHandler
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.SafeBrowsingPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.TrackingCategory
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.Settings
import mozilla.components.concept.engine.content.blocking.TrackerLog
import mozilla.components.concept.engine.content.blocking.TrackingProtectionExceptionStorage
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.engine.utils.EngineVersion
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.TabHandler
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionDelegate
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.concept.engine.webnotifications.WebNotificationDelegate
import mozilla.components.concept.engine.webpush.WebPushDelegate
import mozilla.components.concept.engine.webpush.WebPushHandler
import mozilla.components.support.utils.ThreadUtils
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.ContentBlockingController
import org.mozilla.geckoview.ContentBlockingController.Event
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebExtensionController

/**
 * Gecko-based implementation of Engine interface.
 */
@Suppress("TooManyFunctions", "LargeClass")
class GeckoEngine(
    context: Context,
    private val defaultSettings: Settings? = null,
    private val runtime: GeckoRuntime = GeckoRuntime.getDefault(context),
    executorProvider: () -> GeckoWebExecutor = { GeckoWebExecutor(runtime) },
    override val trackingProtectionExceptionStore: TrackingProtectionExceptionStorage =
        TrackingProtectionExceptionFileStorage(context, runtime)
) : Engine, WebExtensionRuntime {
    private val executor by lazy { executorProvider.invoke() }
    private val localeUpdater = LocaleSettingUpdater(context, runtime)
    @VisibleForTesting internal var speculativeSession: GeckoEngineSession? = null

    private var webExtensionDelegate: WebExtensionDelegate? = null
    private val webExtensionActionHandler = object : ActionHandler {
        override fun onBrowserAction(extension: WebExtension, session: EngineSession?, action: Action) {
            webExtensionDelegate?.onBrowserActionDefined(extension, action)
        }

        override fun onPageAction(extension: WebExtension, session: EngineSession?, action: Action) {
            webExtensionDelegate?.onPageActionDefined(extension, action)
        }

        override fun onToggleActionPopup(extension: WebExtension, action: Action): EngineSession? {
            return webExtensionDelegate?.onToggleActionPopup(extension, GeckoEngineSession(runtime), action)
        }
    }
    private val webExtensionTabHandler = object : TabHandler {
        override fun onNewTab(webExtension: WebExtension, engineSession: EngineSession, active: Boolean, url: String) {
            webExtensionDelegate?.onNewTab(webExtension, engineSession, active, url)
        }
    }

    private var webPushHandler: WebPushHandler? = null

    init {
        runtime.delegate = GeckoRuntime.Delegate {
            // On shutdown: The runtime is shutting down (possibly because of an unrecoverable error state). We crash
            // the app here for two reasons:
            //  - We want to know about those unsolicited shutdowns and fix those issues.
            //  - We can't recover easily from this situation. Just continuing will leave us with an engine that
            //    doesn't do anything anymore.
            @Suppress("TooGenericExceptionThrown")
            throw RuntimeException("GeckoRuntime is shutting down")
        }
        trackingProtectionExceptionStore.restore()
    }

    /**
     * Fetch a list of trackers logged for a given [session] .
     *
     * @param session the session where the trackers were logged.
     * @param onSuccess callback invoked if the data was fetched successfully.
     * @param onError (optional) callback invoked if fetching the data caused an exception.
     */
    override fun getTrackersLog(
        session: EngineSession,
        onSuccess: (List<TrackerLog>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val geckoSession = (session as GeckoEngineSession).geckoSession
        runtime.contentBlockingController.getLog(geckoSession).then({ contentLogList ->
            val list = contentLogList ?: emptyList()
            val logs = list.map { logEntry ->
                logEntry.toTrackerLog()
            }.filterNot {
                !it.cookiesHasBeenBlocked &&
                    it.blockedCategories.isEmpty() &&
                    it.loadedCategories.isEmpty()
            }

            onSuccess(logs)
            GeckoResult<Void>()
        }, { throwable ->
            onError(throwable)
            GeckoResult<Void>()
        })
    }

    /**
     * Creates a new Gecko-based EngineView.
     */
    override fun createView(context: Context, attrs: AttributeSet?): EngineView {
        return GeckoEngineView(context, attrs)
    }

    /**
     * See [Engine.createSession].
     */
    override fun createSession(private: Boolean, contextId: String?): EngineSession {
        ThreadUtils.assertOnUiThread()
        val speculativeSession = this.speculativeSession?.let { speculativeSession ->
            if (speculativeSession.geckoSession.settings.usePrivateMode == private &&
                speculativeSession.geckoSession.settings.contextId == contextId) {
                speculativeSession
            } else {
                speculativeSession.close()
                null
            }.also {
                this.speculativeSession = null
            }
        }
        return speculativeSession ?: GeckoEngineSession(runtime, private, defaultSettings, contextId)
    }

    /**
     * See [Engine.createSessionState].
     */
    override fun createSessionState(json: JSONObject): EngineSessionState {
        return GeckoEngineSessionState.fromJSON(json)
    }

    /**
     * See [Engine.speculativeCreateSession].
     */
    override fun speculativeCreateSession(private: Boolean, contextId: String?) {
        ThreadUtils.assertOnUiThread()
        if (this.speculativeSession?.geckoSession?.settings?.usePrivateMode != private ||
            this.speculativeSession?.geckoSession?.settings?.contextId != contextId) {
            this.speculativeSession?.geckoSession?.close()
            this.speculativeSession = GeckoEngineSession(runtime, private, defaultSettings, contextId)
        }
    }

    /**
     * Opens a speculative connection to the host of [url].
     *
     * This is useful if an app thinks it may be making a request to that host in the near future. If no request
     * is made, the connection will be cleaned up after an unspecified.
     */
    override fun speculativeConnect(url: String) {
        executor.speculativeConnect(url)
    }

    /**
     * See [Engine.installWebExtension].
     */
    override fun installWebExtension(
        id: String,
        url: String,
        allowContentMessaging: Boolean,
        supportActions: Boolean,
        onSuccess: ((WebExtension) -> Unit),
        onError: ((String, Throwable) -> Unit)
    ) {
        val ext = GeckoWebExtension(id, url, runtime, allowContentMessaging, supportActions)
        installWebExtension(ext, onSuccess, onError)
    }

    internal fun installWebExtension(
        ext: GeckoWebExtension,
        onSuccess: ((WebExtension) -> Unit) = { },
        onError: ((String, Throwable) -> Unit) = { _, _ -> }
    ) {
        if (ext.isBuiltIn()) {
            if (ext.supportActions) {
                // We currently have to install the global action handler before we
                // install the extension which is why this is done here directly.
                // This code can be removed from the engine once the new GV addon
                // management API (specifically installBuiltIn) lands. Then the
                // global handlers will be invoked with the latest state whenever
                // they are registered:
                // https://bugzilla.mozilla.org/show_bug.cgi?id=1599897
                // https://bugzilla.mozilla.org/show_bug.cgi?id=1582185
                ext.registerActionHandler(webExtensionActionHandler)
                ext.registerTabHandler(webExtensionTabHandler)
            }

            // For now we have to use registerWebExtension for builtin extensions until we get the
            // new installBuiltIn call on the controller: https://bugzilla.mozilla.org/show_bug.cgi?id=1601067
            runtime.registerWebExtension(ext.nativeExtension).then({
                webExtensionDelegate?.onInstalled(ext)
                onSuccess(ext)
                GeckoResult<Void>()
            }, { throwable ->
                onError(ext.id, throwable)
                GeckoResult<Void>()
            })
        } else {
            runtime.webExtensionController.install(ext.url).then({
                val installedExtension = GeckoWebExtension(it!!, runtime)
                webExtensionDelegate?.onInstalled(installedExtension)
                installedExtension.registerActionHandler(webExtensionActionHandler)
                installedExtension.registerTabHandler(webExtensionTabHandler)
                onSuccess(installedExtension)
                GeckoResult<Void>()
            }, { throwable ->
                onError(ext.id, throwable)
                GeckoResult<Void>()
            })
        }
    }

    /**
     * See [Engine.uninstallWebExtension].
     */
    override fun uninstallWebExtension(
        ext: WebExtension,
        onSuccess: () -> Unit,
        onError: (String, Throwable) -> Unit
    ) {
        runtime.webExtensionController.uninstall((ext as GeckoWebExtension).nativeExtension).then({
            webExtensionDelegate?.onUninstalled(ext)
            onSuccess()
            GeckoResult<Void>()
        }, { throwable ->
            onError(ext.id, throwable)
            GeckoResult<Void>()
        })
    }

    /**
     * See [Engine.updateWebExtension].
     */
    override fun updateWebExtension(
        extension: WebExtension,
        onSuccess: (WebExtension?) -> Unit,
        onError: (String, Throwable) -> Unit
    ) {
        runtime.webExtensionController.update((extension as GeckoWebExtension).nativeExtension).then({ geckoExtension ->
            val updatedExtension = if (geckoExtension != null) {
                GeckoWebExtension(geckoExtension, runtime).also {
                    it.registerActionHandler(webExtensionActionHandler)
                    it.registerTabHandler(webExtensionTabHandler)
                }
            } else {
                null
            }
            onSuccess(updatedExtension)
            GeckoResult<Void>()
        }, { throwable ->
            onError(extension.id, throwable)
            GeckoResult<Void>()
        })
    }

    /**
     * See [Engine.registerWebExtensionDelegate].
     */
    @Suppress("Deprecation")
    override fun registerWebExtensionDelegate(
        webExtensionDelegate: WebExtensionDelegate
    ) {
        this.webExtensionDelegate = webExtensionDelegate

        val promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPrompt(ext: org.mozilla.geckoview.WebExtension): GeckoResult<AllowOrDeny>? {
                val extension = GeckoWebExtension(ext, runtime)
                return if (webExtensionDelegate.onInstallPermissionRequest(extension)) {
                    GeckoResult.ALLOW
                } else {
                    GeckoResult.DENY
                }
            }

            override fun onUpdatePrompt(
                current: org.mozilla.geckoview.WebExtension,
                updated: org.mozilla.geckoview.WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                // NB: We don't have a user flow for handling updated origins so we ignore them for now.
                val result = GeckoResult<AllowOrDeny>()
                webExtensionDelegate.onUpdatePermissionRequest(
                    GeckoWebExtension(current, runtime),
                    GeckoWebExtension(updated, runtime),
                    newPermissions.toList()
                ) {
                    allow -> if (allow) result.complete(AllowOrDeny.ALLOW) else result.complete(AllowOrDeny.DENY)
                }
                return result
            }
        }

        val debuggerDelegate = object : WebExtensionController.DebuggerDelegate {
            override fun onExtensionListUpdated() {
                webExtensionDelegate.onExtensionListUpdated()
            }
        }

        runtime.webExtensionController.promptDelegate = promptDelegate
        runtime.webExtensionController.setDebuggerDelegate(debuggerDelegate)
    }

    /**
     * See [Engine.listInstalledWebExtensions].
     */
    override fun listInstalledWebExtensions(onSuccess: (List<WebExtension>) -> Unit, onError: (Throwable) -> Unit) {
        runtime.webExtensionController.list().then({
            val extensions = it?.map {
                extension -> GeckoWebExtension(extension, runtime)
            } ?: emptyList()

            extensions.forEach { extension ->
                extension.registerActionHandler(webExtensionActionHandler)
                extension.registerTabHandler(webExtensionTabHandler)
            }
            onSuccess(extensions)
            GeckoResult<Void>()
        }, { throwable ->
            onError(throwable)
            GeckoResult<Void>()
        })
    }

    /**
     * See [Engine.enableWebExtension].
     */
    override fun enableWebExtension(
        extension: WebExtension,
        source: EnableSource,
        onSuccess: (WebExtension) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runtime.webExtensionController.enable((extension as GeckoWebExtension).nativeExtension, source.id).then({
            val enabledExtension = GeckoWebExtension(it!!, runtime)
            webExtensionDelegate?.onEnabled(enabledExtension)
            onSuccess(enabledExtension)
            GeckoResult<Void>()
        }, { throwable ->
            onError(throwable)
            GeckoResult<Void>()
        })
    }

    /**
     * See [Engine.disableWebExtension].
     */
    override fun disableWebExtension(
        extension: WebExtension,
        source: EnableSource,
        onSuccess: (WebExtension) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runtime.webExtensionController.disable((extension as GeckoWebExtension).nativeExtension, source.id).then({
            val disabledExtension = GeckoWebExtension(it!!, runtime)
            webExtensionDelegate?.onDisabled(disabledExtension)
            onSuccess(disabledExtension)
            GeckoResult<Void>()
        }, { throwable ->
            onError(throwable)
            GeckoResult<Void>()
        })
    }

    /**
     * See [Engine.registerWebNotificationDelegate].
     */
    override fun registerWebNotificationDelegate(
        webNotificationDelegate: WebNotificationDelegate
    ) {
        runtime.webNotificationDelegate = GeckoWebNotificationDelegate(webNotificationDelegate)
    }

    /**
     * See [Engine.registerWebPushDelegate].
     */
    override fun registerWebPushDelegate(
        webPushDelegate: WebPushDelegate
    ): WebPushHandler {
        runtime.webPushController.setDelegate(GeckoWebPushDelegate(webPushDelegate))

        if (webPushHandler == null) {
            webPushHandler = GeckoWebPushHandler(runtime)
        }

        return requireNotNull(webPushHandler)
    }

    /**
     * See [Engine.clearData].
     */
    override fun clearData(
        data: Engine.BrowsingData,
        host: String?,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val flags = data.types.toLong()
        if (host != null) {
            runtime.storageController.clearDataFromHost(host, flags)
        } else {
            runtime.storageController.clearData(flags)
        }.then({
            onSuccess()
            GeckoResult<Void>()
        }, {
            throwable -> onError(throwable)
            GeckoResult<Void>()
        })
    }

    override fun name(): String = "Gecko"

    override val version: EngineVersion = EngineVersion.parse(org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION)
        ?: throw IllegalStateException("Could not determine engine version")

    /**
     * See [Engine.settings]
     */
    override val settings: Settings = object : Settings() {
        override var javascriptEnabled: Boolean
            get() = runtime.settings.javaScriptEnabled
            set(value) { runtime.settings.javaScriptEnabled = value }

        override var webFontsEnabled: Boolean
            get() = runtime.settings.webFontsEnabled
            set(value) { runtime.settings.webFontsEnabled = value }

        override var automaticFontSizeAdjustment: Boolean
            get() = runtime.settings.automaticFontSizeAdjustment
            set(value) { runtime.settings.automaticFontSizeAdjustment = value }

        override var automaticLanguageAdjustment: Boolean
            get() = localeUpdater.enabled
            set(value) {
                localeUpdater.enabled = value
                defaultSettings?.automaticLanguageAdjustment = value
            }

        override var safeBrowsingPolicy: Array<SafeBrowsingPolicy> =
            arrayOf(SafeBrowsingPolicy.RECOMMENDED)
            set(value) {
                val policy = value.sumBy { it.id }
                runtime.settings.contentBlocking.setSafeBrowsing(policy)
                field = value
            }

        override var trackingProtectionPolicy: TrackingProtectionPolicy? = null
            set(value) {
                value?.let { policy ->
                    val activateStrictSocialTracking =
                        policy.strictSocialTrackingProtection ?: policy.trackingCategories.contains(
                            TrackingCategory.STRICT
                        )
                    val etpLevel =
                        when {
                            policy.trackingCategories.contains(TrackingCategory.NONE) ->
                                ContentBlocking.EtpLevel.NONE
                            else -> ContentBlocking.EtpLevel.STRICT
                        }
                    runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel(etpLevel)
                    runtime.settings.contentBlocking.setStrictSocialTrackingProtection(
                        activateStrictSocialTracking
                    )
                    runtime.settings.contentBlocking.setAntiTracking(policy.getAntiTrackingPolicy())
                    runtime.settings.contentBlocking.setCookieBehavior(policy.cookiePolicy.id)
                    defaultSettings?.trackingProtectionPolicy = value
                    field = value
                }
            }

        private fun TrackingProtectionPolicy.getAntiTrackingPolicy(): Int {
            /**
             * The [TrackingProtectionPolicy.TrackingCategory.SCRIPTS_AND_SUB_RESOURCES] is an
             * artificial category, created with the sole purpose of going around this bug
             * https://bugzilla.mozilla.org/show_bug.cgi?id=1579264, for this reason we have to
             * remove its value from the valid anti tracking categories, when is present.
             */
            val total = trackingCategories.sumBy { it.id }
            return if (contains(TrackingCategory.SCRIPTS_AND_SUB_RESOURCES)) {
                total - TrackingProtectionPolicy.TrackingCategory.SCRIPTS_AND_SUB_RESOURCES.id
            } else {
                total
            }
        }

        override var remoteDebuggingEnabled: Boolean
            get() = runtime.settings.remoteDebuggingEnabled
            set(value) { runtime.settings.remoteDebuggingEnabled = value }

        override var historyTrackingDelegate: HistoryTrackingDelegate?
            get() = defaultSettings?.historyTrackingDelegate
            set(value) { defaultSettings?.historyTrackingDelegate = value }

        override var testingModeEnabled: Boolean
            get() = defaultSettings?.testingModeEnabled ?: false
            set(value) { defaultSettings?.testingModeEnabled = value }

        override var userAgentString: String?
            get() = defaultSettings?.userAgentString ?: GeckoSession.getDefaultUserAgent()
            set(value) { defaultSettings?.userAgentString = value }

        override var preferredColorScheme: PreferredColorScheme
            get() = PreferredColorScheme.from(runtime.settings.preferredColorScheme)
            set(value) { runtime.settings.preferredColorScheme = value.toGeckoValue() }

        override var suspendMediaWhenInactive: Boolean
            get() = defaultSettings?.suspendMediaWhenInactive ?: false
            set(value) { defaultSettings?.suspendMediaWhenInactive = value }

        override var fontInflationEnabled: Boolean?
            get() = runtime.settings.fontInflationEnabled
            set(value) {
                // automaticFontSizeAdjustment is set to true by default, which
                // will cause an exception if fontInflationEnabled is set
                // (to either true or false). We therefore need to be able to
                // set our built-in default value to null so that the exception
                // is only thrown if an app is configured incorrectly but not
                // if it uses default values.
                value?.let {
                    runtime.settings.fontInflationEnabled = it
                }
            }

        override var fontSizeFactor: Float?
            get() = runtime.settings.fontSizeFactor
            set(value) {
                // automaticFontSizeAdjustment is set to true by default, which
                // will cause an exception if fontSizeFactor is set as well.
                // We therefore need to be able to set our built-in default value
                // to null so that the exception is only thrown if an app is
                // configured incorrectly but not if it uses default values.
                value?.let {
                    runtime.settings.fontSizeFactor = it
                }
            }

        override var forceUserScalableContent: Boolean
            get() = runtime.settings.forceUserScalableEnabled
            set(value) { runtime.settings.forceUserScalableEnabled = value }
    }.apply {
        defaultSettings?.let {
            this.javascriptEnabled = it.javascriptEnabled
            this.webFontsEnabled = it.webFontsEnabled
            this.automaticFontSizeAdjustment = it.automaticFontSizeAdjustment
            this.automaticLanguageAdjustment = it.automaticLanguageAdjustment
            this.trackingProtectionPolicy = it.trackingProtectionPolicy
            this.safeBrowsingPolicy = arrayOf(SafeBrowsingPolicy.RECOMMENDED)
            this.remoteDebuggingEnabled = it.remoteDebuggingEnabled
            this.testingModeEnabled = it.testingModeEnabled
            this.userAgentString = it.userAgentString
            this.preferredColorScheme = it.preferredColorScheme
            this.fontInflationEnabled = it.fontInflationEnabled
            this.fontSizeFactor = it.fontSizeFactor
            this.forceUserScalableContent = it.forceUserScalableContent
        }
    }

    internal fun ContentBlockingController.LogEntry.BlockingData.getLoadedCategory(): TrackingCategory {
        return when (category) {
            Event.LOADED_FINGERPRINTING_CONTENT -> TrackingCategory.FINGERPRINTING
            Event.LOADED_CRYPTOMINING_CONTENT -> TrackingCategory.CRYPTOMINING
            Event.LOADED_SOCIALTRACKING_CONTENT -> TrackingCategory.MOZILLA_SOCIAL
            Event.LOADED_LEVEL_1_TRACKING_CONTENT -> TrackingCategory.SCRIPTS_AND_SUB_RESOURCES
            Event.LOADED_LEVEL_2_TRACKING_CONTENT -> {

                // We are making sure that we are only showing trackers that our settings are
                // taking into consideration.
                val isContentListActive =
                    settings.trackingProtectionPolicy?.contains(TrackingCategory.CONTENT)
                        ?: false
                val isStrictLevelActive =
                    runtime.settings
                        .contentBlocking
                        .getEnhancedTrackingProtectionLevel() == ContentBlocking.EtpLevel.STRICT

                if (isStrictLevelActive && isContentListActive) {
                    TrackingCategory.SCRIPTS_AND_SUB_RESOURCES
                } else {
                    TrackingCategory.NONE
                }
            }
            else -> TrackingCategory.NONE
        }
    }

    internal fun ContentBlockingController.LogEntry.toTrackerLog(): TrackerLog {
        val cookiesHasBeenBlocked = this.blockingData.any { it.hasBlockedCookies() }
        return TrackerLog(
            url = origin,
            loadedCategories = blockingData.map { it.getLoadedCategory() }
                .filterNot { it == TrackingCategory.NONE }
                .distinct(),
            blockedCategories = blockingData.map { it.getBlockedCategory() }
                .filterNot { it == TrackingCategory.NONE }
                .distinct(),
            cookiesHasBeenBlocked = cookiesHasBeenBlocked
        )
    }
}

internal fun ContentBlockingController.LogEntry.BlockingData.hasBlockedCookies(): Boolean {
    return category == Event.COOKIES_BLOCKED_BY_PERMISSION ||
        category == Event.COOKIES_BLOCKED_TRACKER ||
        category == Event.COOKIES_BLOCKED_ALL ||
        category == Event.COOKIES_PARTITIONED_FOREIGN ||
        category == Event.COOKIES_BLOCKED_FOREIGN
}

internal fun ContentBlockingController.LogEntry.BlockingData.getBlockedCategory(): TrackingCategory {
    return when (category) {
        Event.BLOCKED_FINGERPRINTING_CONTENT -> TrackingCategory.FINGERPRINTING
        Event.BLOCKED_CRYPTOMINING_CONTENT -> TrackingCategory.CRYPTOMINING
        Event.BLOCKED_SOCIALTRACKING_CONTENT -> TrackingCategory.MOZILLA_SOCIAL
        Event.BLOCKED_TRACKING_CONTENT -> TrackingCategory.SCRIPTS_AND_SUB_RESOURCES
        else -> TrackingCategory.NONE
    }
}
