/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.server.AnnotatedHttpDocServiceUtil.extractParameter;
import static com.linecorp.armeria.server.AnnotatedHttpDocServiceUtil.getNormalizedTriePath;
import static com.linecorp.armeria.server.AnnotatedHttpDocServiceUtil.isHidden;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.oas.models.parameters.Parameter;

/**
 * {@link DocServicePlugin} implementation that supports {@link AnnotatedHttpService}s.
 */
public class AnnotatedHttpDocServicePlugin implements DocServicePlugin {

    @VisibleForTesting
    static final TypeSignature VOID = TypeSignature.ofBase("void");
    @VisibleForTesting
    static final TypeSignature BOOL = TypeSignature.ofBase("boolean");
    @VisibleForTesting
    static final TypeSignature INT8 = TypeSignature.ofBase("int8");
    @VisibleForTesting
    static final TypeSignature INT16 = TypeSignature.ofBase("int16");
    @VisibleForTesting
    static final TypeSignature INT32 = TypeSignature.ofBase("int32");
    @VisibleForTesting
    static final TypeSignature INT64 = TypeSignature.ofBase("int64");
    @VisibleForTesting
    static final TypeSignature FLOAT = TypeSignature.ofBase("float");
    @VisibleForTesting
    static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    @VisibleForTesting
    static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    static final TypeSignature BINARY = TypeSignature.ofBase("binary");

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(AnnotatedHttpService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs) {
        requireNonNull(serviceConfigs, "serviceConfigs");

        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();

        serviceConfigs.forEach(sc -> {
            final Optional<AnnotatedHttpService> service = sc.service().as(AnnotatedHttpService.class);
            if (service.isPresent()) {
                final AnnotatedHttpService httpService = service.get();
                addMethodInfo(methodInfos, sc.virtualHost().hostnamePattern(), httpService);
            }
        });

        return generate(methodInfos);
    }

