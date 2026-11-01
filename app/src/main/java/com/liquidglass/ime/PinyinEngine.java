package com.liquidglass.ime;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.*;

/** Read-only indexed lexicon plus bounded local personalized entries. Call on one worker. */
public final class PinyinEngine {
    public static final int LIMIT = 24;
    public static final class Candidate {
        public final String text, spelling;
        public final int consumed;
        final double score;
        Candidate(String text, String spelling, int consumed, double score) {
            this.text=text; this.spelling=spelling; this.consumed=consumed; this.score=score;
        }
    }
    private ByteBuffer data;
    private int count, records;
    private final TreeMap<String,List<Candidate>> fixture = new TreeMap<>();
    private final TreeMap<String,List<Candidate>> characters = new TreeMap<>();
    private final Map<String, Learned> learned = new HashMap<>();
    public static final class Learned {
        public final String spelling,text;
        public int count;
        public long lastUsed;
        Learned(String spelling,String text,int count,long lastUsed) { this.spelling=spelling;this.text=text;this.count=count;this.lastUsed=lastUsed; }
    }
    public static String normalize(String input) { return input.toLowerCase(Locale.ROOT).replace("'", "").replace(" ", "").replace("ü", "v"); }
    public static String t9(String input) {
        StringBuilder out=new StringBuilder();
        for(char c:normalize(input).toCharArray()) { if(c>='a'&&c<='z')out.append(digit(c));else out.append(c); }
        return out.toString();
    }
    /** Temporary letters while a new T9 decode is in flight; never expose numeric codes. */
    static String fallbackSpelling(String raw){StringBuilder out=new StringBuilder();for(char c:normalize(raw).toCharArray()){if(c>='2'&&c<='9')out.append("adgjmptw".charAt(c-'2'));else if(c>='a'&&c<='z')out.append(c);}return out.toString();}
    /** 数字与显式字母混合的九宫格查询键：字母转成对应数字，数字原样保留。 */
    public static String t9Mixed(String input){StringBuilder out=new StringBuilder();for(char c:normalize(input).toCharArray()){if(c>='2'&&c<='9')out.append(c);else if(c>='a'&&c<='z')out.append(digit(c));}return out.toString();}
    private static boolean hasLetter(String key){for(int i=0;i<key.length();i++){char c=key.charAt(i);if(c>='a'&&c<='z')return true;}return false;}
    /** 候选拼音是否满足混合约束：数字位匹配同键字母，字母位必须完全一致。 */
    private static boolean compatible(String mix,String spelling){if(spelling==null||spelling.length()<mix.length())return false;for(int i=0;i<mix.length();i++){char k=mix.charAt(i),s=spelling.charAt(i);if(k>='2'&&k<='9'){if(digit(s)!=k)return false;}else if(s!=k)return false;}return true;}
    private static char digit(int c) { return "22233344455566677778889999".charAt(c-'a'); }
    public void map(FileChannel channel,long offset,long length) throws IOException {
        ByteBuffer buffer=channel.map(FileChannel.MapMode.READ_ONLY,offset,length).order(ByteOrder.BIG_ENDIAN);
        if(buffer.getInt(0)!=0x4c474432)throw new IOException("Unknown dictionary format");
        count=buffer.getInt(4);records=8+count*8;data=buffer;
    }
    /** Text fixture loader, also useful for small custom dictionaries in tests. */
    public void load(InputStream stream) throws IOException {
        try(BufferedReader r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String s;while((s=r.readLine())!=null) {
                String[] f=s.split("\t");if(f.length<2)continue;
                int weight=f.length>3?Integer.parseInt(f[3]):100;
                fixture.computeIfAbsent(f[0],k->new ArrayList<>()).add(new Candidate(f[1],f[0],0,Math.log1p(weight)));
                if(f[1].codePointCount(0,f[1].length())==1)characters.computeIfAbsent(f[0],k->new ArrayList<>()).add(new Candidate(f[1],f[0],0,Math.log1p(weight)));
            }
        }
    }
    /** Independent character index retains rare characters beyond the normal phrase limit. */
    public void loadCharacters(InputStream stream) throws IOException {
        characters.clear();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
            String line;while((line=r.readLine())!=null){String[] f=line.split("\t");if(f.length==3)characters.computeIfAbsent(f[0],k->new ArrayList<>()).add(new Candidate(f[1],f[0],0,Math.log1p(Integer.parseInt(f[2]))));}
        }
    }
    private static int rawOffset(String raw,int length){int at=0,n=0;while(at<raw.length()&&n<length){char c=raw.charAt(at++);if(c!='\''&&c!=' ')n++;}return at;}
    private List<Candidate> firstCharacters(String raw,boolean nine){
        String key=normalize(raw);int boundary=raw.indexOf('\'');String first=boundary>0?normalize(raw.substring(0,boundary)):key;
        // Prefer the longest complete first syllable that permits the remaining syllables.
        Set<String> codes=new HashSet<>();for(String spelling:characters.keySet())codes.add(nine?t9(spelling):spelling);
        boolean[] valid=new boolean[first.length()+1];valid[first.length()]=true;
        for(int start=first.length()-1;start>=0;start--)for(int end=start+1;end<=Math.min(first.length(),start+6);end++)if(codes.contains(first.substring(start,end))&&valid[end]){valid[start]=true;break;}
        int length=0;
        for(int n=Math.min(first.length(),6);n>0;n--)if(codes.contains(first.substring(0,n))&&(boundary>0?n==first.length():valid[n])){length=n;break;}
        if(length==0&&boundary<0)for(int n=Math.min(first.length(),6);n>0;n--)if(codes.contains(first.substring(0,n))){length=n;break;}
        if(length==0)return Collections.emptyList();
        String code=first.substring(0,length);List<Candidate> out=new ArrayList<>();int consumed=rawOffset(raw,length);
        for(Map.Entry<String,List<Candidate>> e:characters.entrySet())if((nine?t9(e.getKey()):e.getKey()).equals(code))for(Candidate c:e.getValue())out.add(new Candidate(c.text,c.spelling,consumed,c.score));
        out.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed());return out;
    }
    private int offset(int index,boolean nine) { return records+data.getInt(8+(nine?count:0)*4+index*4); }
    private int compare(int pos,String key,boolean nine) {
        int n=data.getShort(pos)&65535;int m=Math.min(n,key.length());
        for(int i=0;i<m;i++) { int c=data.get(pos+8+i)&255;if(nine)c=digit(c);int diff=c-key.charAt(i);if(diff!=0)return diff; }
        return n-key.length();
    }
    private boolean startsWith(int pos,String key,boolean nine) {
        int n=data.getShort(pos)&65535;if(n<key.length())return false;
        for(int i=0;i<key.length();i++){int c=data.get(pos+8+i)&255;if(nine)c=digit(c);if(c!=key.charAt(i))return false;}return true;
    }
    private int lowerBound(String key,boolean nine) {
        int lo=0,hi=count;while(lo<hi){int mid=(lo+hi)>>>1;if(compare(offset(mid,nine),key,nine)<0)lo=mid+1;else hi=mid;}return lo;
    }
    private Candidate read(int pos,int consumed) {
        int a=data.getShort(pos)&65535,b=data.getShort(pos+2)&65535,weight=data.getInt(pos+4);
        byte[] key=new byte[a],text=new byte[b];ByteBuffer slice=data.duplicate();slice.position(pos+8);slice.get(key);slice.get(text);
        return new Candidate(new String(text,StandardCharsets.UTF_8),new String(key,StandardCharsets.US_ASCII),consumed,Math.log1p(weight));
    }
    private List<Candidate> lookup(String key,boolean nine,boolean prefix,int consumed) {
        List<Candidate> result=new ArrayList<>();
        if(data!=null) {
            int at=lowerBound(key,nine);
            for(int i=at,scanned=0;i<count&&scanned<256;i++,scanned++) {
                int pos=offset(i,nine);
                if(prefix?!startsWith(pos,key,nine):compare(pos,key,nine)!=0)break;
                result.add(read(pos,consumed));
            }
        }
        for(Map.Entry<String,List<Candidate>> e:fixture.entrySet()) {
            String k=nine?t9(e.getKey()):e.getKey();if(prefix?k.startsWith(key):k.equals(key))for(Candidate c:e.getValue())result.add(new Candidate(c.text,c.spelling,consumed,c.score));
        }
        result.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed());
        return result.size()>LIMIT?new ArrayList<>(result.subList(0,LIMIT)):result;
    }
    public List<Candidate> candidates(String raw) { return candidates(raw,false,true); }
    public List<Candidate> candidates(String raw,boolean nine,boolean personalized) {
        String key=normalize(raw);if(key.isEmpty())return Collections.emptyList();
        boolean mixed=nine&&hasLetter(key);
        String search=nine?t9Mixed(key):key;
        List<Candidate> result=new ArrayList<>();
        if(personalized)for(Learned entry:learned.values()) {
            String code=nine?t9(entry.spelling):entry.spelling;
            if(code.equals(search)&&(!mixed||compatible(key,entry.spelling)))result.add(new Candidate(entry.text,entry.spelling,raw.length(),30+Math.log1p(entry.count)+entry.lastUsed/1e15));
        }
        result.addAll(lookup(search,nine,false,raw.length()));
        if(result.isEmpty()&&search.length()>=4) {
            Candidate sentence=sentence(search,nine);if(sentence!=null&&(!mixed||compatible(key,sentence.spelling)))result.add(new Candidate(sentence.text,sentence.spelling,raw.length(),sentence.score));
        }
        // Exact matches always precede unfinished-word completions.
        result.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed());
        if(result.size()<LIMIT) {
            List<Candidate> prefix=lookup(search,nine,true,raw.length());
            if(personalized)for(Learned e:learned.values())if((nine?t9(e.spelling):e.spelling).startsWith(search))prefix.add(0,new Candidate(e.text,e.spelling,raw.length(),30+Math.log1p(e.count)));
            result.addAll(prefix);
        }
        for(int n=search.length()-1;n>0&&result.size()<LIMIT*2;n--) {
            int rawCount=0,normalizedCount=0;
            while(rawCount<raw.length()&&normalizedCount<n){char c=raw.charAt(rawCount++);if(c!='\''&&c!=' ')normalizedCount++;}
            result.addAll(lookup(search.substring(0,n),nine,false,rawCount));
        }
        LinkedHashMap<String,Candidate> distinct=new LinkedHashMap<>();
        for(Candidate c:result){if(mixed&&!compatible(key,c.spelling))continue;distinct.putIfAbsent(c.text,c);if(distinct.size()>=LIMIT)break;}
        // The phrase page is bounded; the first-syllable character tail is exhaustive.
        for(Candidate c:firstCharacters(mixed?search:raw,nine))if(!mixed||compatible(key,c.spelling))distinct.putIfAbsent(c.text,c);
        return new ArrayList<>(distinct.values());
    }
    /** Dynamic programming for dictionary words joined in an otherwise missing phrase. */
    private Candidate sentence(String key,boolean nine) {
        Candidate[] best=new Candidate[key.length()+1];best[0]=new Candidate("","",0,0);
        for(int start=0;start<key.length();start++) {
            if(best[start]==null)continue;
            for(int end=start+1;end<=Math.min(key.length(),start+24);end++) {
                List<Candidate> entries=lookup(key.substring(start,end),nine,false,end-start);
                if(entries.isEmpty())continue;Candidate part=entries.get(0);double score=best[start].score+part.score-18;
                if(best[end]==null||score>best[end].score)best[end]=new Candidate(best[start].text+part.text,best[start].spelling+part.spelling,end,score);
            }
        }
        return best[key.length()];
    }
    public void learn(String spelling,String text,long now) {
        spelling=normalize(spelling);
        if(!spelling.matches("[a-z]{1,128}")||text.isEmpty()||text.length()>40)return;
        String id=spelling+"\t"+text;Learned e=learned.get(id);
        if(e==null){e=new Learned(spelling,text,0,now);learned.put(id,e);}e.count=Math.min(1000000,e.count+1);e.lastUsed=now;
        if(learned.size()>5000){Learned oldest=Collections.min(learned.values(),Comparator.comparingLong(v->v.lastUsed));learned.remove(oldest.spelling+"\t"+oldest.text);}
    }
    public void readLearning(InputStream stream) throws IOException {
        learned.clear();try(BufferedReader r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null&&learned.size()<5000){String[] f=line.split("\t");if(f.length!=4)continue;try{if(f[0].matches("[a-z]{1,128}"))learned.put(f[0]+"\t"+f[1],new Learned(f[0],f[1],Integer.parseInt(f[2]),Long.parseLong(f[3])));}catch(NumberFormatException ignored){}}}
    }
    public void writeLearning(OutputStream stream) throws IOException {
        Writer w=new OutputStreamWriter(stream,StandardCharsets.UTF_8);for(Learned e:learned.values())w.write(e.spelling+"\t"+e.text+"\t"+e.count+"\t"+e.lastUsed+"\n");w.flush();
    }
    public void clearLearning(){learned.clear();}
    public int learnedCount(){return learned.size();}
}
