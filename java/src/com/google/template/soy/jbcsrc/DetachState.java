
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.RENDER_RESULT_TYPE;
import static com.google.template.soy.jbcsrc.restricted.Statement.returnExpression;

import com.google.auto.value.AutoValue;
import com.google.template.soy.jbcsrc.TemplateVariableManager.SaveRestoreState;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.FieldRef;
import com.google.template.soy.jbcsrc.restricted.LocalVariable;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

/**
 * An object that manages generating the logic to save and restore execution state to enable
 * rendering to pause partway through a template.
 *
 * <p>First, definitions:
 *
 * <dl>
 *   <dt>Detach
 *   <dd>A 'detach' is the act of saving local state and returning control to our caller. Logically,
 *       we are saving a continuation.
 *   <dt>Detachable
 *   <dd>An operation that may conditionally detach.
 *   <dt>Reattach
 *   <dd>A 'reattach' is the act of restoring state and jumping back to the location just before the
 *       original 'detach'. We are calling back into our saved 'continuation'.
 *   <dt>Reattach Point
 *   <dd>The specific code location to which control should return.
 * </dl>
 *
 * <p>Each detachable method will look approximately like:
 *
 * <pre>{@code
 * int state;
 * Result detachable() {
 *   switch (state) {
 *     case 0: goto L0;  // no locals for state 0
 *     case 1:
 *       // restore all variables active at state 1
 *       goto L1;
 *     case 2:
 *       // restore locals active at state 2
 *       goto L2;
 *     ...
 *     default:
 *       throw new AssertionError();
 *   }
 *   L0:
 *   // start of the method
 *   ...
 * }
 * }</pre>
 *
 * <p>Then prior to each detachable point we will assign a label and generate code that looks like
 * this:
 *
 * <pre>{@code
 * LN:
 *   if (needs to detach) {
 *     save locals to fields
 *     state = N;
 *     return Result.detachCause(cause);
 *   }
 * }
 * }</pre>
 *
 * <p>This object is mutable and depends on the state of the {@link TemplateVariableManager} to
 * determine the current set of active variables. So it is important that uses of this object are
 * sequenced appropriately with operations that introduce (or remove) active variables.
 *
 * <p>Note, in the above examples, the caller is responsible for calculating when/why to detach but
 * this class is responsible for calculating the save/restore reattach logic.
 */
final class DetachState implements ExpressionDetacher.Factory {
  private final TemplateVariableManager variables;
  private final List<ReattachState> reattaches = new ArrayList<>();
  private final Supplier<RenderContextExpression> renderContextExpression;
  int disabledCount;

  DetachState(
      TemplateVariableManager variables,
      Supplier<RenderContextExpression> renderContextExpression) {
    this.variables = variables;
    this.renderContextExpression = renderContextExpression;
  }

  interface NoNewDetaches extends AutoCloseable {
    @Override
    void close();
  }

  NoNewDetaches expectNoNewDetaches() {
    disabledCount++;
    return () -> {
      disabledCount--;
      if (disabledCount < 0) {
        throw new AssertionError();
      }
    };
  }

  private void checkDetachesAllowed() {
    if (disabledCount > 0) {
      throw new IllegalStateException();
    }
  }

  /**
   * Returns a {@link ExpressionDetacher} that can be used to instrument an expression with detach
   * reattach logic.
   */
  @Override
  public ExpressionDetacher createExpressionDetacher(Label reattachPoint) {
    return new ExpressionDetacher.BasicDetacher(() -> addState(reattachPoint));
  }

  /**
   * Returns a Statement that will conditionally detach if the given {@link AdvisingAppendable} has
   * been {@link AdvisingAppendable#softLimitReached() output limited}.
   *
   * <p>This is only valid to call at the beginning of templates. It does not allocate a save/restore
   * block since there should be nothing to save or restore.
   */
  Statement detachLimited(AppendableExpression appendable) {
    checkDetachesAllowed();
    variables.assertSaveRestoreStateIsEmpty();
    if (!appendable.supportsSoftLimiting()) {
      return appendable.toStatement();
    }
    Expression isSoftLimited = appendable.softLimitReached();
    Statement returnLimited = returnExpression(MethodRef.RENDER_RESULT_LIMITED.invoke());
    return createDetachLimitedStatement(isSoftLimited, returnLimited);
  }

  private Statement createDetachLimitedStatement(Expression isSoftLimited, Statement returnLimited) {
    return new Statement() {
      @Override
      protected void doGen(CodeBuilder adapter) {
        Label continueLabel = new Label();
        isSoftLimited.gen(adapter);
        adapter.ifZCmp(Opcodes.IFEQ, continueLabel); // if !softLimited
        returnLimited.gen(adapter);
        adapter.mark(continueLabel);
      }
    };
  }

  /**
   * Evaluates the given render expression and asserts that it is complete.
   *
   * <p>This is a sanity check for the compiler that is theoretically optional. We could only
   * generate this code in debug mode and the rest of the time emit a single {@code pop}
   * instruction.
   */
  Statement assertFullyRenderered(Expression render) {
    return render.invokeVoid(MethodRef.RENDER_RESULT_ASSERT_DONE);
  }

