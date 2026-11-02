package com.damian.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Prinde butonul de canale al telecomenzii TCL (cod 4001), pe care sistemul îl consumă
 * înainte să ajungă la aplicații, și deschide TV-ul când nu ești deja în el.
 * Folosește doar numele pachetului aplicației din față și tastele; nu citește ecranul.
 */
public class KeyService extends AccessibilityService {
    private static final int CHANNELS = 4001;
    private static final String TV = "com.tcl.tv";
    private String front = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String s = p.toString();
        if (s.equals("android") || s.startsWith("com.android.systemui")) return;
        front = s;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != CHANNELS) return false;
        if (TV.equals(front)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            try {
                Intent i = new Intent();
                i.setClassName(TV, "com.tcl.player.TVActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }
}
