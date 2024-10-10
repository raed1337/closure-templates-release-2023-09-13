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

package com.google.template.soy.jbcsrc;

import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.boxJavaPrimitive;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.isDefinitelyAssignableFrom;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.unboxJavaPrimitive;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.exprtree.AbstractLocalVarDefn;
import com.google.template.soy.exprtree.DataAccessNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.jbcsrc.ExpressionCompiler.BasicExpressionCompiler;
import com.google.template.soy.jbcsrc.TemplateVariableManager.SaveStrategy;
import com.google.template.soy.jbcsrc.TemplateVariableManager.Variable;
import com.google.template.soy.jbcsrc.internal.SoyClassWriter;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.JbcSrcPluginContext;
import com.google.template.soy.jbcsrc.restricted.SoyExpression;
import com.google.template.soy.jbcsrc.restricted.SoyRuntimeType;
import com.google.template.soy.jbcsrc.restricted.Statement;
import com.google.template.soy.jbcsrc.restricted.TypeInfo;
import com.google.template.soy.jbcsrc.shared.Names;
import com.google.template.soy.soytree.ConstNode;
import com.google.template.soy.soytree.PartialFileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.types.SoyType;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

/** Compiles byte code for {@link ConstNode}s. */
public final class ConstantsCompiler {

  static final TemplateAnalysis ALL_RESOLVED =
      new TemplateAnalysis() {
        @Override
        public boolean isResolved(VarRefNode ref) {
          // Only locals in list comprehension and other globals may possibly be referenced.
          return true;
        }

        @Override
        public boolean isResolved(DataAccessNode ref) {
          // Data access is not allowed in const context.
          throw new UnsupportedOperationException();
        }
      };

  private final ConstNode constant;
  private final FieldManager fields;
  private final SoyClassWriter writer;
  private final JavaSourceFunctionCompiler javaSourceFunctionCompiler;
  private final PartialFileSetMetadata fileSetMetadata;

  ConstantsCompiler(
      ConstNode constant,
      SoyClassWriter writer,
      FieldManager fields,
      JavaSourceFunctionCompiler javaSourceFunctionCompiler,
      PartialFileSetMetadata fileSetMetadata) {
    this.constant = constant;
    this.fields = fields;
    this.writer = writer;
    this.javaSourceFunctionCompiler = javaSourceFunctionCompiler;
    this.fileSetMetadata = fileSetMetadata;
  }

  static SoyRuntimeType getConstantRuntimeType(SoyType type) {
    return SoyRuntimeType.getUnboxedType(type).orElseGet(() -> SoyRuntimeType.getBoxedType(type));
  }

  static Method getConstantMethod(String symbol, SoyType type) {
    return new Method(
        symbol,
        getConstantRuntimeType(type).runtimeType(),
        new Type[] {BytecodeUtils.RENDER_CONTEXT_TYPE});
  }

