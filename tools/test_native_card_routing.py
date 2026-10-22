"""Run production routing hooks against fake Android/Xposed gesture boundaries, without a device."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
'android.content.ContentResolver': 'public class ContentResolver {}',
'android.content.Intent': '''public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000, FLAG_ACTIVITY_CLEAR_TOP=0x04000000;
 public String pkg, name; public Intent setClassName(String p,String n){pkg=p;name=n;return this;}
 public Intent addFlags(int flags){return this;}
}''',
'android.app.KeyguardManager': 'public class KeyguardManager {public boolean locked; public boolean isKeyguardLocked(){return locked;}}',
'android.os.PowerManager': 'public class PowerManager {public boolean interactive=true; public boolean isInteractive(){return interactive;}}',
'android.content.Context': '''public class Context {
 public final android.app.KeyguardManager keyguard=new android.app.KeyguardManager();
 public final android.os.PowerManager power=new android.os.PowerManager();
 public <T>T getSystemService(Class<T> type){return type.cast(type==android.app.KeyguardManager.class?keyguard:power);}
 public ContentResolver getContentResolver(){return new ContentResolver();}
}''',
'android.app.Application': '''public class Application extends android.content.Context {
 public int launches; public boolean failLaunch;
 public void startActivity(android.content.Intent intent){if(failLaunch)throw new SecurityException(); launches++;}
}''',
'android.provider.Settings': '''public class Settings {public static class Secure {
 public static int enabled; public static int getInt(android.content.ContentResolver r,String k,int d){return enabled;}
}}''',
'android.util.Log': 'public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
'io.github.libxposed.api.XposedModule': '''public class XposedModule {
 public interface Chain {Object getArg(int n);Object proceed() throws Throwable;}
 public interface Hooker {Object intercept(Chain chain) throws Throwable;}
 public final java.util.Map<String,Hooker> hooks=new java.util.HashMap<>();
 public Registration hook(java.lang.reflect.Method method){return new Registration(method.getName());}
 public class Registration {final String name;Registration(String n){name=n;} public void intercept(Hooker h){hooks.put(name,h);}}
}''',
'dev.local.supercardhost.DhuCardConfiguration': '''public class DhuCardConfiguration {
 static Object field(Object o,String n)throws Exception {return o.getClass().getField(n).get(o);}
}''',
'dev.local.supercardhost.PluginRuntime': '''public class PluginRuntime {static int hidden;static void hide(){hidden++;}}''',
'com.vivo.card.framework.coordinator.CardCoordinator$setupGestureCallbacks$1': '''public class CardCoordinator$setupGestureCallbacks$1 {
 public void onGestureEnd(float x,float y,float distance){}
}''',
'dev.local.supercardhost.TestNativeRouting': '''import android.app.Application;
import android.provider.Settings;
import io.github.libxposed.api.XposedModule;
public class TestNativeRouting {
 static int checks;
 static void check(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
 public static class Plugin {public Impl cardServiceImpl=new Impl();}
 public static class Impl {public Coordinator cardCoordinator=new Coordinator(); public void onCardTriggerIntent(String t,String target,int start,String open){}}
 public static class Coordinator {
  public boolean shown=true; public Controller controller=new Controller();
  public boolean isCardWindowShown(){return shown;} public Controller getGestureController(){return controller;}
 }
 public static class Controller {public boolean committed,broken;
  public boolean shouldTriggerShow(float x,float y,float d,boolean shown){if(broken)throw new IllegalStateException();return committed;}
 }
 public static class Chain implements XposedModule.Chain {
  Object[] args;int proceeded; Chain(Object...a){args=a;} public Object getArg(int n){return args[n];}
  public Object proceed(){proceeded++;return "original";}
 }
 public static void main(String[] ignored)throws Throwable {
  Application host=new Application();Plugin plugin=new Plugin();XposedModule module=new XposedModule();
  NativeCardRouting.install(module,host,plugin);
  check(NativeCardRouting.isReady(),"Both original boundaries attach");
  XposedModule.Hooker trigger=module.hooks.get("onCardTriggerIntent"),end=module.hooks.get("onGestureEnd");
  Chain disabled=new Chain("intent_trigger",null,0,null);trigger.intercept(disabled);
  check(disabled.proceeded==1&&host.launches==0,"Default route unchanged");
  Settings.Secure.enabled=1;
  Chain normal=new Chain("intent_trigger",null,0,null);trigger.intercept(normal);
  check(normal.proceeded==0&&host.launches==1,"Generic trigger opens own Activity");
  Chain payment=new Chain("system_ui",null,0,"wx_pay");trigger.intercept(payment);
  check(payment.proceeded==1&&host.launches==1,"Targeted payment stays original");
  Chain atom=new Chain("atom_island","memory",0,null);trigger.intercept(atom);
  check(atom.proceeded==1,"Targeted card stays original");
  host.keyguard.locked=true;Chain locked=new Chain("intent_trigger",null,0,null);trigger.intercept(locked);
  check(locked.proceeded==1,"Lockscreen stays original");host.keyguard.locked=false;
  host.power.interactive=false;Chain asleep=new Chain("intent_trigger",null,0,null);trigger.intercept(asleep);
  check(asleep.proceeded==1,"Screen-off stays original");host.power.interactive=true;
  Chain legacy=new Chain("intent_trigger","paycard",0,null);
  NativeCardRouting.showLegacy(()->{try{trigger.intercept(legacy);}catch(Throwable e){throw new AssertionError(e);}});
  check(legacy.proceeded==1,"Legacy entry bypasses new route");
  int launched=host.launches;
  plugin.cardServiceImpl.cardCoordinator.controller.committed=false;
  Chain cancel=new Chain(0f,0f,1f);end.intercept(cancel);
  check(cancel.proceeded==1&&host.launches==launched&&PluginRuntime.hidden==0,"Cancelled swipe never routes although exit window is attached");
  plugin.cardServiceImpl.cardCoordinator.controller.committed=true;
  Chain success=new Chain(-1000f,0f,300f);end.intercept(success);
  check(success.proceeded==1&&host.launches==launched+1&&PluginRuntime.hidden==1,"Committed swipe routes and closes OEM window");
  host.failLaunch=true;
  Chain blocked=new Chain("intent_trigger",null,0,null);trigger.intercept(blocked);
  check(blocked.proceeded==1,"Denied Activity launch retains original trigger");
  Chain blockedSwipe=new Chain(-1000f,0f,300f);end.intercept(blockedSwipe);
  check(blockedSwipe.proceeded==1&&PluginRuntime.hidden==1,"Denied swipe launch leaves original card open");
  host.failLaunch=false;
  plugin.cardServiceImpl.cardCoordinator.controller.broken=true;
  Chain broken=new Chain(-1000f,0f,300f);end.intercept(broken);
  check(broken.proceeded==1&&!NativeCardRouting.isReady(),"Reflection failure disables routing without dropping original end callback");
  System.out.println("Native card routing: "+checks+" checks passed (offline fake Android/Xposed).");
 }
}'''
}


def main():
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    if not java or not javac:
        raise RuntimeError('A JDK is required')
    with tempfile.TemporaryDirectory(prefix='native-cards-') as directory:
        out = Path(directory)
        files = []
        for name, source in SOURCES.items():
            path = out.joinpath(*name.split('.')).with_suffix('.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + source, encoding='utf-8')
            files.append(str(path))
        files.append(str(ROOT / 'app/src/main/java/dev/local/supercardhost/NativeCardRouting.java'))
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(out), *files], check=True)
        subprocess.run([java, '-cp', str(out), 'dev.local.supercardhost.TestNativeRouting'], check=True)


if __name__ == '__main__':
    main()
