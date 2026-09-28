package com.jmj.audioclip;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 波形解码器：MediaExtractor + MediaCodec 解码为 PCM，
 * 均匀抽取峰值，生成绘制用的波形数据。
 */
public final class WaveformDecoder {

    public interface Callback {
        void onReady(short[] peaks, int sampleRate, int channels, long durationUs);
        void onError(String msg);
        void onProgress(int percent);
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private WaveformDecoder() {}

    /** 解码音频并抽取约 points 个峰值点 */
    public static void decode(final Context ctx, final Uri uri,
                              final int points, final Callback cb) {
        POOL.submit(() -> {
            MediaExtractor ex = null;
            MediaCodec codec = null;
            try {
                ex = new MediaExtractor();
                ex.setDataSource(ctx, uri, null);
                int track = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { track = i; break; }
                }
                if (track < 0) { cb.onError("没有可解码的音频轨"); return; }
                MediaFormat fmt = ex.getTrackFormat(track);
                ex.selectTrack(track);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();

                long durUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                        ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;

                // 每点对应的微秒数（未知时长时按 30 分钟兜底）
                long spanUs = durUs > 0 ? durUs : 30L * 60 * 1_000_000;
                int[] maxVal = new int[points];
                int outRate = 0, outCh = 0;
                boolean sawFmt = false, eos = false;

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (!eos) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int size = ex.readSampleData(ib, 0);
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
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            codec.releaseOutputBuffer(outIdx, false);
                            outIdx = codec.dequeueOutputBuffer(info, 10000);
                            continue;
                        }
                        if (!sawFmt) {
                            MediaFormat of = codec.getOutputFormat();
                            outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            outCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            sawFmt = true;
                        }
                        if (info.size > 0) {
                            ByteBuffer ob = codec.getOutputBuffer(outIdx);
                            long pts = info.presentationTimeUs;
                            int bucket = (int) (pts * points / spanUs);
                            if (bucket >= 0 && bucket < points) {
                                // 在该点窗口内找最大绝对值（抽样步进 2 字节 x 通道）
                                byte[] chunk = new byte[info.size];
                                ob.get(chunk);
                                for (int i = 0; i + 1 < chunk.length; i += 2) {
                                    int v = (short) ((chunk[i] & 0xFF) | (chunk[i+1] << 8));
                                    int av = Math.abs(v);
                                    if (av > maxVal[bucket]) maxVal[bucket] = av;
                                }
                            }
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                        codec.releaseOutputBuffer(outIdx, false);
                        if (eos) break;
                        outIdx = codec.dequeueOutputBuffer(info, 10000);
                    }
                    if (cb != null && durUs > 0 && info.presentationTimeUs > 0)
                        cb.onProgress((int) Math.min(99,
                                info.presentationTimeUs * 100 / durUs));
                }

                short[] peaks = new short[points];
                for (int i = 0; i < points; i++)
                    peaks[i] = (short) Math.min(32767, maxVal[i]);
                if (durUs <= 0) {
                    // 依据最后 PTS 修正时长
                    durUs = Math.max(info.presentationTimeUs, spanUs);
                }
                cb.onReady(peaks, outRate, outCh, durUs);
            } catch (Exception e) {
                cb.onError("解码失败：" + e.getMessage());
            } finally {
                try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
                try { if (ex != null) ex.release(); } catch (Exception ignored) {}
            }
        });
    }
}