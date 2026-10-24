package com.tango.nihongo;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.math.BigInteger;
import java.util.*;

/** Protocol-only helpers; all cryptographic operations use the platform JCA. */
final class PlanProtocol {
    static final String ISSUER="https://auth.openai.com", RESOURCE="https://api.openai.com/v1";
    static final String SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    static final String[] EFFORTS={"","none","minimal","low","medium","high","xhigh","max"};
    static final String[] EFFORT_LABELS={"选择强度","不思考（none）","极低（minimal）","低（low）","中（medium）","高（high）","超高（xhigh）","最高（max）"};
    static String effort(String value) {
        for(String option:EFFORTS) if(option.equals(value)) return option;
        throw new IllegalArgumentException("无效的思考强度");
    }
    static String effortLabel(String value) {
        for(int i=0;i<EFFORTS.length;i++) if(EFFORTS[i].equals(value)) return EFFORT_LABELS[i];
        return EFFORT_LABELS[0];
    }
    static List<String> allowedEfforts(String model,boolean official) {
        List<String> values=new ArrayList<>(Arrays.asList(EFFORTS));
        if(official && model.matches("gpt-6(?:-luna|-sol|-astra|\\.1-sol)(?:-\\d{4}-\\d{2}-\\d{2})?")) {
            values.remove("minimal");
            if(model.startsWith("gpt-6-astra") || model.startsWith("gpt-6.1-sol")) values.remove("none");
        }
        return values;
    }
    static List<String> selectableEfforts(String model,boolean official) {
        List<String> values=allowedEfforts(model,official);values.remove("");return values;
    }
    static JSONObject inferenceBody(boolean plan,String model,String instructions,JSONObject input,String effort) throws Exception {
        effort=effort(effort);
        JSONObject body=new JSONObject().put("model",model);
        if(plan) {
            body.put("instructions",instructions).put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",input.toString())))
                .put("store",false).put("stream",true);
            if(!effort.isEmpty()) body.put("reasoning",new JSONObject().put("effort",effort));
        } else {
            body.put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",instructions))
                .put(new JSONObject().put("role","user").put("content",input.toString()))).put("stream",false);
            if(!effort.isEmpty()) body.put("reasoning_effort",effort);
        }
        return body;
    }
    static String random() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return b64(bytes); }
    static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String challenge(String verifier) throws Exception { return b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
    static String form(Map<String,String> values) throws Exception {
        StringJoiner result=new StringJoiner("&");
        for(var e:values.entrySet()) result.add(URLEncoder.encode(e.getKey(),"UTF-8")+"="+URLEncoder.encode(e.getValue(),"UTF-8"));
        return result.toString();
    }
    static Map<String,String> query(String raw) throws Exception {
        Map<String,String> result=new LinkedHashMap<>();
        for(String field:(raw==null?"":raw).split("&")) {
            if(field.isEmpty()) continue;String[] pair=field.split("=",2);
            String key=URLDecoder.decode(pair[0],"UTF-8");
            if(result.containsKey(key)) throw new IOException("登录回调包含重复参数，请重新登录");
            result.put(key,URLDecoder.decode(pair.length==2?pair[1]:"","UTF-8"));
        }
        return result;
    }
    static String issuedClient(Map<String,String> callback,String state,String previous) throws Exception {
        String actual=callback.get("state");
        if(actual==null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8))) throw new IOException("登录安全校验失败（state）");
        if(callback.containsKey("error")) throw new IOException("已取消授权或账号不支持此登录方式，请返回连接页重试");
        String client=callback.get("client_id");
        if(previous!=null && !previous.isEmpty()) {
            if(client!=null && !previous.equals(client)) throw new IOException("登录返回的客户端与原账号不一致");
            return previous;
        }
        if(client==null || client.isEmpty() || client.equals("dynamic_agent_client")) throw new IOException("未收到有效的注册信息，请重新登录");
        return client;
    }
    static JSONObject verifyId(String jwt,JSONObject jwks,String client,String nonce,long now) throws Exception {
        String[] parts=jwt.split("\\.",-1);
        if(parts.length!=3) throw new IOException("身份凭据格式无效");
        JSONObject header=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));
        if(!"RS256".equals(header.optString("alg")) || header.has("crit")) throw new IOException("身份签名算法不受支持");
        String kid=header.getString("kid");JSONArray keys=jwks.getJSONArray("keys");boolean verified=false;
        for(int i=0;i<keys.length();i++) {
            JSONObject key=keys.getJSONObject(i);
            if(!kid.equals(key.optString("kid")) || !"RSA".equals(key.optString("kty"))) continue;
            if(key.has("use") && !"sig".equals(key.optString("use"))) continue;
            if(key.has("alg") && !"RS256".equals(key.optString("alg"))) continue;
            var spec=new RSAPublicKeySpec(new BigInteger(1,Base64.getUrlDecoder().decode(key.getString("n"))),new BigInteger(1,Base64.getUrlDecoder().decode(key.getString("e"))));
            Signature sig=Signature.getInstance("SHA256withRSA");sig.initVerify(KeyFactory.getInstance("RSA").generatePublic(spec));
            sig.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(sig.verify(Base64.getUrlDecoder().decode(parts[2]))) { verified=true;break; }
        }
        if(!verified) throw new IOException("身份签名验证失败，请重新登录");
        JSONObject claims=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8));
        boolean audience=client.equals(claims.optString("aud"));JSONArray audiences=claims.optJSONArray("aud");
        if(audiences!=null) for(int i=0;i<audiences.length();i++) audience|=client.equals(audiences.getString(i));
        if(!ISSUER.equals(claims.optString("iss")) || !audience || claims.getLong("exp")<=now-5 || claims.getLong("iat")>now+5 || claims.optLong("nbf",0)>now+5 || !nonce.equals(claims.optString("nonce")) || claims.optString("sub").isEmpty()) throw new IOException("身份凭据校验失败，请检查手机时间后重试");
        if((audiences!=null && audiences.length()>1 || claims.has("azp")) && !client.equals(claims.optString("azp"))) throw new IOException("身份凭据的授权客户端不匹配");
        return claims;
    }
    static void requirePlan(String scope) throws IOException {
        Set<String> scopes=new HashSet<>(Arrays.asList(scope.split("\\s+")));
        if(!scopes.contains("chatgpt.tokens.use.direct") || !scopes.contains("resource.invoke")) throw new IOException("登录成功但未获得套餐使用权限，请重新登录并允许使用 ChatGPT 套餐");
    }
    static List<String> modelIds(JSONObject response) throws Exception {
        JSONArray models=response.getJSONArray("models");List<String> result=new ArrayList<>();
        for(int i=0;i<models.length();i++) {
            JSONObject model=models.getJSONObject(i);String slug=model.optString("slug");
            if("list".equals(model.optString("visibility")) && !slug.isEmpty() && !result.contains(slug)) result.add(slug);
        }
        if(result.isEmpty()) throw new IOException("当前 ChatGPT 账号没有返回可用模型");return result;
    }
    static String readStream(InputStream input) throws Exception {
        return readStream(input,null);
    }
    static String readStream(InputStream input,java.util.function.Consumer<JSONObject> onUsage) throws Exception {
        BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
        StringBuilder data=new StringBuilder(),text=new StringBuilder();String line;int size=0;boolean completed=false;
        while((line=reader.readLine())!=null) {
            size+=line.length();if(size>2*1024*1024) throw new IOException("模型响应过大");
            if(line.isEmpty()) {
                if(data.length()>0) {
                    String value=data.toString();data.setLength(0);if("[DONE]".equals(value)) continue;
                    JSONObject event=new JSONObject(value);String type=event.optString("type");
                    if("response.output_text.delta".equals(type)) text.append(event.optString("delta"));
                    if("response.failed".equals(type) || "error".equals(type)) {
                        JSONObject response=event.optJSONObject("response"),error=response==null?event.optJSONObject("error"):response.optJSONObject("error");
                        if(onUsage!=null && response!=null) onUsage.accept(response.optJSONObject("usage"));
                        String code=error==null?event.optString("code"):error.optString("code");
                        if(code.startsWith("subscription_sharing_usage")) throw new IOException("ChatGPT 套餐额度已达上限或暂不可用，请到“管理套餐用量”检查");
                        throw new IOException("ChatGPT 套餐请求失败，请检查授权和模型后重试");
                    }
                    if("response.incomplete".equals(type)) { JSONObject response=event.optJSONObject("response");if(onUsage!=null && response!=null) onUsage.accept(response.optJSONObject("usage"));throw new IOException("模型回答未完成，请重试"); }
                    if("response.completed".equals(type)) {
                        JSONObject response=event.getJSONObject("response");
                        if(onUsage!=null) onUsage.accept(response.optJSONObject("usage"));
                        if(!"completed".equals(response.optString("status"))) throw new IOException("模型回答未完成");
                        // Prefer the completed output, which also handles providers omitting deltas.
                        StringBuilder full=new StringBuilder();JSONArray output=response.optJSONArray("output");
                        if(output!=null) for(int i=0;i<output.length();i++) {
                            JSONArray content=output.getJSONObject(i).optJSONArray("content");if(content==null) continue;
                            for(int j=0;j<content.length();j++) { JSONObject part=content.getJSONObject(j);if("output_text".equals(part.optString("type"))) full.append(part.optString("text")); }
                        }
                        if(full.length()>0) { text.setLength(0);text.append(full); }
                        completed=true;break;
                    }
                }
            } else if(line.startsWith("data:")) { if(data.length()>0) data.append('\n');data.append(line.substring(5).trim()); }
        }
        if(!completed || text.length()==0) throw new IOException("回答连接中断或未完成，请重试");
        return text.toString();
    }
}
