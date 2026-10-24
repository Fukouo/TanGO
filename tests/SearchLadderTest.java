package com.tango.nihongo;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class SearchLadderTest {
    static int checks;
    static void check(boolean ok,String label) { checks++;if(!ok) throw new AssertionError(label); }
    static final List<LookupRules.Candidate> FOUND=List.of(new LookupRules.Candidate("宅","たく","家","名词"));
    public static void main(String[] args) throws Exception {
        SearchLadder one=new SearchLadder("fixture",false,"low");List<String> efforts=new ArrayList<>();
        var outcome=one.run(0,(effort,level)->{efforts.add(effort);return level==0?List.of():FOUND;},()->true,level->{});
        check(efforts.equals(List.of("low","medium"))&&outcome.items==FOUND,"empty initial automatically raises once then stops on results");
        one.run(one.lastAttempt+1,(effort,level)->{efforts.add(effort);return FOUND;},()->true,level->{});
        check(efforts.equals(List.of("low","medium","high"))&&!one.canRaise(),"manual raise shares the two-level budget");
        boolean blocked=false;try {one.run(3,(effort,level)->{throw new AssertionError("fourth request");},()->true,level->{});}catch(IllegalArgumentException e){blocked=true;}
        check(blocked,"no fourth request");
        SearchLadder empty=new SearchLadder("fixture",false,"low");AtomicInteger attempts=new AtomicInteger();
        outcome=empty.run(0,(effort,level)->{attempts.incrementAndGet();return List.of();},()->true,level->{});
        check(attempts.get()==3&&outcome.items.isEmpty()&&!empty.canRaise(),"all empty stops at three total attempts");
        SearchLadder first=new SearchLadder("fixture",false,"low");attempts.set(0);
        first.run(0,(effort,level)->{attempts.incrementAndGet();return FOUND;},()->true,level->{});
        check(attempts.get()==1&&first.canRaise(),"nonempty initial response is not automatically retried");
        SearchLadder max=new SearchLadder("fixture",false,"max");check(max.efforts.equals(List.of("max")),"already at max");
        check(new SearchLadder("fixture",false,"xhigh").efforts.equals(List.of("xhigh","max")),"only one higher level available");
        check(new SearchLadder("gpt-6-luna",true,"none").efforts.equals(List.of("none","low","medium")),"skip unsupported minimal");
        check(new SearchLadder("gpt-6-astra",true,"low").efforts.equals(List.of("low","medium","high")),"supported official ladder");
        boolean missing=false;try {new SearchLadder("fixture",false,"");}catch(IllegalArgumentException e){missing=true;}check(missing,"legacy default cannot start a search");
        check(!PlanProtocol.selectableEfforts("fixture",false).contains(""),"UI choices exclude model default");
        check(!PlanProtocol.selectableEfforts("gpt-6-luna",true).contains("minimal")&&PlanProtocol.selectableEfforts("gpt-6-luna",true).contains("low"),"UI retains model restrictions");
        boolean unsupported=false;try {new SearchLadder("gpt-6-luna",true,"minimal");}catch(IllegalArgumentException e){unsupported=true;}check(unsupported,"invalid saved base rejected before requests");
        SearchLadder errors=new SearchLadder("fixture",false,"low");attempts.set(0);
        boolean thrown=false;try {errors.run(0,(effort,level)->{attempts.incrementAndGet();throw new java.io.IOException("429");},()->true,level->{});}catch(java.io.IOException e){thrown=true;}
        check(thrown&&attempts.get()==1,"exceptions do not trigger an escalation");
        SearchLadder cancel=new SearchLadder("fixture",false,"low");AtomicBoolean active=new AtomicBoolean(true);attempts.set(0);
        outcome=cancel.run(0,(effort,level)->{attempts.incrementAndGet();active.set(false);return List.of();},active::get,level->{});
        check(outcome.cancelled&&attempts.get()==1,"leaving query stops later attempts");
        SearchLadder alreadyCancelled=new SearchLadder("fixture",false,"low");attempts.set(0);
        outcome=alreadyCancelled.run(0,(effort,level)->{attempts.incrementAndGet();return FOUND;},()->false,level->{});
        check(outcome.cancelled&&attempts.get()==0,"cancelled before first attempt");
        SearchLadder independent=new SearchLadder("fixture",false,"low");check(independent.lastAttempt==-1&&independent.efforts.get(0).equals("low"),"new query starts at saved base");
        boolean immutable=false;try {independent.efforts.add("max");}catch(UnsupportedOperationException e){immutable=true;}check(immutable,"ladder cannot be mutated");
        check(!LookupRules.kanaOnly("apple")&&LookupRules.searchQuery("apple").equals("apple"),"Android compatibility path avoids script regex");
        check(LookupRules.searchQuery("ノーでり").equals("ノーデリ"),"1.8.0 mixed kana behavior preserved");
        System.out.println("PASS: "+checks+" search ladder checks; automatic/manual shared cap, no error retry, cancellation, default/max/unsupported efforts and compatibility.");
    }
}
