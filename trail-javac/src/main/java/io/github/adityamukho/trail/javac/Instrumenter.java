package io.github.adityamukho.trail.javac;

import com.sun.source.tree.LineMap;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCConditional;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCLabeledStatement;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMemberReference;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSwitch;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.tree.JCTree.JCTypeCast;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites the traced methods of one compilation unit.
 *
 * <p>For a traced method
 * <pre>{@code
 * int quote(Order order) {
 *     discount: if (order.tier() == GOLD && total > 100) { ... } else { ... }
 * }
 * }</pre>
 * the plugin produces the equivalent of
 * <pre>{@code
 * int quote(Order order) {
 *     final Frame __trail_frame__ = Decisions.enter("<site>", order);
 *     try {
 *         discount: if (order.tier() == GOLD && total > 100) {
 *             Decisions.yes("<site>", order, total); ...
 *         } else {
 *             Decisions.no("<site>", order, total); ...
 *         }
 *     } catch (final Throwable __trail_ex__) {
 *         __trail_frame__.fail(__trail_ex__);
 *         throw __trail_ex__;
 *     } finally {
 *         __trail_frame__.exit();
 *     }
 * }
 * }</pre>
 *
 * <p>The condition itself is never touched. Wrapping it in a call would break Java's flow
 * scoping: pattern variables ({@code if (!(o instanceof Foo f)) return; use(f);}) and definite
 * assignment both depend on the condition being a direct operand of the {@code if}. Recording at
 * the start of each arm keeps both intact, and it reads the values after the condition has run.
 */
final class Instrumenter {
    /** Must match {@code Site} in trail-runtime. */
    private static final char SEP = '\u0001';
    private static final String DECISIONS = "io.github.adityamukho.trail.Decisions";
    private static final String FRAME = "io.github.adityamukho.trail.Frame";
    private static final int MAX_TEXT = 300;

    record Result(int methods, int branches) {
    }

    private final TreeMaker make;
    private final Names names;
    private final PluginOptions options;
    private final JCCompilationUnit cu;
    private final String file;
    private final LineMap lines;
    private final String pkg;
    private int methods;
    private int branches;

    Instrumenter(Context context, PluginOptions options, JCCompilationUnit cu, String file) {
        this.make = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.options = options;
        this.cu = cu;
        this.file = file;
        this.lines = cu.getLineMap();
        this.pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
    }

    Result run() {
        boolean pkgSelected = options.packageSelected(pkg);
        for (JCTree def : cu.defs) {
            if (def instanceof JCClassDecl c) {
                visitClass(c, pkg.isEmpty() ? c.name.toString() : pkg + "." + c.name, pkgSelected);
            }
        }
        return new Result(methods, branches);
    }

    private void visitClass(JCClassDecl c, String className, boolean pkgSelected) {
        boolean classTraced = hasAnnotation(c.mods, "TraceDecisions");
        for (JCTree member : c.defs) {
            if (member instanceof JCMethodDecl m) {
                visitMethod(m, className, classTraced, pkgSelected);
            } else if (member instanceof JCClassDecl nested) {
                visitClass(nested, className + "$" + nested.name, pkgSelected);
            }
        }
    }

