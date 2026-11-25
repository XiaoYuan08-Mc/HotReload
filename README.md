# HotReload

26.3 / Purpur 的插件热重载。

`HotReload-0.1.0.jar` 丢进 plugins 就行。`hotreload-agent.jar` 只有用 patch 时才需要，启动参数加一行：

```
-javaagent:"你的目录\hotreload-agent.jar"
```

## patch

不卸载，直接把新 jar 里的方法体盖到已经加载的类上。

```
/hr patch VanillaPlus ../build/VanillaPlus-0.2.3.jar
```

ClassLoader 不动，世界里已有的方块实体、玩家背包里的物品、注册的监听都还指着同一个类，没有旧实例要回收。

新 jar 放别的路径，别覆盖原文件——Windows 上插件 jar 被 ClassLoader 锁着。

能改方法体，也能增删方法。加字段、改父类、改类名、动注册表的，JVM 会拒，报告里会列出来，那种只能 reload。

`<clinit>` 不会重跑，靠静态单例缓存的插件 patch 完可能还是旧值。

## reload

```
/hr reload VanillaPlus
```

默认有人在线也直接执行，前后在聊天栏广播一句。流程是 disable、摘任务和通道、清监听、服务、命令、权限、配方、brigadier、关 ClassLoader、重新 load 和 enable。旧 ClassLoader 的回收丢到后台线程复查，不占主线程，`/hr status` 随时看。

有人时安全是因为 Bukkit 单线程，玩家的包在主线程队列里排队，塞不进 reload 中间；`disable` 之后 `isEnabled()` 已经是 false，事件和命令都会跳过。代价就是那几百毫秒的卡顿。

## idle

```
/hr reload VanillaPlus --idle
```

有人在就排队，每 5 秒看一次，超过 `idle-timeout` 放弃。配置 `idle-only: true` 可以让所有 reload 默认走这条。

## 插件配合

主类里写两个无参方法，卸载前存档、加载后回填：

```java
public void onHotReloadUnload() {
}

public void onHotReloadLoad() {
}
```

不用实现接口，反射按名字找。

## 能不能 reload

看 `onDisable` 有没有把线程、端口、任务、监听、命令关干净，再看世界里有没有方块实体、实体、物品 NBT 存着它的类实例。两条都过才行。有世界状态的走 patch，或者把状态数据化。

## 配置

```yaml
protected-plugins:
  - HotReload
verify-classloader: true
idle-only: false
idle-timeout: 1800
announce: true
```
