"""Exercise the production floating-card hook boundary without an Android device."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
'android.content.Context': 'public class Context { public void startActivity(Object intent){throw new AssertionError("Floating cards must not launch a dashboard");}}',
'android.view.View': '''public class View {
 public final android.content.Context context;public Object parent;
 public View(android.content.Context c){context=c;} public android.content.Context getContext(){return context;}
 public Object getParent(){return parent;}
}''',
'android.view.ViewGroup': '''public class ViewGroup extends View {
 public final java.util.List<View> children=new java.util.ArrayList<>();public boolean failAdd;
 public ViewGroup(android.content.Context c){super(c);}
 public void addView(View v,Object params){children.add(v);v.parent=this;if(failAdd)throw new IllegalStateException("attach failed");}
 public void removeView(View v){children.remove(v);v.parent=null;}
}''',
'android.widget.FrameLayout': '''public class FrameLayout extends android.view.ViewGroup {
 public FrameLayout(android.content.Context c){super(c);}
 public static class LayoutParams {public LayoutParams(int w,int h){if(w!=-1||h!=-1)throw new AssertionError("Not card-sized");}}
}''',
'android.util.Log': 'public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
'io.github.libxposed.api.XposedModule': '''public class XposedModule {
 public interface Chain {Object getThisObject();Object proceed() throws Throwable;}
 public interface Hooker {Object intercept(Chain chain) throws Throwable;}
 public final java.util.Map<java.lang.reflect.Method,Hooker> hooks=new java.util.HashMap<>();
 public Registration hook(java.lang.reflect.Method method){return new Registration(method);}
 public class Registration {final java.lang.reflect.Method method;Registration(java.lang.reflect.Method m){method=m;}
 public void intercept(Hooker h){hooks.put(method,h);}}
}''',
'com.vivo.card.cards.cardcamera.CameraCardView': '''public class CameraCardView extends android.widget.FrameLayout {
 public CameraCardView(android.content.Context c){super(c);}
 private void initComposeView(){} public void updateData(){} public void onGestureEnd(){}
}''',
'dev.local.supercardhost.LensCardView': '''public class LensCardView extends android.widget.FrameLayout {
 public static boolean fail, linkage;public final android.view.View owner;
 public LensCardView(android.content.Context c,android.view.View owner){super(c);this.owner=owner;
 if(fail)throw new IllegalStateException("Private UI missing");if(linkage)throw new NoClassDefFoundError("Missing MDC");}
}''',
'dev.local.supercardhost.TestFloatingLens': '''import android.content.Context;
import android.view.View;
import com.vivo.card.cards.cardcamera.CameraCardView;
import io.github.libxposed.api.XposedModule;
public class TestFloatingLens {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static class Chain implements XposedModule.Chain {
  final CameraCardView card;int proceeded;Chain(CameraCardView c){card=c;}
  public Object getThisObject(){return card;} public Object proceed(){proceeded++;return "original";}
 }
 public static void main(String[] ignored)throws Throwable {
  XposedModule module=new XposedModule();LensSurfaceBridge.install(module,TestFloatingLens.class.getClassLoader());
  check(module.hooks.size()==1,"Exactly one inner-view boundary");
  java.lang.reflect.Method target=module.hooks.keySet().iterator().next();
  check(target.getName().equals("initComposeView")&&target.getDeclaringClass()==CameraCardView.class,"No gesture or window interception");
  XposedModule.Hooker hook=module.hooks.get(target);Context context=new Context();CameraCardView card=new CameraCardView(context);
  View existing=new View(context);card.addView(existing,null);Chain nativeChain=new Chain(card);
  check(hook.intercept(nativeChain)==null&&nativeChain.proceeded==0,"Successful native surface replaces only Compose initialization");
  check(card.children.size()==2&&card.children.get(0)==existing,"Original card children retained");
  LensCardView surface=(LensCardView)card.children.get(1);
  check(surface.owner==card&&surface.parent==card&&surface.getContext()==context,"Same original card, context and window owner");
  LensCardView.fail=true;Chain unavailable=new Chain(card);
  check("original".equals(hook.intercept(unavailable))&&unavailable.proceeded==1,"Missing private UI falls back to original Compose");
  check(card.children.size()==2,"Constructor failure does not alter original children");LensCardView.fail=false;
  LensCardView.linkage=true;Chain linkage=new Chain(card);hook.intercept(linkage);
  check(linkage.proceeded==1&&card.children.size()==2,"Dependency linkage failure preserves old card");LensCardView.linkage=false;
  card.failAdd=true;Chain attach=new Chain(card);hook.intercept(attach);
  check(attach.proceeded==1&&card.children.size()==2,"Partial attach is removed before original fallback");
  check(card.children.get(0)==existing&&card.children.get(1)==surface,"Fallback leaves existing content untouched");
  XposedModule absent=new XposedModule();boolean rejected=false;
  try {LensSurfaceBridge.install(absent,new ClassLoader(null){});}catch(ClassNotFoundException expected){rejected=true;}
  check(rejected&&absent.hooks.isEmpty(),"Unknown ABI rejected before installing any hook");
  System.out.println("Floating Lens: "+checks+" checks passed (offline fake Android/Xposed).");
 }
}'''
}


def main():
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    if not java or not javac:
        raise RuntimeError('A JDK is required')
    with tempfile.TemporaryDirectory(prefix='floating-lens-') as directory:
        out = Path(directory)
        files = []
        for name, source in SOURCES.items():
            path = out.joinpath(*name.split('.')).with_suffix('.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + source, encoding='utf-8')
            files.append(str(path))
        files.append(str(ROOT / 'app/src/main/java/dev/local/supercardhost/LensSurfaceBridge.java'))
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(out), *files], check=True)
        subprocess.run([java, '-cp', str(out), 'dev.local.supercardhost.TestFloatingLens'], check=True)


if __name__ == '__main__':
    main()
