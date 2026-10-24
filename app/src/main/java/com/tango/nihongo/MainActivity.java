package com.tango.nihongo;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.content.Context;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xfff8f7fc, INK=0xff353247, GREEN=0xff705aa5, MUTED=0xff716d81, LINE=0xffe8e3f1, SOFT=0xfff0ecf8, PINK=0xffae4d75;
    private State state;
    private LinearLayout root, content, nav;
    private EditText queryField;
    private String draftUrl, draftKey, draftModel;
    private boolean keyReadable=true;

    /** Retained application-only state: a rotation never repeats a paid API request. */
    private static final class State {
        final Store store;
        final ExecutorService worker = Executors.newFixedThreadPool(2);
        final Handler main = new Handler(Looper.getMainLooper());
        final PlanAuth auth;
        LoopbackLogin login;
        Runnable changed;
        volatile String page="search";
        String query="", inputText="", error="", note="", busy="";
        List<LookupRules.Candidate> results = new ArrayList<>();
        JSONObject detail;
        List<String> models = new ArrayList<>();
        LinkedHashMap<String,String> planModels=new LinkedHashMap<>();
        boolean welcome;
        boolean usageSaveFailed;
        boolean apiExpanded;
        boolean historyExpanded;
        String favoriteQuery="";
        int usageDays=1;
        volatile long searchGeneration;
        SearchLadder searchLadder;
        String searchConnection="";
        State(Context c) { store = new Store(c.getApplicationContext());auth=new PlanAuth(store);apiExpanded=!store.planMode()&&!store.apiModel().isEmpty(); }
        void emit() { if(changed != null) changed.run(); }
        interface Job { Object run() throws Exception; }
        interface Done { void accept(Object result) throws Exception; }
        void run(String label, Job job, Done done) {
            if(!busy.isEmpty()) return;
            busy=label; error=""; note=""; emit();
            worker.execute(() -> {
                try {
                    Object result = job.run();
                    main.post(() -> { busy=""; try { done.accept(result); } catch(Exception e) { error=message(e); } emit(); });
                } catch(Exception e) { main.post(() -> { busy=""; error=message(e); emit(); }); }
            });
        }
        static String message(Exception e) {
            if(e instanceof java.net.UnknownHostException) return "找不到服务器，请检查网络和 API 地址";
            if(e instanceof javax.net.ssl.SSLException) return "HTTPS 证书验证失败，请检查服务地址和手机时间";
            if(e instanceof JSONException) return "模型返回的数据格式不完整，请重试或更换模型";
            if(e instanceof IllegalArgumentException || e instanceof java.io.IOException) return e.getMessage()==null ? "请求失败，请重试" : e.getMessage();
            return "操作失败，请检查连接设置后重试";
        }
        void recordUsage(String scope,String model,String kind,JSONObject metrics) {
            try { store.recordUsage(scope,model,kind,metrics); }
            catch(Exception e) { main.post(()->usageSaveFailed=true); }
        }
        void cacheUsage(String kind) {
            try { recordUsage(store.usageScope(),store.model(),kind,UsageData.cacheHit()); }
            catch(Exception e) { main.post(()->usageSaveFailed=true); }
        }
        AiClient client(String kind) throws Exception {
            return client(kind,store.effort(store.planMode()));
        }
        AiClient client(String kind,String temporaryEffort) throws Exception {
            if(temporaryEffort.isEmpty()) throw new IllegalArgumentException("请先选择明确的思考强度");
            String scope=store.usageScope(),model=store.model();AiClient.UsageSink sink=metrics->recordUsage(scope,model,kind,metrics);
            return store.planMode()?new AiClient(PlanProtocol.RESOURCE,auth.accessToken(),model,true,temporaryEffort,sink):new AiClient(store.url(),store.apiKey(),model,false,temporaryEffort,sink);
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state=(State)getLastNonConfigurationInstance();
        if(state==null) state=new State(this);
        if(saved!=null) { draftUrl=saved.getString("url"); draftModel=saved.getString("model"); }
        // The key draft is deliberately kept out of instance-state bundles.
        try { draftKey=state.store.apiKey(); } catch(Exception e) { draftKey=""; keyReadable=false; }
        state.changed=this::render;
        render();
    }
    @Override public Object onRetainNonConfigurationInstance() { return state; }
    @Override protected void onSaveInstanceState(Bundle out) {
        if(queryField!=null) state.inputText=queryField.getText().toString();
        out.putString("url", draftUrl); out.putString("model", draftModel); super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        state.changed=null;
        if(!isChangingConfigurations()) { state.searchGeneration++;if(state.login!=null) state.login.close();state.worker.shutdown(); }
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if(state.detail!=null) { state.detail=null; render(); }
        else if(!state.page.equals("search")) navigate("search");
        else if(!state.query.isEmpty()) home();
        else super.onBackPressed();
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if(color==Color.WHITE) d.setStroke(dp(1),LINE);return d;
    }
    private GradientDrawable gradient(int from,int to,int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{from,to});d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1.05f);if(bold) v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return v;
    }
    private void addText(LinearLayout parent, String value, int size, int color, boolean bold, int bottom) {
        TextView v=text(value,size,color,bold); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(bottom); parent.addView(v,lp);
    }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c=column(); c.setPadding(dp(18),dp(18),dp(18),dp(18)); c.setBackground(shape(Color.WHITE,22));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(1),dp(1),dp(1),dp(12)); parent.addView(c,lp); return c;
    }
    private void heading(String eyebrow,String description) {
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView small=text(eyebrow,10,PINK,true);small.setLetterSpacing(.12f);row.addView(small,new LinearLayout.LayoutParams(0,-2,1));
        if(!description.isEmpty()) { TextView count=text(description,12,MUTED,false);count.setGravity(Gravity.END);row.addView(count); }
        content.addView(row);gap(content,16);
    }
    private TextView tag(String label,boolean pink) {
        TextView view=text(label,10,pink?PINK:GREEN,true);view.setPadding(dp(10),dp(5),dp(10),dp(5));view.setBackground(shape(pink?0xffffedf3:SOFT,20));view.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));return view;
    }
    private Button button(String label, boolean primary, Runnable click) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setIncludeFontPadding(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{primary?0xffe2dcef:0xffa9a2b9,primary?Color.WHITE:GREEN}));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22705aa5),primary?gradient(0xff7d65b8,0xff68529f,16):shape(SOFT,14),shape(Color.WHITE,16)));
        b.setStateListAnimator(null);b.setElevation(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(15),dp(12),dp(15),dp(12));
        b.setOnClickListener(v->click.run()); return b;
    }
    private void gap(LinearLayout parent, int height) { Space s=new Space(this); parent.addView(s,new LinearLayout.LayoutParams(1,dp(height))); }
    private EditText field(String hint, String initial, boolean password) {
        EditText e=new EditText(this); e.setSingleLine(true); e.setTextSize(16); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setHint(hint); e.setText(initial);
        e.setIncludeFontPadding(false);e.setPadding(dp(16),dp(16),dp(16),dp(16));e.setBackground(shape(0xfff7f5fb,14));
        e.setOnFocusChangeListener((v,focused)-> { GradientDrawable d=shape(focused?Color.WHITE:0xfff7f5fb,14);d.setStroke(dp(1),focused?0xffb5a4dc:LINE);e.setBackground(d); });
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        e.setSaveEnabled(false); return e;
    }
    private GradientDrawable purpleBorder(int radius,boolean focused) {
        GradientDrawable background=shape(Color.WHITE,radius);background.setStroke(dp(focused?2:1),focused?GREEN:0xffb5a4dc);return background;
    }
    private EditText searchField(String hint,String initial) {
        EditText input=field(hint,initial,false);input.setBackground(purpleBorder(14,false));
        input.setOnFocusChangeListener((v,focused)->input.setBackground(purpleBorder(14,focused)));return input;
    }
    private void navigate(String page) {
        if(page.equals("search") && state.page.equals("search")) { home();return; }
        if(queryField!=null) state.inputText=queryField.getText().toString();
        state.page=page; state.detail=null; state.error=""; state.note=""; render();
    }
    private void home() {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        queryField=null;state.searchGeneration++;state.searchLadder=null;state.page="search";state.query="";state.inputText="";state.results.clear();state.detail=null;state.error="";state.note="";render();
    }
    private void render() {
        if(queryField!=null) state.inputText=queryField.getText().toString();
        queryField=null;
        root=column();root.setBackground(gradient(0xfffcf9fd,0xfff4f2fa,0));root.setPadding(dp(18),0,dp(18),0);
        if(Build.VERSION.SDK_INT>=30) {
            root.setOnApplyWindowInsetsListener((v,insets)-> {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
                root.setPadding(dp(18),bars.top,dp(18),bars.bottom);if(nav!=null) nav.setVisibility(insets.isVisible(WindowInsets.Type.ime())?View.GONE:View.VISIBLE);return insets;
            });
        } else {
            root.setOnApplyWindowInsetsListener((v,i)-> { root.setPadding(dp(18),i.getSystemWindowInsetTop(),dp(18),i.getSystemWindowInsetBottom());if(nav!=null) nav.setVisibility(i.getSystemWindowInsetBottom()>dp(160)?View.GONE:View.VISIBLE);return i; });
        }
        setContentView(root); root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(3),dp(18),dp(3),dp(20));
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.tango_mascot);mark.setContentDescription("返回首页");mark.setOnClickListener(v->home());LinearLayout.LayoutParams markSize=new LinearLayout.LayoutParams(dp(44),dp(44));markSize.rightMargin=dp(7);header.addView(mark,markSize);
        TextView brand=text("TanGO",23,INK,true);brand.setLetterSpacing(.015f);header.addView(brand);
        brand.setOnClickListener(v->home());brand.setContentDescription("TanGO，返回首页");
        Space name=new Space(this);header.addView(name,new LinearLayout.LayoutParams(0,1,1));
        String currentModel=state.store.model();
        TextView badge=text(currentModel.isEmpty()?"选择模型  ›":currentModel,10,GREEN,true);badge.setSingleLine(true);badge.setMaxWidth(dp(Math.max(100,Math.min(220,getResources().getConfiguration().screenWidthDp-200))));badge.setEllipsize(android.text.TextUtils.TruncateAt.END);badge.setContentDescription(currentModel.isEmpty()?"选择模型":currentModel+"，点击打开连接设置");badge.setPadding(dp(11),dp(7),dp(11),dp(7));badge.setBackground(shape(SOFT,20));badge.setOnClickListener(v->navigate("settings"));header.addView(badge);
        root.addView(header);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        content=column(); content.setPadding(0,0,0,dp(16)); scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(state.detail!=null) detailPage();
        else switch(state.page) { case "favorites" -> favoritesPage(); case "settings" -> settingsPage();case "usage" -> usagePage(); default -> searchPage(); }
        if(!state.busy.isEmpty()) {
            LinearLayout progress=new LinearLayout(this); progress.setGravity(Gravity.CENTER_VERTICAL); progress.setPadding(0,dp(8),0,dp(8));
            ProgressBar spinner=new ProgressBar(this); progress.addView(spinner,new LinearLayout.LayoutParams(dp(22),dp(22)));
            TextView label=text("   "+state.busy,13,GREEN,false); progress.addView(label); root.addView(progress);
            if(state.login!=null) root.addView(button("取消登录",false,()-> { if(state.login!=null) state.login.close(); }));
        }
        if(!state.error.isEmpty()) { TextView e=text(state.error,12,0xffa14d66,false);e.setBackground(shape(0xffffeef3,14));e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setMaxLines(3);e.setEllipsize(android.text.TextUtils.TruncateAt.END);e.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("提示").setMessage(state.error).setPositiveButton("知道了",null).show());root.addView(e); }
        if(!state.note.isEmpty()) { TextView e=text(state.note,11,GREEN,false);e.setPadding(dp(3),dp(6),dp(3),dp(5));e.setMaxLines(2);e.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(e); }
        nav=new LinearLayout(this);nav.setBackground(shape(Color.WHITE,24));nav.setPadding(dp(5),dp(5),dp(5),dp(5));
        String[] pages={"search","favorites","usage","settings"}, labels={"查词","词库","用量","连接"};
        int[] icons={R.drawable.ic_search,R.drawable.ic_bookmark,R.drawable.ic_usage,R.drawable.ic_settings};
        for(int i=0;i<pages.length;i++) {
            final String p=pages[i];boolean active=state.page.equals(p);LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(dp(4),dp(9),dp(4),dp(8));item.setBackground(shape(active?SOFT:Color.TRANSPARENT,18));
            ImageView icon=new ImageView(this);icon.setImageResource(icons[i]);icon.setImageTintList(ColorStateList.valueOf(active?GREEN:MUTED));item.addView(icon,new LinearLayout.LayoutParams(dp(21),dp(21)));
            TextView label=text(labels[i],10,active?GREEN:MUTED,active);LinearLayout.LayoutParams labelSize=new LinearLayout.LayoutParams(-2,-2);labelSize.topMargin=dp(5);item.addView(label,labelSize);
            item.setContentDescription(labels[i]);item.setOnClickListener(v->navigate(p));nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
        LinearLayout.LayoutParams navSize=new LinearLayout.LayoutParams(-1,-2);navSize.topMargin=dp(9);navSize.bottomMargin=dp(10);root.addView(nav,navSize);
        if(state.welcome) {
            state.welcome=false;
            new AlertDialog.Builder(this).setTitle("正在使用你的 ChatGPT 套餐")
                .setMessage("本 App 的合资格 AI 请求将使用你的 ChatGPT 套餐额度，与其他使用该额度的应用共享限额。你可以在 ChatGPT 设置中管理用量和断开连接。")
                .setPositiveButton("知道了",null).show();
        }
    }
    private void searchPage() {
        boolean compact=!state.query.isEmpty();
        LinearLayout searchPanel=card(content);
        LinearLayout queryRow=new LinearLayout(this);queryRow.setGravity(Gravity.CENTER_VERTICAL);
        queryField=searchField("输入日语或中文…",state.inputText);queryField.setTextSize(18);
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        queryField.setOnEditorActionListener((v,id,event)-> { if(id==EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        LinearLayout.LayoutParams inputSize=new LinearLayout.LayoutParams(0,-2,1);queryRow.addView(queryField,inputSize);
        try {
            String value=PlanProtocol.effortLabel(state.store.effort(state.store.planMode())).replaceAll("（.*?）","");
            Button effort=button("思考 · "+value+"  ›",false,()->chooseEffort(state.store.planMode()));effort.setTextSize(11);effort.setMaxWidth(dp(112));effort.setMinHeight(dp(48));effort.setMinimumHeight(dp(48));effort.setPadding(dp(10),dp(8),dp(10),dp(8));effort.setEnabled(state.busy.isEmpty());LinearLayout.LayoutParams effortSize=new LinearLayout.LayoutParams(-2,-2);effortSize.leftMargin=dp(9);queryRow.addView(effort,effortSize);
        } catch(Exception ignored) {}
        searchPanel.addView(queryRow);gap(searchPanel,12);
        Button go=button("查找词语   →",true,this::search);go.setEnabled(state.busy.isEmpty());searchPanel.addView(go);
        gap(content,3);
        if(!compact) searchHistory();
        usageStrip();
        if(!state.results.isEmpty()) {
            addText(content,"「"+state.query+"」的候选  ·  "+state.results.size()+" 个",12,MUTED,true,12);
            for(LookupRules.Candidate c:state.results) candidateCard(c);
        } else if(state.busy.isEmpty() && !state.query.isEmpty() && state.error.isEmpty()) {
            addText(content,"没有找到合适的词条，试着换一种写法。",14,MUTED,false,0);
        }
        if(!state.query.isEmpty() && state.searchLadder!=null && state.busy.isEmpty() && state.error.isEmpty()) {
            SearchLadder ladder=state.searchLadder;
            if(ladder.lastAttempt>=0) {
                gap(content,10);addText(content,"本次强度 · "+PlanProtocol.effortLabel(ladder.efforts.get(ladder.lastAttempt))+(ladder.lastAttempt>0?" · 临时提高 "+ladder.lastAttempt+" 档":""),11,MUTED,false,10);
            }
            if(ladder.canRaise()) content.addView(button("提高一档再查 · "+(ladder.lastAttempt+1)+"/2",false,this::raiseSearch));
            else if(ladder.lastAttempt>0) addText(content,"已达到本次提档上限",11,MUTED,false,0);
        }
    }
    private void searchHistory() {
        List<String> history=state.store.searchHistory();if(history.isEmpty()) return;
        LinearLayout box=card(content);addText(box,"搜索历史",12,MUTED,true,10);
        for(int i=0;i<(state.historyExpanded?history.size():Math.min(3,history.size()));i++) {
            String value=history.get(i);TextView item=text(value+"  ↗",14,INK,false);item.setPadding(dp(4),dp(12),dp(4),dp(12));item.setOnClickListener(v->{queryField.setText(value);search();});box.addView(item);
        }
        if(history.size()>3) {
            TextView toggle=text(state.historyExpanded?"收起  ⌃":"展开全部（"+history.size()+"）  ›",14,GREEN,false);
            toggle.setPadding(dp(4),dp(12),dp(4),dp(12));toggle.setOnClickListener(v->{state.historyExpanded=!state.historyExpanded;render();});box.addView(toggle);
        }
    }
    private void search() {
        if(!state.busy.isEmpty()) return;
        String query=queryField.getText().toString().trim();
        if(query.isEmpty()) { queryField.setError("请输入日语或中文"); return; }
        if(query.length()>150) { queryField.setError("请输入150字以内的词语或语法"); return; }
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(queryField.getWindowToken(),0);
        try {
            String base=state.store.effort(state.store.planMode());
            if(base.isEmpty()) { chooseEffort(state.store.planMode());return; }
            state.searchLadder=new SearchLadder(state.store.model(),state.store.planMode(),base);
            state.searchConnection=state.store.cacheScope();
        } catch(Exception e) { state.error=State.message(e);render();return; }
        state.store.rememberSearch(query);
        state.query=query;state.results=new ArrayList<>();state.searchGeneration++;
        runSearch(0);
    }
    private void raiseSearch() {
        if(!state.busy.isEmpty() || state.searchLadder==null || !state.searchLadder.canRaise()) return;
        if(queryField!=null && !queryField.getText().toString().trim().equals(state.query)) { state.error="输入已改变，请点击查找开始新查询";render();return; }
        try { if(!state.searchConnection.equals(state.store.cacheScope())) { state.error="连接、模型或基础强度已改变，请点击查找开始新查询";render();return; } }
        catch(Exception e) { state.error=State.message(e);render();return; }
        runSearch(state.searchLadder.lastAttempt+1);
    }
    private void runSearch(int start) {
        State s=state;
        String query=s.query,connection=s.searchConnection;long generation=s.searchGeneration;SearchLadder ladder=s.searchLadder;
        s.run("正在查找相关词条…",()-> {
            try {
            return ladder.run(start,(effort,level)-> {
            if(!connection.equals(s.store.cacheScope())) throw new IllegalArgumentException("连接设置已改变，请重新查询");
            String id="search-v3|"+s.store.cacheScope(effort)+"|"+LookupRules.normalize(query);
            String cached=s.store.cached(id);
            if(cached!=null) {
                s.cacheUsage("查词");
                JSONArray a=new JSONArray(cached); List<LookupRules.Candidate> list=new ArrayList<>();
                for(int i=0;i<a.length();i++) list.add(candidate(a.getJSONObject(i)));
                return LookupRules.rank(query,list);
            }
            List<LookupRules.Candidate> list=s.client(level==0?"查词":"查词 · 提档"+level,effort).search(query);
            JSONArray a=new JSONArray(); for(LookupRules.Candidate c:list) a.put(AiClient.candidateJson(c));
            s.store.cache(id,a.toString()); return list;
            },()->s.searchGeneration==generation && s.page.equals("search"),level->s.main.post(()-> {
                if(s.searchGeneration==generation && !s.busy.isEmpty()) { s.busy=level==0?"正在查找相关词条…":"临时提高 "+level+" 档 · "+PlanProtocol.effortLabel(ladder.efforts.get(level))+"…";s.emit(); }
            }));
            } catch(Exception e) {
                if(s.searchGeneration!=generation || !s.page.equals("search")) return new SearchLadder.Outcome(Collections.emptyList(),true);
                throw e;
            }
        },result->{ SearchLadder.Outcome outcome=(SearchLadder.Outcome)result;if(s.searchGeneration==generation&&!outcome.cancelled) s.results=outcome.items; });
    }
    private static LookupRules.Candidate candidate(JSONObject o) { return new LookupRules.Candidate(o.optString("word"),o.optString("reading"),o.optString("meaning"),o.optString("pos")); }
    private void candidateCard(LookupRules.Candidate c) {
        LinearLayout card=entryCard(c.word,c.reading,c.meaning,c.pos,c.exact(state.query)?"完全匹配":"相关词条",c.exact(state.query));
        card.setContentDescription(c.word+"，"+c.meaning+"，点击查看详情"); card.setOnClickListener(v->loadDetail(c));
    }
    private LinearLayout entryCard(String word,String reading,String meaning,String pos,String badge,boolean exact) {
        LinearLayout card=card(content);card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x17705aa5),shape(Color.WHITE,22),shape(Color.WHITE,22)));LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView kana=text(reading,12,MUTED,false);row.addView(kana,new LinearLayout.LayoutParams(0,-2,1));row.addView(tag(badge,exact));card.addView(row);gap(card,9);
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);TextView term=text(word,26,INK,true);title.addView(term,new LinearLayout.LayoutParams(0,-2,1));title.addView(text("›",25,GREEN,false));card.addView(title);gap(card,9);
        addText(card,meaning,14,INK,false,11);card.addView(tag(pos,false));return card;
    }
    private void loadDetail(LookupRules.Candidate c) {
        if(!state.busy.isEmpty()) return;
        State s=state; String origin=s.page;long generation=s.searchGeneration;
        s.run("正在整理「"+c.word+"」的精准释义…",()-> {
            String id="detail-v2|"+s.store.cacheScope()+"|"+c.identity();
            String cached=s.store.cached(id); if(cached!=null) { s.cacheUsage("详情");return new JSONObject(cached); }
            JSONObject detail=s.client("详情").detail(c); s.store.cache(id,detail.toString()); return detail;
        },result->{ if(s.page.equals(origin)&&s.searchGeneration==generation) s.detail=(JSONObject)result; });
    }
    private void detailPage() {
        JSONObject d=state.detail;
        TextView back=text("‹  返回"+(state.page.equals("favorites")?"收藏夹":"候选列表"),14,GREEN,true); back.setPadding(0,0,0,dp(24)); back.setOnClickListener(v->{state.detail=null;render();}); content.addView(back);
        LinearLayout term=card(content);term.setBackground(gradient(0xffefebfa,0xfffff0f5,24));addText(term,d.optString("reading"),14,MUTED,false,8);addText(term,d.optString("word"),36,INK,true,13);term.addView(tag(d.optString("pos"),false));
        LinearLayout meaning=card(content); addText(meaning,"中文释义",12,MUTED,true,12); addText(meaning,d.optString("meaning"),19,INK,true,0);
        gap(content,10);addText(content,"例句手帖",17,INK,true,14);
        JSONArray examples=d.optJSONArray("examples");
        if(examples!=null) for(int i=0;i<examples.length();i++) {
            JSONObject example=examples.optJSONObject(i); if(example==null) continue;
            LinearLayout card=card(content);card.addView(tag("例句 0"+(i+1),i==1));gap(card,13);addText(card,example.optString("ja"),18,INK,false,10);addText(card,example.optString("zh"),13,MUTED,false,0);
        }
        String id=d.optString("id"); boolean saved=state.store.isFavorite(id);
        content.addView(button(saved?"♥  已收藏 · 点击取消":"♡  收藏到我的词库",!saved,()-> {
            if(saved) state.store.removeFavorite(id); else state.store.favorite(id,d); render();
        }));
    }
    private void favoritesPage() {
        List<JSONObject> favorites=state.store.favorites();
        heading("MY COLLECTION","已收藏 "+favorites.size()+" 个词条");
        EditText filter=searchField("搜索收藏夹内的单词/短语",state.favoriteQuery);content.addView(filter);gap(content,14);
        LinearLayout entries=column();content.addView(entries);
        Runnable update=()-> {
            state.favoriteQuery=filter.getText().toString();entries.removeAllViews();
            List<JSONObject> matches=new ArrayList<>();for(JSONObject d:favorites) if(favoriteScore(d)<Integer.MAX_VALUE) matches.add(d);
            matches.sort(Comparator.comparingInt(this::favoriteScore));
            LinearLayout pageContent=content;content=entries;
            try {
                if(matches.isEmpty()) addText(content,favorites.isEmpty()?"暂无收藏":"没有匹配的收藏",14,MUTED,false,0);
                for(JSONObject d:matches) {
                    LinearLayout card=card(content);card.setPadding(dp(16),dp(12),dp(16),dp(12));
                    card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x17705aa5),shape(Color.WHITE,16),shape(Color.WHITE,16)));
                    addText(card,d.optString("word"),20,INK,true,5);
                    addText(card,d.optString("meaning"),13,MUTED,false,0);
                    card.setContentDescription(d.optString("word")+"，"+d.optString("meaning")+"，点击查看详情");
                    card.setOnClickListener(v->{state.detail=d;render();});
                }
            } finally { content=pageContent; }
        };
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { update.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });update.run();
    }
    private int favoriteScore(JSONObject d) {
        return LocalSearch.score(state.favoriteQuery,d.optString("word"),d.optString("reading"),d.optString("meaning"));
    }
    private void openUrl(String url) {
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url))); }
        catch(Exception e) { state.error="无法打开浏览器，请先安装可用浏览器";render(); }
    }
    private long todayStart() {
        return LocalSearch.periodStart(Calendar.getInstance(),1);
    }
    private void usageStrip() {
        try {
            JSONObject summary=state.store.usageSummary(state.store.usageScope(),todayStart());
            LinearLayout strip=new LinearLayout(this);strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(4),dp(3),dp(4),dp(18));
            TextView line=text("今日 "+summary.optLong("requests")+" 次请求  ·  "+summaryValue(summary,"total_tokens")+" Token",10,MUTED,false);strip.addView(line,new LinearLayout.LayoutParams(0,-2,1));strip.addView(text("用量  ›",11,GREEN,true));strip.setOnClickListener(v->navigate("usage"));content.addView(strip);
        } catch(Exception ignored) {}
    }
    private String summaryValue(JSONObject summary,String field) {
        return summary.optLong("requests")>0 && summary.optLong(field+"_reported")==0?"未返回":UsageData.display(summary.optLong(field));
    }
    private void usageSummaryCard(String title,JSONObject summary) {
        LinearLayout card=card(content);card.setBackground(purpleBorder(22,false));addText(card,title,13,MUTED,true,12);
        addText(card,summaryValue(summary,"total_tokens")+"  Token",27,INK,true,5);addText(card,"已回报总量",10,MUTED,false,17);
        LinearLayout metrics=new LinearLayout(this);
        String[] labels={"输入","输出","其中思考"},fields={"input_tokens","output_tokens","reasoning_tokens"};
        for(int i=0;i<3;i++) { LinearLayout cell=column();addText(cell,labels[i],10,MUTED,false,6);addText(cell,summaryValue(summary,fields[i]),16,GREEN,true,0);metrics.addView(cell,new LinearLayout.LayoutParams(0,-2,1)); }
        card.addView(metrics);gap(card,16);
        addText(card,"请求 "+summary.optLong("requests")+" 次  ·  本地缓存 "+summary.optLong("cache_hits")+" 次\n服务端缓存输入 "+summaryValue(summary,"cached_tokens"),11,MUTED,false,8);
        addText(card,"未完成请求 "+summary.optLong("failures")+" 次 · 未返回合计用量 "+summary.optLong("unknown")+" 次",11,MUTED,false,0);
    }
    private void usagePage() {
        heading("USAGE NOTES","");
        String[] periods={"今天","近三天","近一周","近一个月"};int[] days={1,3,7,30};
        Spinner period=new Spinner(this);period.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,periods));
        for(int i=0;i<days.length;i++) if(state.usageDays==days[i]) period.setSelection(i);
        period.setContentDescription("用量时间范围");period.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout periodBox=new LinearLayout(this);periodBox.setGravity(Gravity.CENTER_VERTICAL);periodBox.setPadding(dp(8),dp(4),dp(14),dp(4));periodBox.setMinimumHeight(dp(56));periodBox.setBackground(purpleBorder(14,false));
        periodBox.addView(period,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("⌄",20,GREEN,true);arrow.setPadding(dp(8),dp(8),dp(8),dp(8));arrow.setOnClickListener(v->period.performClick());periodBox.addView(arrow);periodBox.setOnClickListener(v->period.performClick());content.addView(periodBox);gap(content,14);
        period.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { if(state.usageDays!=days[position]) { state.usageDays=days[position];render(); } }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        try {
            String scope=state.store.usageScope();List<JSONObject> recent=state.store.usageRecent(scope);
            addText(content,"当前"+(state.store.planMode()?" ChatGPT 授权连接":" API 地址与 Key")+" · 所有模型",12,MUTED,false,10);
            if(state.usageSaveFailed) addText(content,"有用量记录保存失败，本页统计可能不完整。",13,0xffa14431,false,12);
            usageSummaryCard(state.usageDays==1?"今天":state.usageDays==3?"近三天":state.usageDays==7?"近一周":"近一个月（30天）",state.store.usageSummary(scope,LocalSearch.periodStart(Calendar.getInstance(),state.usageDays)));
            addText(content,"最近请求 · 最多5条",18,INK,true,12);
            if(recent.isEmpty()) addText(content,"暂无请求记录",13,MUTED,false,14);
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault());
            for(JSONObject event:recent) {
                LinearLayout row=card(content);row.setBackground(purpleBorder(22,false));addText(row,event.optString("kind")+" · "+event.optString("model"),15,INK,true,6);
                addText(row,format.format(new Date(event.optLong("saved"))),11,MUTED,false,8);
                if(!event.optBoolean("network")) addText(row,"命中本地缓存，未发起模型请求。",13,GREEN,false,0);
                else {
                    addText(row,"合计 "+UsageData.display(event.optLong("total_tokens",-1))+" Token",17,INK,true,8);
                    addText(row,"输入 "+UsageData.display(event.optLong("input_tokens",-1))+" · 输出 "+UsageData.display(event.optLong("output_tokens",-1))+"\n其中缓存输入 "+UsageData.display(event.optLong("cached_tokens",-1))+" · 思考输出 "+UsageData.display(event.optLong("reasoning_tokens",-1)),12,MUTED,false,8);
                    addText(row,(event.optBoolean("complete")?"已收到模型完整响应":"请求未完成或失败")+(event.optInt("status",-1)>0?" · HTTP "+event.optInt("status"):""),11,event.optBoolean("complete")?GREEN:0xffa14431,false,0);
                }
            }
        } catch(Exception e) { addText(content,"暂时无法读取本地用量记录，请稍后重试。",13,0xffa14431,false,12); }
    }
    private void chooseEffort(boolean plan) {
        if(!state.busy.isEmpty()) return;
        try {
            String model;
            if(plan) { JSONObject account=state.store.activeAccount();model=account==null?"":account.optString("model"); }
            else model=state.store.apiModel();
            if(model.isEmpty()) { state.error="请先保存并选择模型，再设置思考强度";render();return; }
            List<String> values=PlanProtocol.selectableEfforts(model,plan);String[] labels=new String[values.size()];
            String current=state.store.effort(plan);int selected=-1;
            for(int i=0;i<values.size();i++) { labels[i]=PlanProtocol.effortLabel(values.get(i));if(current.equals(values.get(i))) selected=i; }
            new AlertDialog.Builder(this).setTitle("思考强度（依模型支持）\n"+model)
                .setSingleChoiceItems(labels,selected,(dialog,which)-> {
                    try { state.store.saveEffort(plan,values.get(which));state.note="已保存思考强度，对后续查词和详情请求生效。";state.error="";dialog.dismiss();render(); }
                    catch(Exception e) { state.error=State.message(e);dialog.dismiss();render(); }
                }).setNegativeButton("取消",null).show();
        } catch(Exception e) { state.error=State.message(e);render(); }
    }
    private void beginLogin(JSONObject account) {
        if(!state.busy.isEmpty()) return;
        try {
            State s=state;LoopbackLogin login=new LoopbackLogin();
            String url;
            try { url=login.authorize(s.store.hostId(),account==null?null:new LoopbackLogin.JSONObjectAccount(account.getString("client_id"),account.optString("id_token"))); }
            catch(Exception e) { login.close();throw e; }
            s.login=login;
            s.run("请在浏览器完成 ChatGPT 登录与授权…",()-> {
                try { return s.auth.finish(login,account); }
                finally { s.main.post(()->s.login=null); }
            },result->{
                JSONObject verified=(JSONObject)result;s.store.saveAccount(verified,true);s.planModels.clear();s.note="已授权使用 ChatGPT 套餐，请拉取并选择可用模型。";
                s.welcome=s.store.firstWelcome(verified.getString("client_id"));
            });
            try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url))); }
            catch(Exception e) { login.close();throw new java.io.IOException("无法打开浏览器，请安装浏览器后重新登录"); }
        } catch(Exception e) { state.error=State.message(e);render(); }
    }
    private void choosePlanModel(boolean refresh) {
        if(!state.busy.isEmpty()) return;
        try {
            JSONObject account=state.store.activeAccount();
            if(account==null || account.optString("access_token").isEmpty()) { state.error="请先登录 ChatGPT";render();return; }
            String clientId=account.getString("client_id");
            if(refresh || state.planModels.isEmpty()) {
                State s=state;
                s.run("正在读取 ChatGPT 可用模型…",()-> {
                    AiClient client=new AiClient(PlanProtocol.RESOURCE,s.auth.accessToken(),"",true);client.models();return client.modelLabels;
                },result->{
                    JSONObject active=s.store.activeAccount();
                    if(active==null || !clientId.equals(active.optString("client_id"))) return;
                    @SuppressWarnings("unchecked") LinkedHashMap<String,String> choices=(LinkedHashMap<String,String>)result;
                    s.planModels=choices;
                    if(choices.isEmpty()) { s.error="此账号暂未返回可用模型，请稍后重试";return; }
                    if(s.page.equals("settings") && s.detail==null) showPlanModelDialog(clientId);
                });
            } else showPlanModelDialog(clientId);
        } catch(Exception e) { state.error=State.message(e);render(); }
    }
    private void showPlanModelDialog(String clientId) throws Exception {
        JSONObject account=state.store.activeAccount();
        if(account==null || !clientId.equals(account.optString("client_id"))) return;
        List<String> ids=new ArrayList<>(state.planModels.keySet());String[] labels=new String[ids.size()];int selected=-1;
        for(int i=0;i<ids.size();i++) {
            String id=ids.get(i),name=state.planModels.get(id);labels[i]=id.equals(name)?id:name+"（"+id+"）";
            if(id.equals(account.optString("model"))) selected=i;
        }
        new AlertDialog.Builder(this).setTitle("切换模型")
            .setSingleChoiceItems(labels,selected,(dialog,which)-> {
                try {
                    JSONObject active=state.store.activeAccount();
                    if(active==null || !clientId.equals(active.optString("client_id"))) throw new IllegalArgumentException("账号已切换，请重新选择模型");
                    state.store.savePlanModel(ids.get(which));state.note="已切换为 "+ids.get(which);state.error="";dialog.dismiss();render();
                } catch(Exception e) { state.error=State.message(e);dialog.dismiss();render(); }
            }).setNeutralButton("刷新列表",(dialog,which)->choosePlanModel(true)).setNegativeButton("取消",null).show();
    }
    private void chooseAccount() {
        if(!state.busy.isEmpty()) return;
        try {
            JSONArray accounts=state.store.accounts();JSONObject active=state.store.activeAccount();
            if(accounts.length()==0) { beginLogin(null);return; }
            String[] labels=new String[accounts.length()];int selected=-1;
            for(int i=0;i<accounts.length();i++) {
                JSONObject account=accounts.getJSONObject(i);String email=account.optString("email");
                labels[i]=(email.isEmpty()?"账号 "+(i+1):email)+(account.optString("access_token").isEmpty()?"（已退出）":"");
                if(active!=null && active.optString("client_id").equals(account.optString("client_id"))) selected=i;
            }
            new AlertDialog.Builder(this).setTitle("切换账号").setSingleChoiceItems(labels,selected,(dialog,which)-> {
                try { state.store.selectAccount(accounts.getJSONObject(which).getString("client_id"));state.planModels.clear();state.error="";state.note="已切换账号";dialog.dismiss();render(); }
                catch(Exception e) { state.error=State.message(e);dialog.dismiss();render(); }
            }).setNegativeButton("取消",null).show();
        } catch(Exception e) { state.error=State.message(e);render(); }
    }
    private void accountMenu() {
        if(!state.busy.isEmpty()) return;
        try {
            JSONObject account=state.store.activeAccount();
            List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
            labels.add("切换已保存账号");actions.add(this::chooseAccount);
            labels.add("添加其他账号");actions.add(()->beginLogin(null));
            if(account!=null) { labels.add("重新登录当前账号");actions.add(()->beginLogin(account)); }
            labels.add("管理套餐用量  ↗");actions.add(()->openUrl("https://chatgpt.com/settings/usage"));
            if(account!=null && !account.optString("access_token").isEmpty()) {
                labels.add("退出当前账号");actions.add(()->new AlertDialog.Builder(this).setTitle("退出当前账号？")
                    .setMessage(account.optString("email")).setNegativeButton("取消",null).setPositiveButton("退出",(dialog,which)-> {
                        State s=state;s.run("正在退出并撤销会话…",()->s.auth.signOut(),result->{s.planModels.clear();s.note=(String)result;});
                    }).show());
            }
            new AlertDialog.Builder(this).setTitle("账号管理").setItems(labels.toArray(new String[0]),(dialog,which)->actions.get(which).run()).setNegativeButton("取消",null).show();
        } catch(Exception e) { state.error=State.message(e);render(); }
    }
    private void planSettings() {
        LinearLayout plan=card(content);plan.addView(tag("CHATGPT",false));gap(plan,14);
        try {
            JSONObject account=state.store.activeAccount();boolean loggedIn=account!=null && !account.optString("access_token").isEmpty();
            if(account!=null) {
                addText(plan,account.optString("email"),15,INK,true,7);
                addText(plan,!loggedIn?"已退出登录":state.store.planMode()?"当前使用 ChatGPT 套餐":"当前使用 API",11,GREEN,false,16);
            }
            if(!loggedIn) {
                Button login=button("Continue with ChatGPT",true,()->beginLogin(account));login.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(0xff25232f,15),shape(Color.WHITE,15)));login.setEnabled(state.busy.isEmpty());plan.addView(login);
            } else {
                if(!state.store.planMode()) {
                    Button use=button("使用此账号的 ChatGPT 套餐",false,()->{state.store.planMode(true);state.error="";state.note="已切换为 ChatGPT 套餐";render();});use.setEnabled(state.busy.isEmpty());plan.addView(use);gap(plan,10);
                }
                Button model=button("切换模型 · "+(account.optString("model").isEmpty()?"尚未选择":account.optString("model"))+"  ›",false,()->choosePlanModel(false));
                model.setEnabled(state.busy.isEmpty());plan.addView(model);gap(plan,10);
                Button effort=button("思考强度 · "+PlanProtocol.effortLabel(state.store.effort(true))+"  ›",false,()->chooseEffort(true));effort.setEnabled(state.busy.isEmpty());plan.addView(effort);
            }
            gap(plan,12);TextView manage=text("账号管理  ›",13,GREEN,true);manage.setPadding(dp(4),dp(10),dp(4),dp(10));manage.setEnabled(state.busy.isEmpty());manage.setOnClickListener(v->accountMenu());plan.addView(manage);
        } catch(Exception e) { addText(plan,"账号凭据无法读取，请检查手机解锁状态后重试。",13,0xffa14431,false,8); }
    }
    private void settingsPage() {
        heading("CONNECTION","");
        planSettings();
        gap(content,6);content.addView(button(state.apiExpanded?"收起 API Key 配置  ⌃":"API Key 连接 · 单独计费  ›",false,()->{state.apiExpanded=!state.apiExpanded;render();}));gap(content,14);
        if(!state.apiExpanded) { addText(content,"使用第三方服务或独立 API 时，再展开此项设置。",11,MUTED,false,0);return; }
        addText(content,"API Key 配置",17,INK,true,13);
        LinearLayout settings=card(content);
        try { Button effort=button("已保存 API 模型的思考强度："+PlanProtocol.effortLabel(state.store.effort(false)),false,()->chooseEffort(false));effort.setEnabled(state.busy.isEmpty());settings.addView(effort);gap(settings,8);addText(settings,"先保存模型，再选择此接口支持的明确强度。",12,MUTED,false,14); }
        catch(Exception ignored) {}
        addText(settings,"API 基础地址",12,MUTED,true,8);
        EditText url=field("https://api.openai.com/v1",draftUrl==null?state.store.url():draftUrl,false); url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); settings.addView(url); gap(settings,18);
        addText(settings,"API Key",12,MUTED,true,8); EditText key=field("输入你的 API Key",draftKey==null?"":draftKey,true); settings.addView(key);
        CheckBox show=new CheckBox(this); show.setText("显示 Key"); show.setTextColor(MUTED); show.setOnCheckedChangeListener((v,checked)-> { key.setTransformationMethod(checked?null:android.text.method.PasswordTransformationMethod.getInstance()); key.setSelection(key.length()); }); settings.addView(show);
        if(!keyReadable) addText(settings,"旧密钥无法解密，请重新输入并保存。",12,0xffa14431,false,8);
        gap(settings,8); addText(settings,"模型名称",12,MUTED,true,8);
        EditText model=field("拉取后选择，或手动填写",draftModel==null?state.store.apiModel():draftModel,false); settings.addView(model); gap(settings,12);
        android.text.TextWatcher watcher=new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { draftUrl=url.getText().toString();draftKey=key.getText().toString();draftModel=model.getText().toString(); }
            public void afterTextChanged(android.text.Editable s) {}
        };
        url.addTextChangedListener(watcher);key.addTextChangedListener(watcher);model.addTextChangedListener(watcher);
        Button fetch=button("拉取可用模型",false,()-> {
            draftUrl=url.getText().toString();draftKey=key.getText().toString();draftModel=model.getText().toString();
            final String u=draftUrl,k=draftKey; State s=state;
            s.run("正在读取模型列表…",()->new AiClient(u,k,"").models(),result->{ @SuppressWarnings("unchecked") List<String> ids=(List<String>)result;s.models=ids;s.note="模型列表已更新，点击下方选择模型。"; });
        }); fetch.setEnabled(state.busy.isEmpty());settings.addView(fetch);
        if(!state.models.isEmpty()) {
            gap(settings,8); settings.addView(button("选择模型（"+state.models.size()+" 个）  ›",false,()->new AlertDialog.Builder(this).setTitle("选择聊天模型").setItems(state.models.toArray(new String[0]),(dialog,which)->{draftModel=state.models.get(which);render();}).setNegativeButton("取消",null).show()));
        }
        gap(settings,16); Button save=button("保存并使用 API（单独计费）",true,()-> {
            try {
                state.store.saveSettings(url.getText().toString(),key.getText().toString(),model.getText().toString());keyReadable=true;
                state.store.planMode(false);
                state.models=new ArrayList<>();state.error="";state.note="已保存。现在可以开始查词。";render();
            } catch(Exception e) { state.error=State.message(e);render(); }
        }); save.setEnabled(state.busy.isEmpty()); settings.addView(save);
        gap(content,8); addText(content,"兼容 OpenAI Chat Completions 协议。地址请包含服务要求的路径（如 /v1），也支持填写完整 /chat/completions 地址。模型列表可能包含非聊天模型，请选择支持文字对话的模型。",12,MUTED,false,14);
        addText(content,"Key 使用 Android Keystore 加密保存在本机，仅随请求发送至你填写的 HTTPS 服务。查词会发送输入文字；详情会发送所选词条。请使用你信任的 API 服务。",12,MUTED,false,14);
        addText(content,"同一服务与模型下，查询缓存30天，最多300条；收藏长期保存在本机。卸载应用会清除本地数据。",12,MUTED,false,0);
    }
}
