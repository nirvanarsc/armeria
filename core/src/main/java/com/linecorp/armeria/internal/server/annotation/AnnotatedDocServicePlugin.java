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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.server.annotation.KotlinUtil.isKFunction;
import static com.linecorp.armeria.internal.server.annotation.KotlinUtil.isReturnTypeNothing;
import static com.linecorp.armeria.internal.server.annotation.KotlinUtil.kFunctionGenericReturnType;
import static com.linecorp.armeria.internal.server.annotation.KotlinUtil.kFunctionReturnType;
import static com.linecorp.armeria.server.docs.FieldLocation.HEADER;
import static com.linecorp.armeria.server.docs.FieldLocation.PATH;
import static com.linecorp.armeria.server.docs.FieldLocation.QUERY;
import static com.linecorp.armeria.server.docs.FieldLocation.UNSPECIFIED;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.internal.server.annotation.AnnotatedBeanFactoryRegistry.BeanFactoryId;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldLocation;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfoProvider;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A {@link DocServicePlugin} implementation that supports the {@link AnnotatedService}.
 */
public final class AnnotatedDocServicePlugin implements DocServicePlugin {

    @VisibleForTesting
    static final TypeSignature VOID = TypeSignature.ofBase("void");
    @VisibleForTesting
    static final TypeSignature BOOLEAN = TypeSignature.ofBase("boolean");
    @VisibleForTesting
    static final TypeSignature BYTE = TypeSignature.ofBase("byte");
    @VisibleForTesting
    static final TypeSignature SHORT = TypeSignature.ofBase("short");
    @VisibleForTesting
    static final TypeSignature INT = TypeSignature.ofBase("int");
    @VisibleForTesting
    static final TypeSignature LONG = TypeSignature.ofBase("long");
    @VisibleForTesting
    static final TypeSignature FLOAT = TypeSignature.ofBase("float");
    @VisibleForTesting
    static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    @VisibleForTesting
    static final TypeSignature CHAR = TypeSignature.ofBase("char");
    @VisibleForTesting
    static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    static final TypeSignature BINARY = TypeSignature.ofBase("binary");
    @VisibleForTesting
    static final TypeSignature BEAN = TypeSignature.ofBase("bean");
    @VisibleForTesting
    static final TypeSignature OBJECT = TypeSignature.ofBase("object");

    private static final ObjectWriter objectWriter = JacksonUtil.newDefaultObjectMapper()
                                                                .writerWithDefaultPrettyPrinter();

    private static final NamedTypeInfoProvider defaultRequestNamedTypeInfoProvider =
            new DefaultNamedTypeInfoProvider(true);
    private static final NamedTypeInfoProvider defaultResponseNamedTypeInfoProvider =
            new DefaultNamedTypeInfoProvider(false);

    @Override
    public String name() {
        return "annotated";
    }

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(AnnotatedService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs,
                                                      DocServiceFilter filter,
                                                      NamedTypeInfoProvider namedTypeInfoProvider) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");
        requireNonNull(namedTypeInfoProvider, "namedTypeInfoProvider");

        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        final Map<Class<?>, DescriptionInfo> serviceDescription = new HashMap<>();
        serviceConfigs.forEach(sc -> {
            final AnnotatedService service = sc.service().as(AnnotatedService.class);
            if (service != null) {
                final String className = service.object().getClass().getName();
                final String methodName = service.method().getName();
                if (!filter.test(name(), className, methodName)) {
                    return;
                }
                addMethodInfo(methodInfos, sc.virtualHost().hostnamePattern(), service, namedTypeInfoProvider);
                addServiceDescription(serviceDescription, service);
            }
        });

