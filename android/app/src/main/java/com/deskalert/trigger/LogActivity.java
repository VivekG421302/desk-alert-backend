package com.deskalert.trigger;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Live list of everything the app received and what it did with it. */
public class LogActivity extends Activity {
    private final Handler ui = new Handler();
    private ListView list;
    private TextView summary;
    private ArrayAdapter<String[]> adapter;

    private final Runnable refresh = new Runnable() { public void run() { reload(); ui.postDelayed(this, 2000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        summary = new TextView(this);
        summary.setTextSize(14);
        summary.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Journal.clear(LogActivity.this); reload(); } });
        bar.addView(summary);
        bar.addView(clear);

        list = new ListView(this);
        adapter = new ArrayAdapter<String[]>(this, 0, new ArrayList<String[]>()) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout row;
                if (convert == null) {
                    row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.VERTICAL);
                    int p = (int) (8 * getResources().getDisplayMetrics().density);
                    row.setPadding(0, p, 0, p);
                    TextView head = new TextView(getContext()); head.setTextSize(13); head.setTypeface(Typeface.DEFAULT_BOLD);
                    TextView body = new TextView(getContext()); body.setTextSize(14);
                    row.addView(head); row.addView(body);
                } else row = (LinearLayout) convert;
                String[] e = getItem(pos);
                TextView head = (TextView) row.getChildAt(0), body = (TextView) row.getChildAt(1);
                String time = DateFormat.format("HH:mm:ss", new Date(Long.parseLong(e[0]))).toString();
                head.setText(time + "  " + e[1] + "  \u2022  " + e[3]);
                String st = e[3];
                head.setTextColor(st.startsWith("SENT") ? Color.rgb(22, 140, 70)
                        : (st.startsWith("FAILED") || st.startsWith("OFFLINE") || st.startsWith("REJECTED") || st.startsWith("ERROR") || st.startsWith("NOT SENT")) ? Color.rgb(200, 40, 40)
                        : st.startsWith("SKIPPED") || st.startsWith("IGNORED") ? Color.GRAY : Color.rgb(30, 90, 200));
                body.setText(e[2]);
                return row;
            }
        };
        list.setAdapter(adapter);

        root.addView(bar);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        setTitle("Activity log");
    }

    private void reload() {
        List<String[]> all = Journal.all(this);
        adapter.clear();
        adapter.addAll(all);
        summary.setText(all.size() + " entries \u2022 waiting to send: " + Outbox.size(this));
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }
}
