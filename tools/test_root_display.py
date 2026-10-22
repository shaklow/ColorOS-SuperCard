"""Run production root connection/IPC/client code with deterministic Android fakes.

No su or real services are invoked. Framework Parcel encoding and display wrapping
are additionally exercised by Gradle and the installed-APK tests.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

from test_pay_startup import ROOT, SOURCES as STARTUP_SOURCES

SOURCES = {k: v for k, v in STARTUP_SOURCES.items() if not k.endswith("TestPayStartup")}
SOURCES["android.content.BroadcastReceiver"] = SOURCES["android.content.BroadcastReceiver"].replace(
    "public static int completions;", "public static int completions,sender;"
).replace("getSentFromUid(){return 0;}", "getSentFromUid(){return sender;}")
SOURCES.update({
    "android.os.Parcel": """
import java.util.*;
public class Parcel {
 public static int obtained,recycled; public final List<Object> values=new ArrayList<>(); int cursor;
 public static Parcel obtain(){obtained++;return new Parcel();} public void recycle(){recycled++;}
 public void readException(){} public void writeInterfaceToken(String s){values.add(s);}
 public void writeString(String s){values.add(s);} public String readString(){return (String)values.get(cursor++);}
 public void writeStrongBinder(IBinder b){values.add(b);} public IBinder readStrongBinder(){return (IBinder)values.get(cursor++);}
 public void writeInt(int i){values.add(i);} public int readInt(){return (Integer)values.get(cursor++);}
 public void writeTypedObject(Object o,int f){values.add(o);}
}
""",
    "android.content.Intent": """
public class Intent {String action; boolean demand; public android.os.IBinder endpoint; public String nonce;
 public Intent(){} public Intent(String a){action=a;} public String getAction(){return action;}
 public Intent setClassName(String p,String c){return this;} public Intent putExtra(String k,boolean v){demand=v;return this;}
 public boolean getBooleanExtra(String k,boolean d){return demand;}
 public android.os.Bundle getExtras(){android.os.Bundle b=new android.os.Bundle();b.binder=endpoint;return b;}
 public String getStringExtra(String k){return nonce;}}
""",
    "android.hardware.display.VirtualDisplay": "public class VirtualDisplay {public final int id; public VirtualDisplay(int i){id=i;}}",
    "android.hardware.display.IVirtualDisplayCallback": """
public interface IVirtualDisplayCallback {class Stub {public static IVirtualDisplayCallback asInterface(android.os.IBinder b){return new IVirtualDisplayCallback(){};}}}
""",
    "android.hardware.display.DisplayManagerGlobal": """
public class DisplayManagerGlobal {public static boolean fail; public static DisplayManagerGlobal getInstance(){return new DisplayManagerGlobal();}
 public VirtualDisplay createVirtualDisplayWrapper(VirtualDisplayConfig c,IVirtualDisplayCallback token,int id){
  if(fail)throw new IllegalStateException("framework wrap failure");return new VirtualDisplay(id);}}
""",
    "dev.local.supercardhost.PluginRuntime": """
public class PluginRuntime {public static final java.util.List<Boolean> secure=new java.util.ArrayList<>();
 public static void setPaymentSecure(boolean s){secure.add(s);}}
""",
    "dev.local.supercardhost.TestRootDisplay": """
import android.app.Application;
import android.content.*;
import android.hardware.display.*;
import android.os.*;
import android.view.Surface;
import java.util.*;

