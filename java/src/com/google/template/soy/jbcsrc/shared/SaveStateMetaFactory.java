
/*
 * Copyright 2021 Google Inc.
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

package com.google.template.soy.jbcsrc.shared;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;
import static org.objectweb.asm.Opcodes.V1_8;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableList;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Bootstrap methods for handling our save/restore state logic.
 *
 * <p>The benefit of {@code invokedynamic} in this case is 3 fold:
 *
 * <ul>
 *   <li>Most save/restore states are never triggered, so we can leverage the laziness supplied by
 *       the bootstrap linkage to reduce the amount of statically generated code.
 *   <li>We can efficiently pack our stack frames into fields and avoid overheads related to boxing
 *       and lots of stack operations in RenderContext.
 *   <li>The actual logic for save/restore is reduced since much of the management of fields and
 *       assignments can be relegated to this class.
 * </ul>
 */
public final class SaveStateMetaFactory {

  /** A map to ensure we only attempt to define a class for each FrameKey once. */
  private static final ConcurrentMap<FrameKey, Class<? extends StackFrame>> frameCache =
      new ConcurrentHashMap<>();

  private static final Type STACK_FRAME_TYPE = Type.getType(StackFrame.class);
  private static final String GENERATED_CLASS_NAME_PREFIX =
      StackFrame.class.getPackage().getName() + ".StackFrame";
  private static final String GENERATED_CLASS_NAME_INTERNAL_PREFIX =
      GENERATED_CLASS_NAME_PREFIX.replace('.', '/');
  private static final MethodType STACK_FRAME_CTOR_TYPE =
      MethodType.methodType(void.class, int.class);
  private static final MethodHandle RENDER_CONTEXT_PUSH_FRAME;

  static {
    try {
      RENDER_CONTEXT_PUSH_FRAME =
          MethodHandles.publicLookup()
              .findVirtual(
                  RenderContext.class,
                  "pushFrame",
                  MethodType.methodType(void.class, StackFrame.class));
    } catch (ReflectiveOperationException nsme) {
      throw new LinkageError(nsme.getMessage(), nsme);
    }
  }

  @AutoValue
  abstract static class FrameKey {
    static FrameKey create(ImmutableList<Class<?>> fieldTypes) {
      return new AutoValue_SaveStateMetaFactory_FrameKey(fieldTypes);
    }

    abstract ImmutableList<Class<?>> fieldTypes();

    private static String charFromClass(Class<?> cls) {
      if (cls == int.class) return "I";
      if (cls == boolean.class) return "Z";
      if (cls == byte.class) return "B";
      if (cls == short.class) return "S";
      if (cls == char.class) return "C";
      if (cls == float.class) return "F";
      if (cls == double.class) return "D";
      if (cls == long.class) return "J";
      if (cls == Object.class) return "A";
      throw new AssertionError("unexpected class: " + cls);
    }

    @Memoized
    String symbol() {
      return fieldTypes().stream().map(FrameKey::charFromClass).collect(joining());
    }
  }

  private static Class<?> simplifyType(Class<?> paramType) {
    return paramType.isPrimitive() ? paramType : Object.class;
  }

  private static FrameKey frameKeyFromSaveMethodType(MethodType type) {
    ImmutableList<Class<?>> fieldTypes =
        type.parameterList().stream()
            .skip(2)
            .map(SaveStateMetaFactory::simplifyType)
            .collect(toImmutableList());
    return FrameKey.create(fieldTypes);
  }

  public static CallSite bootstrapSaveState(
      MethodHandles.Lookup lookup, String name, MethodType type) {
    FrameKey frameKey = frameKeyFromSaveMethodType(type);
    Class<? extends StackFrame> frameClass = getStackFrameClass(frameKey);
    MethodHandle ctorHandle = getConstructorHandle(lookup, frameClass, frameKey);
    MethodHandle saveStateHandle = createSaveStateHandle(ctorHandle);
    return new ConstantCallSite(saveStateHandle.asType(type));
  }

  private static MethodHandle getConstructorHandle(MethodHandles.Lookup lookup, Class<? extends StackFrame> frameClass, FrameKey frameKey) {
    try {
      return lookup.findConstructor(
          frameClass, STACK_FRAME_CTOR_TYPE.appendParameterTypes(frameKey.fieldTypes()));
    } catch (ReflectiveOperationException nsme) {
      throw new LinkageError(nsme.getMessage(), nsme);
    }
  }

