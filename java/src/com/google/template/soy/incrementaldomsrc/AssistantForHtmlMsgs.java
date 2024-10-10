
/*
 * Copyright 2016 Google Inc.
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
package com.google.template.soy.incrementaldomsrc;

import static com.google.template.soy.incrementaldomsrc.IncrementalDomRuntime.INCREMENTAL_DOM_TEXT;
import static com.google.template.soy.jssrc.dsl.Statements.forOf;
import static com.google.template.soy.jssrc.internal.JsRuntime.GOOG_STRING_UNESCAPE_ENTITIES;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.jssrc.SoyJsSrcOptions;
import com.google.template.soy.jssrc.dsl.DoWhile;
import com.google.template.soy.jssrc.dsl.Expression;
import com.google.template.soy.jssrc.dsl.Expressions;
import com.google.template.soy.jssrc.dsl.Statement;
import com.google.template.soy.jssrc.dsl.Statements;
import com.google.template.soy.jssrc.dsl.SwitchBuilder;
import com.google.template.soy.jssrc.dsl.VariableDeclaration;
import com.google.template.soy.jssrc.internal.GenCallCodeUtils;
import com.google.template.soy.jssrc.internal.GenJsCodeVisitorAssistantForMsgs;
import com.google.template.soy.jssrc.internal.GenJsExprsVisitor;
import com.google.template.soy.jssrc.internal.IsComputableAsJsExprsVisitor;
import com.google.template.soy.jssrc.internal.OutputVarHandler;
import com.google.template.soy.jssrc.internal.TemplateAliases;
import com.google.template.soy.jssrc.internal.TranslationContext;
import com.google.template.soy.soytree.HtmlContext;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.VeLogNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates <code>{msg}</code> commands in HTML context into idom instructions.
 *
 * <p>This class is not reusable.
 *
 * <p>This will pass all interpolated values as special placeholder strings. It will then extract
 * these placeholders from the translated message and execute the idom commands instead.
 */
final class AssistantForHtmlMsgs extends GenJsCodeVisitorAssistantForMsgs {

  private final Map<String, MsgPlaceholderNode> placeholderNames = new LinkedHashMap<>();
  private static final String PLACEHOLDER_WRAPPER = "\u0001";
  private static final String PLACEHOLDER_REGEX = "/\\x01\\d+\\x01/g";

  private final String staticDecl;
  private final GenIncrementalDomTemplateBodyVisitor idomTemplateBodyVisitor;

  AssistantForHtmlMsgs(
      GenIncrementalDomTemplateBodyVisitor idomTemplateBodyVisitor,
      SoyJsSrcOptions jsSrcOptions,
      GenCallCodeUtils genCallCodeUtils,
      IsComputableAsJsExprsVisitor isComputableAsJsExprsVisitor,
      TemplateAliases functionAliases,
      GenJsExprsVisitor genJsExprsVisitor,
      TranslationContext translationContext,
      ErrorReporter errorReporter,
      String staticDecl,
      OutputVarHandler outputVars) {
    super(
        idomTemplateBodyVisitor,
        jsSrcOptions,
        genCallCodeUtils,
        isComputableAsJsExprsVisitor,
        functionAliases,
        genJsExprsVisitor,
        translationContext,
        errorReporter,
        outputVars);
    this.staticDecl = staticDecl;
    this.idomTemplateBodyVisitor = idomTemplateBodyVisitor;
  }

  @Override
  public Expression generateMsgGroupVariable(MsgFallbackGroupNode node) {
    throw new IllegalStateException(
        "This class should only be used for via the new idom entry-point.");
  }

  Statement generateMsgGroupCode(MsgFallbackGroupNode node) {
    Preconditions.checkState(placeholderNames.isEmpty(), "This class is not reusable.");
    Preconditions.checkArgument(
        node.getHtmlContext() == HtmlContext.HTML_PCDATA,
        "AssistantForHtmlMsgs is only for HTML {msg}s.");

    Expression translationVar = super.generateMsgGroupVariable(node);
    if (placeholderNames.isEmpty()) {
      return INCREMENTAL_DOM_TEXT.call(GOOG_STRING_UNESCAPE_ENTITIES.call(translationVar)).asStatement();
    }

    if (!translationVar.isCheap()) {
      translationVar = createFreshVariable(translationVar);
    }

    ImmutableList.Builder<Statement> body = ImmutableList.builder();
    String itemId = "i" + node.getId();
    Expression item = Expressions.id(itemId);

    body.add(createTextOutputStatement(item));
    body.add(createSwitchStatement(node, item));

    Statement loop = forOf(itemId, Expressions.id(staticDecl).bracketAccess(translationVar), Statements.of(body.build()));
    return Statements.of(staticsInitializer(node, translationVar), loop);
  }

