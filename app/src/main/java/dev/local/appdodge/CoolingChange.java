package dev.local.appdodge;

/** Read-only view of a future user-approved change; the value itself remains in the ledger. */
final class CoolingChange {
    final String key,kind,title,pkg,value;
    final long effectiveAt;
    CoolingChange(String key,String kind,String title,String value,long at){
        this.value=value;this.key=key;this.kind=kind;this.title=title;effectiveAt=at;
        pkg=key.startsWith("rule:")?key.substring(5):"";
    }
}
