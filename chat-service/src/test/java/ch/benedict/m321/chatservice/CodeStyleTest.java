package ch.benedict.m321.chatservice;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft authored Java-Quellen mit dem JDK-Parser statt Kommentarlücken durch Regex zu erraten. */
class CodeStyleTest {

    /** Klassen und Methoden brauchen Zweckkommentare; Java-Streams sind gemäss Auftrag verboten. */
    @Test
    void documentsClassesAndMethodsWithoutJavaStreams() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not just a JRE");
        List<File> files = new ArrayList<>();
        collect(new File("src/main/java"), files);
        collect(new File("src/test/java"), files);
        assertFalse(files.isEmpty());
        List<String> violations = new ArrayList<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = manager.getJavaFileObjectsFromFiles(files);
            List<String> options = List.of("-proc:none", "--release", "21");
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, options, null, sources);
            DocTrees comments = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                SourceCheck check = new SourceCheck(comments, unit, violations);
                check.scan(unit, null);
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** Rekursive Dateisuche bleibt selbst ohne die verbotene Stream-API lesbar. */
    private void collect(File directory, List<File> files) {
        File[] children = directory.listFiles();
        assertNotNull(children, "Source directory missing: " + directory);
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, files);
            } else if (child.getName().endsWith(".java")) {
                files.add(child);
            }
        }
    }

    /** Der Syntaxbaum unterscheidet echte Aufrufe von Wörtern in Kommentaren und Zeichenfolgen. */
    private static class SourceCheck extends TreePathScanner<Void, Void> {
        private final DocTrees comments;
        private final CompilationUnitTree unit;
        private final List<String> violations;

        /** Jede Meldung behält ihre Datei, damit sie ohne Suche behoben werden kann. */
        SourceCheck(DocTrees comments, CompilationUnitTree unit, List<String> violations) {
            this.comments = comments;
            this.unit = unit;
            this.violations = violations;
        }

        /** Anonyme Typmarkierungen besitzen keinen eigenen deklarierten Klassennamen. */
        @Override
        public Void visitClass(ClassTree node, Void unused) {
            if (!node.getSimpleName().toString().isEmpty()) {
                requireComment("class " + node.getSimpleName());
            }
            return super.visitClass(node, unused);
        }

        /** Nur geschriebene Methoden und Konstruktoren werden geprüft, keine Lombok-Erzeugnisse. */
        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            requireComment("method " + node.getName());
            return super.visitMethod(node, unused);
        }

        /** Direkte sowie statische Imports der Stream-Pakete sind unzulässig. */
        @Override
        public Void visitImport(ImportTree node, Void unused) {
            String imported = node.getQualifiedIdentifier().toString();
            if (imported.startsWith("java.util.stream.")) {
                report("Java Stream import");
            }
            return super.visitImport(node, unused);
        }

        /** Auch voll qualifizierte Verweise dürfen die Import-Prüfung nicht umgehen. */
        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (node.toString().startsWith("java.util.stream.")) {
                report("Qualified Java Stream reference");
            }
            return super.visitMemberSelect(node, unused);
        }

        /** Collection.stream und parallelStream sind auch ohne expliziten Stream-Import verboten. */
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (node.getMethodSelect() instanceof MemberSelectTree selected) {
                String method = selected.getIdentifier().toString();
                if (method.equals("stream") || method.equals("parallelStream")) {
                    report("Java Stream invocation");
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        /** Die tatsächliche deutsche Sprache und Verständlichkeit bleiben eine manuelle Prüfung. */
        private void requireComment(String declaration) {
            String comment = comments.getDocComment(getCurrentPath());
            if (comment == null || comment.isBlank()) {
                report("Missing purpose comment: " + declaration);
            }
        }

        /** Der fehlgeschlagene Test nennt alle gefundenen Stellen statt nur die erste. */
        private void report(String message) {
            String file = unit.getSourceFile().getName();
            violations.add(file + ": " + message);
        }
    }
}
