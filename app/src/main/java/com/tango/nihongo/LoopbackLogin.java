package com.tango.nihongo;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A single-use callback listener, bound only to IPv4 loopback. */
final class LoopbackLogin implements AutoCloseable {
    final String state=PlanProtocol.random(), nonce=PlanProtocol.random(), verifier=PlanProtocol.random();
    final ServerSocket server;
    final String redirect;
    LoopbackLogin() throws Exception {
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);
        redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
    }
    String authorize(String host,JSONObjectAccount account) throws Exception {
        Map<String,String> p=new LinkedHashMap<>();p.put("client_id",account==null?"dynamic_agent_client":account.client);
        if(account==null) p.put("agent_name_hint","TanGO");
        else if(!account.idToken.isEmpty()) p.put("id_token_hint",account.idToken);
        p.put("ext_agent_host_id",host);p.put("response_type","code");p.put("redirect_uri",redirect);p.put("scope",PlanProtocol.SCOPES);
        p.put("resource",PlanProtocol.RESOURCE);p.put("state",state);p.put("nonce",nonce);p.put("code_challenge_method","S256");p.put("code_challenge",PlanProtocol.challenge(verifier));
        return PlanProtocol.ISSUER+"/api/accounts/authorize?"+PlanProtocol.form(p);
    }
    static final class JSONObjectAccount {
        final String client,idToken;
        JSONObjectAccount(String client,String idToken) { this.client=client;this.idToken=idToken; }
    }
    Map<String,String> await() throws Exception {
        long deadline=System.currentTimeMillis()+5*60*1000;
        try {
            while(System.currentTimeMillis()<deadline) {
                server.setSoTimeout((int)Math.max(1,deadline-System.currentTimeMillis()));
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String first=boundedLine(reader);if(first==null) continue;
                    String[] parts=first.split(" ");String host="";String line;int total=first.length();
                    while((line=boundedLine(reader))!=null && !line.isEmpty()) { total+=line.length();if(total>16384) throw new IOException("回调请求过大");if(line.toLowerCase(Locale.ROOT).startsWith("host:")) host=line.substring(5).trim(); }
                    if(parts.length!=3 || !"GET".equals(parts[0]) || !host.equals("127.0.0.1:"+server.getLocalPort())) { reply(socket,400);continue; }
                    URI uri;Map<String,String> params;
                    try { uri=URI.create(parts[1]);params=PlanProtocol.query(uri.getRawQuery()); } catch(Exception e) { reply(socket,400);continue; }
                    if(uri.isAbsolute() || !"/auth/callback".equals(uri.getPath())) { reply(socket,404);continue; }
                    if(!state.equals(params.get("state"))) { reply(socket,400);continue; }
                    reply(socket,200);return params;
                } catch(SocketTimeoutException e) { if(System.currentTimeMillis()>=deadline) break; }
            }
            throw new IOException("登录等待超时，请返回连接页重新登录");
        } catch(SocketException e) { throw new IOException("登录已取消，请重试"); }
        finally { close(); }
    }
    private static String boundedLine(BufferedReader in) throws Exception {
        StringBuilder result=new StringBuilder();int c;
        while((c=in.read())!=-1) { if(c=='\n') return result.toString();if(c!='\r') result.append((char)c);if(result.length()>8192) throw new IOException("回调请求过大"); }
        return result.length()==0?null:result.toString();
    }
    private static void reply(Socket s,int status) throws Exception {
        byte[] bytes=(status==200?"<!doctype html><meta charset=utf-8><title>TanGO</title><p>授权回调已接收。请返回 TanGO 查看登录结果。</p>":"Invalid callback").getBytes(StandardCharsets.UTF_8);
        OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
    @Override public void close() { try { server.close(); } catch(IOException ignored) {} }
}
