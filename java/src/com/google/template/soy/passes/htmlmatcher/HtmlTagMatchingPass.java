
package com.google.template.soy.passes.htmlmatcher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode.EdgeKind;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.HtmlTagNode.TagExistence;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.TagName;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import javax.annotation.Nullable;

public final class HtmlTagMatchingPass {
  private static final SoyErrorKind INVALID_CLOSE_TAG =
      SoyErrorKind.of("''{0}'' tag is a void element and must not specify a close tag.");
  private static final SoyErrorKind INVALID_SELF_CLOSING_TAG =
      SoyErrorKind.of("''{0}'' tag is not allowed to be self-closing.");
  private static final String UNEXPECTED_CLOSE_TAG = "Unexpected HTML close tag.";
  private static final String UNEXPECTED_CLOSE_TAG_KNOWN =
      "Unexpected HTML close tag. Expected to match the ''<{0}>'' at {1}.";
  private static final String BLOCK_QUALIFIER = " Tags within a %s must be internally balanced.";
  private static final String UNEXPECTED_OPEN_TAG_ALWAYS =
      "This HTML open tag is never matched with a close tag.";
  private static final String UNEXPECTED_OPEN_TAG_SOMETIMES =
      "This HTML open tag does not consistently match with a close tag.";
  private static final String EXPECTED_TAG_NAME = "Expected an html tag name.";
  private static final Optional<HtmlTagNode> INVALID_NODE = Optional.empty();

  private final ErrorReporter errorReporter;
  private final IdGenerator idGenerator;
  private final boolean inCondition;
  private final int foreignContentTagDepth;
  @Nullable private final String parentBlockType;

  private final SetMultimap<HtmlTagNode, Optional<HtmlTagNode>> annotationMap =
      LinkedHashMultimap.create();
  private final ExprEquivalence exprEquivalence = new ExprEquivalence();

  public HtmlTagMatchingPass(
      ErrorReporter errorReporter,
      IdGenerator idGenerator,
      boolean inCondition,
      int foreignContentTagDepth,
      String parentBlockType) {
    this.foreignContentTagDepth = foreignContentTagDepth;
    this.parentBlockType = parentBlockType;
    this.errorReporter = errorReporter;
    this.idGenerator = idGenerator;
    this.inCondition = inCondition;
  }

  private SoyErrorKind makeSoyErrorKind(String soyError) {
    return SoyErrorKind.of(
        soyError
            + (parentBlockType != null ? String.format(BLOCK_QUALIFIER, parentBlockType) : ""));
  }

  static class HtmlStack {
    final HtmlOpenTagNode tagNode;
    final int foreignContentTagDepth;
    final HtmlStack prev;

    HtmlStack(HtmlOpenTagNode tagNode, int foreignContentTagDepth, HtmlStack prev) {
      this.tagNode = tagNode;
      this.foreignContentTagDepth = foreignContentTagDepth;
      this.prev = prev;
    }

    HtmlStack push(HtmlOpenTagNode tagNode, int foreignContentTagDepth) {
      return new HtmlStack(tagNode, foreignContentTagDepth, this);
    }

    HtmlStack pop() {
      return prev;
    }

    boolean isEmpty() {
      return tagNode == null;
    }

    @Override
    public String toString() {
      return prev == null ? "[START]" : prev + "->" + tagNode.getTagName();
    }
  }

  public void run(HtmlMatcherGraph htmlMatcherGraph) {
    if (!htmlMatcherGraph.getRootNode().isPresent()) {
      return;
    }
    visit(htmlMatcherGraph.getRootNode().get());
    checkOpenTags();
    if (errorReporter.hasErrors() && inCondition) {
      return;
    }
    annotateTagPairs();
  }

  private void checkOpenTags() {
    for (HtmlTagNode tag : annotationMap.keySet()) {
      if (tag instanceof HtmlOpenTagNode) {
        HtmlOpenTagNode openTag = (HtmlOpenTagNode) tag;
        if (!annotationMap.containsEntry(openTag, INVALID_NODE)) {
          continue;
        }
        reportOpenTagErrors(openTag);
      }
    }
  }

  private void reportOpenTagErrors(HtmlOpenTagNode openTag) {
    if (annotationMap.get(openTag).size() == 1) {
      if (!openTag.getTagName().isExcludedOptionalTag()) {
        errorReporter.report(openTag.getSourceLocation(), makeSoyErrorKind(UNEXPECTED_OPEN_TAG_ALWAYS));
      }
    } else {
      errorReporter.report(openTag.getSourceLocation(), makeSoyErrorKind(UNEXPECTED_OPEN_TAG_SOMETIMES));
    }
  }

