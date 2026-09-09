package com.liskovsoft.youtubeapi.app.playerdata

import com.liskovsoft.youtubeapi.app.nsigsolver.remote.PipePipeNsigDecoder
import com.liskovsoft.youtubeapi.app.nsigsolver.webview.FunctionNameExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FunctionNameExtractorTest {
    private val samplePlayerJs = """
        "use strict";
        var _yt_player = {};
        (function(g) {
            var Q = "a}b}c".split("}");
            var b = function(d) {
                var c = a.get("n");
                if (c) {
                    b = GU[6](c);
                }
            };
            function testSig(a, b) {
                c && a.set("sig", encodeURIComponent(JI(b)));
            }
            g.sts = 20543;
            var signatureTimestamp = 20543;
        })(_yt_player);
    """.trimIndent()

    @Test
    fun testExtractSignatureTimestamp() {
        val sts = FunctionNameExtractor.extractSignatureTimestamp(samplePlayerJs)
        assertEquals(20543, sts)
    }

    @Test
    fun testHasQArrayObfuscation() {
        val hasQArray = FunctionNameExtractor.hasQArrayObfuscation(samplePlayerJs)
        assertTrue(hasQArray)
    }

    @Test
    fun testExtractSigFunction() {
        val sigInfo = FunctionNameExtractor.extractSigFunctionInfo(samplePlayerJs)
        assertNotNull(sigInfo)
        assertEquals("JI", sigInfo?.name)
    }

    @Test
    fun testPipePipeUrlParsing() {
        val streamUrl = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1754600000&ei=xyz&n=Q7dK2mLpR9vTs&itag=137"
        val rawN = PipePipeNsigDecoder.rawN(streamUrl)
        assertEquals("Q7dK2mLpR9vTs", rawN)

        val replaced = PipePipeNsigDecoder.replaceNParam(streamUrl, "TRANSFORMED")
        assertTrue(replaced.contains("n=TRANSFORMED"))
        assertEquals("TRANSFORMED", PipePipeNsigDecoder.rawN(replaced))

        val noNUrl = "https://rr3---sn-abc.googlevideo.com/videoplayback?itag=137"
        assertNull(PipePipeNsigDecoder.rawN(noNUrl))
    }

    @Test
    fun testExtractNFunctionDeclarationUrlWrapper() {
        // player.js 2026+ shape: function-declaration URL wrapper, parser class
        // is a plain global (not a _yt_player property).
        val js = """
            (function(){'use strict';
            function BA(u, f) { this.url = u; }
            function LN(e) {}
            function Fh9(K){try{let H=(new BA(K,!0)).get("n");if(H){let r=K.match(/\/n\/([^/]+)/);if(r&&r[1]&&r[1]!==H)return K.replace(`/n/${'$'}{r[1]}`,`/n/${'$'}{H}`)}}catch(H){LN(H)}return K}
            }).call(this);
        """.trimIndent()

        val info = FunctionNameExtractor.extractNFunctionInfo(js)
        assertNotNull("N URL wrapper must be extracted from declaration form", info)
        assertEquals("Fh9", info?.name)
        assertTrue("Must be marked as URL-accepting wrapper", info?.acceptsUrl == true)
    }

    @Test
    fun testLiveYouTubeBaseJsExtraction() {
        val appService = com.liskovsoft.youtubeapi.app.AppServiceInt()
        val playerUrl = appService.playerUrl
        println("Live YouTube Player URL: $playerUrl")
        assertNotNull("Player URL must not be null", playerUrl)
        assertTrue("Player URL must end with .js", playerUrl.endsWith(".js"))

        // Resolve main player if tv player
        val mainPlayerUrl = PlayerUrlResolver.resolve(playerUrl, com.liskovsoft.youtubeapi.common.helpers.AppClient.WEB)
        println("Resolved Main Player URL: $mainPlayerUrl")

        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(mainPlayerUrl).build()
        val response = client.newCall(request).execute()
        assertTrue("HTTP response must be successful", response.isSuccessful)
        val jsContent = response.body()?.string()
        assertNotNull("JS content must not be null", jsContent)
        assertTrue("JS content length must be substantial (> 1MB)", jsContent!!.length > 1_000_000)

        // 1. Test STS extraction
        val sts = FunctionNameExtractor.extractSignatureTimestamp(jsContent)
        println("Extracted STS: $sts")
        assertNotNull("STS must be extracted", sts)
        assertTrue("STS must be recent (> 19000)", sts!! > 19000)

        // 2. Test n-sig function name extraction
        val nsigInfo = FunctionNameExtractor.extractNFunctionInfo(jsContent)
        println("Extracted nsig function: ${nsigInfo?.name}, isHardcoded=${nsigInfo?.isHardcoded}")
        assertNotNull("Nsig function info must be extracted", nsigInfo)
        assertTrue("Nsig function name must not be blank", !nsigInfo!!.name.isNullOrBlank())

        // 3. Test sig function name extraction
        val sigInfo = FunctionNameExtractor.extractSigFunctionInfo(jsContent)
        println("Extracted sig function: ${sigInfo?.name}")
        assertNotNull("Sig function info must be extracted", sigInfo)
        assertTrue("Sig function name must not be blank", !sigInfo!!.name.isNullOrBlank())
    }
}
