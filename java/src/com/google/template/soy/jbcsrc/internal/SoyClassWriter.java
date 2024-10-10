
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

package com.google.template.soy.jbcsrc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.OBJECT;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.GeneratedMessage;
import com.google.template.soy.jbcsrc.restricted.Flags;
import com.google.template.soy.jbcsrc.restricted.TypeInfo;
import com.google.template.soy.jbcsrc.shared.Names;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassTooLargeException;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * A subclass of {@link ClassWriter} that allows us to specialize {@link
 * ClassWriter#getCommonSuperClass} for compiler generated types as well as set common defaults for
 * all classwriters used by {@code jbcsrc}.
 */
public final class SoyClassWriter extends ClassVisitor {
  /** Returns a new SoyClassWriter for writing a new class of the given type. */
  public static Builder builder(TypeInfo type) {
    return new Builder(type);
  }

  public static final class Builder {
    private final TypeInfo type;
    private int access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
    private TypeInfo baseClass = OBJECT;
    private List<String> interfaces = new ArrayList<>();
    private String fileName; // optional

    private Builder(TypeInfo type) {
      this.type = checkNotNull(type);
    }

    /**
     * Set the access permissions on the generated class. The default is package private and {@code
     * final}.
     *
     * @param access The access permissions, a bit mask composed from constants like {@link
     *     Opcodes#ACC_PUBLIC}
     */
    @CanIgnoreReturnValue
    public Builder setAccess(int access) {
      this.access = access;
      return this;
    }

    /** Sets the base class for this type. The default is {@code Object}. */
    @CanIgnoreReturnValue
    public Builder extending(TypeInfo baseClass) {
      this.baseClass = checkNotNull(baseClass);
      return this;
    }

    /** Adds an {@code interface} to the class. */
    @CanIgnoreReturnValue
    public Builder implementing(TypeInfo typeInfo) {
      interfaces.add(typeInfo.internalName());
      return this;
    }

    @CanIgnoreReturnValue
    public Builder sourceFileName(String fileName) {
      this.fileName = checkNotNull(fileName);
      return this;
    }

    public SoyClassWriter build() {
      return new SoyClassWriter(new Writer(), this);
    }
  }

  private final Writer writer;
  private final TypeInfo typeInfo;
  private int numFields;

  private SoyClassWriter(Writer writer, Builder builder) {
    super(
        writer.api(),
        Flags.DEBUG ? new CheckClassAdapter(writer, /* checkDataFlow=*/ false) : writer);
    this.writer = writer;
    this.typeInfo = builder.type;
    initVisit(builder);
  }

  private void initVisit(Builder builder) {
    super.visit(
        Opcodes.V11,
        builder.access,
        builder.type.internalName(),
        null /* not generic */,
        builder.baseClass.internalName(),
        builder.interfaces.toArray(new String[0]));
    if (builder.fileName != null) {
      super.visitSource(builder.fileName, null);
    }
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String desc, String signature, Object value) {
    numFields++;
    return super.visitField(access, name, desc, signature, value);
  }

  /** Returns the bytecode of the class that was built with this class writer. */
  public ClassData toClassData() {
    try {
      return ClassData.create(typeInfo, writer.toByteArray(), numFields);
    } catch (ClassTooLargeException e) {
      throw handleClassTooLargeException(e);
    } catch (MethodTooLargeException e) {
      throw handleMethodTooLargeException(e);
    }
  }

  private RuntimeException handleClassTooLargeException(ClassTooLargeException e) {
    return new RuntimeException(
        "Attempted to generate a method of class with too many constants: "
            + e.getConstantPoolCount()
            + " constants (max is 65536, delta is "
            + (e.getConstantPoolCount() - 65536)
            + "), numFields: "
            + numFields,
        e);
  }

  private RuntimeException handleMethodTooLargeException(MethodTooLargeException e) {
    return new RuntimeException(
        "Attempted to generate a method of size: "
            + e.getCodeSize()
            + " bytes (max is 65536), numFields: "
            + numFields,
        e);
  }

  private static final class Writer extends ClassWriter {
    private static final String PROTO_EXTENDABLE_BUILDER =
        Type.getInternalName(GeneratedMessage.ExtendableBuilder.class);

    Writer() {
      super(COMPUTE_FRAMES | COMPUTE_MAXS);
    }

    int api() {
      return api;
    }

    @Override
    protected String getCommonSuperClass(String left, String right) {
      if ("java/lang/Object".equals(left) || "java/lang/Object".equals(right)) {
        return "java/lang/Object";
      }
      boolean leftIsGenerated = left.startsWith(Names.INTERNAL_CLASS_PREFIX);
      boolean rightIsGenerated = right.startsWith(Names.INTERNAL_CLASS_PREFIX);
      if (!leftIsGenerated && !rightIsGenerated) {
        return handleCommonSuperClass(left, right);
      }
      return OBJECT.internalName();
    }

    private String handleCommonSuperClass(String left, String right) {
      if ((left.equals(PROTO_EXTENDABLE_BUILDER) && right.endsWith("$Builder"))
          || (right.equals(PROTO_EXTENDABLE_BUILDER) && left.endsWith("$Builder"))) {
        return PROTO_EXTENDABLE_BUILDER;
      }
      try {
        return super.getCommonSuperClass(left, right);
      } catch (RuntimeException re) {
        throw new RuntimeException(
            "unable to calculate common base class of: " + left + " and " + right, re);
      }
    }
  }
}
