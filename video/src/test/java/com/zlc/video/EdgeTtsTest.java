package com.zlc.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

import org.junit.Test;

public class EdgeTtsTest {

    @Test
    public void tokenIsUppercaseSha256() {
        String t = EdgeTts.secMsGec();
        assertEquals(64, t.length());
        assertTrue(t.matches("[0-9A-F]{64}"));
    }

    @Test
    public void voiceNameAndEscaping() {
        assertEquals("Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)",
                EdgeTts.longVoiceName("zh-CN-XiaoxiaoNeural"));
        assertEquals("a &amp; b &lt;c&gt;", EdgeTts.escapeXml("a & b <c>"));
    }

    /** Talks to the real service; CI turns it on with EDGE_TTS_LIVE=1. */
    @Test
    public void liveChineseVoice() throws Exception {
        assumeTrue("1".equals(System.getenv("EDGE_TTS_LIVE")));
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
        EdgeTts tts = new EdgeTts(client);
        String[] voices = {"zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural"};
        for (String v : voices) {
            byte[] mp3 = tts.synthesize("你好，这是 AI 成片的免费配音测试。", v, "+0%", "+0Hz", 30);
            System.out.println(v + " -> " + mp3.length + " bytes");
            assertTrue("too little audio from " + v, mp3.length > 4000);
            String out = System.getenv("EDGE_TTS_OUT");
            if (out != null && !out.isEmpty()) {
                new File(out).mkdirs();
                try (FileOutputStream f = new FileOutputStream(new File(out, v + ".mp3"))) {
                    f.write(mp3);
                }
            }
        }
    }
}
