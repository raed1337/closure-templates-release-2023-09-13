
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Comparator.comparing;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.LocalVariable;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.Statement;
import com.google.template.soy.jbcsrc.shared.SaveStateMetaFactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

/**
 * Manages logical template variables and their scopes as well as calculating how to generate
 * save/restore logic for template detaches.
 */
final class TemplateVariableManager implements LocalVariableManager {
  enum SaveStrategy {
    /** Means that the value of the variable should be recalculated rather than saved. */
    DERIVED,
    /** Means that the value of the variable should be saved . */
    STORE
  }

  abstract static class Scope implements LocalVariableManager.Scope {
    private Scope() {}

    abstract void createTrivial(String name, Expression expression);
    abstract Variable createSynthetic(SyntheticVarName name, Expression initializer, SaveStrategy strategy);
    abstract Variable create(String name, Expression initializer, SaveStrategy strategy);
  }

  @AutoValue
  abstract static class VarKey {
    enum Kind {
      USER_DEFINED,
      SYNTHETIC
    }

    static VarKey create(String proposedName) {
      return new AutoValue_TemplateVariableManager_VarKey(Kind.USER_DEFINED, proposedName);
    }

    static VarKey create(SyntheticVarName proposedName) {
      return new AutoValue_TemplateVariableManager_VarKey(Kind.SYNTHETIC, proposedName);
    }

    abstract Kind kind();
    abstract Object name();
  }

  private abstract static class AbstractVariable {
    abstract Expression accessor();
  }

  private static final class TrivialVariable extends AbstractVariable {
    final Expression accessor;

    TrivialVariable(Expression accessor) {
      this.accessor = accessor;
    }

    @Override
    Expression accessor() {
      return accessor;
    }
  }

  static final class Variable extends AbstractVariable {
    private final Expression initExpression;
    private final LocalVariable local;
    private final SaveStrategy strategy;
    private final Statement initializer;

    private Variable(Expression initExpression, LocalVariable local, SaveStrategy strategy) {
      this.initExpression = initExpression;
      this.local = adjustLocal(local, initExpression);
      this.initializer = this.local.initialize(initExpression);
      this.strategy = strategy;
    }

    private LocalVariable adjustLocal(LocalVariable local, Expression initExpression) {
      if (initExpression.isNonJavaNullable()) {
        return local.asNonJavaNullable();
      }
      if (initExpression.isNonSoyNullish()) {
        return local.asNonSoyNullish();
      }
      return local;
    }
    
    Statement initializer() {
      return initializer;
    }

    @Override
    Expression accessor() {
      return local();
    }

    LocalVariable local() {
      return local;
    }
  }

  private ScopeImpl activeScope;
  private final SimpleLocalVariableManager delegate;

  TemplateVariableManager(
      Type owner,
      Type[] methodArguments,
      ImmutableList<String> parameterNames,
      Label methodBegin,
      Label methodEnd,
      boolean isStatic) {
    this.delegate = new SimpleLocalVariableManager(owner, methodArguments, parameterNames, methodBegin, methodEnd, isStatic);
    activeScope = new ScopeImpl();
    delegate.allActiveVariables().forEach((key, value) -> activeScope.variablesByKey.put(VarKey.create(key), new TrivialVariable(value)));
  }

  public void updateParameterTypes(Type[] parameterTypes, List<String> parameterNames) {
    delegate.updateParameterTypes(parameterTypes, parameterNames);
  }

  @Override
  public Scope enterScope() {
    return new ScopeImpl();
  }

  private class ScopeImpl extends Scope {
    final ScopeImpl parent;
    final LocalVariableManager.Scope delegateScope = delegate.enterScope();
    final List<VarKey> activeVariables = new ArrayList<>();
    final Map<VarKey, AbstractVariable> variablesByKey = new LinkedHashMap<>();

    ScopeImpl() {
      this.parent = TemplateVariableManager.this.activeScope;
      TemplateVariableManager.this.activeScope = this;
    }

    @Override
    void createTrivial(String name, Expression expression) {
      putVariable(VarKey.create(name), new TrivialVariable(expression));
    }

    @Override
    Variable createSynthetic(SyntheticVarName varName, Expression initExpr, SaveStrategy strategy) {
      return doCreate("$" + varName.name(), initExpr, VarKey.create(varName), strategy);
    }

    @Override
    Variable create(String name, Expression initExpr, SaveStrategy strategy) {
      return doCreate(name, initExpr, VarKey.create(name), strategy);
    }

