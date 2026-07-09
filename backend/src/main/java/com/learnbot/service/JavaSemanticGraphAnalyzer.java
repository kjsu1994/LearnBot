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
import com.github.javaparser.ast.body.Parameter;
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
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaSemanticGraphAnalyzer {
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("Entity", "MappedSuperclass", "Embeddable");
    private static final Set<String> SPRING_DATA_REPOSITORIES = Set.of(
            "Repository", "CrudRepository", "PagingAndSortingRepository", "JpaRepository", "JpaSpecificationExecutor"
    );
    private static final Set<String> SPRING_COMPONENT_ANNOTATIONS = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration"
    );
    private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of("Transactional");
    private static final Set<String> ENDPOINT_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"
    );
    private static final Pattern SPRING_QUERY_METHOD_PATTERN = Pattern.compile("^(find|read|get|query|search|stream|exists|count|delete|remove)\\w*By(.+)$");
    private static final int MAX_DECLARED_QUERY_CHARS = 600;
    private final LearnBotProperties properties;

    public JavaSemanticGraphAnalyzer() {
        this(null);
    }

    @Autowired
    public JavaSemanticGraphAnalyzer(LearnBotProperties properties) {
        this.properties = properties;
    }

    public CodeGraph analyze(Path repositoryRoot, List<CodeSearchResult> chunks) {
        return analyzeWithDiagnostics(repositoryRoot, chunks).graph();
    }

    public CodeGraphAnalysisResult analyzeWithDiagnostics(Path repositoryRoot, List<CodeSearchResult> chunks) {
        return analyzeWithDiagnostics(repositoryRoot, chunks, List.of());
    }

    public CodeGraphAnalysisResult analyzeWithDiagnostics(Path repositoryRoot, List<CodeSearchResult> chunks, List<Path> dependencyJars) {
        long started = System.nanoTime();
        if (repositoryRoot == null || !Files.isDirectory(repositoryRoot)) {
            return new CodeGraphAnalysisResult(empty(), CodeAnalysisDiagnostic.skipped(
                    "JAVA_SEMANTIC", "JavaParser Symbol Solver", "SOURCE", "Repository root is unavailable."
            ));
        }
        List<Path> sourceRoots = javaSourceRoots(repositoryRoot);
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        sourceRoots.forEach(root -> typeSolver.add(new JavaParserTypeSolver(root)));
        if (dependencyJars != null) {
            dependencyJars.forEach(jar -> {
                try {
                    typeSolver.add(new JarTypeSolver(jar));
                } catch (IOException | RuntimeException ignored) {
                    // Invalid cached jars are ignored; classpath diagnostics remains PARTIAL.
                }
            });
        }
        JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver)));
        List<ParsedFile> files = parseFiles(repositoryRoot, sourceRoots, parser);
        int attemptedFiles = countJavaFiles(sourceRoots);
        if (files.isEmpty()) {
            return new CodeGraphAnalysisResult(empty(), new CodeAnalysisDiagnostic(
                    "JAVA_SEMANTIC", "JavaParser Symbol Solver", attemptedFiles == 0 ? "SKIPPED" : "FAILED", "SOURCE",
                    attemptedFiles, 0, attemptedFiles, 0, 0, 0, 0, elapsedMillis(started),
                    attemptedFiles == 0 ? "No Java source files found." : "No Java source file could be parsed.", Map.of()
            ));
        }

        Map<String, CodeGraphNode> nodes = new LinkedHashMap<>();
        Map<String, CodeGraphEdge> edges = new LinkedHashMap<>();
        ChunkLookup chunkLookup = new ChunkLookup(chunks);
        Set<String> entityTypes = new LinkedHashSet<>();
        Map<String, List<AnnotationExpr>> typeTransactionAnnotations = new LinkedHashMap<>();

        for (ParsedFile file : files) {
            for (TypeDeclaration<?> declaration : file.unit().findAll(TypeDeclaration.class)) {
                String qualifiedName = qualifiedTypeName(file.unit(), declaration);
                if (qualifiedName == null) {
                    continue;
                }
                UUID chunkId = chunkLookup.forNode(file.relativePath(), line(declaration), declaration.getNameAsString());
                addNode(nodes, typeNode(qualifiedName, file.relativePath(), chunkId, springRole(declaration)));
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
                if (springAnalysisEnabled() && hasAnnotation(declaration, TRANSACTION_ANNOTATIONS)) {
                    typeTransactionAnnotations.put(qualifiedName, declaration.getAnnotations());
                    addTransactionBoundary(nodes, edges, typeKey(qualifiedName), qualifiedName,
                            file.relativePath(), chunkId, declaration.getAnnotations(), "java_ast");
                }
                if (springAnalysisEnabled()) {
                    addSpringRepositoryRelations(nodes, edges, file, declaration, qualifiedName, chunkId);
                }
                addTypeRelations(nodes, edges, file, declaration, qualifiedName, chunkId);
            }
        }

        for (ParsedFile file : files) {
            for (CallableDeclaration<?> callable : file.unit().findAll(CallableDeclaration.class)) {
                addCallable(nodes, edges, file, callable, chunkLookup, entityTypes, typeTransactionAnnotations);
            }
        }
        CodeGraph graph = new CodeGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()));
        int failedFiles = Math.max(0, attemptedFiles - files.size());
        return new CodeGraphAnalysisResult(graph, new CodeAnalysisDiagnostic(
                "JAVA_SEMANTIC", "JavaParser Symbol Solver", failedFiles == 0 ? "SUCCESS" : "PARTIAL", "SOURCE",
                attemptedFiles, files.size(), failedFiles, graph.edges().size(), 0,
                graph.nodes().size(), graph.edges().size(), elapsedMillis(started),
                failedFiles == 0 ? "Java semantic analysis completed." : "Some Java files could not be parsed.",
                Map.of("sourceRoots", sourceRoots.size(), "dependencyJars", dependencyJars == null ? 0 : dependencyJars.size())
        ));
    }

    private int countJavaFiles(List<Path> roots) {
        int count = 0;
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                count += (int) paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).count();
            } catch (IOException ignored) {
                // Count remains best-effort and analysis continues.
            }
        }
        return count;
    }

    private long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
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
                if (springAnalysisEnabled() && hasAnnotation(field, INJECTION_ANNOTATIONS)) {
                    addTypeEdge(nodes, edges, typeKey(qualifiedName), variable.getType(), "INJECTS", file.relativePath(), chunkId,
                            injectionConfidence(field.getAnnotations()), injectionMetadata(field.getAnnotations(), "field"));
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
            Set<String> entityTypes,
            Map<String, List<AnnotationExpr>> typeTransactionAnnotations
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
        if (springAnalysisEnabled() && hasAnnotation(callable, TRANSACTION_ANNOTATIONS)) {
            addTransactionBoundary(nodes, edges, key, signature, file.relativePath(), chunkId, callable.getAnnotations(), "java_ast");
        } else if (springAnalysisEnabled() && typeTransactionAnnotations.containsKey(owner)) {
            addTransactionBoundary(nodes, edges, key, signature, file.relativePath(), chunkId,
                    typeTransactionAnnotations.get(owner), "java_ast", true);
        }
        if (springAnalysisEnabled() && callable instanceof MethodDeclaration method && method.getAnnotationByName("Bean").isPresent()) {
            addBean(nodes, edges, key, method, file.relativePath(), chunkId);
        }
        for (var parameter : callable.getParameters()) {
            addTypeEdge(nodes, edges, key, parameter.getType(), "ACCEPTS", file.relativePath(), chunkId);
            if (springAnalysisEnabled() && callable instanceof ConstructorDeclaration && (hasAnnotation(callable, INJECTION_ANNOTATIONS)
                    || constructorCount(callable) == 1)) {
                addTypeEdge(nodes, edges, typeKey(owner), parameter.getType(), "INJECTS", file.relativePath(), chunkId,
                        injectionConfidence(parameter.getAnnotations()), injectionMetadata(parameter.getAnnotations(), "constructor"));
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
            if (springAnalysisEnabled()) {
                addEndpoint(nodes, edges, key, method, file.relativePath(), chunkId);
                addRepositoryMethodRelations(nodes, edges, key, owner, method, file.relativePath(), chunkId);
            }
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

    private void addSpringRepositoryRelations(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges,
                                              ParsedFile file, TypeDeclaration<?> declaration, String qualifiedName, UUID chunkId) {
        if (!(declaration instanceof ClassOrInterfaceDeclaration type)) {
            return;
        }
        if (!isSpringRepository(type)) {
            return;
        }
        addNode(nodes, new CodeGraphNode(typeKey(qualifiedName), "type", declaration.getNameAsString(), qualifiedName, file.relativePath(), chunkId,
                Map.of("language", "java", "framework", "spring", "springRole", "repository")));
        for (var parent : type.getExtendedTypes()) {
            parent.getTypeArguments().ifPresent(arguments -> {
                if (!arguments.isEmpty()) {
                    String entityName = resolvedTypeName(arguments.get(0));
                    addNode(nodes, typeNode(entityName, file.relativePath(), null));
                    addEdge(edges, typeKey(qualifiedName), typeKey(entityName), "REPOSITORY_FOR", 0.86, chunkId,
                            Map.of("source", "spring_data_repository", "repositoryBase", parent.getNameAsString(),
                                    "evidenceKind", "inferred", "confidenceReason", "generic_entity_type"));
                    addEdge(edges, typeKey(qualifiedName), typeKey(entityName), "QUERIES_ENTITY", 0.78, chunkId,
                            Map.of("source", "spring_data_repository", "repositoryBase", parent.getNameAsString(),
                                    "confidenceReason", "generic_entity_type", "evidenceKind", "inferred"));
                }
            });
        }
    }

    private void addRepositoryMethodRelations(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges,
                                              String methodKey, String owner, MethodDeclaration method, String path, UUID chunkId) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        ClassOrInterfaceDeclaration type = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (type == null || !isSpringRepository(type)) {
            return;
        }
        String entityName = repositoryEntityType(type);
        Map<String, Object> methodMetadata = repositoryMethodMetadata(method);
        if (entityName != null && !entityName.isBlank()) {
            addNode(nodes, typeNode(entityName, path, null));
            addEdge(edges, methodKey, typeKey(entityName), "QUERIES_ENTITY", 0.82, chunkId,
                    withMetadata(methodMetadata, Map.of(
                            "source", "spring_data_query_method",
                            "confidenceReason", "repository_method_entity_type",
                            "evidenceKind", "inferred"
                    )));
        }
        for (String property : queryMethodProperties(method.getNameAsString())) {
            String propertyKey = "property:java:" + (entityName == null || entityName.isBlank() ? owner : entityName) + "#" + property;
            addNode(nodes, new CodeGraphNode(propertyKey, "property", property,
                    (entityName == null || entityName.isBlank() ? owner : entityName) + "#" + property,
                    null, null, Map.of("language", "java", "framework", "spring")));
            addEdge(edges, methodKey, propertyKey, "FILTERS_BY_PROPERTY", 0.64, chunkId,
                    withMetadata(methodMetadata, Map.of(
                            "source", "spring_data_query_method",
                            "queryProperty", property,
                            "confidenceReason", "derived_query_method_name",
                            "evidenceKind", "candidate"
                    )));
        }
    }

    private boolean isSpringRepository(ClassOrInterfaceDeclaration type) {
        return hasAnnotation(type, Set.of("Repository"))
                || type.getExtendedTypes().stream().anyMatch(parent -> SPRING_DATA_REPOSITORIES.contains(parent.getNameAsString()));
    }

    private String repositoryEntityType(ClassOrInterfaceDeclaration type) {
        for (var parent : type.getExtendedTypes()) {
            if (!SPRING_DATA_REPOSITORIES.contains(parent.getNameAsString())) {
                continue;
            }
            var arguments = parent.getTypeArguments();
            if (arguments.isPresent() && !arguments.get().isEmpty()) {
                return resolvedTypeName(arguments.get().get(0));
            }
        }
        return null;
    }

    private List<String> queryMethodProperties(String methodName) {
        Matcher matcher = SPRING_QUERY_METHOD_PATTERN.matcher(methodName == null ? "" : methodName);
        if (!matcher.matches()) {
            return List.of();
        }
        String criteria = matcher.group(2)
                .replaceAll("OrderBy.*$", "")
                .replaceAll("(True|False|IsNull|IsNotNull|NotNull|Null|Between|Before|After|LessThanEqual|LessThan|GreaterThanEqual|GreaterThan|StartingWith|EndingWith|Containing|Contains|Like|NotLike|In|NotIn|Not|IgnoreCase)$", "");
        if (criteria.isBlank()) {
            return List.of();
        }
        List<String> properties = new ArrayList<>();
        for (String part : criteria.split("(And|Or)")) {
            String property = lowerFirst(part.replaceAll("(True|False|IsNull|IsNotNull|NotNull|Null|Between|Before|After|LessThanEqual|LessThan|GreaterThanEqual|GreaterThan|StartingWith|EndingWith|Containing|Contains|Like|NotLike|In|NotIn|Not|IgnoreCase)$", ""));
            if (!property.isBlank()) {
                properties.add(property);
            }
        }
        return properties.stream().distinct().limit(8).toList();
    }

    private Map<String, Object> repositoryMethodMetadata(MethodDeclaration method) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "java");
        metadata.put("framework", "spring");
        Matcher matcher = SPRING_QUERY_METHOD_PATTERN.matcher(method.getNameAsString());
        if (matcher.matches()) {
            metadata.put("queryMethodKind", matcher.group(1));
            List<String> properties = queryMethodProperties(method.getNameAsString());
            if (!properties.isEmpty()) {
                metadata.put("queryProperties", properties);
            }
        }
        String query = annotationValue(method.getAnnotations(), "Query", "value");
        if (query != null && !query.isBlank()) {
            metadata.put("declaredQuery", truncate(query, MAX_DECLARED_QUERY_CHARS));
        }
        if (method.getAnnotationByName("Modifying").isPresent()) {
            metadata.put("modifying", true);
        }
        String lockMode = annotationValue(method.getAnnotations(), "Lock", "value");
        if (lockMode != null && !lockMode.isBlank()) {
            metadata.put("lockMode", lockMode);
        }
        return metadata;
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
                String declaringType = value.asField().declaringType().getQualifiedName();
                String fieldKey = "field:java:" + declaringType + "#" + fieldName;
                addNode(nodes, new CodeGraphNode(
                        fieldKey,
                        "field",
                        fieldName,
                        declaringType + "#" + fieldName,
                        declaringType.equals(owner) ? path : null,
                        declaringType.equals(owner) ? chunkId : null,
                        Map.of("language", "java")
                ));
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
                    String route = joinedRoute(classRoute(method), annotationValue(List.of(annotation), annotation.getName().getIdentifier(), "value", "path"));
                    String methodName = annotation.getName().getIdentifier();
                    String endpointName = methodName + ":" + (route == null ? "" : route);
                    String endpointKey = "endpoint:java:" + endpointName + ":" + methodKey;
                    addNode(nodes, new CodeGraphNode(endpointKey, "endpoint", endpointName, endpointName, path, chunkId,
                            endpointMetadata(method, annotation, methodName, route)));
                    addEdge(edges, methodKey, endpointKey, "EXPOSES_ENDPOINT", 0.99, chunkId, "java_ast");
                });
    }

    private void addBean(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String methodKey,
                         MethodDeclaration method, String path, UUID chunkId) {
        String beanName = annotationValue(method.getAnnotations(), "Bean", "name", "value");
        if (beanName == null || beanName.isBlank()) {
            beanName = method.getNameAsString();
        }
        String beanKey = "bean:java:" + beanName + ":" + methodKey;
        addNode(nodes, new CodeGraphNode(beanKey, "bean", beanName, beanName, path, chunkId,
                beanMetadata(method, beanName)));
        addEdge(edges, methodKey, beanKey, "DECLARES_BEAN", 0.99, chunkId, "java_ast");
    }

    private void addTransactionBoundary(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges,
                                        String sourceKey, String name, String path, UUID chunkId, String sourceName) {
        addTransactionBoundary(nodes, edges, sourceKey, name, path, chunkId, List.of(), sourceName);
    }

    private void addTransactionBoundary(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges,
                                        String sourceKey, String name, String path, UUID chunkId,
                                        List<AnnotationExpr> annotations, String sourceName) {
        addTransactionBoundary(nodes, edges, sourceKey, name, path, chunkId, annotations, sourceName, false);
    }

    private void addTransactionBoundary(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges,
                                        String sourceKey, String name, String path, UUID chunkId,
                                        List<AnnotationExpr> annotations, String sourceName, boolean inherited) {
        String key = "transaction:java:" + sourceKey;
        addNode(nodes, new CodeGraphNode(key, "transaction_boundary", name, name, path, chunkId, transactionMetadata(annotations, inherited)));
        addEdge(edges, sourceKey, key, "TRANSACTION_BOUNDARY", inherited ? 0.86 : 0.99, chunkId,
                inherited ? Map.of("source", sourceName, "transactionInherited", true, "evidenceKind", "inferred",
                        "confidenceReason", "class_level_transaction_inherited") : Map.of("source", sourceName));
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
        addTypeEdge(nodes, edges, sourceKey, type, relation, path, chunkId, 0.97, Map.of("source", "java_symbol_solver"));
    }

    private void addTypeEdge(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, String sourceKey,
                             Type type, String relation, String path, UUID chunkId, double confidence, Map<String, Object> metadata) {
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
        addEdge(edges, sourceKey, typeKey(qualified), relation, confidence, chunkId, metadata);
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

    private String springRole(TypeDeclaration<?> declaration) {
        if (!springAnalysisEnabled()) {
            return "";
        }
        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if (SPRING_COMPONENT_ANNOTATIONS.contains(name)) {
                return switch (name) {
                    case "RestController", "Controller" -> "controller";
                    case "Service" -> "service";
                    case "Repository" -> "repository";
                    case "Configuration" -> "configuration";
                    default -> "component";
                };
            }
        }
        if (declaration instanceof ClassOrInterfaceDeclaration type
                && type.getExtendedTypes().stream().anyMatch(parent -> SPRING_DATA_REPOSITORIES.contains(parent.getNameAsString()))) {
            return "repository";
        }
        return "";
    }

    private String resolvedTypeName(Type type) {
        try {
            return eraseGeneric(type.resolve().describe());
        } catch (RuntimeException ignored) {
            return eraseGeneric(type.asString());
        }
    }

    private String classRoute(MethodDeclaration method) {
        TypeDeclaration<?> owner = ownerType(method);
        if (owner == null) return null;
        for (AnnotationExpr annotation : owner.getAnnotations()) {
            if (ENDPOINT_ANNOTATIONS.contains(annotation.getName().getIdentifier())) {
                return annotationValue(List.of(annotation), annotation.getName().getIdentifier(), "value", "path");
            }
        }
        return null;
    }

    private TypeDeclaration<?> ownerType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return type;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private String joinedRoute(String prefix, String route) {
        String left = prefix == null ? "" : prefix.trim();
        String right = route == null ? "" : route.trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return ("/" + left + "/" + right).replaceAll("/+", "/");
    }

    private boolean springAnalysisEnabled() {
        return properties == null
                || (properties.getCode().getGraph().isFrameworkAnalysisEnabled()
                && properties.getCode().getGraph().isJavaSpringEnabled());
    }

    private String annotationValue(List<AnnotationExpr> annotations, String annotationName, String member) {
        return annotationValue(annotations, annotationName, member, member);
    }

    private String annotationValue(List<AnnotationExpr> annotations, String annotationName, String primaryMember, String fallbackMember) {
        return annotations.stream()
                .filter(annotation -> annotation.getName().getIdentifier().equals(annotationName))
                .findFirst()
                .flatMap(annotation -> {
                    if (annotation.isSingleMemberAnnotationExpr()) {
                        return java.util.Optional.of(cleanAnnotationValue(annotation.asSingleMemberAnnotationExpr().getMemberValue().toString()));
                    }
                    if (annotation.isNormalAnnotationExpr()) {
                        var pairs = annotation.asNormalAnnotationExpr().getPairs();
                        return pairs.stream()
                                .filter(pair -> pair.getNameAsString().equals(primaryMember))
                                .map(pair -> cleanAnnotationValue(pair.getValue().toString()))
                                .findFirst()
                                .or(() -> pairs.stream()
                                        .filter(pair -> pair.getNameAsString().equals(fallbackMember))
                                        .map(pair -> cleanAnnotationValue(pair.getValue().toString()))
                                        .findFirst());
                    }
                    return java.util.Optional.empty();
                }).orElse(null);
    }

    private String cleanAnnotationValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            int comma = cleaned.indexOf(',');
            if (comma >= 0) {
                cleaned = cleaned.substring(0, comma).trim();
            }
        }
        return cleaned.replace("\"", "").trim();
    }

    private double injectionConfidence(List<AnnotationExpr> annotations) {
        if (!springAnalysisEnabled()) {
            return 0.97;
        }
        boolean qualified = annotations.stream().anyMatch(annotation ->
                Set.of("Qualifier", "Resource", "Primary").contains(annotation.getName().getIdentifier()));
        return qualified ? 0.99 : 0.92;
    }

    private Map<String, Object> injectionMetadata(List<AnnotationExpr> annotations, String injectionPoint) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "java_symbol_solver");
        metadata.put("framework", "spring");
        metadata.put("injectionPoint", injectionPoint);
        String qualifier = qualifierValue(annotations);
        if (qualifier != null && !qualifier.isBlank()) {
            metadata.put("qualifier", qualifier);
        }
        if (annotations.stream().anyMatch(annotation -> "Primary".equals(annotation.getName().getIdentifier()))) {
            metadata.put("primary", true);
        }
        return Map.copyOf(metadata);
    }

    private String qualifierValue(List<AnnotationExpr> annotations) {
        String qualifier = annotationValue(annotations, "Qualifier", "value");
        if (qualifier != null && !qualifier.isBlank()) {
            return qualifier;
        }
        String resource = annotationValue(annotations, "Resource", "name");
        return resource == null || resource.isBlank() ? null : resource;
    }

    private Map<String, Object> beanMetadata(MethodDeclaration method, String beanName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "java");
        metadata.put("framework", "spring");
        metadata.put("beanName", beanName);
        metadata.put("returnType", method.getType().asString());
        if (method.getAnnotationByName("Primary").isPresent()) {
            metadata.put("primary", true);
        }
        return Map.copyOf(metadata);
    }

    private Map<String, Object> transactionMetadata(List<AnnotationExpr> annotations) {
        return transactionMetadata(annotations, false);
    }

    private Map<String, Object> transactionMetadata(List<AnnotationExpr> annotations, boolean inherited) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "java");
        metadata.put("framework", "spring");
        if (inherited) {
            metadata.put("transactionInherited", true);
            metadata.put("evidenceKind", "inferred");
        }
        putIfPresent(metadata, "readOnly", annotationValue(annotations, "Transactional", "readOnly"));
        putIfPresent(metadata, "propagation", annotationValue(annotations, "Transactional", "propagation"));
        putIfPresent(metadata, "rollbackFor", annotationValue(annotations, "Transactional", "rollbackFor"));
        return Map.copyOf(metadata);
    }

    private Map<String, Object> endpointMetadata(MethodDeclaration method, AnnotationExpr annotation, String methodName, String route) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "java");
        metadata.put("framework", "spring");
        metadata.put("httpMapping", methodName);
        metadata.put("route", route == null ? "" : route);
        metadata.put("httpMethod", httpMethod(methodName));
        Set<String> pathVariables = new LinkedHashSet<>();
        Set<String> requestParams = new LinkedHashSet<>();
        Set<String> requestBodies = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            if (hasAnnotation(parameter, Set.of("PathVariable"))) {
                pathVariables.add(firstNonBlank(annotationValue(parameter.getAnnotations(), "PathVariable", "value", "name"), parameter.getNameAsString()));
            }
            if (hasAnnotation(parameter, Set.of("RequestParam"))) {
                requestParams.add(firstNonBlank(annotationValue(parameter.getAnnotations(), "RequestParam", "value", "name"), parameter.getNameAsString()));
            }
            if (hasAnnotation(parameter, Set.of("RequestBody"))) {
                requestBodies.add(parameter.getNameAsString());
            }
        }
        if (!pathVariables.isEmpty()) metadata.put("pathVariables", List.copyOf(pathVariables));
        if (!requestParams.isEmpty()) metadata.put("requestParams", List.copyOf(requestParams));
        if (!requestBodies.isEmpty()) metadata.put("requestBodyParams", List.copyOf(requestBodies));
        return Map.copyOf(metadata);
    }

    private String httpMethod(String mappingName) {
        return switch (mappingName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "PatchMapping" -> "PATCH";
            case "DeleteMapping" -> "DELETE";
            default -> "";
        };
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private Map<String, Object> withMetadata(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return Map.copyOf(merged);
    }

    private String lowerFirst(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }

    private String truncate(String value, int max) {
        if (value == null || max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
        return typeNode(qualified, path, chunkId, "");
    }

    private CodeGraphNode typeNode(String qualified, String path, UUID chunkId, String springRole) {
        String name = qualified.contains(".") ? qualified.substring(qualified.lastIndexOf('.') + 1) : qualified;
        Map<String, Object> metadata = springRole == null || springRole.isBlank()
                ? Map.of("language", "java")
                : Map.of("language", "java", "framework", "spring", "springRole", springRole);
        return new CodeGraphNode(typeKey(qualified), "type", name, qualified, path, chunkId, metadata);
    }

    private String fileKey(String path) { return "file:" + path; }
    private String typeKey(String qualified) { return "type:java:" + qualified; }
    private String methodKey(String signature) { return "method:java:" + signature; }

    private void addNode(Map<String, CodeGraphNode> nodes, CodeGraphNode node) {
        nodes.putIfAbsent(node.key(), node);
    }

    private void addEdge(Map<String, CodeGraphEdge> edges, String source, String target, String type,
                         double confidence, UUID chunkId, String sourceName) {
        addEdge(edges, source, target, type, confidence, chunkId, Map.of("source", sourceName));
    }

    private void addEdge(Map<String, CodeGraphEdge> edges, String source, String target, String type,
                         double confidence, UUID chunkId, Map<String, Object> metadata) {
        if (source == null || target == null || source.equals(target)) {
            return;
        }
        edges.putIfAbsent(source + "|" + type + "|" + target,
                new CodeGraphEdge(source, target, type, confidence, chunkId, metadata == null ? Map.of() : metadata));
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
