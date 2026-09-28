package com.jmj.audioclip;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

/** 全局崩溃捕获：堆栈写入外部专属目录 crash_log.txt，便于定位闪退原因 */
public class ClipApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Handler ui = new Handler(Looper.getMainLooper());
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File log = new File(dir, "crash_log.txt");
                PrintWriter pw = new PrintWriter(new FileWriter(log, true));
                pw.println("==== " + new Date() + " thread=" + t.getName());
                e.printStackTrace(pw);
                pw.close();
            } catch (Exception ignored) {}
            try {
                ui.post(() -> {
                    try {
                        String msg = e.toString();
                        if (msg.length() > 120) msg = msg.substring(0, 120);
                        Toast.makeText(ClipApp.this,
                                "很抱歉发生崩溃，日志已记录：\n" + msg,
                                Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            try { Thread.sleep(1200); } catch (Exception ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}