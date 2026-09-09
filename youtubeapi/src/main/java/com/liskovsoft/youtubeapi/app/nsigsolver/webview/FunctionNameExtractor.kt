package com.liskovsoft.youtubeapi.app.nsigsolver.webview

import com.liskovsoft.sharedutils.mylogger.Log
import java.security.MessageDigest

/**
 * Extracts cipher function names from YouTube's player.js
 *
 * Handles both legacy patterns and modern Q-array obfuscation (2025+).
 * Falls back to hardcoded configs for known player.js hashes when regex fails.
 * Ported from Flow (io.github.aedev.flow.utils.cipher).
 */
internal object FunctionNameExtractor {
    private val TAG = FunctionNameExtractor::class.java.simpleName

    // ==================== DATA CLASSES ====================

    data class SigFunctionInfo(
        val name: String,
        val constantArg: Int?,
        val constantArgs: List<Int>? = null,
        val preprocessFunc: String? = null,
        val preprocessArgs: List<Int>? = null,
        val isHardcoded: Boolean = false
    )

    data class NFunctionInfo(
        val name: String,
        val arrayIndex: Int?,
        val constantArgs: List<Int>? = null,
        val isHardcoded: Boolean = false,
        val acceptsUrl: Boolean = false
    )

    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?,
        val sigConstantArgs: List<Int>? = null,
        val sigPreprocessFunc: String? = null,
        val sigPreprocessArgs: List<Int>? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?,
        val signatureTimestamp: Int
    )

    // ==================== KNOWN PLAYER CONFIGS ====================

    private val KNOWN_PLAYER_CONFIGS = mapOf(
        "74edf1a3" to HardcodedPlayerConfig(
            sigFuncName = "JI",
            sigConstantArg = 48,
            sigConstantArgs = listOf(48, 1918),
            sigPreprocessFunc = "f1",
            sigPreprocessArgs = listOf(1, 6528),
            nFuncName = "GU",
            nArrayIndex = null,
            nConstantArgs = listOf(6, 6010),
            signatureTimestamp = 20522
        ),
        "f4c47414" to HardcodedPlayerConfig(
            sigFuncName = "hJ",
            sigConstantArg = 6,
            sigConstantArgs = listOf(6),
            sigPreprocessFunc = null,
            sigPreprocessArgs = null,
            nFuncName = "",
            nArrayIndex = null,
            nConstantArgs = null,
            signatureTimestamp = 20543
        )
    )

    // ==================== DETECTION PATTERNS ====================

    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['":\s]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    private val SIG_FUNCTION_PATTERNS = listOf(
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"""),
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\bm=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(h\.s\)\)"""),
        Regex("""\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("""),
        Regex("""\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\(""")
    )

    private val N_FUNCTION_PATTERNS = listOf(
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)"""),
        Regex("""\.get\("n"\);if\([a-zA-Z0-9$]+\)\s*\{[^}]*match"""),
        Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"""),
        Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_""")
    )

    private val N_URL_WRAPPER_PATTERNS = listOf(
        Regex("""([a-zA-Z0-9${'$'}]+)\s*=\s*function\(([a-zA-Z0-9${'$'}]+)\)\s*\{\s*try\s*\{\s*var\s+[a-zA-Z0-9${'$'}]+\s*=\s*\(new\s+g\.[a-zA-Z0-9${'$'}]+\(\2\s*,\s*!0\)\)\.get\("n"\)"""),
        Regex("""([a-zA-Z0-9${'$'}]+)\s*=\s*function\(([a-zA-Z0-9${'$'}]+)\)\s*\{[^{}]{0,300}\.get\("n"\)[^{}]{0,300}/\\?/n\\?/"""),
        // Function-declaration URL wrapper (player.js 2026+):
        // function Fh9(K){try{let H=(new BA(K,!0)).get("n");if(H){let r=K.match(/\/n\/([^/]+)/);...
        Regex("""function\s+([a-zA-Z0-9$]+)\s*\([a-zA-Z0-9$]+\)\s*\{\s*try\s*\{[\s\S]{0,150}?new\s+[a-zA-Z0-9$]+\([a-zA-Z0-9$]+\s*,\s*!0\)[\s\S]{0,50}?\.get\("n"\)[\s\S]{0,300}?match\(/\\/n\\/""")
    )

    // ==================== EXTRACTION FUNCTIONS ====================

    fun hasQArrayObfuscation(playerJs: String): Boolean {
        val hasQArray = Q_ARRAY_PATTERN.containsMatchIn(playerJs)
        Log.d(TAG, "Q-array obfuscation check: hasQArray=$hasQArray")

        if (hasQArray) {
            val match = Q_ARRAY_PATTERN.find(playerJs)
            if (match != null) {
                val start = match.range.first
                val qDefEnd = playerJs.indexOf(";", start)
                if (qDefEnd > start) {
                    val qDef = playerJs.substring(start, qDefEnd)
                    val elementCount = qDef.count { it == '}' } + 1
                    Log.d(TAG, "Q-array detected with ~$elementCount elements")
                }
            }
        }
        return hasQArray
    }

    fun extractPlayerHash(playerJs: String): String? {
        for (pattern in PLAYER_HASH_PATTERNS) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val hash = match.groupValues[1]
                Log.d(TAG, "Player hash found via pattern: $hash")
                return hash
            }
        }
        return try {
            val contentToHash = playerJs.take(10000)
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(contentToHash.toByteArray())
            val computedHash = digest.take(4).joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Player hash computed from content: $computedHash")
            computedHash
        } catch (e: Exception) {
            null
        }
    }

    fun getHardcodedConfig(playerHash: String): HardcodedPlayerConfig? {
        val config = KNOWN_PLAYER_CONFIGS[playerHash]
        if (config != null) {
            Log.d(TAG, "Found hardcoded config for hash $playerHash")
        }
        return config
    }

    fun extractSigFunctionInfo(playerJs: String, knownHash: String? = null): SigFunctionInfo? {
        // 1. Check nested preprocessed pattern (e.g. r&&(r=cW(16,7936,DM(48,5248,r)))
        val nestedPattern = Regex("""[a-zA-Z0-9$]+&&\s*\(?(?:[a-zA-Z0-9$]+=)?([a-zA-Z0-9$]+)\((\d+),\s*(\d+),\s*([a-zA-Z0-9$]+)\((\d+),\s*(\d+),\s*[a-zA-Z0-9$]+\)""")
        val nestedMatch = nestedPattern.find(playerJs)
        if (nestedMatch != null) {
            val name = nestedMatch.groupValues[1]
            val arg1 = nestedMatch.groupValues[2].toIntOrNull()
            val arg2 = nestedMatch.groupValues[3].toIntOrNull()
            val prepFunc = nestedMatch.groupValues[4]
            val prepArg1 = nestedMatch.groupValues[5].toIntOrNull()
            val prepArg2 = nestedMatch.groupValues[6].toIntOrNull()
            val constArgs = listOfNotNull(arg1, arg2)
            val prepArgs = listOfNotNull(prepArg1, prepArg2)
            Log.d(TAG, "SIG FUNCTION FOUND via nested pattern: name=$name, constArgs=$constArgs, prepFunc=$prepFunc, prepArgs=$prepArgs")
            return SigFunctionInfo(
                name = name,
                constantArg = arg1,
                constantArgs = constArgs,
                preprocessFunc = prepFunc,
                preprocessArgs = prepArgs,
                isHardcoded = false
            )
        }

        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val name = match.groupValues[1]
                val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
                Log.d(TAG, "SIG FUNCTION FOUND via pattern $index: name=$name, constantArg=$constArg")
                return SigFunctionInfo(name, constArg, isHardcoded = false)
            }
        }

        if (hasQArrayObfuscation(playerJs)) {
            val hashToUse = knownHash ?: extractPlayerHash(playerJs)
            if (hashToUse != null) {
                val config = getHardcodedConfig(hashToUse)
                if (config != null) {
                    Log.d(TAG, "USING HARDCODED SIG FUNCTION: ${config.sigFuncName}")
                    return SigFunctionInfo(
                        name = config.sigFuncName,
                        constantArg = config.sigConstantArg,
                        constantArgs = config.sigConstantArgs,
                        preprocessFunc = config.sigPreprocessFunc,
                        preprocessArgs = config.sigPreprocessArgs,
                        isHardcoded = true
                    )
                }
            }
        }

        Log.w(TAG, "Could not find signature deobfuscation function name")
        return null
    }

    fun extractNFunctionInfo(playerJs: String, knownHash: String? = null): NFunctionInfo? {
        for ((index, pattern) in N_URL_WRAPPER_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val name = match.groupValues[1]
                Log.d(TAG, "N URL WRAPPER FOUND via pattern $index: name=$name")
                return NFunctionInfo(name, null, isHardcoded = false, acceptsUrl = true)
            }
        }

        for ((index, pattern) in N_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                when (index) {
                    0 -> {
                        val name = match.groupValues[1]
                        val arrayIdx = match.groupValues[2].toIntOrNull()
                        Log.d(TAG, "N-FUNCTION FOUND via pattern $index: name=$name, arrayIndex=$arrayIdx")
                        return NFunctionInfo(name, arrayIdx, isHardcoded = false)
                    }
                    1 -> {
                        val name = match.groupValues[2]
                        val arrayIdx = match.groupValues[3].toIntOrNull()
                        Log.d(TAG, "N-FUNCTION FOUND via pattern $index: name=$name, arrayIndex=$arrayIdx")
                        return NFunctionInfo(name, arrayIdx, isHardcoded = false)
                    }
                    else -> {
                        if (pattern.toPattern().matcher("").groupCount() < 1) {
                            continue
                        }
                        val name = match.groupValues[1]
                        Log.d(TAG, "N-FUNCTION FOUND via pattern $index: name=$name")
                        return NFunctionInfo(name, null, isHardcoded = false)
                    }
                }
            }
        }

        if (hasQArrayObfuscation(playerJs)) {
            val hashToUse = knownHash ?: extractPlayerHash(playerJs)
            if (hashToUse != null) {
                val config = getHardcodedConfig(hashToUse)
                if (config != null) {
                    Log.d(TAG, "USING HARDCODED N-FUNCTION: ${config.nFuncName}[${config.nArrayIndex}]")
                    return NFunctionInfo(config.nFuncName, config.nArrayIndex, config.nConstantArgs, isHardcoded = true)
                }
            }
        }

        Log.w(TAG, "Could not find n-transform function name")
        return null
    }

    fun extractSignatureTimestamp(playerJs: String): Int? {
        val patterns = listOf(
            Regex("""(?:\bsts|signatureTimestamp)['":\s=]+(\d+)"""),
            Regex("""signatureTimestamp['":\s=]+(\d+)"""),
            Regex("""sts['":\s=]+(\d+)"""),
            Regex(""""signatureTimestamp"\s*:\s*(\d+)""")
        )
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val sts = match.groupValues[1].toIntOrNull()
                if (sts != null) {
                    Log.d(TAG, "signatureTimestamp found via pattern $index: $sts")
                    return sts
                }
            }
        }
        val playerHash = extractPlayerHash(playerJs)
        if (playerHash != null) {
            val config = getHardcodedConfig(playerHash)
            if (config != null) {
                Log.d(TAG, "Using hardcoded signatureTimestamp: ${config.signatureTimestamp}")
                return config.signatureTimestamp
            }
        }
        return null
    }

    fun analyzePlayerJs(playerJs: String, knownHash: String? = null): PlayerAnalysis {
        val playerHash = knownHash ?: extractPlayerHash(playerJs)
        val hasQArray = hasQArrayObfuscation(playerJs)
        val sigInfo = extractSigFunctionInfo(playerJs, playerHash)
        val nFuncInfo = extractNFunctionInfo(playerJs, playerHash)
        val signatureTimestamp = extractSignatureTimestamp(playerJs)

        return PlayerAnalysis(
            playerHash = playerHash,
            hasQArrayObfuscation = hasQArray,
            sigInfo = sigInfo,
            nFuncInfo = nFuncInfo,
            signatureTimestamp = signatureTimestamp
        )
    }

    data class PlayerAnalysis(
        val playerHash: String?,
        val hasQArrayObfuscation: Boolean,
        val sigInfo: SigFunctionInfo?,
        val nFuncInfo: NFunctionInfo?,
        val signatureTimestamp: Int?
    )
}
