
package com.google.template.soy.passes;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.TemplateContentKind.ElementContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.MethodCallNode;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.HtmlElementMetadataP;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.KeyNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SkipNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.TagName;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateElementNode;
import com.google.template.soy.soytree.TemplateMetadata;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.VeLogNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Validates restrictions specific to Soy elements. */
@RunAfter(StrictHtmlValidationPass.class)
final class SoyElementPass implements CompilerFileSetPass {

  private static final SoyErrorKind SOYELEMENT_CANNOT_BE_SKIPPED =
      SoyErrorKind.of("Soy elements cannot be skipped.");

  private static final SoyErrorKind SOY_ELEMENT_MUST_HAVE_STATIC_TAG =
      SoyErrorKind.of("Soy elements must have static tags.");

  private static final SoyErrorKind SOYELEMENT_CANNOT_WRAP_ITSELF_RECURSIVELY =
      SoyErrorKind.of(
          "The root node of Soy elements must not recursively call itself. The cycle is ''{0}''.");

  private static final SoyErrorKind SOYELEMENT_CANNOT_WRAP_SOY_ELEMENT =
      SoyErrorKind.of("The root node of Soy elements must not be another Soy element.");

  private static final SoyErrorKind ROOT_HAS_KEY_NODE =
      SoyErrorKind.of(
          "The root node of Soy elements must not have a key. "
              + "Instead, consider wrapping the Soy element in a keyed tag node.");

  private static final SoyErrorKind SOY_ELEMENT_OPEN_TAG_CLOSE_AMBIGUOUS =
      SoyErrorKind.of("Soy element open tags must map to exactly one close tag.");

  private static final SoyErrorKind SOY_ELEMENT_EXACTLY_ONE_TAG =
      SoyErrorKind.of(
          "Soy elements must contain exactly one top-level HTML element (e.g, span, div). Calls to"
              + " templates (but not deltemplates) that contain one top-level HTML element are also"
              + " allowed, but not as function calls. Replace function calls with call commands or"
              + " element composition instead.");

  private static final SoyErrorKind ELEMENT_TEMPLATE_EXACTLY_ONE_TAG =
      SoyErrorKind.of(
          "Templates with kind=\"html<?>\" must contain exactly one top-level HTML element (e.g,"
              + " span, div).");

  static final ImmutableSet<SoyNode.Kind> ALLOWED_CHILD_NODES =
      Sets.immutableEnumSet(
          SoyNode.Kind.LET_CONTENT_NODE,
          SoyNode.Kind.LET_VALUE_NODE,
          SoyNode.Kind.DEBUGGER_NODE,
          SoyNode.Kind.LOG_NODE);

  private static final HtmlElementMetadataP DEFAULT_HTML_METADATA =
      HtmlElementMetadataP.newBuilder().setIsHtmlElement(false).setIsVelogged(false).build();

  private final ErrorReporter errorReporter;
  private final Supplier<FileSetMetadata> templateRegistryFromDeps;

  SoyElementPass(ErrorReporter errorReporter, Supplier<FileSetMetadata> templateRegistryFromDeps) {
    this.errorReporter = errorReporter;
    this.templateRegistryFromDeps = templateRegistryFromDeps;
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    Map<String, TemplateNode> templatesInLibrary = new LinkedHashMap<>();
    Set<TemplateNode> delegateTemplates = new HashSet<>();
    
    populateTemplates(sourceFiles, templatesInLibrary, delegateTemplates);
    
    processTemplates(templatesInLibrary, delegateTemplates);
    
    return Result.CONTINUE;
  }

  private void populateTemplates(ImmutableList<SoyFileNode> sourceFiles,
      Map<String, TemplateNode> templatesInLibrary, Set<TemplateNode> delegateTemplates) {
    for (SoyFileNode file : sourceFiles) {
      for (TemplateNode template : file.getTemplates()) {
        if (!template.getContentKind().isHtml()) {
          template.setHtmlElementMetadata(DEFAULT_HTML_METADATA);
        } else if (template instanceof TemplateDelegateNode) {
          delegateTemplates.add(template);
        } else {
          templatesInLibrary.put(template.getTemplateName(), template);
        }
      }
    }
  }

