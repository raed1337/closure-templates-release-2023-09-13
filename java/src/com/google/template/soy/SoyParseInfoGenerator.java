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

package com.google.template.soy;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.base.internal.SoyJarFileWriter;
import com.google.template.soy.shared.internal.gencode.GeneratedFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Option;

/**
 * Executable for generating Java classes containing Soy parse info.
 *
 * <p>The command-line arguments should contain command-line flags and the list of paths to the Soy
 * files.
 */
public final class SoyParseInfoGenerator extends AbstractSoyCompiler {

  @Option(
      name = "--generateBuilders",
      usage =
          "[Reqiured] Whether to generate the new java template invocation builders"
              + " (FooTemplates.java). If false, generates the old FooSoyInfo.java files"
              + " instead.")
  private boolean generateBuilders = false;

  @Option(
      name = "--kytheCorpus",
      usage =
          "[Optional] The value to use for the Kythe corpus of GeneratedCodeInfo metadata. If empty"
              + " GeneratedCodeInfo will not be added to the output.")
  private String kytheCorpus = "";

  @Option(
      name = "--outputDirectory",
      usage =
          "[Optional] The path to the output directory. If files with the same names already exist"
              + " at this location, they will be overwritten. Either --outputDirectory or"
              + " --outputJar must be set.")
  private String outputDirectory = "";

  @Option(
      name = "--outputSrcJar",
      usage =
          "[Optional]  The path to the source jar to write. If a file with the same name already"
              + " exists at this location, it will be overwritten. Either --outputDirectory or"
              + " --outputJar must be set.")
  private File outputSrcJar;

  @Option(
      name = "--javaClassNameSource",
      required = true,
      usage =
          "[Required for *SoyInfo mode, Ignored for invocation builder mode (i.e. if"
              + " --generateBuilders=true)]. The source for the"
              + " generated class names. Valid values are \"filename\", \"namespace\", and"
              + " \"generic\". Option \"filename\" turns a Soy file name AaaBbb.soy or aaa_bbb.soy"
              + " into AaaBbbSoyInfo. Option \"namespace\" turns a namespace aaa.bbb.cccDdd into"
              + " CccDddSoyInfo (note it only uses the last part of the namespace). Option"
              + " \"generic\" generates class names such as File1SoyInfo, File2SoyInfo.")
  private String javaClassNameSource = "";

  SoyParseInfoGenerator(PluginLoader loader, SoyInputCache cache) {
    super(loader, cache);
  }

  SoyParseInfoGenerator() {}

  /**
   * Generates Java classes containing Soy parse info.
   *
   * <p>If syntax errors are encountered, no output is generated and the process terminates with a
   * non-zero exit status. On successful parse, the process terminates with a zero exit status.
   *
   * @param args Should contain command-line flags and the list of paths to the Soy files.
   */
  public static void main(String[] args) {
    new SoyParseInfoGenerator().runMain(args);
  }

  @Override
  protected void validateFlags() {
    // Java package is always required.
    if (javaPackage.length() == 0) {
      exitWithError("Must provide Java package.");
    }

    if (outputDirectory.isEmpty() == (outputSrcJar == null)) {
      exitWithError("Must provide exactly one of --outputDirectory or --outputSrcJar");
    }

    if (!generateBuilders && javaClassNameSource.isEmpty()) {
      exitWithError("Must provide Java class name source.");
    }
  }

  @Override
  protected void compile(SoyFileSet.Builder sfsBuilder) throws IOException {
    SoyFileSet sfs = sfsBuilder.build();

    ImmutableList<GeneratedFile> genFiles =
        generateBuilders
            ? sfs.generateBuilders(javaPackage, kytheCorpus)
            : sfs.generateParseInfo(javaPackage, kytheCorpus, javaClassNameSource);

    if (outputSrcJar == null) {
      for (GeneratedFile genFile : genFiles) {
        File outputFile = new File(outputDirectory, genFile.fileName());
        BaseUtils.ensureDirsExistInPath(outputFile.getPath());
        CharSink fileSink = Files.asCharSink(outputFile, UTF_8);
        writeContentsWithKytheComment(genFile, fileSink);
      }
    } else {
      String resourcePath = javaPackage.replace('.', '/') + "/";
      try (SoyJarFileWriter writer = new SoyJarFileWriter(new FileOutputStream(outputSrcJar))) {
        for (GeneratedFile genFile : genFiles) {
          writer.writeEntry(
              resourcePath + genFile.fileName(),
              contentsWithKytheComment(genFile, false).asByteSource(UTF_8));
        }
      }
    }
  }

  static void writeContentsWithKytheComment(GeneratedFile file, CharSink sink) throws IOException {
    contentsWithKytheComment(file, false).copyTo(sink);
  }

  static String getFullContentsForGolden(GeneratedFile file) {
    try {
      return contentsWithKytheComment(file, true).read();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static CharSource contentsWithKytheComment(GeneratedFile file, boolean withTestOutput) {
    List<CharSource> parts = new ArrayList<>();
    parts.add(CharSource.wrap((file.contents())));
    Message generatedCodeInfo = file.generatedCodeInfo();
    if (generatedCodeInfo != null) {
      if (!file.contents().endsWith("\n")) {
        parts.add(CharSource.wrap(("\n")));
      }
      parts.add(CharSource.wrap(("// GeneratedCodeInfo:")));
      if (withTestOutput) {
        parts.add(CharSource.wrap(("--base64 encoding of the proto below--")));
      } else {
        parts.add(CharSource.wrap((BaseEncoding.base64().encode(generatedCodeInfo.toByteArray()))));
      }
      parts.add(CharSource.wrap(("\n")));

      if (withTestOutput) {
        parts.add(CharSource.wrap(("/**\n\nHuman-readable test-only output:\n\n")));
        parts.add(CharSource.wrap(TextFormat.printer().printToString(generatedCodeInfo)));
        parts.add(CharSource.wrap(("\n*/\n")));
      }
    }
    return CharSource.concat(parts);
  }
}
