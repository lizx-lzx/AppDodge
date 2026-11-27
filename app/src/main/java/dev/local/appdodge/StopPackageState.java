package dev.local.appdodge;

import java.io.*;
import java.util.regex.*;

/** Reads the selected package only. Android clone/work profiles can share a package name. */
final class StopPackageState {
    static final int UNKNOWN=-1, RUNNABLE=0, STOPPED=1, MULTIPLE=2;
    static final int PER_USER_RANGE=100000;
    private static final Pattern PACKAGE=Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern USER=Pattern.compile("^\\s*User (\\d+):");
    static boolean validName(String p){return p!=null && p.length()<=255 && PACKAGE.matcher(p).matches();}
    static int read(BufferedReader input,String pkg,int user) throws IOException {
        boolean inPackages=false, selected=false, complete=false;
        int mine=UNKNOWN, installed=0;
        String line;
        while((line=input.readLine())!=null){
            if("Packages:".equals(line)){inPackages=true;selected=false;continue;}
            if(!inPackages)continue;
            if(!line.isEmpty()&&!Character.isWhitespace(line.charAt(0))){
                complete=selected && line.endsWith(":");break;
            }
            if(line.startsWith("  Package [")){
                if(selected){complete=true;break;}
                selected=line.startsWith("  Package ["+pkg+"]");continue;
            }
            if(!selected)continue;
            Matcher m=USER.matcher(line);
            if(m.find() && (" "+line+" ").contains(" installed=true ")){
                installed++;
                if(Integer.toString(user).equals(m.group(1))){
                    if((" "+line+" ").contains(" stopped=true "))mine=STOPPED;
                    else if((" "+line+" ").contains(" stopped=false "))mine=RUNNABLE;
                }
            }
        }
        // A timed-out dump cut between the main user and a clone is not proof of a single install.
        return installed>1?MULTIPLE:complete?mine:UNKNOWN;
    }
    private StopPackageState(){}
}
