package cn.mcfhm.hotreload;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// 用 JVM 的类重定义换方法体，ClassLoader 不动。
// 世界里已有的对象、注册的监听、开着的连接都还指着同一个类，没有旧实例要回收。
//
// 能改方法体，也能增删方法（要 -XX:+AllowRedefinitionToAddDeleteMethods）。
// 加字段、改父类、改类名这些 JVM 会拒，只能退回 unload/load。
final class Patcher {

    record Result(boolean applied, int scanned, int changed, int failed, List<String> failures, long millis) {

        static Result unavailable(String reason) {
            return new Result(false, 0, 0, 0, List.of(reason), 0L);
        }
    }

    private final Instrumentation instrumentation;

    Patcher(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    boolean available() {
        return instrumentation != null;
    }

    /**
     * 拿 jar 里的字节去覆盖已经加载的类。同一个 Class 对象，方法体换掉。
     */
    Result patch(ClassLoader targetLoader, File jar) {
        if (instrumentation == null) {
            return Result.unavailable("启动时没有加 -javaagent");
        }
        if (targetLoader == null || jar == null || !jar.isFile()) {
            return Result.unavailable("找不到插件或 jar");
        }

        long start = System.nanoTime();
        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getClassLoader() == targetLoader && instrumentation.isModifiableClass(type)) {
                loaded.add(type);
            }
        }

        List<ClassDefinition> definitions = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar)) {
            for (Class<?> type : loaded) {
                String entryName = type.getName().replace('.', '/') + ".class";
                JarEntry entry = jarFile.getJarEntry(entryName);
                if (entry == null) {
                    // 新 jar 里没有这个类，可能是改名或删掉了，redefine 帮不上
                    continue;
                }
                byte[] fresh = readJarEntry(jarFile, entry);
                if (fresh == null) {
                    continue;
                }
                byte[] current = readFromLoader(targetLoader, entryName);
                if (current != null && Arrays.equals(current, fresh)) {
                    continue;
                }
                definitions.add(new ClassDefinition(type, fresh));
            }
        } catch (IOException error) {
            return Result.unavailable("读 jar 失败: " + error.getMessage());
        }

        if (definitions.isEmpty()) {
            return new Result(true, loaded.size(), 0, 0, List.of(), millis(start));
        }

        // 先整批试一次，JVM 对一批里的任何一个不满意就会把整批退掉
        try {
            instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));
            return new Result(true, loaded.size(), definitions.size(), 0, List.of(), millis(start));
        } catch (Throwable batchFailure) {
            // 退回来了就一个一个试，把真正改不了的那些挑出来
            int changed = 0;
            for (ClassDefinition definition : definitions) {
                try {
                    instrumentation.redefineClasses(definition);
                    changed++;
                } catch (Throwable singleFailure) {
                    String reason = singleFailure.getMessage();
                    failures.add(definition.getDefinitionClass().getName()
                            + (reason == null ? "" : " -> " + reason));
                }
            }
            return new Result(changed > 0, loaded.size(), changed, failures.size(), failures, millis(start));
        }
    }

    private static byte[] readJarEntry(JarFile jarFile, JarEntry entry) {
        try (InputStream in = jarFile.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static byte[] readFromLoader(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
