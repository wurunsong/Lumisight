package com.lumisight.tools.kg.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.lumisight.tools.kg.model.EdgeType;
import com.lumisight.tools.kg.model.GraphElementStatus;
import com.lumisight.tools.kg.model.GraphEdge;
import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.NodeType;
import com.lumisight.tools.kg.model.node.ClassNode;
import com.lumisight.tools.kg.model.node.MethodNode;
import com.lumisight.tools.kg.model.node.ModuleNode;
import com.lumisight.tools.kg.model.node.PackageNode;
import com.lumisight.tools.kg.util.NodeIdUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class JavaCodeGraphParser {

    public ParsedGraphFragment parseFile(Path repoRoot, Path javaFile) {
        String sourceFile = repoRoot.relativize(javaFile).toString();
        String moduleName = resolveModuleName(repoRoot, javaFile);
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaFile);
        } catch (Exception e) {
            // 语法异常文件直接跳过，避免阻断整个构建流程。
            return new ParsedGraphFragment();
        }
        return parseCompilationUnit(repoRoot, sourceFile, moduleName, cu);
    }

    public ParsedGraphFragment parseSource(Path repoRoot, String sourceFile, String sourceContent) {
        String moduleName = resolveModuleName(repoRoot, repoRoot.resolve(sourceFile));
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(sourceContent);
        } catch (Exception e) {
            return new ParsedGraphFragment();
        }
        return parseCompilationUnit(repoRoot, sourceFile, moduleName, cu);
    }

    private ParsedGraphFragment parseCompilationUnit(Path repoRoot, String sourceFile, String moduleName, CompilationUnit cu) {
        // 将单个 Java 文件解析为局部图谱片段（节点 + 边）。
        ParsedGraphFragment fragment = new ParsedGraphFragment();
        String repoName = repoRoot.getFileName().toString();

        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default");

        GraphNode moduleNode = new ModuleNode(moduleName, sourceFile, repoName).toGraphNode();
        GraphNode packageNode = new PackageNode(moduleName, pkg, sourceFile, repoName).toGraphNode();
        fragment.getNodes().put(moduleNode.id(), moduleNode);
        fragment.getNodes().put(packageNode.id(), packageNode);
        addEdge(fragment, moduleNode.id(), packageNode.id(), EdgeType.MODULE_CONTAINS_PACKAGE, sourceFile);

        Map<String, String> importedClassMap = buildImportClassMap(cu);

        // 构建层级关系：module -> package -> class -> method。
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String classQn = pkg + "." + cls.getNameAsString();
            GraphNode classNode = new ClassNode(
                    moduleName,
                    pkg,
                    cls.getNameAsString(),
                    classQn,
                    sourceFile,
                    repoName
            ).toGraphNode();
            fragment.getNodes().put(classNode.id(), classNode);
            addEdge(fragment, packageNode.id(), classNode.id(), EdgeType.PACKAGE_CONTAINS_CLASS, sourceFile);

            cls.findAll(MethodDeclaration.class).forEach(method -> {
                String methodQn = classQn + "#" + method.getNameAsString() + "(" + method.getParameters().size() + ")";
                Integer startLine = method.getBegin().map(p -> p.line).orElse(null);
                Integer endLine = method.getEnd().map(p -> p.line).orElse(null);
                GraphNode methodNode = new MethodNode(
                        moduleName,
                        pkg,
                        cls.getNameAsString(),
                        method.getNameAsString(),
                        method.getParameters().size(),
                        methodQn,
                        startLine,
                        endLine,
                        sourceFile,
                        repoName
                ).toGraphNode();
                fragment.getNodes().put(methodNode.id(), methodNode);
                addEdge(fragment, classNode.id(), methodNode.id(), EdgeType.CLASS_CONTAINS_METHOD, sourceFile);
            });
        });

        // 按统一 ID 规则建立方法调用边，允许跨文件先建边后补点。
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<ClassOrInterfaceDeclaration> ownerClass = method.findAncestor(ClassOrInterfaceDeclaration.class);
            if (ownerClass.isEmpty()) {
                return;
            }
            String classQn = pkg + "." + ownerClass.get().getNameAsString();
            String fromMethodQn = classQn + "#" + method.getNameAsString() + "(" + method.getParameters().size() + ")";
            String fromNodeId = NodeIdUtils.nodeId(NodeType.METHOD, fromMethodQn);

            method.findAll(MethodCallExpr.class).forEach(call -> {
                String targetClassQn = resolveTargetClassQualifiedName(
                        call.getScope(),
                        ownerClass.get().getNameAsString(),
                        pkg,
                        importedClassMap
                );
                String targetMethodQn = targetClassQn + "#" + call.getNameAsString() + "(" + call.getArguments().size() + ")";
                String toNodeId = NodeIdUtils.nodeId(NodeType.METHOD, targetMethodQn);
                addEdge(fragment, fromNodeId, toNodeId, EdgeType.METHOD_CALLS_METHOD, sourceFile);
            });
        });

        return fragment;
    }

    private Map<String, String> buildImportClassMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.getImports().forEach(importDecl -> {
            if (importDecl.isAsterisk()) {
                return;
            }
            String qn = importDecl.getNameAsString();
            int idx = qn.lastIndexOf('.');
            if (idx <= 0 || idx == qn.length() - 1) {
                return;
            }
            String simple = qn.substring(idx + 1);
            map.put(simple, qn);
        });
        return map;
    }

    private String resolveTargetClassQualifiedName(
            Optional<Expression> scope,
            String currentClassName,
            String pkg,
            Map<String, String> importedClassMap
    ) {
        if (scope.isEmpty()) {
            return pkg + "." + currentClassName;
        }

        String scopeText = scope.get().toString();
        String candidate = scopeText.contains(".")
                ? scopeText.substring(scopeText.lastIndexOf('.') + 1)
                : scopeText;

        // 仅把看起来像类名的 scope 作为跨类调用目标，其他情况回退到当前类。
        if (candidate.isEmpty() || !Character.isUpperCase(candidate.charAt(0))) {
            return pkg + "." + currentClassName;
        }
        if (importedClassMap.containsKey(candidate)) {
            return importedClassMap.get(candidate);
        }
        return pkg + "." + candidate;
    }

    private void addEdge(ParsedGraphFragment fragment, String from, String to, EdgeType type, String sourceFile) {
        String edgeId = NodeIdUtils.edgeId(from, to, type);
        fragment.getEdges().put(edgeId, new GraphEdge(edgeId, from, to, type, sourceFile, GraphElementStatus.ACTIVE));
    }

    private String resolveModuleName(Path repoRoot, Path javaFile) {
        Path cursor = javaFile.getParent();
        while (cursor != null && !cursor.equals(repoRoot.getParent())) {
            if (cursor.resolve("pom.xml").toFile().exists() && !cursor.equals(repoRoot)) {
                return cursor.getFileName().toString();
            }
            if (cursor.equals(repoRoot)) {
                break;
            }
            cursor = cursor.getParent();
        }
        return repoRoot.getFileName().toString();
    }
}
