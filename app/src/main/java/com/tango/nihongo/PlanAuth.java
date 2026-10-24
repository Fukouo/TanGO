package com.tango.nihongo;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official browser OAuth only. No ChatGPT passwords, cookies, or private endpoints. */
final class PlanAuth {
    private final Store store;
    PlanAuth(Store store) { this.store=store; }
    private static String trusted(String value) throws Exception {
        URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IOException("授权服务地址校验失败");
        return value;
    }
    private static JSONObject http(String url,Map<String,String> form) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(trusted(url)).openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
        try {
            if(form!=null) {
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                try(var out=connection.getOutputStream()) { out.write(PlanProtocol.form(form).getBytes(StandardCharsets.UTF_8)); }
            }
            int status=connection.getResponseCode();
            if(status<200 || status>=300) throw new IOException("ChatGPT 授权请求失败（HTTP "+status+"），请重新登录；若账号未开放此功能，请暂用 API 模式");
            try(var in=connection.getInputStream();var out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[4096];int n;
                while((n=in.read(bytes))!=-1) { out.write(bytes,0,n);if(out.size()>1024*1024) throw new IOException("授权响应过大"); }
                String text=out.toString("UTF-8");return text.trim().isEmpty()?new JSONObject():new JSONObject(text);
            }
        } finally { connection.disconnect(); }
    }
    private static JSONObject discovery() throws Exception {
        JSONObject config=http(PlanProtocol.ISSUER+"/.well-known/openid-configuration",null);
        if(!PlanProtocol.ISSUER.equals(config.optString("issuer"))) throw new IOException("授权签发方不匹配");
        trusted(config.getString("jwks_uri"));return config;
    }
    JSONObject finish(LoopbackLogin login,JSONObject previous) throws Exception {
        Map<String,String> callback=login.await();
        String client=PlanProtocol.issuedClient(callback,login.state,previous==null?null:previous.getString("client_id"));
        String code=callback.get("code");if(code==null || code.isEmpty()) throw new IOException("授权未返回登录代码，请重试");
        Map<String,String> form=new LinkedHashMap<>();form.put("grant_type","authorization_code");form.put("client_id",client);form.put("code",code);form.put("code_verifier",login.verifier);form.put("redirect_uri",login.redirect);form.put("resource",PlanProtocol.RESOURCE);
        JSONObject tokens=http(PlanProtocol.ISSUER+"/api/accounts/oauth/token",form);
        JSONObject claims=PlanProtocol.verifyId(tokens.getString("id_token"),http(discovery().getString("jwks_uri"),null),client,login.nonce,System.currentTimeMillis()/1000);
        if(previous!=null && !previous.getString("subject").equals(claims.getString("sub"))) throw new IOException("返回的账号与选定账号不一致，请使用“添加其他账号”");
        JSONObject account=previous==null?new JSONObject():new JSONObject(previous.toString());
        account.put("client_id",client).put("subject",claims.getString("sub")).put("email",claims.optString("email",claims.optString("name","ChatGPT 账号")));
        update(account,tokens,"");return account;
    }
    private static void update(JSONObject account,JSONObject tokens,String fallbackScope) throws Exception {
        String scope=tokens.optString("scope",fallbackScope);PlanProtocol.requirePlan(scope);
        if(!"Bearer".equalsIgnoreCase(tokens.getString("token_type")) || tokens.getString("access_token").isEmpty()) throw new IOException("登录凭据不完整");
        account.put("access_token",tokens.getString("access_token")).put("scope",scope).put("expires_at",System.currentTimeMillis()+tokens.getLong("expires_in")*1000);
        if(tokens.has("refresh_token")) account.put("refresh_token",tokens.getString("refresh_token"));
        if(tokens.has("id_token") && fallbackScope.isEmpty()) account.put("id_token",tokens.getString("id_token"));
    }
    synchronized String accessToken() throws Exception {
        JSONObject account=store.activeAccount();
        if(account==null || account.optString("access_token").isEmpty()) throw new IOException("请先使用 Continue with ChatGPT 登录并授权");
        PlanProtocol.requirePlan(account.optString("scope"));
        if(account.optLong("expires_at")<System.currentTimeMillis()+60000) {
            if(account.optString("refresh_token").isEmpty()) throw new IOException("登录已到期，请重新登录 ChatGPT");
            Map<String,String> form=new LinkedHashMap<>();form.put("grant_type","refresh_token");form.put("client_id",account.getString("client_id"));form.put("refresh_token",account.getString("refresh_token"));form.put("resource",PlanProtocol.RESOURCE);
            JSONObject refreshed=http(PlanProtocol.ISSUER+"/api/accounts/oauth/token",form);
            update(account,refreshed,account.getString("scope"));store.saveAccount(account,false);
        }
        return account.getString("access_token");
    }
    String signOut() throws Exception {
        JSONObject account=store.activeAccount();if(account==null) return "当前没有登录账号";
        boolean confirmed=account.optString("refresh_token").isEmpty();
        try {
            if(!confirmed) {
                Map<String,String> form=new LinkedHashMap<>();form.put("token",account.getString("refresh_token"));form.put("token_type_hint","refresh_token");form.put("client_id",account.getString("client_id"));
                http(discovery().getString("revocation_endpoint"),form);confirmed=true;
            }
        } catch(Exception ignored) { /* Always complete local sign-out; disclose unconfirmed revocation. */ }
        account.remove("access_token");account.remove("refresh_token");account.remove("id_token");account.remove("scope");account.remove("expires_at");store.saveAccount(account,false);
        return confirmed?"已退出当前账号并清除本机凭据":"已清除本机凭据，未确认远程撤销；请到 ChatGPT 设置断开 TanGO 的登录连接";
    }
}
