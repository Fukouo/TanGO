package com.tango.nihongo;
import java.util.*;
public final class LookupRulesTest {
    private static void check(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        check(LookupRules.baseUrl("https://api.openai.com/v1/chat/completions/").equals("https://api.openai.com/v1"),"complete endpoint normalization");
        check(LookupRules.baseUrl("https://example.com/custom/models").equals("https://example.com/custom"),"custom path");
        for(String url:List.of("http://example.com/v1","https://user:secret@example.com/v1","https://example.com/v1?key=x","broken")) {
            boolean rejected=false;try { LookupRules.baseUrl(url); } catch(IllegalArgumentException e) { rejected=true; }check(rejected,"reject unsafe URL");
        }
        check(LookupRules.normalize("ﾀｸ").equals("たく"),"halfwidth kana normalization");
        check(LookupRules.searchQuery("のーでり").equals("ノーデリ"),"hiragana abbreviation canonicalization");
        check(LookupRules.searchQuery("ノーでリ").equals("ノーデリ"),"mixed kana canonicalization");
        check(LookupRules.searchQuery("ﾉｰﾃﾞﾘ").equals("ノーデリ"),"halfwidth voiced kana canonicalization");
        check(LookupRules.searchQuery("hello").equals("hello"),"English stays English");
        check(LookupRules.searchQuery("没关系").equals("没关系"),"Chinese preserved");
        var misplaced=new LookupRules.Candidate("delicacy","デリカシー","细腻的心思","名词");
        check(misplaced.word.equals("デリカシー")&&misplaced.reading.equals("でりかしー")&&misplaced.meaning.contains("delicacy"),"repair English headword and preserve English in meaning");
        var loan=new LookupRules.Candidate("ハロー","ハロー","你好","感叹词");
        check(loan.word.equals("ハロー")&&loan.reading.equals("はろー"),"katakana word and hiragana reading");
        check(LookupRules.rank("hello",List.of(new LookupRules.Candidate("こんにちは","こんにちは","你好","感叹词"),loan)).get(0)==loan,"rank returned loanword ahead of English semantic equivalent");
        check(!misplaced.exact("delicacy"),"English placeholder no longer false exact Japanese match");
        check(LookupRules.rank("hello",List.of(new LookupRules.Candidate("hello","hello","你好","感叹词"))).isEmpty(),"exclude unrepairable English placeholder");
        var similar=new LookupRules.Candidate("タクシー","たくしー","出租车","名词");
        var exact=new LookupRules.Candidate("宅","たく","家","名词");
        var exact2=new LookupRules.Candidate("卓","たく","桌子","名词");
        var items=new ArrayList<>(List.of(similar,exact,exact2,exact));
        var ranked=LookupRules.rank("たく",items);
        check(ranked.size()==3 && ranked.get(0)==exact && ranked.get(1)==exact2 && ranked.get(2)==similar,"exact first and dedup");
        for(int i=0;i<10;i++) items.add(new LookupRules.Candidate("語"+i,"ご","词"+i,"名词"));
        check(LookupRules.rank("たく",items).size()==5,"maximum five");
        var lateExact=new ArrayList<LookupRules.Candidate>();
        for(int i=0;i<8;i++) lateExact.add(new LookupRules.Candidate("語"+i,"ご","词"+i,"名词"));
        lateExact.add(exact);
        check(LookupRules.rank("たく",lateExact).get(0)==exact,"sort before limiting to five");
        var iru=List.of(new LookupRules.Candidate("居る","いる","在","动词"),new LookupRules.Candidate("要る","いる","需要","动词"));
        check(LookupRules.rank("いる",iru).size()==2,"preserve homophones");
        check(LookupRules.rank("中文",List.of(similar,exact)).get(0)==similar,"preserve semantic ordering for Chinese");
        System.out.println("PASS: endpoint validation, kana normalization, exact priority, dedup, five-item cap, homophones, Chinese ordering");
    }
}
