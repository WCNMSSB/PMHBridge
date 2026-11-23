package com.pmh.xrlayertest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class MainActivity extends Activity {

    private static final String TAG = "PmhXrLayerTest";

    static {
        System.loadLibrary("xrlayertest");
    }

    private native String probe(Context context);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String result;
        try {
            result = probe(this);
        } catch (Throwable t) {
            result = "probe failed: " + t;
        }
        Log.i(TAG, result);
        TextView view = new TextView(this);
        view.setText(result);
        view.setTextSize(20f);
        setContentView(view);
    }
}