    @Override
    public LocalVariable createTemporary(String proposedName, Type type) {
      return delegateScope.createTemporary(proposedName, type);
    }

    @Override
    public LocalVariable createNamedLocal(String name, Type type) {
      LocalVariable var = delegateScope.createNamedLocal(name, type);
      putVariable(VarKey.create(name), new TrivialVariable(var));
      return var;
    }

    @Override
    public Statement exitScope() {
      for (VarKey key : activeVariables) {
        AbstractVariable var = variablesByKey.remove(key);
        if (var == null) {
          throw new IllegalStateException("no variable active for key: " + key);
        }
      }
      TemplateVariableManager.this.activeScope = parent;
      return delegateScope.exitScope();
    }

    Variable doCreate(String proposedName, Expression initExpr, VarKey key, SaveStrategy strategy) {
      Variable var = new Variable(initExpr, delegateScope.createTemporary(proposedName, initExpr.resultType()), strategy);
      putVariable(key, var);
      return var;
    }

    void putVariable(VarKey key, AbstractVariable var) {
      AbstractVariable old = variablesByKey.put(key, var);
      if (old != null) {
        throw new IllegalStateException("multiple variables active for key: " + key);
      }
      activeVariables.add(key);
    }

    Expression getVariable(VarKey key) {
      var variable = variablesByKey.get(key);
      if (variable == null) {
        if (parent != null) {
          return parent.getVariable(key);
        }
        throw new IllegalStateException("no variable: '" + key + "' is bound. " + variablesByKey.keySet() + " are in scope");
      }
      return variable.accessor();
    }

    Stream<AbstractVariable> allVariables() {
      var direct = variablesByKey.values().stream();
      if (parent != null) {
        return Streams.concat(parent.allVariables(), direct);
      }
      return direct;
    }
  }

  @Override
  public void generateTableEntries(CodeBuilder ga) {
    delegate.generateTableEntries(ga);
  }

  @Override
  public Expression getVariable(String name) {
    return getVariable(VarKey.create(name));
  }

  Expression getVariable(SyntheticVarName name) {
    return getVariable(VarKey.create(name));
  }

  private Expression getVariable(VarKey varKey) {
    return activeScope.getVariable(varKey);
  }

  @AutoValue
  abstract static class SaveRestoreState {
    abstract Statement save();
    abstract Optional<Function<LocalVariable, Statement>> restore();
  }

  void assertSaveRestoreStateIsEmpty() {
    checkState(activeScope.allVariables().noneMatch(v -> v instanceof Variable));
  }

  private static final Handle BOOTSTRAP_SAVE_HANDLE =
      MethodRef.create(SaveStateMetaFactory.class, "bootstrapSaveState", MethodHandles.Lookup.class, String.class, MethodType.class).asHandle();
  private static final Handle BOOTSTRAP_RESTORE_HANDLE =
      MethodRef.create(SaveStateMetaFactory.class, "bootstrapRestoreState", MethodHandles.Lookup.class, String.class, MethodType.class, MethodType.class, int.class).asHandle();

  private static Type simplifyType(Type type) {
    switch (type.getSort()) {
      case Type.OBJECT:
      case Type.ARRAY:
        return BytecodeUtils.OBJECT.type();
      case Type.BOOLEAN:
      case Type.CHAR:
      case Type.BYTE:
      case Type.SHORT:
      case Type.INT:
      case Type.FLOAT:
      case Type.LONG:
      case Type.DOUBLE:
        return type;
      default:
        throw new AssertionError("unsupported type: " + type);
    }
  }

  SaveRestoreState saveRestoreState(RenderContextExpression renderContextExpression, int stateNumber) {
    ImmutableList<Variable> restoresInOrder = activeScope.allVariables().filter(v -> !(v instanceof TrivialVariable)).map(v -> (Variable) v).collect(toImmutableList());

    ImmutableList<Variable> storesToPerform = prepareStoresToPerform(restoresInOrder);
    List<Type> methodTypeParams = prepareMethodTypeParams(storesToPerform);

    Type saveStateMethodType = Type.getMethodType(Type.VOID_TYPE, methodTypeParams.toArray(new Type[0]));
    Statement saveState = createSaveStateStatement(renderContextExpression, stateNumber, storesToPerform, saveStateMethodType);

    Optional<Function<LocalVariable, Statement>> restoreFromFrame = createRestoreFromFrame(restoresInOrder, storesToPerform, saveStateMethodType);
    ImmutableList<Statement> restoreDerivedVariables = createRestoreDerivedVariables(restoresInOrder);

    return new AutoValue_TemplateVariableManager_SaveRestoreState(saveState, createRestoreFunction(restoreFromFrame, restoreDerivedVariables));
  }

