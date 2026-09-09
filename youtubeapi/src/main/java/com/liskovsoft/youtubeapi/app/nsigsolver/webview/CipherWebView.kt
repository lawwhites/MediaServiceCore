package com.liskovsoft.youtubeapi.app.nsigsolver.webview

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import com.liskovsoft.sharedutils.mylogger.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView-based cipher executor for YouTube stream URL deobfuscation.
 * Executes signature decipher and n-transform functions extracted from player.js.
 * Ported from Flow and adapted with thread-safe synchronous latch execution for Android TV.
 */
internal class CipherWebView private constructor(
    context: Context,
    private val playerJs: String,
    private val sigInfo: FunctionNameExtractor.SigFunctionInfo?,
    private val nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
    private val onInitFinished: (Boolean, Throwable?) -> Unit
) {
    private val webView = WebView(context)
    private val evalLock = Any()

    @Volatile private var activeSigLatch: CountDownLatch? = null
    @Volatile private var activeSigResult: String? = null
    @Volatile private var activeSigError: String? = null

    @Volatile private var activeNLatch: CountDownLatch? = null
    @Volatile private var activeNResult: String? = null
    @Volatile private var activeNError: String? = null

    @Volatile var nFunctionAvailable: Boolean = false
        private set
    @Volatile var sigFunctionAvailable: Boolean = false
        private set
    @Volatile var discoveredNFuncName: String? = null
        private set
    @Volatile var usingHardcodedMode: Boolean = false
        private set

    init {
        Log.d(TAG, "Initializing CipherWebView: sig=${sigInfo?.name}, nFunc=${nFuncInfo?.name}[${nFuncInfo?.arrayIndex}]")
        val settings = webView.settings
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.blockNetworkLoads = true
        setSafeBrowsingEnabled(settings, false)

        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val msg = m.message()
                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> {
                        if (!msg.contains("is not defined")) Log.e(TAG, "JS ERROR: $msg at ${m.sourceId()}:${m.lineNumber()}")
                    }
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS WARN: $msg")
                    else -> Log.d(TAG, "JS LOG: $msg")
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun setSafeBrowsingEnabled(settings: WebSettings, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                settings.safeBrowsingEnabled = enabled
            } catch (t: Throwable) {
                // Ignore platform mismatch
            }
        }
    }

    private fun loadPlayerJsFromFile() {
        val sigFuncName = sigInfo?.name
        val nFuncName = nFuncInfo?.name
        val nArrayIdx = nFuncInfo?.arrayIndex
        val isHardcoded = sigInfo?.isHardcoded == true || nFuncInfo?.isHardcoded == true

        Log.d(TAG, "Loading player.js into WebView: sig=$sigFuncName, nFunc=$nFuncName, hardcoded=$isHardcoded")
        usingHardcodedMode = isHardcoded

        val exports = mutableListOf<String>()
        sigInfo?.let { sig ->
            val sigConstArgs = sig.constantArgs
            val preprocessFunc = sig.preprocessFunc
            val preprocessArgs = sig.preprocessArgs
            if (!sigConstArgs.isNullOrEmpty() && preprocessFunc != null && !preprocessArgs.isNullOrEmpty()) {
                val mainArgsStr = sigConstArgs.joinToString(", ")
                val prepArgsStr = preprocessArgs.joinToString(", ")
                exports.add("window._cipherSigFunc = function(sig) { return $sigFuncName($mainArgsStr, $preprocessFunc($prepArgsStr, sig)); };")
            } else if (!sigConstArgs.isNullOrEmpty()) {
                val argsStr = sigConstArgs.joinToString(", ")
                exports.add("window._cipherSigFunc = function(sig) { return $sigFuncName($argsStr, sig); };")
            } else {
                exports.add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
            }
        }

        nFuncInfo?.let { nFunc ->
            val nConstArgs = nFunc.constantArgs
            if (nFunc.acceptsUrl) {
                exports.add("window._nUrlTransformFunc = typeof $nFuncName !== 'undefined' ? $nFuncName : null;")
            } else if (!nConstArgs.isNullOrEmpty()) {
                val argsStr = nConstArgs.joinToString(", ")
                exports.add("window._nTransformFunc = function(n) { return $nFuncName($argsStr, n); };")
            } else {
                val nExpr = if (nArrayIdx != null) "$nFuncName[$nArrayIdx]" else nFuncName
                exports.add("window._nTransformFunc = typeof $nFuncName !== 'undefined' ? $nExpr : null;")
            }
        }

        val modifiedJs = if (exports.isNotEmpty()) {
            val exportCode = "; " + exports.joinToString(" ")
            // Exports must land INSIDE the player IIFE, otherwise the cipher
            // functions are out of scope. Anchor on the IIFE terminator:
            // "})(_yt_player);" on legacy players, "}).call(this);" on 2026+.
            var modified: String? = null
            for (anchor in listOf("})(_yt_player);", "}).call(this);")) {
                val idx = playerJs.lastIndexOf(anchor)
                if (idx >= 0) {
                    modified = playerJs.substring(0, idx) + exportCode + " " + playerJs.substring(idx)
                    break
                }
            }
            if (modified == null) {
                Log.w(TAG, "Export injection point not found, appending exports (functions may be out of scope)")
                playerJs + "\n" + exportCode
            } else {
                modified
            }
        } else {
            playerJs
        }

        val cacheDir = File(webView.context.cacheDir, "cipher")
        cacheDir.mkdirs()
        val playerJsFile = File(cacheDir, "player.js")
        playerJsFile.writeText(modifiedJs)
        Log.d(TAG, "player.js written to cache: ${modifiedJs.length} chars")

        val html = buildDiscoveryHtml()
        webView.loadDataWithBaseURL(
            "file://${cacheDir.absolutePath}/",
            html, "text/html", "utf-8", null
        )
    }

    private fun buildDiscoveryHtml(): String = """<!DOCTYPE html>
<html><head><script>
function deobfuscateSig(funcName, constantArg, obfuscatedSig) {
    try {
        var func = window._cipherSigFunc;
        if (typeof func !== 'function') {
            CipherBridge.onSigError("Sig func not found on window (type: " + typeof func + ")");
            return;
        }
        var result;
        if (func.length === 1) {
            result = func(obfuscatedSig);
        } else if (constantArg !== null && constantArg !== undefined) {
            result = func(constantArg, obfuscatedSig);
        } else {
            result = func(obfuscatedSig);
        }
        if (result === undefined || result === null) {
            CipherBridge.onSigError("Function returned null/undefined");
            return;
        }
        CipherBridge.onSigResult(String(result));
    } catch (error) {
        CipherBridge.onSigError(error + "\n" + (error.stack || ""));
    }
}
function transformN(nValue) {
    try {
        var func = window._nTransformFunc;
        var result = null;
        if (typeof func === 'function') {
            result = func(nValue);
        } else if (typeof window._nUrlTransformFunc === 'function') {
            result = runNUrlTransform(nValue);
        } else {
            CipherBridge.onNError("N-transform func not available (n type: " + typeof func + ", url type: " + typeof window._nUrlTransformFunc + ")");
            return;
        }
        if (result === undefined || result === null) {
            CipherBridge.onNError("N-transform returned null/undefined");
            return;
        }
        CipherBridge.onNResult(String(result));
    } catch (error) {
        CipherBridge.onNError(error + "\n" + (error.stack || ""));
    }
}
function runNUrlTransform(nValue) {
    var encoded = encodeURIComponent(nValue);
    var probeUrl = "https://rr1---sn.googlevideo.com/videoplayback/n/" + encoded + "/itag/18";
    var transformedUrl = window._nUrlTransformFunc(probeUrl);
    if (typeof transformedUrl !== 'string') {
        throw "N URL transform returned " + typeof transformedUrl;
    }
    var match = transformedUrl.match(/\/n\/([^\/]+)/);
    if (!match || !match[1]) {
        throw "N URL transform did not return an /n/ segment";
    }
    return decodeURIComponent(match[1]);
}
function discoverAndInit() {
    var nFuncName = "";
    var sigFuncName = "";
    var info = "";
    if (typeof window._cipherSigFunc === 'function') {
        sigFuncName = "exported_sig_func";
    }
    if (typeof window._nTransformFunc === 'function') {
        try {
            var testInput = "KdrqFlzJXl9EcCwlmEy";
            var testResult = window._nTransformFunc(testInput);
            if (typeof testResult === 'string' && testResult !== testInput && testResult.length >= 5 && /^[a-zA-Z0-9_-]+${"$"}/.test(testResult)) {
                nFuncName = "exported_n_func";
                info = "export_valid";
            } else {
                info = "export_bad_result";
                window._nTransformFunc = null;
            }
        } catch(e) {
            info = "export_threw:" + e;
            window._nTransformFunc = null;
        }
    }
    if (!nFuncName && typeof window._nUrlTransformFunc === 'function') {
        try {
            var testInputUrl = "T2Xw3pWQ_Wk0xbOg";
            var testResultUrl = runNUrlTransform(testInputUrl);
            if (typeof testResultUrl === 'string' && testResultUrl !== testInputUrl && testResultUrl.length >= 5 && /^[a-zA-Z0-9_-]+${"$"}/.test(testResultUrl)) {
                nFuncName = "exported_n_url_func";
                info = "export_url_valid";
            } else {
                info = "export_url_bad_result";
                window._nUrlTransformFunc = null;
            }
        } catch(e) {
            info = "export_url_threw:" + e;
            window._nUrlTransformFunc = null;
        }
    }
    if (!nFuncName) {
        try {
            var testInput = "T2Xw3pWQ_Wk0xbOg";
            var keys = Object.getOwnPropertyNames(window);
            var tested = 0;
            var deadline = Date.now() + 3000;
            for (var i = 0; i < keys.length; i++) {
                try {
                    var key = keys[i];
                    if (key.startsWith("webkit") || key.startsWith("on") || key === "CipherBridge" || key === "_cipherSigFunc" || key === "_nTransformFunc" || key === "_nUrlTransformFunc" || key === "window" || key === "self") continue;
                    var fn = window[key];
                    if (typeof fn !== 'function' || fn.length !== 1) continue;
                    // Native functions are never the cipher and some (eval,
                    // setInterval, setTimeout...) turn the probe string into
                    // scheduled code that throws forever - skip them all.
                    var src = Function.prototype.toString.call(fn);
                    if (src.indexOf("[native code]") !== -1) continue;
                    if (tested++ > 500 || Date.now() > deadline) { info = "brute_force_bounded:tested=" + tested; break; }
                    var result = fn(testInput);
                    if (typeof result === 'string' && result !== testInput && result.length >= 5 && /^[a-zA-Z0-9_-]+${"$"}/.test(result)) {
                        window._nTransformFunc = fn;
                        nFuncName = key;
                        break;
                    }
                } catch(e) {}
            }
            if (!info) info = "brute_force:tested=" + tested;
        } catch(e) {
            info = "brute_force_error:" + e;
        }
    }
    CipherBridge.onDiscoveryDone(sigFuncName, nFuncName, info);
    CipherBridge.onPlayerJsLoaded();
}
</script>
<script src="player.js"
    onload="discoverAndInit()"
    onerror="CipherBridge.onPlayerJsError('Failed to load player.js from file')">
</script>
</head><body></body></html>"""

    @JavascriptInterface
    fun logDebug(message: String) {
        Log.d(TAG, "JS: $message")
    }

    @JavascriptInterface
    fun onDiscoveryDone(sigFuncName: String, nFuncName: String, info: String) {
        Log.d(TAG, "Discovery: sig=${if (sigFuncName.isEmpty()) "NOT FOUND" else sigFuncName}, n=${if (nFuncName.isEmpty()) "NOT FOUND" else nFuncName}, info=$info")
        sigFunctionAvailable = sigFuncName.isNotEmpty()
        if (nFuncName.isNotEmpty()) {
            discoveredNFuncName = nFuncName
            nFunctionAvailable = true
        } else {
            Log.e(TAG, "N-function NOT AVAILABLE")
            nFunctionAvailable = false
        }
    }

    @JavascriptInterface
    fun onPlayerJsLoaded() {
        Log.d(TAG, "Player.js loaded: sig=$sigFunctionAvailable, n=$nFunctionAvailable, nFunc=$discoveredNFuncName")
        onInitFinished(true, null)
    }

    @JavascriptInterface
    fun onPlayerJsError(error: String) {
        Log.e(TAG, "Player.js load FAILED: $error")
        onInitFinished(false, CipherException("Player JS load failed: $error"))
    }

    fun deobfuscateSignature(obfuscatedSig: String, timeoutMs: Long = 3000L): String? {
        if (!sigFunctionAvailable && sigInfo == null) {
            Log.e(TAG, "Signature function info not available")
            return null
        }
        val latch = CountDownLatch(1)
        var result: String? = null
        var err: String? = null

        synchronized(evalLock) {
            activeSigLatch = latch
            activeSigResult = null
            activeSigError = null

            mainHandler.post {
                val constArgJs = if (sigInfo?.constantArg != null) "${sigInfo.constantArg}" else "null"
                val fnName = sigInfo?.name ?: ""
                webView.evaluateJavascript("deobfuscateSig('$fnName', $constArgJs, '${escapeJsString(obfuscatedSig)}')", null)
            }

            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "deobfuscateSignature timed out after ${timeoutMs}ms")
                    return null
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "deobfuscateSignature interrupted", e)
                return null
            }

            result = activeSigResult
            err = activeSigError
            activeSigLatch = null
        }

        if (err != null) {
            Log.e(TAG, "Signature deobfuscation failed: $err")
            return null
        }
        return result
    }

    @JavascriptInterface
    fun onSigResult(result: String) {
        activeSigResult = result
        activeSigLatch?.countDown()
    }

    @JavascriptInterface
    fun onSigError(error: String) {
        activeSigError = error
        activeSigLatch?.countDown()
    }

    fun transformN(nValue: String, timeoutMs: Long = 3000L): String? {
        if (!nFunctionAvailable) {
            Log.e(TAG, "N-transform function not discovered")
            return null
        }
        val latch = CountDownLatch(1)
        var result: String? = null
        var err: String? = null

        synchronized(evalLock) {
            activeNLatch = latch
            activeNResult = null
            activeNError = null

            mainHandler.post {
                webView.evaluateJavascript("transformN('${escapeJsString(nValue)}')", null)
            }

            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "transformN timed out after ${timeoutMs}ms")
                    return null
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "transformN interrupted", e)
                return null
            }

            result = activeNResult
            err = activeNError
            activeNLatch = null
        }

        if (err != null) {
            Log.e(TAG, "N-transform failed: $err")
            return null
        }
        return result
    }

    @JavascriptInterface
    fun onNResult(result: String) {
        activeNResult = result
        activeNLatch?.countDown()
    }

    @JavascriptInterface
    fun onNError(error: String) {
        activeNError = error
        activeNLatch?.countDown()
    }

    fun close() {
        mainHandler.post {
            try {
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
                Log.d(TAG, "CipherWebView closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing WebView: ${e.message}")
            }
        }
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        private const val TAG = "CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"
        private val mainHandler = Handler(Looper.getMainLooper())

        fun create(
            context: Context,
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo? = null,
            timeoutMs: Long = 10000L
        ): CipherWebView? {
            Log.d(TAG, "Creating CipherWebView: playerJs=${playerJs.length}, sig=${sigInfo?.name}, n=${nFuncInfo?.name}")
            val latch = CountDownLatch(1)
            var createdInstance: CipherWebView? = null
            var initError: Throwable? = null

            mainHandler.post {
                try {
                    val instance = CipherWebView(context, playerJs, sigInfo, nFuncInfo) { success, error ->
                        if (!success) {
                            initError = error
                        }
                        latch.countDown()
                    }
                    createdInstance = instance
                    instance.loadPlayerJsFromFile()
                } catch (t: Throwable) {
                    initError = t
                    latch.countDown()
                }
            }

            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "CipherWebView creation timed out after ${timeoutMs}ms")
                    createdInstance?.close()
                    return null
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "CipherWebView creation interrupted", e)
                createdInstance?.close()
                return null
            }

            if (initError != null) {
                Log.e(TAG, "CipherWebView creation failed: ${initError?.message}")
                createdInstance?.close()
                return null
            }

            return createdInstance
        }
    }
}

internal class CipherException(message: String) : Exception(message)
