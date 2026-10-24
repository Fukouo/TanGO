package com.tango.nihongo;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Store extends SQLiteOpenHelper {
    private final SharedPreferences prefs;
    Store(Context context) {
        super(context, "tango.db", null, 3);
        prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cache (id TEXT PRIMARY KEY, body TEXT NOT NULL, saved INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY, body TEXT NOT NULL, saved INTEGER NOT NULL)");
        createUsage(db);
        createHistory(db);
    }
    private void createUsage(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_events (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, model TEXT NOT NULL, kind TEXT NOT NULL, saved INTEGER NOT NULL, network INTEGER NOT NULL, complete INTEGER NOT NULL, status INTEGER NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL, total_tokens INTEGER NOT NULL, cached_tokens INTEGER NOT NULL, reasoning_tokens INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_scope_time ON usage_events(scope,saved)");
    }
    private void createHistory(SQLiteDatabase db) { db.execSQL("CREATE TABLE IF NOT EXISTS search_history (id TEXT PRIMARY KEY, query TEXT NOT NULL, saved INTEGER NOT NULL)"); }
    @Override public void onUpgrade(SQLiteDatabase db, int old, int next) { if(old<2) createUsage(db);if(old<3) createHistory(db); }
    void rememberSearch(String query) {
        ContentValues v=new ContentValues();v.put("id",LookupRules.normalize(query));v.put("query",query);v.put("saved",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("search_history",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().execSQL("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY saved DESC LIMIT 50)");
    }
    List<String> searchHistory() {
        List<String> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT query FROM search_history ORDER BY saved DESC",null)) { while(c.moveToNext()) result.add(c.getString(0)); }return result;
    }
    String usageScope() throws Exception {
        if(planMode()) { JSONObject account=activeAccount();return "plan|"+(account==null?"none":account.getString("client_id")); }
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(apiKey().getBytes(StandardCharsets.UTF_8));
        return "api|"+url()+"|"+Base64.encodeToString(digest,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
    }
    void recordUsage(String scope,String model,String kind,JSONObject metrics) throws Exception {
        ContentValues values=new ContentValues();values.put("scope",scope);values.put("model",model);values.put("kind",kind);values.put("saved",System.currentTimeMillis());
        values.put("network",metrics.getBoolean("network")?1:0);values.put("complete",metrics.getBoolean("complete")?1:0);values.put("status",metrics.getInt("status"));
        for(String key:UsageData.COUNTERS) values.put(key,metrics.getLong(key));
        if(getWritableDatabase().insert("usage_events",null,values)<0) throw new Exception("无法保存用量记录");
    }
    JSONObject usageSummary(String scope,long since) throws Exception {
        String sql="SELECT COALESCE(SUM(network),0), COALESCE(SUM(CASE WHEN network=0 THEN 1 ELSE 0 END),0), COALESCE(SUM(CASE WHEN network=1 AND complete=0 THEN 1 ELSE 0 END),0), COALESCE(SUM(CASE WHEN network=1 AND total_tokens<0 THEN 1 ELSE 0 END),0)";
        for(String key:UsageData.COUNTERS) sql+=",COALESCE(SUM(CASE WHEN "+key+">=0 THEN "+key+" ELSE 0 END),0)";
        for(String key:UsageData.COUNTERS) sql+=",COALESCE(SUM(CASE WHEN network=1 AND "+key+">=0 THEN 1 ELSE 0 END),0)";
        sql+=" FROM usage_events WHERE scope=? AND saved>=?";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{scope,String.valueOf(since)})) {
            c.moveToFirst();JSONObject result=new JSONObject().put("requests",c.getLong(0)).put("cache_hits",c.getLong(1)).put("failures",c.getLong(2)).put("unknown",c.getLong(3));
            for(int i=0;i<UsageData.COUNTERS.length;i++) { result.put(UsageData.COUNTERS[i],c.getLong(4+i));result.put(UsageData.COUNTERS[i]+"_reported",c.getLong(4+UsageData.COUNTERS.length+i)); }return result;
        }
    }
    List<JSONObject> usageRecent(String scope) throws Exception {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT model,kind,saved,network,complete,status,input_tokens,output_tokens,total_tokens,cached_tokens,reasoning_tokens FROM usage_events WHERE scope=? ORDER BY id DESC LIMIT 5",new String[]{scope})) {
            while(c.moveToNext()) {
                JSONObject event=new JSONObject().put("model",c.getString(0)).put("kind",c.getString(1)).put("saved",c.getLong(2)).put("network",c.getInt(3)==1).put("complete",c.getInt(4)==1).put("status",c.getInt(5));
                for(int i=0;i<UsageData.COUNTERS.length;i++) event.put(UsageData.COUNTERS[i],c.getLong(6+i));result.add(event);
            }
        }return result;
    }
    String url() { return prefs.getString("url", "https://api.openai.com/v1"); }
    String apiModel() { return prefs.getString("model", ""); }
    String model() {
        if(!planMode()) return apiModel();
        try { JSONObject account=activeAccount();return account==null?"":account.optString("model"); } catch(Exception e) { return ""; }
    }
    boolean planMode() { return prefs.getBoolean("plan_mode",false); }
    boolean firstWelcome(String client) { boolean first=!prefs.getBoolean("welcomed_"+client,false);prefs.edit().putBoolean("welcomed_"+client,true).commit();return first; }
    void planMode(boolean enabled) { prefs.edit().putBoolean("plan_mode",enabled).commit(); }
    synchronized String hostId() throws Exception {
        String host=prefs.getString("host_id","");
        if(host.isEmpty()) { host="urn:uuid:"+UUID.randomUUID();if(!prefs.edit().putString("host_id",host).commit()) throw new Exception("无法保存登录主机标识"); }
        return host;
    }
    private String connectionScope(boolean plan) throws Exception {
        if(!plan) return "api|"+url()+"|"+apiModel();
        JSONObject account=activeAccount();return "plan|"+(account==null?"none":account.getString("client_id"))+"|"+(account==null?"":account.optString("model"));
    }
    private String effortKey(boolean plan) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(connectionScope(plan).getBytes(StandardCharsets.UTF_8));
        return "effort_"+Base64.encodeToString(digest,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
    }
    String effort(boolean plan) throws Exception { return PlanProtocol.effort(prefs.getString(effortKey(plan),"")); }
    void saveEffort(boolean plan,String value) throws Exception {
        if(value.isEmpty()) throw new IllegalArgumentException("请选择明确的思考强度");
        if(!prefs.edit().putString(effortKey(plan),PlanProtocol.effort(value)).commit()) throw new Exception("思考强度保存失败");
    }
    String cacheScope() throws Exception {
        return cacheScope(effort(planMode()));
    }
    String cacheScope(String temporaryEffort) throws Exception { return connectionScope(planMode())+"|reasoning:"+PlanProtocol.effort(temporaryEffort); }
    private javax.crypto.SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("tango_api_key")) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("tango_api_key", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            g.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey("tango_api_key", null);
    }
    private String decrypt(String encrypted) throws Exception {
        if (encrypted.isEmpty()) return "";
        String[] parts = encrypted.split(":");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    private String encrypt(String value) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret());
        return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    String apiKey() throws Exception { return decrypt(prefs.getString("key","")); }
    synchronized JSONArray accounts() throws Exception {
        String value=decrypt(prefs.getString("plan_accounts",""));return value.isEmpty()?new JSONArray():new JSONArray(value);
    }
    synchronized JSONObject activeAccount() throws Exception {
        JSONArray list=accounts();String client=prefs.getString("plan_active","");
        for(int i=0;i<list.length();i++) if(client.equals(list.getJSONObject(i).optString("client_id"))) return list.getJSONObject(i);
        return null;
    }
    synchronized void saveAccount(JSONObject account,boolean activate) throws Exception {
        JSONArray list=accounts();int found=-1;
        for(int i=0;i<list.length();i++) if(account.getString("client_id").equals(list.getJSONObject(i).getString("client_id"))) { found=i;break; }
        if(found>=0) list.put(found,account);else list.put(account);
        SharedPreferences.Editor editor=prefs.edit().putString("plan_accounts",encrypt(list.toString()));
        if(activate) editor.putString("plan_active",account.getString("client_id")).putBoolean("plan_mode",true);
        if(!editor.commit()) throw new Exception("账号保存失败，请重新登录");
    }
    synchronized void selectAccount(String client) throws Exception {
        JSONArray list=accounts();
        for(int i=0;i<list.length();i++) if(client.equals(list.getJSONObject(i).getString("client_id"))) {
            if(!prefs.edit().putString("plan_active",client).putBoolean("plan_mode",true).commit()) throw new Exception("无法选择账号");return;
        }
        throw new Exception("账号不存在，请重新登录");
    }
    void savePlanModel(String model) throws Exception {
        JSONObject account=activeAccount();if(account==null) throw new IllegalArgumentException("请先登录 ChatGPT");
        if(model.trim().isEmpty()) throw new IllegalArgumentException("请选择一个可用模型");account.put("model",model.trim());saveAccount(account,false);
    }
    void saveSettings(String url, String key, String model) throws Exception {
        url = LookupRules.baseUrl(url);
        if (key.trim().isEmpty() || model.trim().isEmpty()) throw new IllegalArgumentException("请填写 API Key 和模型名称");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secret());
        String encrypted = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(key.trim().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("url", url).putString("model", model.trim()).putString("key", encrypted).commit()) throw new Exception("设置保存失败，请重试");
    }
    String cached(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM cache WHERE id=? AND saved>?", new String[]{id, String.valueOf(System.currentTimeMillis() - 30L*24*3600*1000)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
    void cache(String id, String body) {
        put("cache", id, body);
        getWritableDatabase().execSQL("DELETE FROM cache WHERE id NOT IN (SELECT id FROM cache ORDER BY saved DESC LIMIT 300)");
    }
    private void put(String table, String id, String body) {
        ContentValues v = new ContentValues(); v.put("id", id); v.put("body", body); v.put("saved", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    boolean isFavorite(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM favorites WHERE id=?", new String[]{id})) { return c.moveToFirst(); }
    }
    void favorite(String id, JSONObject detail) { put("favorites", id, detail.toString()); }
    void removeFavorite(String id) { getWritableDatabase().delete("favorites", "id=?", new String[]{id}); }
    List<JSONObject> favorites() {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM favorites ORDER BY saved DESC", null)) {
            while(c.moveToNext()) try { result.add(new JSONObject(c.getString(0))); } catch (JSONException ignored) {}
        }
        return result;
    }
}
