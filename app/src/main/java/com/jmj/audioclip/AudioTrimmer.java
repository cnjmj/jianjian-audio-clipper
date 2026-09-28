package com.jmj.audioclip;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * 音频剪辑核心引擎。
 * 策略：
 *  - MP3   -> 按帧头逐帧定位，字节级裁剪，无损不重编码
 *  - WAV   -> 解析 RIFF 头，按字节率裁剪 PCM 数据，无损
 *  - M4A/MP4/3GP/AAC -> MediaExtractor + MediaMuxer 无损重封装，输出 m4a
 *  - 其他（OGG/FLAC/AMR 等）-> 解码为 PCM 导出 WAV
 */
public final class AudioTrimmer {

    public interface Progress {
        void onProgress(int percent);
    }

    private AudioTrimmer() {}

    /** 根据扩展名判断导出策略 */
    public static String plan(String fileName) {
        String ext = ext(fileName);
        switch (ext) {
            case "mp3":  return "mp3";
            case "wav":  return "wav";
            case "m4a": case "mp4": case "3gp": case "3gpp":
            case "aac": case "m4b":
                return "remux";
            default:      return "decode";
        }
    }

    /** 导出文件建议扩展名 */
    public static String suggestExt(String srcName) {
        switch (plan(srcName)) {
            case "mp3":  return ".mp3";
            case "wav":  return ".wav";
            case "remux": return ".m4a";
            default:     return ".wav";
        }
    }

    public static void trim(Context ctx, Uri src, Uri dst, String srcName,
                            long startMs, long endMs, Progress cb) throws Exception {
        switch (plan(srcName)) {
            case "mp3":   trimMp3(ctx, src, dst, startMs, endMs, cb); break;
            case "wav":   trimWav(ctx, src, dst, startMs, endMs);     break;
            case "remux": remux(ctx, src, dst, startMs, endMs, cb);   break;
            default:      decodeToWav(ctx, src, dst, startMs, endMs, cb);
        }
    }

    // ================= MP3 帧级裁剪（全内存，简单可靠） =================

    private static void trimMp3(Context ctx, Uri src, Uri dst,
                                long startMs, long endMs, Progress cb) throws Exception {
        byte[] data = readAll(ctx, src);
        int len = data.length;

        long startUs = startMs * 1000L, endUs = endMs * 1000L;
        int p = (int) skipId3(data);
        int startOff = -1, endOff = -1, endFrameLen = 0;
        long t = 0;
        int lastPct = 0;

        while (p + 4 <= len) {
            if (data[p] != (byte) 0xFF || (data[p + 1] & 0xE0) != 0xE0) {
                p++;
                continue;
            }
            Mp3Frame f = Mp3Frame.parse(new int[]{data[p] & 0xFF, data[p+1] & 0xFF,
                    data[p+2] & 0xFF, data[p+3] & 0xFF});
            if (f == null || p + f.frameLen > len) { p++; continue; }
            if (startOff < 0 && t >= startUs) startOff = p;
            if (t + f.durationUs >= endUs) {
                endOff = p; endFrameLen = f.frameLen;
                break;
            }
            t += f.durationUs;
            p += f.frameLen;
            if (cb != null) {
                int pct = (int) (t * 100 / Math.max(1, endUs));
                if (pct > lastPct) { lastPct = pct; cb.onProgress(Math.min(50, pct / 2)); }
            }
        }

        if (startOff < 0) throw new IOException("未能定位剪辑起点，文件可能损坏");
        int stop;
        if (endOff < 0) stop = len;
        else stop = endOff + endFrameLen;

        OutputStream out = new BufferedOutputStream(
                ctx.getContentResolver().openOutputStream(dst, "w"), 1 << 16);
        int total = stop - startOff;
        int chunk = 65536;
        int copied = 0;
        while (copied < total) {
            int n = Math.min(chunk, total - copied);
            out.write(data, startOff + copied, n);
            copied += n;
            if (cb != null) cb.onProgress(50 + copied * 50 / total);
        }
        out.flush(); out.close();
    }

    private static int skipId3(byte[] d) {
        if (d.length > 10 && d[0]=='I' && d[1]=='D' && d[2]=='3') {
            long size = 0;
            for (int i = 6; i < 10; i++) size = (size << 7) | (d[i] & 0x7F);
            int off = (int) (10 + size);
            return Math.min(off, d.length);
        }
        return 0;
    }