    private void visitMethod(JCMethodDecl m, String className, boolean classTraced, boolean pkgSelected) {
        if (m.body == null || m.name == names.init) {
            return;
        }
        JCAnnotation run = annotation(m.mods, "DecisionRun");
        boolean annotated = run != null || classTraced || hasAnnotation(m.mods, "TraceDecisions");
        if (!annotated) {
            if (!pkgSelected || !containsDecision(m.body)) {
                return;
            }
        }
        String site = className + "#" + m.name + "(" + paramTypes(m) + ")";
        Set<String> redacted = redactedNames(m);

        BranchTranslator t = new BranchTranslator(site, redacted);
        m.body = t.translate(m.body);
        branches += t.count;
        methods++;

        make.at(m.body.pos);
        ListBuffer<JCExpression> args = new ListBuffer<>();
        List<String> paramNames = new ArrayList<>();
        for (JCVariableDecl p : m.params) {
            paramNames.add(p.name.toString());
        }
        String runName = run == null ? "" : runName(run, m.name.toString());
        String meta = meta(run != null ? "R" : "F", site, line(m.pos), paramNames, runName);
        args.append(make.Literal(meta));
        for (JCVariableDecl p : m.params) {
            args.append(asVararg(value(p.name.toString(), redacted), m.params.size()));
        }

        Name frame = names.fromString("__trail_frame__");
        Name ex = names.fromString("__trail_ex__");
        JCVariableDecl frameVar = make.VarDef(make.Modifiers(Flags.FINAL), frame, qualified(FRAME),
                call("enter", args.toList()));
        JCVariableDecl catchParam = make.VarDef(make.Modifiers(Flags.FINAL), ex, qualified("java.lang.Throwable"),
                null);
        JCBlock onError = make.Block(0, com.sun.tools.javac.util.List.of(
                make.Exec(make.Apply(com.sun.tools.javac.util.List.nil(),
                        make.Select(make.Ident(frame), names.fromString("fail")),
                        com.sun.tools.javac.util.List.of(make.Ident(ex)))),
                make.Throw(make.Ident(ex))));
        JCBlock onExit = make.Block(0, com.sun.tools.javac.util.List.of(
                make.Exec(make.Apply(com.sun.tools.javac.util.List.nil(),
                        make.Select(make.Ident(frame), names.fromString("exit")),
                        com.sun.tools.javac.util.List.nil()))));
        JCStatement guarded = make.Try(make.Block(0, m.body.stats),
                com.sun.tools.javac.util.List.of(make.Catch(catchParam, onError)), onExit);
        m.body.stats = com.sun.tools.javac.util.List.of(frameVar, guarded);
    }

    // ---------------------------------------------------------------- branch rewriting

    private final class BranchTranslator extends TreeTranslator {
        private final String site;
        private final Set<String> redacted;
        private int ifs;
        private int ternaries;
        private int switches;
        private String pendingLabel;
        int count;

        BranchTranslator(String site, Set<String> redacted) {
            this.site = site;
            this.redacted = redacted;
        }

        private String takeLabel() {
            String l = pendingLabel;
            pendingLabel = null;
            return l;
        }

        @Override
        public void visitLabelled(JCLabeledStatement tree) {
            if (tree.body instanceof JCIf || tree.body instanceof JCSwitch) {
                pendingLabel = tree.label.toString();
            }
            tree.body = translate(tree.body);
            result = tree;
        }

        @Override
        public void visitIf(JCIf tree) {
            String label = takeLabel();
            JCExpression cond = TreeInfo.skipParens(tree.cond);
            int ord = ++ifs; // counted even when skipped, so later ordinals stay stable
            if (cond instanceof JCLiteral) {
                super.visitIf(tree);
                return;
            }
            String id = site + "/if" + (label != null ? "[" + label + "]" : "#" + ord);
            String text = text(cond);
            List<String> captured = CaptureNames.of(cond);
            int line = line(tree.pos);

            tree.cond = translate(tree.cond);
            tree.thenpart = translate(tree.thenpart);
            tree.elsepart = translate(tree.elsepart);

            String meta = meta("I", id, line, captured, text);
            make.at(tree.pos);
            JCStatement yes = make.Exec(call("yes", args(meta, null, captured)));
            JCStatement no = make.Exec(call("no", args(meta, null, captured)));
            tree.thenpart = prepend(yes, tree.thenpart);
            tree.elsepart = tree.elsepart == null
                    ? make.Block(0, com.sun.tools.javac.util.List.of(no))
                    : prepend(no, tree.elsepart);
            count++;
            result = tree;
        }

