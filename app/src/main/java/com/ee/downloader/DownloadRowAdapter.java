package com.ee.downloader;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class DownloadRowAdapter extends BaseAdapter {
    static final class Entry {
        final long id;
        final String title;
        final int status;
        final int progress;
        Entry(long id, String title, int status, int progress) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.progress = progress;
        }
    }

    private final Context context;
    private final List<Entry> items = new ArrayList<>();
    DownloadRowAdapter(Context context) { this.context = context; }

    void replace(List<Entry> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Entry getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return getItem(position).id; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Entry item = getItem(position);
        int padding = dp(14);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundResource(R.drawable.input_background);

        TextView name = new TextView(context);
        name.setText(item.title);
        name.setTextColor(0xFFF8FAFF);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextSize(14);
        name.setMaxLines(2);
        root.addView(name);

        TextView state = new TextView(context);
        state.setText(statusLabel(item.status, item.progress));
        state.setTextColor(0xFFABB9D2);
        state.setTextSize(12);
        state.setPadding(0, dp(6), 0, 0);
        root.addView(state);

        if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING
                || item.status == DownloadManager.STATUS_PAUSED) {
            ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(item.progress);
            bar.setIndeterminate(item.progress <= 0 && item.status != DownloadManager.STATUS_PAUSED);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF7788FF));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
            lp.topMargin = dp(9);
            root.addView(bar, lp);
        }
        root.setGravity(Gravity.CENTER_VERTICAL);
        return root;
    }

    private String statusLabel(int status, int progress) {
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL: return "اكتمل التنزيل • اضغط للفتح";
            case DownloadManager.STATUS_FAILED: return "فشل التنزيل • اضغط مطولًا لإزالة السجل";
            case DownloadManager.STATUS_RUNNING: return "جارٍ التنزيل • " + progress + "%";
            case DownloadManager.STATUS_PAUSED: return "متوقف مؤقتًا • " + progress + "%";
            case DownloadManager.STATUS_PENDING: return "بانتظار بدء التنزيل";
            default: return "حالة غير معروفة";
        }
    }

    private int dp(int v) { return (int) (v * context.getResources().getDisplayMetrics().density + .5f); }
}
