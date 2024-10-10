
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
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.template.soy.data.internal.ParamStore;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.data.restricted.UndefinedData;
import com.google.template.soy.jbcsrc.restricted.Expression.Feature;
import com.google.template.soy.jbcsrc.restricted.Expression.Features;
import com.google.template.soy.jbcsrc.shared.StackFrame;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Representation of a field in a java class. */
@AutoValue
public abstract class FieldRef {

  public static final FieldRef NULL_DATA =
      staticFieldReference(NullData.class, "INSTANCE").asNonJavaNullable();
  public static final FieldRef UNDEFINED_DATA =
      staticFieldReference(UndefinedData.class, "INSTANCE").asNonJavaNullable();
  public static final FieldRef EMPTY_STRING_DATA =
      staticFieldReference(StringData.class, "EMPTY_STRING").asNonJavaNullable();
  public static final FieldRef EMPTY_PARAMS =
      staticFieldReference(ParamStore.class, "EMPTY_INSTANCE").asNonJavaNullable();

  public static final FieldRef STACK_FRAME_STATE_NUMBER =
      instanceFieldReference(StackFrame.class, "stateNumber");

  public static FieldRef create(TypeInfo owner, String name, Type type, int modifiers, boolean isNullable) {
    validateModifiers(modifiers);
    FieldRef ref = new AutoValue_FieldRef(owner, name, type);
    ref.accessFlags = modifiers;
    ref.isNullable = isNullable;
    return ref;
  }

  public static FieldRef create(TypeInfo owner, String name, Type fieldType, int modifiers) {
    return create(owner, name, fieldType, modifiers, !BytecodeUtils.isPrimitive(fieldType));
  }

  public static FieldRef instanceFieldReference(Class<?> owner, String name) {
    Field declaredField = getDeclaredField(owner, name);
    validateInstanceField(declaredField);
    return create(
        TypeInfo.create(owner),
        declaredField.getName(),
        Type.getType(declaredField.getType()),
        declaredField.getModifiers() & Modifier.fieldModifiers(),
        !declaredField.getType().isPrimitive());
  }

  public static FieldRef staticFieldReference(Class<?> owner, String name) {
    Field declaredField = getDeclaredField(owner, name);
    return staticFieldReference(declaredField);
  }

  public static FieldRef staticFieldReference(Field field) {
    validateStaticField(field);
    return create(
        TypeInfo.create(field.getDeclaringClass()),
        field.getName(),
        Type.getType(field.getType()),
        field.getModifiers() & Modifier.fieldModifiers(),
        /* isNullable= */ false);
  }

  public static <T extends Enum<T>> FieldRef enumReference(T enumInstance) {
    return staticFieldReference(enumInstance.getDeclaringClass(), enumInstance.name());
  }

  public static FieldRef createPublicStaticField(TypeInfo owner, String name, Type type) {
    return create(
        owner,
        name,
        type,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
        !BytecodeUtils.isPrimitive(type));
  }

  /** The type that owns this field. */
  public abstract TypeInfo owner();
  
  public abstract String name();

  public abstract Type type();

  private int accessFlags;
  private boolean isNullable;

  public final boolean isStatic() {
    return (accessFlags & Opcodes.ACC_STATIC) != 0;
  }

  public final boolean isFinal() {
    return (accessFlags & Opcodes.ACC_FINAL) != 0;
  }

  private static final int VISIBILITY_MASK =
      Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;

  @CanIgnoreReturnValue
  public final FieldRef setVisibility(int visibility) {
    checkArgument(visibility % VISIBILITY_MASK == visibility);
    accessFlags = (accessFlags & ~VISIBILITY_MASK) | visibility;
    return this;
  }

  /** Defines the given field as member of the class. */
  public void defineField(ClassVisitor cv) {
    cv.visitField(
        accessFlags,
        name(),
        type().getDescriptor(),
        null /* no generic signature */,
        null /* no initializer */);
  }

  @CanIgnoreReturnValue
  public FieldRef asNonJavaNullable() {
    isNullable = false;
    return this;
  }

