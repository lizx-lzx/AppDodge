package dev.local.appdodge;

import java.io.*;

public final class StopPackageStateTest {
    static final String P="dev.sample.game";
    static int check(String users,int id)throws Exception{
        return StopPackageState.read(new BufferedReader(new StringReader("User 0: installed=true stopped=true\nPackages:\n  Package ["+P+"] (ab1):\n"+users+"\nQueries:\n")),P,id);
    }
    static void yes(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        yes(StopPackageState.validName("org.example.Game_2")&&!StopPackageState.validName("-a")&&!StopPackageState.validName("dev.sample;x"));
        String owner="    User 0: ceDataInode=73 installed=true hidden=false suspended=false stopped=false notLaunched=false";
        String clone="    User 999: ceDataInode=0 installed=false hidden=false stopped=true";
        yes(check(owner+"\n"+clone,0)==StopPackageState.RUNNABLE);
        yes(check(owner.replace("stopped=false","stopped=true")+"\n"+clone,0)==StopPackageState.STOPPED);
        yes(check(owner+"\n"+clone.replace("installed=false","installed=true"),0)==StopPackageState.MULTIPLE);
        yes(check(owner+"\n"+clone,999)==StopPackageState.UNKNOWN);
        yes(check(owner.replace("stopped=false","notLaunched=true"),0)==StopPackageState.UNKNOWN);
        yes(StopPackageState.read(new BufferedReader(new StringReader("Packages:\n  Package ["+P+"] (a):\n"+owner+"\n")),P,0)==StopPackageState.UNKNOWN);
        System.out.println("StopPackageState: validated package names, current-user stopped state and clone isolation.");
    }
}