public class TestRootDisplay {
 static int checks;
 static final String NONCE="0123456789abcdef0123456789abcdef", NEXT="abcdef0123456789abcdef0123456789";
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
 interface Action {void run() throws Exception;}
 static void rejects(Action action,String name){try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(name);}
 static class Endpoint extends Binder {
  boolean alive=true,deathOnLink,linkFailure; int unsupported,id=7,hello,links,unlinks; IBinder callback=new Binder();
  IBinder.DeathRecipient death; Runnable creating; final List<Integer> released=new ArrayList<>(); List<Object> request;
  @Override public boolean isBinderAlive(){return alive;}
  @Override public void linkToDeath(IBinder.DeathRecipient d,int flags){
   if(linkFailure)throw new IllegalStateException("link failed"); death=d;links++;
   if(deathOnLink)die();
  }
  @Override public boolean unlinkToDeath(IBinder.DeathRecipient d,int flags){unlinks++;return true;}
  void die(){alive=false;if(death!=null)death.binderDied();}
  @Override public boolean transact(int code,Parcel data,Parcel reply,int flags){
   request=new ArrayList<>(data.values);
   check(flags==0,"synchronous IPC");check(request.get(0).equals("dev.local.supercardhost.RootDisplay.v1"),"v1 descriptor");
   check(request.get(1).equals(NONCE)||request.get(1).equals(NEXT),"request carries its nonce");
   if(code==unsupported)return false;
   if(code==1){hello++;check(request.size()==3&&request.get(2) instanceof IBinder,"live client handshake layout");}
   else if(code==2){check(request.size()==8,"create wire field count");if(creating!=null)creating.run();reply.writeInt(id);reply.writeStrongBinder(callback);}
   else if(code==3){check(request.size()==3,"release wire field count");released.add((Integer)request.get(2));}
   else throw new AssertionError("unexpected transaction");
   return true;
  }
 }
 static void connection() throws Exception {
  int[] lost={0}; RootDisplayConnection state=new RootDisplayConnection(new Handler(Looper.getMainLooper()),()->lost[0]++);
  Endpoint a=new Endpoint(),b=new Endpoint();
  check(state.connect(a,NONCE),"first endpoint accepted");RootDisplayConnection.Session old=state.snapshot();
  check(!state.connect(a,NONCE)&&a.hello==1&&a.links==1,"duplicate announcement does not add a death listener");
  a.die();check(state.snapshot()==null,"dead endpoint immediately unavailable");
  state.connect(b,NEXT);Handler.until(0);
  check(lost[0]==0&&state.snapshot().endpoint==b,"queued old death cannot clear replacement security");
  a.death.binderDied();Handler.until(0);check(state.snapshot().endpoint==b&&lost[0]==0,"late duplicate death ignored");
  check(old.endpoint==a&&old.nonce.equals(NONCE),"captured endpoint and nonce remain coherent");
  Endpoint failed=new Endpoint();failed.unsupported=1;
  rejects(()->state.connect(failed,NONCE),"unsupported handshake must fail");check(state.snapshot().endpoint==b,"handshake failure preserves current endpoint");
  Endpoint link=new Endpoint();link.linkFailure=true;
  rejects(()->state.connect(link,NONCE),"death registration failure must fail");check(state.snapshot().endpoint==b,"link failure preserves current endpoint");
  Endpoint dying=new Endpoint();dying.deathOnLink=true;
  rejects(()->state.connect(dying,NONCE),"death before publication must fail");
  check(state.snapshot().endpoint==b&&dying.unlinks==1,"unpublished death cannot replace current endpoint");
  rejects(()->state.connect(new Endpoint(),"bad"),"invalid nonce");
  Endpoint dead=new Endpoint();dead.alive=false;rejects(()->state.connect(dead,NONCE),"already dead endpoint");
  Endpoint c=new Endpoint();state.connect(c,NONCE);check(b.unlinks==1,"replacement unlinks old death listener");
  b.death.binderDied();Handler.until(0);check(state.snapshot().endpoint==c,"unlinked old recipient cannot disconnect current endpoint");
  c.die();c.death.binderDied();Handler.until(0);check(state.snapshot()==null&&lost[0]==1,"current death notifies exactly once");
  RootDisplayTransactions.release(old,19);check(a.released.equals(List.of(19))&&c.released.isEmpty(),"cleanup retains original endpoint");
  Endpoint unsupported=new Endpoint();RootDisplayConnection next=new RootDisplayConnection(new Handler(Looper.getMainLooper()),()->{});
  next.connect(unsupported,NONCE);unsupported.unsupported=2;
  rejects(()->RootDisplayTransactions.create(next.snapshot(),"SuperCard_Lens",600,900,240,new Surface(),19917),"missing create operation");
  unsupported.unsupported=3;rejects(()->RootDisplayTransactions.release(next.snapshot(),7),"missing release operation");
 }
 static class App extends Application {
  BroadcastReceiver receiver;
  @Override public void registerReceiver(BroadcastReceiver r,IntentFilter f,String permission,Object h,int flags){
   check(permission.equals("android.permission.DUMP"),"announcement permission retained");receiver=r;
  }
  void announce(Endpoint endpoint,String nonce){Intent i=new Intent();i.endpoint=endpoint;i.nonce=nonce;receiver.onReceive(this,i);}
 }
 static void client() throws Exception {
  App app=new App();RootDisplayClient.install(app);Endpoint first=new Endpoint();
  BroadcastReceiver.sender=10000;app.announce(first,NONCE);
  check(!RootDisplayClient.isReady()&&first.hello==0,"non-root announcement rejected before handshake");
  BroadcastReceiver.sender=0;app.announce(first,"bad");
  check(!RootDisplayClient.isReady()&&first.hello==0,"invalid nonce rejected before handshake");
  app.announce(first,NONCE);
  check(RootDisplayClient.isReady(),"production announcement connects");
  Surface surface=new Surface();VirtualDisplay pay=RootDisplayClient.create("SuperCard_Virtual",600,900,240,surface,19913);
  check(pay.id==7,"framework wrapper retains returned secondary ID");
  check(first.request.subList(2,8).equals(List.of("SuperCard_Virtual",600,900,240,surface,19917)),"PAY geometry/surface/secure flags preserved");
  check(PluginRuntime.secure.equals(List.of(true)),"PAY enables window protection");
  first.die();Endpoint second=new Endpoint();app.announce(second,NEXT);Handler.until(0);
  check(RootDisplayClient.isReady()&&PluginRuntime.secure.equals(List.of(true)),"old death cannot clear new client security");
  second.id=8;RootDisplayClient.createCamera(600,900,240,surface);
  check(second.request.get(2).equals("SuperCard_Lens")&&second.request.get(7).equals(19917),"fixed secure Lens request");
  check(PluginRuntime.secure.equals(List.of(true)),"camera leaves PAY security request alone");
  rejects(()->RootDisplayClient.create("Other",1,1,1,surface,4),"arbitrary PAY display name rejected");
  second.id=9;DisplayManagerGlobal.fail=true;
  rejects(()->RootDisplayClient.createCamera(600,900,240,surface),"failed framework wrapper");
  check(second.released.equals(List.of(9))&&PluginRuntime.secure.equals(List.of(true)),"failed Lens releases only created display without clearing PAY");
  Endpoint third=new Endpoint();second.id=10;second.creating=()->app.announce(third,NONCE);
  rejects(()->RootDisplayClient.createCamera(600,900,240,surface),"connection changes during failed creation");
  check(second.released.equals(List.of(9,10))&&third.released.isEmpty(),"rollback uses captured endpoint after replacement");
  DisplayManagerGlobal.fail=false;third.id=0;
  rejects(()->RootDisplayClient.createCamera(600,900,240,surface),"main display rejected");
  check(third.released.isEmpty(),"never release main display");
  third.id=11;third.callback=null;
  rejects(()->RootDisplayClient.createCamera(600,900,240,surface),"missing display callback rejected");
  check(third.released.equals(List.of(11)),"missing callback still rolls back positive display ID");
  third.callback=new Binder();third.id=12;
  RootDisplayClient.createCamera(600,900,240,surface);RootDisplayClient.released(12);
  check(third.released.equals(List.of(11,12)),"normal close reaches release operation");
  third.die();Handler.until(0);check(!RootDisplayClient.isReady()&&PluginRuntime.secure.get(PluginRuntime.secure.size()-1)==false,"current disconnect clears PAY security");
 }
 public static void main(String[] args)throws Exception {
  if(args[0].equals("connection"))connection();else client();
  check(Parcel.obtained==Parcel.recycled,"all request and response Parcels recycled, including failures");
  System.out.println("Root display "+args[0]+": "+checks+" checks passed (offline fake Android).");
 }
}
""",
})


def main():
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        raise RuntimeError("A JDK is required for the offline display test")
    with tempfile.TemporaryDirectory(prefix="supercard-root-display-") as temp:
        directory = Path(temp)
        files = []
        for name, source in SOURCES.items():
            path = directory.joinpath(*name.split(".")).with_suffix(".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + source, encoding="utf-8")
            files.append(str(path))
        production = ROOT / "app/src/main/java/dev/local/supercardhost"
        files += [str(production / (name + ".java")) for name in (
            "AutoVirtualDisplayManager", "RootDisplayClient", "RootDisplayStartReceiver",
            "RootDisplayConnection", "RootDisplayProtocol", "RootDisplayTransactions", "RootDisplayWrapper")]
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(directory), *files], check=True)
        for scenario in ("connection", "client"):
            subprocess.run([java, "-cp", str(directory), "dev.local.supercardhost.TestRootDisplay", scenario], check=True)


if __name__ == "__main__":
    main()
