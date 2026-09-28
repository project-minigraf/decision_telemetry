package io.github.adityamukho.trail.javac;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the variables a condition reads, from syntax alone (the plugin runs before type
 * attribution, so it cannot ask what a name refers to).
 *
 * <p>The generated code reads these names again right after the condition, so the rules lean
 * towards leaving a name out when it might not be a readable, definitely assigned variable:
 * <ul>
 *   <li>method names, type names ({@code Tier.GOLD}, {@code java.util.Objects}), and the type part
 *       of casts, {@code instanceof} and {@code new} are skipped;</li>
 *   <li>pattern variables ({@code o instanceof Foo f}) are skipped, since they are not always in
 *       scope where the value is read;</li>
 *   <li>variables assigned inside the condition are skipped, since they may not be assigned on
 *       the path where the value is read;</li>
 *   <li>lambdas, method references, nested switches and class bodies are not searched.</li>
 * </ul>
 * For {@code order.total} the variable is {@code order}.
 */
final class CaptureNames extends TreeScanner<Void, Void> {
    static final int MAX = 8;

    private final Set<String> found = new LinkedHashSet<>();
    private final Set<String> excluded = new LinkedHashSet<>();

    static List<String> of(Tree condition) {
        CaptureNames s = new CaptureNames();
        condition.accept(s, null);
        List<String> out = new ArrayList<>();
        for (String n : s.found) {
            if (!s.excluded.contains(n) && out.size() < MAX) {
                out.add(n);
            }
        }
        return out;
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void p) {
        String n = node.getName().toString();
        if (!n.equals("this") && !n.equals("super")) {
            found.add(n);
        }
        return null;
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void p) {
        captureRootOf(node);
        return null;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree ms) {
            ExpressionTree receiver = ms.getExpression();
            if (receiver instanceof IdentifierTree id) {
                captureName(id.getName().toString());
            } else if (receiver instanceof MemberSelectTree rs) {
                captureRootOf(rs);
            } else {
                receiver.accept(this, p);
            }
        }
        for (ExpressionTree arg : node.getArguments()) {
            arg.accept(this, p);
        }
        return null;
    }

    /**
     * For {@code order.customer.tier} captures {@code order}. Captures nothing when any part looks
     * like a type, as in {@code Tier.GOLD} or {@code java.util.Objects}; a variable used through a
     * capitalised member is lost, which is the safe side.
     */
    private void captureRootOf(MemberSelectTree node) {
        ExpressionTree e = node;
        while (e instanceof MemberSelectTree ms) {
            if (looksLikeType(ms.getIdentifier().toString())) {
                return;
            }
            e = ms.getExpression();
        }
        if (e instanceof IdentifierTree root) {
            captureName(root.getName().toString());
        } else {
            e.accept(this, null);
        }
    }

    /** A receiver or select root: skipped when it looks like a type name. */
    private void captureName(String name) {
        if (!looksLikeType(name) && !name.equals("this") && !name.equals("super")) {
            found.add(name);
        }
    }

    @Override
    public Void visitInstanceOf(InstanceOfTree node, Void p) {
        node.getExpression().accept(this, p);
        Tree pattern = patternOf(node);
        if (pattern != null) {
            pattern.accept(new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree v, Void q) {
                    excluded.add(v.getName().toString());
                    return null;
                }
            }, null);
        }
        return null;
    }

    /** {@code getPattern()} exists from JDK 16. Looked up reflectively so older javac still works. */
    private static Tree patternOf(InstanceOfTree node) {
        try {
            return (Tree) InstanceOfTree.class.getMethod("getPattern").invoke(node);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public Void visitTypeCast(TypeCastTree node, Void p) {
        return node.getExpression().accept(this, p);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void p) {
        if (node.getEnclosingExpression() != null) {
            node.getEnclosingExpression().accept(this, p);
        }
        for (ExpressionTree a : node.getArguments()) {
            a.accept(this, p);
        }
        return null;
    }

    @Override
    public Void visitNewArray(NewArrayTree node, Void p) {
        if (node.getDimensions() != null) {
            for (ExpressionTree d : node.getDimensions()) {
                d.accept(this, p);
            }
        }
        if (node.getInitializers() != null) {
            for (ExpressionTree i : node.getInitializers()) {
                i.accept(this, p);
            }
        }
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void p) {
        excludeTarget(node.getVariable());
        return node.getExpression().accept(this, p);
    }

    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void p) {
        excludeTarget(node.getVariable());
        return node.getExpression().accept(this, p);
    }

    private void excludeTarget(ExpressionTree target) {
        if (target instanceof IdentifierTree id) {
            excluded.add(id.getName().toString());
        }
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void p) {
        return null;
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree node, Void p) {
        return null;
    }

    @Override
    public Void visitClass(ClassTree node, Void p) {
        return null;
    }

    @Override
    public Void visitOther(Tree node, Void p) {
        return null;
    }

    @Override
    public Void scan(Tree tree, Void p) {
        if (tree != null && tree.getKind().name().equals("SWITCH_EXPRESSION")) {
            return null;
        }
        return super.scan(tree, p);
    }

    /**
     * Java convention: types start with a capital letter. In select and receiver position that
     * includes all-caps names such as {@code UUID}; a constant used as a receiver is skipped.
     */
    private static boolean looksLikeType(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }
}