  private void processTemplates(Map<String, TemplateNode> templatesInLibrary,
      Set<TemplateNode> delegateTemplates) {
    for (TemplateNode template : templatesInLibrary.values()) {
      Set<TemplateNode> visited = new HashSet<>();
      getTemplateMetadata(template, templatesInLibrary, visited);
    }
    
    for (TemplateNode template : delegateTemplates) {
      Set<TemplateNode> visited = new HashSet<>();
      getTemplateMetadata(template, templatesInLibrary, visited);
    }
  }

  private HtmlElementMetadataP getTemplateMetadata(
      TemplateNode template,
      Map<String, TemplateNode> templatesInLibrary,
      Set<TemplateNode> visited) {
    if (visited.contains(template)) {
      handleCycle(template, visited);
      return null;
    }
    visited.add(template);
    
    boolean isSoyElement = template instanceof TemplateElementNode;
    boolean isElmOrHtml = template.getTemplateContentKind() instanceof ElementContentKind;
    VeLogNode veLogNode = null;
    HtmlOpenTagNode openTag = null;
    HtmlTagNode closeTag = null;
    SourceLocation invalidReportLoc = template.getSourceLocation();
    boolean reportedSingleHtmlElmError = false;

    for (int i = 0; i < template.numChildren(); i++) {
      SoyNode child = template.getChild(i);
      if (ALLOWED_CHILD_NODES.contains(child.getKind())) {
        continue;
      }

      if (openTag == null) {
        if (child instanceof CallBasicNode && ((CallBasicNode) child).isStaticCall() && i == template.numChildren() - 1) {
          checkStaticCall(template, (CallBasicNode) child, templatesInLibrary, visited);
          return getTemplateMetadataForStaticCall(
              template,
              ((CallBasicNode) child).getCalleeName(),
              child.getSourceLocation(),
              templatesInLibrary,
              visited);
        } else if (child instanceof HtmlOpenTagNode) {
          closeTag = checkHtmlOpenTag(template, (HtmlOpenTagNode) child, errorReporter, isSoyElement);
          if (closeTag == null) {
            break;
          }
          i = template.getChildIndex(closeTag);
          openTag = (HtmlOpenTagNode) child;
        } else if (child instanceof VeLogNode) {
          veLogNode = (VeLogNode) child;
          handleVeLogNode(template, veLogNode, isSoyElement, templatesInLibrary, visited);
        } else {
          openTag = null;
          closeTag = null;
          invalidReportLoc = child.getSourceLocation();
          break;
        }
      }
    }
    finalizeTemplateMetadata(openTag, closeTag, template, isSoyElement, invalidReportLoc, reportedSingleHtmlElmError);
    return constructHtmlElementMetadata(template, openTag, visited);
  }

  private void handleCycle(TemplateNode template, Set<TemplateNode> visited) {
    if (template instanceof TemplateElementNode) {
      errorReporter.report(
          template.getSourceLocation(),
          SOYELEMENT_CANNOT_WRAP_ITSELF_RECURSIVELY,
          visited.stream().map(TemplateNode::getTemplateName).sorted().collect(toImmutableSet()));
    }
    template.setHtmlElementMetadata(DEFAULT_HTML_METADATA);
  }

  private void checkStaticCall(TemplateNode template, CallBasicNode child,
      Map<String, TemplateNode> templatesInLibrary, Set<TemplateNode> visited) {
    if (child.getKeyExpr() != null && template instanceof TemplateElementNode) {
      errorReporter.report(
          child.getSourceCalleeLocation(), ROOT_HAS_KEY_NODE);
    }
  }