  private ImmutableList<Variable> prepareStoresToPerform(ImmutableList<Variable> restoresInOrder) {
    return restoresInOrder.stream()
        .filter(v -> v.strategy == SaveStrategy.STORE)
        .sorted(comparing(v -> v.accessor().resultType().getSort()))
        .collect(toImmutableList());
  }

  private List<Type> prepareMethodTypeParams(ImmutableList<Variable> storesToPerform) {
    List<Type> methodTypeParams = new ArrayList<>();
    methodTypeParams.add(BytecodeUtils.RENDER_CONTEXT_TYPE);
    methodTypeParams.add(Type.INT_TYPE);
    for (Variable variable : storesToPerform) {
      methodTypeParams.add(simplifyType(variable.accessor().resultType()));
    }
    return methodTypeParams;
  }

  private Statement createSaveStateStatement(RenderContextExpression renderContextExpression, int stateNumber, ImmutableList<Variable> storesToPerform, Type saveStateMethodType) {
    return new Statement() {
      @Override
      protected void doGen(CodeBuilder cb) {
        renderContextExpression.gen(cb);
        cb.pushInt(stateNumber);
        for (Variable var : storesToPerform) {
          var.accessor().gen(cb);
        }
        cb.visitInvokeDynamicInsn("save", saveStateMethodType.getDescriptor(), BOOTSTRAP_SAVE_HANDLE);
      }
    };
  }

  private Optional<Function<LocalVariable, Statement>> createRestoreFromFrame(ImmutableList<Variable> restoresInOrder, ImmutableList<Variable> storesToPerform, Type saveStateMethodType) {
    ImmutableMap<Variable, Integer> storeToSlotIndex = IntStream.range(0, storesToPerform.size())
        .boxed()
        .collect(toImmutableMap(storesToPerform::get, index -> index));
    
    ImmutableList<Variable> variablesToRestoreFromStorage = restoresInOrder.stream()
        .filter(v -> v.strategy == SaveStrategy.STORE)
        .collect(toImmutableList());

    return variablesToRestoreFromStorage.isEmpty() 
        ? Optional.empty() 
        : Optional.of(createRestoreStatement(storeToSlotIndex, variablesToRestoreFromStorage, saveStateMethodType));
  }

  private Function<LocalVariable, Statement> createRestoreStatement(ImmutableMap<Variable, Integer> storeToSlotIndex, ImmutableList<Variable> variablesToRestoreFromStorage, Type saveStateMethodType) {
    return (stackFrameVar) -> new Statement() {
      @Override
      protected void doGen(CodeBuilder cb) {
        stackFrameVar.loadUnchecked(cb);
        for (int i = 0; i < variablesToRestoreFromStorage.size(); i++) {
          if (i < variablesToRestoreFromStorage.size() - 1) {
            cb.dup();
          }
          Variable variableToRestore = variablesToRestoreFromStorage.get(i);
          Type varType = variableToRestore.accessor().resultType();
          cb.visitInvokeDynamicInsn("restoreLocal", Type.getMethodType(varType, BytecodeUtils.STACK_FRAME_TYPE).getDescriptor(), BOOTSTRAP_RESTORE_HANDLE, saveStateMethodType, storeToSlotIndex.get(variableToRestore));
          variableToRestore.local.storeUnchecked(cb);
        }
      }
    };
  }

  private ImmutableList<Statement> createRestoreDerivedVariables(ImmutableList<Variable> restoresInOrder) {
    return restoresInOrder.stream()
        .filter(var -> var.strategy == SaveStrategy.DERIVED)
        .map(v -> v.local.store(v.initExpression))
        .collect(toImmutableList());
  }

  private Optional<Function<LocalVariable, Statement>> createRestoreFunction(Optional<Function<LocalVariable, Statement>> restoreFromFrame, ImmutableList<Statement> restoreDerivedVariables) {
    return !restoreFromFrame.isPresent() && restoreDerivedVariables.isEmpty()
        ? Optional.empty()
        : Optional.of((LocalVariable variable) -> Statement.concat(restoreFromFrame.orElse((LocalVariable v) -> Statement.NULL_STATEMENT).apply(variable), Statement.concat(restoreDerivedVariables)));
  }
}
