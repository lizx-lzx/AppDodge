package dev.local.appdodge;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Non-exported. Only the reply to the current short-lived system pairing notification is accepted. */
public final class StopPairingReply extends BroadcastReceiver {
    static final String KEY="local_pair_code";
    @Override public void onReceive(Context c,Intent intent){
        if(intent==null || !StopSetup.REPLY.equals(intent.getAction()))return;
        StopSetup.stage("reply-delivered");
        Bundle input=RemoteInput.getResultsFromIntent(intent);
        CharSequence text=input==null?null:input.getCharSequence(KEY);
        StopSetup.reply(c,intent.getLongExtra("session",0),intent.getLongExtra("until",0),text);
    }
}
