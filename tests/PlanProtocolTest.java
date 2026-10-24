package com.tango.nihongo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class PlanProtocolTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    interface Action { void run() throws Exception; }
    static void rejects(Action action,String label) throws Exception { boolean failed=false;try { action.run(); } catch(Exception e) { failed=true; }check(failed,label); }
    static String signed(JSONObject claims,KeyPair pair) throws Exception {
        String header=PlanProtocol.b64("{\"alg\":\"RS256\",\"kid\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
        String body=header+"."+PlanProtocol.b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(body.getBytes(StandardCharsets.US_ASCII));
        return body+"."+PlanProtocol.b64(signature.sign());
    }
    static String event(String type,JSONObject fields) throws Exception { fields.put("type",type);return "event: "+type+"\ndata: "+fields+"\n\n"; }
    static String stream(String value) throws Exception { return PlanProtocol.readStream(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
    public static void main(String[] args) throws Exception {
        check(PlanProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").equals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),"RFC 7636 PKCE vector");
        check(PlanProtocol.random().length()==43,"secure random transaction values");
        rejects(()->PlanProtocol.query("state=a&state=b"),"duplicate callback params");
        check(PlanProtocol.issuedClient(Map.of("state","s","client_id","issued"),"s",null).equals("issued"),"issued ID");
        check(PlanProtocol.issuedClient(Map.of("state","s"),"s","issued").equals("issued"),"reauthorization retains ID");
        rejects(()->PlanProtocol.issuedClient(Map.of("state","wrong","client_id","issued"),"s",null),"state mismatch");
        rejects(()->PlanProtocol.issuedClient(Map.of("state","s","client_id","other"),"s","issued"),"client mismatch");
        rejects(()->PlanProtocol.issuedClient(Map.of("state","s","error","access_denied"),"s",null),"denied consent");
        rejects(()->PlanProtocol.requirePlan("openid profile"),"identity alone cannot authorize inference");
        PlanProtocol.requirePlan(PlanProtocol.SCOPES);
        rejects(()->new AiClient("https://attacker.example/v1","fixture","m",true),"plan credentials cannot target custom API hosts");
        for(String effort:PlanProtocol.EFFORTS) {
            JSONObject plan=PlanProtocol.inferenceBody(true,"fixture-model","fixture",new JSONObject().put("query","いる"),effort);
            JSONObject api=PlanProtocol.inferenceBody(false,"fixture-model","fixture",new JSONObject().put("query","いる"),effort);
            check(!plan.getBoolean("store") && plan.getBoolean("stream"),"plan transport requirements preserved");
            if(effort.isEmpty()) check(!plan.has("reasoning") && !api.has("reasoning_effort"),"default means omit parameter");
            else {
                check(plan.getJSONObject("reasoning").getString("effort").equals(effort),"Responses reasoning mapping");
                check(api.getString("reasoning_effort").equals(effort),"Chat Completions reasoning mapping");
            }
            check(!plan.has("reasoning_effort") && !api.has("reasoning"),"no parameter cross-contamination");
        }
        rejects(()->PlanProtocol.effort("ultra"),"undocumented API effort rejected");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
        RSAPublicKey publicKey=(RSAPublicKey)pair.getPublic();
        JSONObject jwks=new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("kid","fixture").put("kty","RSA").put("n",PlanProtocol.b64(publicKey.getModulus().toByteArray())).put("e",PlanProtocol.b64(publicKey.getPublicExponent().toByteArray()))));
        long now=System.currentTimeMillis()/1000;
        JSONObject claims=new JSONObject().put("iss",PlanProtocol.ISSUER).put("aud","issued").put("sub","fixture-user").put("iat",now).put("exp",now+3600).put("nonce","nonce");
        String token=signed(claims,pair);check(PlanProtocol.verifyId(token,jwks,"issued","nonce",now).getString("sub").equals("fixture-user"),"valid RS256 ID token");
        rejects(()->PlanProtocol.verifyId(token,jwks,"other","nonce",now),"wrong audience");
        rejects(()->PlanProtocol.verifyId(token,jwks,"issued","other",now),"wrong nonce");
        rejects(()->PlanProtocol.verifyId(token,jwks,"issued","nonce",now+7200),"expired token");
        var wrongIssuer=new JSONObject(claims.toString()).put("iss","https://attacker.example");
        rejects(()->PlanProtocol.verifyId(signed(wrongIssuer,pair),jwks,"issued","nonce",now),"wrong issuer");
        String[] parts=token.split("\\.");String forged=parts[0]+"."+PlanProtocol.b64(new JSONObject(claims.toString()).put("sub","attacker").toString().getBytes(StandardCharsets.UTF_8))+"."+parts[2];
        rejects(()->PlanProtocol.verifyId(forged,jwks,"issued","nonce",now),"forged signature");
        String delta=event("response.output_text.delta",new JSONObject().put("delta","{\"items\":[]}"));
        String completed=event("response.completed",new JSONObject().put("response",new JSONObject().put("status","completed")));
        check(stream(delta+completed).equals("{\"items\":[]}"),"completed SSE only");
        rejects(()->stream(delta),"interrupted stream cannot be cached as success");
        rejects(()->stream(delta+event("response.incomplete",new JSONObject())),"incomplete event");
        rejects(()->stream(delta+event("response.failed",new JSONObject().put("response",new JSONObject().put("error",new JSONObject().put("code","subscription_sharing_usage_limit_exceeded"))))),"usage failure after output");
        check(PlanProtocol.modelIds(new JSONObject("{\"models\":[{\"slug\":\"z\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"},{\"slug\":\"a\",\"visibility\":\"list\"}]}" )).equals(List.of("z","a")),"account model visibility and ordering");
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(LoopbackLogin login=new LoopbackLogin()) {
            Map<String,String> authorization=PlanProtocol.query(URI.create(login.authorize("urn:uuid:fixture",null)).getRawQuery());
            check(authorization.get("client_id").equals("dynamic_agent_client") && authorization.get("redirect_uri").equals(login.redirect),"dynamic registration and exact redirect");
            check(authorization.get("code_challenge").equals(PlanProtocol.challenge(login.verifier)),"PKCE sent");
            Future<Map<String,String>> future=executor.submit(login::await);
            HttpURLConnection invalid=(HttpURLConnection)new URL(login.redirect+"?state=wrong&code=bad").openConnection();check(invalid.getResponseCode()==400,"listener rejects state mismatch");invalid.disconnect();
            HttpURLConnection valid=(HttpURLConnection)new URL(login.redirect+"?state="+login.state+"&code=fixture-code&client_id=issued").openConnection();check(valid.getResponseCode()==200,"valid loopback callback");valid.disconnect();
            check(future.get(3,TimeUnit.SECONDS).get("code").equals("fixture-code"),"single-use callback received");
        } finally { executor.shutdownNow(); }
        System.out.println("PASS: PKCE, dynamic callback/client binding, scope checks, RS256 signature/issuer/audience/nonce/expiry, SSE terminal/error handling, account model ordering, real loopback listener");
    }
}
