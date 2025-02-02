package zju.cst.aces.runner;

import okhttp3.Response;
import zju.cst.aces.utils.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class MethodRunner extends ClassRunner {

    public MethodInfo methodInfo;

    public MethodRunner(String fullClassName, String parsePath, String testOutputPath, MethodInfo methodInfo) throws IOException {
        super(fullClassName, parsePath, testOutputPath);
        this.methodInfo = methodInfo;
    }


    public void run(List<Path> paths) throws IOException {
        if (Config.stopWhenSuccess == false && Config.enableMultithreading == true) {
            ExecutorService executor = Executors.newFixedThreadPool(Config.testNumber);
            List<Future<String>> futures = new ArrayList<>();
            for (int num = 1; num <= Config.testNumber; num++) {
                int finalNum = num;
                Callable<String> callable = new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        startRounds(finalNum, paths);
                        return "";
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
        } else {
            // 单线程执行部分
            for (int num = 1; num <= Config.testNumber; num++) {
                if (startRounds(num, paths)) {
                    break;
                }
            }
        }
    }

    public boolean startRounds(final int num, List<Path> paths) throws IOException {
        PromptInfo promptInfo = null;
        String testName = className + separator + methodInfo.methodName + separator
                + classInfo.methodSignatures.get(methodInfo.methodSignature) + separator + num + separator + "Test";
        log.info("\n==========================\n[ChatTester] Generating test for method < "
                + methodInfo.methodName + " > number " + num + "...\n");
        for (int rounds = 1; rounds <= Config.maxRounds; rounds++) {
            if (promptInfo == null) {
                log.info("Generating test for method < " + methodInfo.methodName + " > round " + rounds + " ...");
                if (methodInfo.dependentMethods.size() > 0) {
                    promptInfo = generatePromptInfoWithDep(classInfo, methodInfo);
                } else {
                    promptInfo = generatePromptInfoWithoutDep(classInfo, methodInfo);
                }
            } else {
                log.info("Fixing test for method < " + methodInfo.methodName + " > round " + rounds + " ...");
            }
            List<Message> prompt = generateMessages(promptInfo);
            log.debug("[Prompt]:\n" + prompt.toString());

            AskGPT askGPT = new AskGPT();
            Response response = askGPT.askChatGPT(prompt);
            Path savePath = testOutputPath.resolve(classInfo.packageDeclaration
                            .replace(".", File.separator)
                            .replace("package ", "")
                            .replace(";", ""))
                    .resolve(testName + ".java");

            String code = parseResponse(response);
            if (code.isEmpty()) {
                log.info("Test for method < " + methodInfo.methodName + " > extract code failed");
                continue;
            }
            code = changeTestName(code, className, testName);
            code = repairPackage(code, classInfo.packageDeclaration);
            code = addTimeout(code, testTimeOut);

            promptInfo.setUnitTest(code);

            code = repairImports(code, classInfo.imports);
            exportTest(code, savePath);

            TestCompiler compiler = new TestCompiler();
            if (compiler.compileAndExport(savePath.toFile(),
                    errorOutputPath.resolve(testName + "CompilationError_" + rounds + ".txt"), promptInfo)) {
                paths.add(savePath);
                log.info("Test for method < " + methodInfo.methodName + " > generated successfully");
                return true;
            } else {
                removeTestFile(savePath.toFile());
                log.info("Test for method < " + methodInfo.methodName + " > generated failed");
            }
        }
        return false;
    }

    /**
     * Remove the failed test file
     */
    public static void removeTestFile(File testFile) {
        if (testFile != null && testFile.exists()) {
            testFile.delete();
        }
    }
}