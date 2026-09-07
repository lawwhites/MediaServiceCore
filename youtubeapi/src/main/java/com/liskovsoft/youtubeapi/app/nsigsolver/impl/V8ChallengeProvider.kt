package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.quickjs.JSContext
import com.quickjs.QuickJS
import com.quickjs.QuickJSException
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.JsRuntimeChalBaseJCP
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.Script
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptSource
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptType
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptVariant

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

internal object V8ChallengeProvider: JsRuntimeChalBaseJCP() {
    private val tag = V8ChallengeProvider::class.simpleName
    private val v8NpmLibFilename = listOf("${libPrefix}polyfill.js", "${libPrefix}meriyah-6.1.4.min.js", "${libPrefix}astring-1.9.0.min.js")
    private var quickJS: QuickJS? = null
    private var jsContext: JSContext? = null
    private val jsLock = Any()

    // NOTE: the QuickJS binding enforces thread affinity (all native calls must
    // happen on the thread that created the runtime), so pin everything to one
    // dedicated worker thread instead of whatever pool thread happens to call in.
    private val jsThread = Executors.newSingleThreadExecutor { r -> Thread(r, "QuickJSSolver") }

    private fun <T> onJsThread(block: () -> T): T {
        try {
            return jsThread.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw JsChallengeProviderError("QuickJS interrupted", e)
        }
    }

    override fun iterScriptSources(): Sequence<Pair<ScriptSource, (ScriptType) -> Script?>> = sequence {
        for ((source, func) in super.iterScriptSources()) {
            if (source == ScriptSource.WEB || source == ScriptSource.BUILTIN)
                yield(Pair(ScriptSource.BUILTIN, ::v8NpmSource))
            yield(Pair(source, func))
        }
    }

    private fun v8NpmSource(scriptType: ScriptType): Script? {
        if (scriptType != ScriptType.LIB)
            return null
        // QuickJS lib scripts that use Deno NPM imports (meriyah + astring)
        val code = loadScript(v8NpmLibFilename, "Failed to read challenge solver lib script")
        return Script(scriptType, ScriptVariant.V8_NPM, ScriptSource.BUILTIN, scriptVersion, code)
    }

    override fun runJsRuntime(stdin: String): String = onJsThread {
        synchronized(jsLock) {
            initRuntime()

            val result = runQuickJs(stdin)

            shutdownIfNeeded()

            result
        }
    }

    private fun runQuickJs(stdin: String): String {
        val context = jsContext ?: throw JsChallengeProviderError("QuickJS runtime not initialized yet")
        try {
            return context.executeStringScript(stdin, "eval.js") ?: throw JsChallengeProviderError("QuickJS runtime error: empty response")
        } catch (e: QuickJSException) {
            if (e.message?.contains("Invalid or unexpected token") == true || e.message?.contains("syntax error") == true)
                ie.cache.clear(cacheSection) // cached data broken?
            throw JsChallengeProviderError("QuickJS runtime error: ${e.message}", e)
        }
    }

    private fun initRuntime() {
        if (jsContext != null)
            return
        val runtime = QuickJS.createRuntime()
        val context = runtime.createContext()
        quickJS = runtime
        jsContext = context
        runQuickJs(constructCommonStdin()) // warm up with lib and core scripts
    }

    private fun disposeRuntime() {
        try {
            jsContext?.close()
        } catch (_: Exception) {
        }
        try {
            quickJS?.close()
        } catch (_: Exception) {
        }
        jsContext = null
        quickJS = null
    }
    
    fun warmup() = onJsThread {
        synchronized(jsLock) {
            initRuntime()
        }
    }

    fun shutdown() = onJsThread {
        synchronized(jsLock) {
            disposeRuntime()
        }
    }

    fun forceRecreate() = onJsThread {
        synchronized(jsLock) {
            disposeRuntime()

            initRuntime()
        }
    }

    private fun shutdownIfNeeded() {
        disposeRuntime()
    }
}