    private static byte[] readAll(Context ctx, Uri uri) throws IOException {
        InputStream in = new BufferedInputStream(
                ctx.getContentResolver().openInputStream(uri), 1 << 16);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    // ================= WAV 字节裁剪 =================

    private static void trimWav(Context ctx, Uri src, Uri dst,
                                long startMs, long endMs) throws Exception {
        byte[] d = readAll(ctx, src);
        int channels = 0, bits = 0; long byteRate = 0;
        int dataOff = -1; long dataLen = 0;
        int p = 12;
        while (p + 8 <= d.length) {
            String id = str(d, p, 4);
            long sz = u32(d, p + 4);
            if (id.equals("fmt ")) {
                channels = u16(d, p + 8 + 2);
                byteRate = u32(d, p + 8 + 4);
                bits = u16(d, p + 8 + 14);
            } else if (id.equals("data")) {
                dataOff = p + 8;
                dataLen = sz;
            }
            p += 8 + sz + (sz % 2);
        }
        if (dataOff < 0 || channels == 0 || bits == 0 || byteRate == 0)
            throw new IOException("WAV 结构异常，仅支持标准 PCM 格式");

        int block = channels * bits / 8;
        long startByte = dataOff + (startMs * byteRate / 1000 / block) * block;
        long lenByte  = ((endMs - startMs) * byteRate / 1000 / block) * block;
        if (startByte + lenByte > dataOff + dataLen)
            lenByte = dataOff + dataLen - startByte;
        if (lenByte <= 0) throw new IOException("剪辑区间为空");

        OutputStream out = new BufferedOutputStream(
                ctx.getContentResolver().openOutputStream(dst, "w"), 1 << 16);
        byte[] hdr = new byte[44];
        putStr(hdr, 0, "RIFF");
        putU32(hdr, 4, 36 + lenByte);
        putStr(hdr, 8, "WAVE");
        putStr(hdr, 12, "fmt ");
        putU32(hdr, 16, 16);
        putU16(hdr, 20, 1);
        putU16(hdr, 22, channels);
        putU32(hdr, 24, byteRate);
        putU32(hdr, 28, byteRate);
        putU16(hdr, 32, block);
        putU16(hdr, 34, bits);
        putStr(hdr, 36, "data");
        putU32(hdr, 40, lenByte);
        out.write(hdr);
        byte[] buf = new byte[65536];
        long copied = 0;
        while (copied < lenByte) {
            int n = (int) Math.min(buf.length, lenByte - copied);
            out.write(d, (int) (startByte + copied), n);
            copied += n;
        }
        out.flush(); out.close();
    }

    // ================= M4A/MP4 无损重封装 =================

    private static void remux(Context ctx, Uri src, Uri dst,
                              long startMs, long endMs, Progress cb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, src, null);
        int track = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; break; }
        }
        if (track < 0) {
            ex.release();
            throw new IOException("文件中没有音频轨，请在选择文件时改用解码方式");
        }
        MediaFormat fmt = ex.getTrackFormat(track);
        ex.selectTrack(track);
        ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        File outCache = new File(ctx.getCacheDir(), "trim_mux.mp4");
        MediaMuxer muxer = new MediaMuxer(outCache.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int outTrack = muxer.addTrack(fmt);
        muxer.start();

        int maxBuf = fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 262144;
        ByteBuffer buf = ByteBuffer.allocate(maxBuf + 16384);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long base = -1;
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            long t = ex.getSampleTime();
            if (t >= endMs * 1000L) break;
            if (t >= startMs * 1000L) {
                if (base < 0) base = t;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = t - base;
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(outTrack, buf, info);
            }
            ex.advance();
        }
        muxer.stop();
        muxer.release();
        ex.release();

        copy(ctx, Uri.fromFile(outCache), dst, cb);
        //noinspection ResultOfMethodCallIgnored
        outCache.delete();
    }

    // ================= 通用解码导出 WAV =================

    private static void decodeToWav(Context ctx, Uri src, Uri dst,
                                    long startMs, long endMs, Progress cb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, src, null);
        int track = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; break; }
        }
        if (track < 0) { ex.release(); throw new IOException("没有可解码的音频轨"); }
        MediaFormat fmt = ex.getTrackFormat(track);
        ex.selectTrack(track);
        ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long spanUs = (endMs - startMs) * 1000L;
        long startUs = startMs * 1000L, endUs = endMs * 1000L;

        int sr = 0, ch = 0;
        boolean sawFmt = false;
        boolean eos = false;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(1 << 20);

        while (!eos) {
            int inIdx = codec.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
                ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                int size = ex.readSampleData(inBuf, 0);
                if (size < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                    ex.advance();
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            while (outIdx >= 0) {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (!sawFmt) {
                        MediaFormat of = codec.getOutputFormat();
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        sawFmt = true;
                    }
                    long ptsUs = info.presentationTimeUs;
                    if (ptsUs >= startUs && ptsUs < endUs) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        byte[] chunk = new byte[info.size];
                        outBuf.get(chunk);
                        pcm.write(chunk);
                    }
                    if (ptsUs >= endUs) eos = true;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                codec.releaseOutputBuffer(outIdx, false);
                if (eos) break;
                outIdx = codec.dequeueOutputBuffer(info, 10000);
            }
            if (cb != null && spanUs > 0)
                cb.onProgress((int) Math.min(99,
                        Math.max(0, (info.presentationTimeUs - startUs) * 100 / spanUs)));
        }
        codec.stop();
        codec.release();
        ex.release();

        if (!sawFmt) throw new IOException("解码失败，请换一个文件试试");
        byte[] data = pcm.toByteArray();
        int block = ch * 2;
        OutputStream out = new BufferedOutputStream(
                ctx.getContentResolver().openOutputStream(dst, "w"), 1 << 16);
        byte[] hdr = new byte[44];
        putStr(hdr, 0, "RIFF");
        putU32(hdr, 4, 36L + data.length);
        putStr(hdr, 8, "WAVE");
        putStr(hdr, 12, "fmt ");
        putU32(hdr, 16, 16);
        putU16(hdr, 20, 1);            // PCM
        putU16(hdr, 22, ch);           // 声道
        putU32(hdr, 24, sr);           // 采样率
        putU32(hdr, 28, (long) sr * block); // 字节率
        putU16(hdr, 32, block);        // 块对齐
        putU16(hdr, 34, 16);           // 位深
        putStr(hdr, 36, "data");
        putU32(hdr, 40, data.length);
        out.write(hdr);
        out.write(data);
        out.flush(); out.close();
        if (cb != null) cb.onProgress(100);
    }

    // ================= 工具 =================

    private static void putStr(byte[] a, int off, String s) {
        byte[] b = s.getBytes();
        System.arraycopy(b, 0, a, off, b.length);
    }
    private static void putU16(byte[] a, int off, int v) {
        a[off] = (byte) v; a[off + 1] = (byte) (v >> 8);
    }
    private static void putU32(byte[] a, int off, long v) {
        a[off] = (byte) v; a[off+1] = (byte)(v>>8); a[off+2] = (byte)(v>>16); a[off+3] = (byte)(v>>24);
    }
    static long u32(byte[] b, int off) {
        return (b[off]&0xFFL) | ((b[off+1]&0xFFL)<<8) | ((b[off+2]&0xFFL)<<16) | ((b[off+3]&0xFFL)<<24);
    }
    static int u16(byte[] b, int off) {
        return (b[off]&0xFF) | ((b[off+1]&0xFF)<<8);
    }
    private static String str(byte[] b, int off, int len) {
        return new String(b, off, len).trim();
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private static void copy(Context ctx, Uri src, Uri dst, Progress cb) throws IOException {
        InputStream in = new BufferedInputStream(
                ctx.getContentResolver().openInputStream(src), 1 << 16);
        OutputStream out = new BufferedOutputStream(
                ctx.getContentResolver().openOutputStream(dst, "w"), 1 << 16);
        long total = 0;
        try {
            android.os.ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(src, "r");
            if (pfd != null) { total = Math.max(0, pfd.getStatSize()); pfd.close(); }
        } catch (Exception ignored) {}
        byte[] buf = new byte[65536];
        long copied = 0; int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            copied += n;
            if (cb != null && total > 0) cb.onProgress((int) (copied * 100 / total));
        }
        out.flush(); out.close(); in.close();
    }
}