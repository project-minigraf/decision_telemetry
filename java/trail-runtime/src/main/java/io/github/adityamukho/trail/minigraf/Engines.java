package io.github.adityamukho.trail.minigraf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Opens Minigraf databases.
 *
 * <p>By default this uses {@code io.github.project-minigraf:minigraf-jvm} through reflection. Set the
 * system property {@code trail.minigraf.engineFactory} to a class with a public static
 * {@code DatalogEngine open(String path)} method to use something else, such as a test double.
 */
public final class Engines {
    static final String MINIGRAF_CLASS = "uniffi.minigraf_ffi.MiniGrafDb";
    private static final String FACTORY_PROPERTY = "trail.minigraf.engineFactory";

    private Engines() {
    }

    /** True if a Minigraf engine can be opened in this JVM. */
    public static boolean available() {
        if (System.getProperty(FACTORY_PROPERTY) != null) {
            return true;
        }
        try {
            Class.forName(MINIGRAF_CLASS, false, Engines.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Opens a file-backed database, or an in-memory one when {@code path} is null. */
    public static DatalogEngine open(String path) {
        String factory = System.getProperty(FACTORY_PROPERTY);
        if (factory != null) {
            try {
                Method m = Class.forName(factory).getMethod("open", String.class);
                return (DatalogEngine) m.invoke(null, path);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot use engine factory " + factory, e);
            }
        }
        return Reflective.open(path);
    }

    static RuntimeException unwrap(InvocationTargetException e) {
        Throwable c = e.getCause();
        if (c instanceof RuntimeException r) {
            return r;
        }
        if (c instanceof Error err) {
            throw err;
        }
        return new IllegalStateException(c);
    }

    /** Calls the UniFFI-generated Kotlin class without a compile-time dependency on it. */
    private static final class Reflective implements DatalogEngine {
        private final Object db;
        private final Method execute;
        private final Method checkpoint;
        private final Method close;

        private Reflective(Object db) throws NoSuchMethodException {
            this.db = db;
            Class<?> c = db.getClass();
            execute = c.getMethod("execute", String.class);
            checkpoint = findOptional(c, "checkpoint");
            Method cl = findOptional(c, "close");
            close = cl != null ? cl : findOptional(c, "destroy");
        }

        static DatalogEngine open(String path) {
            try {
                Class<?> cls = Class.forName(MINIGRAF_CLASS);
                Object db = path == null ? call(cls, "openInMemory") : call(cls, "open", path);
                return new Reflective(db);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "minigraf-jvm is not on the classpath. Add io.github.project-minigraf:minigraf-jvm, "
                                + "or set trail.sink=jsonl.", e);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported minigraf-jvm version", e);
            }
        }

        /** Kotlin companion functions are instance methods on {@code Companion} unless @JvmStatic. */
        private static Object call(Class<?> cls, String name, Object... args) throws ReflectiveOperationException {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = String.class;
            }
            try {
                Method m = cls.getMethod(name, types);
                if (Modifier.isStatic(m.getModifiers())) {
                    return m.invoke(null, args);
                }
            } catch (NoSuchMethodException ignored) {
                // try the companion object
            }
            Object companion = cls.getField("Companion").get(null);
            return companion.getClass().getMethod(name, types).invoke(companion, args);
        }

        private static Method findOptional(Class<?> c, String name) {
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }

        @Override
        public String execute(String datalog) {
            try {
                return (String) execute.invoke(db, datalog);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void checkpoint() {
            if (checkpoint == null) {
                return;
            }
            try {
                checkpoint.invoke(db);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            if (close == null) {
                return;
            }
            try {
                close.invoke(db);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
