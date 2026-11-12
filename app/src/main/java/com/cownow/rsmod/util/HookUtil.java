package com.cownow.rsmod.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 原 bsh 脚本里的通用设施（hookMethodBefore / hookMethodAfter / ret / argOf / getF / setF / callM / plog）
 * 在这里统一换成 Xposed API + 反射实现。
 */
public final class HookUtil {

    private static final String TAG = "[RsMod]";

    private HookUtil() {
    }

    // ---------------- 日志 ----------------

    public static void log(String msg) {
        try {
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static void log(Throwable t) {
        try {
            XposedBridge.log(t);
        } catch (Throwable ignored) {
        }
    }

    public static void toast(final Context ctx, final String msg) {
        if (ctx == null) {
            return;
        }
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        log("toast fail " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("toast post fail " + t);
        }
    }

    public static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    public static void onMainDelayed(Runnable r, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(r, delayMs);
    }

    // ---------------- 按方法名 hook（覆盖所有重载，等价 bsh 的 hookMethodBefore/After） ----------------

    public static int hookBefore(String className, ClassLoader cl, String methodName, XC_MethodHook hook) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            log("skip " + className + " (class not found)");
            return 0;
        }
        return hookBefore(c, methodName, hook);
    }

    public static int hookBefore(Class<?> c, String methodName, XC_MethodHook hook) {
        return hookAll(c, methodName, hook);
    }

    public static int hookAll(Class<?> c, String methodName, XC_MethodHook hook) {
        int n = 0;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            Method[] ms;
            try {
                ms = k.getDeclaredMethods();
            } catch (Throwable t) {
                k = k.getSuperclass();
                continue;
            }
            for (Method m : ms) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, hook);
                    n++;
                } catch (Throwable t) {
                    log("hook fail " + m + " -> " + t);
                }
            }
            k = k.getSuperclass();
        }
        if (n == 0) {
            log("skip " + c.getName() + "#" + methodName + " (method not found)");
        }
        return n;
    }

    /** 只 hook 精确签名，找不到就返回 false，不抛。 */
    public static boolean hookExact(Class<?> c, String methodName, Class<?>[] types, XC_MethodHook hook) {
        if (c == null) {
            return false;
        }
        Method m = XposedHelpers.findMethodExactIfExists(c, methodName, (Object[]) types);
        if (m == null) {
            log("skip " + c.getName() + "#" + methodName + " (exact signature not found)");
            return false;
        }
        try {
            XposedBridge.hookMethod(m, hook);
            return true;
        } catch (Throwable t) {
            log("hook exact fail " + m + " -> " + t);
            return false;
        }
    }

    // ---------------- 返回值填充（原脚本 ret(cb, null) 的类型安全版） ----------------

    /**
     * “放行式”返回值：void -> null，boolean -> true，数值 -> 1，对象 -> null。
     * 直接 setResult(null) 在返回 boolean 的方法上会 NPE（自动拆箱），脚本里那颗雷一并拆掉。
     */
    public static void setNeutral(XC_MethodHook.MethodHookParam p) {
        Class<?> t = ((Method) p.method).getReturnType();
        if (t == void.class) {
            p.setResult(null);
        } else if (t == boolean.class) {
            p.setResult(Boolean.TRUE);
        } else if (t == int.class) {
            p.setResult(1);
        } else if (t == long.class) {
            p.setResult(1L);
        } else if (t == short.class) {
            p.setResult((short) 1);
        } else if (t == byte.class) {
            p.setResult((byte) 1);
        } else if (t == char.class) {
            p.setResult((char) 1);
        } else if (t == float.class) {
            p.setResult(1f);
        } else if (t == double.class) {
            p.setResult(1d);
        } else {
            p.setResult(null);
        }
    }

    /** “不限制式”返回值：boolean -> false，数值 -> 0，void -> null，对象 -> null。 */
    public static void setFalse(XC_MethodHook.MethodHookParam p) {
        Class<?> t = ((Method) p.method).getReturnType();
        if (t == boolean.class) {
            p.setResult(Boolean.FALSE);
        } else if (t == int.class) {
            p.setResult(0);
        } else if (t == long.class) {
            p.setResult(0L);
        } else if (t == short.class) {
            p.setResult((short) 0);
        } else if (t == byte.class) {
            p.setResult((byte) 0);
        } else if (t == char.class) {
            p.setResult((char) 0);
        } else if (t == float.class) {
            p.setResult(0f);
        } else if (t == double.class) {
            p.setResult(0d);
        } else {
            p.setResult(null);
        }
    }

    /** 按方法真实返回类型塞入指定对象，类型不匹配时退化为 setNeutral。 */
    public static void setResultTyped(XC_MethodHook.MethodHookParam p, Object value) {
        Class<?> t = ((Method) p.method).getReturnType();
        try {
            if (t == void.class) {
                p.setResult(null);
            } else if (t == String.class && value != null) {
                p.setResult(value.toString());
            } else if (t.isPrimitive() || value == null) {
                setNeutral(p);
            } else {
                p.setResult(value);
            }
        } catch (Throwable e) {
            setNeutral(p);
        }
    }

    // ---------------- 反射工具（原脚本 getF / setF / callM） ----------------

    public static Field findField(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
            k = k.getSuperclass();
        }
        return null;
    }

    public static Object getField(Object o, String name) {
        if (o == null) {
            return null;
        }
        try {
            Field f = findField(o.getClass(), name);
            return f == null ? null : f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean setField(Object o, String name, Object v) {
        if (o == null) {
            return false;
        }
        try {
            Field f = findField(o.getClass(), name);
            if (f == null) {
                log("field not found: " + o.getClass().getName() + "#" + name);
                return false;
            }
            f.set(o, v);
            return true;
        } catch (Throwable t) {
            log("setField fail " + name + " -> " + t);
            return false;
        }
    }

    /** 按名字 + 参数个数调用实例方法（同名同参个数有多个重载时逐个试）。 */
    public static Object call(Object o, String name, Object... args) {
        if (o == null) {
            return null;
        }
        int argc = args == null ? 0 : args.length;
        Class<?> k = o.getClass();
        while (k != null && k != Object.class) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != argc) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(o, args == null ? new Object[0] : args);
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        log("callM miss " + o.getClass().getName() + "#" + name + "/" + argc);
        return null;
    }

    public static Object callStatic(Class<?> c, String name, Object... args) {
        if (c == null) {
            return null;
        }
        int argc = args == null ? 0 : args.length;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != argc) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(null, args == null ? new Object[0] : args);
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        log("callStatic miss " + c.getName() + "#" + name + "/" + argc);
        return null;
    }

    public static Class<?> cls(String name, ClassLoader cl) {
        return XposedHelpers.findClassIfExists(name, cl);
    }
}
