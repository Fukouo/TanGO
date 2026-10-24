package com.tango.nihongo;

import java.net.URI;
import java.text.Normalizer;
import java.util.*;

/** Pure Java rules shared by the app and the JVM checks. */
public final class LookupRules {
    private LookupRules() {}
    public static String normalize(String value) {
        String text = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) result.append(c >= '\u30a1' && c <= '\u30f6' ? (char)(c - 0x60) : c);
        return result.toString();
    }
    public static String baseUrl(String input) {
        String url = input.trim().replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - 17);
        else if (url.endsWith("/models")) url = url.substring(0, url.length() - 7);
        URI uri;
        try { uri = URI.create(url); } catch (Exception e) { throw new IllegalArgumentException("API 地址格式不正确"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("请填写 HTTPS API 基础地址，例如 https://api.openai.com/v1");
        return url;
    }
    static boolean kanaOnly(String value) {
        boolean letter=false;
        for(int offset=0;offset<value.length();) {
            int c=value.codePointAt(offset);offset+=Character.charCount(c);
            if(c>=0x3041&&c<=0x3096 || c>=0x30a1&&c<=0x30fa || c>=0x31f0&&c<=0x31ff || c>=0xff66&&c<=0xff9d&&c!=0xff70) { letter=true;continue; }
            if(c==0x30fc||c==0x30fb||c==0xff65||c==0xff70||c==0xff9e||c==0xff9f||c==0x3099||c==0x309a||c==0x309d||c==0x309e||c==0x30fd||c==0x30fe||Character.isWhitespace(c)||Character.isSpaceChar(c)) continue;
            return false;
        }
        return letter;
    }
    static String katakana(String value) {
        String text=Normalizer.normalize(value.trim(),Normalizer.Form.NFKC);StringBuilder result=new StringBuilder();
        for(char c:text.toCharArray()) result.append(c>='\u3041'&&c<='\u3096'?(char)(c+0x60):c);return result.toString();
    }
    static String searchQuery(String query) {
        String text=Normalizer.normalize(query.trim(),Normalizer.Form.NFKC);
        return kanaOnly(text)?katakana(text):text;
    }
    public static final class Candidate {
        public final String word, reading, meaning, pos;
        public Candidate(String word, String reading, String meaning, String pos) {
            String term=word.trim(),pronunciation=Normalizer.normalize(reading.trim(),Normalizer.Form.NFKC),definition=meaning.trim();
            // Repair misplaced English headwords using the supplied Japanese reading only.
            if(term.matches("[A-Za-z]+") && kanaOnly(pronunciation)) {
                if(!definition.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) definition+="（"+term+"）";
                term=pronunciation;
            }
            this.word=term;this.reading=kanaOnly(pronunciation)?normalize(pronunciation):pronunciation;this.meaning=definition;this.pos=pos.trim();
        }
        public String identity() { return word + "\u001f" + reading + "\u001f" + meaning + "\u001f" + pos; }
        public boolean exact(String query) { String q = normalize(query); return q.equals(normalize(word)) || q.equals(normalize(reading)); }
    }
    public static List<Candidate> rank(String query, List<Candidate> items) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate c : items) {
            if (!c.word.isEmpty() && !c.word.matches("[A-Za-z]+") && !c.meaning.isEmpty() && !c.pos.isEmpty()) unique.putIfAbsent(c.identity(), c);
        }
        List<Candidate> result = new ArrayList<>(unique.values());
        boolean english=Normalizer.normalize(query.trim(),Normalizer.Form.NFKC).matches("[A-Za-z][A-Za-z\\s'-]*");
        result.sort(Comparator.comparingInt(c -> c.exact(query)?0:english&&kanaOnly(c.word)&&c.word.equals(katakana(c.word))?1:2));
        return new ArrayList<>(result.subList(0, Math.min(5, result.size())));
    }
}
