package dev.local.appdodge;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An explicitly opted-in lease on the shared MUSIC stream, never on ring, alarm or voice.
 * The journal is committed before muting. A reconnect/relaunch repairs an interrupted lease
 * unless the user has since made media audible. Never write a previous route's level to a new one. */
final class MediaSilence {
    private static final String PREFS="appdodge_media_lease", LEVEL="before", ROUTE="output", MUTED="muted";
    private static final int MUSIC=AudioManager.STREAM_MUSIC;
    private static final AudioAttributes MEDIA=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    private final AudioManager audio;
    private final SharedPreferences journal;
    private boolean active, refused;

    MediaSilence(Context c) {
        audio=c.getSystemService(AudioManager.class);
        journal=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        restoreStale();
    }
    /** Only when no service currently owns the lease, e.g. UI startup after force-stop. */
    static void recover(Context c) {
        if(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).contains(LEVEL)) new MediaSilence(c);
    }
    private String route() {
        // On API 31/32 route-specific selection isn't public. Do not transfer numeric levels
        // when an output is plugged/unplugged between entry and release.
        List<String> keys=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33) {
            for(AudioDeviceInfo d:audio.getAudioDevicesForAttributes(MEDIA))keys.add(d.getType()+":"+d.getId());
        } else {
            for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))keys.add(d.getType()+":"+d.getId());
        }
        Collections.sort(keys);return keys.toString();
    }
    void enforce() {
        if(refused || audio==null || audio.isVolumeFixed() || audio.getMode()!=AudioManager.MODE_NORMAL) return;
        try {
            if(!active) {
                if(journal.contains(LEVEL)) {restoreStale(); if(journal.contains(LEVEL)){refused=true;return;}}
                boolean saved=journal.edit().putInt(LEVEL,audio.getStreamVolume(MUSIC))
                        .putBoolean(MUTED,audio.isStreamMute(MUSIC)).putString(ROUTE,route()).commit();
                if(!saved){refused=true;return;}
                active=true;
            }
            if(!audio.isStreamMute(MUSIC))
                audio.adjustStreamVolume(MUSIC,AudioManager.ADJUST_MUTE,0);
        } catch(SecurityException | IllegalStateException e) {
            refused=true;Log.w(NativeRestService.TAG,"media_mute_unavailable");release();
            refused=true; // do not keep writing if platform policy refused this visit
        }
    }
    boolean owns() {return active;}
    private void restoreStale() { if(journal.contains(LEVEL)) restore(true); }
    void release() { if(active)restore(false);active=false;refused=false; }
    private void restore(boolean stale) {
        if(audio==null) return;
        try {
            if(!journal.contains(LEVEL))return;
            boolean nowMuted=audio.isStreamMute(MUSIC);
            if(!stale || nowMuted) {
                boolean beforeMuted=journal.getBoolean(MUTED,false);
                int before=journal.getInt(LEVEL,0);
                // Adjust mute preserves Android's per-output level; never transfer a loud
                // speaker index onto a headset after a route change.
                boolean sameOutput=journal.getString(ROUTE,"").equals(route());
                if(beforeMuted!=nowMuted)
                    audio.adjustStreamVolume(MUSIC,beforeMuted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
                if(!beforeMuted && sameOutput && audio.getStreamVolume(MUSIC)!=before)
                    audio.setStreamVolume(MUSIC,before,0);
            }
            journal.edit().clear().commit();
        } catch(SecurityException | IllegalStateException e) {
            Log.w(NativeRestService.TAG,"media_restore_pending"); // leave journal for reconnect
        }
    }
}
