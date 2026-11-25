package cn.mcfhm.hotreload.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

// 接住 JVM 给的 Instrumentation，挂到 system classpath，让插件侧能拿到同一个实例
public final class HotReloadAgent {

    private static volatile Instrumentation instrumentation;

    private HotReloadAgent() {
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    public static void premain(String arguments, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String arguments, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        instrumentation = inst;
        try {
            File self = new File(HotReloadAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (self.isFile()) {
                inst.appendToSystemClassLoaderSearch(new JarFile(self));
            }
        } catch (Throwable ignored) {
            // 挂不上就退化成不可用，插件侧会自己判断
        }
    }
}
