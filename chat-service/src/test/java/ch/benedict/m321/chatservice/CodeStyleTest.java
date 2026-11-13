package ch.benedict.m321.chatservice;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.SourcePositions;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft geschriebene Java-Quellen mit dem JDK-Parser statt Regeln durch Regex zu erraten. */
class CodeStyleTest {

    /** Klassen und Methoden brauchen Zweckkommentare; Java-Streams sind gemäss Auftrag verboten. */
    @Test
    void documentsClassesAndMethodsWithoutJavaStreams() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not just a JRE");
        List<File> files = new ArrayList<>();
        var mainDirectory = new File("src/main/java");
        collect(mainDirectory, files);
        var testDirectory = new File("src/test/java");
        collect(testDirectory, files);
        var empty = files.isEmpty();
        assertFalse(empty);
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
        var empty2 = violations.isEmpty();
        var failureDescription = String.join("\n", violations);
        assertTrue(empty2, failureDescription);
    }

    /** Verschachtelung wird erkannt; ein erst später ausgeführter Lambda-Körper zählt nicht als Argumentaufruf. */
    @Test
    void detectsNestedArgumentsAndChainsWithoutRejectingDeferredLambda() throws Exception {
        List<String> invalidExpressions = List.of("consume(load());", "factory().run();",
                "new Example(load());", "consume(new Example());");
        for (String expression : invalidExpressions) {
            List<String> violations = checkExample(expression);
            boolean detected = false;
            for (String violation : violations) {
                if (violation.contains("Nested call")) {
                    detected = true;
                }
            }
            assertTrue(detected, expression);
        }
        List<String> valid = checkExample("Object value = load(); consume(value); defer(() -> load());");
        var empty = valid.isEmpty();
        var failureDescription = String.join("\n", valid);
        assertTrue(empty, failureDescription);
    }

    /** Reine Parser-Beispiele benötigen keine auflösbaren Framework-Abhängigkeiten. */
    private List<String> checkExample(String expression) throws Exception {
        String code = "/** Beispiel. */ class Example { /** Testzweck. */ void run() { " + expression + " } }";
        URI uri = URI.create("string:///Example.java");
        StringSource source = new StringSource(uri, code);
        List<StringSource> sources = List.of(source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> violations = new ArrayList<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-proc:none", "--release", "21");
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, options, null, sources);
            DocTrees comments = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                SourceCheck check = new SourceCheck(comments, unit, violations);
                check.scan(unit, null);
            }
        }
        return violations;
    }

    /** Eine kleine Quelle im Speicher hält positive und negative Prüffälle neben dem Test. */
    private static class StringSource extends SimpleJavaFileObject {
        private final String code;

        /** Die URI wird vom Aufrufer erzeugt, ohne verschachtelten super-Aufruf. */
        StringSource(URI uri, String code) {
            super(uri, Kind.SOURCE);
            this.code = code;
        }

        /** Der Parser liest dieselben Zeichen wie aus einer Java-Datei. */
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** Rekursive Dateisuche bleibt selbst ohne die verbotene Stream-API lesbar. */
    private void collect(File directory, List<File> files) {
        File[] children = directory.listFiles();
        assertNotNull(children, "Source directory missing: " + directory);
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, files);
            } else {
                String name = child.getName();
                if (name.endsWith(".java")) {
                    files.add(child);
                }
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
            var simpleName = node.getSimpleName();
            String className = simpleName.toString();
            if (!className.isEmpty()) {
                requireComment("class " + className);
            }
            return super.visitClass(node, unused);
        }

        /** Nur geschriebene Methoden und Konstruktoren werden geprüft, keine Lombok-Erzeugnisse. */
        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            var methodName = node.getName();
            requireComment("method " + methodName);
            return super.visitMethod(node, unused);
        }

        /** Direkte sowie statische Imports der Stream-Pakete sind unzulässig. */
        @Override
        public Void visitImport(ImportTree node, Void unused) {
            var qualifiedIdentifier = node.getQualifiedIdentifier();
            String imported = qualifiedIdentifier.toString();
            if (imported.startsWith("java.util.stream.")) {
                report("Java Stream import");
            }
            return super.visitImport(node, unused);
        }

        /** Auch voll qualifizierte Verweise dürfen die Import-Prüfung nicht umgehen. */
        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            String selectedName = node.toString();
            if (selectedName.startsWith("java.util.stream.")) {
                report("Qualified Java Stream reference");
            }
            return super.visitMemberSelect(node, unused);
        }

        /** Collection.stream und parallelStream sind auch ohne expliziten Stream-Import verboten. */
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            var arguments = node.getArguments();
            checkArguments(arguments);
            if (node.getMethodSelect() instanceof MemberSelectTree selected) {
                ExpressionTree receiver = selected.getExpression();
                if (containsCall(receiver)) {
                    report("Nested call in receiver");
                }
                var identifier = selected.getIdentifier();
                String method = identifier.toString();
                if (method.equals("stream") || method.equals("parallelStream")) {
                    report("Java Stream invocation");
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        /** Konstruktorargumente folgen derselben Lesbarkeitsregel wie Methodenargumente. */
        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            var arguments = node.getArguments();
            checkArguments(arguments);
            return super.visitNewClass(node, unused);
        }

        /** Benannte Zwischenergebnisse machen die unmittelbare Auswertungsreihenfolge sichtbar. */
        private void checkArguments(List<? extends ExpressionTree> arguments) {
            for (ExpressionTree argument : arguments) {
                if (containsCall(argument)) {
                    report("Nested call in argument");
                }
            }
        }

        /** Aufrufe innerhalb eines Lambda-Körpers werden erst bei dessen Ausführung ausgewertet. */
        private boolean containsCall(Tree expression) {
            ImmediateCallCheck check = new ImmediateCallCheck();
            Boolean found = check.scan(expression, null);
            return Boolean.TRUE.equals(found);
        }

        /** Die tatsächliche deutsche Sprache und Verständlichkeit bleiben eine manuelle Prüfung. */
        private void requireComment(String declaration) {
            var currentPath = getCurrentPath();
            String comment = comments.getDocComment(currentPath);
            if (comment == null || comment.isBlank()) {
                report("Missing purpose comment: " + declaration);
            }
        }

        /** Der fehlgeschlagene Test nennt alle gefundenen Stellen statt nur die erste. */
        private void report(String message) {
            var sourceFile = unit.getSourceFile();
            String file = sourceFile.getName();
            SourcePositions positions = comments.getSourcePositions();
            var currentPath = getCurrentPath();
            Tree declaration = currentPath.getLeaf();
            long offset = positions.getStartPosition(unit, declaration);
            var lineMap = unit.getLineMap();
            long line = lineMap.getLineNumber(offset);
            violations.add(file + ":" + line + ": " + message);
        }
    }

    /** Unterscheidet unmittelbare Auswertung von später auszuführenden Funktionen. */
    private static class ImmediateCallCheck extends TreeScanner<Boolean, Void> {

        /** Ein bereits gefundener Aufruf bleibt auch bei weiteren Geschwisterknoten erhalten. */
        @Override
        public Boolean reduce(Boolean first, Boolean second) {
            return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
        }

        /** Ein Methodenaufruf muss vor dem äusseren Aufruf ausgewertet werden. */
        @Override
        public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
            return true;
        }

        /** Auch ein neues Objekt ist ein unmittelbar ausgeführter Konstruktoraufruf. */
        @Override
        public Boolean visitNewClass(NewClassTree node, Void unused) {
            return true;
        }

        /** Ein Lambda wird hier nur übergeben, nicht bereits ausgeführt. */
        @Override
        public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
            return false;
        }

        /** Eine Methodenreferenz führt nur ihren Empfängerausdruck sofort aus. */
        @Override
        public Boolean visitMemberReference(MemberReferenceTree node, Void unused) {
            var qualifier = node.getQualifierExpression();
            return scan(qualifier, unused);
        }
    }
}