  private Expression createFreshVariable(Expression translationVar) {
    return translationContext
        .codeGenerator()
        .declarationBuilder()
        .setRhs(translationVar)
        .build()
        .ref();
  }

  private Statement createTextOutputStatement(Expression item) {
    return item.bracketAccess(Expressions.number(0))
        .and(INCREMENTAL_DOM_TEXT.call(item.bracketAccess(Expressions.number(0))),
            translationContext.codeGenerator())
        .asStatement();
  }

  private Statement createSwitchStatement(MsgFallbackGroupNode node, Expression item) {
    SwitchBuilder switchBuilder = Statements.switchValue(item.bracketAccess(Expressions.number(1)));
    for (Map.Entry<String, MsgPlaceholderNode> ph : placeholderNames.entrySet()) {
      switchBuilder.addCase(Expressions.stringLiteral(ph.getKey()), 
          idomTemplateBodyVisitor.addStaticsContent(() -> idomTemplateBodyVisitor.visit(ph.getValue()), true));
    }
    return switchBuilder.build();
  }

  private Statement staticsInitializer(MsgFallbackGroupNode node, Expression translationVar) {
    VariableDeclaration regexVar = createVariable("partRe_", node.getId(), Expressions.regexLiteral(PLACEHOLDER_REGEX));
    VariableDeclaration matchVar = createMutableVariable("match_", node.getId());
    VariableDeclaration lastIndexVar = createMutableVariable("lastIndex_", node.getId(), Expressions.number(0));
    VariableDeclaration counter = createMutableVariable("counter_", node.getId(), Expressions.number(0));

    List<Statement> doBody = createDoBody(translationVar, regexVar, matchVar, lastIndexVar, counter);

    Statement statement = Statements.of(
            Expressions.id(staticDecl).bracketAccess(translationVar).assign(Expressions.arrayLiteral(ImmutableList.of())).asStatement(),
            Statements.of(translationVar.allInitialStatementsInTopScope()),
            regexVar,
            lastIndexVar,
            counter,
            matchVar,
            DoWhile.builder().setCondition(matchVar.ref()).setBody(Statements.of(doBody)).build());

    return Statements.ifStatement(
            Expressions.not(Expressions.id(staticDecl).bracketAccess(translationVar)), statement).build();
  }

  private VariableDeclaration createVariable(String prefix, int id, Expression rhs) {
    return VariableDeclaration.builder(prefix + id).setRhs(rhs).build();
  }

  private VariableDeclaration createMutableVariable(String prefix, int id) {
    return VariableDeclaration.builder(prefix + id).setMutable().build();
  }

  private VariableDeclaration createMutableVariable(String prefix, int id, Expression rhs) {
    return VariableDeclaration.builder(prefix + id).setMutable().setRhs(rhs).build();
  }

  private List<Statement> createDoBody(Expression translationVar, VariableDeclaration regexVar,
                                        VariableDeclaration matchVar, VariableDeclaration lastIndexVar, 
                                        VariableDeclaration counter) {
    List<Statement> doBody = new ArrayList<>();

    doBody.add(matchVar.ref().assign(regexVar.ref().dotAccess("exec").call(translationVar).or(Expressions.id("undefined"), translationContext.codeGenerator())).asStatement());

    Expression endIndex = matchVar.ref().and(matchVar.ref().dotAccess("index"), translationContext.codeGenerator());
    Expression unescape = GOOG_STRING_UNESCAPE_ENTITIES.call(translationVar.dotAccess("substring").call(lastIndexVar.ref(), endIndex));

    doBody.add(Expressions.id(staticDecl).bracketAccess(translationVar).bracketAccess(counter.ref()).assign(
            Expressions.arrayLiteral(ImmutableList.of(unescape, matchVar.ref().and(matchVar.ref().bracketAccess(Expressions.number(0)), translationContext.codeGenerator())))).asStatement());

    doBody.add(counter.ref().assign(counter.ref().plus(Expressions.number(1))).asStatement());
    doBody.add(lastIndexVar.ref().assign(regexVar.ref().dotAccess("lastIndex")).asStatement());

    return doBody;
  }

  @Override
  protected Expression genGoogMsgPlaceholder(MsgPlaceholderNode msgPhNode) {
    String name = PLACEHOLDER_WRAPPER + placeholderNames.size() + PLACEHOLDER_WRAPPER;
    placeholderNames.put(name, msgPhNode);
    return Expressions.stringLiteral(name);
  }
}
