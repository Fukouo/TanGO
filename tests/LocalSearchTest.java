package com.tango.nihongo;
import java.util.*;
public final class LocalSearchTest {
    static void check(boolean condition,String label) { if(!condition) throw new AssertionError(label); }
    public static void main(String[] args) {
        check(LocalSearch.score("ﾀｸｼｰ","タクシー")==0,"half width and kana normalization");
        check(LocalSearch.score("たく","宅","たく","家")==0,"reading exact");
        check(LocalSearch.score("出租","タクシー","出租车；计程车")==1,"Chinese prefix");
        check(LocalSearch.score("计程","タクシー","出租车；计程车")==2,"Chinese substring");
        check(LocalSearch.score("たくし","タクシー")==1,"Japanese partial");
        check(LocalSearch.score("たぐしー","タクシー")==3,"one character typo");
        check(LocalSearch.score("猫","タクシー","出租车")==Integer.MAX_VALUE,"unrelated short query excluded");
        check(LocalSearch.score("", "居る")==0,"empty query shows all");
        check(LocalSearch.score("ＴａｎＧＯ","tango")==0,"case and fullwidth");
        check(LocalSearch.score("没关系","大丈夫","没关系")<LocalSearch.score("没关系","没关系的表达"),"exact before partial");
        Calendar now=new GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai"));now.clear();now.set(2026,Calendar.MARCH,1,15,20,30);
        Calendar start=(Calendar)now.clone();start.setTimeInMillis(LocalSearch.periodStart(now,3));
        check(start.get(Calendar.MONTH)==Calendar.FEBRUARY&&start.get(Calendar.DAY_OF_MONTH)==27&&start.get(Calendar.HOUR_OF_DAY)==0,"three inclusive local days across month");
        check(now.get(Calendar.HOUR_OF_DAY)==15,"calendar input unchanged");
        start.setTimeInMillis(LocalSearch.periodStart(now,30));check(start.get(Calendar.MONTH)==Calendar.JANUARY&&start.get(Calendar.DAY_OF_MONTH)==31,"thirty days across month");
        Calendar dst=new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));dst.clear();dst.set(2026,Calendar.MARCH,9,12,0,0);
        start=(Calendar)dst.clone();start.setTimeInMillis(LocalSearch.periodStart(dst,3));check(start.get(Calendar.DAY_OF_MONTH)==7&&start.get(Calendar.HOUR_OF_DAY)==0,"daylight savings local day boundary");
        System.out.println("PASS: offline Japanese/Chinese matching, kana and width normalization, typo tolerance, exact priority, inclusive periods and DST");
    }
}
