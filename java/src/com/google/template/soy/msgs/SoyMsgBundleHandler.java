
/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.msgs;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteSink;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.error.ErrorReporter;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Handler for writing {@code SoyMsgBundle}s to file format and for creating {@code SoyMsgBundle}s
 * from files or resources.
 *
 * <p>Uses a {@code SoyMsgPlugin} to do the actual generation of the output data and the actual
 * parsing of the input data. The {@code SoyMsgPlugin} implements the specific message file format.
 */
@Immutable
public class SoyMsgBundleHandler {

  /**
   * Options for generating an output messages file.
   *
   * <p>This same class is used for both extracted messages files (source messages to be translated)
   * and translated messages files. Not all options will apply to both types of output files, and
   * not all options will apply to all message plugins.
   */
  public static class OutputFileOptions {

    /** The source locale string in the messages file. */
    private String sourceLocaleString;

    /** The target locale string in the messages file. */
    private String targetLocaleString;

    /**
     * This constructor sets default values for the source locale string and target locale string.
     * The source locale string defaults to "en" and the target locale string defaults to null.
     */
    public OutputFileOptions() {
      sourceLocaleString = "en";
      targetLocaleString = null;
    }

    /**
     * Sets the source locale string for an output messages file.
     *
     * @param sourceLocaleString The source locale string.
     */
    public void setSourceLocaleString(String sourceLocaleString) {
      this.sourceLocaleString = sourceLocaleString;
    }

    /** Returns the source locale string. */
    public String getSourceLocaleString() {
      return sourceLocaleString;
    }

    /**
     * Sets the target locale string for an output messages file.
     *
     * @param targetLocaleString The target locale string.
     */
    public void setTargetLocaleString(String targetLocaleString) {
      this.targetLocaleString = targetLocaleString;
    }

    /** Returns the target locale string. */
    public String getTargetLocaleString() {
      return targetLocaleString;
    }
  }

  /** For backwards-compatibility checking of file names that start with "en". */
  private static final Pattern FIRST_WORD_IS_EN_PATTERN = Pattern.compile("^en[^A-Za-z].*");

  private final SoyMsgPlugin msgPlugin;

  public SoyMsgBundleHandler(SoyMsgPlugin msgPlugin) {
    this.msgPlugin = msgPlugin;
  }

  /**
   * Reads a translated messages file and creates a SoyMsgBundle.
   *
   * @param inputFile The input file to read from.
   * @return The message bundle created from the messages file.
   * @throws IOException If there's an error while accessing the file.
   * @throws SoyMsgException If there's an error while processing the messages.
   */
  public SoyMsgBundle createFromFile(File inputFile) throws IOException {
    if (isFallbackFile(inputFile)) {
      return SoyMsgBundle.EMPTY;
    }

    String inputFileContent = readFileContent(inputFile);
    return msgPlugin.parseTranslatedMsgsFile(inputFileContent);
  }

  private boolean isFallbackFile(File inputFile) {
    return !inputFile.exists() && FIRST_WORD_IS_EN_PATTERN.matcher(inputFile.getName()).matches();
  }

  private String readFileContent(File inputFile) throws IOException {
    try {
      return Files.asCharSource(inputFile, UTF_8).read();
    } catch (SoyMsgException sme) {
      sme.setFileOrResourceName(inputFile.toString());
      throw sme;
    }
  }

  /**
   * Reads a translated messages resource and creates a SoyMsgBundle.
   *
   * @param inputResource The resource to read from.
   * @return The message bundle created from the messages resource.
   * @throws IOException If there's an error while accessing the resource.
   * @throws SoyMsgException If there's an error while processing the messages.
   */
  public SoyMsgBundle createFromResource(URL inputResource) throws IOException {
    String inputFileContent = readResourceContent(inputResource);
    return msgPlugin.parseTranslatedMsgsFile(inputFileContent);
  }

  private String readResourceContent(URL inputResource) throws IOException {
    try {
      return Resources.asCharSource(inputResource, UTF_8).read();
    } catch (SoyMsgException sme) {
      sme.setFileOrResourceName(inputResource.toString());
      throw sme;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Soy internal methods.

  /**
   * Generates extracted messages (source messages to be translated) from a given message bundle,
   * and writes it out.
   *
   * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
   *
   * @param msgBundle The message bundle to write.
   * @param options The options for generating the output extracted messages (depending on the
   *     message plugin being used, none or some of the options may be applicable).
   * @param output The output to write to.
   * @param errorReporter For reporting errors.
   * @throws SoyMsgException If there's an error while processing the messages.
   * @throws IOException If there's an error writing the messages.
   */
  public void writeExtractedMsgs(
      SoyMsgBundle msgBundle,
      OutputFileOptions options,
      ByteSink output,
      ErrorReporter errorReporter)
      throws IOException {

    CharSequence cs = msgPlugin.generateExtractedMsgsFile(msgBundle, options, errorReporter);
    output.asCharSink(UTF_8).write(cs);
  }
}