  /** Returns an accessor that accesses this field on the given owner. */
  public Expression accessor(Expression owner) {
    validateAccessor(owner);
    Features features = createFeatures(owner);
    return new Expression(type(), features) {
      @Override
      protected void doGen(CodeBuilder mv) {
        owner.gen(mv);
        mv.getField(owner().type(), FieldRef.this.name(), resultType());
      }
    };
  }

  /** Returns an expression that accesses this static field. */
  public Expression accessor() {
    checkState(isStatic());
    Features features = Features.of(Feature.CHEAP);
    if (!isNullable) {
      features = features.plus(Feature.NON_JAVA_NULLABLE);
    }
    return new Expression(type(), features) {
      @Override
      protected void doGen(CodeBuilder mv) {
        accessStaticUnchecked(mv);
      }
    };
  }

  /** Accesses a static field. */
  void accessStaticUnchecked(CodeBuilder mv) {
    checkState(isStatic());
    mv.getStatic(owner().type(), FieldRef.this.name(), type());
  }

  /**
   * Returns a {@link Statement} that stores the {@code value} in this field on the given {@code
   * instance}.
   *
   * @throws IllegalStateException if this is a static field
   */
  public Statement putInstanceField(Expression instance, Expression value) {
    validatePutInstanceField(instance, value);
    return new Statement() {
      @Override
      protected void doGen(CodeBuilder adapter) {
        instance.gen(adapter);
        value.gen(adapter);
        putUnchecked(adapter);
      }
    };
  }

  /**
   * Returns a {@link Statement} that stores the {@code value} in this field on the given {@code
   * instance}.
   *
   * @throws IllegalStateException if this is a static field
   */
  public Statement putStaticField(Expression value) {
    validatePutStaticField(value);
    return new Statement() {
      @Override
      protected void doGen(CodeBuilder adapter) {
        value.gen(adapter);
        adapter.putStatic(owner().type(), name(), type());
      }
    };
  }

  /**
   * Adds code to place the top item of the stack into this field.
   *
   * @throws IllegalStateException if this is a static field
   */
  public void putUnchecked(CodeBuilder adapter) {
    checkState(!isStatic(), "This field is static!");
    adapter.putField(owner().type(), name(), type());
  }

  private static void validateModifiers(int modifiers) {
    if ((Modifier.fieldModifiers() & modifiers) != modifiers) {
      throw new IllegalArgumentException(
          "invalid modifiers, expected: "
              + Modifier.toString(Modifier.fieldModifiers())
              + " ("
              + Modifier.fieldModifiers()
              + ")"
              + " got: "
              + Modifier.toString(modifiers)
              + " ("
              + modifiers
              + ")");
    }
  }

  private static Field getDeclaredField(Class<?> owner, String name) {
    try {
      return owner.getDeclaredField(name);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void validateInstanceField(Field declaredField) {
    int modifiers = declaredField.getModifiers() & Modifier.fieldModifiers();
    if (Modifier.isStatic(modifiers)) {
      throw new IllegalStateException("Field: " + declaredField + " is static");
    }
  }

  private static void validateStaticField(Field field) {
    if (!Modifier.isStatic(field.getModifiers())) {
      throw new IllegalStateException("Field: " + field + " is not static");
    }
  }

  private void validateAccessor(Expression owner) {
    checkState(!isStatic());
    checkArgument(
        owner.resultType().equals(this.owner().type()),
        "Unexpected type: %s expected %s",
        owner.resultType(),
        owner().type());
  }

  private Features createFeatures(Expression owner) {
    Features features = Features.of();
    if (owner.isCheap()) {
      features = features.plus(Feature.CHEAP);
    }
    if (!isNullable) {
      features = features.plus(Feature.NON_JAVA_NULLABLE);
    }
    return features;
  }

  private void validatePutInstanceField(Expression instance, Expression value) {
    checkState(!isStatic(), "This field is static!");
    instance.checkAssignableTo(owner().type());
    value.checkAssignableTo(type());
  }

  private void validatePutStaticField(Expression value) {
    checkState(isStatic(), "This field is not static!");
    value.checkAssignableTo(type());
  }
}
