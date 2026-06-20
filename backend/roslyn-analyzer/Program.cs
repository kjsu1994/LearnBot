using System.Text.Json;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

if (args.Length != 1 || !Directory.Exists(args[0]))
{
    Console.Error.WriteLine("Usage: LearnBot.RoslynAnalyzer <repository-root>");
    return 2;
}

var root = Path.GetFullPath(args[0]);
var files = Directory.EnumerateFiles(root, "*.cs", SearchOption.AllDirectories)
    .Where(path => !path.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")
        && !path.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}"))
    .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
    .ToArray();
var trees = files.Select(path => CSharpSyntaxTree.ParseText(File.ReadAllText(path), path: path)).ToArray();
var references = ((string?)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES") ?? "")
    .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
    .Select(path => MetadataReference.CreateFromFile(path));
var compilation = CSharpCompilation.Create("LearnBot.IndexedRepository", trees, references,
    new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));

var nodes = new Dictionary<string, GraphNode>(StringComparer.Ordinal);
var edges = new Dictionary<string, GraphEdge>(StringComparer.Ordinal);
var entityTypes = new HashSet<string>(StringComparer.Ordinal);

foreach (var tree in trees)
{
    var model = compilation.GetSemanticModel(tree, ignoreAccessibility: true);
    var syntaxRoot = await tree.GetRootAsync();
    foreach (var declaration in syntaxRoot.DescendantNodes().OfType<BaseTypeDeclarationSyntax>())
    {
        if (model.GetDeclaredSymbol(declaration) is not INamedTypeSymbol symbol) continue;
        var typeName = TypeName(symbol);
        var file = Relative(root, tree.FilePath);
        var line = Line(declaration);
        AddNode(TypeKey(typeName), "type", symbol.Name, typeName, file, line);
        AddEdge(FileKey(file), TypeKey(typeName), "DEFINES", 1.0, file, line, "roslyn_semantic_model");
        foreach (var attribute in symbol.GetAttributes()) AddAnnotation(TypeKey(typeName), attribute, file, line);
        if (HasAttribute(symbol, "Table", "Entity", "Owned"))
        {
            entityTypes.Add(typeName);
            var table = AttributeString(symbol, "Table") ?? symbol.Name;
            var tableKey = "table:" + table.ToLowerInvariant();
            AddNode(tableKey, "table", table, table, file, line);
            AddEdge(TypeKey(typeName), tableKey, "MAPS_TO_TABLE", .98, file, line, "roslyn_semantic_model");
        }
        if (symbol.BaseType is { SpecialType: not SpecialType.System_Object } baseType)
            AddTypeRelation(TypeKey(typeName), baseType, "EXTENDS", file, line);
        foreach (var iface in symbol.Interfaces) AddTypeRelation(TypeKey(typeName), iface, "IMPLEMENTS", file, line);
    }
}

