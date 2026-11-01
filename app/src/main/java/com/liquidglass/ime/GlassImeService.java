package com.liquidglass.ime;

import android.content.*;
import android.content.res.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public final class GlassImeService extends InputMethodService {
    private LinearLayout dock,keys,candidateRow;
    private String displayed="",previousWord="";
    private boolean prediction,emojiPanel,englishEligible,expanded;
    private ImageButton expandButton;
    private TextView tallKey,actionKey,bangKey;
    private int visibleCandidates,emojiCategory;
    private String lastEmoji="";
    private final WordSuggestions words=new WordSuggestions();
    private HorizontalScrollView candidateScroll;
    private BlurController blur;
    private SharedPreferences prefs;
    private final StringBuilder composing=new StringBuilder(),acceptedText=new StringBuilder(),acceptedSpelling=new StringBuilder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread=new HandlerThread("PinyinDecoder",android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private Handler worker;
    private PinyinEngine engine;
    private Runnable pendingQuery;
    private boolean chinese=true,symbols,shifted,sensitive,nine,personalized,queryPending,waitingForChoice,destroyed;
    private final ArrayDeque<Runnable> deferred=new ArrayDeque<>();
    private int generation,editorAction;
    private List<PinyinEngine.Candidate> candidates=Collections.emptyList();
    private final List<TextView> chips=new ArrayList<>();
    @Override public void onCreate(){
        setTheme(R.style.ImeTheme);super.onCreate();prefs=getSharedPreferences("glass",MODE_PRIVATE);nine=prefs.getBoolean("nine",true);
        workerThread.start();worker=new Handler(workerThread.getLooper());
        worker.post(()->{
            try(AssetFileDescriptor fd=getAssets().openFd("dictionary.bin");FileInputStream stream=fd.createInputStream()){
                words.loadEnglish(getAssets().open("en-words.txt"),getAssets().open("en-next.tsv"));
                try(AssetFileDescriptor next=getAssets().openFd("zh-next.bin");FileInputStream input=next.createInputStream()){words.mapChinese(input.getChannel(),next.getStartOffset(),next.getLength());}
                engine=new PinyinEngine();engine.map(stream.getChannel(),fd.getStartOffset(),fd.getLength());engine.loadCharacters(getAssets().open("characters.tsv"));LearningStore.load(this,engine);
            }catch(IOException e){android.util.Log.e("GlassIME","Lexicon unavailable",e);}
            main.post(()->{if(!destroyed&&dock!=null)updateCandidates();});
        });
    }
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public View onCreateInputView(){
        if(blur!=null)blur.close();
        dock=new LinearLayout(this);dock.setOrientation(LinearLayout.VERTICAL);dock.setPadding(dp(6),dp(6),dp(6),dp(8));
        if(Build.VERSION.SDK_INT>=30)dock.setOnApplyWindowInsetsListener((v,insets)->{dock.setPadding(dp(6),dp(6),dp(6),dp(8)+insets.getInsets(WindowInsets.Type.navigationBars()).bottom);return insets;});
        dock.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton layouts=icon(R.drawable.ic_keyboard,"选择键盘布局");layouts.setOnClickListener(v->dispatch(()->{haptic(v);showLayouts();}));header.addView(layouts,new LinearLayout.LayoutParams(dp(48),dp(40)));
        ImageButton emoji=icon(R.drawable.ic_smile,"Emoji 表情");emoji.setOnClickListener(v->dispatch(()->{haptic(v);commitRaw();emojiPanel=!emojiPanel;if(emojiPanel)showEmoji();else rebuildKeys();}));header.addView(emoji,new LinearLayout.LayoutParams(dp(48),dp(40)));
        View spacer=new View(this);header.addView(spacer,new LinearLayout.LayoutParams(0,dp(40),1));
        ImageButton hide=icon(R.drawable.ic_down,"收起键盘");hide.setOnClickListener(v->{haptic(v);requestHideSelf(0);});header.addView(hide,new LinearLayout.LayoutParams(dp(48),dp(40)));
        ImageButton settings=icon(R.drawable.ic_gear,"Future Keyboard 设置");settings.setOnClickListener(v->{haptic(v);requestHideSelf(0);Intent i=new Intent(this,MainActivity.class).putExtra("open_settings",true);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);});header.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(40)));dock.addView(header);
        LinearLayout candidateBar=new LinearLayout(this);candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        candidateScroll=new HorizontalScrollView(this);candidateScroll.setHorizontalScrollBarEnabled(false);candidateRow=new LinearLayout(this);candidateRow.setGravity(Gravity.CENTER_VERTICAL);candidateScroll.addView(candidateRow);candidateBar.addView(candidateScroll,new LinearLayout.LayoutParams(0,dp(46),1));
        expandButton=icon(R.drawable.ic_down,"展开候选词");expandButton.setOnClickListener(v->dispatch(()->{haptic(v);expanded=!expanded;if(expanded)showExpandedCandidates();else rebuildKeys();updateExpandButton();}));candidateBar.addView(expandButton,new LinearLayout.LayoutParams(dp(44),dp(46)));dock.addView(candidateBar);
        candidateScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(!queryPending&&x+candidateScroll.getWidth()>=candidateRow.getWidth()-dp(80))appendCandidateStrip();});
        keys=new LinearLayout(this);keys.setOrientation(LinearLayout.VERTICAL);dock.addView(keys);rebuildKeys();updateCandidates();blur=new BlurController(this,getWindow().getWindow(),dock);return dock;
    }
    @Override public void onStartInput(EditorInfo info,boolean restarting){
        super.onStartInput(info,restarting);resetComposition();shifted=false;nine=prefs.getBoolean("nine",true);
        int cls=info.inputType&InputType.TYPE_MASK_CLASS,variation=info.inputType&InputType.TYPE_MASK_VARIATION;
        sensitive=(cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))||(cls==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        personalized=!sensitive&&(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)==0;
        symbols=cls==InputType.TYPE_CLASS_NUMBER||cls==InputType.TYPE_CLASS_PHONE||cls==InputType.TYPE_CLASS_DATETIME;
        chinese=!sensitive&&cls==InputType.TYPE_CLASS_TEXT&&variation!=InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS&&variation!=InputType.TYPE_TEXT_VARIATION_URI;
        englishEligible=!sensitive&&cls==InputType.TYPE_CLASS_TEXT&&variation!=InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS&&variation!=InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS&&variation!=InputType.TYPE_TEXT_VARIATION_URI;
        editorAction=(info.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)!=0?EditorInfo.IME_ACTION_NONE:info.imeOptions&EditorInfo.IME_MASK_ACTION;
        worker.post(()->{if(engine!=null)try{LearningStore.load(this,engine);}catch(IOException e){engine.clearLearning();}});
        if(keys!=null){rebuildKeys();updateCandidates();}
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);boolean savedNine=prefs.getBoolean("nine",true);if(savedNine!=nine){if(composing.length()>0)commitRaw();nine=savedNine;if(keys!=null)rebuildKeys();}if(dock!=null)updateCandidates();if(blur!=null)blur.apply();}
    @Override public void onFinishInput(){InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.finishComposingText();resetComposition();if(dock!=null)updateCandidates();super.onFinishInput();}
    @Override public void onUpdateSelection(int a,int b,int start,int end,int cs,int ce){super.onUpdateSelection(a,b,start,end,cs,ce);if(composing.length()>0&&(start!=ce||end!=ce)){resetComposition();InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.finishComposingText();if(dock!=null)updateCandidates();}}
    @Override public void onDestroy(){destroyed=true;if(blur!=null)blur.close();workerThread.quitSafely();main.removeCallbacksAndMessages(null);super.onDestroy();}
    private void resetComposition(){generation++;previousWord="";displayed="";lastEmoji="";emojiPanel=false;expanded=false;composing.setLength(0);acceptedText.setLength(0);acceptedSpelling.setLength(0);waitingForChoice=false;deferred.clear();}
    private int dp(float n){return GlassStyle.dp(this,n);}
    private void haptic(View view){if(prefs.getBoolean("haptics",true))view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(GlassStyle.INK);t.setGravity(Gravity.CENTER);t.setIncludeFontPadding(false);return t;}
    private ImageButton icon(int resource,String label){ImageButton b=new ImageButton(this);b.setImageResource(resource);b.setImageTintList(ColorStateList.valueOf(GlassStyle.INK));b.setBackgroundColor(Color.TRANSPARENT);b.setContentDescription(label);b.setPadding(dp(9),dp(6),dp(9),dp(6));return b;}
    private boolean t9Active(){return chinese&&nine&&!symbols&&!sensitive;}
    private void dispatch(Runnable action){if(waitingForChoice)deferred.add(action);else action.run();}
    private void rebuildKeys(){
        keys.removeAllViews();emojiPanel=false;expanded=false;updateExpandButton();tallKey=null;actionKey=null;bangKey=null;
        if(t9Active()){buildT9();return;}
        String[] rows=symbols?new String[]{"1234567890","@#$%&*()-","!?/;:'\""}:new String[]{"qwertyuiop","asdfghjkl","zxcvbnm"};
        for(int r=0;r<3;r++){LinearLayout row=row();if(r==1)row.setPadding(dp(16),0,dp(16),0);if(r==2)addKey(row,"",R.drawable.ic_shift,"大写切换",1.3f,false,()->{shifted=!shifted;rebuildKeys();},false);
            for(char c:rows[r].toCharArray()){String value=shifted&&!symbols?String.valueOf(c).toUpperCase(Locale.ROOT):String.valueOf(c);addKey(row,value,0,value,1,false,()->type(value),true);}
            if(r==2)addKey(row,"",R.drawable.ic_backspace,"删除",1.3f,false,this::delete,true);keys.addView(row);}
        LinearLayout bottom=row();addKey(bottom,symbols?"ABC":"123",0,"数字符号切换",1.2f,false,()->{commitRaw();symbols=!symbols;rebuildKeys();},false);
        View globe=addKey(bottom,"",R.drawable.ic_globe,"切换中英文，长按选择输入法",1,false,()->{commitRaw();chinese=!chinese;rebuildKeys();updateCandidates();},false);globe.setOnLongClickListener(v->{getSystemService(InputMethodManager.class).showInputMethodPicker();return true;});
        addKey(bottom,",",0,"逗号",.8f,false,()->punctuation(chinese?"，":","),false);addKey(bottom,chinese?"中文":"English",0,"空格，拼音时选择首个候选词",3.7f,false,this::space,true);
        addKey(bottom,".",0,"句号",.8f,false,()->punctuation(chinese?"。":"."),false);addKey(bottom,"",R.drawable.ic_enter,"回车",1.5f,true,this::enter,false);keys.addView(bottom);
    }
    // ---------- 九宫格 · 微信式布局 ----------
    private int t9Row(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?44:56;}
    private RippleDrawable solidKey(boolean accent){return new RippleDrawable(ColorStateList.valueOf(0x5581D8B8),GlassStyle.surface(accent?GlassStyle.MINT:0x1FFFFFFF,dp(11),accent?0:0x28FFFFFF),null);}
    private RippleDrawable softKey(){return new RippleDrawable(ColorStateList.valueOf(0x5581D8B8),GlassStyle.surface(0x14FFFFFF,dp(10),0x1FFFFFFF),null);}
    private View finishKey(View view,String desc,Runnable action,boolean onDown,Runnable longPress){
        view.setContentDescription(desc);view.setClickable(true);view.setFocusable(true);
        view.setOnClickListener(v->dispatch(()->{haptic(v);action.run();}));
        if(onDown){
            final boolean[] held={false};
            view.setOnTouchListener((v,event)->{switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);v.drawableHotspotChanged(event.getX(),event.getY());haptic(v);held[0]=true;
                    if(longPress!=null)v.postDelayed(()->{if(held[0]){held[0]=false;dispatch(longPress);}},450);
                    dispatch(action);return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:v.setPressed(false);held[0]=false;return true;
                default:return true;}});
        }else if(longPress!=null){
            view.setLongClickable(true);view.setOnLongClickListener(v->{haptic(v);longPress.run();return true;});
        }
        return view;
    }
    private View t9Text(String label,int size,boolean accent,Runnable action,Runnable longPress){
        TextView t=text(label,size);t.setTextColor(accent?0xFF122A24:GlassStyle.INK);t.setBackground(accent?solidKey(true):softKey());
        return finishKey(t,label,action,false,longPress);
    }
    private View t9Letter(String letters,String digit,Runnable action){
        FrameLayout frame=new FrameLayout(this);frame.setBackground(solidKey(false));
        TextView main=text(letters,20);main.setTypeface(Typeface.DEFAULT_BOLD);
        frame.addView(main,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        TextView sup=text(digit,9);sup.setTextColor(0x8CE8F3F1);sup.setGravity(Gravity.TOP|Gravity.END);sup.setPadding(0,dp(3),dp(6),0);
        frame.addView(sup,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        return finishKey(frame,letters+" 数字键 "+digit,action,true,null);
    }
    private View t9Icon(int res,String desc,Runnable action,Runnable longPress){
        View v=icon(res,desc);v.setBackground(softKey());
        return finishKey(v,desc,action,true,longPress);
    }
    private View addT9Bottom(LinearLayout row,String label,float weight,boolean accent,Runnable action,Runnable longPress,String desc){
        View key=t9Text(label,label.length()>2?11:label.length()>1?15:20,accent,action,longPress);key.setContentDescription(desc);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,weight);lp.setMargins(dp(3),0,dp(3),0);row.addView(key,lp);
        return key;
    }
    private void buildT9(){
        int h=dp(t9Row()),gap=dp(3);
        LinearLayout area=new LinearLayout(this);area.setOrientation(LinearLayout.HORIZONTAL);
        area.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,3*h+6*gap));

        LinearLayout marks=new LinearLayout(this);marks.setOrientation(LinearLayout.VERTICAL);
        area.addView(marks,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1.6f));
        String[] markKeys={"，","。","？"};
        for(String mark:markKeys){
            View key=t9Text(mark,22,false,()->punctuation(mark),null);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h);lp.setMargins(gap,gap,gap,gap);marks.addView(key,lp);
        }

        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);
        area.addView(grid,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,6f));
        String[][] letters={{"@#","ABC","DEF"},{"GHI","JKL","MNO"},{"PQRS","TUV","WXYZ"}};
        String[][] digits={{"","2","3"},{"4","5","6"},{"7","8","9"}};
        for(int r=0;r<3;r++){
            LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h);lineLp.setMargins(0,gap,0,gap);grid.addView(line,lineLp);
            for(int c=0;c<3;c++){
                final String digit=digits[r][c];
                View key=(r==0&&c==0)?t9Text("@#",18,false,()->{commitRaw();symbols=true;rebuildKeys();},null):t9Letter(letters[r][c],digit,()->type(digit));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f);lp.setMargins(gap,0,gap,0);line.addView(key,lp);
            }
        }

        LinearLayout side=new LinearLayout(this);side.setOrientation(LinearLayout.VERTICAL);
        area.addView(side,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,2.1f));
        View delete=t9Icon(R.drawable.ic_backspace,"删除，长按清空拼音",this::delete,this::clearComposing);
        LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h);dlp.setMargins(gap,gap,gap,gap);side.addView(delete,dlp);
        tallKey=(TextView)t9Text("换行",16,false,()->{if(composing.length()>0)clearComposing();else newline();},null);
        LinearLayout.LayoutParams elp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,2*h+2*gap);elp.setMargins(gap,gap,gap,gap);side.addView(tallKey,elp);
        keys.addView(area);

        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h);blp.setMargins(0,gap,0,0);bottom.setLayoutParams(blp);
        keys.addView(bottom);
        bangKey=(TextView)addT9Bottom(bottom,"！",1.6f,false,()->punctuation("！"),null,"感叹号");
        addT9Bottom(bottom,"符号",1.2f,false,this::showPunctuation,null,"中文标点");
        addT9Bottom(bottom,"123",1.2f,false,()->{commitRaw();symbols=true;rebuildKeys();},null,"数字与符号");
        addT9Bottom(bottom,"空格",2.4f,false,this::space,()->getSystemService(InputMethodManager.class).showInputMethodPicker(),"空格，拼音时选择首个候选词；长按切换输入法");
        addT9Bottom(bottom,"中/英",1.2f,false,()->{commitRaw();chinese=!chinese;rebuildKeys();updateCandidates();},()->getSystemService(InputMethodManager.class).showInputMethodPicker(),"切换中英文，长按选择输入法");
        actionKey=(TextView)addT9Bottom(bottom,"发送",2.1f,true,()->{if(composing.length()>0)commitRaw();else sendAction();},null,"发送");
        syncT9Actions();
    }
    private void showLayouts(){
        expanded=false;updateExpandButton();commitRaw();keys.removeAllViews();
        String[] labels={"中文 · 全键盘","中文 · 九宫格","English","数字与符号"};
        for(int i=0;i<labels.length;i++){final int choice=i;LinearLayout r=row();addKey(r,labels[i],0,labels[i],1,false,()->{chinese=choice<2;nine=choice==1;symbols=choice==3;previousWord="";if(choice<2)prefs.edit().putBoolean("nine",nine).apply();rebuildKeys();updateCandidates();},false);keys.addView(r);}
    }
    private int rowHeight(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?42:54;}
    private void showEmoji(){
        keys.removeAllViews();emojiPanel=true;expanded=false;updateExpandButton();
        HorizontalScrollView tabs=new HorizontalScrollView(this);tabs.setHorizontalScrollBarEnabled(false);LinearLayout categories=new LinearLayout(this);
        for(int i=0;i<EmojiCatalog.TITLES.length;i++){final int at=i;TextView tab=text(EmojiCatalog.TITLES[i],13);tab.setTextColor(at==emojiCategory?GlassStyle.MINT:GlassStyle.INK);tab.setBackground(at==emojiCategory?GlassStyle.surface(0x3349987D,dp(14),0):null);tab.setOnClickListener(v->{haptic(v);emojiCategory=at;showEmoji();});categories.addView(tab,new LinearLayout.LayoutParams(dp(54),dp(34)));}tabs.addView(categories);keys.addView(tabs,new LinearLayout.LayoutParams(-1,dp(34)));tabs.post(()->tabs.smoothScrollTo(dp(Math.max(0,emojiCategory-2)*54),0));
        ScrollView grid=new ScrollView(this);grid.setContentDescription("Emoji 表情列表");LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);grid.addView(body);String[] entries=EmojiCatalog.ITEMS[emojiCategory];
        for(int start=0;start<entries.length;start+=7){LinearLayout line=new LinearLayout(this);body.addView(line,new LinearLayout.LayoutParams(-1,dp(42)));for(int col=0;col<7;col++){int position=start+col;if(position<entries.length){String value=entries[position];TextView cell=text(value,26);cell.setContentDescription("插入表情 "+value);cell.setOnClickListener(v->{haptic(v);insertEmoji(value);});line.addView(cell,new LinearLayout.LayoutParams(0,-1,1));}else line.addView(new View(this),new LinearLayout.LayoutParams(0,-1,1));}}
        keys.addView(grid,new LinearLayout.LayoutParams(-1,dp(rowHeight()*3-34)));
        LinearLayout bottom=row();addKey(bottom,"返回键盘",0,"返回键盘",2,false,this::rebuildKeys,false);addKey(bottom,"空格",0,"空格",2,false,this::space,false);addKey(bottom,"",R.drawable.ic_backspace,"删除表情",1,false,this::delete,true);keys.addView(bottom);
    }
    private void insertEmoji(String emoji){InputConnection ic=getCurrentInputConnection();if(ic!=null){lastEmoji=emoji;ic.commitText(emoji,1);}previousWord="";updateCandidates();}
    private void updateExpandButton(){if(expandButton==null)return;expandButton.setRotation(expanded?180:0);expandButton.setContentDescription(expanded?"收起候选词":"展开候选词");expandButton.setEnabled(!queryPending&&!candidates.isEmpty());expandButton.setAlpha(candidates.isEmpty()?.3f:1f);}
    private void showExpandedCandidates(){
        keys.removeAllViews();emojiPanel=false;expanded=true;
        final List<PinyinEngine.Candidate> shown=new ArrayList<>(candidates);
        ScrollView grid=new ScrollView(this);grid.setContentDescription("候选词列表");LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);grid.addView(body);
        for(int start=0;start<shown.size();start+=5){LinearLayout line=new LinearLayout(this);body.addView(line,new LinearLayout.LayoutParams(-1,dp(rowHeight())));
            for(int col=0;col<5;col++){int p=start+col;LinearLayout.LayoutParams cellParams=new LinearLayout.LayoutParams(0,-1,1);cellParams.setMargins(dp(2),dp(2),dp(2),dp(2));
                if(p>=shown.size()){line.addView(new View(this),cellParams);continue;}
                PinyinEngine.Candidate c=shown.get(p);TextView cell=text("",20);
                String title=c.text.codePointCount(0,c.text.length())>5?c.text.substring(0,c.text.offsetByCodePoints(0,4))+"…":c.text;
                String spelling=c.spelling.length()>10?c.spelling.substring(0,9)+"…":c.spelling;
                android.text.SpannableString label=new android.text.SpannableString(title+"\n"+spelling);int readingStart=title.length()+1;
                label.setSpan(new android.text.style.RelativeSizeSpan(.55f),readingStart,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setSpan(new android.text.style.ForegroundColorSpan(0xFF9CB2BA),readingStart,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cell.setTextSize(title.codePointCount(0,title.length())>=5?12:title.length()>2?14:20);cell.setText(label);cell.setMaxLines(3);cell.setPadding(dp(2),dp(2),dp(2),dp(2));cell.setBackground(GlassStyle.surface(p==0?0x3349987D:0x0CFFFFFF,dp(8),0));cell.setContentDescription(c.text+"，"+c.spelling);cell.setOnClickListener(v->dispatch(()->{if(!queryPending){haptic(v);select(c);}}));line.addView(cell,cellParams);
            }
        }
        keys.addView(grid,new LinearLayout.LayoutParams(-1,dp(rowHeight()*4)));updateExpandButton();
    }
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);int h=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?42:54;r.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(h)));return r;}
    private View addKey(LinearLayout row,String label,int resource,String desc,float weight,boolean accent,Runnable action,boolean onDown){
        View view;if(resource!=0){ImageButton b=icon(resource,desc);if(accent)b.setImageTintList(ColorStateList.valueOf(0xFF122A24));view=b;}else view=text(label,label.length()>1?15:24);
        view.setContentDescription(desc);view.setClickable(true);view.setFocusable(true);view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x5581D8B8),GlassStyle.surface(accent?GlassStyle.MINT:0x1FFFFFFF,dp(11),accent?0:0x28FFFFFF),null));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,weight);lp.setMargins(dp(3),dp(4),dp(3),dp(4));row.addView(view,lp);
        // Touch-down enters characters immediately; click remains available to accessibility services.
        view.setOnClickListener(v->dispatch(()->{haptic(v);action.run();}));
        if(onDown)view.setOnTouchListener((v,event)->{switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:v.setPressed(true);v.drawableHotspotChanged(event.getX(),event.getY());haptic(v);dispatch(action);return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:v.setPressed(false);return true;
            default:return true;}});
        return view;
    }
    private void type(String value){InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        lastEmoji="";char c=value.charAt(0);boolean pinyin=chinese&&!symbols&&!sensitive&&((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(t9Active()&&c>='2'&&c<='9')||c=='\'');
        boolean english=!chinese&&!symbols&&englishEligible&&Character.isLetter(c);
        if(pinyin||english){if(composing.length()>=64)commitRaw();if(composing.length()==0){acceptedText.setLength(0);acceptedSpelling.setLength(0);}if(c=='\''&&(composing.length()==0||composing.charAt(composing.length()-1)=='\''))return;composing.append(pinyin?value.toLowerCase(Locale.ROOT):value);if(!t9Active()){displayed=composing.toString();ic.setComposingText(displayed,1);}updateCandidates();}else ic.commitText(value,1);
        if(shifted){shifted=false;rebuildKeys();}
    }
    private void updateCandidates(){
        if(dock==null)return;
        syncT9Actions();
        int version=++generation;String raw=composing.toString();boolean nineMode=t9Active(),personal=personalized&&prefs.getBoolean("learning",true);
        if(pendingQuery!=null)worker.removeCallbacks(pendingQuery);
        boolean suggestions=prefs.getBoolean("suggestions",true)&&!sensitive;
        String context=previousWord;boolean zh=chinese;
        prediction=raw.isEmpty();
        if(raw.isEmpty()&&(!suggestions||context.isEmpty())){queryPending=false;candidates=Collections.emptyList();renderCandidates();return;}
        queryPending=true;candidates=Collections.emptyList();updateExpandButton();for(TextView chip:chips){chip.setEnabled(false);chip.setVisibility(View.GONE);}
        pendingQuery=()->{
            List<PinyinEngine.Candidate> result=new ArrayList<>();
            if(raw.isEmpty()){for(String word:words.next(context,zh))result.add(new PinyinEngine.Candidate(word,"",0,0));}
            else if(zh){if(engine!=null)result=engine.candidates(raw,nineMode,personal);}
            else if(suggestions){for(String word:words.completeEnglish(raw))result.add(new PinyinEngine.Candidate(word,"",raw.length(),0));}
            final List<PinyinEngine.Candidate> found=result;
            main.post(()->{if(destroyed||version!=generation)return;queryPending=false;candidates=found;
                if(nineMode&&!raw.isEmpty()){
                    displayed=found.isEmpty()?"":found.get(0).spelling.substring(0,Math.min(PinyinEngine.normalize(raw).length(),found.get(0).spelling.length()));
                    InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.setComposingText(displayed,1);
                }
                renderCandidates();if(waitingForChoice){waitingForChoice=false;space();while(!waitingForChoice&&!deferred.isEmpty())deferred.removeFirst().run();}
            });};worker.post(pendingQuery);
    }
    private void renderCandidates(){
        candidateRow.removeAllViews();chips.clear();visibleCandidates=0;candidateScroll.scrollTo(0,0);appendCandidateStrip();
        if(candidates.isEmpty()){TextView idle=text(composing.length()>0?(t9Active()?displayed:composing.toString()):"Future Keyboard · 隐私输入法",composing.length()>0?20:12);idle.setTextColor(0xFFB3C8CC);idle.setPadding(dp(16),0,dp(16),0);candidateRow.addView(idle,new LinearLayout.LayoutParams(-2,dp(40)));if(composing.length()>0)idle.setOnClickListener(v->{haptic(v);commitRaw();});}
        if(expanded){if(composing.length()>0&&!candidates.isEmpty())showExpandedCandidates();else rebuildKeys();}updateExpandButton();
    }
    private void appendCandidateStrip(){
        int end=Math.min(candidates.size(),visibleCandidates+PinyinEngine.LIMIT);
        while(visibleCandidates<end){PinyinEngine.Candidate c=candidates.get(visibleCandidates);TextView t=text(c.text,20);t.setPadding(dp(16),0,dp(16),0);if(visibleCandidates==0)t.setBackground(GlassStyle.surface(0x6049987D,dp(20),0));t.setOnClickListener(v->dispatch(()->{haptic(v);select(c);}));candidateRow.addView(t,new LinearLayout.LayoutParams(-2,dp(40)));chips.add(t);visibleCandidates++;}
    }
    private void remember(String spelling,String text){if(!personalized||!prefs.getBoolean("learning",true))return;long epoch=prefs.getLong("learning_epoch",0);worker.post(()->{if(engine!=null)try{LearningStore.record(this,engine,spelling,text,epoch);}catch(IOException e){android.util.Log.w("GlassIME","Could not save local phrase",e);}});}
    private void select(PinyinEngine.Candidate c){
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(!chinese||prediction){boolean next=prediction;String before="";if(next&&!chinese){CharSequence tail=ic.getTextBeforeCursor(1,0);if(tail!=null&&tail.length()>0&&!Character.isWhitespace(tail.charAt(0)))before=" ";}ic.commitText(before+c.text,1);composing.setLength(0);displayed="";previousWord=c.text;updateCandidates();return;}
        ic.beginBatchEdit();ic.commitText(c.text,1);composing.delete(0,Math.min(c.consumed,composing.length()));while(composing.length()>0&&composing.charAt(0)=='\'')composing.deleteCharAt(0);if(composing.length()>0){
            if(t9Active()){int consumed=PinyinEngine.normalize(c.spelling).length();displayed=displayed.length()>consumed?displayed.substring(consumed):PinyinEngine.fallbackSpelling(composing.toString());}
            else displayed=composing.toString();
            ic.setComposingText(displayed,1);
        }ic.endBatchEdit();
        if(composing.length()==0)previousWord=c.text;
        acceptedText.append(c.text);acceptedSpelling.append(c.spelling);remember(c.spelling,c.text);
        if(composing.length()==0&&acceptedText.length()>c.text.length())remember(acceptedSpelling.toString(),acceptedText.toString());updateCandidates();
    }
    private void commitRaw(){InputConnection ic=getCurrentInputConnection();if(ic!=null&&composing.length()>0)ic.commitText(t9Active()?displayed:composing.toString(),1);previousWord="";displayed="";composing.setLength(0);acceptedText.setLength(0);acceptedSpelling.setLength(0);updateCandidates();}
    private void space(){if(!chinese&&composing.length()>0){InputConnection ic=getCurrentInputConnection();previousWord=composing.toString();if(ic!=null)ic.commitText(previousWord+" ",1);composing.setLength(0);displayed="";updateCandidates();return;}if(composing.length()>0){if(queryPending){waitingForChoice=true;return;}if(candidates.isEmpty())commitRaw();else select(candidates.get(0));}else{InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.commitText(" ",1);}}
    private void punctuation(String value){if(!chinese&&composing.length()>0)commitRaw();if(queryPending&&composing.length()>0){waitingForChoice=true;deferred.addFirst(()->punctuation(value));return;}if(composing.length()>0){if(!candidates.isEmpty())select(candidates.get(0));if(composing.length()>0)commitRaw();}InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.commitText(value,1);previousWord="";updateCandidates();}
    private void delete(){InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(!lastEmoji.isEmpty()&&composing.length()==0){CharSequence tail=ic.getTextBeforeCursor(lastEmoji.length(),0);if(lastEmoji.contentEquals(tail==null?"":tail)){ic.deleteSurroundingText(lastEmoji.length(),0);lastEmoji="";previousWord="";updateCandidates();return;}lastEmoji="";}
        previousWord="";acceptedText.setLength(0);acceptedSpelling.setLength(0);if(composing.length()>0){composing.deleteCharAt(composing.length()-1);if(!t9Active()||composing.length()==0){displayed=composing.toString();ic.setComposingText(displayed,1);}updateCandidates();}else{ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DEL));updateCandidates();}}
    private void showPunctuation(){
        keys.removeAllViews();emojiPanel=false;expanded=false;updateExpandButton();commitRaw();
        String[][] pages={{"，","。","？","！","、","；","："},{"“","”","‘","’","（","）","《"},{"》","【","】","〔","〕","…","—"}};
        for(String[] line:pages){
            LinearLayout row=row();
            for(String mark:line){
                View key=t9Text(mark,20,false,()->punctuation(mark),null);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f);lp.setMargins(dp(3),dp(4),dp(3),dp(4));row.addView(key,lp);
            }
            keys.addView(row);
        }
        LinearLayout bottom=row();
        addKey(bottom,"返回键盘",0,"返回键盘",2,false,this::rebuildKeys,false);
        addKey(bottom,"空格",0,"空格",2,false,this::space,false);
        addKey(bottom,"",R.drawable.ic_backspace,"删除",1,false,this::delete,true);
        keys.addView(bottom);
    }
    private void syncT9Actions(){
        boolean editing=composing.length()>0;
        if(tallKey!=null){tallKey.setText(editing?"重输":"换行");tallKey.setContentDescription(editing?"重输：清空已输入的拼音":"换行");}
        if(actionKey!=null){actionKey.setText(editing?"确定":"发送");actionKey.setContentDescription(editing?"确定：把当前拼音原样上屏":"发送");}
        if(bangKey!=null)bangKey.setVisibility(editing?View.GONE:View.VISIBLE);
    }
    private void clearComposing(){
        displayed="";composing.setLength(0);acceptedText.setLength(0);acceptedSpelling.setLength(0);
        InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.setComposingText("",1);
        updateCandidates();
    }
    private void newline(){
        if(composing.length()>0)commitRaw();
        InputConnection ic=getCurrentInputConnection();if(ic!=null)ic.commitText("\n",1);
    }
    private void sendAction(){
        if(composing.length()>0){commitRaw();return;}
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(editorAction!=EditorInfo.IME_ACTION_NONE&&editorAction!=EditorInfo.IME_ACTION_UNSPECIFIED)ic.performEditorAction(editorAction);
        else{ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));}
    }
    private void enter(){if(composing.length()>0){commitRaw();return;}InputConnection ic=getCurrentInputConnection();if(ic==null)return;if(editorAction!=EditorInfo.IME_ACTION_NONE&&editorAction!=EditorInfo.IME_ACTION_UNSPECIFIED)ic.performEditorAction(editorAction);else ic.commitText("\n",1);}
}
