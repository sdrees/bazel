// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.syntax;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.events.Event;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Syntax tree for a Starlark file, such as a Bazel BUILD or .bzl file.
 *
 * <p>Call {@link #parse} to parse a file. Parser errors are recorded in the syntax tree (see {@link
 * #errors}), which may be incomplete.
 */
public final class StarlarkFile extends Node {

  private final ImmutableList<Statement> statements;
  private final FileOptions options;
  private final ImmutableList<Comment> comments;
  final List<Event> errors; // appended to by ValidationEnvironment
  @Nullable private final String contentHashCode;

  private StarlarkFile(
      ImmutableList<Statement> statements,
      FileOptions options,
      ImmutableList<Comment> comments,
      List<Event> errors,
      String contentHashCode,
      Lexer.LexerLocation location) {
    this.statements = statements;
    this.options = options;
    this.comments = comments;
    this.errors = errors;
    this.contentHashCode = contentHashCode;
    this.setLocation(location);
  }

  // Creates a StarlarkFile from the given effective list of statements,
  // which may include the prelude.
  private static StarlarkFile create(
      ImmutableList<Statement> statements,
      FileOptions options,
      Parser.ParseResult result,
      String contentHashCode) {
    return new StarlarkFile(
        statements,
        options,
        ImmutableList.copyOf(result.comments),
        result.errors,
        contentHashCode,
        result.location);
  }

  /** Extract a subtree containing only statements from i (included) to j (excluded). */
  public StarlarkFile subTree(int i, int j) {
    return new StarlarkFile(
        this.statements.subList(i, j),
        this.options,
        /*comments=*/ ImmutableList.of(),
        errors,
        /*contentHashCode=*/ null,
        (Lexer.LexerLocation) this.statements.get(i).getStartLocation());
  }

  /**
   * Returns an unmodifiable view of the list of scanner, parser, and (perhaps) resolver errors
   * accumulated in this Starlark file.
   */
  public List<Event> errors() {
    return Collections.unmodifiableList(errors);
  }

  /** Returns errors().isEmpty(). */
  public boolean ok() {
    return errors.isEmpty();
  }

  /** Returns an (immutable, ordered) list of statements in this BUILD file. */
  public ImmutableList<Statement> getStatements() {
    return statements;
  }

  /** Returns an (immutable, ordered) list of comments in this BUILD file. */
  public ImmutableList<Comment> getComments() {
    return comments;
  }

  @Override
  public String toString() {
    return "<StarlarkFile with " + statements.size() + " statements>";
  }

  @Override
  public void accept(NodeVisitor visitor) {
    visitor.visit(this);
  }

  /**
   * Parse the specified file, returning its syntax tree with the prelude statements inserted at the
   * front of its statement list.
   */
  public static StarlarkFile parseWithPrelude(
      ParserInput input, List<Statement> prelude, FileOptions options) {
    Parser.ParseResult result = Parser.parseFile(input, options);

    ImmutableList.Builder<Statement> stmts = ImmutableList.builder();
    stmts.addAll(prelude);
    stmts.addAll(result.statements);

    return create(stmts.build(), options, result, /*contentHashCode=*/ null);
  }

  // TODO(adonovan): make the digest publicly settable, and delete this.
  public static StarlarkFile parseWithDigest(ParserInput input, byte[] digest, FileOptions options)
      throws IOException {
    Parser.ParseResult result = Parser.parseFile(input, options);
    return create(
        ImmutableList.copyOf(result.statements),
        options,
        result,
        HashCode.fromBytes(digest).toString());
  }

  /**
   * Parse a Starlark file.
   *
   * <p>A syntax tree is always returned, even in case of error. Errors are recorded in the tree.
   * Example usage:
   *
   * <pre>
   * StarlarkFile file = StarlarkFile.parse(input, options);
   * if (!file.ok()) {
   *    Event.replayEventsOn(handler, file.errors());
   *    ...
   * }
   * </pre>
   */
  public static StarlarkFile parse(ParserInput input, FileOptions options) {
    Parser.ParseResult result = Parser.parseFile(input, options);
    return create(
        ImmutableList.copyOf(result.statements), options, result, /*contentHashCode=*/ null);
  }

  /** Parse a Starlark file with default options. */
  public static StarlarkFile parse(ParserInput input) {
    return parse(input, FileOptions.DEFAULT);
  }

  /** Returns the options specified when parsing this file. */
  public FileOptions getOptions() {
    return options;
  }

  /**
   * Returns a hash code calculated from the string content of the source file of this AST.
   */
  @Nullable public String getContentHashCode() {
    return contentHashCode;
  }
}
