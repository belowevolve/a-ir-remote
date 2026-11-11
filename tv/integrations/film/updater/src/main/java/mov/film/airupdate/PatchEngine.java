package mov.film.airupdate;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.value.Entry;
import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.Opcodes;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/** Local, fail-closed APK transformation. Never installs or edits application data. */
public final class PatchEngine {
    public static final String UPSTREAM = "9f37b040ce8ddb4ea47b5c4ec8b0fd452cc89f74082636e09cc5a921737ff1b0";
    public interface Progress { void at(String message); }
    private static final String ASSETS = "assets/airupdate/";

    public static byte[] bytes(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536]; int n;
            while ((n = input.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
    private static byte[] entry(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) throw new IOException("В APK отсутствует " + name);
        return bytes(z.getInputStream(e));
    }
    public static String hash(byte[] b) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256").digest(b)) result.append(String.format(Locale.ROOT, "%02x", v & 255));
        return result.toString();
    }
    public static String signer(File file) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(24).build().verify();
        if (!r.isVerified() || r.getSignerCertificates().size() != 1) throw new IOException("Не удалось проверить подпись APK");
        return hash(r.getSignerCertificates().get(0).getEncoded());
    }
    public static void decode(File apk, File dir) throws Exception {
        if (!dir.mkdirs()) throw new IOException("Не удалось создать рабочую папку");
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().matches("classes\\d*\\.dex")) continue;
                DexBackedDexFile dex = new DexBackedDexFile(Opcodes.forApi(28), entry(z, e.getName()));
                BaksmaliOptions options = new BaksmaliOptions();
                options.debugInfo = false;
                if (!Baksmali.disassembleDexFile(dex, dir, 2, options)) throw new IOException("Не удалось прочитать DEX");
            }
        }
    }
    private static String normalized(String s) {
        // Ignore debug positions and blank lines; retain every instruction, label and signature.
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\\r?\\n")) {
            boolean quoted = false, escaped = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') quoted = !quoted;
                if (c == '#' && !quoted) { line = line.substring(0, i); break; }
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(".source ") || line.startsWith(".line ")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }
    private static void code(File dir, JSONObject recipe, ZipFile template) throws Exception {
        JSONArray files = recipe.getJSONArray("code");
        for (int i = 0; i < files.length(); i++) {
            JSONObject change = files.getJSONObject(i);
            String name = change.getString("path");
            File target = new File(dir, name);
            if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new IOException("Invalid recipe path");
            String text = target.exists() ? normalized(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)) : "";
            if (change.has("added")) {
                if (target.exists()) throw incompatible(name);
                text = new String(entry(template, change.getString("added")), StandardCharsets.UTF_8);
            } else {
                JSONArray hunks = change.getJSONArray("hunks");
                for (int j = 0; j < hunks.length(); j++) {
                    JSONObject h = hunks.getJSONObject(j);
                    String before = h.getString("before"), after = h.getString("after");
                    int at = text.indexOf(before);
                    if (before.isEmpty() || at < 0 || text.indexOf(before, at + 1) >= 0) throw incompatible(name);
                    text = text.substring(0, at) + after + text.substring(at + before.length());
                }
            }
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
        }
    }
    private static IOException incompatible(String where) {
        return new IOException("Структура film.mov изменилась (" + where + "). Нужна новая версия патча. Установленное приложение сохранено.");
    }
    private static Map<String, Entry> entries(TableBlock table) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (PackageBlock pkg : table) {
            Iterator<com.reandroid.arsc.model.ResourceEntry> resources = pkg.getResources();
            while (resources.hasNext()) {
                for (Entry e : resources.next()) {
                    if (!e.isNull()) result.put(e.getResourceId() + ":" + e.getResConfig().getQualifiers(), e);
                }
            }
        }
        return result;
    }
    private static String value(Entry e) {
        return e == null ? "" : e.getName() + ":" + e.toJson().toString();
    }
    private static byte[] resources(ZipFile source, ZipFile template) throws Exception {
        TableBlock original = TableBlock.load(new ByteArrayInputStream(entry(template, "original.arsc")));
        TableBlock patched = TableBlock.load(new ByteArrayInputStream(entry(template, "patched.arsc")));
        TableBlock current = TableBlock.load(new ByteArrayInputStream(entry(source, "resources.arsc")));
        Map<String, Entry> old = entries(original), updates = entries(patched), actual = entries(current);
        // Resource IDs embedded in DEX and drawables must still name the same resources.
        for (Map.Entry<String, Entry> item : old.entrySet()) {
            Entry now = actual.get(item.getKey()), was = item.getValue();
            if (now == null || !was.getName().equals(now.getName()) || !was.getTypeName().equals(now.getTypeName())) throw incompatible("resource IDs");
        }
        for (Map.Entry<String, Entry> item : updates.entrySet()) {
            Entry before = old.get(item.getKey()), after = item.getValue(), now = actual.get(item.getKey());
            if (value(before).equals(value(after))) continue;
            if (!value(before).equals(value(now))) throw incompatible("resource " + after.getName());
            PackageBlock pkg = current.getPackageBlockById(after.getPackageBlock().getId());
            Entry dest = pkg.getOrCreateEntry((byte) after.getTypeId(), (short) after.getId(), after.getResConfig());
            // New resource types are added at their reviewed IDs; never remap application references.
            pkg.getOrCreateTypeString(after.getTypeId(), after.getTypeName());
            dest.merge(after);
        }
        current.refreshFull();
        return current.getBytes();
    }
    private static byte[] manifest(ZipFile source, ZipFile template) throws Exception {
        AndroidManifestBlock before = AndroidManifestBlock.load(new ByteArrayInputStream(entry(template, "original.xml")));
        AndroidManifestBlock now = AndroidManifestBlock.load(new ByteArrayInputStream(entry(source, "AndroidManifest.xml")));
        int code = now.getVersionCode(); String name = now.getVersionName();
        now.setVersionCode(before.getVersionCode()); now.setVersionName(before.getVersionName());
        if (!now.toJson().toString().equals(before.toJson().toString())) throw incompatible("AndroidManifest.xml");
        AndroidManifestBlock after = AndroidManifestBlock.load(new ByteArrayInputStream(entry(template, "patched.xml")));
        after.setVersionCode(code); after.setVersionName(name); after.refresh();
        return after.getBytes();
    }
    private static boolean signature(String n) {
        return n.equals("META-INF/MANIFEST.MF") || n.matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
    }
    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b,int o,int n) throws IOException { out.write(b,o,n);count+=n; }
    }
    private static void put(ZipOutputStream out, Counting count, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name); e.setTime(315532800000L);
        if (stored) {
            CRC32 crc = new CRC32(); crc.update(data); e.setMethod(ZipEntry.STORED); e.setSize(data.length); e.setCrc(crc.getValue());
            int align = name.endsWith(".so") ? 16384 : 4;
            // DOS epoch adds no timestamp extra. The custom extra field supplies alignment padding.
            int padding = (int)((align - (count.count + 30 + name.getBytes(StandardCharsets.UTF_8).length + 4) % align) % align);
            byte[] extra = new byte[4 + padding]; extra[0] = 0x35; extra[1] = (byte)0xd9;
            extra[2] = (byte)padding; extra[3] = (byte)(padding >> 8); e.setExtra(extra);
        }
        out.putNextEntry(e); out.write(data); out.closeEntry();
    }
    public static File patch(File source, File self, File work, Progress progress) throws Exception {
        if (!UPSTREAM.equals(signer(source))) throw new IOException("Подпись автора film.mov не совпадает");
        work.mkdirs(); File tree = new File(work, "smali");
        progress.at("Применяю патчи оформления и интеграции…");
        decode(source, tree);
        File unsigned = new File(work, "unsigned.apk"), result = new File(work, "ready.apk");
        try (ZipFile app = new ZipFile(self)) {
            File recipeFile = new File(work, "recipe.zip");
            Files.write(recipeFile.toPath(), entry(app, ASSETS + "recipe.zip"));
            try (ZipFile template = new ZipFile(recipeFile); ZipFile input = new ZipFile(source)) {
                JSONObject recipe = new JSONObject(new String(entry(template, "recipe.json"), StandardCharsets.UTF_8));
                code(tree, recipe, template);
                SmaliOptions options = new SmaliOptions(); options.apiLevel=28; options.jobs=2;
                options.outputDexFile = new File(work, "classes.dex").getPath();
                if (!Smali.assemble(options, tree.getPath())) throw new IOException("Не удалось собрать DEX");
                byte[] manifest = manifest(input, template), resources = resources(input, template);
                Map<String, byte[]> replacements = new HashMap<>();
                JSONArray files = recipe.getJSONArray("files");
                for (int i=0;i<files.length();i++) {
                    JSONObject change=files.getJSONObject(i); String n=change.getString("path");
                    ZipEntry existing=input.getEntry(n);
                    String actual=existing==null ? "" : hash(entry(input,n));
                    if (!actual.equals(change.getString("before"))) throw incompatible(n);
                    replacements.put(n,entry(template,"files/"+n));
                }
                try (Counting count = new Counting(new FileOutputStream(unsigned)); ZipOutputStream out = new ZipOutputStream(count)) {
                    Set<String> written=new HashSet<>();
                    Enumeration<? extends ZipEntry> all=input.entries();
                    while(all.hasMoreElements()) {
                        ZipEntry e=all.nextElement();String n=e.getName();
                        if(e.isDirectory() || signature(n) || n.matches("classes\\d*\\.dex")) continue;
                        byte[] data=n.equals("AndroidManifest.xml")?manifest:n.equals("resources.arsc")?resources:replacements.containsKey(n)?replacements.get(n):entry(input,n);
                        put(out,count,n,data,n.equals("resources.arsc") || e.getMethod()==ZipEntry.STORED);written.add(n);
                    }
                    for(Map.Entry<String,byte[]> e:replacements.entrySet()) if(!written.contains(e.getKey())) put(out,count,e.getKey(),e.getValue(),false);
                    put(out,count,"classes.dex",Files.readAllBytes(new File(work,"classes.dex").toPath()),true);
                    put(out,count,"classes2.dex",entry(app,ASSETS+"runtime.dex"),true);
                    Enumeration<? extends ZipEntry> assets=app.entries();
                    while(assets.hasMoreElements()) { ZipEntry e=assets.nextElement();if(!e.isDirectory()&&e.getName().startsWith(ASSETS))put(out,count,e.getName(),entry(app,e.getName()),false); }
                }
            }
            progress.at("Подписываю и проверяю обновление…");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(entry(app,ASSETS+"signer.pk8")));
            X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(entry(app,ASSETS+"signer.der")));
            ApkSigner.SignerConfig config=new ApkSigner.SignerConfig.Builder("air",key,Collections.singletonList(cert)).build();
            new ApkSigner.Builder(Collections.singletonList(config)).setInputApk(unsigned).setOutputApk(result).setMinSdkVersion(24).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).setOtherSignersSignaturesPreserved(false).build().sign();
        }
        if(!signer(result).equals(signer(self))) throw new IOException("Подпись обновления не совпадает с установленной");
        return result;
    }
    /** Host tooling shares the exact decoder used on the device. */
    public static void main(String[] args) throws Exception {
        if(args[0].equals("decode")) decode(new File(args[1]),new File(args[2]));
        else if(args[0].equals("patch")) System.out.println(patch(new File(args[1]),new File(args[2]),new File(args[3]),System.out::println));
        else if(args[0].equals("key")) {
            if(args.length!=5)throw new IllegalArgumentException("key requires keystore, directory, alias, password file");
            char[] password=new String(Files.readAllBytes(new File(args[4]).toPath()),StandardCharsets.UTF_8).trim().toCharArray();
            KeyStore ks=KeyStore.getInstance(args[1].endsWith(".p12")?"PKCS12":"JKS");try(InputStream in=new FileInputStream(args[1])){ks.load(in,password);}
            File dir=new File(args[2]);dir.mkdirs();
            Files.write(new File(dir,"signer.pk8").toPath(),ks.getKey(args[3],password).getEncoded());
            Files.write(new File(dir,"signer.der").toPath(),ks.getCertificate(args[3]).getEncoded());
            Arrays.fill(password,'\0');
        }
    }
}
