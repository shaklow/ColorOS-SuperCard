"""Check the actual Lens click hooks with fake OEM/Android boundaries, offline."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from test_floating_lens import SOURCES as BASE

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {k: v for k, v in BASE.items() if not k.endswith('TestFloatingLens')}
SOURCES.update({
'android.content.Context': '''public class Context { public final android.app.KeyguardManager keyguard=new android.app.KeyguardManager();
 public <T>T getSystemService(Class<T> type){return type.cast(keyguard);}}''',
'android.app.KeyguardManager': '''public class KeyguardManager {public boolean locked;
 public boolean isKeyguardLocked(){return locked;}public boolean isDeviceLocked(){return locked;}}''',
'android.os.SystemClock': 'public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}',
'io.github.libxposed.api.XposedModule': '''public class XposedModule {
 public interface Chain {Object getThisObject();Object getArg(int i);Object proceed() throws Throwable;}
 public interface Hooker {Object intercept(Chain chain) throws Throwable;}
 public final java.util.Map<java.lang.reflect.Method,Hooker> hooks=new java.util.HashMap<>();
 public Registration hook(java.lang.reflect.Method method){return new Registration(method);}
 public class Registration {final java.lang.reflect.Method method;Registration(java.lang.reflect.Method m){method=m;}
 public void intercept(Hooker h){hooks.put(method,h);}}}''',
'com.vivo.card.cards.base.view.BaseCardView': '''public class BaseCardView extends android.widget.FrameLayout {
 public BaseCardView(android.content.Context c){super(c);}public void onCardPackHiding(){}public void onCardPackHidden(){}
 public void onCardPackDestroyed(){}public void notifyCardForegroundState(boolean active){}
 public void onOuterPageScrollStateChanged(int state,String type){} }''',
'com.vivo.card.model.CardSetBean': '''public class CardSetBean {public static class CardBean {public static class FunctionBean {
 public String getType(){return "take_photo";}}}}''',
'com.vivo.card.cards.cardcamera.handler.CameraCardEventHandler': '''public class CameraCardEventHandler {
 private final Object cardCallback;public boolean expanded=true;public int unlocks;
 public CameraCardEventHandler(Object c){cardCallback=c;}private boolean isCardExpanded(){return expanded;}
 private void requestUnlock(android.content.Context c){unlocks++;}
 public void handleFunctionClick(android.content.Context c,com.vivo.card.model.CardSetBean.CardBean.FunctionBean f){}
 public void handleSettingsClick(android.content.Context c){}public void resetPendingKeyguardRequest(){}public void onKeyguardSuccess(){} }''',
'dev.local.supercardhost.ColorOsLensModes': '''public class ColorOsLensModes {
 public static Object embeddedIntent(android.content.Context c,String type){if(!"take_photo".equals(type))throw new IllegalArgumentException();return new Object();}}''',
'dev.local.supercardhost.LensCardView': '''public class LensCardView extends android.widget.FrameLayout {
 public int opens,closes,resumes;public LensCardView(android.content.Context c){super(c);}
 public void openCamera(String t){opens++;}public void closeCamera(){closes++;}public void resumeCameraAfterUnlock(String t){resumes++;}}''',
'dev.local.supercardhost.TestLensCamera': '''import android.content.Context;
import android.widget.FrameLayout;
import com.vivo.card.cards.base.view.BaseCardView;
import com.vivo.card.cards.cardcamera.handler.CameraCardEventHandler;
import com.vivo.card.model.CardSetBean.CardBean.FunctionBean;
import io.github.libxposed.api.XposedModule;
public class TestLensCamera {
 static int checks;static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static class Chain implements XposedModule.Chain {Object owner;Object[]args;int proceeded;
 Chain(Object o,Object...a){owner=o;args=a;}public Object getThisObject(){return owner;}
 public Object getArg(int i){return args[i];}public Object proceed(){proceeded++;return null;}}
 static Chain call(XposedModule m,Object owner,String name,Object...args)throws Throwable {
  var match=m.hooks.entrySet().stream().filter(e->e.getKey().getName().equals(name)).findFirst().orElseThrow();
  Chain c=new Chain(owner,args);match.getValue().intercept(c);return c;}
 public static void main(String[]args)throws Throwable {
  var module=new XposedModule();LensCameraBridge.install(module,TestLensCamera.class.getClassLoader());
  Context context=new Context();BaseCardView card=new BaseCardView(context);LensCardView lens=new LensCardView(context);card.addView(lens,null);
  var handler=new CameraCardEventHandler(card);var mode=new FunctionBean();
  Chain click=call(module,handler,"handleFunctionClick",context,mode);
  check(lens.opens==1&&click.proceeded==0,"Mode remains in the card before original hide/external launch");
  handler.expanded=false;call(module,handler,"handleFunctionClick",context,mode);check(lens.opens==1,"Collapsed card ignores clicks");handler.expanded=true;
  context.keyguard.locked=true;call(module,handler,"handleFunctionClick",context,mode);
  check(handler.unlocks==1&&lens.opens==1,"Locked card requests original unlock without launching camera");
  context.keyguard.locked=false;call(module,handler,"onKeyguardSuccess");check(lens.resumes==1,"Unlock resumes the selected embedded camera");
  call(module,handler,"onKeyguardSuccess");check(lens.resumes==1,"Unlock cannot replay a selection");
  context.keyguard.locked=true;call(module,handler,"handleFunctionClick",context,mode);call(module,handler,"resetPendingKeyguardRequest");
  call(module,handler,"onKeyguardSuccess");check(lens.resumes==1,"Cancelled unlock discards camera action");
  call(module,handler,"handleFunctionClick",context,mode);call(module,handler,"handleSettingsClick",context);call(module,handler,"onKeyguardSuccess");
  check(lens.resumes==1&&lens.closes==1,"Settings replaces pending camera action");
  call(module,handler,"handleFunctionClick",context,mode);android.os.SystemClock.now=60001;
  call(module,handler,"onKeyguardSuccess");check(lens.resumes==1,"Expired unlock cannot reopen the camera");
  for(String event:new String[]{"onCardPackHiding","onCardPackHidden","onCardPackDestroyed"})call(module,card,event);
  call(module,card,"notifyCardForegroundState",false);call(module,card,"onOuterPageScrollStateChanged",0,"paycard");
  check(lens.closes==6,"Hide, destroy, and page switch release camera even while attached");
  call(module,card,"notifyCardForegroundState",true);call(module,card,"onOuterPageScrollStateChanged",0,"camera");
  check(lens.closes==6,"Foreground camera stays active");
  BaseCardView fallback=new BaseCardView(context);check(call(module,new CameraCardEventHandler(fallback),"handleFunctionClick",context,mode).proceeded==1,"Original UI fallback remains valid");
  System.out.println("Embedded Lens hooks: "+checks+" checks passed.");
 }}''',
})


def main():
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java' + suffix) if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
    javac = java.with_name('javac' + suffix)
    compiler = [str(javac)] if javac.exists() else [str(java), 'com.sun.tools.javac.Main']
    with tempfile.TemporaryDirectory(prefix='lens-camera-hooks-') as directory:
        source = Path(directory) / 'src'; out = Path(directory) / 'classes'; files = []
        for name, body in SOURCES.items():
            path = source / (name.replace('.', '/') + '.java'); path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body, encoding='utf-8'); files.append(str(path))
        files.append(str(ROOT / 'app/src/main/java/dev/local/supercardhost/LensCameraBridge.java'))
        subprocess.run(compiler + ['-encoding', 'UTF-8', '--release', '17', '-d', str(out)] + files, check=True)
        subprocess.run([str(java), '-cp', str(out), 'dev.local.supercardhost.TestLensCamera'], check=True)


if __name__ == '__main__':
    main()
