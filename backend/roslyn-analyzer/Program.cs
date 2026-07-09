using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml;
using System.Xml.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

if (args.Length < 1 || !Directory.Exists(args[0]))
{
    Console.Error.WriteLine("Usage: LearnBot.RoslynAnalyzer <repository-root> [SIMPLE|SAFE_PROJECT|SAFE_SOLUTION]");
    return 2;
}

var root = Path.GetFullPath(args[0]);
var requestedMode = NormalizeMode(args.Length > 1 ? args[1] : "SIMPLE");
var dotnetUiEnabled = args.Length < 3 || bool.TryParse(args[2], out var parsedUiFlag) && parsedUiFlag;
var inputs = ResolveInputs(root, requestedMode);
var parseOptions = new CSharpParseOptions(preprocessorSymbols: inputs.DefineConstants);
var trees = new List<SyntaxTree>();
var failedFiles = 0;
foreach (var path in inputs.Files)
{
    try
    {
        trees.Add(CSharpSyntaxTree.ParseText(File.ReadAllText(path), parseOptions, path: path));
    }
    catch
    {
        failedFiles++;
    }
}
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
        if (dotnetUiEnabled && symbol.AllInterfaces.Any(iface => TypeName(iface).EndsWith("INotifyPropertyChanged", StringComparison.Ordinal)))
            AddNode(TypeKey(typeName), "view_model", symbol.Name, typeName, file, line);
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
            if (dotnetUiEnabled && file.EndsWith(".Designer.cs", StringComparison.OrdinalIgnoreCase))
            {
                var controlKey = ControlKey(owner, symbol.Name);
                AddNode(controlKey, "winforms_control", symbol.Name, owner + "#" + symbol.Name, file, Line(variable));
                AddEdge(TypeKey(owner), controlKey, "DECLARES_CONTROL", .96, file, Line(variable), "winforms_designer");
            }
            foreach (var attribute in symbol.GetAttributes()) AddAnnotation(key, attribute, file, Line(variable));
            if (HasAttribute(symbol, "Inject", "Autowired", "FromServices"))
                AddTypeRelation(TypeKey(owner), symbol.Type, "INJECTS", file, Line(variable));
            if (dotnetUiEnabled && variable.Initializer?.Value is ObjectCreationExpressionSyntax fieldCommandCreation)
                AddCommandExecutionEdge(model, fieldCommandCreation, owner, symbol.Name, file);
        }
    }

    foreach (var property in syntaxRoot.DescendantNodes().OfType<PropertyDeclarationSyntax>())
    {
        if (!dotnetUiEnabled || property.Initializer?.Value is not ObjectCreationExpressionSyntax propertyCommandCreation) continue;
        if (model.GetDeclaredSymbol(property) is not IPropertySymbol symbol) continue;
        AddCommandExecutionEdge(model, propertyCommandCreation, TypeName(symbol.ContainingType), symbol.Name, file);
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
            var target = ResolveMethodSymbol(model.GetSymbolInfo(invocation));
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
        if (dotnetUiEnabled) AddCommandExecutionEdges(model, declaration, method, file);
    }
}

if (dotnetUiEnabled)
{
    AddWpfXamlGraph(root);
    AddWinFormsDesignerGraph(root, compilation, trees);
}

