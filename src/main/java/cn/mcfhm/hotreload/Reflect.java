package cn.mcfhm.hotreload;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

// 反射读写，拿不到就返回 null 或 false，不抛异常
final class Reflect {

    private Reflect() {
    }

    static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    static Object get(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = field(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    static boolean set(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        Field f = field(target.getClass(), name);
        if (f == null) {
            return false;
        }
        try {
            f.set(target, value);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }
}