  private void handleVeLogNode(TemplateNode template, VeLogNode veLogNode, boolean isSoyElement,
      Map<String, TemplateNode> templatesInLibrary, Set<TemplateNode> visited) {
    HtmlOpenTagNode maybeOpenTagNode = veLogNode.getOpenTagNode();
    if (maybeOpenTagNode != null) {
      HtmlTagNode closeTag = checkHtmlOpenTag(veLogNode, maybeOpenTagNode, errorReporter, isSoyElement);
      if (closeTag == null) {
        return; // skip reporting additional errors
      }
      if (veLogNode.getChildren().stream().filter(p -> p instanceof CallBasicNode).count() == 1) {
        CallBasicNode callNode = (CallBasicNode) veLogNode.getChildren().get(0);
        if (callNode.isStaticCall()) {
          checkStaticCall(template, callNode, templatesInLibrary, visited);
          getTemplateMetadataForStaticCall(
              template,
              callNode.getCalleeName(),
              callNode.getSourceLocation(),
              templatesInLibrary,
              visited);
        }
      }
    }
  }

  private void finalizeTemplateMetadata(HtmlOpenTagNode openTag, HtmlTagNode closeTag,
      TemplateNode template, boolean isSoyElement, SourceLocation invalidReportLoc, boolean reportedSingleHtmlElmError) {
    if (openTag != null) {
      openTag.setElementRoot();
    }
    boolean isValid = openTag != null && closeTag != null;
    boolean hasSkipNode = false;
    if (isValid) {
      hasSkipNode = openTag.getChildren().stream().anyMatch(child -> child instanceof SkipNode);
      if (hasSkipNode && template instanceof TemplateElementNode) {
        errorReporter.report(openTag.getSourceLocation(), SOYELEMENT_CANNOT_BE_SKIPPED);
      }
    } else if (!reportedSingleHtmlElmError) {
      if (isSoyElement) {
        errorReporter.report(invalidReportLoc, SOY_ELEMENT_EXACTLY_ONE_TAG);
      }
    }
  }

  private HtmlElementMetadataP constructHtmlElementMetadata(TemplateNode template,
      HtmlOpenTagNode openTag, Set<TemplateNode> visited) {
    String delegateTemplate = getStaticDelegateCall(openTag);
    String tagName = openTag != null && openTag.getTagName().isStatic() ? openTag.getTagName().getStaticTagName() : "?";
    
    HtmlElementMetadataP info = HtmlElementMetadataP.newBuilder()
            .setIsHtmlElement(openTag != null && openTag.getTaggedPairs().size() == 1)
            .setTag(tagName)
            .setIsVelogged(false)
            .setIsSkip(false)
            .setDelegateElement(nullToEmpty(delegateTemplate))
            .setFinalCallee("")
            .build();
    template.setHtmlElementMetadata(info);
    return info;
  }

  private static String getStaticDelegateCall(HtmlOpenTagNode openTag) {
    TagName tagName = openTag.getTagName();
    if (tagName.isStatic()) {
      return null;
    }
    PrintNode printNode = tagName.getDynamicTagName();
    ExprNode exprNode = printNode.getExpr().getRoot();
    if (exprNode instanceof TemplateLiteralNode) {
      return ((TemplateLiteralNode) exprNode).getResolvedName();
    }
    if (!(exprNode.getKind() == ExprNode.Kind.METHOD_CALL_NODE
        && ((MethodCallNode) exprNode).getMethodName().identifier().equals("bind"))) {
      return null;
    }

    MethodCallNode bind = (MethodCallNode) exprNode;
    if (bind.getChild(0).getKind() != ExprNode.Kind.TEMPLATE_LITERAL_NODE) {
      return null;
    }

    return ((TemplateLiteralNode) bind.getChild(0)).getResolvedName();
  }

