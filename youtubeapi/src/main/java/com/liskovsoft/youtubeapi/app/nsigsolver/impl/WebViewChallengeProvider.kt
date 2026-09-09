package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.YouTubeInfoExtractor
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeOutput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProvider
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeType
import com.liskovsoft.youtubeapi.app.nsigsolver.webview.CipherWebView
import com.liskovsoft.youtubeapi.app.nsigsolver.webview.FunctionNameExtractor

/**
 * Ultra-fast WebView-based YouTube challenge solver ported from Flow.
 * Evaluates cipher and n-param transforms on Chromium's native V8 engine in <1ms.
 */
internal object WebViewChallengeProvider : JsChallengeProvider() {
    private val TAG = WebViewChallengeProvider::class.java.simpleName
    override val supportedTypes = listOf(JsChallengeType.N, JsChallengeType.SIG)

    private val lock = Any()
    @Volatile private var currentWebView: CipherWebView? = null
    @Volatile private var currentPlayerUrl: String? = null
    @Volatile private var cachedSignatureTimestamp: Int? = null

    fun getSignatureTimestamp(): Int? = cachedSignatureTimestamp

    fun isWarm(): Boolean = currentWebView != null

    override fun realBulkSolve(requests: List<JsChallengeRequest>): Sequence<JsChallengeProviderResponse> = sequence {
        val grouped = requests.groupBy { it.input.playerUrl }

        for ((playerUrl, groupedRequests) in grouped) {
            val webView = getOrCreateWebView(playerUrl)
            if (webView == null) {
                for (req in groupedRequests) {
                    yield(JsChallengeProviderResponse(req, null, JsChallengeProviderError("Failed to initialize CipherWebView for $playerUrl")))
                }
                continue
            }

            for (request in groupedRequests) {
                val results = mutableMapOf<String, String>()
                var hasError = false
                var errorMsg: String? = null

                for (challenge in request.input.challenges) {
                    val solved = when (request.type) {
                        JsChallengeType.N -> webView.transformN(challenge)
                        JsChallengeType.SIG -> webView.deobfuscateSignature(challenge)
                    }

                    if (solved != null) {
                        results[challenge] = solved
                    } else {
                        hasError = true
                        errorMsg = "Failed to solve ${request.type} challenge: $challenge"
                        break
                    }
                }

                if (hasError) {
                    yield(JsChallengeProviderResponse(request, null, JsChallengeProviderError(errorMsg ?: "Unknown error")))
                } else {
                    yield(JsChallengeProviderResponse(request, JsChallengeResponse(request.type, ChallengeOutput(results))))
                }
            }
        }
    }

    private fun getOrCreateWebView(playerUrl: String): CipherWebView? {
        synchronized(lock) {
            if (currentWebView != null && currentPlayerUrl == playerUrl) {
                return currentWebView
            }

            currentWebView?.close()
            currentWebView = null
            currentPlayerUrl = null

            val context = AppService.instance().context

            val playerJs = YouTubeInfoExtractor.loadPlayerSilent(playerUrl)
            if (playerJs.isNullOrEmpty()) {
                Log.e(TAG, "Failed to load player.js from $playerUrl")
                return null
            }

            val analysis = FunctionNameExtractor.analyzePlayerJs(playerJs)
            cachedSignatureTimestamp = analysis.signatureTimestamp

            if (analysis.sigInfo == null && analysis.nFuncInfo == null) {
                Log.e(TAG, "Could not extract sig or n function info from player.js")
                return null
            }

            val wv = CipherWebView.create(
                context = context,
                playerJs = playerJs,
                sigInfo = analysis.sigInfo,
                nFuncInfo = analysis.nFuncInfo
            )

            if (wv != null) {
                currentWebView = wv
                currentPlayerUrl = playerUrl
            }
            return wv
        }
    }

    fun warmup(playerUrl: String? = null) {
        if (playerUrl != null) {
            getOrCreateWebView(playerUrl)
        }
    }

    fun invalidateCache() {
        synchronized(lock) {
            currentWebView?.close()
            currentWebView = null
            currentPlayerUrl = null
            cachedSignatureTimestamp = null
        }
    }
}