  private void annotateTagPairs() {
    for (HtmlTagNode openTag : annotationMap.keySet()) {
      for (Optional<HtmlTagNode> closeTag : annotationMap.get(openTag)) {
        if (closeTag.isPresent()) {
          openTag.addTagPair(closeTag.get());
          closeTag.get().addTagPair(openTag);
        }
      }
    }
  }

  private void injectCloseTag(
      HtmlOpenTagNode optionalOpenTag, HtmlTagNode destinationTag, IdGenerator idGenerator) {
    StandaloneNode openTagCopy = optionalOpenTag.getTagName().getNode().copy(new CopyState());
    HtmlCloseTagNode syntheticClose =
        new HtmlCloseTagNode(
            idGenerator.genId(),
            openTagCopy,
            optionalOpenTag.getSourceLocation(),
            TagExistence.SYNTHETIC);
    if (destinationTag == null) {
      optionalOpenTag.getParent().addChild(optionalOpenTag.getParent().numChildren(), syntheticClose);
    } else {
      ParentSoyNode<StandaloneNode> openTagParent = destinationTag.getParent();
      int i = openTagParent.getChildIndex(destinationTag);
      openTagParent.addChild(i, syntheticClose);
    }
    annotationMap.put(optionalOpenTag, Optional.of(syntheticClose));
    annotationMap.put(syntheticClose, Optional.of(optionalOpenTag));
  }

  @FunctionalInterface
  interface QueuedTask {
    List<QueuedTask> run();
  }

  private List<QueuedTask> visit(
      HtmlMatcherTagNode tagNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    HtmlTagNode tag = (HtmlTagNode) tagNode.getSoyNode().get();
    TagName openTagName = tag.getTagName();
    HtmlStack prev = stack;
    switch (tagNode.getTagKind()) {
      case VOID_TAG:
        handleVoidTag(openTagName, (HtmlOpenTagNode) tag, prev);
        break;
      case OPEN_TAG:
        prev = handleOpenTag((HtmlOpenTagNode) tag, prev);
        break;
      case CLOSE_TAG:
        handleCloseTag((HtmlCloseTagNode) tag, prev);
        break;
    }
    Optional<HtmlMatcherGraphNode> nextNode = tagNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    return ImmutableList.of(visit(nextNode, exprValueMap, prev));
  }

  private void handleVoidTag(TagName openTagName, HtmlOpenTagNode voidTag, HtmlStack stack) {
    if (stack.foreignContentTagDepth == 0
        && !openTagName.isDefinitelyVoid()
        && voidTag.isSelfClosing()
        && openTagName.isStatic()) {
      errorReporter.report(voidTag.getSourceLocation(), INVALID_SELF_CLOSING_TAG, openTagName.getStaticTagName());
    }
  }

  private HtmlStack handleOpenTag(HtmlOpenTagNode openTag, HtmlStack stack) {
    HtmlStack prev = stack;
    if (!prev.isEmpty()) {
      HtmlOpenTagNode optionalTag = stack.tagNode;
      if (optionalTag.getTagName().isDefinitelyOptional()) {
        if (TagName.checkOpenTagClosesOptional(openTag.getTagName(), optionalTag.getTagName())) {
          injectCloseTag(optionalTag, openTag, idGenerator);
          prev = prev.pop();
        }
      }
    }
    return prev.push(openTag, stack.foreignContentTagDepth + (openTag.getTagName().isForeignContent() ? 1 : 0));
  }

  private void handleCloseTag(HtmlCloseTagNode closeTag, HtmlStack stack) {
    if (closeTag.getTagName().isDefinitelyVoid()) {
      errorReporter.report(closeTag.getTagName().getTagLocation(), INVALID_CLOSE_TAG, closeTag.getTagName().getStaticTagName());
      return;
    }
    if (stack.isEmpty() && !closeTag.getTagName().isExcludedOptionalTag()) {
      errorReporter.report(closeTag.getSourceLocation(), makeSoyErrorKind(UNEXPECTED_CLOSE_TAG));
      return;
    }
    HtmlStack prev = stack;
    while (!prev.isEmpty()) {
      HtmlOpenTagNode nextOpenTag = prev.tagNode;
      if (nextOpenTag.getTagName().isStatic() && closeTag.getTagName().isWildCard()) {
        errorReporter.report(closeTag.getTagName().getTagLocation(), makeSoyErrorKind(EXPECTED_TAG_NAME));
      }
      if (nextOpenTag.getTagName().equals(closeTag.getTagName())
          || (!nextOpenTag.getTagName().isStatic() && closeTag.getTagName().isWildCard())) {
        annotationMap.put(nextOpenTag, Optional.of(closeTag));
        annotationMap.put(closeTag, Optional.of(nextOpenTag));
        prev = prev.pop();
        break;
      } else if (nextOpenTag.getTagName().isDefinitelyOptional()
          && TagName.checkCloseTagClosesOptional(closeTag.getTagName(), nextOpenTag.getTagName())) {
        injectCloseTag(nextOpenTag, closeTag, idGenerator);
        prev = prev.pop();
      } else {
        annotationMap.put(nextOpenTag, INVALID_NODE);
        if (!closeTag.getTagName().isExcludedOptionalTag()) {
          errorReporter.report(closeTag.getSourceLocation(),
              makeSoyErrorKind(UNEXPECTED_CLOSE_TAG_KNOWN),
              nextOpenTag.getTagName(),
              nextOpenTag.getSourceLocation());
        }
        prev = prev.pop();
      }
    }
  }

