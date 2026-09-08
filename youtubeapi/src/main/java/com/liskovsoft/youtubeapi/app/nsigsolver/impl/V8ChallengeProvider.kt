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

    // 10 minutes idle watchdog to automatically release native resources when inactive
    private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    private var idleTask: java.util.concurrent.ScheduledFuture<*>? = null

    // Dedicated single thread executor supporting delayed scheduling for idle watchdog
    private val jsThread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "QuickJSSolver") }

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
            cancelIdleWatchdog()
            initRuntime()

            val result = try {
                runQuickJs(stdin)
            } catch (e: Exception) {
                // Self-healing: dispose runtime on error so next call gets a clean state
                disposeRuntime()
                throw e
            }

            resetIdleWatchdog()

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
        cancelIdleWatchdog()
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

    private fun cancelIdleWatchdog() {
        idleTask?.cancel(false)
        idleTask = null
    }

    private fun resetIdleWatchdog() {
        cancelIdleWatchdog()
        idleTask = jsThread.schedule({
            synchronized(jsLock) {
                if (jsContext != null) {
                    disposeRuntime()
                }
            }
        }, IDLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    
    fun warmup() = onJsThread {
        synchronized(jsLock) {
            cancelIdleWatchdog()
            initRuntime()
            resetIdleWatchdog()
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
            resetIdleWatchdog()
        }
    }

    fun isWarm(): Boolean = jsContext != null
}