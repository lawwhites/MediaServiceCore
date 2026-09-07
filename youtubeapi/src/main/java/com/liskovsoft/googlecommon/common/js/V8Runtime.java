package com.liskovsoft.googlecommon.common.js;

import androidx.annotation.Nullable;

import com.quickjs.JSContext;
import com.quickjs.QuickJS;
import com.quickjs.QuickJSException;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class V8Runtime {
    private static final String TAG = V8Runtime.class.getSimpleName();
    private static V8Runtime sInstance;

    // NOTE: the QuickJS binding enforces thread affinity (all native calls must
    // happen on the thread that created the runtime), so everything runs on one
    // dedicated worker thread. A single persistent runtime also avoids paying
    // runtime/context creation on every evaluation.
    private final ExecutorService mJsThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "QuickJSRuntime"));
    private QuickJS mQuickJS;
    private JSContext mContext;

    private V8Runtime() {
    }

    public static V8Runtime instance() {
        if (sInstance == null) {
            sInstance = new V8Runtime();
        }

        return sInstance;
    }

    public static void unhold() {
        if (sInstance != null) {
            sInstance.dispose();
        }
        sInstance = null;
    }

    @Nullable
    public String evaluate(final String source) {
        try {
            return evaluateSafe(source);
        } catch (QuickJSException e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    @Nullable
    public String evaluateWithErrors(final String source) throws QuickJSException {
        return evaluateSafe(source);
    }

    @Nullable
    public String evaluate(final List<String> sources) {
        try {
            return evaluateSafe(sources);
        } catch (QuickJSException e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    @Nullable
    public String evaluateWithErrors(final List<String> sources) throws QuickJSException {
        return evaluateSafe(sources);
    }

    /**
     * Thread safe evaluation on the shared persistent runtime.
     */
    private String evaluateSafe(final String source) throws QuickJSException {
        return onJsThread(() -> getContext().executeStringScript(source, "eval.js"));
    }

    /**
     * Thread safe evaluation of multiple scripts sequentially.
     */
    private String evaluateSafe(final List<String> sources) throws QuickJSException {
        return onJsThread(() -> {
            String result = null;
            for (String source : sources) {
                try {
                    result = getContext().executeStringScript(source, "eval.js");
                } catch (QuickJSException e) {
                    // NOP if intermediate result undefined or not a string
                }
            }
            return result;
        });
    }

    private JSContext getContext() {
        if (mContext == null) {
            mQuickJS = QuickJS.createRuntime();
            mContext = mQuickJS.createContext();
        }
        return mContext;
    }

    private void dispose() {
        mJsThread.execute(() -> {
            if (mContext != null) {
                try {
                    mContext.close();
                } catch (Exception ignored) {}
                mContext = null;
            }
            if (mQuickJS != null) {
                try {
                    mQuickJS.close();
                } catch (Exception ignored) {}
                mQuickJS = null;
            }
        });
    }

    private <T> T onJsThread(Callable<T> block) throws QuickJSException {
        try {
            return mJsThread.submit(block).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof QuickJSException) {
                throw (QuickJSException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