  private static MethodHandle createSaveStateHandle(MethodHandle ctorHandle) {
    MethodHandle stackFrameConstructionHandle =
        ctorHandle.asType(ctorHandle.type().changeReturnType(StackFrame.class));
    return MethodHandles.collectArguments(RENDER_CONTEXT_PUSH_FRAME, 1, stackFrameConstructionHandle);
  }

  private static Class<? extends StackFrame> getStackFrameClass(FrameKey key) {
    return frameCache.computeIfAbsent(key, SaveStateMetaFactory::generateStackFrameClass);
  }

  private static Class<? extends StackFrame> generateStackFrameClass(FrameKey key) {
    if (key.fieldTypes().isEmpty()) {
      return StackFrame.class;
    }
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
    String className = GENERATED_CLASS_NAME_INTERNAL_PREFIX + key.symbol();
    cw.visit(
        V1_8,
        Opcodes.ACC_SUPER + Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SYNTHETIC,
        className,
        null,
        STACK_FRAME_TYPE.getInternalName(),
        null);
    List<Type> argTypes = defineFields(cw, key);
    createConstructor(cw, className, key, argTypes);
    cw.visitEnd();
    return defineGeneratedClass(cw);
  }

  private static List<Type> defineFields(ClassWriter cw, FrameKey key) {
    List<Type> argTypes = new ArrayList<>(key.fieldTypes().size());
    int counter = 0;
    for (Class<?> fieldType : key.fieldTypes()) {
      Type asmType = Type.getType(fieldType);
      argTypes.add(asmType);
      FieldVisitor fv =
          cw.visitField(
              Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
              "f_" + counter,
              asmType.getDescriptor(),
              null,
              null);
      fv.visitEnd();
      counter++;
    }
    return argTypes;
  }

  private static void createConstructor(ClassWriter cw, String className, FrameKey key, List<Type> argTypes) {
    Type generatedType = Type.getObjectType(className);
    MethodType ctorMethodType =
        MethodType.methodType(void.class, key.fieldTypes()).insertParameterTypes(0, int.class);
    MethodVisitor constructor =
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            ctorMethodType.toMethodDescriptorString(),
            null,
            null);
    constructor.visitCode();
    constructor.visitVarInsn(Opcodes.ALOAD, 0); 
    constructor.visitVarInsn(Opcodes.ILOAD, 1); 
    constructor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        STACK_FRAME_TYPE.getInternalName(),
        "<init>",
        STACK_FRAME_CTOR_TYPE.toMethodDescriptorString(),
        false);
    assignFields(constructor, argTypes);
    constructor.visitInsn(Opcodes.RETURN);
    constructor.visitMaxs(-1, -1);
    constructor.visitEnd();
  }

  private static void assignFields(MethodVisitor constructor, List<Type> argTypes) {
    int argPosition = 2; 
    for (int i = 0; i < argTypes.size(); i++) {
      Type argType = argTypes.get(i);
      constructor.visitVarInsn(Opcodes.ALOAD, 0); 
      constructor.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), argPosition);
      constructor.visitFieldInsn(
          Opcodes.PUTFIELD, argTypes.get(i).getInternalName(), "f_" + i, argType.getDescriptor());
      argPosition += argType.getSize();
    }
  }

  private static Class<? extends StackFrame> defineGeneratedClass(ClassWriter cw) {
    try {
      return MethodHandles.lookup().defineClass(cw.toByteArray()).asSubclass(StackFrame.class);
    } catch (IllegalAccessException iae) {
      throw new AssertionError(iae);
    }
  }

  public static CallSite bootstrapRestoreState(
      MethodHandles.Lookup lookup, String name, MethodType type, MethodType frameType, int slot) {

    FrameKey key = frameKeyFromSaveMethodType(frameType);
    Class<? extends StackFrame> implClass = getStackFrameClass(key);
    Field slotField = getField(implClass, slot);
    MethodHandle fieldGetter = getFieldGetter(lookup, slotField);
    return new ConstantCallSite(fieldGetter.asType(type));
  }

  private static Field getField(Class<? extends StackFrame> implClass, int slot) {
    try {
      return implClass.getField("f_" + slot);
    } catch (NoSuchFieldException nsfe) {
      throw new AssertionError(nsfe);
    }
  }

  private static MethodHandle getFieldGetter(MethodHandles.Lookup lookup, Field slotField) {
    try {
      return lookup.unreflectGetter(slotField);
    } catch (IllegalAccessException iae) {
      throw new AssertionError(iae);
    }
  }

  private SaveStateMetaFactory() {}
}
