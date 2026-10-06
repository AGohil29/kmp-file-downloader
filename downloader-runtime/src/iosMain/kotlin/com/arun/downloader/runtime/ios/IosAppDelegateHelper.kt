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

    fun attachBridge(activeBridge: IosBackgroundSessionBridge) {
        bridge = activeBridge
    }

    fun handleEventsForBackgroundURLSession(identifier: String, completionHandler: () -> Unit) {
        val activeBridge = bridge
        if (activeBridge != null) {
            activeBridge.registerBackgroundCompletionHandler(identifier, completionHandler)
        } else {
            // Immediately invoke callback if bridge is unattached to release iOS system assertion
            completionHandler()
        }
    }
}