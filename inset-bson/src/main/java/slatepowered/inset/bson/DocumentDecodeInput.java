package slatepowered.inset.bson;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import slatepowered.inset.codec.CodecContext;
import slatepowered.inset.codec.DecodeInput;
import slatepowered.inset.util.DebugLogging;
import slatepowered.inset.util.Reflections;
import slatepowered.inset.util.ValueUtils;
import slatepowered.veru.reflect.ReflectUtil;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads data from a {@link Document} input.
 */
@Getter
@RequiredArgsConstructor
public class DocumentDecodeInput extends DecodeInput implements DebugLogging {

    protected final String keyFieldOverride;

    /**
     * The input document to read from.
     */
    final Document document;

    // decodes a map-valid key retrieved from a bson document
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object decodeDocumentKey(CodecContext context, String value, Type expectedType) {
        Class<?> expectedClass = ReflectUtil.getClassForType(expectedType);
        if (expectedClass == String.class) {
            return value;
        }

        /* Convert floating point numbers */
        if (
                expectedClass == Float.class || expectedClass == Double.class ||
                expectedClass == float.class || expectedClass == double.class
        ) {
            return Double.longBitsToDouble(Long.parseLong(value));
        }

        /* Convert boxed numbers */
        if (Number.class.isAssignableFrom(expectedClass)) {
            return ValueUtils.castBoxedNumber(Long.parseLong(value), expectedClass);
        }

        /* Convert primitive numbers */
        if (expectedClass.isPrimitive()) {
            return ValueUtils.castBoxedPrimitive(Long.parseLong(value), expectedClass);
        }

        throw new IllegalArgumentException("Got unsupported map key type to decode: " + value.getClass());
    }

