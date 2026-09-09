package com.liskovsoft.youtubeapi.app.playerdata

import com.quickjs.QuickJSException
import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper
import com.liskovsoft.sharedutils.helpers.DeviceHelpers
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.app.nsigsolver.common.YouTubeInfoExtractor
import com.liskovsoft.youtubeapi.app.nsigsolver.impl.V8ChallengeProvider
import com.liskovsoft.youtubeapi.app.nsigsolver.impl.WebViewChallengeProvider
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeInput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeOutput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeType
import com.liskovsoft.youtubeapi.app.nsigsolver.remote.PipePipeNsigDecoder
import com.liskovsoft.youtubeapi.app.nsigsolver.webview.FunctionNameExtractor
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData

internal class PlayerDataExtractor(val playerUrl: String) {
    private val tag = PlayerDataExtractor::class.java.simpleName
    private val data
        get() = MediaServiceData.instance()
    private var nFuncCode: Boolean = false
    private var sFuncCode: Boolean = false
    private var cpnCode: String? = null
    private var signatureTimestamp: String? = null
    private val fixedPlayerUrl by lazy {
        // Those are implements global helper functions. No fix. Fallback to regular.
        // See https://github.com/yt-dlp/yt-dlp/issues/12398
        // tv url: https://www.youtube.com/s/player/69b31e11/tv-player-es6-tce.vflset/tv-player-es6-tce.js
        // web url: https://www.youtube.com/s/player/e12fbea4/player_ias_tce.vflset/en_US/base.js
        playerUrl
            //.replace("_tce", "") // global helper functions, web url
            //.replace("/player_ias.vflset/en_US/base.js", "/tv-player-ias.vflset/tv-player-ias.js") // does not validate cpn
            //.replace("-es6", "-ias") // es6 no supported
            //.replace("-tcl", "") // (403 fix, incompatible nParam, e.g. /tv-player-es6-tcl.vflset/tv-player-es6-tcl.js)
            //.replace("/tv-player-es6.vflset/tv-player-es6.js", "/player_es6.vflset/en_US/base.js") // 403 fix, incompatible nParam?
            //.replace("/tv-player-ias.vflset/tv-player-ias.js", "/player_ias.vflset/en_US/base.js") // 403 fix, incompatible nParam?
    }

    init {
        // Get the code from the cache
        restoreAllData()
        checkSigData()
        checkCpnData()

        if (signatureTimestamp == null) {
            fetchAllData()
            checkCpnData()
            persistAllData()
        }
    }

    fun extractNSig(nParam: String): String? {
        return bulkSigExtract(listOf(nParam), null).first?.firstOrNull()
    }

    fun extractSig(sParams: List<String?>): List<String?>? {
        return bulkSigExtract(null, sParams).second
    }

    fun bulkSigExtract(nParams: List<String?>?, sParams: List<String?>?): Pair<List<String?>?, List<String?>?> {
        if (Helpers.allNulls(nParams, sParams)) {
            return Pair(null, null)
        }

        val response = bulkSigExtractReal(nParams, sParams)

        return Pair(response.first, response.second)
    }

    /**
     * "cpn":"KjdxegeSaJXRctIl"
     */
    fun createClientPlaybackNonce(): String? {
        return cpnCode?.let { ClientPlaybackNonceExtractor.createClientPlaybackNonce(it) } ?: YouTubeHelper.generateCPNParameter2()
    }

    /**
     * "signatureTimestamp":20522
     */
    fun getSignatureTimestamp(): String? {
        return signatureTimestamp
    }

    fun setSignatureTimestamp(timestamp: String) {
        signatureTimestamp = timestamp
    }

    fun validate(): Boolean {
        // TODO: fix cpn code
        // return mNFuncCode && mSigFuncCode && mCPNCode != null && mSignatureTimestamp != null
        return nFuncCode && sFuncCode && signatureTimestamp != null
    }

    private fun extractNSigReal(nParam: String): String? {
        return bulkSigExtractReal(listOf(nParam), null).first?.firstOrNull()
    }

    private fun extractSigReal(sParam: List<String>): List<String?>? {
        return bulkSigExtractReal(null, sParam).second
    }

