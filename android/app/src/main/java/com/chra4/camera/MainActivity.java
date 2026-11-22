package com.chra4.camera;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // native camera alerts (see KameraGuardPlugin.java)
        registerPlugin(KameraGuardPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
