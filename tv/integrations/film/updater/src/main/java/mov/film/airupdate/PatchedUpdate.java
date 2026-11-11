package mov.film.airupdate;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** A native film.mov settings row; all network and APK work runs off the UI thread. */
public final class PatchedUpdate {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final String RELEASE = "https://api.github.com/repos/gamahacka/filmmov/releases/latest";
    private static final long MAX_APK = 80L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TITLE = "Обновить с патчем";

    public static void addRow(final Activity activity) {
        try {
            Method row = activity.getClass().getDeclaredMethod("row",CharSequence.class,String.class,String.class,Runnable.class);
            row.setAccessible(true);
            row.invoke(activity,TITLE,"Скачать film.mov и сохранить оформление, плашки и интеграцию","",(Runnable)() -> show(activity));
        } catch (Exception error) { android.util.Log.e("AirUpdate","Settings row",error); }
    }
    public static void show(final Activity activity) {
        if (BUSY.get()) { Toast.makeText(activity,"Обновление уже готовится",Toast.LENGTH_SHORT).show();return; }
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setTitle(TITLE);dialog.setMessage("Проверяю новую версию…");dialog.setIndeterminate(true);
        dialog.setCancelable(false);dialog.setButton(DialogInterface.BUTTON_NEGATIVE,"Скрыть",(d,w)->d.dismiss());dialog.show();
        run(activity,dialog);
    }
    private static boolean live(Activity a) {return !a.isFinishing() && !a.isDestroyed();}
    private static void run(Activity activity, ProgressDialog dialog) {
        if (!BUSY.compareAndSet(false,true)) {dialog.dismiss();return;}
        new Thread(() -> {
            File work=new File(activity.getCacheDir(),"airupdate-work");
            try {
                JSONObject release = new JSONObject(new String(download(RELEASE,1024*1024),java.nio.charset.StandardCharsets.UTF_8));
                if(release.optBoolean("draft") || release.optBoolean("prerelease"))throw new IOException("Не найден стабильный релиз");
                String tag=release.getString("tag_name");
                if(!tag.matches("v4\\.0-[0-9]+"))throw new IOException("Для этой версии film.mov нужен новый патч");
                int code=Integer.parseInt(tag.substring(tag.lastIndexOf('-')+1));
                PackageInfo installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);
                if(code<=installed.versionCode) {
                    MAIN.post(()->{if(live(activity)){dialog.dismiss();new AlertDialog.Builder(activity).setTitle(TITLE).setMessage("Уже установлена последняя версия: "+installed.versionName+" ("+installed.versionCode+") с патчем.").setPositiveButton("Хорошо",null).show();}});
                    return;
                }
                JSONObject apk=null;JSONArray assets=release.getJSONArray("assets");
                for(int i=0;i<assets.length();i++)if("filmmov.apk".equals(assets.getJSONObject(i).getString("name")))apk=assets.getJSONObject(i);
                if(apk==null)throw new IOException("В релизе нет filmmov.apk");
                String url=apk.getString("browser_download_url");
                if(!url.equals("https://github.com/gamahacka/filmmov/releases/download/"+tag+"/filmmov.apk"))throw new IOException("Неожиданный адрес APK");
                String digest=apk.getString("digest");
                if(!digest.matches("sha256:[0-9a-f]{64}"))throw new IOException("В релизе нет контрольной суммы APK");
                clear(work);work.mkdirs();
                progress(activity,dialog,"Скачиваю film.mov "+tag.substring(1)+"…");
                byte[] bytes=download(url,MAX_APK);
                if(!PatchEngine.hash(bytes).equals(digest.substring(7)))throw new IOException("Контрольная сумма APK не совпадает");
                File original=new File(work,"original.apk");java.nio.file.Files.write(original.toPath(),bytes);
                PackageInfo pkg=activity.getPackageManager().getPackageArchiveInfo(original.getPath(),0);
                if(pkg==null || !"mov.film".equals(pkg.packageName) || pkg.versionCode!=code)throw new IOException("Версия внутри APK не совпадает с релизом");
                File patched=PatchEngine.patch(original,new File(activity.getApplicationInfo().sourceDir),new File(work,"build"),s->progress(activity,dialog,s));
                MAIN.post(()->{
                    if(!live(activity))return;
                    dialog.dismiss();
                    new AlertDialog.Builder(activity).setTitle("film.mov "+tag.substring(1)+" готов")
                        .setMessage("Патчи применены и подпись проверена. Подтвердите обновление в Android.")
                        .setNegativeButton("Позже",null).setPositiveButton("Установить",(d,w)->offerInstall(activity,patched)).show();
                });
            } catch(Exception | LinkageError error) {
                android.util.Log.e("AirUpdate","Update failed",error);
                MAIN.post(()->{if(live(activity)){dialog.dismiss();new AlertDialog.Builder(activity).setTitle("Обновление не установлено").setMessage(error.getMessage()==null?error.toString():error.getMessage()).setPositiveButton("Хорошо",null).show();}});
            } finally {BUSY.set(false);}
        },"film-local-update").start();
    }
    private static void progress(Activity a,ProgressDialog dialog,String text){MAIN.post(()->{if(live(a))dialog.setMessage(text);});}
    private static byte[] download(String url,long limit) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","film.mov-AirUpdate");
        try {
            if(c.getResponseCode()!=200)throw new IOException("Сервер обновлений: HTTP "+c.getResponseCode());
            if(!"https".equals(c.getURL().getProtocol()))throw new IOException("Небезопасный адрес обновления");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[65536];int n;
                while((n=in.read(b))!=-1){if(out.size()+(long)n>limit)throw new IOException("Слишком большой файл обновления");out.write(b,0,n);}
                return out.toByteArray();
            }
        } finally {c.disconnect();}
    }
    private static void offerInstall(Activity a,File apk) {
        if(!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("Разрешите обновления film.mov")
                .setMessage("В настройках Android включите установку из этого источника, затем вернитесь и нажмите «Установить».")
                .setNegativeButton("Позже",null).setPositiveButton("Настройки",(d,w)->{
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));
                    new AlertDialog.Builder(a).setTitle("Обновление готово").setPositiveButton("Установить",(dd,ww)->offerInstall(a,apk)).setNegativeButton("Позже",null).show();
                }).show();return;
        }
        try { install(a,apk); }catch(Exception e){new AlertDialog.Builder(a).setTitle("Не удалось установить").setMessage(e.getMessage()).setPositiveButton("Хорошо",null).show();}
    }
    private static void install(Context context,File apk) throws Exception {
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName("mov.film");params.setSize(apk.length());
        int id=installer.createSession(params);
        try(PackageInstaller.Session session=installer.openSession(id)) {
            try(InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite("base.apk",0,apk.length())) {
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);session.fsync(out);
            }
            // Reuse film.mov's existing install callback, including the Android confirmation UI.
            Class<?> update=Class.forName("mov.film.Update");
            Method listen=update.getDeclaredMethod("listen",Context.class);listen.setAccessible(true);listen.invoke(null,context);
            Method confirm=update.getDeclaredMethod("confirm",Context.class,int.class);confirm.setAccessible(true);
            PendingIntent callback=(PendingIntent)confirm.invoke(null,context,id);
            session.commit(callback.getIntentSender());
        }catch(Exception e){installer.abandonSession(id);throw e;}
    }
    /** Standard film.mov install actions also pass through the local patcher. */
    public static String nativeInstall(Context context,Object found,Object ignored) {
        if(!BUSY.compareAndSet(false,true))return "Обновление уже готовится";
        try {
            String url=(String)found.getClass().getField("apk").get(found);
            String expected=(String)found.getClass().getField("sha256").get(found);
            if(!url.startsWith("https://"))throw new IOException("Нужен HTTPS для обновления");
            byte[] bytes=download(url,MAX_APK);
            if(expected==null || !expected.matches("[0-9a-fA-F]{64}") || !PatchEngine.hash(bytes).equalsIgnoreCase(expected))throw new IOException("Контрольная сумма APK не совпадает");
            File work=new File(context.getCacheDir(),"airupdate-native");clear(work);work.mkdirs();
            File source=new File(work,"original.apk");java.nio.file.Files.write(source.toPath(),bytes);
            PackageInfo candidate=context.getPackageManager().getPackageArchiveInfo(source.getPath(),0);
            if(candidate==null || !"mov.film".equals(candidate.packageName) || candidate.versionCode<=context.getPackageManager().getPackageInfo("mov.film",0).versionCode)throw new IOException("Неверная версия APK");
            File ready=PatchEngine.patch(source,new File(context.getApplicationInfo().sourceDir),new File(work,"build"),s->android.util.Log.i("AirUpdate",s));
            install(context,ready);return "";
        }catch(Exception | LinkageError e){android.util.Log.e("AirUpdate","Native update failed",e);return e.getMessage()==null?e.toString():e.getMessage();}
        finally{BUSY.set(false);}
    }
    private static void clear(File f) throws IOException {
        File[] children=f.listFiles();if(children!=null)for(File child:children)clear(child);
        if(f.exists()&&!f.delete())throw new IOException("Не удалось очистить временные файлы");
    }
}