    private static void addMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos,
                                      String hostnamePattern, AnnotatedHttpService service) {
        if (isHidden(service)) {
            return;
        }
        final HttpHeaderPathMapping pathMapping = service.pathMapping();
        final EndpointInfo endpoint = endpointInfo(pathMapping, hostnamePattern);
        if (endpoint == null) {
            return;
        }

        final Method method = service.method();
        final String name = method.getName();
        final TypeSignature returnTypeSignature = toTypeSignature(method.getGenericReturnType());
        final List<FieldInfo> fieldInfos = fieldInfos(service.annotatedValueResolvers());
        final List<TypeSignature> exceptions = exceptions(method);

        final Class<?> clazz = service.object().getClass();
        pathMapping.supportedMethods().forEach(
                httpMethod -> {
                    final MethodInfo methodInfo = new MethodInfo(
                            name, returnTypeSignature, fieldInfos, exceptions,
                            ImmutableList.of(endpoint), httpMethod, description(method));
                    methodInfos.computeIfAbsent(clazz, unused -> new HashSet<>()).add(methodInfo);
                });
    }

    @Nullable
    @VisibleForTesting
    static EndpointInfo endpointInfo(HttpHeaderPathMapping pathMapping, String hostnamePattern) {
        final String endpointPath;
        String regexPathPrefix = null;
        if (pathMapping.prefix().isPresent()) {
            if (pathMapping.regex().isPresent()) { // PrefixAddingPathMapping.
                regexPathPrefix = pathMapping.prefix().get();
                endpointPath = RegexPathMapping.PREFIX + pathMapping.regex().get();
            } else { // PrefixPathMapping.
                endpointPath = PrefixPathMapping.PREFIX + pathMapping.prefix().get();
            }
        } else {
            endpointPath = getNormalizedTriePathOrRegex(pathMapping);
        }

        if (isNullOrEmpty(endpointPath)) {
            return null;
        }

        final EndpointInfoBuilder builder = new EndpointInfoBuilder(hostnamePattern, endpointPath);
        if (!isNullOrEmpty(regexPathPrefix)) {
            builder.regexPathPrefix(regexPathPrefix);
        }

        builder.defaultMimeType(MediaType.JSON_UTF_8);
        final List<MediaType> consumeTypes = pathMapping.consumeTypes();
        if (!consumeTypes.isEmpty()) {
            builder.availableMimeTypes(consumeTypes);
        }

        return builder.build();
    }

    @Nullable
    private static String getNormalizedTriePathOrRegex(HttpHeaderPathMapping pathMapping) {
        final String normalizedTriePath = getNormalizedTriePath(pathMapping);
        if (normalizedTriePath != null) {
            return normalizedTriePath;
        }
        final Optional<String> regex = pathMapping.regex();
        return regex.map(s -> RegexPathMapping.PREFIX + s).orElse(null);
    }

    @VisibleForTesting
    static List<FieldInfo> fieldInfos(List<AnnotatedValueResolver> resolvers) {
        final ImmutableList.Builder<FieldInfo> fieldInfoBuilder = ImmutableList.builder();
        for (AnnotatedValueResolver resolver : resolvers) {
            final Parameter parameter = extractParameter(resolver);
            if (parameter == null) {
                continue;
            }

            final TypeSignature signature;
            if (resolver.hasContainer()) {
                final Class<?> containerType = resolver.containerType();
                assert containerType != null;
                if (List.class.isAssignableFrom(containerType)) {
                    signature = TypeSignature.ofList(resolver.elementType());
                } else if (Set.class.isAssignableFrom(containerType)) {
                    signature = TypeSignature.ofSet(resolver.elementType());
                } else {
                    throw new IllegalStateException(
                            "Only List and Set for fields are supported. containerType:" + containerType);
                }
            } else {
                signature = toTypeSignature(resolver.elementType());
            }

            fieldInfoBuilder.add(new FieldInfo(parameter.getName(), requirement(parameter),
                                               signature, parameter.getDescription()));
        }
        return fieldInfoBuilder.build();
    }

    private static FieldRequirement requirement(Parameter param) {
        return param.getRequired() != null && param.getRequired() ? FieldRequirement.REQUIRED
                                                                  : FieldRequirement.OPTIONAL;
    }

    @VisibleForTesting
    static TypeSignature toTypeSignature(Type type) {
        if (type == Void.class || type == void.class) {
            return VOID;
        } else if (type == Boolean.class || type == boolean.class) {
            return BOOL;
        } else if (type == Byte.class || type == byte.class) {
            return INT8;
        } else if (type == Short.class || type == short.class) {
            return INT16;
        } else if (type == Integer.class || type == int.class) {
            return INT32;
        } else if (type == Long.class || type == long.class) {
            return INT64;
        } else if (type == Float.class || type == float.class) {
            return FLOAT;
        } else if (type == Double.class || type == double.class) {
            return DOUBLE;
        } else if (type.equals(String.class)) {
            return STRING;
        }

        if (type == byte[].class || type == Byte[].class) {
            return BINARY;
        }

        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            if (List.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofList(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }

            if (Set.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofSet(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }

            if (Map.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofMap(toTypeSignature(parameterizedType.getActualTypeArguments()[0]),
                                           toTypeSignature(parameterizedType.getActualTypeArguments()[1]));
            }

            if (CompletionStage.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofContainer(rawType.getSimpleName(),
                                                 toTypeSignature(
                                                         parameterizedType.getActualTypeArguments()[0]));
            }
        }

        assert type instanceof Class : "type: " + type;
        final Class<?> clazz = (Class<?>) type;
        if (clazz.isArray()) {
            throw new IllegalStateException("array is not supported: " + clazz);
        }

        return TypeSignature.ofNamed(clazz);
    }

    private static List<TypeSignature> exceptions(Method method) {
        final ImmutableList.Builder<TypeSignature> exceptionBuilder = ImmutableList.builder();
        final Class<?>[] exceptionTypes = method.getExceptionTypes();
        for (Class<?> exceptionType : exceptionTypes) {
            exceptionBuilder.add(TypeSignature.ofNamed(exceptionType));
        }

        return exceptionBuilder.build();
    }

    private static String description(Method method) {
        final io.swagger.v3.oas.annotations.Operation operationAnnotation =
                ReflectionUtils.getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        return operationAnnotation != null ? operationAnnotation.description() : "";
    }

    private static ServiceSpecification generate(
            Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final Set<ServiceInfo> serviceInfos = methodInfos
                .entrySet().stream()
                .map(entry -> new ServiceInfo(entry.getKey().getName(), entry.getValue()))
                .collect(toImmutableSet());

        return ServiceSpecification.generate(serviceInfos, AnnotatedHttpDocServicePlugin::newNamedTypeInfo);
    }

    private static NamedTypeInfo newNamedTypeInfo(TypeSignature typeSignature) {
        final Optional<Object> namedTypeDescriptor = typeSignature.namedTypeDescriptor();
        if (!namedTypeDescriptor.isPresent()) {
            throw new IllegalArgumentException("cannot create named type from: " + typeSignature);
        }
        final Class<?> type = (Class<?>) namedTypeDescriptor.get();
        if (type.isEnum()) {
            return EnumInfo.of(type);
        }

        if (Exception.class.isAssignableFrom(type)) {
            return newExceptionInfo(type);
        }

        return newStructInfo(type);
    }

    @VisibleForTesting
    static ExceptionInfo newExceptionInfo(Class<?> type) {
        final Builder<FieldInfo> builder = ImmutableList.builder();
        final Field[] declaredFields = type.getDeclaredFields();
        for (Field declaredField : declaredFields) {
            if (shouldContain(declaredField)) {
                // Set the requirement as DEFAULT because it's hard to determine the field in a Exception
                // is required or not.
                builder.add(new FieldInfo(declaredField.getName(), FieldRequirement.DEFAULT,
                                          toTypeSignature(declaredField.getGenericType())));
            }
        }
        return new ExceptionInfo(type.getName(), builder.build());
    }

    private static boolean shouldContain(Field field) {
        return !field.getName().equals("serialVersionUID");
    }

    private static StructInfo newStructInfo(Class<?> structClass) {
        final String name = structClass.getName();

        final Field[] declaredFields = structClass.getDeclaredFields();
        final List<FieldInfo> fields = Stream.of(declaredFields)
                                             .filter(AnnotatedHttpDocServicePlugin::shouldContain)
                                             .map(f -> new FieldInfo(f.getName(), FieldRequirement.DEFAULT,
                                                                     toTypeSignature(f.getGenericType())))
                                             .collect(Collectors.toList());
        return new StructInfo(name, fields);
    }

    @Override
    public Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of(TreeNode.class);
    }

    @Override
    public Optional<String> serializeExampleRequest(String serviceName, String methodName,
                                                    Object exampleRequest) {
        try {
            return Optional.of(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exampleRequest));
        } catch (JsonProcessingException e) {
            // Ignore the exception and just return Optional.empty().
        }
        return Optional.empty();
    }
}