  /**
   * Generate detach logic for render operations (like SoyValueProvider.renderAndResolve).
   *
   * <p>This is simple
   *
   * <pre>{@code
   * REATTACH_RENDER:
   * RenderResult initialResult = svp.renderAndResolve(appendable);
   * if (!initialResult.isDone()) {
   *   // save all fields
   *   state = REATTACH_RENDER;
   *   return initialResult;
   * }
   * }</pre>
   *
   * @param render an Expression that can generate code to call a render method that returns a
   *     RenderResult
   */
  Statement detachForRender(Expression render) {
    checkDetachesAllowed();
    checkArgument(render.resultType().equals(RENDER_RESULT_TYPE));
    Label reattachPoint = new Label();
    Statement saveState = addState(reattachPoint);
    return createDetachForRenderStatement(render, reattachPoint, saveState);
  }

  private Statement createDetachForRenderStatement(Expression render, Label reattachPoint, Statement saveState) {
    return new Statement() {
      @Override
      protected void doGen(CodeBuilder adapter) {
        adapter.mark(reattachPoint);
        render.gen(adapter); // Stack: RR
        adapter.dup(); // Stack: RR, RR
        MethodRef.RENDER_RESULT_IS_DONE.invokeUnchecked(adapter); // Stack: RR, Z
        Label end = new Label();
        adapter.ifZCmp(Opcodes.IFNE, end); // Stack: RR
        saveState.gen(adapter);
        adapter.returnValue();
        adapter.mark(end);
        adapter.pop(); // Stack:
      }
    };
  }

  /**
   * Returns a statement that generates the reattach jump table.
   *
   * <p>Note: This statement should be the <em>first</em> statement in any detachable method.
   */
  Statement generateReattachTable() {
    if (reattaches.isEmpty()) {
      return Statement.NULL_STATEMENT;
    }
    var stackFrameScope = variables.enterScope();
    LocalVariable stackFrameVar = stackFrameScope.createTemporary(StandardNames.STACK_FRAME, BytecodeUtils.STACK_FRAME_TYPE);
    Statement initStackFrame = stackFrameVar.initialize(renderContextExpression.get().popFrame());
    Expression readStateNumber = FieldRef.STACK_FRAME_STATE_NUMBER.accessor(stackFrameVar);
    
    Label unexpectedState = new Label();
    Label end = new Label();
    List<Label> caseLabels = new ArrayList<>();
    List<Statement> casesToGen = new ArrayList<>();
    
    caseLabels.add(end);
    for (ReattachState reattachState : reattaches) {
      generateReattachCases(reattachState, stackFrameVar, caseLabels, casesToGen);
    }
    
    casesToGen.add(Statement.throwExpression(MethodRef.RUNTIME_UNEXPECTED_STATE_ERROR.invoke(stackFrameVar)).labelStart(unexpectedState));
    var scopeExit = stackFrameScope.exitScope();
    
    return Statement.concat(
            initStackFrame,
            new Statement() {
              @Override
              protected void doGen(CodeBuilder adapter) {
                readStateNumber.gen(adapter);
                scopeExit.gen(adapter);
                adapter.visitTableSwitchInsn(0, reattaches.size(), unexpectedState, caseLabels.toArray(new Label[0]));
              }
            },
            Statement.concat(casesToGen))
        .labelEnd(end);
  }

  private void generateReattachCases(ReattachState reattachState, LocalVariable stackFrameVar, List<Label> caseLabels, List<Statement> casesToGen) {
    if (reattachState.restoreStatement().isPresent()) {
      Statement restoreState = reattachState.restoreStatement().get().apply(stackFrameVar);
      Label caseLabel = new Label();
      casesToGen.add(new Statement() {
        @Override
        protected void doGen(CodeBuilder cb) {
          cb.mark(caseLabel);
          restoreState.gen(cb);
          cb.goTo(reattachState.reattachPoint());
        }
      });
      caseLabels.add(caseLabel);
    } else {
      caseLabels.add(reattachState.reattachPoint());
    }
  }

  /** Add a new state item and return the statement that saves state. */
  private Statement addState(Label reattachPoint) {
    checkDetachesAllowed();
    int stateNumber = reattaches.size() + 1;
    SaveRestoreState saveRestoreState = variables.saveRestoreState(renderContextExpression.get(), stateNumber);
    ReattachState create = ReattachState.create(reattachPoint, saveRestoreState.restore());
    reattaches.add(create);
    return saveRestoreState.save();
  }

  @AutoValue
  abstract static class ReattachState {
    static ReattachState create(Label reattachPoint, Optional<Function<LocalVariable, Statement>> restore) {
      return new AutoValue_DetachState_ReattachState(reattachPoint, restore);
    }

    /** The label where control should resume when continuing. */
    abstract Label reattachPoint();

    /** The statement that restores the state of local variables so we can resume execution. */
    abstract Optional<Function<LocalVariable, Statement>> restoreStatement();
  }
}
