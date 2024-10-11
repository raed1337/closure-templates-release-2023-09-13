
/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.sharedpasses.render;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.Lists;
import com.google.common.html.types.SafeHtml;
import com.google.common.html.types.SafeHtmlProto;
import com.google.common.html.types.SafeScript;
import com.google.common.html.types.SafeScriptProto;
import com.google.common.html.types.SafeStyle;
import com.google.common.html.types.SafeStyleProto;
import com.google.common.html.types.SafeStyleSheet;
import com.google.common.html.types.SafeStyleSheetProto;
import com.google.common.html.types.SafeUrl;
import com.google.common.html.types.SafeUrlProto;
import com.google.common.html.types.TrustedResourceUrl;
import com.google.common.html.types.TrustedResourceUrlProto;
import com.google.common.primitives.Primitives;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SoyDataException;
import com.google.template.soy.data.SoyMap;
import com.google.template.soy.data.SoyProtoValue;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueConverter;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.SoyValueUnconverter;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.FloatData;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.NumberData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.data.restricted.UndefinedData;
import com.google.template.soy.internal.proto.JavaQualifiedNames;
import com.google.template.soy.plugin.internal.JavaPluginExecContext;
import com.google.template.soy.plugin.java.PluginInstances;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.MethodSignature;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.types.FunctionType;
import com.google.template.soy.types.ListType;
import com.google.template.soy.types.MapType;
import com.google.template.soy.types.SoyProtoEnumType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Adapts JavaValueFactory to work with Tofu, wrapping the JavaValues in TofuJavaValues. */
// TODO(b/19252021): Add unit tests after things shape up.
class TofuValueFactory extends JavaValueFactory {
  private final SourceLocation fnSourceLocation;
  private final String instanceKey;
  private final PluginInstances pluginInstances;
  @Nullable private final FunctionType externSig;

  TofuValueFactory(JavaPluginExecContext fn, PluginInstances pluginInstances) {
    this(fn.getSourceLocation(), fn.getFunctionName(), pluginInstances, null);
  }

  TofuValueFactory(
      SourceLocation fnSourceLocation,
      String instanceKey,
      PluginInstances pluginInstances,
      FunctionType externSig) {
    this.fnSourceLocation = fnSourceLocation;
    this.instanceKey = instanceKey;
    this.pluginInstances = pluginInstances;
    this.externSig = externSig;
  }

  SoyValue computeForJava(
      SoyJavaSourceFunction srcFn, List<SoyValue> args, TofuPluginContext context) {
    List<JavaValue> javaArgs =
        Lists.transform(args, soyArg -> TofuJavaValue.forSoyValue(soyArg, fnSourceLocation));
    TofuJavaValue result = (TofuJavaValue) srcFn.applyForJavaSource(this, javaArgs, context);
    if (!result.hasSoyValue()) {
      throw RenderException.create(
          "applyForJavaSource must return either an 'args' parameter or the result of "
              + "JavaValueFactory method.");
    }
    return result.soyValue();
  }

  TofuJavaValue callStaticMethod(
      MethodSignature methodSig, @Nullable SoyType returnType, JavaValue... params) {
    return callStaticMethod(toMethod(methodSig), returnType, params);
  }

  @Override
  public TofuJavaValue callStaticMethod(MethodSignature methodSig, JavaValue... params) {
    return callStaticMethod(methodSig, null, params);
  }

  TofuJavaValue callStaticMethod(Method method, @Nullable SoyType returnType, JavaValue... params) {
    try {
      Object adaptedParams = adaptParams(method, params);
      return wrapInTofuValue(method, method.invoke(null, adaptedParams), returnType);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      throw RenderException.create("Unexpected exception", e);
    }
  }

  @Override
  public TofuJavaValue callStaticMethod(Method method, JavaValue... params) {
    return callStaticMethod(method, null, params);
  }

  TofuJavaValue callInstanceMethod(
      MethodSignature methodSig, @Nullable SoyType returnType, JavaValue... params) {
    return callInstanceMethod(toMethod(methodSig), returnType, params);
  }

  @Override
  public TofuJavaValue callInstanceMethod(MethodSignature methodSig, JavaValue... params) {
    return callInstanceMethod(methodSig, null, params);
  }

  TofuJavaValue callInstanceMethod(
      Method method, @Nullable SoyType returnType, JavaValue... params) {
    Supplier<Object> instanceSupplier = pluginInstances.get(instanceKey);
    if (instanceSupplier == null) {
      throw RenderException.create(
          "No plugin instance registered for key '"
              + instanceKey
              + "'. Available keys are: "
              + pluginInstances.keys());
    }
    try {
      Object adaptedParams = adaptParams(method, params);
      return wrapInTofuValue(method, method.invoke(instanceSupplier.get(), adaptedParams), returnType);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      throw RenderException.create("Unexpected exception", e);
    }
  }

  @Override
  public TofuJavaValue callInstanceMethod(Method method, JavaValue... params) {
    return callInstanceMethod(method, null, params);
  }

