package com.damian.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.provider.Settings;

/**
 * Deschide TV-ul pe tuner, nu pe ultima sursă HDMI: aplicația TV a TCL reia ultima sursă, deci
 * deschidem un canal real. Doar citește lista de canale (READ_TV_LISTINGS), nu scrie nimic în ea.
 */
final class LiveTv {
    private static final String TV_PKG = "com.tcl.tv";
    private static final String TV_ACT = "com.tcl.player.TVActivity";

    private LiveTv() {
    }

    private static long pickChannel(Context c) {
        long recent = -1;
        try {
            recent = Settings.System.getLong(c.getContentResolver(), "recent_play_channel_id", -1);
        } catch (Exception ignored) {
        }
        long best = -1;
        double bestNum = Double.MAX_VALUE;
        Cursor q = null;
        try {
            ContentResolver cr = c.getContentResolver();
            q = cr.query(TvContract.Channels.CONTENT_URI, new String[]{
                    TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_BROWSABLE}, null, null, null);
            if (q == null) return -1;
            while (q.moveToNext()) {
                String in = q.getString(1);
                if (in == null || !in.contains("TunerInputService")) continue;
                if (q.getInt(3) != 1) continue;
                long id = q.getLong(0);
                if (id == recent) return id;
                double n;
                try {
                    n = Double.parseDouble(q.getString(2));
                } catch (Exception e) {
                    n = Double.MAX_VALUE - 1;
                }
                if (n < bestNum) {
                    bestNum = n;
                    best = id;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (q != null) q.close();
        }
        return best;
    }

    static boolean open(Context c) {
        try {
            long id = pickChannel(c);
            if (id > 0) {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id));
                i.setPackage(TV_PKG);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent();
            i.setClassName(TV_PKG, TV_ACT);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
