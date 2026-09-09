package com.liskovsoft.youtubeapi.app.nsigsolver.remote

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.AppService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Remote n-parameter deobfuscation via PipePipe's public decoder API (https://api.pipepipe.dev/decoder).
 * Used as Level 3 ultimate fallback when both WebView and QuickJS local solvers fail.
 * Ported from Flow (io.github.aedev.flow.utils.cipher.PipePipeNsigDecoder).
 */
internal object PipePipeNsigDecoder {
    private val TAG = PipePipeNsigDecoder::class.java.simpleName
    private const val LATEST_PLAYER_URL = "https://api.pipepipe.dev/decoder/latest-player"
    private const val DECODE_URL = "https://api.pipepipe.dev/decoder/decode"
    private const val USER_AGENT = "PipePipe/4.9.0"
    private const val PLAYER_TTL_MS = 24L * 60L * 60L * 1000L

    private const val PREFS_NAME = "pipepipe_nsig_prefs"
    private const val KEY_PLAYER_ID = "nsig_player_id"
    private const val KEY_PLAYER_STS = "nsig_player_sts"
    private const val KEY_PLAYER_EXPIRY_MS = "nsig_player_expiry_ms"

    private const val N_CACHE_MAX_ENTRIES = 512
    private val N_PARAM_REGEX = Regex("([?&])n=([^&]+)")

    fun rawN(url: String): String? =
        N_PARAM_REGEX.find(url)?.groupValues?.get(2)?.let {
            try {
                java.net.URLDecoder.decode(it, "UTF-8")
            } catch (e: Exception) {
                it
            }
        }

    fun replaceNParam(url: String, decodedN: String): String =
        url.replaceFirst(N_PARAM_REGEX, "$1n=${URLEncoder.encode(decodedN, "UTF-8")}")

    private val nCache: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > N_CACHE_MAX_ENTRIES
            }
        )

    private val lock = Any()

    @Volatile private var playerId: String? = null
    @Volatile private var playerIdExpiryMs = 0L
    @Volatile private var cachedSignatureTimestamp: Int? = null
    @Volatile private var restoredFromDisk = false

    private fun getHttpClient(): OkHttpClient {
        return OkHttpManager.instance().client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun getContext(): Context? {
        return AppService.instance().context
    }

    fun getSignatureTimestamp(): Int? {
        ensurePlayerId()
        return cachedSignatureTimestamp
    }

    private fun restorePersistedPlayerId() {
        if (restoredFromDisk) return
        restoredFromDisk = true
        val context = getContext() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val expiry = prefs.getLong(KEY_PLAYER_EXPIRY_MS, 0L)
        if (expiry <= System.currentTimeMillis()) return
        val id = prefs.getString(KEY_PLAYER_ID, null)?.takeIf { it.isNotEmpty() } ?: return
        playerId = id
        playerIdExpiryMs = expiry
        cachedSignatureTimestamp = prefs.getInt(KEY_PLAYER_STS, 0).takeIf { it != 0 }
        Log.d(TAG, "Restored player id from disk: id=$id sts=$cachedSignatureTimestamp")
    }

    private fun persistPlayerId(id: String, expiryMs: Long, sts: Int?) {
        val context = getContext() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString(KEY_PLAYER_ID, id)
            .putLong(KEY_PLAYER_EXPIRY_MS, expiryMs)
            .putInt(KEY_PLAYER_STS, sts ?: 0)
            .apply()
    }

    private fun ensurePlayerId(): String? {
        restorePersistedPlayerId()
        playerId?.let { if (System.currentTimeMillis() < playerIdExpiryMs) return it }

        synchronized(lock) {
            playerId?.let { if (System.currentTimeMillis() < playerIdExpiryMs) return it }
            return try {
                val request = Request.Builder()
                    .url(LATEST_PLAYER_URL)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val body = getHttpClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body()?.string() else null
                }
                if (body.isNullOrEmpty()) return null

                val json = JSONObject(body)
                val id = json.optString("player").takeIf { it.isNotEmpty() } ?: return null
                val sts = json.optInt("signatureTimestamp").takeIf { it != 0 }
                val expiry = System.currentTimeMillis() + PLAYER_TTL_MS

                cachedSignatureTimestamp = sts
                playerId = id
                playerIdExpiryMs = expiry
                persistPlayerId(id, expiry, sts)
                Log.d(TAG, "Fetched latest player ok: id=$id sts=$sts")
                id
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch latest player: ${e.message}")
                null
            }
        }
    }

    fun decodeBatch(challenges: List<String>): Map<String, String> {
        val pid = ensurePlayerId() ?: return emptyMap()
        val results = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()

        for (c in challenges) {
            val cached = nCache["$pid:$c"]
            if (cached != null) {
                results[c] = cached
            } else {
                missing.add(c)
            }
        }

        if (missing.isEmpty()) {
            return results
        }

        try {
            val joined = missing.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
            val url = "$DECODE_URL?player=$pid&n=$joined"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val body = getHttpClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body()?.string() else null
            }

            val data = parseData(body)
            if (data != null) {
                for (m in missing) {
                    val decoded = data.optString(m).takeIf { it.isNotEmpty() }
                    if (decoded != null) {
                        nCache["$pid:$m"] = decoded
                        results[m] = decoded
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote batch decode failed: ${e.message}")
        }

        return results
    }

    private fun parseData(body: String?): JSONObject? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body).getJSONArray("responses").getJSONObject(0).getJSONObject("data")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected response shape: ${e.message}")
            null
        }
    }
}