  public void compile() {
    String javaClassName =
        Names.javaClassNameFromSoyNamespace(
            constant.getNearestAncestor(SoyFileNode.class).getNamespace());
    TypeInfo javaClass = TypeInfo.createClass(javaClassName);
    Method method = getConstantMethod(constant.getVar().name(), constant.getVar().type());
    Type methodType = method.getReturnType();

    Label start = new Label();
    Label end = new Label();
    TemplateVariableManager variableSet =
        new TemplateVariableManager(
            javaClass.type(),
            method.getArgumentTypes(),
            ImmutableList.of(StandardNames.RENDER_CONTEXT),
            start,
            end,
            /* isStatic= */ true);
    Expression renderContext = variableSet.getVariable(StandardNames.RENDER_CONTEXT);
    RenderContextExpression renderContextExpr = new RenderContextExpression(renderContext);
    TemplateParameterLookup variables = new ConstantVariables(variableSet, renderContextExpr);

    if (ExpressionCompiler.canCompileToConstant(constant, constant.getExpr())) {
      BasicExpressionCompiler constantCompiler =
          ExpressionCompiler.createConstantCompiler(
              constant,
              ALL_RESOLVED,
              new SimpleLocalVariableManager(
                  javaClass.type(),
                  method.getArgumentTypes(),
                  ImmutableList.of("renderContext"),
                  start,
                  end,
                  /* isStatic= */ true),
              javaSourceFunctionCompiler,
              fileSetMetadata);
      SoyExpression expression = constantCompiler.compile(constant.getExpr());
      Expression value =
          BytecodeUtils.isPrimitive(expression.resultType())
              ? expression
              : fields.addStaticField("const$" + constant.getVar().name(), expression).accessor();

      Preconditions.checkArgument(
          isDefinitelyAssignableFrom(expression.soyRuntimeType().runtimeType(), methodType));
      Statement.returnExpression(value)
          .labelStart(start)
          .labelEnd(end)
          .writeMethod(methodAccess(), method, writer);
    } else {
      BasicExpressionCompiler expressionCompiler =
          ExpressionCompiler.createBasicCompiler(
              constant,
              ALL_RESOLVED,
              variables,
              variableSet,
              javaSourceFunctionCompiler,
              fileSetMetadata);

      SoyExpression buildConstValue = expressionCompiler.compile(constant.getExpr());
      Preconditions.checkArgument(
          isDefinitelyAssignableFrom(buildConstValue.soyRuntimeType().runtimeType(), methodType));

      String constKey = javaClassName + "#" + constant.getVar().name();

      boolean constIsPrimitive = BytecodeUtils.isPrimitive(methodType);
      var constScope = variableSet.enterScope();
      Variable tmpVar =
          constScope.create("tmp", renderContextExpr.getConst(constKey), SaveStrategy.STORE);
      Statement storeLocal =
          tmpVar
              .local()
              .store(
                  constIsPrimitive
                      ? boxJavaPrimitive(methodType, buildConstValue)
                      : buildConstValue);
      Statement storeConst = renderContextExpr.storeConst(constKey, tmpVar.accessor());
      Expression returnValue =
          constIsPrimitive
              ? unboxJavaPrimitive(methodType, tmpVar.accessor())
              : tmpVar.accessor().checkedCast(method.getReturnType());

      // Implements a lazily initialized, memoized value. Memoization ensures that values appear
      // constant even if their initializer is not idempotent. The value is memoized into the
      // RenderContext, which is more or less request scoped. Request scoped storage (rather than
      // Java
      // static memory) is necessary because constants can be initialized via externs that depend on
      // request scoped data (via type="instance").
      var scopeExit = constScope.exitScope();
      new Statement() {
        @Override
        protected void doGen(CodeBuilder adapter) {
          /*
            public static T constant(RenderContext r) {
              Object tmp = r.getConst("key");
              if (tmp == null) {
                goto END;
              }
              tmp = ...;
              r.storeConst("key", tmp);
              END;
              return (T) tmp;
            }
          */
          adapter.mark(start);
          tmpVar.initializer().gen(adapter);
          tmpVar.accessor().gen(adapter);
          adapter.ifNonNull(end);
          storeLocal.gen(adapter);
          storeConst.gen(adapter);
          adapter.mark(end);
          returnValue.gen(adapter);
          adapter.returnValue();
          scopeExit.gen(adapter);
          variableSet.generateTableEntries(adapter);
        }
      }.writeMethod(methodAccess(), method, writer);
    }
  }

  private int methodAccess() {
    // Same issue as TemplateCompiler#methodAccess
    return (constant.isExported() ? Opcodes.ACC_PUBLIC : 0) | Opcodes.ACC_STATIC;
  }

  static final class ConstantVariables implements TemplateParameterLookup {
    private final TemplateVariableManager variableSet;
    private final RenderContextExpression renderContext;

    ConstantVariables(TemplateVariableManager variableSet, RenderContextExpression renderContext) {
      this.renderContext = renderContext;
      this.variableSet = variableSet;
    }

    UnsupportedOperationException unsupported() {
      return new UnsupportedOperationException(
          "This method isn't supported in constants compilation context");
    }

    @Override
    public Expression getParam(TemplateParam param) {
      throw unsupported();
    }

    @Override
    public Expression getParamsRecord() {
      throw unsupported();
    }

    @Override
    public Expression getIjRecord() {
      throw unsupported();
    }

    @Override
    public Expression getLocal(AbstractLocalVarDefn<?> local) {
      return variableSet.getVariable(local.name());
    }

    @Override
    public Expression getLocal(SyntheticVarName varName) {
      throw unsupported();
    }

    @Override
    public RenderContextExpression getRenderContext() {
      return renderContext;
    }

    @Override
    public JbcSrcPluginContext getPluginContext() {
      throw unsupported();
    }
  }
}
