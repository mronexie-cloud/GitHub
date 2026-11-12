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
    public void tokenFormat() {
        assertTrue(EdgeTts.secMsGec().matches("[0-9A-F]{16}-[0-9A-F]{64}"));
    }

    /** Values produced by edge-tts 7.3.1's Python generate_sec_ms_gec_v2(). */
    @Test
    public void tokenMatchesPythonReference() {
        String c = EdgeTts.TOKEN;
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeToken.generate(0, c));
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", EdgeToken.generate(1700000000, c));
        assertEquals("E0C192438A7C909A-C389582981D42F275F5D785525F979F6CCB04CCD1236B7970924A7E798DA1CBF", EdgeToken.generate(1762937215.5, c));
        assertEquals("65B83946959CD719-3564CA014DB4BB08121011A239CDEF0F3DEE7350F9E8DA69E90A7B6BFF497E1C", EdgeToken.generate(1794470000, c));
        assertEquals("498DEBEC091E9FBB-48F566F30D363EA955F305452BB7D7029DA9E2E43FF809AEEF3ACB5964B2A49B", EdgeToken.generate(2000000123, c));
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
