/*
 * Copyright 2022 LINE Corporation
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

import static com.linecorp.armeria.internal.common.util.ObjectCollectingUtil.MONO_CLASS;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.annotation.Nullable;

public final class ClassUtil {

    /**
     * Converts the specified {@link Type} to a {@link Class} instance.
     */
    @Nullable
    public static Class<?> typeToClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return null;
    }

    /**
     * Unwraps an enclosing unary async type, which includes {@link CompletionStage},
     * {@code reactor.core.publisher.Mono} and {@code scala.concurrent.Future}, and returns its type arguments.
     * Returns the accepted type itself if the {@link Type} is not enclosed by one of them.
     */
    public static Type unwrapUnaryAsyncType(Type type) {
        return unwrapAsyncType(type, ClassUtil::isUnaryAsyncType);
    }

    /**
     * Unwraps an enclosing async type, which includes {@link CompletionStage},
     * {@link Publisher} and {@code scala.concurrent.Future}, and returns its type arguments.
     * Returns the accepted type itself if the {@link Type} is not enclosed by one of them.
     */
    public static Type unwrapAsyncType(Type type) {
        return unwrapAsyncType(type, ClassUtil::isAsyncType);
    }

    private static Type unwrapAsyncType(Type type, Predicate<Class<?>> asyncTypePredicate) {
        if (type instanceof Class) {
            return type;
        }

        if (!(type instanceof ParameterizedType)) {
            return type;
        }

        final ParameterizedType ptype = (ParameterizedType) type;
        final Type[] typeArguments = ptype.getActualTypeArguments();
        if (typeArguments.length == 0) {
            return type;
        }

        final Class<?> clazz = (Class<?>) ptype.getRawType();
        final Type typeArgument = typeArguments[0];

        return asyncTypePredicate.test(clazz) ? typeArgument : type;
    }

    private static boolean isUnaryAsyncType(Class<?> clazz) {
        return CompletionStage.class.isAssignableFrom(clazz) ||
               ScalaUtil.isScalaFuture(clazz) ||
               (MONO_CLASS != null && MONO_CLASS.isAssignableFrom(clazz));
    }

    private static boolean isAsyncType(Class<?> clazz) {
        return CompletionStage.class.isAssignableFrom(clazz) ||
               Publisher.class.isAssignableFrom(clazz) ||
               ScalaUtil.isScalaFuture(clazz);
    }

    private ClassUtil() {}
}
