package mov.film.airupdate.test;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.net.Uri;
import android.content.ContentValues;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.zip.*;

/** Run only on a disposable emulator: writes a private sentinel and offers a reinstall. */
public final class UpdateRegression extends Instrumentation {
    private boolean verifyOnly;
    private long expectedUpdateAfter;
    private boolean themeContract;
    @Override public void onCreate(Bundle args) {super.onCreate(args);verifyOnly=args!=null&&"true".equals(args.getString("verifyOnly"));themeContract=args!=null&&"true".equals(args.getString("themeContract"));expectedUpdateAfter=args==null?0:Long.parseLong(args.getString("updatedAfter","0"));start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(!Build.HARDWARE.contains("ranchu")&&!Build.FINGERPRINT.contains("generic"))throw new SecurityException("Disposable emulator required");
            Context app=getTargetContext();ClassLoader loader=app.getClassLoader();
            if(themeContract){
                Uri uri=Uri.parse("content://belowevolve.tvhome.theme");
                if(app.getContentResolver().call(uri,"palette",null,null)==null)throw new AssertionError("Missing palette");
                Bundle bindings=app.getContentResolver().call(uri,"bindings",null,null);
                if(bindings==null||!bindings.containsKey("183"))throw new AssertionError("Missing button bindings");
                try{app.getContentResolver().insert(uri,new ContentValues());throw new AssertionError("Theme writes allowed");}catch(UnsupportedOperationException expected){}
                try{app.getContentResolver().update(uri,new ContentValues(),null,null);throw new AssertionError("Theme writes allowed");}catch(UnsupportedOperationException expected){}
                try{app.getContentResolver().delete(uri,null,null);throw new AssertionError("Theme writes allowed");}catch(UnsupportedOperationException expected){}
                result.putString("result","PASS; film.mov reads palette/bindings across independent signatures; insert/update/delete rejected");finish(-1,result);return;
            }
            if(verifyOnly){
                if(!"kept".equals(app.getSharedPreferences("airupdate-regression",0).getString("sentinel","")))throw new AssertionError("Data lost after reinstall");
                if(expectedUpdateAfter<=0||app.getPackageManager().getPackageInfo("mov.film",0).lastUpdateTime<=expectedUpdateAfter)throw new AssertionError("APK was not reinstalled");
                result.putString("result","PASS; application data preserved after actual reinstall");finish(-1,result);return;
            }
            Class<?> engine=loader.loadClass("mov.film.airupdate.PatchEngine");
            Class<?> progress=loader.loadClass("mov.film.airupdate.PatchEngine$Progress");
            File root=new File(app.getCacheDir(),"updater-regression-"+System.nanoTime());root.mkdirs();
            File original=new File(root,"original.apk");
            try(InputStream in=getContext().getAssets().open("original.apk")){Files.copy(in,original.toPath());}
            File self=new File(app.getApplicationInfo().sourceDir);
            Object listener=Proxy.newProxyInstance(loader,new Class[]{progress},(p,m,a)->{if(a!=null)android.util.Log.i("AirUpdateTest",a[0].toString());return null;});
            Method patch=engine.getMethod("patch",File.class,File.class,File.class,progress);
            long start=System.currentTimeMillis();
            File output=(File)patch.invoke(null,original,self,new File(root,"build"),listener);
            int expectedVersion=app.getPackageManager().getPackageArchiveInfo(original.getPath(),0).versionCode;
            if(app.getPackageManager().getPackageArchiveInfo(output.getPath(),0).versionCode!=expectedVersion)throw new AssertionError("Version changed");
            try(ZipFile a=new ZipFile(original);ZipFile b=new ZipFile(output)){
                java.util.Enumeration<? extends ZipEntry> entries=a.entries();
                while(entries.hasMoreElements()){
                    String n=entries.nextElement().getName();
                    if(!n.startsWith("lib/")&&!n.startsWith("res/raw/"))continue;
                    if(!java.util.Arrays.equals(read(a.getInputStream(a.getEntry(n))),read(b.getInputStream(b.getEntry(n)))))throw new AssertionError("Payload changed: "+n);
                }
                for(String n:new String[]{"assets/airupdate/recipe.zip","assets/airupdate/runtime.dex","assets/airupdate/signer.der"})if(b.getEntry(n)==null)throw new AssertionError("Updater not preserved");
            }
            try {patch.invoke(null,self,self,new File(root,"bad"),listener);throw new AssertionError("Accepted a non-upstream signer");}
            catch(InvocationTargetException expected){if(!expected.getCause().getMessage().contains("Подпись автора"))throw expected;}
            app.getSharedPreferences("airupdate-regression",0).edit().putString("sentinel","kept").commit();
            long installedAt=app.getPackageManager().getPackageInfo("mov.film",0).lastUpdateTime;
            Activity settings=startActivitySync(new Intent(app,loader.loadClass("mov.film.SettingsActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Method install=loader.loadClass("mov.film.airupdate.PatchedUpdate").getDeclaredMethod("install",Context.class,File.class);install.setAccessible(true);
            runOnMainSync(()->{try{install.invoke(null,settings,output);}catch(Exception e){throw new RuntimeException(e);}});
            boolean confirmation=false;long deadline=SystemClock.elapsedRealtime()+15000;
            while(SystemClock.elapsedRealtime()<deadline){
                AccessibilityNodeInfo window=getUiAutomation().getRootInActiveWindow();
                if(window!=null&&window.getPackageName()!=null&&window.getPackageName().toString().contains("packageinstaller")){confirmation=true;break;}
                SystemClock.sleep(50);
            }
            if(!confirmation)throw new AssertionError("Android installation confirmation did not appear");
            result.putString("installedAt",Long.toString(installedAt));
            result.putString("result","PASS; version="+expectedVersion+"; original payload preserved; self-update assets preserved; signer rejection; Android install confirmation visible; ms="+(System.currentTimeMillis()-start));
            finish(-1,result);
        }catch(Throwable error){android.util.Log.e("AirUpdateTest","Regression failed",error);result.putString("error",error.toString());finish(0,result);}
    }
    private static byte[] read(InputStream in)throws Exception{try(InputStream i=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=i.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
}
