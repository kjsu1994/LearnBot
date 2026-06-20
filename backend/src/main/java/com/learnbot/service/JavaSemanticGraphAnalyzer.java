package com.learnbot.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class JavaSemanticGraphAnalyzer {
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("Entity", "MappedSuperclass", "Embeddable");
    private static final Set<String> ENDPOINT_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"
    );

    public CodeGraph analyze(Path repositoryRoot, List<CodeSearchResult> chunks) {
        if (repositoryRoot == null || !Files.isDirectory(repositoryRoot)) {
            return empty();
        }
        List<Path> sourceRoots = javaSourceRoots(repositoryRoot);
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        sourceRoots.forEach(root -> typeSolver.add(new JavaParserTypeSolver(root)));
        JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver)));
        List<ParsedFile> files = parseFiles(repositoryRoot, sourceRoots, parser);
        if (files.isEmpty()) {
            return empty();
        }

        Map<String, CodeGraphNode> nodes = new LinkedHashMap<>();
        Map<String, CodeGraphEdge> edges = new LinkedHashMap<>();
        ChunkLookup chunkLookup = new ChunkLookup(chunks);
        Set<String> entityTypes = new LinkedHashSet<>();

        for (ParsedFile file : files) {
            for (TypeDeclaration<?> declaration : file.unit().findAll(TypeDeclaration.class)) {
                String qualifiedName = qualifiedTypeName(file.unit(), declaration);
                if (qualifiedName == null) {
                    continue;
                }
                UUID chunkId = chunkLookup.forNode(file.relativePath(), line(declaration), declaration.getNameAsString());
                addNode(nodes, typeNode(qualifiedName, file.relativePath(), chunkId));
                addEdge(edges, fileKey(file.relativePath()), typeKey(qualifiedName), "DEFINES", 1.0, chunkId, "java_symbol_solver");
                if (hasAnnotation(declaration, ENTITY_ANNOTATIONS)) {
                    entityTypes.add(qualifiedName);
                    String table = annotationValue(declaration.getAnnotations(), "Table", "name");
                    if (table == null || table.isBlank()) {
                        table = declaration.getNameAsString();
                    }
                    String tableKey = "table:" + table.toLowerCase(Locale.ROOT);
                    addNode(nodes, new CodeGraphNode(tableKey, "table", table, table, file.relativePath(), chunkId, Map.of("language", "java")));
                    addEdge(edges, typeKey(qualifiedName), tableKey, "MAPS_TO_TABLE", 0.98, chunkId, "java_ast");
                }
                addTypeRelations(nodes, edges, file, declaration, qualifiedName, chunkId);
            }
        }

        for (ParsedFile file : files) {
            for (CallableDeclaration<?> callable : file.unit().findAll(CallableDeclaration.class)) {
                addCallable(nodes, edges, file, callable, chunkLookup, entityTypes);
            }
        }
        return new CodeGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private void addTypeRelations(
            Map<String, CodeGraphNode> nodes,
            Map<String, CodeGraphEdge> edges,
            ParsedFile file,
            TypeDeclaration<?> declaration,
            String qualifiedName,
            UUID chunkId
    ) {
        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            addAnnotation(nodes, edges, typeKey(qualifiedName), annotation, file.relativePath(), chunkId);
        }
        if (!(declaration instanceof ClassOrInterfaceDeclaration type)) {
            return;
        }
        type.getExtendedTypes().forEach(parent -> addTypeEdge(nodes, edges, typeKey(qualifiedName), parent, "EXTENDS", file.relativePath(), chunkId));
        type.getImplementedTypes().forEach(parent -> addTypeEdge(nodes, edges, typeKey(qualifiedName), parent, "IMPLEMENTS", file.relativePath(), chunkId));
        for (FieldDeclaration field : type.getFields()) {
            for (var variable : field.getVariables()) {
                String fieldKey = "field:java:" + qualifiedName + "#" + variable.getNameAsString();
                addNode(nodes, new CodeGraphNode(fieldKey, "field", variable.getNameAsString(), qualifiedName + "#" + variable.getNameAsString(), file.relativePath(), chunkId, Map.of("language", "java")));
                addEdge(edges, typeKey(qualifiedName), fieldKey, "CONTAINS", 1.0, chunkId, "java_ast");
                if (hasAnnotation(field, INJECTION_ANNOTATIONS)) {
                    addTypeEdge(nodes, edges, typeKey(qualifiedName), variable.getType(), "INJECTS", file.relativePath(), chunkId);
                }
                field.getAnnotations().forEach(annotation -> addAnnotation(nodes, edges, fieldKey, annotation, file.relativePath(), chunkId));
            }
        }
    }

    private void addCallable(
            Map<String, CodeGraphNode> nodes,
            Map<String, CodeGraphEdge> edges,
            ParsedFile file,
            CallableDeclaration<?> callable,
            ChunkLookup chunks,
            Set<String> entityTypes
    ) {
        String owner = callable.findAncestor(TypeDeclaration.class)
                .map(type -> qualifiedTypeName(file.unit(), type))
                .orElse(null);
        if (owner == null) {
            return;
        }
        String signature = callableSignature(owner, callable);
        String key = methodKey(signature);
        UUID chunkId = chunks.forNode(file.relativePath(), line(callable), callable.getNameAsString());
        addNode(nodes, new CodeGraphNode(key, "method", callable.getNameAsString(), signature, file.relativePath(), chunkId,
                Map.of("language", "java", "signature", signature)));
        addEdge(edges, typeKey(owner), key, "CONTAINS", 1.0, chunkId, "java_symbol_solver");
        addEdge(edges, fileKey(file.relativePath()), key, "DEFINES", 1.0, chunkId, "java_symbol_solver");

        callable.getAnnotations().forEach(annotation -> addAnnotation(nodes, edges, key, annotation, file.relativePath(), chunkId));
        for (var parameter : callable.getParameters()) {
            addTypeEdge(nodes, edges, key, parameter.getType(), "ACCEPTS", file.relativePath(), chunkId);
            if (callable instanceof ConstructorDeclaration && (hasAnnotation(callable, INJECTION_ANNOTATIONS)
                    || constructorCount(callable) == 1)) {
                addTypeEdge(nodes, edges, typeKey(owner), parameter.getType(), "INJECTS", file.relativePath(), chunkId);
            }
        }
        for (ReferenceType thrown : callable.getThrownExceptions()) {
            addTypeEdge(nodes, edges, key, thrown, "THROWS", file.relativePath(), chunkId);
        }
        if (callable instanceof MethodDeclaration method) {
            addTypeEdge(nodes, edges, key, method.getType(), "RETURNS", file.relativePath(), chunkId);
            if (method.getAnnotationByName("Override").isPresent()) {
                addOverrideEdge(nodes, edges, key, method, file.relativePath(), chunkId);
            }
            addEndpoint(nodes, edges, key, method, file.relativePath(), chunkId);
        }

        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                String targetSignature = resolved.getQualifiedSignature();
                String targetKey = methodKey(targetSignature);
                addNode(nodes, new CodeGraphNode(targetKey, "method", resolved.getName(), targetSignature,
                        null, null, Map.of("language", "java", "external", true)));
                addEdge(edges, key, targetKey, "CALLS", 1.0, chunkId, "java_symbol_solver");
                String targetOwner = resolved.declaringType().getQualifiedName();
                if (entityTypes.contains(targetOwner)) {
                    addEdge(edges, key, typeKey(targetOwner), "USES_ENTITY", 0.98, chunkId, "java_symbol_solver");
                }
            } catch (RuntimeException ignored) {
                // Unresolved calls are deliberately left for deterministic REFERENCES/optional LLM fallback.
            }
        }
        addFieldAccesses(nodes, edges, key, owner, callable, file.relativePath(), chunkId);
    }

    private void addFieldAccesses(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String methodKey,
                                  String owner, CallableDeclaration<?> callable, String path, UUID chunkId) {
        Set<Node> writes = new LinkedHashSet<>();
        callable.findAll(AssignExpr.class).forEach(assign -> writes.add(assign.getTarget()));
        List<Node> candidates = new ArrayList<>();
        candidates.addAll(callable.findAll(NameExpr.class));
        candidates.addAll(callable.findAll(FieldAccessExpr.class));
        for (Node candidate : candidates) {
            try {
                ResolvedValueDeclaration value = candidate instanceof NameExpr name ? name.resolve() : ((FieldAccessExpr) candidate).resolve();
                if (!value.isField()) {
                    continue;
                }
                String fieldName = value.getName();
                String fieldKey = "field:java:" + owner + "#" + fieldName;
                addNode(nodes, new CodeGraphNode(fieldKey, "field", fieldName, owner + "#" + fieldName, path, chunkId, Map.of("language", "java")));
                boolean write = writes.stream().anyMatch(target -> target == candidate || target.isAncestorOf(candidate));
                addEdge(edges, methodKey, fieldKey, write ? "WRITES_FIELD" : "READS_FIELD", 0.96, chunkId, "java_symbol_solver");
            } catch (RuntimeException ignored) {
                // Local variables and unresolved external fields are not graph fields.
            }
        }
    }

    private void addOverrideEdge(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String sourceKey,
                                 MethodDeclaration method, String path, UUID chunkId) {
        try {
            ResolvedMethodDeclaration resolved = method.resolve();
            ResolvedReferenceTypeDeclaration owner = resolved.declaringType();
            owner.getAllAncestors().forEach(ancestor -> ancestor.getTypeDeclaration().ifPresent(parent ->
                    parent.getDeclaredMethods().stream()
                            .filter(candidate -> sameParameters(resolved, candidate))
                            .forEach(candidate -> {
                                String signature = candidate.getQualifiedSignature();
                                addNode(nodes, new CodeGraphNode(methodKey(signature), "method", candidate.getName(), signature,
                                        null, null, Map.of("language", "java", "external", true)));
                                addEdge(edges, sourceKey, methodKey(signature), "OVERRIDES", 0.99, chunkId, "java_symbol_solver");
                            })));
        } catch (RuntimeException ignored) {
            // @Override remains represented by ANNOTATED_BY when the parent cannot be resolved.
        }
    }

    private boolean sameParameters(ResolvedMethodDeclaration left, ResolvedMethodDeclaration right) {
        if (!left.getName().equals(right.getName()) || left.getNumberOfParams() != right.getNumberOfParams()) {
            return false;
        }
        for (int i = 0; i < left.getNumberOfParams(); i++) {
            if (!left.getParam(i).getType().describe().equals(right.getParam(i).getType().describe())) {
                return false;
            }
        }
        return true;
    }

    private void addEndpoint(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String methodKey,
                             MethodDeclaration method, String path, UUID chunkId) {
        method.getAnnotations().stream()
                .filter(annotation -> ENDPOINT_ANNOTATIONS.contains(annotation.getName().getIdentifier()))
                .forEach(annotation -> {
                    String route = annotationValue(List.of(annotation), annotation.getName().getIdentifier(), "value");
                    String endpointName = annotation.getName().getIdentifier() + ":" + (route == null ? "" : route);
                    String endpointKey = "endpoint:java:" + endpointName + ":" + methodKey;
                    addNode(nodes, new CodeGraphNode(endpointKey, "endpoint", endpointName, endpointName, path, chunkId, Map.of("language", "java")));
                    addEdge(edges, methodKey, endpointKey, "EXPOSES_ENDPOINT", 0.99, chunkId, "java_ast");
                });
    }

    private void addAnnotation(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String sourceKey,
                               AnnotationExpr annotation, String path, UUID chunkId) {
        String name;
        try {
            name = annotation.resolve().getQualifiedName();
        } catch (RuntimeException ignored) {
            name = annotation.getNameAsString();
        }
        String key = "annotation:java:" + name;
        addNode(nodes, new CodeGraphNode(key, "annotation", annotation.getName().getIdentifier(), name, path, chunkId, Map.of("language", "java")));
        addEdge(edges, sourceKey, key, "ANNOTATED_BY", 0.98, chunkId, "java_ast");
    }

    private void addTypeEdge(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String sourceKey,
                             Type type, String relation, String path, UUID chunkId) {
        String qualified;
        try {
            qualified = type.resolve().describe();
        } catch (RuntimeException ignored) {
            qualified = type.asString();
        }
        qualified = eraseGeneric(qualified);
        if (qualified.isBlank() || "void".equals(qualified)) {
            return;
        }
        addNode(nodes, typeNode(qualified, path, null));
        addEdge(edges, sourceKey, typeKey(qualified), relation, 0.97, chunkId, "java_symbol_solver");
    }

    private String callableSignature(String owner, CallableDeclaration<?> callable) {
        try {
            if (callable instanceof MethodDeclaration method) {
                return method.resolve().getQualifiedSignature();
            }
            ConstructorDeclaration constructor = (ConstructorDeclaration) callable;
            return constructor.resolve().getQualifiedSignature();
        } catch (RuntimeException ignored) {
            String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
            return owner + "." + name + "(" + callable.getParameters().stream()
                    .map(parameter -> parameter.getType().asString())
                    .reduce((left, right) -> left + "," + right).orElse("") + ")";
        }
    }

    private List<ParsedFile> parseFiles(Path repositoryRoot, List<Path> roots, JavaParser parser) {
        List<ParsedFile> result = new ArrayList<>();
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                parser.parse(path).getResult().ifPresent(unit -> result.add(
                                        new ParsedFile(repositoryRoot.relativize(path).toString().replace('\\', '/'), unit)
                                ));
                            } catch (IOException ignored) {
                                // A malformed or unreadable file does not block other Java sources.
                            }
                        });
            } catch (IOException ignored) {
                // Other source roots remain analyzable.
            }
        }
        return result;
    }

    private List<Path> javaSourceRoots(Path repositoryRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        try (var paths = Files.walk(repositoryRoot)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of("src", "main", "java")) || path.endsWith(Path.of("src", "test", "java")))
                    .forEach(roots::add);
        } catch (IOException ignored) {
            // Fall back to the repository root for flat Java projects.
        }
        if (roots.isEmpty()) {
            roots.add(repositoryRoot);
        }
        return roots.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private String qualifiedTypeName(CompilationUnit unit, TypeDeclaration<?> type) {
        try {
            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                return declaration.resolve().getQualifiedName();
            }
        } catch (RuntimeException ignored) {
            // Package plus nesting is a deterministic fallback.
        }
        String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString() + ".").orElse("");
        List<String> nesting = new ArrayList<>();
        Node current = type;
        while (current instanceof TypeDeclaration<?> declaration) {
            nesting.add(0, declaration.getNameAsString());
            current = declaration.getParentNode().orElse(null);
        }
        return packageName + String.join(".", nesting);
    }

    private boolean hasAnnotation(Node node, Set<String> names) {
        if (!(node instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> annotated)) {
            return false;
        }
        return annotated.getAnnotations().stream().anyMatch(annotation -> names.contains(annotation.getName().getIdentifier()));
    }

    private String annotationValue(List<AnnotationExpr> annotations, String annotationName, String member) {
        return annotations.stream()
                .filter(annotation -> annotation.getName().getIdentifier().equals(annotationName))
                .findFirst()
                .flatMap(annotation -> {
                    if (annotation.isSingleMemberAnnotationExpr()) {
                        return java.util.Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", ""));
                    }
                    if (annotation.isNormalAnnotationExpr()) {
                        return annotation.asNormalAnnotationExpr().getPairs().stream()
                                .filter(pair -> pair.getNameAsString().equals(member))
                                .map(pair -> pair.getValue().toString().replace("\"", ""))
                                .findFirst();
                    }
                    return java.util.Optional.empty();
                }).orElse(null);
    }

    private int constructorCount(CallableDeclaration<?> callable) {
        return callable.findAncestor(TypeDeclaration.class)
                .map(type -> type.getMembers().stream().filter(member -> member instanceof ConstructorDeclaration).count())
                .map(Long::intValue).orElse(0);
    }

    private int line(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(1);
    }

    private String eraseGeneric(String value) {
        int generic = value.indexOf('<');
        String erased = generic < 0 ? value : value.substring(0, generic);
        return erased.replace("[]", "").trim();
    }

    private CodeGraphNode typeNode(String qualified, String path, UUID chunkId) {
        String name = qualified.contains(".") ? qualified.substring(qualified.lastIndexOf('.') + 1) : qualified;
        return new CodeGraphNode(typeKey(qualified), "type", name, qualified, path, chunkId, Map.of("language", "java"));
    }

    private String fileKey(String path) { return "file:" + path; }
    private String typeKey(String qualified) { return "type:java:" + qualified; }
    private String methodKey(String signature) { return "method:java:" + signature; }

    private void addNode(Map<String, CodeGraphNode> nodes, CodeGraphNode node) {
        nodes.putIfAbsent(node.key(), node);
    }

    private void addEdge(Map<String, CodeGraphEdge> edges, String source, String target, String type,
                         double confidence, UUID chunkId, String sourceName) {
        if (source == null || target == null || source.equals(target)) {
            return;
        }
        edges.putIfAbsent(source + "|" + type + "|" + target,
                new CodeGraphEdge(source, target, type, confidence, chunkId, Map.of("source", sourceName)));
    }

    private CodeGraph empty() { return new CodeGraph(List.of(), List.of()); }

    private record ParsedFile(String relativePath, CompilationUnit unit) {}

    private static final class ChunkLookup {
        private final Map<String, List<CodeSearchResult>> byPath = new HashMap<>();

        private ChunkLookup(List<CodeSearchResult> chunks) {
            if (chunks != null) {
                chunks.forEach(chunk -> byPath.computeIfAbsent(normalize(chunk.filePath()), ignored -> new ArrayList<>()).add(chunk));
            }
        }

        private UUID forNode(String path, int line, String name) {
            List<CodeSearchResult> candidates = byPath.getOrDefault(normalize(path), List.of());
            return candidates.stream()
                    .filter(chunk -> chunk.lineStart() <= line && chunk.lineEnd() >= line)
                    .sorted(Comparator.comparingInt(chunk -> symbolPenalty(chunk, name)))
                    .map(CodeSearchResult::chunkId)
                    .findFirst()
                    .orElseGet(() -> candidates.stream().map(CodeSearchResult::chunkId).findFirst().orElse(null));
        }

        private int symbolPenalty(CodeSearchResult chunk, String name) {
            return name.equals(chunk.methodName()) || name.equals(chunk.className()) || name.equals(chunk.symbolName()) ? 0 : 1;
        }

        private static String normalize(String path) {
            return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
    }
}