    // decodes a value retrieved from a bson document
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object decodeDocumentValue(CodecContext context, Object value, Type expectedType) {
        Class<?> expectedClass = ReflectUtil.getClassForType(expectedType);
        if (DEBUG_LOGGING_LEVEL >= TRACE) log("decodeDocumentValue(" + compactString(value) + ", expected: " + compactString(expectedType) + ")");

        if (value == null) {
            if (List.class.isAssignableFrom(expectedClass)) {
                return new ArrayList<>();
            } else if (Map.class.isAssignableFrom(expectedClass)) {
                return new HashMap<>();
            } else if (expectedClass.isArray()) {
                return Array.newInstance(expectedClass.getComponentType(), 0);
            }

            return null;
        }

        // check for list value before checking for direct primitives
        // because it is the only collection type generated by bson
        if (value instanceof List) {
            if (Map.class.isAssignableFrom(expectedClass)) {
                List<List> encodedMap = (List<List>) value;
                if (DEBUG_LOGGING_LEVEL >= TRACE) log(" Decoding list into map, enc = " + compactString(encodedMap));

                /*
                 * Maps are encoded as arrays with each entry being a pair of key and value
                 * represented in BSON as another array:
                 *
                 * { a = 6, b = 7 }
                 * becomes
                 * [ ["a", 6], ["b", 7] ]
                 */

                // check for parameter types
                Type expectedKeyType;
                Type expectedValueType;
                if (Map.class.isAssignableFrom(expectedClass) && expectedType instanceof ParameterizedType) {
                    expectedKeyType = ((ParameterizedType) expectedType).getActualTypeArguments()[0];
                    expectedValueType = ((ParameterizedType) expectedType).getActualTypeArguments()[1];
                } else {
                    expectedKeyType = Object.class;
                    expectedValueType = Object.class;
                }

                Map convertedMap = new HashMap();
                encodedMap.forEach(pair -> convertedMap.put(
                        decodeDocumentValue(context, pair.get(0), expectedKeyType),  // key
                        decodeDocumentValue(context, pair.get(1), expectedValueType) // value
                ));

                return convertedMap;
            }

            if (context == null) {
                throw new IllegalArgumentException("Document contains non-primitive value for key field");
            }

            // check if we expect an array
            if (expectedClass.isArray()) {
                Type expectedElementType = expectedClass.getComponentType();

                List list = (List) value;
                final int length = list.size();
                Object array = Array.newInstance(expectedClass.getComponentType(), length);

                for (int i = 0; i < length; i++) {
                    Array.set(array, i, decodeDocumentValue(context, value, expectedElementType));
                }

                return array;
            }

            // try to find accurate element type
            Type expectedElementType = Object.class;
            if (expectedType instanceof ParameterizedType) {
                expectedElementType = ((ParameterizedType)expectedType).getActualTypeArguments()[0];
            }

            // decode list
            List list = (List) value;
            final int length = list.size();
            List newList = new ArrayList(length);

            for (int i = 0; i < length; i++) {
                newList.add(decodeDocumentValue(context, list.get(i), expectedElementType));
            }

            return newList;
        }

        // primitives which can represent complex definitions
        if (expectedClass.isInstance(value)) {
            return value;
        }

        // simple enum class
        if (expectedClass.isEnum() && value instanceof String) {
            String str = (String) value;

            if (str.contains(":")) {
                String[] strings = ((String) value).split(":");
                String enumDeclClassName = strings[0];
                String enumConstantName = strings[1];

                Class<?> enumDeclClass = Reflections.findClass(enumDeclClassName);

                for (Object constant : enumDeclClass.getEnumConstants()) {
                    if (((Enum)constant).name().equalsIgnoreCase(enumConstantName)) {
                        return constant;
                    }
                }

                throw new IllegalArgumentException("Could not resolve `" + value + "` to an enum value of " + enumDeclClass);
            }

            for (Object constant : expectedClass.getEnumConstants()) {
                if (((Enum)constant).name().equalsIgnoreCase(str)) {
                    return constant;
                }
            }

            throw new IllegalArgumentException("Could not resolve `" + value + "` to an enum value of " + expectedClass);
        }

        // complex enum declaration
        if (value instanceof String && BsonCodecs.shouldWriteClassName(expectedClass)) {
            String[] strings = ((String) value).split(":");
            String enumDeclClassName = strings[0];
            String enumConstantName = strings[1];

            Class<?> enumDeclClass = Reflections.findClass(enumDeclClassName);

            for (Object constant : enumDeclClass.getEnumConstants()) {
                if (((Enum)constant).name().equalsIgnoreCase(enumConstantName)) {
                    return constant;
                }
            }

            throw new IllegalArgumentException("Could not resolve `" + value + "` to an enum value of " + enumDeclClass);
        }

        /* Complex objects */
        //  only support primitives if context is
        //  null, because this is only ever used to decode
        //  the primary key field
        if (value instanceof Document) {
            if (context == null) {
                throw new IllegalArgumentException("Document contains non-primitive value for key field");
            }

            Document doc = (Document) value;

            // check for map
            if (Map.class.isAssignableFrom(expectedClass)) {
                // check for parameter types
                Type expectedKeyType;
                Type expectedValueType;
                if (expectedType instanceof ParameterizedType) {
                    expectedKeyType = ((ParameterizedType)expectedType).getActualTypeArguments()[0];
                    expectedValueType = ((ParameterizedType)expectedType).getActualTypeArguments()[1];
                } else {
                    expectedKeyType = Object.class;
                    expectedValueType = Object.class;
                }

                Map map = new HashMap();
                doc.forEach((k, v) -> map.put(
                        decodeDocumentKey(context, k, expectedKeyType),
                        decodeDocumentValue(context, v, expectedValueType)
                ));

                return map;
            }

            // decode nested object
            String className = doc.getString(BsonCodecs.CLASS_NAME_FIELD);
            if (DEBUG_LOGGING_LEVEL >= TRACE) log("  Decoding document value " + compactString(doc) + " into explicit class " + compactString(className));
            if (className != null) {
                // decode with an alternate target type
                Class<?> klass = Reflections.findClass(className);
                if (klass != null) {
                    DocumentDecodeInput input = new DocumentDecodeInput(keyFieldOverride, doc);
                    return context.findCodec(klass).constructAndDecode(context, input);
                }

                if (DEBUG_LOGGING_LEVEL >= TRACE) log("  Could not resolve class from " + compactString(className) + ", decoding into expected " + compactString(expectedType));
            }

            DocumentDecodeInput input = new DocumentDecodeInput(keyFieldOverride, doc);
            return context.findCodec(expectedClass).constructAndDecode(context, input);
        }

        /* Primitives */
        else {
            if (Number.class.isAssignableFrom(expectedClass)) {
                return ValueUtils.castBoxedNumber((Number) value, expectedClass);
            }

            if (expectedClass.isPrimitive()) {
                return ValueUtils.castBoxedPrimitive(value, expectedClass);
            }

            return value;
        }

//        throw new IllegalArgumentException("Got unsupported value type to decode: " + value.getClass());
    }

    @Override
    public Object read(CodecContext context, String field, Type expectedType) {
        Object value = document.get(field);
        if (DEBUG_LOGGING_LEVEL >= TRACE) log("Decoding value for `" + field + "` expected: " + expectedType + " from value `" + compactString(value) + "`");
        Object v = decodeDocumentValue(context, value, expectedType);
        if (DEBUG_LOGGING_LEVEL >= TRACE) log(" Finished decode v = " + compactString(v));
        return v;
    }

    @Override
    public Object readKey(String field, Type expectedType) {
        Object value = document.get(keyFieldOverride != null ? keyFieldOverride : field);
        return decodeDocumentValue(null, value, expectedType);
    }

}
