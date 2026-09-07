package com.liskovsoft.youtubeapi.app.potokennp2.misc

import com.quickjs.JSContext
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import com.quickjs.QuickJSException
import com.liskovsoft.youtubeapi.app.potokennp2.core.V8WrapperException

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

internal class V8Wrapper {
    private val tag = V8Wrapper::class.simpleName
    private var quickJS: QuickJS? = null
    private var jsContext: JSContext? = null

    // NOTE: the QuickJS binding enforces thread affinity (all native calls must
    // happen on the thread that created the runtime), so pin everything to one
    // dedicated worker thread. Java callbacks fire re-entrantly on that same
    // thread, so they must run inline instead of being re-submitted (deadlock).
    private var jsWorkerThread: Thread? = null
    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "QuickJSPoToken").also { jsWorkerThread = it }
    }

    private fun <T> onJsThread(block: () -> T): T {
        if (Thread.currentThread() === jsWorkerThread)
            return block()
        try {
            return jsExecutor.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw V8WrapperException("QuickJS interrupted", e)
        }
    }

    fun executeStringScript(stdin: String): String = onJsThread {
        executeInternal {
            it.executeStringScript(stdin, "eval.js") ?: throw V8WrapperException("QuickJS runtime error: empty response")
        }
    }

    fun executeVoidScript(stdin: String) = onJsThread {
        executeInternal {
            it.executeVoidScript(stdin, "eval.js")
        }
    }

    private fun <T> executeInternal(block: (JSContext) -> T): T {
        initRuntime()

        val runtime = jsContext ?: throw V8WrapperException("QuickJS runtime not initialized yet")

        try {
            return block(runtime)
        } catch (e: QuickJSException) {
            throw V8WrapperException("QuickJS runtime error: ${e.message}", e)
        }
    }

    private fun initRuntime() {
        if (jsContext != null)
            return
        val runtime = QuickJS.createRuntime()
        val context = runtime.createContext()
        quickJS = runtime
        jsContext = context
    }

    fun shutdownRuntime() = onJsThread {
        disposeRuntime()
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

    fun registerJavaMethod(callback: JavaVoidCallback, jsFunctionName: String) = onJsThread {
        initRuntime()
        jsContext?.registerJavaMethod(callback, jsFunctionName)
    }

    fun registerJavaMethod(callback: JavaCallback, jsFunctionName: String) = onJsThread {
        initRuntime()
        jsContext?.registerJavaMethod(callback, jsFunctionName)
    }

    fun executeJsFunction(fnName: String): Any? = onJsThread {
        initRuntime()
        jsContext?.executeFunction2(fnName)
    }
}
