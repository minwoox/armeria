package com.linecorp.armeria.internal.server;

import static org.reflections.ReflectionUtils.getConstructors;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.DependencyInjector;

public final class DependencyInjectorManager {

    private static final Logger logger = LoggerFactory.getLogger(DependencyInjectorManager.class);

    private static final Map<String, Object> singletonCache = new ConcurrentHashMap<>();

    private static final Set<Object> instanceCache = Sets.newIdentityHashSet();

    @Nullable
    private final DependencyInjector dependencyInjector;

    public DependencyInjectorManager(@Nullable DependencyInjector dependencyInjector) {
        this.dependencyInjector = dependencyInjector;
    }

    public <T> T getInstance(Class<T> type, boolean singleton) {
        if (singleton) {
            //noinspection unchecked
            return (T) singletonCache.computeIfAbsent(type.getName(), unused -> getInstance(type));
        }

        final T instance = getInstance(type);
        if (!instanceCache.contains(instance)) {
            synchronized (instanceCache) {
                instanceCache.add(instance);
            }
        }

        return instance;
    }

    private <T> T getInstance(Class<? extends T> type) {
        if (dependencyInjector != null) {
            final T instance = dependencyInjector.getInstance(type);
            if (instance != null) {
                return instance;
            }
        }
        // If dependencyInjector does not return the instance, then we try to make the class by ourself.

        //noinspection rawtypes
        final Set<Constructor> constructors = getConstructors(type);
        if (constructors.size() != 1) {
            throw new IllegalArgumentException("Class should have one constructor. class: " + type.getName() +
                                               ", constructors: " + constructors);
        }
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor = constructors.iterator().next();
        constructor.setAccessible(true);
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length == 0) {
            try {
                return constructor.newInstance();
            } catch (Exception e) {
                throw new IllegalArgumentException("failed to create an instance of " + type.getName(), e);
            }
        }

        final Object[] parameters = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            final Class<?> parameterType = parameterTypes[i];
            try {
                parameters[i] = getInstance(parameterType);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "can't create " + parameterType.getName() + " which is required to create " +
                        type.getName(), e);
            }
        }
        try {
            return constructor.newInstance(parameters);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to create an instance of " + type.getName(), e);
        }
    }

    public void close() {
        final Collection<Object> singletonInstances = singletonCache.values();
        maybeClose(singletonInstances);
        singletonCache.clear();
        synchronized (instanceCache) {
            maybeClose(instanceCache);
            instanceCache.clear();
        }
    }

    private static void maybeClose(Collection<Object> instances) {
        for (Object instance : instances) {
            if (instance instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) instance).close();
                } catch (Exception e) {
                    logger.warn("Unexpected exception while closing " + instance);
                }
            }
        }
    }
}