  @Override
  public TofuJavaValue listOf(List<JavaValue> args) {
    List<SoyValue> values =
        Lists.transform(
            args,
            soyArg -> {
              TofuJavaValue tjv = (TofuJavaValue) soyArg;
              if (!tjv.hasSoyValue()) {
                throw RenderException.create(
                    "listOf may only be called with the 'arg' parameters to "
                        + "JavaValueFactory methods");
              }
              return tjv.soyValue();
            });
    return TofuJavaValue.forSoyValue(
        SoyValueConverter.INSTANCE.convert(values).resolve(), fnSourceLocation);
  }

  @Override
  public TofuJavaValue constant(boolean value) {
    return TofuJavaValue.forSoyValue(BooleanData.forValue(value), SourceLocation.UNKNOWN);
  }

  @Override
  public TofuJavaValue constant(double value) {
    return TofuJavaValue.forSoyValue(FloatData.forValue(value), SourceLocation.UNKNOWN);
  }

  @Override
  public TofuJavaValue constant(long value) {
    return TofuJavaValue.forSoyValue(IntegerData.forValue(value), SourceLocation.UNKNOWN);
  }

  @Override
  public TofuJavaValue constant(String value) {
    return TofuJavaValue.forSoyValue(StringData.forValue(value), SourceLocation.UNKNOWN);
  }

  @Override
  public TofuJavaValue constantNull() {
    return TofuJavaValue.forSoyValue(NullData.INSTANCE, SourceLocation.UNKNOWN);
  }

  private TofuJavaValue wrapInTofuValue(
      Method method, Object object, @Nullable SoyType returnType) {
    if (object instanceof SoyValue) {
      return TofuJavaValue.forSoyValue((SoyValue) object, fnSourceLocation);
    }
    if (returnType != null && returnType.getKind() == SoyType.Kind.MAP && object instanceof Map) {
      object = SoyValueConverter.markAsSoyMap((Map) object);
    }
    try {
      return TofuJavaValue.forSoyValue(
          SoyValueConverter.INSTANCE.convert(object).resolve(), fnSourceLocation);
    } catch (SoyDataException e) {
      throw RenderException.create("Invalid return value from `" + method + "`", e);
    }
  }