        @Override
        public void visitConditional(JCConditional tree) {
            int ord = ++ternaries; // counted even when skipped, so later ordinals stay stable
            if (!options.ternary || unsafeOperand(tree.truepart) || unsafeOperand(tree.falsepart)
                    || TreeInfo.skipParens(tree.cond) instanceof JCLiteral) {
                super.visitConditional(tree);
                return;
            }
            String id = site + "/ternary#" + ord;
            JCExpression cond = TreeInfo.skipParens(tree.cond);
            String text = text(cond);
            List<String> captured = CaptureNames.of(cond);
            int line = line(tree.pos);

            tree.cond = translate(tree.cond);
            tree.truepart = translate(tree.truepart);
            tree.falsepart = translate(tree.falsepart);

            String meta = meta("T", id, line, captured, text);
            make.at(tree.pos);
            tree.truepart = call("tv", args(meta, tree.truepart, captured));
            tree.falsepart = call("fv", args(meta, tree.falsepart, captured));
            count++;
            result = tree;
        }

        @Override
        public void visitSwitch(JCSwitch tree) {
            String label = takeLabel();
            tree.selector = wrapSelector(tree.selector, label, tree.pos);
            tree.cases = translate(tree.cases);
            result = tree;
        }

        @Override
        public void visitSwitchExpression(JCSwitchExpression tree) {
            tree.selector = wrapSelector(tree.selector, null, tree.pos);
            tree.cases = translate(tree.cases);
            result = tree;
        }

        private JCExpression wrapSelector(JCExpression selector, String label, int pos) {
            int ord = ++switches;
            String id = site + "/switch" + (label != null ? "[" + label + "]" : "#" + ord);
            JCExpression sel = TreeInfo.skipParens(selector);
            String text = text(sel);
            boolean isRedacted = sel instanceof JCTree.JCIdent ident && redacted.contains(ident.name.toString());
            JCExpression translated = translate(selector);
            String meta = meta("S", id, line(pos), List.of(), text);
            make.at(pos);
            count++;
            return call(isRedacted ? "chooseRedacted" : "choose",
                    com.sun.tools.javac.util.List.of(make.Literal(meta), translated));
        }

        /** Nested and local classes are traced on their own, or not at all. */
        @Override
        public void visitClassDef(JCClassDecl tree) {
            result = tree;
        }

        @Override
        public void visitNewClass(JCNewClass tree) {
            tree.encl = translate(tree.encl);
            tree.args = translate(tree.args);
            result = tree;
        }

        private com.sun.tools.javac.util.List<JCExpression> args(String meta, JCExpression operand,
                                                                 List<String> captured) {
            ListBuffer<JCExpression> a = new ListBuffer<>();
            a.append(make.Literal(meta));
            if (operand != null) {
                a.append(operand);
            }
            for (String n : captured) {
                a.append(asVararg(value(n, redacted), captured.size()));
            }
            return a.toList();
        }
    }

    /**
     * Ternary operands whose type could change if passed through a generic method.
     * Numeric and char literals, and casts to primitives, take part in narrowing rules
     * ({@code short s = c ? (short) 1 : 2} is a short, but boxed operands would widen to int).
     * Lambdas and method references need a target type the generic call does not give them.
     * String, boolean and null literals are safe.
     */
    private static boolean unsafeOperand(JCExpression e) {
        JCExpression x = TreeInfo.skipParens(e);
        if (x instanceof JCLiteral lit) {
            return lit.value instanceof Number || lit.value instanceof Character
                    || lit.typetag == com.sun.tools.javac.code.TypeTag.CHAR;
        }
        if (x instanceof JCTypeCast cast) {
            return cast.clazz instanceof JCTree.JCPrimitiveTypeTree;
        }
        return x instanceof JCLambda || x instanceof JCMemberReference;
    }

    // ---------------------------------------------------------------- helpers

