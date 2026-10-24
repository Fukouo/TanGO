package com.tango.nihongo;

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.security.KeyStore;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** All requests go to a loopback HTTPS fixture; no paid model is used. */
public final class AiClientTest {
    static void check(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
    static final AtomicReference<String> mode=new AtomicReference<>("search");
    static int requests=0;
    public static void main(String[] args) throws Exception {
        char[] password="fixture-only".toCharArray();
        KeyStore ks=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(Path.of(args[0]))) { ks.load(in,password); }
        KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(ks,password);
        TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init(ks);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(kmf.getKeyManagers(),tmf.getTrustManagers(),null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
        HttpsServer server=HttpsServer.create(new java.net.InetSocketAddress("localhost",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/v1/",exchange->{
            try {
                requests++;
                check("Bearer fixture-key".equals(exchange.getRequestHeaders().getFirst("Authorization")),"authorization");
                if(mode.get().equals("401")) { exchange.sendResponseHeaders(401,-1);return; }
                if(mode.get().equals("400")) { exchange.sendResponseHeaders(400,-1);return; }
                if(mode.get().equals("redirect")) { exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/v1/redirect-target");exchange.sendResponseHeaders(302,-1);return; }
                String response;
                if(exchange.getRequestURI().getPath().equals("/v1/models")) {
                    check(exchange.getRequestMethod().equals("GET"),"models GET");
                    response="{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"}]}";
                } else {
                    check(exchange.getRequestMethod().equals("POST"),"completion POST");
                    JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    check(request.getString("model").equals("fixture-model"),"selected model");
                    check(!request.getBoolean("stream"),"nonstream request");
                    if(mode.get().equals("effort")) check(request.getString("reasoning_effort").equals("high"),"selected effort reaches HTTPS request");
                    else check(!request.has("reasoning_effort"),"model default omits optional effort");
                    JSONArray messages=request.getJSONArray("messages");check(messages.length()==2,"two-stage stateless messages");
                    JSONObject input=new JSONObject(messages.getJSONObject(1).getString("content"));
                    String content;
                    if(mode.get().equals("delicacy")) {
                        check(input.getString("query").equals("delicacy"),"English input preserved");
                        content="{\"items\":[{\"word\":\"delicacy\",\"reading\":\"デリカシー\",\"meaning\":\"细腻的心思\",\"pos\":\"名词\"}]}";
                    } else if(mode.get().equals("hello")) {
                        check(input.getString("query").equals("hello"),"hello input preserved");
                        content="{\"items\":[{\"word\":\"こんにちは\",\"reading\":\"こんにちは\",\"meaning\":\"你好\",\"pos\":\"感叹词\"},{\"word\":\"ハロー\",\"reading\":\"ハロー\",\"meaning\":\"你好\",\"pos\":\"感叹词\"}]}";
                    } else if(mode.get().equals("mixed")) {
                        check(input.getString("query").equals("ノーデリ"),"mixed kana normalized before network request");
                        content="{\"items\":[{\"word\":\"ノーデリ\",\"reading\":\"ノーデリ\",\"meaning\":\"不懂体谅；欠缺分寸\",\"pos\":\"口语略语\"}]}";
                    } else if(mode.get().equals("search")) {
                        check(input.getString("query").equals("タク"),"kana search query is canonical katakana");
                        content="{\"items\":[{\"word\":\"タクシー\",\"reading\":\"たくしー\",\"meaning\":\"出租车\",\"pos\":\"名词\"},{\"word\":\"宅\",\"reading\":\"たく\",\"meaning\":\"家\",\"pos\":\"名词\"}]}";
                    } else {
                        check(input.getString("word").equals("宅"),"precise detail payload");
                        JSONObject detail=new JSONObject().put("word",mode.get().equals("wrong")?"卓":"宅").put("reading","たく").put("meaning","家；住所").put("pos","名词");
                        JSONArray examples=new JSONArray().put(new JSONObject().put("ja","先生のお宅を訪ねました。").put("zh","拜访了老师家。"));
                        if(!mode.get().equals("missing")) examples.put(new JSONObject().put("ja","田中さんのお宅はどこですか。").put("zh","田中先生的家在哪里？"));
                        detail.put("examples",examples);content=detail.toString();
                        if(mode.get().equals("fenced")) content="```json\n"+content+"\n```";
                        if(mode.get().equals("badjson")) content="not json";
                    }
                    response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",mode.get().equals("truncated")?"length":"stop").put("message",new JSONObject().put("content",content)))).toString();
                    if(mode.get().equals("effort")) response=new JSONObject(response).put("usage",new JSONObject().put("prompt_tokens",25).put("completion_tokens",8).put("total_tokens",33)).toString();
                }
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Throwable error) { error.printStackTrace();exchange.sendResponseHeaders(500,-1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            AiClient client=new AiClient("https://localhost:"+server.getAddress().getPort()+"/v1","fixture-key","fixture-model");
            check(client.models().equals(List.of("a-model","z-model")),"model IDs sorted/deduplicated");
            var candidates=client.search("たく");check(candidates.size()==2 && candidates.get(0).word.equals("宅"),"search response and ranking");
            mode.set("detail");JSONObject detail=client.detail(candidates.get(0));check(detail.getJSONArray("examples").length()==2,"two examples");check(detail.getString("id").equals(candidates.get(0).identity()),"favorite identity");
            mode.set("fenced");check(client.detail(candidates.get(0)).getString("word").equals("宅"),"fence tolerance");
            for(String failure:List.of("wrong","missing","badjson","truncated","401","redirect")) {
                mode.set(failure);boolean rejected=false;try { client.detail(candidates.get(0)); } catch(java.io.IOException e) { rejected=true; }check(rejected,"reject "+failure);
            }
            check(requests==10,"redirect was not followed");
            List<JSONObject> metrics=new ArrayList<>();
            AiClient high=new AiClient("https://localhost:"+server.getAddress().getPort()+"/v1","fixture-key","fixture-model",false,"high",metrics::add);
            mode.set("effort");high.detail(candidates.get(0));
            mode.set("400");boolean rejected=false;try { high.detail(candidates.get(0)); } catch(java.io.IOException e) { rejected=e.getMessage().contains("受支持"); }
            check(rejected && requests==12,"unsupported effort is reported without silent retry");
            check(metrics.size()==2 && metrics.get(0).getLong("total_tokens")==33 && metrics.get(0).getBoolean("complete"),"real HTTPS response usage captured once");
            check(metrics.get(1).getLong("total_tokens")==-1 && !metrics.get(1).getBoolean("complete") && metrics.get(1).getInt("status")==400,"failed HTTPS request stays unknown");
            mode.set("delicacy");int before=requests;var english=client.search("delicacy");
            check(requests==before+1&&english.get(0).word.equals("デリカシー")&&english.get(0).reading.equals("でりかしー")&&english.get(0).meaning.contains("delicacy"),"English misplaced headword repaired with one request");
            mode.set("hello");before=requests;check(client.search("hello").get(0).word.equals("ハロー")&&requests==before+1,"English loanword first without extra request");
            mode.set("mixed");before=requests;var mixed=client.search("ノーでり");check(mixed.get(0).word.equals("ノーデリ")&&mixed.get(0).reading.equals("のーでり")&&requests==before+1,"mixed kana and hiragana reading with one request");
            System.out.println("PASS: "+requests+" local HTTPS calls; model listing, auth, selected model, candidate search, precise details, fenced JSON, invalid/truncated response, 401, redirects, reasoning effort and 400 without fallback");
        } finally { server.stop(0); }
    }
}
