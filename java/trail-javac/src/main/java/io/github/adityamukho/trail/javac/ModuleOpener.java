package io.github.adityamukho.trail.javac;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Opens the {@code jdk.compiler} packages the plugin needs to rewrite syntax trees.
 *
 * <p>Plugins run inside javac, in the unnamed module, and javac's tree classes are not exported.
 * Builds can pass {@code -J--add-exports=jdk.compiler/...=ALL-UNNAMED} for each package. When they
 * don't, this class does what Lombok does: it uses {@code sun.misc.Unsafe} to make
 * {@code Module.implAddOpens} callable, then opens the packages to itself. Newer JDKs print a
 * warning about the Unsafe call; the clean flags avoid it.
 */
final class ModuleOpener {
    private static final String[] PACKAGES = {
        "com.sun.tools.javac.api",
        "com.sun.tools.javac.code",
        "com.sun.tools.javac.file",
        "com.sun.tools.javac.tree",
        "com.sun.tools.javac.util",
    };

    private ModuleOpener() {
    }

    static void openCompilerInternals() {
        Module compiler = ModuleLayer.boot().findModule("jdk.compiler").orElse(null);
        if (compiler == null) {
            return;
        }
        Module self = ModuleOpener.class.getModule();
        boolean allOpen = true;
        for (String p : PACKAGES) {
            allOpen &= compiler.isExported(p, self);
        }
        if (allOpen) {
            return;
        }
        try {
            Method addOpens = Module.class.getDeclaredMethod("implAddOpens", String.class, Module.class);
            makeAccessible(addOpens);
            for (String p : PACKAGES) {
                addOpens.invoke(compiler, p, self);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(TrailPlugin.NAME + ": cannot open jdk.compiler internals. Pass "
                    + "-J--add-exports=jdk.compiler/com.sun.tools.javac.<pkg>=ALL-UNNAMED for the api, code, "
                    + "file, tree and util packages.", e);
        }
    }

    private static void makeAccessible(Method m) throws ReflectiveOperationException {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        Method offset = unsafe.getClass().getMethod("objectFieldOffset", Field.class);
        Method put = unsafe.getClass().getMethod("putBooleanVolatile", Object.class, long.class, boolean.class);
        long off = (long) offset.invoke(unsafe, Mirror.class.getDeclaredField("first"));
        put.invoke(unsafe, m, off, true);
    }

    /**
     * Has the same instance field layout as {@code java.lang.reflect.AccessibleObject}, whose first
     * field is {@code boolean override}. The offset of {@code first} is the offset of {@code override}.
     */
    @SuppressWarnings("unused")
    private static final class Mirror {
        boolean first;
        static final Object STATIC_OBJ = OutputStream.class;
        volatile Object second;
        private static volatile boolean staticSecond;
        private static volatile boolean staticThird;
    }
}
