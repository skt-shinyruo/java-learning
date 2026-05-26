package yier.bubu.jvm.generic;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

public abstract class TypeReference<T> {
    private final Type type;
    private final Class<?> rawType;

    protected TypeReference() {
        Type genericSuperclass = getClass().getGenericSuperclass();
        if (!(genericSuperclass instanceof ParameterizedType)) {
            throw new IllegalArgumentException("TypeReference must be created with a type parameter");
        }

        Type capturedType = ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0];
        this.type = capturedType;
        this.rawType = resolveRawType(capturedType);
    }

    public Type getType() {
        return type;
    }

    public Class<?> getRawType() {
        return rawType;
    }

    @Override
    public String toString() {
        return type.getTypeName();
    }

    private static Class<?> resolveRawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
            throw new IllegalArgumentException("Parameterized type raw type is not a Class: " + rawType);
        }
        if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type).getGenericComponentType();
            return Array.newInstance(resolveRawType(componentType), 0).getClass();
        }
        if (type instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : resolveRawType(bounds[0]);
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return upperBounds.length == 0 ? Object.class : resolveRawType(upperBounds[0]);
        }
        throw new IllegalArgumentException("Cannot resolve raw type for: " + type);
    }
}
