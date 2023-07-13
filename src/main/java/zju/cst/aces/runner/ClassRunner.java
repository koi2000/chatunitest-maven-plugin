package zju.cst.aces.runner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import zju.cst.aces.parser.ClassParser;
import zju.cst.aces.utils.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class ClassRunner extends AbstractRunner {
    public ClassInfo classInfo;
    public File infoDir;
    public ClassParser classParser;

    public ClassRunner(String fullClassName, String parsePath, String testPath) throws IOException {
        super(fullClassName, parsePath, testPath);
        infoDir = new File(parseOutputPath + File.separator + fullClassName.replace(".", File.separator));
        if (!infoDir.isDirectory()) {
            log.error("Error: " + fullClassName + " no parsed info found");
        }
        File classInfoFile = new File(infoDir + File.separator + "class.json");
        classInfo = GSON.fromJson(Files.readString(classInfoFile.toPath(), StandardCharsets.UTF_8), ClassInfo.class);
    }

    public ClassRunner(String fullClassName, String parsePath, String testPath, String srcFolderPath, String outputPath, String classPath) throws IOException {
        super(fullClassName, parsePath, testPath);
        infoDir = new File(parseOutputPath + File.separator + fullClassName.replace(".", File.separator));
        if (!infoDir.isDirectory()) {
            log.error("Error: " + fullClassName + " no parsed info found");
        }
        File classInfoFile = new File(infoDir + File.separator + "class.json");
        classInfo = GSON.fromJson(Files.readString(classInfoFile.toPath(), StandardCharsets.UTF_8), ClassInfo.class);

        String packagePath = classPath.substring(srcFolderPath.length() + 1);
        Path output = Paths.get(outputPath, packagePath).getParent();

        classParser = new ClassParser(output);
    }

    public void start() throws IOException {
        if (Config.enableMultithreading == true) {
            methodJob();
        } else {
            // 当前针对类的测试生成使用的是多次调用method
            // 想办法将其进行合并
            // class 中的method直接合并即可，如果是需要对
            // 难点是如何针对都有的方法进行合并
            // 同名的应如何处理
            // 比较稳妥的方式是创建一个副本
            // 所有同名的变量和函数都创建一个副本
            // 比较特殊的函数需要合并，比如setup，对其中所有的变量都进行一次拷贝
            List<Path> paths = new ArrayList<>();
            for (String mSig : classInfo.methodSignatures.keySet()) {
                MethodInfo methodInfo = getMethodInfo(classInfo, mSig);
                if (methodInfo == null) {
                    continue;
                }
                new MethodRunner(fullClassName, parseOutputPath.toString(),
                        testOutputPath.toString(), methodInfo)
                        .run(paths);
            }
            String code = mergeClassAndGenerate(paths);
            saveFile(code);
        }
    }

    private String mergeClassAndGenerate(List<Path> classPath) throws IOException {
        JavaParser parser = ClassParser.parser;
        // 得到一个sig到MethodDeclaration的映射
        HashMap<String, MethodDeclaration> methodSigMap = new HashMap<>();
        // 开始准备生成
        CompilationUnit res = new CompilationUnit();
        // 获取所有的import
        Set<ImportDeclaration> importSet = new HashSet<>();
        // 获取所有的field
        HashSet<FieldDeclaration> fieldSet = new HashSet<>();
        for (Path path : classPath) {
            ParseResult<CompilationUnit> parseResult = parser.parse(path);
            CompilationUnit cu = parseResult.getResult().orElseThrow();
            // 获取PackageDeclaration
            // 直接从Optional中取值，可能会存在问题
            res.setPackageDeclaration(cu.getPackageDeclaration().get());
            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
            // 获取所有的import
            NodeList<ImportDeclaration> imports = cu.getImports();
            importSet.addAll(imports);
            for (ClassOrInterfaceDeclaration classDeclaration : classes) {
                // 获取所有的field
                List<FieldDeclaration> fields = classDeclaration.getFields();
                fieldSet.addAll(fields);
                // 获取所有的method
                extractMethods(methodSigMap, cu, classDeclaration);
            }
        }
        // 放入import
        for (ImportDeclaration importDeclaration : importSet) {
            res.addImport(importDeclaration);
        }

        ClassOrInterfaceDeclaration test = res.addClass("Test");
        for (FieldDeclaration fieldDeclaration : fieldSet) {
            test.getMembers().add(fieldDeclaration);
        }
        for (MethodDeclaration methodDeclaration : methodSigMap.values()) {
            test.getMembers().add(methodDeclaration);
        }
        // System.out.println(res.toString());
        return res.toString();
    }

    private void extractMethods(Map<String, MethodDeclaration> methodSigMap, CompilationUnit cu, ClassOrInterfaceDeclaration classDeclaration) throws IOException {
        List<MethodDeclaration> methods = classDeclaration.getMethods();
        for (MethodDeclaration m : methods) {
            String sig = getMethodSig(m);
            if (methodSigMap.containsKey(sig)) {
                MethodDeclaration methodDeclaration = methodSigMap.get(sig);
                NodeList<Statement> statements = new NodeList<>();
                methodDeclaration.getBody().ifPresent(body -> {
                    statements.addAll(body.getStatements());
                });
                m.getBody().ifPresent(body -> {
                    statements.addAll(body.getStatements());
                });
                methodDeclaration.setBody(new BlockStmt(statements));
            } else {
                methodSigMap.put(sig, m);
            }
        }
    }

    private String getMethodSig(CallableDeclaration node) {
        if (node instanceof MethodDeclaration) {
            return ((MethodDeclaration) node).resolve().getSignature();
        } else {
            return ((ConstructorDeclaration) node).resolve().getSignature();
        }
    }

    public boolean saveFile(String code) throws IOException {
        String testName = className + separator + "Test";
        Path savePath = testOutputPath.resolve(classInfo.packageDeclaration
                        .replace(".", File.separator)
                        .replace("package ", "")
                        .replace(";", ""))
                .resolve(testName + ".java");

        exportTest(code, savePath);

        TestCompiler compiler = new TestCompiler();
        if (compiler.compileAndExport(savePath.toFile(),
                errorOutputPath.resolve(testName + "CompilationError_" + ".txt"), new PromptInfo())) {

            log.info("Test for class < " + className + " > generated successfully");
            return true;
        } else {
            MethodRunner.removeTestFile(savePath.toFile());
            log.info("Test for class < " + className + " > generated failed");
        }
        return false;
    }

    public void methodJob() {
        ExecutorService executor = Executors.newFixedThreadPool(methodThreads);
        List<Future<String>> futures = new ArrayList<>();
        for (String mSig : classInfo.methodSignatures.keySet()) {
            Callable<String> callable = new Callable<String>() {
                @Override
                public String call() throws Exception {
                    MethodInfo methodInfo = getMethodInfo(classInfo, mSig);
                    if (methodInfo == null) {
                        return "No parsed info found for " + mSig + " in " + fullClassName;
                    }
                    new MethodRunner(fullClassName, parseOutputPath.toString(), testOutputPath.toString(), methodInfo).start();
                    return "Processed " + mSig;
                }
            };
            Future<String> future = executor.submit(callable);
            futures.add(future);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                executor.shutdownNow();
            }
        });

        for (Future<String> future : futures) {
            try {
                String result = future.get();
                System.out.println(result);
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
    }

    public PromptInfo generatePromptInfoWithoutDep(ClassInfo classInfo, MethodInfo methodInfo) {
        PromptInfo promptInfo = new PromptInfo(
                false,
                classInfo.className,
                methodInfo.methodName,
                methodInfo.methodSignature,
                methodInfo.sourceCode);
        String fields = joinLines(classInfo.fields);
        String methods = filterAndJoinLines(classInfo.briefMethods, methodInfo.brief);
        String imports = joinLines(classInfo.imports);

        String information = classInfo.packageDeclaration
                + "\n" + imports
                + "\n" + classInfo.classSignature
                + " {"
                + "\n" + fields
                + "\n" + methods
                + "\n" + methodInfo.sourceCode
                + "\n}";

        promptInfo.setInfo(information);

        return promptInfo;
    }

    public PromptInfo generatePromptInfoWithDep(ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        PromptInfo promptInfo = new PromptInfo(
                true,
                classInfo.className,
                methodInfo.methodName,
                methodInfo.methodSignature,
                methodInfo.sourceCode);
        List<String> otherBriefMethods = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : methodInfo.dependentMethods.entrySet()) {
            String depClassName = entry.getKey();
            if (depClassName.equals(className)) {
                Set<String> otherSig = methodInfo.dependentMethods.get(depClassName);
                for (String otherMethod : otherSig) {
                    MethodInfo otherMethodInfo = getMethodInfo(classInfo, otherMethod);
                    if (otherMethodInfo == null) {
                        continue;
                    }
                    otherBriefMethods.add(otherMethodInfo.brief);
                }
                continue;
            }
            Set<String> depMethods = entry.getValue();
            promptInfo.addMethodDeps(getDepInfo(promptInfo, depClassName, depMethods));
        }
        for (Map.Entry<String, Set<String>> entry : classInfo.constructorDeps.entrySet()) {
            String depClassName = entry.getKey();
            Set<String> depMethods = entry.getValue();
            if (methodInfo.dependentMethods.containsKey(depClassName)) {
                continue;
            }
            promptInfo.addConstructorDeps(getDepInfo(promptInfo, depClassName, depMethods));
        }

        String fields = joinLines(classInfo.fields);
        String imports = joinLines(classInfo.imports);

        String information = classInfo.packageDeclaration
                + "\n" + imports
                + "\n" + classInfo.classSignature
                + " {\n";
        //TODO: handle used fields instead of all fields
        if (methodInfo.useField) {
            information += fields + "\n" + joinLines(classInfo.getterSetters) + "\n";
        }
        if (classInfo.hasConstructor) {
            information += joinLines(classInfo.constructors) + "\n";
        }
        information += joinLines(otherBriefMethods) + "\n";
        information += methodInfo.sourceCode + "\n}";

        promptInfo.setInfo(information);
        return promptInfo;
    }

    public MethodInfo getMethodInfo(ClassInfo info, String mSig) throws IOException {
        String packagePath = info.packageDeclaration
                .replace("package ", "")
                .replace(".", File.separator)
                .replace(";", "");
        Path depMethodInfoPath = parseOutputPath
                .resolve(packagePath)
                .resolve(info.className)
                .resolve(ClassParser.getFilePathBySig(mSig, info));
        if (!depMethodInfoPath.toFile().exists()) {
            return null;
        }
        return GSON.fromJson(Files.readString(depMethodInfoPath, StandardCharsets.UTF_8), MethodInfo.class);
    }

    public Map<String, String> getDepInfo(PromptInfo promptInfo, String depClassName, Set<String> depMethods) throws IOException {
        Path depClassInfoPath = parseOutputPath.resolve(depClassName).resolve("class.json");
        if (!depClassInfoPath.toFile().exists()) {
            return null;
        }
        ClassInfo depClassInfo = GSON.fromJson(Files.readString(depClassInfoPath, StandardCharsets.UTF_8), ClassInfo.class);

        String classSig = depClassInfo.classSignature;
        String fields = joinLines(depClassInfo.fields);
        String constructors = joinLines(depClassInfo.constructors);
        Map<String, String> methodDeps = new HashMap<>();

        String basicInfo = classSig + " {\n" + fields + "\n";
        if (depClassInfo.hasConstructor) {
            basicInfo += constructors + "\n";
        }

        String briefDepMethods = "";
        for (String sig : depMethods) {
            //TODO: identify used fields in dependent class
            MethodInfo depMethodInfo = getMethodInfo(depClassInfo, sig);
            if (depMethodInfo == null) {
                continue;
            }
            briefDepMethods += depMethodInfo.brief + "\n";
        }
        String getterSetter = joinLines(depClassInfo.getterSetters) + "\n";
        methodDeps.put(depClassName, basicInfo + getterSetter + briefDepMethods + "}");
        return methodDeps;
    }
}