  private List<QueuedTask> visit(
      HtmlMatcherBlockNode blockNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    if (blockNode.getGraph().getRootNode().isPresent()) {
      new HtmlTagMatchingPass(
              errorReporter,
              idGenerator,
              false,
              stack.foreignContentTagDepth,
              blockNode.getParentBlockType())
          .run(blockNode.getGraph());
    }
    Optional<HtmlMatcherGraphNode> nextNode = blockNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    return ImmutableList.of(visit(nextNode, exprValueMap, stack));
  }

  private List<QueuedTask> visit(
      HtmlMatcherConditionNode condNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    ExprEquivalence.Wrapper condition = exprEquivalence.wrap(condNode.getExpression());
    Boolean originalState = exprValueMap.getOrDefault(condition, null);
    Optional<HtmlMatcherGraphNode> nextNode = condNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    Optional<HtmlMatcherGraphNode> nextAltNode = condNode.getNodeForEdgeKind(EdgeKind.FALSE_EDGE);
    ImmutableList.Builder<QueuedTask> tasks = ImmutableList.builder();
    if (!condNode.isInternallyBalanced(stack.foreignContentTagDepth, idGenerator)
        && nextNode.isPresent()
        && !Boolean.FALSE.equals(originalState)) {
      Map<ExprEquivalence.Wrapper, Boolean> lMap = new HashMap<>(exprValueMap);
      lMap.put(condition, true);
      tasks.add(visit(nextNode, lMap, stack));
    }

    if (nextAltNode.isPresent() && !Boolean.TRUE.equals(originalState)) {
      Map<ExprEquivalence.Wrapper, Boolean> rMap = new HashMap<>(exprValueMap);
      rMap.put(condition, false);
      tasks.add(visit(nextAltNode, rMap, stack));
    }
    return tasks.build();
  }

  private List<QueuedTask> visit(
      HtmlMatcherAccumulatorNode accNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    Optional<HtmlMatcherGraphNode> nextNode = accNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    return ImmutableList.of(visit(nextNode, exprValueMap, stack));
  }

  public void visit(HtmlMatcherGraphNode node) {
    Queue<QueuedTask> stack = new ArrayDeque<>();
    stack.add(
        visit(
            Optional.of(node), new HashMap<>(), new HtmlStack(null, foreignContentTagDepth, null)));
    while (!stack.isEmpty()) {
      QueuedTask task = stack.remove();
      List<QueuedTask> newTasks = task.run();
      stack.addAll(newTasks);
    }
  }

  private QueuedTask visit(
      Optional<HtmlMatcherGraphNode> maybeNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    if (!maybeNode.isPresent()) {
      return () -> {
        checkUnusedTags(stack);
        return ImmutableList.of();
      };
    }
    HtmlMatcherGraphNode node = maybeNode.get();
    if (node instanceof HtmlMatcherTagNode) {
      return () -> visit((HtmlMatcherTagNode) node, exprValueMap, stack);
    } else if (node instanceof HtmlMatcherConditionNode) {
      return () -> visit((HtmlMatcherConditionNode) node, exprValueMap, stack);
    } else if (node instanceof HtmlMatcherAccumulatorNode) {
      return () -> visit((HtmlMatcherAccumulatorNode) node, exprValueMap, stack);
    } else if (node instanceof HtmlMatcherBlockNode) {
      return () -> visit((HtmlMatcherBlockNode) node, exprValueMap, stack);
    } else {
      throw new UnsupportedOperationException("No implementation for: " + node);
    }
  }

  private void checkUnusedTags(HtmlStack stack) {
    while (!stack.isEmpty()) {
      if (stack.tagNode.getTagName().isDefinitelyOptional() && !inCondition) {
        injectCloseTag(stack.tagNode, null, idGenerator);
      } else {
        annotationMap.put(stack.tagNode, INVALID_NODE);
      }
      stack = stack.pop();
    }
  }
}