  private static Method toMethod(MethodSignature methodSig) {
    try {
      Class<?> clazz = Class.forName(methodSig.fullyQualifiedClassName());
      Method method =
          clazz.getMethod(methodSig.methodName(), methodSig.arguments().toArray(new Class<?>[0]));
      if (!method.getReturnType().equals(methodSig.returnType())) {
        throw RenderException.create(
            "Invalid methodSig: '"
                + methodSig
                + "'. Return type is '"
                + method.getReturnType().getName()
                + "', not '"
                + methodSig.returnType()
                + "'.");
      }
      return method;
    } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
      throw RenderException.create("Invalid methodSig: '" + methodSig + "'.", e);
    }
  }

  private Object[] adaptParams(Method method, JavaValue[] tofuValues) {
    Class<?>[] paramTypes = method.getParameterTypes();
    if (tofuValues.length != paramTypes.length) {
      throw RenderException.create(
          "Parameters ["
              + Arrays.asList(paramTypes)
              + " don't match values ["
              + Arrays.asList(tofuValues)
              + "], calling method: "
              + method);
    }
    Object[] params = new Object[tofuValues.length];
    for (int i = 0; i < tofuValues.length; i++) {
      params[i] = adaptParam((TofuJavaValue) tofuValues[i], paramTypes[i], method, i);
    }
    return params;
  }

  private Object adaptParam(TofuJavaValue tofuVal, Class<?> type, Method method, int i) {
    boolean isExternApi = externSig != null;

    if (!tofuVal.hasSoyValue()) {
      return tofuVal.rawValue();
    } else {
      SoyValue value = tofuVal.soyValue();
      if (value instanceof NullData || value instanceof UndefinedData) {
        return handleNullData(type, method, i, tofuVal);
      } else if (isExternApi && type == Object.class) {
        return SoyValueUnconverter.unconvert(value);
      } else if (isExternApi && type == Number.class) {
        return value instanceof NumberData ? ((NumberData) value).javaNumberValue() : value.numberValue();
      } else if (type.isInstance(value)) {
        return value;
      }

      return adaptPrimitiveTypes(type, method, i, isExternApi, value);
    }
  }

  private Object handleNullData(Class<?> type, Method method, int i, TofuJavaValue tofuVal) {
    if (Primitives.allPrimitiveTypes().contains(type)) {
      throw RenderException.create(
          "cannot call method "
              + method.getDeclaringClass().getName()
              + "."
              + method.getName()
              + " because parameter["
              + i
              + "] expects a primitive type ["
              + type
              + "], but actual value is null [ "
              + tofuVal
              + "]");
    }
    return null;
  }

  private Object adaptPrimitiveTypes(Class<?> type, Method method, int i, boolean isExternApi, SoyValue value) {
    Class<?> primitiveType = Primitives.unwrap(type);
    if (primitiveType == boolean.class) {
      return value.booleanValue();
    } else if (primitiveType == int.class) {
      return value.integerValue();
    } else if (primitiveType == long.class) {
      return value.longValue();
    } else if (primitiveType == double.class) {
      return value.numberValue();
    } else if (primitiveType == float.class) {
      return (float) value.numberValue();
    } else if (type == String.class) {
      return value.stringValue();
    } else if (Iterable.class.isAssignableFrom(type)) {
      return handleIterableType(isExternApi, value, i);
    } else if (Map.class.isAssignableFrom(type) && isExternApi) {
      return handleMapType(value, i);
    } else if (type.isEnum() && ProtocolMessageEnum.class.isAssignableFrom(type)) {
      return adaptEnumType(type, value);
    } else if (type == SafeHtml.class) {
      return ((SanitizedContent) value).toSafeHtml();
    } else if (type == SafeScript.class) {
      return ((SanitizedContent) value).toSafeScript();
    } else if (type == SafeStyle.class) {
      return ((SanitizedContent) value).toSafeStyle();
    } else if (type == SafeStyleSheet.class) {
      return ((SanitizedContent) value).toSafeStyleSheet();
    } else if (type == SafeUrl.class) {
      return ((SanitizedContent) value).toSafeUrl();
    } else if (type == TrustedResourceUrl.class) {
      return ((SanitizedContent) value).toTrustedResourceUrl();
    } else if (type == SafeHtmlProto.class) {
      return ((SanitizedContent) value).toSafeHtmlProto();
    } else if (type == SafeScriptProto.class) {
      return ((SanitizedContent) value).toSafeScriptProto();
    } else if (type == SafeStyleProto.class) {
      return ((SanitizedContent) value).toSafeStyleProto();
    } else if (type == SafeStyleSheetProto.class) {
      return ((SanitizedContent) value).toSafeStyleSheetProto();
    } else if (type == SafeUrlProto.class) {
      return ((SanitizedContent) value).toSafeUrlProto();
    } else if (type == TrustedResourceUrlProto.class) {
      return ((SanitizedContent) value).toTrustedResourceUrlProto();
    } else if (Message.class.isAssignableFrom(type)) {
      return type.cast(value.getProto());
    } else {
      throw new UnsupportedOperationException(
          "cannot call method "
              + method.getDeclaringClass().getName()
              + "."
              + method.getName()
              + " because parameter["
              + i
              + "] expects a "
              + type
              + ", but actual value is a `"
              + value
              + "`");
    }
  }

  private Object handleIterableType(boolean isExternApi, SoyValue value, int i) {
    if (isExternApi) {
      return value.asJavaList().stream()
          .map(item -> adaptParamItem(item, ((ListType) externSig.getParameters().get(i).getType()).getElementType()))
          .collect(toImmutableList());
    } else {
      return value.asJavaList();
    }
  }

  private Object handleMapType(SoyValue value, int i) {
    SoyType paramType = externSig.getParameters().get(i).getType();
    if (paramType.getKind() == Kind.RECORD) {
      return ((SoyMap) value)
          .entrySet().stream()
              .collect(
                  toImmutableMap(
                      e -> e.getKey().coerceToString(),
                      e -> SoyValueUnconverter.unconvert(e.getValue())));
    }
    MapType mapType = (MapType) paramType;
    return ((SoyMap) value)
        .entrySet().stream()
            .collect(
                toImmutableMap(
                    e -> adaptParamItem(e.getKey(), mapType.getKeyType()),
                    e -> adaptParamItem(e.getValue(), mapType.getValueType())));
  }

  private Object adaptEnumType(Class<?> type, SoyValue value) {
    try {
      return type.getDeclaredMethod("forNumber", int.class).invoke(null, value.integerValue());
    } catch (ReflectiveOperationException roe) {
      throw RenderException.create("Invalid parameter: " + value, roe);
    }
  }

  private Object adaptParamItem(SoyValueProvider item, SoyType elmType) {
    SoyValue val = item.resolve();
    switch (elmType.getKind()) {
      case INT:
        return val.longValue();
      case FLOAT:
        return val.floatValue();
      case STRING:
        return val.coerceToString();
      case BOOL:
        return val.coerceToBoolean();
      case PROTO:
        return ((SoyProtoValue) val).getProto();
      case PROTO_ENUM:
        return adaptProtoEnum(elmType, val);
      default:
        throw new AssertionError();
    }
  }

  private Object adaptProtoEnum(SoyType elmType, SoyValue val) {
    String javaClass = JavaQualifiedNames.getClassName(((SoyProtoEnumType) elmType).getDescriptor());
    try {
      return Class.forName(javaClass)
          .getDeclaredMethod("forNumber", int.class)
          .invoke(null, val.integerValue());
    } catch (ReflectiveOperationException roe) {
      throw RenderException.create("Invalid parameter: " + val, roe);
    }
  }
}
