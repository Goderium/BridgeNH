package com.Goderium.BridgeNH.common.survival;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.Set;

/** Check the native SNBT reader's round trip, allowing reordered compound keys and no scalar coercion. */
final class StorageItemNbt {
    private final String text;
    private int pos;
    private StorageItemNbt(String text) { this.text=text; }

    static boolean roundTrips(String requested,String observed) {
        if(requested.length()>4096 || observed.length()>8192) return false;
        try { return new StorageItemNbt(requested).root().equals(new StorageItemNbt(observed).root()); }
        catch(IllegalArgumentException ambiguous) { return false; }
    }

    private String root() {
        space();
        if(current()!='{') throw new IllegalArgumentException();
        String value=value(0);
        space();
        if(pos!=text.length()) throw new IllegalArgumentException();
        return value;
    }
    private char current() { return pos<text.length()?text.charAt(pos):0; }
    private void space() { while(pos<text.length()&&Character.isWhitespace(current())) pos++; }
    private String token() {
        space();
        int start=pos;
        if(current()=='"') {
            pos++;
            while(pos<text.length()) {
                char c=text.charAt(pos++);
                if(c=='"') return text.substring(start,pos);
                if(c=='\\') {
                    if(current()!='"'&&current()!='\\') throw new IllegalArgumentException();
                    pos++;
                }
            }
            throw new IllegalArgumentException();
        }
        while(pos<text.length() && !Character.isWhitespace(current()) && ",:{}[]\"'".indexOf(current())<0) pos++;
        if(start==pos) throw new IllegalArgumentException();
        return text.substring(start,pos);
    }
    private String value(int depth) {
        if(depth>32) throw new IllegalArgumentException();
        space();
        char open=current();
        if(open!='{' && open!='[') return token();
        pos++;
        char close=open=='{'?'}':']';
        List<String> entries=new ArrayList<String>();
        Set<String> keys=new HashSet<String>();
        space();
        while(current()!=close) {
            String prefix="";
            if (open=='{') {
                String key=token();
                // A quoted and unquoted spelling of the same simple key may not be duplicated.
                String bare=key.startsWith("\"")?key.substring(1,key.length()-1):key;
                if(!keys.add(bare)) throw new IllegalArgumentException();
                space();
                if(current()!=':') throw new IllegalArgumentException();
                pos++;
                prefix=key+":";
            } else {
                int start=pos;
                while(current()>='0'&&current()<='9') pos++;
                if(pos>start&&current()==':') { pos++; prefix=text.substring(start,pos); }
                else pos=start;
            }
            entries.add(prefix+value(depth+1));
            space();
            if(current()==',') { pos++; space(); }
            else if(current()!=close) throw new IllegalArgumentException();
        }
        pos++;
        if(open=='{') Collections.sort(entries);
        return open+String.join(",",entries)+close;
    }
}
