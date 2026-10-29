package com.arun.downloader.runtime.ios

/**
 * Public helper consumed by iOS application delegates:
 *
 * In Swift AppDelegate:
 * ```swift
 * func application(
 *     _ application: UIApplication,
 *     handleEventsForBackgroundURLSession identifier: String,
 *     completionHandler: @escaping () -> Void
 * ) {
 *     IosAppDelegateHelper.shared.handleEventsForBackgroundURLSession(
 *         identifier: identifier,
 *         completionHandler: completionHandler
 *     )
 * }
 * ```
 */

object IosAppDelegateHelper {
    private var bridge: IosBackgroundSessionBridge? = null

    // The OS may call the app delegate before the downloader (and its bridge) is created.
    private val pendingHandlers = mutableMapOf<String, () -> Unit>()

    fun attachBridge(activeBridge: IosBackgroundSessionBridge) {
        bridge = activeBridge
        pendingHandlers.forEach { (identifier, handler) ->
            activeBridge.registerBackgroundCompletionHandler(identifier, handler)
        }
        pendingHandlers.clear()
    }

    fun handleEventsForBackgroundURLSession(identifier: String, completionHandler: () -> Unit) {
        val activeBridge = bridge
        if (activeBridge != null) {
            activeBridge.registerBackgroundCompletionHandler(identifier, completionHandler)
        } else {
            // Keep the handler until the bridge is attached; it runs once all events are processed.
            pendingHandlers[identifier] = completionHandler
        }
    }
}