package com.jmj.audioclip;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 剪辑历史记录（SQLite） */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB = "audio_clip.db";
    private static final int VER = 1;

    public DbHelper(Context c) { super(c, DB, null, VER); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history(_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "src_name TEXT,out_name TEXT,duration_ms INTEGER," +
                "start_ms INTEGER,end_ms INTEGER,created_at INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    public static class Item {
        public long id; public String srcName; public String outName;
        public long durationMs; public long startMs; public long endMs; public long createdAt;
    }

    public void add(String src, String out, long durMs, long sMs, long eMs) {
        ContentValues v = new ContentValues();
        v.put("src_name", src); v.put("out_name", out);
        v.put("duration_ms", durMs); v.put("start_ms", sMs); v.put("end_ms", eMs);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("history", null, v);
    }

    public List<Item> list() {
        List<Item> r = new ArrayList<>();
        Cursor c = getReadableDatabase().query("history", null,
                null, null, null, null, "created_at DESC");
        while (c.moveToNext()) {
            Item i = new Item();
            i.id = c.getLong(0); i.srcName = c.getString(1); i.outName = c.getString(2);
            i.durationMs = c.getLong(3); i.startMs = c.getLong(4); i.endMs = c.getLong(5);
            i.createdAt = c.getLong(6);
            r.add(i);
        }
        c.close();
        return r;
    }

    public void delete(long id) {
        getWritableDatabase().delete("history", "_id=?", new String[]{String.valueOf(id)});
    }

    public void clear() {
        getWritableDatabase().delete("history", null, null);
    }
}