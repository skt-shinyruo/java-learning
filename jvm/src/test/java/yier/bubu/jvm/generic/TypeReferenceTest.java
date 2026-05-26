package yier.bubu.jvm.generic;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;

public class TypeReferenceTest {
    @Test
    public void getType_shouldCapturePlainClass() {
        TypeReference<String> ref = new TypeReference<String>() {};

        Assert.assertSame(String.class, ref.getType());
        Assert.assertSame(String.class, ref.getRawType());
        Assert.assertEquals("java.lang.String", ref.toString());
    }

    @Test
    public void getType_shouldCaptureParameterizedType() {
        TypeReference<List<String>> ref = new TypeReference<List<String>>() {};

        Assert.assertTrue(ref.getType() instanceof ParameterizedType);
        ParameterizedType type = (ParameterizedType) ref.getType();
        Assert.assertSame(List.class, type.getRawType());
        Assert.assertSame(String.class, type.getActualTypeArguments()[0]);
        Assert.assertSame(List.class, ref.getRawType());
    }

    @Test
    public void getType_shouldCaptureNestedParameterizedType() {
        TypeReference<Map<String, List<Integer>>> ref = new TypeReference<Map<String, List<Integer>>>() {};

        ParameterizedType type = (ParameterizedType) ref.getType();
        Assert.assertSame(Map.class, type.getRawType());
        Assert.assertSame(String.class, type.getActualTypeArguments()[0]);
        Assert.assertTrue(type.getActualTypeArguments()[1] instanceof ParameterizedType);
        ParameterizedType nested = (ParameterizedType) type.getActualTypeArguments()[1];
        Assert.assertSame(List.class, nested.getRawType());
        Assert.assertSame(Integer.class, nested.getActualTypeArguments()[0]);
        Assert.assertSame(Map.class, ref.getRawType());
    }

    @Test
    public void getType_shouldCaptureWildcardType() {
        TypeReference<List<? extends Number>> ref = new TypeReference<List<? extends Number>>() {};

        ParameterizedType type = (ParameterizedType) ref.getType();
        Assert.assertTrue(type.getActualTypeArguments()[0] instanceof WildcardType);
        WildcardType wildcardType = (WildcardType) type.getActualTypeArguments()[0];
        Assert.assertSame(Number.class, wildcardType.getUpperBounds()[0]);
        Assert.assertSame(List.class, ref.getRawType());
    }

    @Test
    public void getType_shouldCaptureGenericArrayType() {
        TypeReference<List<String>[]> ref = new TypeReference<List<String>[]>() {};

        Assert.assertTrue(ref.getType() instanceof GenericArrayType);
        Assert.assertSame(List[].class, ref.getRawType());
    }

    @Test
    public void getType_shouldCaptureTypeVariableInsideGenericMethod() {
        Type type = captureListOfTypeVariable();

        ParameterizedType parameterizedType = (ParameterizedType) type;
        Assert.assertTrue(parameterizedType.getActualTypeArguments()[0] instanceof TypeVariable);
        TypeVariable<?> variable = (TypeVariable<?>) parameterizedType.getActualTypeArguments()[0];
        Assert.assertEquals("T", variable.getName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_shouldRejectRawSubclassWithoutTypeParameter() {
        new RawTypeReference();
    }

    private static <T> Type captureListOfTypeVariable() {
        return new TypeReference<List<T>>() {}.getType();
    }

    @SuppressWarnings("rawtypes")
    private static final class RawTypeReference extends TypeReference {
    }
}