        return generate(serviceDescription, methodInfos, namedTypeInfoProvider);
    }

    private static void addServiceDescription(Map<Class<?>, DescriptionInfo> serviceDescription,
                                              AnnotatedService service) {
        final Class<?> clazz = service.object().getClass();
        serviceDescription.computeIfAbsent(clazz, AnnotatedServiceFactory::findDescription);
    }

    private static void addMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos,
                                      String hostnamePattern, AnnotatedService service,
                                      NamedTypeInfoProvider namedTypeInfoProvider) {
        final Route route = service.route();
        final EndpointInfo endpoint = endpointInfo(route, hostnamePattern);
        final Method method = service.method();
        final String name = method.getName();
        final TypeSignature returnTypeSignature = getReturnTypeSignature(method);
        final List<FieldInfo> fieldInfos = fieldInfos(service.annotatedValueResolvers(), namedTypeInfoProvider);
        final Class<?> clazz = service.object().getClass();
        route.methods().forEach(
                httpMethod -> {
                    final MethodInfo methodInfo = new MethodInfo(
                            name, returnTypeSignature, fieldInfos, ImmutableList.of(), // Ignore exceptions.
                            ImmutableList.of(endpoint), httpMethod,
                            AnnotatedServiceFactory.findDescription(method));

                    methodInfos.computeIfAbsent(clazz, unused -> new HashSet<>()).add(methodInfo);
                });
    }

    private static TypeSignature getReturnTypeSignature(Method method) {
        if (isKFunction(method)) {
            if (isReturnTypeNothing(method)) {
                return toTypeSignature(kFunctionReturnType(method));
            }
            return toTypeSignature(kFunctionGenericReturnType(method));
        }
        return toTypeSignature(method.getGenericReturnType());
    }

    @VisibleForTesting
    static EndpointInfo endpointInfo(Route route, String hostnamePattern) {
        final EndpointInfoBuilder builder = endpointInfoBuilder(route, hostnamePattern);
        builder.availableMimeTypes(availableMimeTypes(route));
        return builder.build();
    }

    public static EndpointInfoBuilder endpointInfoBuilder(Route route, String hostnamePattern) {
        final EndpointInfoBuilder builder;
        final RoutePathType pathType = route.pathType();
        final List<String> paths = route.paths();
        switch (pathType) {
            case EXACT:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.EXACT + paths.get(0));
                break;
            case PREFIX:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.PREFIX + paths.get(0));
                break;
            case PARAMETERIZED:
                builder = EndpointInfo.builder(hostnamePattern, route.patternString());
                break;
            case REGEX:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.REGEX + paths.get(0));
                break;
            case REGEX_WITH_PREFIX:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.REGEX + paths.get(0));
                builder.regexPathPrefix(RouteUtil.PREFIX + paths.get(1));
                break;
            default:
                // Should never reach here.
                throw new Error();
        }
        return builder;
    }

    private static Set<MediaType> availableMimeTypes(Route route) {
        final ImmutableSet.Builder<MediaType> builder = ImmutableSet.builder();
        final Set<MediaType> consumeTypes = route.consumes();
        builder.addAll(consumeTypes);
        if (!consumeTypes.contains(MediaType.JSON_UTF_8)) {
            builder.add(MediaType.JSON_UTF_8);
        }
        return builder.build();
    }

    private static List<FieldInfo> fieldInfos(List<AnnotatedValueResolver> resolvers,
                                              NamedTypeInfoProvider namedTypeInfoProvider) {
        final ImmutableList.Builder<FieldInfo> fieldInfosBuilder = ImmutableList.builder();
        for (AnnotatedValueResolver resolver : resolvers) {
            final FieldInfo fieldInfo = fieldInfo(resolver, namedTypeInfoProvider);
            if (fieldInfo != null) {
                fieldInfosBuilder.add(fieldInfo);
            }
        }
        return fieldInfosBuilder.build();
    }

    @Nullable
    private static FieldInfo fieldInfo(AnnotatedValueResolver resolver,
                                       NamedTypeInfoProvider namedTypeInfoProvider) {
        final Class<? extends Annotation> annotationType = resolver.annotationType();
        if (annotationType == RequestObject.class) {
            final BeanFactoryId beanFactoryId = resolver.beanFactoryId();
            final AnnotatedBeanFactory<?> factory = AnnotatedBeanFactoryRegistry.find(beanFactoryId);
            if (factory != null) {
                final Builder<AnnotatedValueResolver> builder = ImmutableList.builder();
                factory.constructor().getValue().forEach(builder::add);
                factory.methods().values().forEach(resolvers -> resolvers.forEach(builder::add));
                factory.fields().values().forEach(builder::add);
                final List<AnnotatedValueResolver> resolvers = builder.build();
                if (!resolvers.isEmpty()) {
                    // Just use the simple name of the bean class as the field name.
                    return FieldInfo.builder(beanFactoryId.type().getSimpleName(), BEAN,
                                             fieldInfos(resolvers, namedTypeInfoProvider))
                                    .build();
                }
            } else {
                // NamedTypeInfoProvider may provide NamedTypedInfo for the implicit request object.
                final Class<?> elementType = resolver.elementType();
                NamedTypeInfo namedTypeInfo = namedTypeInfoProvider.newNamedTypeInfo(elementType);
                if (namedTypeInfo == null) {
                    namedTypeInfo = defaultRequestNamedTypeInfoProvider.newNamedTypeInfo(elementType);
                }
                if (namedTypeInfo instanceof StructInfo && !((StructInfo) namedTypeInfo).fields().isEmpty()) {
                    return FieldInfo.builder(namedTypeInfo.name(), OBJECT,
                                             ((StructInfo) namedTypeInfo).fields())
                                    .requirement(resolver.shouldExist() ?
                                                 FieldRequirement.REQUIRED : FieldRequirement.OPTIONAL)
                                    .build();
                } else {
                    return FieldInfo.builder(elementType.getName(), toTypeSignature(elementType))
                                    .requirement(resolver.shouldExist() ?
                                                 FieldRequirement.REQUIRED : FieldRequirement.OPTIONAL)
                                    .build();
                }
            }
        }

        if (annotationType != Param.class && annotationType != Header.class) {
            return null;
        }
        final TypeSignature signature;
        if (resolver.hasContainer()) {
            final Class<?> containerType = resolver.containerType();
            assert containerType != null;
            final TypeSignature parameterTypeSignature = toTypeSignature(resolver.elementType());
            if (List.class.isAssignableFrom(containerType)) {
                signature = TypeSignature.ofList(parameterTypeSignature);
            } else if (Set.class.isAssignableFrom(containerType)) {
                signature = TypeSignature.ofSet(parameterTypeSignature);
            } else {
                // Only List and Set are supported for the containerType.
                return null;
            }
        } else {
            signature = toTypeSignature(resolver.elementType());
        }
        final String name = resolver.httpElementName();
        assert name != null;

        return FieldInfo.builder(name, signature)
                        .location(location(resolver))
                        .requirement(resolver.shouldExist() ? FieldRequirement.REQUIRED
                                                            : FieldRequirement.OPTIONAL)
                        .descriptionInfo(resolver.description())
                        .build();
    }

    static TypeSignature toTypeSignature(Type type) {
        requireNonNull(type, "type");

        if (type instanceof JavaType) {
            return toTypeSignature((JavaType) type);
        }

        // The data types defined by the OpenAPI Specification:

        if (type == Void.class || type == void.class) {
            return VOID;
        }
        if (type == Boolean.class || type == boolean.class) {
            return BOOLEAN;
        }
        if (type == Byte.class || type == byte.class) {
            return BYTE;
        }
        if (type == Short.class || type == short.class) {
            return SHORT;
        }
        if (type == Integer.class || type == int.class) {
            return INT;
        }
        if (type == Long.class || type == long.class) {
            return LONG;
        }
        if (type == Float.class || type == float.class) {
            return FLOAT;
        }
        if (type == Double.class || type == double.class) {
            return DOUBLE;
        }
        if (type == Character.class || type == char.class) {
            return CHAR;
        }
        if (type == String.class) {
            return STRING;
        }
        if (type == byte[].class || type == Byte[].class) {
            return BINARY;
        }
        // End of data types defined by the OpenAPI Specification.

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
                final TypeSignature key = toTypeSignature(parameterizedType.getActualTypeArguments()[0]);
                final TypeSignature value = toTypeSignature(parameterizedType.getActualTypeArguments()[1]);
                return TypeSignature.ofMap(key, value);
            }

            if (Optional.class.isAssignableFrom(rawType) || "scala.Option".equals(rawType.getName())) {
                return TypeSignature.ofOptional(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }

            final List<TypeSignature> actualTypes = Stream.of(parameterizedType.getActualTypeArguments())
                                                          .map(AnnotatedDocServicePlugin::toTypeSignature)
                                                          .collect(toImmutableList());
            return TypeSignature.ofContainer(rawType.getSimpleName(), actualTypes);
        }

        if (type instanceof WildcardType) {
            // Create an unresolved type with an empty string so that the type name will be '?'.
            return TypeSignature.ofUnresolved("");
        }
        if (type instanceof TypeVariable) {
            return TypeSignature.ofBase(type.getTypeName());
        }
        if (type instanceof GenericArrayType) {
            return TypeSignature.ofList(toTypeSignature(((GenericArrayType) type).getGenericComponentType()));
        }

        if (!(type instanceof Class)) {
            return TypeSignature.ofBase(type.getTypeName());
        }

        final Class<?> clazz = (Class<?>) type;
        if (clazz.isArray()) {
            // If it's an array, return it as a list.
            return TypeSignature.ofList(toTypeSignature(clazz.getComponentType()));
        }

        return TypeSignature.ofNamed(clazz);
    }

    static TypeSignature toTypeSignature(JavaType type) {
        if (type.isArrayType() || type.isCollectionLikeType()) {
            return TypeSignature.ofList(toTypeSignature(type.getContentType()));
        }

        if (type.isMapLikeType()) {
            final TypeSignature key = toTypeSignature(type.getKeyType());
            final TypeSignature value = toTypeSignature(type.getContentType());
            return TypeSignature.ofMap(key, value);
        }

        if (Optional.class.isAssignableFrom(type.getRawClass()) ||
            "scala.Option".equals(type.getRawClass().getName())) {
            return TypeSignature.ofOptional(
                    toTypeSignature(type.getBindings().getBoundType(0)));
        }

        return toTypeSignature(type.getRawClass());
    }

    private static FieldLocation location(AnnotatedValueResolver resolver) {
        if (resolver.isPathVariable()) {
            return PATH;
        }
        if (resolver.annotationType() == Param.class) {
            return QUERY;
        }
        if (resolver.annotationType() == Header.class) {
            return HEADER;
        }
        return UNSPECIFIED;
    }

    @VisibleForTesting
    static ServiceSpecification generate(Map<Class<?>, DescriptionInfo> serviceDescription,
                                         Map<Class<?>, Set<MethodInfo>> methodInfos,
                                         NamedTypeInfoProvider namedTypeInfoProvider) {
        final Set<ServiceInfo> serviceInfos = methodInfos
                .entrySet().stream()
                .map(entry -> {
                    final Class<?> service = entry.getKey();
                    return new ServiceInfo(service.getName(), entry.getValue(),
                                           serviceDescription.getOrDefault(service, DescriptionInfo.empty()));
                })
                .collect(toImmutableSet());

        final Set<TypeSignature> requestNamedTypes =
                serviceInfos.stream()
                            .flatMap(s -> s.findNamedTypes(true).stream())
                            .collect(toImmutableSet());

        return ServiceSpecification.generate(
                serviceInfos,
                typeSignature -> newNamedTypeInfo(typeSignature, namedTypeInfoProvider, requestNamedTypes));
    }

    private static NamedTypeInfo newNamedTypeInfo(TypeSignature typeSignature, NamedTypeInfoProvider provider,
                                                  Set<TypeSignature> requestNamedTypes) {
        final Object typeDescriptor = typeSignature.namedTypeDescriptor();
        if (typeDescriptor == null) {
            throw new IllegalArgumentException("cannot create a named type from: " + typeSignature);
        }

        NamedTypeInfo namedTypeInfo = provider.newNamedTypeInfo(typeDescriptor);
        if (namedTypeInfo != null) {
            return namedTypeInfo;
        }

        if (requestNamedTypes.contains(typeSignature)) {
            namedTypeInfo = defaultRequestNamedTypeInfoProvider.newNamedTypeInfo(typeDescriptor);
        } else {
            namedTypeInfo = defaultResponseNamedTypeInfoProvider.newNamedTypeInfo(typeDescriptor);
        }
        if (namedTypeInfo != null) {
            return namedTypeInfo;
        } else {
            // An unresolved StructInfo.
            return new StructInfo(typeSignature.name(), ImmutableList.of());
        }
    }

    @Override
    public Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of(TreeNode.class);
    }

    @Override
    @Nullable
    public String serializeExampleRequest(String serviceName, String methodName,
                                          Object exampleRequest) {
        try {
            return objectWriter.writeValueAsString(exampleRequest);
        } catch (JsonProcessingException e) {
            // Ignore the exception and just return Optional.empty().
        }
        return null;
    }

    @Override
    public String toString() {
        return AnnotatedDocServicePlugin.class.getSimpleName();
    }
}
