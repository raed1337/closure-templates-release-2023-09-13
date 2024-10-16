
/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc.restricted;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.internal.ParamStore;
import com.google.template.soy.jbcsrc.restricted.Expression.Feature;
import com.google.template.soy.jbcsrc.runtime.JbcSrcRuntime;
import com.ibm.icu.util.ULocale;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

/** A reference to a type that can be constructed at runtime. */
@AutoValue
public abstract class ConstructorRef {
  /**
   * Returns a new {@link ConstructorRef} that refers to a constructor on the given type with the
   * given parameter types.
   */
  public static ConstructorRef create(TypeInfo type, Method init) {
    validateConstructor(init);
    return new AutoValue_ConstructorRef(type, init, ImmutableList.copyOf(init.getArgumentTypes()));
  }

  private static void validateConstructor(Method init) {
    checkArgument(
        init.getName().equals("<init>") && init.getReturnType().equals(Type.VOID_TYPE),
        "'%s' is not a valid constructor",
        init);
  }

  /**
   * Returns a new {@link ConstructorRef} that refers to a constructor on the given type with the
   * given parameter types.
   */
  public static ConstructorRef create(TypeInfo type, Iterable<Type> argTypes) {
    return create(
        type, new Method("<init>", Type.VOID_TYPE, Iterables.toArray(argTypes, Type.class)));
  }

  public static ConstructorRef create(Class<?> clazz, Class<?>... argTypes) {
    TypeInfo type = TypeInfo.create(clazz);
    Constructor<?> constructor = getConstructor(clazz, argTypes);
    Type constructorType = Type.getType(constructor);

    return new AutoValue_ConstructorRef(
        type, Method.getMethod(constructor), ImmutableList.copyOf(constructorType.getArgumentTypes()));
  }

  private static Constructor<?> getConstructor(Class<?> clazz, Class<?>... argTypes) {
    try {
      return clazz.getConstructor(argTypes);
    } catch (NoSuchMethodException | SecurityException e) {
      throw new RuntimeException("Unable to find constructor: " + e.getMessage(), e);
    }
  }

  public static final ConstructorRef ARRAY_LIST = create(ArrayList.class);
  public static final ConstructorRef ARRAY_LIST_SIZE = create(ArrayList.class, int.class);
  public static final ConstructorRef HASH_MAP_CAPACITY = create(HashMap.class, int.class);
  public static final ConstructorRef LINKED_HASH_MAP_CAPACITY =
      create(LinkedHashMap.class, int.class);
  public static final ConstructorRef PARAM_STORE_AUGMENT =
      create(ParamStore.class, SoyRecord.class, int.class);
  public static final ConstructorRef PARAM_STORE_SIZE = create(ParamStore.class, int.class);

  public static final ConstructorRef MSG_RENDERER =
      create(
          JbcSrcRuntime.MsgRenderer.class,
          long.class,
          ImmutableList.class,
          ULocale.class,
          int.class,
          boolean.class);
  public static final ConstructorRef PLRSEL_MSG_RENDERER =
      create(
          JbcSrcRuntime.PlrSelMsgRenderer.class,
          long.class,
          ImmutableList.class,
          ULocale.class,
          int.class,
          boolean.class);

  public static final ConstructorRef REPLAYING_BUFFERED_RENDER_DONE_FN =
      create(JbcSrcRuntime.ReplayingBufferedRenderDoneFn.class);

  public static final ConstructorRef ESCAPING_BUFFERED_RENDER_DONE_FN =
      create(JbcSrcRuntime.EscapingBufferedRenderDoneFn.class, ImmutableList.class);

  public abstract TypeInfo instanceClass();

  public abstract Method method();

  public abstract ImmutableList<Type> argTypes();

  /**
   * Returns an expression that constructs a new instance of {@link #instanceClass()} by calling
   * this constructor.
   */
  public Expression construct(Expression... args) {
    return construct(Arrays.asList(args));
  }

  /**
   * Returns an expression that constructs a new instance of {@link #instanceClass()} by calling
   * this constructor.
   */
  public Expression construct(Iterable<? extends Expression> args) {
    Expression.checkTypes(argTypes(), args);
    return new Expression(instanceClass().type(), Feature.NON_JAVA_NULLABLE) {
      @Override
      protected void doGen(CodeBuilder mv) {
        mv.newInstance(instanceClass().type());
        mv.dup();
        for (Expression arg : args) {
          arg.gen(mv);
        }
        mv.invokeConstructor(instanceClass().type(), method());
      }
    };
  }

  public Handle toHandle() {
    return new Handle(
        Opcodes.H_NEWINVOKESPECIAL,
        instanceClass().type().getInternalName(),
        "<init>",
        method().getDescriptor(),
        /* isInterface=*/ false);
  }
}
