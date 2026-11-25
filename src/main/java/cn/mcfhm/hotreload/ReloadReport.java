package cn.mcfhm.hotreload;

import java.util.ArrayList;
import java.util.List;

// 一次卸载或加载的过程记录，失败写进报告让命令层打印，不抛异常
final class ReloadReport {

    record Step(String name, boolean ok, long millis, String detail) {
    }

    private final List<Step> steps = new ArrayList<>();
    private boolean success = true;
    private String failure;

    void ok(String name, long millis, String detail) {
        steps.add(new Step(name, true, millis, detail));
    }

    void fail(String name, long millis, String detail) {
        steps.add(new Step(name, false, millis, detail));
        success = false;
        if (failure == null) {
            failure = name + ": " + detail;
        }
    }

    void fatal(String reason) {
        success = false;
        if (failure == null) {
            failure = reason;
        }
    }

    boolean success() {
        return success;
    }

    String failure() {
        return failure;
    }

    List<Step> steps() {
        return steps;
    }

    long totalMillis() {
        long total = 0L;
        for (Step step : steps) {
            total += step.millis();
        }
        return total;
    }
}
