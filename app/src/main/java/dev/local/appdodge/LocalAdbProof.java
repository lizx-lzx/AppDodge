package dev.local.appdodge;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** A paired loopback transport must prove its peer can perform a shell-only call on THIS phone.
 * A local process advertising a false port cannot satisfy it. No data or actions are exposed. */
public final class LocalAdbProof extends ContentProvider {
    static volatile String pending;
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if (Binder.getCallingUid()!=2000 || !"prove".equals(method)) throw new SecurityException();
        Bundle b=new Bundle();String proof=pending;
        if(proof!=null)b.putString("proof",proof);
        return b;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri u){return null;}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
