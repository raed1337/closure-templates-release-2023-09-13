
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

package com.google.template.soy.jbcsrc;

import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.SOY_VALUE_PROVIDER_TYPE;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.SOY_VALUE_TYPE;

import com.google.common.base.Suppliers;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.Statement;
import java.util.function.Supplier;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

/** A helper for generating detach operations in soy expressions. */
interface ExpressionDetacher {
  interface Factory {
    ExpressionDetacher createExpressionDetacher(Label reattachPoint);
  }

  Expression resolveSoyValueProvider(Expression soyValueProvider);
  Expression waitForSoyValueProvider(Expression soyValueProvider);
  Expression resolveSoyValueProviderList(Expression soyValueProviderList);
  Expression resolveSoyValueProviderMap(Expression soyValueProviderMap);

  final class NullDetatcher implements ExpressionDetacher, Factory {
    static final NullDetatcher INSTANCE = new NullDetatcher();

    @Override
    public NullDetatcher createExpressionDetacher(Label reattachPoint) {
      return this;
    }

    @Override
    public Expression waitForSoyValueProvider(Expression soyValueProvider) {
      soyValueProvider.checkAssignableTo(BytecodeUtils.SOY_VALUE_PROVIDER_TYPE);
      return soyValueProvider;
    }

    @Override
    public Expression resolveSoyValueProvider(Expression soyValueProvider) {
      soyValueProvider.checkAssignableTo(BytecodeUtils.SOY_VALUE_PROVIDER_TYPE);
      return BytecodeUtils.isDefinitelyAssignableFrom(SOY_VALUE_TYPE, soyValueProvider.resultType())
          ? soyValueProvider
          : soyValueProvider.invoke(MethodRef.SOY_VALUE_PROVIDER_RESOLVE);
    }

    @Override
    public Expression resolveSoyValueProviderList(Expression soyValueProviderList) {
      soyValueProviderList.checkAssignableTo(BytecodeUtils.LIST_TYPE);
      return MethodRef.RUNTIME_CHECK_RESOLVED_LIST.invoke(soyValueProviderList);
    }

    @Override
    public Expression resolveSoyValueProviderMap(Expression soyValueProviderMap) {
      soyValueProviderMap.checkAssignableTo(BytecodeUtils.MAP_TYPE);
      return MethodRef.RUNTIME_CHECK_RESOLVED_MAP.invoke(soyValueProviderMap);
    }
  }

  final class BasicDetacher implements ExpressionDetacher {
    static final BasicDetacher INSTANCE = new BasicDetacher(() -> Statement.NULL_STATEMENT);
    private final Supplier<Statement> saveOperationSupplier;

    BasicDetacher(Supplier<Statement> saveOperationSupplier) {
      this.saveOperationSupplier = Suppliers.memoize(saveOperationSupplier::get);
    }

    @Override
    public Expression waitForSoyValueProvider(Expression soyValueProvider) {
      soyValueProvider.checkAssignableTo(BytecodeUtils.SOY_VALUE_PROVIDER_TYPE);
      Statement saveOperation = saveOperationSupplier.get();
      return createDetacherExpression(soyValueProvider, saveOperation, MethodRef.SOY_VALUE_PROVIDER_STATUS);
    }

    @Override
    public Expression resolveSoyValueProvider(Expression soyValueProvider) {
      soyValueProvider.checkAssignableTo(SOY_VALUE_PROVIDER_TYPE);
      return BytecodeUtils.isDefinitelyAssignableFrom(SOY_VALUE_TYPE, soyValueProvider.resultType())
          ? soyValueProvider
          : waitForSoyValueProvider(soyValueProvider).invoke(MethodRef.SOY_VALUE_PROVIDER_RESOLVE);
    }

    @Override
    public Expression resolveSoyValueProviderList(Expression soyValueProviderList) {
      soyValueProviderList.checkAssignableTo(BytecodeUtils.LIST_TYPE);
      Statement saveOperation = saveOperationSupplier.get();
      return createDetacherExpression(soyValueProviderList, saveOperation, MethodRef.RUNTIME_GET_LIST_STATUS);
    }

    @Override
    public Expression resolveSoyValueProviderMap(Expression soyValueProviderMap) {
      soyValueProviderMap.checkAssignableTo(BytecodeUtils.MAP_TYPE);
      Statement saveOperation = saveOperationSupplier.get();
      return createDetacherExpression(soyValueProviderMap, saveOperation, MethodRef.RUNTIME_GET_MAP_STATUS);
    }

    private Expression createDetacherExpression(Expression soyValueProvider, Statement saveOperation, MethodRef statusMethod) {
      return new Expression(soyValueProvider.resultType()) {
        @Override
        protected void doGen(CodeBuilder adapter) {
          soyValueProvider.gen(adapter);
          adapter.dup();
          statusMethod.invokeUnchecked(adapter);
          adapter.dup();
          MethodRef.RENDER_RESULT_IS_DONE.invokeUnchecked(adapter);
          Label end = new Label();
          adapter.ifZCmp(Opcodes.IFNE, end);
          saveOperation.gen(adapter);
          adapter.returnValue();
          adapter.mark(end);
          adapter.pop();
        }
      };
    }
  }
}
