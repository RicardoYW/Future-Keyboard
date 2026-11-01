package com.liquidglass.ime;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Setup plus a moving host-window scene to verify real cross-window compositing. */
public final class MainActivity extends Activity {
    private LinearLayout panel;
    private TextView status;
    private android.content.SharedPreferences prefs;
    private boolean demo;
    private SceneView scene;
    private final java.util.concurrent.ExecutorService dictionaryIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) { super.onCreate(state); prefs=getSharedPreferences("glass",MODE_PRIVATE); routeIntent();if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack); }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);routeIntent();}
    private void routeIntent(){if(getIntent().getBooleanExtra("demo",false)&&!getIntent().getBooleanExtra("open_settings",false))showDemo();else showSettings();}
    private int dp(float n) { return GlassStyle.dp(this,n); }
    private TextView label(String text,int size,int color) { TextView t=new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(8),0,dp(8)); return t; }
    private String page="home";
    private static final int BG=0xFF0B171E,MUTED=0xFF9BB1BA;
    private void basePage(String title,String subtitle,boolean home){
        demo=false;if(scene!=null)scene.running=false;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(16),dp(22),dp(28));scroll.addView(panel);setContentView(scroll);status=null;
        if(!home){TextView back=label("返回首页",14,GlassStyle.MINT);back.setMinHeight(dp(44));back.setOnClickListener(v->showSettings());panel.addView(back);panel.addView(label(title,24,GlassStyle.INK));if(!subtitle.isEmpty())panel.addView(label(subtitle,13,MUTED));}
    }
    private void showSettings(){
        page="home";basePage("","",true);
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(R.mipmap.ic_launcher);icon.setContentDescription("Future Keyboard 图标");brand.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout name=new LinearLayout(this);name.setOrientation(LinearLayout.VERTICAL);name.setPadding(dp(12),0,0,0);name.addView(label("Future Keyboard",18,GlassStyle.INK));brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=label("隐私输入法",11,GlassStyle.MINT);brand.addView(badge);panel.addView(brand);
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setPadding(dp(20),dp(14),dp(20),dp(16));hero.setBackground(GlassStyle.surface(0xFF12312F,dp(24),0xFF28514A));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(20);panel.addView(hero,hp);
        hero.addView(label("为你的每一次输入，保留边界",11,0xFFA6CCBE));TextView headline=label("永不联网。\n安心输入。",32,GlassStyle.INK);headline.setTypeface(null,Typeface.BOLD);headline.setLineSpacing(0,1.02f);hero.addView(headline);hero.addView(label("文字只在本机处理。\n不上传输入内容，不接入云端服务。",14,0xFFB8D0C9));
        LinearLayout promises=new LinearLayout(this);for(String s:new String[]{"无网络权限","本机处理","无云同步"}){TextView t=label(s,11,GlassStyle.MINT);t.setGravity(Gravity.CENTER);promises.addView(t,new LinearLayout.LayoutParams(0,dp(34),1));}hero.addView(promises);
        button(isCurrentIme()?"体验安心输入":"开始使用 Future Keyboard",()->{if(isCurrentIme())showDemo();else showSetup();});
        TextView setup=label(isCurrentIme()?"已启用 · 当前正在使用":"启用与切换均由 Android 系统管理",11,MUTED);setup.setGravity(Gravity.CENTER);panel.addView(setup);
        section("只属于你的键盘");
        row("隐私承诺","了解不联网、本地存储与数据清除",this::showPrivacy);
        row("键盘与触感",(prefs.getBoolean("nine",true)?"中文九宫格（微信式）":"中文全键盘")+" · 中英文切换 · 按键与选字触感",this::showTyping);
        row("液态玻璃外观","实时背景模糊 · 玻璃底色",this::showAppearance);
        row("本地词组","词组记忆、自定义与清空，都由你决定",this::showWords);
        row("启用与切换","管理系统输入法与选择当前键盘",this::showSetup);
        row("关于 Future Keyboard","作者、公司与离线开源鸣谢",()->startActivity(new Intent(this,AboutActivity.class)));
        TextView foot=label("没有联网开关，因为从不联网。",11,0xFF708B93);foot.setGravity(Gravity.CENTER);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=dp(16);panel.addView(foot,fp);
    }
    private boolean isCurrentIme(){String id=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);return id!=null&&id.startsWith(getPackageName()+"/");}
    private void section(String title){TextView t=label(title,13,MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(18);panel.addView(t,p);}
    private void row(String title,String description,Runnable action){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(2),dp(8),dp(2),dp(8));row.setMinimumHeight(dp(72));row.setBackground(getDrawable(android.R.drawable.list_selector_background));row.setClickable(true);row.setFocusable(true);row.setContentDescription(title+"，"+description);
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);TextView name=label(title,16,GlassStyle.INK);name.setPadding(0,0,0,dp(4));words.addView(name);TextView desc=label(description,12,MUTED);desc.setPadding(0,0,dp(10),0);words.addView(desc);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));TextView enter=label("›",22,0xFF6D9293);row.addView(enter);row.setOnClickListener(v->action.run());panel.addView(row);
        View line=new View(this);line.setBackgroundColor(0xFF1E3038);panel.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void explanation(String title,String body){section(title);panel.addView(label(body,14,0xFFBED0D5));}
    private void showPrivacy(){
        page="privacy";basePage("永不联网，安心输入","这不是一个可以关闭的模式，而是 Future Keyboard 的产品原则。",false);
        explanation("从权限上，就不联网","应用不申请互联网权限，不连接服务器，不接入云输入、广告、分析统计或在线更新服务。输入、词库查询和候选计算均在本机进行。没有联网开关，也无需登录账号。");
        explanation("文字留在你的设备里","输入内容和个人词组不会上传。我们不会读取输入框中的整段历史内容，也不会建立云端输入记录。你确认的文字会交给当前正在使用的应用；那个应用对文字的处理由其自身决定。");
        explanation("只记住你允许记住的词组","开启词组记忆后，会在本机保存你选中的词组、拼音、使用次数与最近使用时间。同一串拼音分次选字完成的组合词组也可记住。不会保存密码字段的输入；应用要求不进行个性化学习时，也不使用或记录个人词条。");
        explanation("存储在哪里，如何删除","个人词组保存在本应用私有目录，不上传、不云同步，且已排除应用备份和设备迁移。可以随时关闭学习，或清空记忆与自定义词组；卸载并删除应用数据后，本地记录随之删除。");
        explanation("完全离线，也能用","内置拼音词库、九宫格、全键盘与本地词组记忆无需网络。后续功能也会坚持本地离线处理，不会转为云端服务。");
        explanation("没有联网跳转","官网和开源项目地址仅作文字说明，没有浏览器跳转或网页加载。开源许可随应用提供，断网也能查看。");
        button("管理本地词组",this::showWords);
    }
    private void showTyping(){page="typing";basePage("键盘与触感","选一种顺手的布局，保留自己的输入习惯。",false);section("输入方式");toggle("中文使用九宫格","nine",true);panel.addView(label("关闭后使用全键盘；中英文可从键盘地球键切换。布局选择只保存在本机。",13,MUTED));section("离线词语联想");toggle("中英文词语联想","suggestions",true);panel.addView(label("中文词组补全与后续词推荐，英文单词补全与常用搭配推荐。全部在本机完成，不联网、不生成整句，也不读取聊天历史。关闭后仍保留中文拼音选字。",13,MUTED));section("触感反馈");toggle("按键与选字触感","haptics",true);panel.addView(label("触感跟随设备与系统设置。",12,MUTED));button("体验当前键盘",this::showDemo);}
    private void showAppearance(){page="appearance";basePage("液态玻璃外观","透过键盘，看见背景的流动。",false);status=label("",12,GlassStyle.MINT);panel.addView(status);refreshStatus();slider("背景模糊","radius",0,120,72," px");slider("玻璃底色","opacity",20,85,42,"% 不透明");explanation("来自真实背景的模糊","需要 Android 12 及以上和设备支持。系统关闭模糊时会自动加深底色。若正在使用的应用在键盘后方绘制纯色，模糊后也会是纯色。效果完全在设备上渲染。");button("体验液态玻璃",this::showDemo);}
    private void showWords(){page="words";basePage("本地词组","越用越顺手，记忆始终留在你的设备里。",false);toggle("记住常用词组（仅保存在本机）","learning",true);explanation("你选择，才记住","开启后保存选中的词条及词频，并提高常用词的排序。同一串拼音分次选择完成的词组也可记住。密码和应用要求的隐私输入场景不学习。");button("添加自定义词组",this::addPhrase);button("清空本机常用词组",()->new android.app.AlertDialog.Builder(this).setTitle("清空常用词组？").setMessage("将删除学到的词频和手动添加的词组。内置离线词库不受影响，删除后无法撤销。").setNegativeButton("取消",null).setPositiveButton("清空",(dialog,which)->dictionaryIo.execute(()->{LearningStore.clear(this);runOnUiThread(()->Toast.makeText(this,"已清空本机常用词组",Toast.LENGTH_SHORT).show());})).show());panel.addView(label("关闭学习会保留已有本地词组，但不会继续使用或新增个人词条；清空才会删除这些记录。",12,MUTED));}
    private void showSetup(){page="setup";basePage("开始安心输入","两个步骤，由 Android 系统完成。",false);button("1  启用 Future Keyboard",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));button("2  切换到 Future Keyboard",()->getSystemService(InputMethodManager.class).showInputMethodPicker());explanation("关于系统的通用提示","Android 会对第三方输入法显示通用提示。Future Keyboard 不申请互联网权限，输入与词库处理仅在本机完成。你可以在这里先阅读隐私说明，再决定是否启用。");button("阅读隐私承诺",this::showPrivacy);button("体验当前键盘",this::showDemo);}
    private void toggle(String title,String key,boolean fallback){Switch control=new Switch(this);control.setText(title);control.setTextSize(15);control.setTextColor(GlassStyle.INK);control.setChecked(prefs.getBoolean(key,fallback));control.setPadding(0,dp(12),0,dp(12));panel.addView(control,new LinearLayout.LayoutParams(-1,dp(60)));control.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean(key,on).apply());}
    private void addPhrase(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);
        EditText phrase=new EditText(this);phrase.setHint("词组，如：液态玻璃");phrase.setSingleLine(true);form.addView(phrase);
        EditText spelling=new EditText(this);spelling.setHint("全拼，如：yetaiboli");spelling.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);spelling.setSingleLine(true);form.addView(spelling);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("添加本机词组").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String word=phrase.getText().toString().trim(),code=PinyinEngine.normalize(spelling.getText().toString().trim());if(word.isEmpty()||word.length()>40||word.matches(".*[\\t\\n\\r].*")||!code.matches("[a-z]{1,128}")){Toast.makeText(this,"请输入词组和完整的字母拼音",Toast.LENGTH_SHORT).show();return;}v.setEnabled(false);dictionaryIo.execute(()->{try{LearningStore.record(this,new PinyinEngine(),code,word);runOnUiThread(()->{dialog.dismiss();Toast.makeText(this,"已保存，下次输入时可用",Toast.LENGTH_SHORT).show();});}catch(java.io.IOException e){runOnUiThread(()->{v.setEnabled(true);Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_SHORT).show();});}});}));dialog.show();
    }
    @Override protected void onDestroy(){dictionaryIo.shutdown();super.onDestroy();}
    private void refreshStatus() { if(status==null)return; boolean enabled=Build.VERSION.SDK_INT>=31 && getSystemService(WindowManager.class).isCrossWindowBlurEnabled(); status.setText(enabled ? "● 系统窗口模糊当前可用" : "● 系统窗口模糊当前不可用，将使用深色底板"); }
    private void button(String text,Runnable action) { Button b=new Button(this); b.setText(text); b.setTextSize(15); b.setAllCaps(false); b.setTextColor(GlassStyle.INK); b.setBackground(GlassStyle.surface(0xFF233740,dp(16),0x336A9997)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54)); lp.topMargin=dp(12); panel.addView(b,lp); b.setOnClickListener(v->action.run()); }
    private void slider(String title,String key,int min,int max,int initial,String suffix) {
        int current=prefs.getInt(key,initial); TextView value=label(title+"  "+current+suffix,14,0xFFB3C8CC); panel.addView(value);
        SeekBar bar=new SeekBar(this); bar.setMax(max-min); bar.setProgress(current-min); panel.addView(bar,new LinearLayout.LayoutParams(-1,dp(44)));
        bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int progress,boolean user) { int n=progress+min; value.setText(title+"  "+n+suffix); if(user)prefs.edit().putInt(key,n).apply(); }
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
        });
    }
    private void showDemo() {
        page="demo";demo=true; FrameLayout root=new FrameLayout(this); scene=new SceneView(); root.addView(scene,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24),dp(48),dp(24),0); root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        TextView back=label("返回首页",15,GlassStyle.MINT); back.setOnClickListener(v->{ getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(),0); showSettings(); }); content.addView(back);
        content.addView(label("林间",28,GlassStyle.INK));
        content.addView(label("慢下来，感受光的流动。",17,0xFFD0E1E5));
        TextView msg=label("到家了吗？\n\n刚到，路上有点堵。\n\n我也刚到，准备休息了。",18,GlassStyle.INK); content.addView(msg);
        TextView hint=label("背景正在移动\n观察键盘下方色彩是否跟随变化",13,0xFFA3C8CC); content.addView(hint);
        EditText input=new EditText(this); input.setSingleLine(true); input.setTextColor(GlassStyle.INK); input.setHintTextColor(0xFFA8BEC7); input.setHint("输入 nihao，试试候选词"); input.setTextSize(17); input.setPadding(dp(16),dp(8),dp(16),dp(8)); input.setBackground(GlassStyle.surface(0x9031454C,dp(18),0x446B8F93));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        content.addView(input,new LinearLayout.LayoutParams(-1,dp(52)));
        setContentView(root); input.requestFocus(); input.postDelayed(()->getSystemService(InputMethodManager.class).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT),300);
    }
    @Override protected void onResume() { super.onResume(); if(scene!=null){scene.running=true;scene.invalidate();} refreshStatus(); }
    private void navigateBack(){if(!page.equals("home"))showSettings();else finish();}
    @Override public void onBackPressed(){navigateBack();}
    @Override protected void onPause() { if(scene!=null)scene.running=false; super.onPause(); }
    private final class SceneView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); boolean running=true;
        final Matrix transform=new Matrix();
        final RadialGradient[] gradients={new RadialGradient(0,0,1,0xFF4AABB1,Color.TRANSPARENT,Shader.TileMode.CLAMP),new RadialGradient(0,0,1,0xFFBF9356,Color.TRANSPARENT,Shader.TileMode.CLAMP),new RadialGradient(0,0,1,0xFF547BC1,Color.TRANSPARENT,Shader.TileMode.CLAMP)};
        SceneView(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_HARDWARE,null);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.drawColor(0xFF10232F);
            float w=getWidth(),h=getHeight(),t=getIntent().getBooleanExtra("motion",true)?SystemClock.uptimeMillis()/2500f:1f;
            for(int i=0;i<3;i++) {
                float x=w*(.5f+.48f*(float)Math.sin(t+i*2.1f)), y=h*(.64f+.24f*(float)Math.cos(t*.8f+i*1.7f));
                transform.setScale(w*.63f,w*.63f);transform.postTranslate(x,y);gradients[i].setLocalMatrix(transform);
                paint.setShader(gradients[i]); canvas.drawRect(0,0,w,h,paint);
            }
            paint.setShader(null); paint.setStrokeWidth(dp(3));
            for(int i=0;i<20;i++){paint.setColor(i%2==0?0x6097DACD:0x70EDB96C);float x=(i*dp(32)+t*dp(25))%(w+dp(80))-dp(40);canvas.drawLine(x,h*.48f,x,h,paint);}
            if(running)postInvalidateDelayed(32);
        }
    }
}
