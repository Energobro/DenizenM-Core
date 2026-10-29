package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.utilities.debugging.Debug;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class EnumHelper<T> {

    public static HashMap<Class<?>, EnumHelper<?>> helpers = new HashMap<>();

    private static final ConcurrentHashMap<Class<?>, EnumHelper<?>> lookup = new ConcurrentHashMap<>();

    public static <T> EnumHelper<T> get(Class<T> enumClass) {
        EnumHelper<?> helper = lookup.get(enumClass);
        if (helper == null) {
            synchronized (helpers) {
                helper = helpers.computeIfAbsent(enumClass, EnumHelper::new);
            }
            lookup.put(enumClass, helper);
        }
        return (EnumHelper<T>) helper;
    }

    public static String cleanKey(String name) {
        return CoreUtilities.toLowerCase(name).replace("_", "");
    }

    public Class<T> targetClass;

    public HashMap<String, T> valuesMapLower = new HashMap<>();

    public EnumHelper(Class<T> clazz) {
        targetClass = clazz;
        try {
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == clazz) {
                    valuesMapLower.put(cleanKey(f.getName()), (T) f.get(null));
                }
            }
        }
        catch (Throwable ex) {
            Debug.echoError(ex);
        }
    }
}