  private HtmlElementMetadataP getTemplateMetadataForStaticCall(
      TemplateNode template,
      String callee,
      SourceLocation calleeSourceLocation,
      Map<String, TemplateNode> templatesInLibrary,
      Set<TemplateNode> visited) {

    HtmlElementMetadataP calleeMetadata;
    boolean isCalleeSoyElement;
    TemplateMetadata templateMetadata =
        templateRegistryFromDeps.get().getBasicTemplateOrElement(callee);

    if (templateMetadata != null) {
      calleeMetadata = templateMetadata.getHtmlElement();
      isCalleeSoyElement = templateMetadata.getSoyElement().getIsSoyElement();
    } else if (templatesInLibrary.containsKey(callee)) {
      TemplateNode calledTemplate = templatesInLibrary.get(callee);
      calleeMetadata = calledTemplate.getHtmlElementMetadata();
      if (calleeMetadata == null) {
        calleeMetadata = getTemplateMetadata(calledTemplate, templatesInLibrary, visited);
        if (calleeMetadata == null) {
          template.setHtmlElementMetadata(DEFAULT_HTML_METADATA);
          return null;
        }
      }
      isCalleeSoyElement = calledTemplate instanceof TemplateElementNode;
    } else {
      isCalleeSoyElement = false;
      calleeMetadata = DEFAULT_HTML_METADATA;
    }
    
    String finalCallee = calleeMetadata.getFinalCallee().isEmpty() ? callee : calleeMetadata.getFinalCallee();
    calleeMetadata = calleeMetadata.toBuilder()
            .clearDelegateElement()
            .setDelegateCallee(callee)
            .setFinalCallee(finalCallee)
            .build();
    
    template.setHtmlElementMetadata(calleeMetadata);
    
    if (template instanceof TemplateElementNode) {
      if (calleeMetadata.getIsSkip()) {
        errorReporter.report(calleeSourceLocation, SOYELEMENT_CANNOT_BE_SKIPPED);
      }
      if (isCalleeSoyElement) {
        errorReporter.report(calleeSourceLocation, SOYELEMENT_CANNOT_WRAP_SOY_ELEMENT);
      }
      if (!calleeMetadata.getIsHtmlElement()) {
        errorReporter.report(calleeSourceLocation, SOY_ELEMENT_EXACTLY_ONE_TAG);
      }
    }
    return calleeMetadata;
  }

  @Nullable
  static HtmlTagNode checkHtmlOpenTag(
      BlockNode parent,
      HtmlOpenTagNode openTagNode,
      ErrorReporter errorReporter,
      boolean isSoyElement) {
    if (isSoyElement) {
      validateOpenTagProperties(openTagNode, errorReporter);
    }
    if (openTagNode.isSelfClosing()
        || (openTagNode.getTagName().isDefinitelyVoid()
            && openTagNode.getTaggedPairs().isEmpty())) {
      return openTagNode;
    } else if (openTagNode.getTaggedPairs().isEmpty()) {
      return openTagNode;
    } else {
      if (openTagNode.getTaggedPairs().size() == 1) {
        HtmlTagNode closeTag = openTagNode.getTaggedPairs().get(0);
        if (closeTag.getParent() != parent) {
          if (isSoyElement) {
            errorReporter.report(
                openTagNode.getSourceLocation(), SOY_ELEMENT_OPEN_TAG_CLOSE_AMBIGUOUS);
          }
          return null;
        }
        return closeTag;
      } else {
        if (isSoyElement) {
          errorReporter.report(
              openTagNode.getSourceLocation(), SOY_ELEMENT_OPEN_TAG_CLOSE_AMBIGUOUS);
        }
        return null;
      }
    }
  }

  private static void validateOpenTagProperties(
      HtmlOpenTagNode firstTagNode, ErrorReporter errorReporter) {
    if (firstTagNode.getTagName().isLegacyDynamicTagName()) {
      errorReporter.report(firstTagNode.getSourceLocation(), SOY_ELEMENT_MUST_HAVE_STATIC_TAG);
    }
    for (SoyNode child : firstTagNode.getChildren()) {
      if (child instanceof KeyNode) {
        errorReporter.report(firstTagNode.getSourceLocation(), ROOT_HAS_KEY_NODE);
      }
    }
  }
}
