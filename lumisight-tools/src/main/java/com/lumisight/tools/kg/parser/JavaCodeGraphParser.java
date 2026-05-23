package com.lumisight.tools.kg.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.lumisight.tools.kg.model.EdgeType;
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

        Map<String, String> methodSimpleKeyToId = new HashMap<>();

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
                methodSimpleKeyToId.put(method.getNameAsString() + "#" + method.getParameters().size(), methodNode.id());
            });
        });

        // 仅在同文件内建立调用边，按“方法名 + 参数个数”做近似匹配。
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<ClassOrInterfaceDeclaration> ownerClass = method.findAncestor(ClassOrInterfaceDeclaration.class);
            if (ownerClass.isEmpty()) {
                return;
            }
            String classQn = pkg + "." + ownerClass.get().getNameAsString();
            String fromMethodQn = classQn + "#" + method.getNameAsString() + "(" + method.getParameters().size() + ")";
            String fromNodeId = NodeIdUtils.nodeId(NodeType.METHOD, fromMethodQn);

            method.findAll(MethodCallExpr.class).forEach(call -> {
                String targetKey = call.getNameAsString() + "#" + call.getArguments().size();
                String toNodeId = methodSimpleKeyToId.get(targetKey);
                if (toNodeId != null) {
                    addEdge(fragment, fromNodeId, toNodeId, EdgeType.METHOD_CALLS_METHOD, sourceFile);
                }
            });
        });

        return fragment;
    }

    private void addEdge(ParsedGraphFragment fragment, String from, String to, EdgeType type, String sourceFile) {
        String edgeId = NodeIdUtils.edgeId(from, to, type);
        fragment.getEdges().put(edgeId, new GraphEdge(edgeId, from, to, type, sourceFile));
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