Console.Write(JsonSerializer.Serialize(new GraphOutput(
    nodes.Values, edges.Values, inputs.Mode, inputs.ProjectCount, trees.Count, inputs.FailedProjects, failedFiles
), new JsonSerializerOptions
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

void AddNode(string key, string type, string name, string qualified, string? file, int line, Dictionary<string, object>? metadata = null)
    => nodes.TryAdd(key, new GraphNode(key, type, name, qualified, file, line, metadata));

void AddEdge(string source, string target, string type, double confidence, string file, int line, string edgeSource, Dictionary<string, object>? metadata = null)
{
    if (source == target) return;
    var edge = new GraphEdge(source, target, type, confidence, file, line, edgeSource, metadata);
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
string PropertyKey(string owner, string name) => "property:csharp:" + owner + "#" + name;
string CommandKey(string owner, string name) => "command:csharp:" + owner + "#" + name;
string ControlKey(string owner, string name) => "control:csharp:" + owner + "#" + name;
string Relative(string repositoryRoot, string path) => Path.GetRelativePath(repositoryRoot, path).Replace('\\', '/');
int Line(SyntaxNode node) => node.GetLocation().GetLineSpan().StartLinePosition.Line + 1;

void AddCommandExecutionEdges(SemanticModel model, BaseMethodDeclarationSyntax declaration, IMethodSymbol ownerMethod, string file)
{
    foreach (var creation in declaration.DescendantNodes().OfType<ObjectCreationExpressionSyntax>())
    {
        var owner = TypeName(ownerMethod.ContainingType);
        var fallbackName = ownerMethod.Name.EndsWith("Command", StringComparison.Ordinal) ? ownerMethod.Name : "";
        AddCommandExecutionEdge(model, creation, owner, fallbackName, file);
    }
}

void AddCommandExecutionEdge(SemanticModel model, ObjectCreationExpressionSyntax creation, string owner, string commandName, string file)
{
    var createdType = model.GetTypeInfo(creation).Type;
    if (createdType is null || !LooksLikeCommandType(TypeName(createdType))) return;
    var methodGroup = creation.ArgumentList?.Arguments
        .Select(argument => argument.Expression)
        .FirstOrDefault(IsMethodGroupExpression);
    if (methodGroup is null) return;
    var execute = model.GetSymbolInfo(methodGroup).Symbol as IMethodSymbol
        ?? ResolveMethodSymbol(model.GetSymbolInfo(methodGroup));
    if (execute is null) return;
    if (string.IsNullOrWhiteSpace(commandName))
    {
        commandName = MethodGroupName(methodGroup) + "Command";
    }
    var commandKey = CommandKey(owner, commandName);
    AddNode(commandKey, "command", commandName, owner + "#" + commandName, file, Line(creation));
    var executeSignature = MethodSignature(execute);
    AddNode(MethodKey(executeSignature), "method", execute.Name, executeSignature, file, Line(methodGroup));
    AddEdge(commandKey, MethodKey(executeSignature), "COMMAND_EXECUTES", .82, file, Line(methodGroup), "roslyn_command");
}

bool IsMethodGroupExpression(ExpressionSyntax expression) =>
    expression is IdentifierNameSyntax || expression is MemberAccessExpressionSyntax;

IMethodSymbol? ResolveMethodSymbol(SymbolInfo symbolInfo)
{
    if (symbolInfo.Symbol is IMethodSymbol method) return method;
    return symbolInfo.CandidateSymbols
        .OfType<IMethodSymbol>()
        .OrderBy(candidate => candidate.Parameters.Length)
        .ThenBy(candidate => candidate.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat), StringComparer.Ordinal)
        .FirstOrDefault();
}

string MethodGroupName(ExpressionSyntax expression) => expression switch
{
    IdentifierNameSyntax identifier => identifier.Identifier.Text,
    MemberAccessExpressionSyntax memberAccess => memberAccess.Name.Identifier.Text,
    _ => expression.ToString()
};

void AddWpfXamlGraph(string repositoryRoot)
{
    XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
    foreach (var path in Directory.EnumerateFiles(repositoryRoot, "*.xaml", SearchOption.AllDirectories)
                 .Where(path => !path.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")
                     && !path.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}")))
    {
        try
        {
            var document = XDocument.Load(path, LoadOptions.SetLineInfo);
            var relative = Relative(repositoryRoot, path);
            var xamlClass = document.Root?.Attribute(x + "Class")?.Value;
            if (string.IsNullOrWhiteSpace(xamlClass)) continue;
            var viewKey = "view:xaml:" + xamlClass;
            AddNode(viewKey, "xaml_view", ShortName(xamlClass), xamlClass, relative, 1);
            AddNode(TypeKey(xamlClass), "type", ShortName(xamlClass), xamlClass, CodeBehindPath(relative), 0);
            AddEdge(viewKey, TypeKey(xamlClass), "CODE_BEHIND", .96, relative, 1, "xaml");
            AddEdge(TypeKey(xamlClass), viewKey, "PARTIAL_OF", .90, relative, 1, "xaml");
            AddViewModelCandidate(viewKey, xamlClass, relative, 1);

            foreach (var element in document.Descendants())
            {
                var line = element is IXmlLineInfo info && info.HasLineInfo() ? info.LineNumber : 1;
                var controlName = element.Attribute(x + "Name")?.Value ?? element.Attribute("Name")?.Value;
                var sourceKey = viewKey;
                if (!string.IsNullOrWhiteSpace(controlName))
                {
                    sourceKey = ControlKey(xamlClass, controlName);
                    AddNode(sourceKey, "xaml_control", controlName, xamlClass + "#" + controlName, relative, line);
                    AddEdge(viewKey, sourceKey, "DECLARES_CONTROL", .94, relative, line, "xaml");
                }

                foreach (var attribute in element.Attributes())
                {
                    var value = attribute.Value?.Trim() ?? "";
                    if (attribute.Name.LocalName.Equals("DataContext", StringComparison.OrdinalIgnoreCase))
                    {
                        AddDataContextEdges(sourceKey, xamlClass, value, relative, line);
                    }
                    if (IsWpfEvent(attribute.Name.LocalName) && !string.IsNullOrWhiteSpace(value) && !value.StartsWith("{", StringComparison.Ordinal))
                    {
                        var handlerKey = MethodKey(xamlClass + "." + value + "(System.Object,System.Windows.RoutedEventArgs)");
                        AddNode(handlerKey, "method", value, xamlClass + "." + value, CodeBehindPath(relative), line);
                        AddEdge(sourceKey, handlerKey, "HANDLES_EVENT", .86, relative, line, "xaml_event");
                    }
                    var binding = BindingInfo(value);
                    if (binding is null) continue;
                    var bindingMetadata = BindingMetadata(attribute.Name.LocalName, binding);
                    if (attribute.Name.LocalName.Equals("Command", StringComparison.OrdinalIgnoreCase) || binding.Path.EndsWith("Command", StringComparison.Ordinal))
                    {
                        var commandKey = CommandKey(xamlClass, binding.Path);
                        AddNode(commandKey, "command", binding.Path, xamlClass + "#" + binding.Path, relative, line, bindingMetadata);
                        AddEdge(sourceKey, commandKey, "USES_COMMAND", .68, relative, line, "xaml_binding", bindingMetadata);
                        AddEdge(sourceKey, commandKey, "COMMAND_BINDING", .66, relative, line, "xaml_binding", bindingMetadata);
                        AddViewModelCommandCandidate(sourceKey, xamlClass, binding.Path, relative, line, bindingMetadata);
                    }
                    else
                    {
                        var propertyKey = PropertyKey(xamlClass, binding.Path);
                        AddNode(propertyKey, "property", binding.Path, xamlClass + "#" + binding.Path, relative, line, bindingMetadata);
                        AddEdge(sourceKey, propertyKey, "BINDS_TO", .62, relative, line, "xaml_binding", bindingMetadata);
                        AddViewModelPropertyCandidate(sourceKey, xamlClass, binding.Path, relative, line, bindingMetadata);
                    }
                }
                if (element.Name.LocalName.EndsWith(".DataContext", StringComparison.OrdinalIgnoreCase)
                    || element.Name.LocalName.Equals("DataContext", StringComparison.OrdinalIgnoreCase))
                {
                    var viewModelElement = element.Elements().FirstOrDefault();
                    if (viewModelElement is not null)
                    {
                        var viewModelType = viewModelElement.Name.LocalName;
                        AddNode(TypeKey(viewModelType), "view_model", ShortName(viewModelType), viewModelType, null, 0);
                        AddEdge(sourceKey, TypeKey(viewModelType), "DATA_CONTEXT", .82, relative, line, "xaml_datacontext",
                            new Dictionary<string, object> { ["evidenceKind"] = "direct", ["confidenceReason"] = "explicit_datacontext_element" });
                    }
                }
            }
        }
        catch
        {
            // Malformed XAML must not block C# semantic analysis.
        }
    }
}

void AddWinFormsDesignerGraph(string repositoryRoot, Compilation compilation, List<SyntaxTree> syntaxTrees)
{
    foreach (var tree in syntaxTrees.Where(tree => tree.FilePath.EndsWith(".Designer.cs", StringComparison.OrdinalIgnoreCase)))
    {
        var model = compilation.GetSemanticModel(tree, ignoreAccessibility: true);
        var syntaxRoot = tree.GetRoot();
        var file = Relative(repositoryRoot, tree.FilePath);
        foreach (var assignment in syntaxRoot.DescendantNodes().OfType<AssignmentExpressionSyntax>()
                     .Where(node => node.IsKind(SyntaxKind.AddAssignmentExpression)))
        {
            if (assignment.Left is not MemberAccessExpressionSyntax memberAccess) continue;
            var handler = HandlerSymbol(model, assignment.Right);
            var ownerType = syntaxRoot.DescendantNodes().OfType<TypeDeclarationSyntax>().FirstOrDefault();
            var owner = handler?.ContainingType is null
                ? ownerType is null ? "" : (model.GetDeclaredSymbol(ownerType) is INamedTypeSymbol ownerSymbol ? TypeName(ownerSymbol) : ownerType.Identifier.Text)
                : TypeName(handler.ContainingType);
            if (string.IsNullOrWhiteSpace(owner)) continue;
            var controlName = memberAccess.Expression.ToString();
            var controlKey = ControlKey(owner, controlName);
            AddNode(controlKey, "winforms_control", controlName, owner + "#" + controlName, file, Line(assignment));
            if (handler is not null)
            {
                var handlerSignature = MethodSignature(handler.ReducedFrom ?? handler);
                AddNode(MethodKey(handlerSignature), "method", handler.Name, handlerSignature, file.Replace(".Designer.cs", ".cs"), Line(assignment));
                AddEdge(controlKey, MethodKey(handlerSignature), "HANDLES_EVENT", .93, file, Line(assignment), "winforms_designer",
                    new Dictionary<string, object> { ["evidenceKind"] = "direct", ["confidenceReason"] = "resolved_event_handler_symbol" });
            }
            else
            {
                var handlerName = HandlerName(assignment.Right);
                if (string.IsNullOrWhiteSpace(handlerName)) continue;
                var handlerKey = MethodKey(owner + "." + handlerName + "(System.Object,System.EventArgs)");
                AddNode(handlerKey, "method", handlerName, owner + "." + handlerName, file.Replace(".Designer.cs", ".cs"), Line(assignment));
                AddEdge(controlKey, handlerKey, "HANDLES_EVENT", .58, file, Line(assignment), "winforms_designer",
                    new Dictionary<string, object> { ["evidenceKind"] = "candidate", ["confidenceReason"] = "event_handler_name_match" });
            }
        }
        foreach (var invocation in syntaxRoot.DescendantNodes().OfType<InvocationExpressionSyntax>())
        {
            if (invocation.Expression is not MemberAccessExpressionSyntax memberAccess) continue;
            if (!memberAccess.Name.Identifier.Text.Equals("Add", StringComparison.Ordinal)) continue;
            var collectionExpression = memberAccess.Expression.ToString();
            if (!IsWinFormsHierarchyCollection(collectionExpression)) continue;
            var ownerType = syntaxRoot.DescendantNodes().OfType<TypeDeclarationSyntax>().FirstOrDefault();
            if (ownerType is null) continue;
            var owner = model.GetDeclaredSymbol(ownerType) is INamedTypeSymbol ownerSymbol ? TypeName(ownerSymbol) : ownerType.Identifier.Text;
            var parentControl = ParentControlName(collectionExpression);
            var childControl = invocation.ArgumentList.Arguments.FirstOrDefault()?.Expression.ToString();
            if (string.IsNullOrWhiteSpace(childControl)) continue;
            AddNode(ControlKey(owner, parentControl), "winforms_control", parentControl, owner + "#" + parentControl, file, Line(invocation));
            AddNode(ControlKey(owner, childControl), "winforms_control", childControl, owner + "#" + childControl, file, Line(invocation));
            AddEdge(ControlKey(owner, parentControl), ControlKey(owner, childControl), "CONTAINS", .88, file, Line(invocation), "winforms_designer",
                new Dictionary<string, object> { ["sourceDetail"] = collectionExpression, ["evidenceKind"] = "direct" });
        }
    }
}

void AddDataContextEdges(string sourceKey, string xamlClass, string value, string file, int line)
{
    var explicitType = ExplicitXamlType(value);
    if (!string.IsNullOrWhiteSpace(explicitType))
    {
        AddNode(TypeKey(explicitType), "view_model", ShortName(explicitType), explicitType, null, 0);
        AddEdge(sourceKey, TypeKey(explicitType), "DATA_CONTEXT", .82, file, line, "xaml_datacontext",
            new Dictionary<string, object> { ["evidenceKind"] = "direct", ["confidenceReason"] = "explicit_datacontext_attribute" });
        return;
    }
    AddViewModelCandidate(sourceKey, xamlClass, file, line);
}

void AddViewModelCandidate(string sourceKey, string xamlClass, string file, int line)
{
    var candidate = xamlClass.EndsWith("View", StringComparison.Ordinal)
        ? xamlClass + "Model"
        : xamlClass + "ViewModel";
    AddNode(TypeKey(candidate), "view_model", ShortName(candidate), candidate, null, 0);
    AddEdge(sourceKey, TypeKey(candidate), "DATA_CONTEXT", .54, file, line, "xaml_naming_convention",
        new Dictionary<string, object> { ["evidenceKind"] = "candidate", ["confidenceReason"] = "viewmodel_naming_convention" });
}

void AddViewModelPropertyCandidate(string sourceKey, string xamlClass, string property, string file, int line, Dictionary<string, object>? metadata)
{
    var viewModel = xamlClass.EndsWith("View", StringComparison.Ordinal) ? xamlClass + "Model" : xamlClass + "ViewModel";
    var propertyKey = PropertyKey(viewModel, property);
    AddNode(TypeKey(viewModel), "view_model", ShortName(viewModel), viewModel, null, 0);
    AddNode(propertyKey, "property", property, viewModel + "#" + property, null, 0);
    AddEdge(sourceKey, propertyKey, "BINDS_TO", .50, file, line, "xaml_viewmodel_candidate",
        Merge(metadata, new Dictionary<string, object> { ["evidenceKind"] = "candidate", ["confidenceReason"] = "viewmodel_binding_candidate" }));
}

void AddViewModelCommandCandidate(string sourceKey, string xamlClass, string command, string file, int line, Dictionary<string, object>? metadata)
{
    var viewModel = xamlClass.EndsWith("View", StringComparison.Ordinal) ? xamlClass + "Model" : xamlClass + "ViewModel";
    var commandKey = CommandKey(viewModel, command);
    AddNode(TypeKey(viewModel), "view_model", ShortName(viewModel), viewModel, null, 0);
    AddNode(commandKey, "command", command, viewModel + "#" + command, null, 0);
    AddEdge(sourceKey, commandKey, "USES_COMMAND", .56, file, line, "xaml_viewmodel_candidate",
        Merge(metadata, new Dictionary<string, object> { ["evidenceKind"] = "candidate", ["confidenceReason"] = "viewmodel_command_candidate" }));
    AddEdge(sourceKey, commandKey, "COMMAND_TARGETS", .52, file, line, "xaml_viewmodel_candidate",
        Merge(metadata, new Dictionary<string, object> { ["evidenceKind"] = "candidate", ["confidenceReason"] = "viewmodel_command_candidate" }));
}

string? ExplicitXamlType(string value)
{
    if (string.IsNullOrWhiteSpace(value)) return null;
    var match = Regex.Match(value, @"\{x:Type\s+([^}]+)\}", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    if (match.Success) return match.Groups[1].Value.Trim();
    return null;
}

BindingDescriptor? BindingInfo(string value)
{
    if (!value.StartsWith("{Binding", StringComparison.Ordinal)) return null;
    var body = value.Trim('{', '}').Substring("Binding".Length).Trim();
    var parts = body.Split(',', StringSplitOptions.TrimEntries);
    var path = "";
    var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    foreach (var part in parts.Where(part => !string.IsNullOrWhiteSpace(part)))
    {
        var equals = part.IndexOf('=');
        if (equals > 0)
        {
            values[part[..equals].Trim()] = part[(equals + 1)..].Trim();
        }
        else if (string.IsNullOrWhiteSpace(path))
        {
            path = part.Trim();
        }
    }
    if (values.TryGetValue("Path", out var explicitPath)) path = explicitPath;
    return string.IsNullOrWhiteSpace(path) ? null : new BindingDescriptor(
        path,
        values.GetValueOrDefault("Mode"),
        values.GetValueOrDefault("UpdateSourceTrigger"),
        values.GetValueOrDefault("ElementName"),
        values.GetValueOrDefault("RelativeSource"),
        values.GetValueOrDefault("StaticResource") ?? StaticResourceKey(value)
    );
}

Dictionary<string, object> BindingMetadata(string targetProperty, BindingDescriptor binding)
{
    var metadata = new Dictionary<string, object>
    {
        ["targetProperty"] = targetProperty,
        ["bindingPath"] = binding.Path,
        ["evidenceKind"] = string.IsNullOrWhiteSpace(binding.ElementName) && string.IsNullOrWhiteSpace(binding.RelativeSource) ? "inferred" : "candidate",
        ["confidenceReason"] = "xaml_binding_path"
    };
    Put(metadata, "bindingMode", binding.Mode);
    Put(metadata, "updateSourceTrigger", binding.UpdateSourceTrigger);
    Put(metadata, "elementName", binding.ElementName);
    Put(metadata, "relativeSource", binding.RelativeSource);
    Put(metadata, "staticResourceKey", binding.StaticResourceKey);
    return metadata;
}

string? StaticResourceKey(string value)
{
    var match = Regex.Match(value, @"StaticResource\s+([^},]+)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    return match.Success ? match.Groups[1].Value.Trim() : null;
}

Dictionary<string, object> Merge(Dictionary<string, object>? left, Dictionary<string, object>? right)
{
    var merged = new Dictionary<string, object>();
    if (left is not null) foreach (var entry in left) merged[entry.Key] = entry.Value;
    if (right is not null) foreach (var entry in right) merged[entry.Key] = entry.Value;
    return merged;
}

void Put(Dictionary<string, object> metadata, string key, string? value)
{
    if (!string.IsNullOrWhiteSpace(value)) metadata[key] = value;
}

IMethodSymbol? HandlerSymbol(SemanticModel model, ExpressionSyntax expression)
{
    if (model.GetSymbolInfo(expression).Symbol is IMethodSymbol direct) return direct;
    if (expression is ObjectCreationExpressionSyntax creation)
        return creation.ArgumentList?.Arguments.Select(argument => model.GetSymbolInfo(argument.Expression).Symbol).OfType<IMethodSymbol>().FirstOrDefault();
    return ResolveMethodSymbol(model.GetSymbolInfo(expression));
}

string HandlerName(ExpressionSyntax expression) => expression switch
{
    IdentifierNameSyntax identifier => identifier.Identifier.Text,
    MemberAccessExpressionSyntax member => member.Name.Identifier.Text,
    ObjectCreationExpressionSyntax creation => creation.ArgumentList?.Arguments.FirstOrDefault()?.Expression switch
    {
        IdentifierNameSyntax identifier => identifier.Identifier.Text,
        MemberAccessExpressionSyntax member => member.Name.Identifier.Text,
        _ => ""
    },
    _ => ""
};

bool IsWinFormsHierarchyCollection(string expression) =>
    expression.EndsWith(".Controls", StringComparison.Ordinal)
    || expression.EndsWith(".TabPages", StringComparison.Ordinal)
    || expression.EndsWith(".Items", StringComparison.Ordinal)
    || expression.EndsWith(".DropDownItems", StringComparison.Ordinal);

string ParentControlName(string collectionExpression) =>
    Regex.Replace(collectionExpression, "\\.(Controls|TabPages|Items|DropDownItems)$", "");

bool IsWpfEvent(string name) => new[]
{
    "Click", "Loaded", "SelectionChanged", "TextChanged", "Checked", "Unchecked",
    "MouseDown", "MouseUp", "KeyDown", "KeyUp"
}.Contains(name, StringComparer.Ordinal);

bool LooksLikeCommandType(string typeName) =>
    typeName.EndsWith("ICommand", StringComparison.Ordinal)
    || typeName.EndsWith("RelayCommand", StringComparison.Ordinal)
    || typeName.EndsWith("DelegateCommand", StringComparison.Ordinal)
    || typeName.EndsWith("AsyncRelayCommand", StringComparison.Ordinal)
    || typeName.EndsWith("ReactiveCommand", StringComparison.Ordinal);

string CodeBehindPath(string xamlPath) => xamlPath + ".cs";
string ShortName(string qualified) => qualified.Contains('.') ? qualified[(qualified.LastIndexOf('.') + 1)..] : qualified;

AnalysisInputs ResolveInputs(string repositoryRoot, string mode)
{
    var projectFiles = mode == "SAFE_SOLUTION" ? ProjectsFromSolutions(repositoryRoot) :
        mode == "SAFE_PROJECT" ? Directory.EnumerateFiles(repositoryRoot, "*.csproj", SearchOption.AllDirectories).ToArray() :
        Array.Empty<string>();
    if (projectFiles.Length == 0)
    {
        var simpleFiles = SafeCsFiles(repositoryRoot).OrderBy(path => path, StringComparer.OrdinalIgnoreCase).ToArray();
        return new AnalysisInputs(simpleFiles, mode == "SIMPLE" ? "SIMPLE" : mode, 0, 0, Array.Empty<string>());
    }

    var files = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    var defines = new HashSet<string>(StringComparer.Ordinal);
    var failedProjects = 0;
    foreach (var project in projectFiles.Distinct(StringComparer.OrdinalIgnoreCase))
    {
        try
        {
            var document = XDocument.Load(project, LoadOptions.None);
            var projectDirectory = Path.GetDirectoryName(project)!;
            foreach (var file in SafeCsFiles(projectDirectory)) files.Add(file);
            foreach (var value in document.Descendants().Where(node => node.Name.LocalName == "DefineConstants"))
                foreach (var symbol in value.Value.Split(new[] { ';', ',' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                    defines.Add(symbol);
            foreach (var remove in document.Descendants().Where(node => node.Name.LocalName == "Compile")
                         .Select(node => node.Attribute("Remove")?.Value).Where(value => !string.IsNullOrWhiteSpace(value)))
                RemoveGlob(files, projectDirectory, remove!);
        }
        catch
        {
            failedProjects++;
        }
    }
    return new AnalysisInputs(files.OrderBy(path => path, StringComparer.OrdinalIgnoreCase).ToArray(), mode,
        projectFiles.Distinct(StringComparer.OrdinalIgnoreCase).Count(), failedProjects, defines.ToArray());
}

string NormalizeMode(string mode)
{
    return (mode ?? "SIMPLE").Trim().ToUpperInvariant() switch
    {
        "SOLUTION" => "SAFE_SOLUTION",
        "PROJECT" => "SAFE_PROJECT",
        "SAFE_SOLUTION" => "SAFE_SOLUTION",
        "SAFE_PROJECT" => "SAFE_PROJECT",
        _ => "SIMPLE"
    };
}

string[] ProjectsFromSolutions(string repositoryRoot)
{
    var projects = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    var pattern = new Regex("\"([^\"]+\\.csproj)\"", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    foreach (var solution in Directory.EnumerateFiles(repositoryRoot, "*.sln", SearchOption.AllDirectories))
    {
        var directory = Path.GetDirectoryName(solution)!;
        foreach (Match match in pattern.Matches(File.ReadAllText(solution)))
        {
            var path = Path.GetFullPath(Path.Combine(directory, match.Groups[1].Value.Replace('\\', Path.DirectorySeparatorChar)));
            if (path.StartsWith(repositoryRoot, StringComparison.OrdinalIgnoreCase) && File.Exists(path)) projects.Add(path);
        }
    }
    return projects.ToArray();
}

IEnumerable<string> SafeCsFiles(string directory) => Directory.EnumerateFiles(directory, "*.cs", SearchOption.AllDirectories)
    .Where(path => !path.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")
        && !path.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}"));

void RemoveGlob(HashSet<string> files, string directory, string glob)
{
    var normalized = glob.Replace('\\', '/');
    var regex = "^" + Regex.Escape(Path.GetFullPath(Path.Combine(directory, normalized)).Replace('\\', '/'))
        .Replace("\\*\\*", ".*").Replace("\\*", "[^/]*").Replace("\\?", ".") + "$";
    files.RemoveWhere(path => Regex.IsMatch(Path.GetFullPath(path).Replace('\\', '/'), regex, RegexOptions.IgnoreCase));
}

record AnalysisInputs(string[] Files, string Mode, int ProjectCount, int FailedProjects, string[] DefineConstants);
record GraphOutput(IEnumerable<GraphNode> Nodes, IEnumerable<GraphEdge> Edges, string Mode,
    int ProjectCount, int AnalyzedFiles, int FailedProjects, int FailedFiles);
record GraphNode(string Key, string Type, string Name, string QualifiedName, string? FilePath, int Line, Dictionary<string, object>? Metadata = null);
record GraphEdge(string SourceKey, string TargetKey, string Type, double Confidence, string FilePath, int Line, string Source, Dictionary<string, object>? Metadata = null);
record BindingDescriptor(string Path, string? Mode, string? UpdateSourceTrigger, string? ElementName, string? RelativeSource, string? StaticResourceKey);
