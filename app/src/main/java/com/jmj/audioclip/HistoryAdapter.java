package com.jmj.audioclip;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface OnLongClick { void onLong(long id); }

    private final MainActivity act;
    private final List<DbHelper.Item> data = new ArrayList<>();

    HistoryAdapter(MainActivity act) { this.act = act; }

    void setItems(List<DbHelper.Item> items) {
        data.clear();
        data.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
        View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_history, p, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        DbHelper.Item it = data.get(pos);
        h.title.setText(it.outName == null ? it.srcName : it.outName);
        h.sub.setText(String.format(Locale.CHINA, "剪辑区间 %s – %s（原始：%s）",
                MainActivity.fmtMs(it.startMs), MainActivity.fmtMs(it.endMs), it.srcName));
        String t = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(it.createdAt));
        h.time.setText(t);
        h.itemView.setOnLongClickListener(v -> {
            act.deleteItem(it.id);
            return true;
        });
    }

    @Override public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView title, sub, time;
        VH(View v) {
            super(v);
            title = v.findViewById(R.id.tv_h_title);
            sub = v.findViewById(R.id.tv_h_sub);
            time = v.findViewById(R.id.tv_h_time);
        }
    }
}