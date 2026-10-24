package com.tango.nihongo;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Per-query reasoning ladder. Never changes saved settings or retries exceptions. */
final class SearchLadder {
    final List<String> efforts;
    volatile int lastAttempt=-1;
    interface Attempt { List<LookupRules.Candidate> search(String effort,int level) throws Exception; }
    static final class Outcome {
        final List<LookupRules.Candidate> items;final boolean cancelled;
        Outcome(List<LookupRules.Candidate> items,boolean cancelled) { this.items=items;this.cancelled=cancelled; }
    }
    SearchLadder(String model,boolean official,String base) {
        if(base.isEmpty()) throw new IllegalArgumentException("请先选择明确的思考强度");
        PlanProtocol.effort(base);List<String> allowed=PlanProtocol.allowedEfforts(model,official);
        if(!allowed.contains(base)) throw new IllegalArgumentException("当前模型不支持已保存的思考强度，请重新选择");
        List<String> levels=new ArrayList<>();levels.add(base);
        int start=allowed.indexOf(base);
        for(int i=start+1;i<allowed.size()&&levels.size()<3;i++) if(!allowed.get(i).isEmpty()) levels.add(allowed.get(i));
        efforts=Collections.unmodifiableList(levels);
    }
    boolean canRaise() { return lastAttempt>=0&&lastAttempt+1<efforts.size(); }
    Outcome run(int start,Attempt attempt,BooleanSupplier active,IntConsumer started) throws Exception {
        if(start!=lastAttempt+1 || start>=efforts.size()) throw new IllegalArgumentException("本次查询已达到提档上限");
        for(int i=start;i<efforts.size();i++) {
            if(!active.getAsBoolean()) return new Outcome(Collections.emptyList(),true);
            lastAttempt=i;started.accept(i);
            List<LookupRules.Candidate> items=attempt.search(efforts.get(i),i);
            if(!active.getAsBoolean()) return new Outcome(Collections.emptyList(),true);
            if(!items.isEmpty() || i==efforts.size()-1) return new Outcome(items,false);
        }
        throw new IllegalStateException("查询档位为空");
    }
}
