package com.tango.nihongo;

import org.json.*;

/** Missing counters remain -1 (unknown), never silently converted to zero. */
final class UsageData {
    static final String[] COUNTERS={"input_tokens","output_tokens","total_tokens","cached_tokens","reasoning_tokens"};
    private static long number(JSONObject value,String key) {
        Object v=value==null?null:value.opt(key);
        if(!(v instanceof Number)) return -1;
        double d=((Number)v).doubleValue();long l=((Number)v).longValue();return d>=0 && d==l?l:-1;
    }
    static JSONObject normalize(JSONObject usage,boolean plan) throws JSONException {
        JSONObject result=new JSONObject();long input=number(usage,plan?"input_tokens":"prompt_tokens"),output=number(usage,plan?"output_tokens":"completion_tokens"),total=number(usage,"total_tokens");
        if(total<0 && input>=0 && output>=0) try { total=Math.addExact(input,output); } catch(ArithmeticException ignored) {}
        result.put("input_tokens",input).put("output_tokens",output).put("total_tokens",total);
        JSONObject inputDetails=usage==null?null:usage.optJSONObject(plan?"input_tokens_details":"prompt_tokens_details");
        JSONObject outputDetails=usage==null?null:usage.optJSONObject(plan?"output_tokens_details":"completion_tokens_details");
        result.put("cached_tokens",number(inputDetails,"cached_tokens")).put("reasoning_tokens",number(outputDetails,"reasoning_tokens"));
        return result;
    }
    static JSONObject cacheHit() throws JSONException { JSONObject result=new JSONObject();for(String key:COUNTERS) result.put(key,0);return result.put("network",false).put("complete",true).put("status",0); }
    static String display(long value) { return value<0?"未返回":String.format(java.util.Locale.CHINA,"%,d",value); }
}
