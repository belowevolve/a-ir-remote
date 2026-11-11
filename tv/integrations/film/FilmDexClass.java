import java.io.File;
import java.util.*;
import org.jf.baksmali.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.writer.pool.DexPool;

/** Surgical replacement: all other class definitions and APK resources remain intact. */
public final class FilmDexClass {
    static final String TYPE = "Lmov/film/PlayerActivity;";
    public static void main(String[] args) throws Exception {
        DexFile original = DexFileFactory.loadDexFile(new File(args[1]), Opcodes.forApi(28));
        if (args[0].equals("decode")) {
            BaksmaliOptions options = new BaksmaliOptions(); options.debugInfo = false;
            if (!Baksmali.disassembleDexFile(original, new File(args[2]), 1, options, Collections.singletonList(TYPE)))
                throw new IllegalStateException("Could not decode player");
        } else if (args[0].equals("media")) {
            ClassDef replacement = null;
            if (!args[2].equals("-")) {
                DexFile patch = DexFileFactory.loadDexFile(new File(args[2]), Opcodes.forApi(28));
                if (patch.getClasses().size() != 1) throw new IllegalStateException("Expected one player");
                replacement = patch.getClasses().iterator().next();
                if (!replacement.getType().equals(TYPE)) throw new IllegalStateException("Unexpected class");
            }
            List<ClassDef> result = new ArrayList<>(); int replaced = 0;
            for (ClassDef item : original.getClasses()) {
                if (item.getType().startsWith("Lmov/film/airvoice/FilmMediaSession")) continue;
                if (item.getType().equals(TYPE) && replacement != null) { result.add(replacement); replaced++; }
                else result.add(item);
            }
            if (replacement != null && replaced != 1) throw new IllegalStateException("Player missing");
            DexPool.writeTo(args[3], new ImmutableDexFile(original.getOpcodes(), result));
        } else {
            DexFile patch = DexFileFactory.loadDexFile(new File(args[2]), Opcodes.forApi(28));
            if (patch.getClasses().size() != 1) throw new IllegalStateException("Unexpected patched classes");
            ClassDef replacement = patch.getClasses().iterator().next();
            if (!replacement.getType().equals(TYPE)) throw new IllegalStateException("Unexpected player type");
            List<ClassDef> result = new ArrayList<>(); int replaced = 0;
            for (ClassDef item : original.getClasses()) {
                if (item.getType().equals(TYPE)) { result.add(replacement); replaced++; }
                else result.add(item);
            }
            if (replaced != 1) throw new IllegalStateException("Player class missing or duplicated");
            DexPool.writeTo(args[3], new ImmutableDexFile(original.getOpcodes(), result));
        }
    }
}
