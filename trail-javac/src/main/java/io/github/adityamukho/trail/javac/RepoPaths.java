package io.github.adityamukho.trail.javac;

import com.sun.source.tree.CompilationUnitTree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Source paths as a repository sees them, such as {@code src/main/java/com/acme/Pricing.java}.
 *
 * <p>IDE plugins and temporal_reasoning both key code by repo-relative path. The repo root is the
 * nearest directory above the source file that holds {@code .git}. Without one, the path falls
 * back to the package path, such as {@code com/acme/Pricing.java}.
 */
final class RepoPaths {
    private final Map<Path, Path> rootByDir = new HashMap<>();

    String relativePath(CompilationUnitTree cu) {
        String simple = simpleName(cu.getSourceFile().getName());
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        String fallback = pkg.isEmpty() ? simple : pkg.replace('.', '/') + "/" + simple;
        try {
            Path file = Path.of(cu.getSourceFile().toUri()).toAbsolutePath().normalize();
            Path root = rootFor(file.getParent());
            if (root == null) {
                return fallback;
            }
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Path rootFor(Path dir) {
        if (dir == null) {
            return null;
        }
        if (rootByDir.containsKey(dir)) {
            return rootByDir.get(dir);
        }
        Path root = Files.exists(dir.resolve(".git")) ? dir : rootFor(dir.getParent());
        rootByDir.put(dir, root);
        return root;
    }

    private static String simpleName(String name) {
        int i = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return i < 0 ? name : name.substring(i + 1);
    }
}
