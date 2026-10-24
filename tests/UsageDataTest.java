package com.tango.nihongo;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class UsageDataTest {
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        JSONObject usage=new JSONObject("{\"input_tokens\":100,\"output_tokens\":30,\"total_tokens\":130,\"input_tokens_details\":{\"cached_tokens\":60},\"output_tokens_details\":{\"reasoning_tokens\":20}}");
        JSONObject normalized=UsageData.normalize(usage,true);
        check(normalized.getLong("total_tokens")==130 && normalized.getLong("reasoning_tokens")==20 && normalized.getLong("cached_tokens")==60,"subsets not double counted");
        JSONObject chat=UsageData.normalize(new JSONObject("{\"prompt_tokens\":11,\"completion_tokens\":7,\"completion_tokens_details\":{\"reasoning_tokens\":4}}"),false);
        check(chat.getLong("total_tokens")==18 && chat.getLong("reasoning_tokens")==4 && chat.getLong("cached_tokens")==-1,"chat mapping and missing subset");
        JSONObject missing=UsageData.normalize(null,true);for(String key:UsageData.COUNTERS) check(missing.getLong(key)==-1,"absent usage is unknown");
        check(UsageData.normalize(new JSONObject("{\"input_tokens\":-3,\"output_tokens\":\"7\"}"),true).getLong("total_tokens")==-1,"invalid counters not treated as zero");
        JSONObject hit=UsageData.cacheHit();check(!hit.getBoolean("network") && hit.getLong("total_tokens")==0,"local cache has no model request");
        String completed="data: "+new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed").put("usage",usage).put("output",new JSONArray().put(new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","{}"))))))+"\n\n";
        List<JSONObject> received=new ArrayList<>();
        check(PlanProtocol.readStream(new ByteArrayInputStream(completed.getBytes(StandardCharsets.UTF_8)),received::add).equals("{}"),"SSE response text");
        check(received.size()==1 && received.get(0).getLong("total_tokens")==130,"usage from SSE terminal event");
        String failure="data: "+new JSONObject().put("type","response.failed").put("response",new JSONObject().put("usage",usage).put("error",new JSONObject().put("code","subscription_sharing_usage_unavailable")))+"\n\n";
        received.clear();boolean failed=false;
        try { PlanProtocol.readStream(new ByteArrayInputStream(failure.getBytes(StandardCharsets.UTF_8)),received::add); } catch(IOException e) { failed=true; }
        check(failed && received.size()==1,"reported failed-request usage retained");
        check(!PlanProtocol.allowedEfforts("gpt-6-luna",true).contains("minimal") && PlanProtocol.allowedEfforts("gpt-6-luna",true).contains("none"),"Luna supported efforts");
        check(PlanProtocol.allowedEfforts("gpt-6-luna-2026-10-01",true).contains("low") && !PlanProtocol.allowedEfforts("gpt-6.1-sol",true).contains("none"),"dated model and Sol support");
        boolean rejected=false;try { new AiClient(PlanProtocol.RESOURCE,"fixture","gpt-6-luna",true,"minimal"); } catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"invalid Luna effort rejected before any model request");
        System.out.println("PASS: usage formats, missing/invalid counters, subset accounting, local cache, successful and failed SSE usage, known-model effort restrictions");
    }
}