foreach (var tree in trees)
{
    var model = compilation.GetSemanticModel(tree, ignoreAccessibility: true);
    var syntaxRoot = await tree.GetRootAsync();
    var file = Relative(root, tree.FilePath);
    foreach (var field in syntaxRoot.DescendantNodes().OfType<FieldDeclarationSyntax>())
    {
        foreach (var variable in field.Declaration.Variables)
        {
            if (model.GetDeclaredSymbol(variable) is not IFieldSymbol symbol) continue;
            var owner = TypeName(symbol.ContainingType);
            var key = FieldKey(owner, symbol.Name);
            AddNode(key, "field", symbol.Name, owner + "#" + symbol.Name, file, Line(variable));
            AddEdge(TypeKey(owner), key, "CONTAINS", 1.0, file, Line(variable), "roslyn_semantic_model");
            foreach (var attribute in symbol.GetAttributes()) AddAnnotation(key, attribute, file, Line(variable));
            if (HasAttribute(symbol, "Inject", "Autowired", "FromServices"))
                AddTypeRelation(TypeKey(owner), symbol.Type, "INJECTS", file, Line(variable));
        }
    }

    foreach (var declaration in syntaxRoot.DescendantNodes().OfType<BaseMethodDeclarationSyntax>())
    {
        if (model.GetDeclaredSymbol(declaration) is not IMethodSymbol method) continue;
        var signature = MethodSignature(method);
        var key = MethodKey(signature);
        var line = Line(declaration);
        AddNode(key, "method", method.Name, signature, file, line);
        AddEdge(TypeKey(TypeName(method.ContainingType)), key, "CONTAINS", 1.0, file, line, "roslyn_semantic_model");
        AddEdge(FileKey(file), key, "DEFINES", 1.0, file, line, "roslyn_semantic_model");
        foreach (var attribute in method.GetAttributes()) AddAnnotation(key, attribute, file, line);
        foreach (var parameter in method.Parameters)
        {
            AddTypeRelation(key, parameter.Type, "ACCEPTS", file, line);
            if (method.MethodKind == MethodKind.Constructor
                && (method.ContainingType.InstanceConstructors.Count(c => !c.IsImplicitlyDeclared) == 1
                    || HasAttribute(method, "Inject", "ActivatorUtilitiesConstructor")))
                AddTypeRelation(TypeKey(TypeName(method.ContainingType)), parameter.Type, "INJECTS", file, line);
        }
        if (!method.ReturnsVoid) AddTypeRelation(key, method.ReturnType, "RETURNS", file, line);
        if (method.OverriddenMethod is { } overridden)
        {
            var target = MethodSignature(overridden);
            AddNode(MethodKey(target), "method", overridden.Name, target, null, 0);
            AddEdge(key, MethodKey(target), "OVERRIDES", 1.0, file, line, "roslyn_semantic_model");
        }
        foreach (var implementation in method.ExplicitInterfaceImplementations)
        {
            var target = MethodSignature(implementation);
            AddNode(MethodKey(target), "method", implementation.Name, target, null, 0);
            AddEdge(key, MethodKey(target), "OVERRIDES", 1.0, file, line, "roslyn_semantic_model");
        }
        foreach (var iface in method.ContainingType.AllInterfaces)
        {
            foreach (var member in iface.GetMembers(method.Name).OfType<IMethodSymbol>())
            {
                if (!SymbolEqualityComparer.Default.Equals(method.ContainingType.FindImplementationForInterfaceMember(member), method)) continue;
                var target = MethodSignature(member);
                AddNode(MethodKey(target), "method", member.Name, target, null, 0);
                AddEdge(key, MethodKey(target), "OVERRIDES", 1.0, file, line, "roslyn_semantic_model");
            }
        }
        AddEndpoint(key, method, file, line);

        foreach (var invocation in declaration.DescendantNodes().OfType<InvocationExpressionSyntax>())
        {
            var target = model.GetSymbolInfo(invocation).Symbol as IMethodSymbol
                ?? model.GetSymbolInfo(invocation).CandidateSymbols.OfType<IMethodSymbol>().SingleOrDefault();
            if (target is null) continue;
            target = target.ReducedFrom ?? target;
            var targetSignature = MethodSignature(target);
            AddNode(MethodKey(targetSignature), "method", target.Name, targetSignature, null, 0);
            AddEdge(key, MethodKey(targetSignature), "CALLS", 1.0, file, Line(invocation), "roslyn_semantic_model");
            if (entityTypes.Contains(TypeName(target.ContainingType)))
                AddEdge(key, TypeKey(TypeName(target.ContainingType)), "USES_ENTITY", .98, file, Line(invocation), "roslyn_semantic_model");
        }
        foreach (var identifier in declaration.DescendantNodes().OfType<IdentifierNameSyntax>())
        {
            if (model.GetSymbolInfo(identifier).Symbol is not IFieldSymbol field) continue;
            var fieldKey = FieldKey(TypeName(field.ContainingType), field.Name);
            AddNode(fieldKey, "field", field.Name, TypeName(field.ContainingType) + "#" + field.Name, file, Line(identifier));
            var write = identifier.Ancestors().OfType<AssignmentExpressionSyntax>()
                .Any(assignment => assignment.Left.Span.Contains(identifier.Span));
            AddEdge(key, fieldKey, write ? "WRITES_FIELD" : "READS_FIELD", .98, file, Line(identifier), "roslyn_semantic_model");
        }
    }
}

