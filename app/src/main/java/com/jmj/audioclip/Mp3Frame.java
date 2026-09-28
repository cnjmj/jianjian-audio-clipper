package com.jmj.audioclip;

/** MP3 帧头解析（MPEG 1/2/2.5 Layer III） */
final class Mp3Frame {

    // MPEG 版本: 0=MPEG2.5, 2=MPEG2, 3=MPEG1 (bit 19-20)
    static final int[][] BITRATES = {
            // v1L3              v2L3
            {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0},
            {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0}
    };
    static final int[][] SAMPLERATES = {
            {44100, 48000, 32000},   // MPEG1
            {22050, 24000, 16000},   // MPEG2
            {11025, 12000, 8000}     // MPEG2.5
    };

    int frameLen;
    long durationUs;

    /** 4 字节帧头是否合法（Layer III） */
    static boolean isFrame(int[] h) {
        if (h[0] != 0xFF) return false;
        if ((h[1] & 0xE0) != 0xE0) return false;
        int ver = (h[1] >> 3) & 0x03;
        if (ver == 1) return false;             // 保留版本
        int layer = (h[1] >> 1) & 0x03;
        if (layer != 1) return false;           // 仅 Layer III
        int brIdx = (h[2] >> 4) & 0x0F;
        if (brIdx == 0 || brIdx == 15) return false;
        int srIdx = h[2] & 0x03;
        if (srIdx == 3) return false;
        return true;
    }

    /** 解析帧头，返回帧对象；非法返回 null */
    static Mp3Frame parse(int[] h) {
        if (!isFrame(h)) return null;
        int ver = (h[1] >> 3) & 0x03;        // 0/2/3
        int vIdx = (ver == 3) ? 0 : (ver == 2) ? 1 : 2;
        int brIdx = (h[2] >> 4) & 0x0F;
        int srIdx = h[2] & 0x03;
        int padding = (h[2] >> 2) & 0x01;

        int bitrate = BITRATES[ver == 3 ? 0 : 1][brIdx];
        int sampleRate = SAMPLERATES[vIdx][srIdx];
        int samples = (ver == 3) ? 1152 : 576;
        int coef = (ver == 3) ? 144 : 72;

        Mp3Frame f = new Mp3Frame();
        f.frameLen = coef * bitrate * 1000 / sampleRate + padding;
        f.durationUs = samples * 1_000_000L / sampleRate;
        if (f.frameLen < 24) return null;
        return f;
    }
}