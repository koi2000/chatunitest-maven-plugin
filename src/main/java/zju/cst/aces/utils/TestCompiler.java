package zju.cst.aces.utils;

import org.codehaus.plexus.util.FileUtils;
import zju.cst.aces.ProjectTestMojo;
import zju.cst.aces.runner.MethodRunner;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestCompiler extends ProjectTestMojo {
    public static File srcTestFolder = new File("src" + File.separator + "test" + File.separator + "java");
    public static File backupFolder = new File("src" + File.separator + "backup");

    public boolean tryCompileAndExport(File file, Path outputPath, PromptInfo promptInfo){
        File testFile = null;
        Config.lock.lock();
        try {
            testFile = copyFileToTest(file);
            log.debug("Running test " + testFile.getName() + "...");
            if (!testFile.exists()) {
                log.error("Test file < " + testFile.getName() + " > not exists");
                return false; // next round
            }
            if (!outputPath.toAbsolutePath().getParent().toFile().exists()) {
                outputPath.toAbsolutePath().getParent().toFile().mkdirs();
            }
            // 读取.java文件的内容
            String sourceCode = new String(Files.readAllBytes(testFile.toPath()));
            // 获取Java编译器实例
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

            // 获取标准文件管理器实例
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

            // 创建内存中的Java源文件
            JavaFileObject sourceFile = new JavaSourceFromString(testFile.getName(), sourceCode);

            // 设置编译输出目的地
            Iterable<? extends JavaFileObject> compilationUnits = Arrays.asList(sourceFile);

            Iterable<String> options = outputPath.toString().isEmpty() ? null : Arrays.asList("-d", outputPath.toString());

            // 创建编译任务
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, options, null, compilationUnits);

            // 执行编译任务
            boolean success = task.call();
            if (success) return true;
        }catch (Exception e) {
            throw new RuntimeException("In TestCompiler.compileAndExport: " + e);
        } finally {
            Config.lock.unlock();
        }
        return false;
    }

    // 自定义JavaFileObject实现，表示内存中的Java源文件
    private class JavaSourceFromString extends SimpleJavaFileObject {
        private final String code;

        public JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    public boolean compileAndExport(File file, Path outputPath, PromptInfo promptInfo) {
//        log.info("Waiting for lock: " + file.getName() + "...");
//        long startTime = System.nanoTime();
//        Config.lock.lock();
        File testFile = null;
        try {
//            long endTime = System.nanoTime();
//            long duration = (endTime - startTime);  // 单位是纳秒
//            log.warn("Get lock: " + file.getName() + " in " + duration / 1000000 + "ms");
            testFile = copyFileToTest(file);
            log.debug("Running test " + testFile.getName() + "...");
            if (!testFile.exists()) {
                log.error("Test file < " + testFile.getName() + " > not exists");
                return false; // next round
            }
            if (!outputPath.toAbsolutePath().getParent().toFile().exists()) {
                outputPath.toAbsolutePath().getParent().toFile().mkdirs();
            }
            String testFileName = testFile.getName().split("\\.")[0];
            ProcessBuilder processBuilder = new ProcessBuilder();
            String mvn = Config.OS.contains("win") ? "mvn.cmd" : "mvn";
            processBuilder.command(Arrays.asList(mvn, "test", "-Dtest=" + getPackage(testFile) + testFileName));

            log.debug("Running command: `"
                    + mvn + "test -Dtest=" + getPackage(testFile) + testFileName + "`");
            // full output text
            StringBuilder output = new StringBuilder();
            List<String> errorMessage = new ArrayList<>();

            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));


            String line;
//            while ((line = reader.readLine()) != null) {
//                log.debug(line);
//                output.append(line).append("\n");
//                errorMessage.add(line);
//                if (line.contains("BUILD SUCCESS")){
//                    return true;
//                }
//                if (line.contains("[Help")){
//                    break;
//                }
//            }
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath.toFile()));
            writer.write(output.toString()); // store the original output
            writer.close();

            promptInfo.setErrorMsg(errorMessage);

        } catch (Exception e) {
            throw new RuntimeException("In TestCompiler.compileAndExport: " + e);
        } finally {
//            Config.lock.unlock();
        }
        MethodRunner.removeTestFile(testFile);
        return true;
    }

    /**
     * Read the first line of the test file to get the package declaration
     */
    public static String getPackage(File testFile) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(testFile));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("package")) {
                    return line.split("package")[1].split(";")[0].trim() + ".";
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("In TestCompiler.getPackage: " + e);
        }
        return "";
    }

    /**
     * Copy test file to src/test/java folder with the same directory structure
     */
    public File copyFileToTest(File file) {
        Path sourceFile = file.toPath();
        String splitString = Config.OS.contains("win") ? "chatunitest-tests\\\\" : "chatunitest-tests/";
        String pathWithParent = sourceFile.toAbsolutePath().toString().split(splitString)[1];
        Path targetPath = srcTestFolder.toPath().resolve(pathWithParent);
        log.debug("In TestCompiler.copyFileToTest: file " + file.getName() + " target path" + targetPath);
        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            log.error("In TestCompiler.copyFileToTest: " + e);
        }
        return targetPath.toFile();
    }

    /**
     * Move the src/test/java folder to a backup folder
     */
    public static void backupTestFolder() {
        restoreTestFolder();
        if (srcTestFolder.exists()) {
            try {
                FileUtils.copyDirectoryStructure(srcTestFolder, backupFolder);
                FileUtils.deleteDirectory(srcTestFolder);
            } catch (IOException e) {
                throw new RuntimeException("In TestCompiler.backupTestFolder: " + e);
            }
        }
    }

    /**
     * Restore the backup folder to src/test/java
     */
    public static void restoreTestFolder() {
        if (backupFolder.exists()) {
            try {
                if (srcTestFolder.exists()) {
                    FileUtils.deleteDirectory(srcTestFolder);
                }
                FileUtils.copyDirectoryStructure(backupFolder, srcTestFolder);
                FileUtils.deleteDirectory(backupFolder);
            } catch (IOException e) {
                throw new RuntimeException("In TestCompiler.restoreTestFolder: " + e);
            }
        }
    }
}
