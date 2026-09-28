package com.jmj.audioclip;

import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;
import java.util.concurrent.Executors;

public class EditorActivity extends AppCompatActivity {

    private static final int REQ_SAVE = 2;
    private static final long STEP_SMALL = 500, STEP_BIG = 5000;

    private Uri src;
    private String srcName;
    private MediaPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long durationMs = 0;
    private long startMs, endMs;
    private long cursorMs;
    private boolean targetIsStart = true;   // 微调作用对象：起点/终点
    private boolean previewMode = false;    // 是否区间试听
    private boolean seeking = false;        // 用户是否正在拖动进度条
    private boolean wasPlaying = false;     // 拖动前是否在播放

    private TextView tvCursor, tvSel, tvTip;
    private EditText etStart, etEnd;
    private MaterialButton btnPlay, btnStart, btnEnd;
    private SeekBar seek;
    private boolean updatingTexts = false;  // 防止 setText 触发回调循环

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                long pos = player.getCurrentPosition();
                if (previewMode && pos >= endMs) {
                    player.pause();
                    previewMode = false;
                    setCursor(endMs);
                    updPlayBtn();
                    return;
                }
                setCursor(pos);
                ui.postDelayed(this, 33);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        src = getIntent().getData();
        srcName = getIntent().getStringExtra("name");
        if (src == null) { finish(); return; }

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(srcName == null ? "编辑音频" : srcName);
        }

        tvCursor = findViewById(R.id.tv_cursor);
        tvSel = findViewById(R.id.tv_sel);
        tvTip = findViewById(R.id.tv_tip);
        etStart = findViewById(R.id.et_start);
        etEnd = findViewById(R.id.et_end);
        btnPlay = findViewById(R.id.btn_play);
        btnStart = findViewById(R.id.btn_mark_start);
        btnEnd = findViewById(R.id.btn_mark_end);
        seek = findViewById(R.id.seek);

        //EditText 焦点变化时解析输入
        View.OnFocusChangeListener timeFocus = (v, hasFocus) -> {
            if (!hasFocus) {
                EditText et = (EditText) v;
                long parsed = parseTime(et.getText().toString());
                if (parsed >= 0) {
                    if (v.getId() == R.id.et_start) {
                        if (parsed >= endMs) { toast("起点不能大于终点"); }
                        else { startMs = parsed; refreshInfo(); }
                    } else {
                        if (parsed <= startMs) { toast("终点不能小于起点"); }
                        else { endMs = parsed; refreshInfo(); }
                    }
                } else {
                    toast("格式有误，请输入 分:秒.毫秒，如 01:23.500");
                    refreshInfo();
                }
                hideKeyboard(v);
            }
        };
        etStart.setOnFocusChangeListener(timeFocus);
        etEnd.setOnFocusChangeListener(timeFocus);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    cursorMs = clamp(progress);
                    tvCursor.setText(String.format(Locale.CHINA, "播放位置  %s",
                            MainActivity.fmtMs(cursorMs)));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
                wasPlaying = (player != null && player.isPlaying());
                ui.removeCallbacks(tick);
                if (wasPlaying && player != null) player.pause();
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (player != null) {
                    player.seekTo((int) cursorMs);
                    if (wasPlaying) {
                        previewMode = false;
                        player.start();
                        ui.post(tick);
                        updPlayBtn();
                    }
                }
            }
        });

        findViewById(R.id.btn_preview).setOnClickListener(v -> preview());
        findViewById(R.id.btn_step_back_big).setOnClickListener(v -> nudge(-STEP_BIG));
        findViewById(R.id.btn_step_back).setOnClickListener(v -> nudge(-STEP_SMALL));
        findViewById(R.id.btn_step_fwd).setOnClickListener(v -> nudge(STEP_SMALL));
        findViewById(R.id.btn_step_fwd_big).setOnClickListener(v -> nudge(STEP_BIG));
        findViewById(R.id.btn_save).setOnClickListener(v -> askSave());

        btnPlay.setOnClickListener(v -> togglePlay());
        btnStart.setOnClickListener(v -> {
            if (cursorMs >= endMs) { toast("起点不能大于终点"); return; }
            startMs = cursorMs; targetIsStart = true; refreshInfo();
        });
        btnEnd.setOnClickListener(v -> {
            if (cursorMs <= startMs) { toast("终点不能小于起点"); return; }
            endMs = cursorMs; targetIsStart = false; refreshInfo();
        });

        findViewById(R.id.btn_mark_start).setOnLongClickListener(v -> {
            startMs = 0; refreshInfo(); return true;
        });
        findViewById(R.id.btn_mark_end).setOnLongClickListener(v -> {
            if (durationMs > 0) { endMs = durationMs; refreshInfo(); }
            return true;
        });

        preparePlayer();
        loadFileInfo();
    }

    private void preparePlayer() {
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(this, src);
            player.prepare();
            durationMs = player.getDuration();
            startMs = 0; endMs = durationMs;
            seek.setMax((int) Math.max(1, durationMs));
            refreshInfo();
        } catch (Exception e) {
            toast("无法播放：" + e.getMessage());
        }
    }

    /** 快速读取文件信息（仅读头部元数据，不解码，毫秒级完成） */
    private void loadFileInfo() {
        TextView tvFname = findViewById(R.id.tv_fname);
        TextView tvFmt = findViewById(R.id.tv_fmt);
        TextView tvDur = findViewById(R.id.tv_dur);
        TextView tvSr = findViewById(R.id.tv_sr);
        TextView tvCh = findViewById(R.id.tv_ch);
        TextView tvBr = findViewById(R.id.tv_br);
        TextView tvSize = findViewById(R.id.tv_size);

        String name = srcName == null ? "音频文件" : srcName;
        tvFname.setText(name);

        String mime = "--";
        String sr = "--";
        String ch = "--";
        String br = "--";
        long durFromMeta = 0;
        try {
            MediaExtractor ex = new MediaExtractor();
            ex.setDataSource(this, src, null);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; break; }
            }
            if (track >= 0) {
                MediaFormat f = ex.getTrackFormat(track);
                if (f.containsKey(MediaFormat.KEY_MIME))
                    mime = f.getString(MediaFormat.KEY_MIME);
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) + " Hz";
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    int c = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    ch = c + (c == 1 ? "（单声道）" : "（立体声）");
                }
                if (f.containsKey(MediaFormat.KEY_BIT_RATE))
                    br = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 + " kbps";
                if (f.containsKey(MediaFormat.KEY_DURATION))
                    durFromMeta = f.getLong(MediaFormat.KEY_DURATION) / 1000;
            }
            ex.release();
        } catch (Exception ignored) {}

        if (durationMs <= 0 && durFromMeta > 0) {
            durationMs = durFromMeta;
            seek.setMax((int) Math.max(1, durationMs));
        }

        tvFmt.setText("格式：" + mime);
        if (durationMs > 0)
            tvDur.setText("时长：" + MainActivity.fmtMs(durationMs));
        else
            tvDur.setText("时长：" + (durFromMeta > 0 ? MainActivity.fmtMs(durFromMeta) : "--"));
        tvSr.setText("采样率：" + sr);
        tvCh.setText("声道：" + ch);
        tvBr.setText("比特率：" + br);
        tvSize.setText("文件大小：" + fmtSize(querySize(src)));

        if (durationMs > 0) { startMs = 0; endMs = durationMs; refreshInfo(); }
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private String fmtSize(long b) {
        if (b < 0) return "--";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024)
            return String.format(Locale.CHINA, "%.2f MB", b / (1024.0 * 1024));
        return String.format(Locale.CHINA, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    private void setCursor(long ms) {
        cursorMs = clamp(ms);
        tvCursor.setText(String.format(Locale.CHINA, "播放位置  %s",
                MainActivity.fmtMs(cursorMs)));
        if (!seeking) seek.setProgress((int) cursorMs);
    }

    private void nudge(long deltaMs) {
        if (targetIsStart) {
            startMs = clamp(startMs + deltaMs);
            if (startMs >= endMs) startMs = Math.max(0, endMs - 100);
            cursorMs = startMs;
        } else {
            endMs = clamp(endMs + deltaMs);
            if (endMs <= startMs) endMs = Math.min(durationMs, startMs + 100);
            cursorMs = endMs;
        }
        setCursor(cursorMs);
        refreshInfo();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            previewMode = false;
        } else {
            player.seekTo((int) cursorMs);
            previewMode = false;
            player.start();
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
        updPlayBtn();
    }

    private void preview() {
        if (player == null) return;
        previewMode = true;
        player.seekTo((int) startMs);
        player.start();
        ui.removeCallbacks(tick);
        ui.post(tick);
        updPlayBtn();
    }

    private void updPlayBtn() {
        boolean playing = player != null && player.isPlaying();
        btnPlay.setText(playing ? (previewMode ? "试听中" : "暂停") : "播放");
        btnPlay.setIconResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (playing)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void askSave() {
        String ext = AudioTrimmer.suggestExt(srcName == null ? "" : srcName);
        String base = srcName == null ? "剪辑" : srcName.replaceAll("\\.[^.]*$", "");
        String suggest = base + "_" + MainActivity.fmtMs(startMs).replaceAll("[:.]", "'")
                + "_" + ext;
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        switch (ext) {
            case ".mp3":  it.setType("audio/mpeg");      break;
            case ".wav":  it.setType("audio/x-wav");     break;
            case ".m4a":  it.setType("audio/mp4");       break;
            default:      it.setType("audio/*");
        }
        it.putExtra(Intent.EXTRA_TITLE, suggest);
        startActivityForResult(it, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE || res != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri dst = data.getData();
        long s = startMs, e = endMs;

        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("正在剪辑");
        pd.setMessage("无损处理中…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                AudioTrimmer.trim(this, src, dst, srcName, s, e, p ->
                        ui.post(() -> pd.setProgress(p)));
                ui.post(() -> {
                    pd.dismiss();
                    toast("剪辑完成，已保存");
                    new DbHelper(this).add(srcName, queryDisplayName(dst),
                            e - s, s, e);
                });
            } catch (Exception ex) {
                ui.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("剪辑失败")
                            .setMessage(ex.getMessage() == null ? "未知错误" : ex.getMessage())
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "剪辑结果";
    }

    private void refreshInfo() {
        updatingTexts = true;
        etStart.setText(MainActivity.fmtMs(startMs));
        etEnd.setText(MainActivity.fmtMs(endMs));
        updatingTexts = false;
        tvSel.setText(String.format(Locale.CHINA, "选中时长 %s / 全长 %s",
                MainActivity.fmtMs(endMs - startMs), MainActivity.fmtMs(durationMs)));
        btnStart.setText(String.format(Locale.CHINA, "设为起点%s", targetIsStart ? " ◀" : ""));
        btnEnd.setText(String.format(Locale.CHINA, "%s设为终点", targetIsStart ? "" : "▶ "));
        if (targetIsStart) setCursor(startMs); else setCursor(endMs);
    }

    /** 解析时间字符串，支持 mm:ss.mmm / ss.mmm / ss / mm:ss 等 */
    private long parseTime(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        try {
            long ms;
            int colon = s.indexOf(':');
            if (colon >= 0) {
                String minStr = s.substring(0, colon);
                String secStr = s.substring(colon + 1);
                int min = Integer.parseInt(minStr);
                double sec = Double.parseDouble(secStr);
                ms = min * 60000L + (long)(sec * 1000);
            } else {
                double sec = Double.parseDouble(s);
                ms = (long)(sec * 1000);
            }
            return clamp(ms);
        } catch (Exception e) {
            return -1;
        }
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private long clamp(long ms) {
        return Math.max(0, Math.min(durationMs > 0 ? durationMs : ms, ms));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
            updPlayBtn();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }
}