Console.Write(JsonSerializer.Serialize(new GraphOutput(nodes.Values, edges.Values), new JsonSerializerOptions
{
    PropertyNamingPolicy = JsonNamingPolicy.CamelCase
}));
return 0;

void AddEndpoint(string methodKey, IMethodSymbol method, string file, int line)
{
    foreach (var attribute in method.GetAttributes().Where(a => IsAttribute(a, "Route", "HttpGet", "HttpPost", "HttpPut", "HttpPatch", "HttpDelete")))
    {
        var route = attribute.ConstructorArguments.FirstOrDefault().Value?.ToString() ?? "";
        var name = attribute.AttributeClass?.Name.Replace("Attribute", "") + ":" + route;
        var key = "endpoint:csharp:" + name + ":" + methodKey;
        AddNode(key, "endpoint", name, name, file, line);
        AddEdge(methodKey, key, "EXPOSES_ENDPOINT", .99, file, line, "roslyn_semantic_model");
    }
}

void AddAnnotation(string source, AttributeData attribute, string file, int line)
{
    var qualified = attribute.AttributeClass is null ? "unknown" : TypeName(attribute.AttributeClass);
    var key = "annotation:csharp:" + qualified;
    AddNode(key, "annotation", attribute.AttributeClass?.Name.Replace("Attribute", "") ?? "unknown", qualified, file, line);
    AddEdge(source, key, "ANNOTATED_BY", .99, file, line, "roslyn_semantic_model");
}

void AddTypeRelation(string source, ITypeSymbol type, string relation, string file, int line)
{
    var qualified = TypeName(type);
    if (string.IsNullOrWhiteSpace(qualified) || qualified == "void") return;
    AddNode(TypeKey(qualified), "type", type.Name, qualified, null, 0);
    AddEdge(source, TypeKey(qualified), relation, .98, file, line, "roslyn_semantic_model");
}

void AddNode(string key, string type, string name, string qualified, string? file, int line)
    => nodes.TryAdd(key, new GraphNode(key, type, name, qualified, file, line));

void AddEdge(string source, string target, string type, double confidence, string file, int line, string edgeSource)
{
    if (source == target) return;
    var edge = new GraphEdge(source, target, type, confidence, file, line, edgeSource);
    edges.TryAdd(source + "|" + type + "|" + target, edge);
}

bool HasAttribute(ISymbol symbol, params string[] names) => symbol.GetAttributes().Any(a => IsAttribute(a, names));
bool IsAttribute(AttributeData attribute, params string[] names)
{
    var name = attribute.AttributeClass?.Name.Replace("Attribute", "");
    return name is not null && names.Contains(name, StringComparer.Ordinal);
}
string? AttributeString(ISymbol symbol, string name) => symbol.GetAttributes()
    .FirstOrDefault(a => IsAttribute(a, name))?.ConstructorArguments.FirstOrDefault().Value?.ToString();
string TypeName(ITypeSymbol symbol) => symbol.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat).Replace("global::", "");
string MethodSignature(IMethodSymbol method) => TypeName(method.ContainingType) + "." +
    (method.MethodKind == MethodKind.Constructor ? "<init>" : method.Name) + "(" +
    string.Join(",", method.Parameters.Select(p => TypeName(p.Type))) + ")";
string FileKey(string path) => "file:" + path;
string TypeKey(string name) => "type:csharp:" + name;
string MethodKey(string signature) => "method:csharp:" + signature;
string FieldKey(string owner, string name) => "field:csharp:" + owner + "#" + name;
string Relative(string repositoryRoot, string path) => Path.GetRelativePath(repositoryRoot, path).Replace('\\', '/');
int Line(SyntaxNode node) => node.GetLocation().GetLineSpan().StartLinePosition.Line + 1;

record GraphOutput(IEnumerable<GraphNode> Nodes, IEnumerable<GraphEdge> Edges);
record GraphNode(string Key, string Type, string Name, string QualifiedName, string? FilePath, int Line);
record GraphEdge(string SourceKey, string TargetKey, string Type, double Confidence, string FilePath, int Line, string Source);
