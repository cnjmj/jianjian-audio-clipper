package com.jmj.audioclip;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK = 1;
    private DbHelper db;
    private HistoryAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new DbHelper(this);
        tvEmpty = findViewById(R.id.tv_empty);
        setSupportActionBar(findViewById(R.id.toolbar));
        RecyclerView rv = findViewById(R.id.rv_history);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(this);
        rv.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_pick);
        fab.setOnClickListener(v ->
                startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("audio/*"), REQ_PICK));

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<DbHelper.Item> items = db.list();
        adapter.setItems(items);
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            // 持久授权
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String name = queryName(uri);
            Intent it = new Intent(this, EditorActivity.class);
            it.setData(uri);
            it.putExtra("name", name);
            startActivity(it);
        }
    }

    String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null,
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "音频";
    }

    void deleteItem(long id) {
        db.delete(id);
        refresh();
        Toast.makeText(this, "已删除记录", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_clear) {
            new AlertDialog.Builder(this)
                    .setTitle("清空历史")
                    .setMessage("确定要清空全部剪辑记录吗？")
                    .setPositiveButton("清空", (d, w) -> { db.clear(); refresh(); })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    static String fmtMs(long ms) {
        long s = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d.%03d", s / 60, s % 60, ms % 1000);
    }
}