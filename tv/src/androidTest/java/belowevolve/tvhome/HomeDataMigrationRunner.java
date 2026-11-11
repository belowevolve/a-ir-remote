package belowevolve.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Build;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Explicit local signer migration only. Never included in the launcher APK. */
public final class HomeDataMigrationRunner extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (args == null || !"true".equals(args.getString("keyMigration")) ||
                    !"belowevolve.tvhome".equals(getTargetContext().getPackageName())) throw new SecurityException("Explicit Air Home migration required");
            File data = getTargetContext().getDataDir();
            File zip = new File(getTargetContext().getExternalFilesDir("key-migration"), "home-data.zip");
            String mode = args.getString("mode");
            int count = 0;
            if ("backup".equals(mode)) {
                if ("true".equals(args.getString("fixture"))) {
                    if (!Build.HARDWARE.contains("ranchu") && !Build.FINGERPRINT.contains("generic")) throw new SecurityException("Fixture requires emulator");
                    getTargetContext().getSharedPreferences("migration-regression", 0).edit().putString("value", "kept").commit();
                    Files.write(new File(getTargetContext().getFilesDir(), "migration-fixture").toPath(), new byte[]{1, 3, 5});
                }
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                    for (String name : new String[]{"shared_prefs", "files", "no_backup"}) count += backup(data, new File(data, name), out);
                }
            } else if ("restore".equals(mode)) {
                if (!digest(zip).equals(args.getString("sha256"))) throw new SecurityException("Backup digest mismatch");
                try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
                    ZipEntry entry;
                    while ((entry = in.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (!name.startsWith("shared_prefs/") && !name.startsWith("files/") && !name.startsWith("no_backup/")) throw new SecurityException("Unexpected backup path");
                        File target = new File(data, name);
                        if (!target.getCanonicalPath().startsWith(data.getCanonicalPath() + File.separator)) throw new SecurityException("Backup traversal");
                        if (entry.isDirectory()) throw new IOException("Unexpected directory entry");
                        Files.createDirectories(target.getParentFile().toPath());
                        try (FileOutputStream out = new FileOutputStream(target)) { in.transferTo(out); }
                        count++;
                    }
                }
                verify(data, zip);
            } else if ("verifyFixture".equals(mode)) {
                if (!"kept".equals(getTargetContext().getSharedPreferences("migration-regression", 0).getString("value", "")) ||
                        !Arrays.equals(new byte[]{1, 3, 5}, Files.readAllBytes(new File(getTargetContext().getFilesDir(), "migration-fixture").toPath()))) throw new IOException("Fixture data lost");
            } else throw new IllegalArgumentException("Unknown migration mode");
            result.putString("stream", mode + ": PASS; files=" + count + "; SHA256=" + digest(zip) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private int backup(File root, File file, ZipOutputStream out) throws IOException {
        if (!file.exists()) return 0;
        if (file.isDirectory()) {
            int count = 0; File[] children = file.listFiles();
            if (children == null) throw new IOException("Unreadable backup directory");
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) count += backup(root, child, out);
            return count;
        }
        out.putNextEntry(new ZipEntry(root.toPath().relativize(file.toPath()).toString()));
        Files.copy(file.toPath(), out); out.closeEntry(); return 1;
    }
    private void verify(File data, File zip) throws Exception {
        try (ZipFile input = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream in = input.getInputStream(entry)) {
                    if (!Arrays.equals(in.readAllBytes(), Files.readAllBytes(new File(data, entry.getName()).toPath()))) throw new IOException("Restored data differs from backup");
                }
            }
        }
    }
    private String digest(File file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : sha.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