    private JCStatement prepend(JCStatement first, JCStatement body) {
        if (body instanceof JCBlock b) {
            b.stats = b.stats.prepend(first);
            return b;
        }
        return make.Block(0, com.sun.tools.javac.util.List.of(first, body));
    }

    private JCExpression value(String name, Set<String> redacted) {
        if (redacted.contains(name)) {
            return qualified(DECISIONS + ".REDACTED");
        }
        return make.Ident(names.fromString(name));
    }

    /**
     * A lone array argument would be taken as the whole varargs array. Casting it to Object
     * makes it one element, as intended.
     */
    private JCExpression asVararg(JCExpression v, int count) {
        return count == 1 ? make.TypeCast(qualified("java.lang.Object"), v) : v;
    }

    private JCExpression call(String method, com.sun.tools.javac.util.List<JCExpression> args) {
        return make.Apply(com.sun.tools.javac.util.List.nil(), qualified(DECISIONS + "." + method), args);
    }

    private JCExpression qualified(String dotted) {
        String[] parts = dotted.split("\\.");
        JCExpression e = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            e = make.Select(e, names.fromString(parts[i]));
        }
        return e;
    }

    private String meta(String kind, String id, int line, List<String> captured, String text) {
        return kind + SEP + id + SEP + file + SEP + line + SEP + String.join(",", captured) + SEP + text;
    }

    private int line(int pos) {
        return pos < 0 ? -1 : (int) lines.getLineNumber(pos);
    }

    private static String text(JCExpression e) {
        String s = e.toString().replace(SEP, ' ').replaceAll("\\s+", " ").trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "..." : s;
    }

    private static String paramTypes(JCMethodDecl m) {
        List<String> types = new ArrayList<>();
        for (JCVariableDecl p : m.params) {
            String t = p.vartype == null ? "?" : p.vartype.toString();
            types.add(stripGenerics(t));
        }
        return String.join(",", types);
    }

    private static String stripGenerics(String t) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (char c : t.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0 && !Character.isWhitespace(c)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String runName(JCAnnotation a, String fallback) {
        for (JCExpression arg : a.args) {
            JCExpression v = arg instanceof JCAssign as ? as.rhs : arg;
            if (v instanceof JCLiteral lit && lit.value instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return fallback;
    }

    private Set<String> redactedNames(JCMethodDecl m) {
        Set<String> out = new HashSet<>();
        for (JCVariableDecl p : m.params) {
            if (hasAnnotation(p.mods, "Redact")) {
                out.add(p.name.toString());
            }
        }
        m.body.accept(new TreeScanner() {
            @Override
            public void visitVarDef(JCVariableDecl v) {
                if (hasAnnotation(v.mods, "Redact")) {
                    out.add(v.name.toString());
                }
                super.visitVarDef(v);
            }

            @Override
            public void visitClassDef(JCClassDecl c) {
                // not part of this method
            }
        });
        return out;
    }

    private static boolean containsDecision(JCBlock body) {
        boolean[] found = {false};
        body.accept(new TreeScanner() {
            @Override
            public void visitIf(JCIf t) {
                found[0] = true;
            }

            @Override
            public void visitConditional(JCConditional t) {
                found[0] = true;
            }

            @Override
            public void visitSwitch(JCSwitch t) {
                found[0] = true;
            }

            @Override
            public void visitSwitchExpression(JCSwitchExpression t) {
                found[0] = true;
            }

            @Override
            public void visitClassDef(JCClassDecl c) {
                // not part of this method
            }
        });
        return found[0];
    }

    private static boolean hasAnnotation(JCModifiers mods, String simpleName) {
        return annotation(mods, simpleName) != null;
    }

    private static JCAnnotation annotation(JCModifiers mods, String simpleName) {
        if (mods == null) {
            return null;
        }
        for (JCAnnotation a : mods.annotations) {
            String n = a.annotationType.toString();
            if (n.equals(simpleName) || n.endsWith("." + simpleName)) {
                return a;
            }
        }
        return null;
    }
}
