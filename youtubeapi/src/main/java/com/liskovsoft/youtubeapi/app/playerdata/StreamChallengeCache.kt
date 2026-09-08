package com.liskovsoft.youtubeapi.app.playerdata

import java.util.Collections
import java.util.LinkedHashMap

internal object StreamChallengeCache {
    private const val MAX_N_CACHE_SIZE = 512
    private const val MAX_S_CACHE_SIZE = 256

    private val nCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
                return size > MAX_N_CACHE_SIZE
            }
        }
    )

    private val sCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
                return size > MAX_S_CACHE_SIZE
            }
        }
    )

    private fun buildKey(playerUrl: String?, token: String): String {
        return "${playerUrl ?: "default"}:$token"
    }

    fun getNSig(playerUrl: String?, nParam: String): String? {
        return nCache[buildKey(playerUrl, nParam)]
    }

    fun putNSig(playerUrl: String?, nParam: String, decoded: String) {
        nCache[buildKey(playerUrl, nParam)] = decoded
    }

    fun getSig(playerUrl: String?, sParam: String): String? {
        return sCache[buildKey(playerUrl, sParam)]
    }

    fun putSig(playerUrl: String?, sParam: String, decoded: String) {
        sCache[buildKey(playerUrl, sParam)] = decoded
    }

    fun size(): Pair<Int, Int> {
        return Pair(nCache.size, sCache.size)
    }

    fun clear() {
        nCache.clear()
        sCache.clear()
    }
}
