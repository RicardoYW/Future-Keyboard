package com.liquidglass.ime;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.*;
import android.graphics.Rect;
import java.io.*;
import java.util.*;

/** Real IME window checks, including scrolling candidates beyond the first 24 entries. */
public class CandidateFlowInstrumentation extends Instrumentation {
    private UiAutomation ui;
    private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void pause(){SystemClock.sleep(250);}
    private String shell(String command)throws Exception{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private AccessibilityNodeInfo search(AccessibilityNodeInfo n,String text,boolean description){if(n==null)return null;CharSequence value=description?n.getContentDescription():n.getText();if(value!=null&&text.contentEquals(value))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=search(n.getChild(i),text,description);if(hit!=null)return hit;}return null;}
    private AccessibilityNodeInfo find(String text,boolean description){if(Build.VERSION.SDK_INT>=33)ui.clearCache();for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo hit=search(w.getRoot(),text,description);if(hit!=null)return hit;}return null;}
    private AccessibilityNodeInfo await(String text,boolean desc)throws Exception{for(int i=0;i<20;i++){AccessibilityNodeInfo n=find(text,desc);if(n!=null)return n;pause();}throw new AssertionError("Node absent: "+text+"\n"+windows());}
    private void dump(AccessibilityNodeInfo n){if(n==null)return;report.append(n.getClassName()).append(" text=").append(n.getText()).append(" desc=").append(n.getContentDescription()).append("\n");for(int i=0;i<n.getChildCount();i++)dump(n.getChild(i));}
    private String windows(){StringBuilder s=new StringBuilder();for(AccessibilityWindowInfo w:ui.getWindows())s.append(w.getType()).append(' ').append(w.getTitle()).append('\n');return s.toString();}
    private void click(String text,boolean desc)throws Exception{AccessibilityNodeInfo n=await(text,desc);while(n!=null&&!n.isClickable())n=n.getParent();if(n==null||!n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click "+text);pause();}
    private AccessibilityNodeInfo classNode(AccessibilityNodeInfo n,String name){if(n==null)return null;if(name.contentEquals(n.getClassName()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=classNode(n.getChild(i),name);if(hit!=null)return hit;}return null;}
    private AccessibilityNodeInfo byClass(String name){if(Build.VERSION.SDK_INT>=33)ui.clearCache();for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo hit=classNode(w.getRoot(),name);if(hit!=null)return hit;}return null;}
    private String value(){AccessibilityNodeInfo edit=byClass("android.widget.EditText");return edit==null?"<no editor>":String.valueOf(edit.getText());}
    private void check(String title,boolean passed){report.append(title).append(": ").append(passed).append('\n');if(!passed)throw new AssertionError(title+"; editor="+value());}
    private void clear()throws Exception{shell("input keycombination 113 29");shell("input keyevent 67");pause();}
    private void tap(int x,int y)throws Exception{shell("input tap "+x+" "+y);pause();}
    private void layout(String name)throws Exception{click("选择键盘布局",true);click(name,true);}
    private void type(String letters)throws Exception{for(char c:letters.toCharArray())click(String.valueOf(c),true);}
    private void chooseScrolling(String word,String spelling)throws Exception{
        String target=word+"，"+spelling;SystemClock.sleep(500);
        for(int i=0;i<30;i++){AccessibilityNodeInfo item=find(target,true);if(item!=null&&item.isVisibleToUser()){click(target,true);return;}AccessibilityNodeInfo grid=find("候选词列表",true);if(grid==null)throw new AssertionError("No candidate grid");if(!grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;pause();}
        throw new AssertionError("Missing candidate "+target);
    }
    private void screenshot(String filename)throws Exception{android.graphics.Bitmap b=ui.takeScreenshot();if(b==null)return;try(FileOutputStream f=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}b.recycle();}
    private void composeRare(boolean nine)throws Exception{
        layout(nine?"中文 · 九宫格":"中文 · 全键盘");clear();
        if(nine){for(char digit:"64426".toCharArray())click(String.valueOf(digit),false);}else type("nihao");
        click("展开候选词",true);screenshot("v06-debug-expanded.png");check("expanded grid contains phrase",find("你好，nihao",true)!=null);screenshot(nine?"v06-t9-expanded.png":"v06-expanded.png");click("收起候选词",true);check("collapsing preserves composition",value().equals("nihao"));click("展开候选词",true);
        chooseScrolling("旎","ni");check((nine?"T9":"QWERTY")+" partial choice preserves remaining pinyin",value().equals("旎hao"));screenshot("v06-next-syllable.png");
        chooseScrolling("郝","hao");check((nine?"T9":"QWERTY")+" custom phrase completed",value().equals("旎郝"));check("keyboard restored after final choice",find("选择键盘布局",true)!=null);
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        ui=getUiAutomation();AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        Intent intent=new Intent(getTargetContext(),MainActivity.class).putExtra("demo",true).putExtra("motion",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getTargetContext().startActivity(intent);shell("ime set com.liquidglass.ime/.GlassImeService");
        for(int i=0;i<15&&find("选择键盘布局",true)==null;i++){tap(400,1090);}
        composeRare(false);
        clear();type("nihao");check("learned complete phrase promoted",find("旎郝",false)!=null);click("旎郝",false);check("learned phrase commits as one word",value().equals("旎郝"));
        composeRare(true);
        check("T9 layout exposes send key",find("发送",false)!=null);
        check("T9 layout exposes punctuation column",find("，",false)!=null);
        check("T9 layout exposes tall enter key",find("换行",false)!=null);
        click("Emoji 表情",true);check("emoji grid has many items",find("插入表情 😾",true)!=null);screenshot("v06-emoji-faces.png");click("插入表情 😀",true);check("simple emoji inserts",value().equals("旎郝😀"));click("删除表情",true);check("simple emoji deletes",value().equals("旎郝"));
        shell("input swipe 940 1690 100 1690 400");pause();
        click("符号",false);screenshot("v06-emoji-symbols.png");click("插入表情 ❤️",true);check("variation emoji inserts",value().equals("旎郝❤️"));click("删除表情",true);check("variation emoji deletes as whole",value().equals("旎郝"));click("返回键盘",true);
        check("emoji catalog exceeds 800 entries",Arrays.stream(EmojiCatalog.ITEMS).mapToInt(a->a.length).sum()>800);
        click("Future Keyboard 设置",true);await("永不联网。\n安心输入。",false);check("settings shortcut opens home",true);
        result.putString("stream",report+"ALL PASSED\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){try{screenshot("v06-failure.png");for(AccessibilityWindowInfo w:ui.getWindows())dump(w.getRoot());}catch(Exception ignored){}result.putString("stream",report+"FAIL: "+error);finish(Activity.RESULT_CANCELED,result);}}
}