    private fun solveChallengesCascade(requests: List<JsChallengeRequest>): Sequence<JsChallengeProviderResponse> {
        // Level 1: Fast WebView V8 Solver (Flow approach)
        if (DeviceHelpers.isWebViewSupported()) {
            try {
                val result = WebViewChallengeProvider.bulkSolve(requests).toList()
                if (result.isNotEmpty() && result.all { it.error == null }) {
                    return result.asSequence()
                }
            } catch (e: Exception) {
                // Fall through to Level 2
            }
        }

        // Level 2: QuickJS AST Solver (SmartTube existing approach)
        try {
            val result = V8ChallengeProvider.bulkSolve(requests).toList()
            if (result.isNotEmpty() && result.all { it.error == null }) {
                return result.asSequence()
            }
        } catch (e: Exception) {
            // Fall through to Level 3
        }

        // Level 3: Remote PipePipe Failover (for N-transform)
        return sequence {
            for (req in requests) {
                if (req.type == JsChallengeType.N) {
                    try {
                        val decoded = PipePipeNsigDecoder.decodeBatch(req.input.challenges)
                        if (decoded.isNotEmpty()) {
                            yield(JsChallengeProviderResponse(req, JsChallengeResponse(req.type, ChallengeOutput(decoded))))
                            continue
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                yield(JsChallengeProviderResponse(req, null, Exception("All challenge solvers failed")))
            }
        }
    }

    private fun bulkSigExtractReal(nParams: List<String?>?, sParams: List<String?>?): Pair<List<String?>?, List<String?>?> {
        if (Helpers.allNulls(nParams, sParams)) {
            return Pair(null, null)
        }

        var nProcessed: List<String?>? = null
        var sProcessed: List<String?>? = null

        val missingN = if (nFuncCode && nParams != null) {
            nParams.filterNotNull().distinct().filter { StreamChallengeCache.getNSig(fixedPlayerUrl, it) == null }
        } else {
            emptyList()
        }

        val missingS = if (sFuncCode && sParams != null) {
            sParams.filterNotNull().distinct().filter { StreamChallengeCache.getSig(fixedPlayerUrl, it) == null }
        } else {
            emptyList()
        }

        val nRequest = missingN.takeIf { it.isNotEmpty() }?.let {
            JsChallengeRequest(JsChallengeType.N, ChallengeInput(fixedPlayerUrl, it))
        }

        val sRequest = missingS.takeIf { it.isNotEmpty() }?.let {
            JsChallengeRequest(JsChallengeType.SIG, ChallengeInput(fixedPlayerUrl, it))
        }

        val requests = listOfNotNull(nRequest, sRequest)
        if (requests.isNotEmpty()) {
            val result = solveChallengesCascade(requests)
            for (item in result) {
                when (item.response?.type) {
                    JsChallengeType.N ->
                        item.response.output.results.forEach { (k, v) ->
                            StreamChallengeCache.putNSig(fixedPlayerUrl, k, v)
                        }
                    JsChallengeType.SIG ->
                        item.response.output.results.forEach { (k, v) ->
                            StreamChallengeCache.putSig(fixedPlayerUrl, k, v)
                        }
                    else -> {}
                }
            }
        }

        if (nFuncCode && nParams != null) {
            nProcessed = nParams.map { it?.let { p -> StreamChallengeCache.getNSig(fixedPlayerUrl, p) } }
        }

        if (sFuncCode && sParams != null) {
            sProcessed = sParams.map { it?.let { p -> StreamChallengeCache.getSig(fixedPlayerUrl, p) } }
        }

        return Pair(nProcessed, sProcessed)
    }

    private fun loadPlayer(): String? {
        return YouTubeInfoExtractor.loadPlayerSilent(fixedPlayerUrl)
    }

    private fun fetchAllData() {
        val jsCode = loadPlayer()

        cpnCode = jsCode?.let { ClientPlaybackNonceExtractor.extractClientPlaybackNonceCode(it) }
        signatureTimestamp = jsCode?.let {
            CommonExtractor.extractSignatureTimestamp(it)
                ?: FunctionNameExtractor.extractSignatureTimestamp(it)?.toString()
        } ?: PipePipeNsigDecoder.getSignatureTimestamp()?.toString()
    }

    private fun persistAllData() {
        if (validate()) {
            data.playerExtractorCache = PlayerExtractorCache(playerUrl, cpnCode, signatureTimestamp)
        }
    }

    private fun restoreAllData() {
        val playerCache = data.playerExtractorCache

        if (playerCache?.playerUrl == playerUrl) {
            cpnCode = playerCache.cpnCode
            signatureTimestamp = playerCache.signatureTimestamp
            nFuncCode = true
            sFuncCode = true
        }
    }

    private fun checkCpnData() {
        cpnCode?.let {
            try {
                val result = createClientPlaybackNonce()
                if (result == null)
                    cpnCode = null
            } catch (error: QuickJSException) {
                cpnCode = null
            }
        }
    }

    private fun checkSigData() {
        if (nFuncCode && sFuncCode) {
            if (DeviceHelpers.isWebViewSupported()) {
                WebViewChallengeProvider.warmup(fixedPlayerUrl)
            } else {
                V8ChallengeProvider.warmup()
            }
            return
        }

        try {
            val nParam = "5cNpZqIJ7ixNqU68Y7S"
            val sigParam = "NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hlMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzz"
            val result = solveChallengesCascade(
                listOf(
                    JsChallengeRequest(JsChallengeType.N, ChallengeInput(fixedPlayerUrl, listOf(nParam))),
                    JsChallengeRequest(JsChallengeType.SIG, ChallengeInput(fixedPlayerUrl, listOf(sigParam))),
                ))

            for (item in result) {
                when (item.response?.type) {
                    JsChallengeType.N ->
                        if (item.response.output.results[nParam]?.let { it != nParam } ?: false)
                            nFuncCode = true
                    JsChallengeType.SIG ->
                        if (item.response.output.results[sigParam]?.let { it != sigParam } ?: false)
                            sFuncCode = true
                    else -> {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}