package com.tango.nihongo;

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.security.KeyStore;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real loopback HTTPS requests through AiClient; no paid endpoint or user credentials. */
public final class SearchLadderHttpTest {
    static final List<String> sent=Collections.synchronizedList(new ArrayList<>());
    static int emptyBefore,failAt=-1,failStatus,checks,total;
    static String base;
    static final List<JSONObject> usage=new ArrayList<>();
    static void check(boolean ok,String label) { checks++;if(!ok) throw new AssertionError(label); }
    static void reset(int empty,int fail,int status) { sent.clear();usage.clear();emptyBefore=empty;failAt=fail;failStatus=status; }
    static List<LookupRules.Candidate> search(String effort,int level) throws Exception {return new AiClient(base,"fixture-key","fixture-model",false,effort,usage::add).search("apple");}
    public static void main(String[] args) throws Exception {
        KeyStore ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(Path.of(args[0]))) {ks.load(in,"fixture-only".toCharArray());}
        KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(ks,"fixture-only".toCharArray());
        TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init(ks);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(kmf.getKeyManagers(),tmf.getTrustManagers(),null);HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
        HttpsServer server=HttpsServer.create(new java.net.InetSocketAddress("localhost",0),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/v1/chat/completions",exchange->{
            try {
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                check("Bearer fixture-key".equals(exchange.getRequestHeaders().getFirst("Authorization")),"fixture auth");
                check(new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content")).getString("query").equals("apple"),"query remains the same across levels");
                int index=sent.size();sent.add(request.optString("reasoning_effort",""));total++;
                if(index==failAt&&failStatus>0) {exchange.sendResponseHeaders(failStatus,-1);return;}
                JSONArray items=new JSONArray();if(index>=emptyBefore) items.put(new JSONObject().put("word","アップル").put("reading","あっぷる").put("meaning","苹果").put("pos","名词"));
                String content=index==failAt?"invalid json":new JSONObject().put("items",items).toString();
                byte[] response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content",content)))).put("usage",new JSONObject().put("prompt_tokens",10).put("completion_tokens",4).put("total_tokens",14)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            } catch(Throwable error) { error.printStackTrace();exchange.sendResponseHeaders(500,-1); } finally {exchange.close();}
        });server.start();base="https://localhost:"+server.getAddress().getPort()+"/v1";
        try {
            reset(1,-1,0);SearchLadder ladder=new SearchLadder("fixture-model",false,"low");
            var result=ladder.run(0,SearchLadderHttpTest::search,()->true,level->{});
            check(sent.equals(List.of("low","medium"))&&!result.items.isEmpty(),"one-step fallback reaches actual HTTPS payload");
            check(usage.size()==2&&usage.stream().mapToLong(value->value.optLong("total_tokens")).sum()==28,"usage is recorded per attempt");
            reset(99,-1,0);ladder=new SearchLadder("fixture-model",false,"low");result=ladder.run(0,SearchLadderHttpTest::search,()->true,level->{});
            check(sent.equals(List.of("low","medium","high"))&&result.items.isEmpty(),"two raises maximum on actual HTTPS requests");
            reset(0,-1,0);ladder=new SearchLadder("fixture-model",false,"low");ladder.run(0,SearchLadderHttpTest::search,()->true,level->{});
            check(sent.size()==1,"found result stops automatic escalation");
            ladder.run(1,SearchLadderHttpTest::search,()->true,level->{});ladder.run(2,SearchLadderHttpTest::search,()->true,level->{});
            check(sent.equals(List.of("low","medium","high"))&&!ladder.canRaise(),"two manual raises share the same cap");
            for(int status:List.of(400,401,429)) {
                reset(99,0,status);ladder=new SearchLadder("fixture-model",false,"low");boolean stopped=false;
                try {ladder.run(0,SearchLadderHttpTest::search,()->true,level->{});}catch(java.io.IOException e){stopped=true;}
                check(stopped&&sent.equals(List.of("low"))&&usage.size()==1,"HTTP "+status+" stops without escalation");
            }
            reset(99,1,400);ladder=new SearchLadder("fixture-model",false,"low");boolean stopped=false;
            try {ladder.run(0,SearchLadderHttpTest::search,()->true,level->{});}catch(java.io.IOException e){stopped=true;}
            check(stopped&&sent.equals(List.of("low","medium")),"unsupported raised effort does not try a third request");
            reset(0,0,0);ladder=new SearchLadder("fixture-model",false,"low");stopped=false;
            try {ladder.run(0,SearchLadderHttpTest::search,()->true,level->{});}catch(java.io.IOException e){stopped=true;}
            check(stopped&&sent.size()==1,"malformed result is an error, not an empty search");
            reset(99,-1,0);boolean missing=false;try {new SearchLadder("fixture-model",false,"");}catch(IllegalArgumentException e){missing=true;}
            check(missing&&sent.isEmpty(),"legacy default is blocked before any HTTP request");
        } finally {server.stop(0);}
        System.out.println("PASS: "+checks+" checks across "+total+" local HTTPS requests; effort payloads, automatic/manual cap, per-attempt usage and errors without retry.");
    }
}
