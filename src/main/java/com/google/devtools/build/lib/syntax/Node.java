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


/** A Node is a node in a Starlark syntax tree. */
public abstract class Node {

  // Use these typical node distributions in Bazel files
  // as a rough guide for optimization decisions.
  // BUILD files are much more numerous than .bzl files,
  // and typically larger.
  //
  // Large BUILD file:
  //   49  % StringLiteral
  //   17  % Identifier
  //   12  % Argument.Keyword
  //    9  % ListExpression
  //    4  % CallExpression
  //    3.5% ExpressionStatement
  //    3.1% Comment
  //    1.2% Argument.Positional
  //    1.8% all others
  //
  // Large .bzl logic file:
  //   42 % Identifier
  //   12 % DotExpression
  //   7.1% StringLiteral
  //   6.7% Argument.Keyword
  //   6.7% CallExpression
  //   4.6% Argument.Positional
  //   3.1% Comment
  //   2.4% ListExpression
  //   2.4% ExpressionStatement
  //   2.2% AssignmentStatement
  //   1.9% DictExpression.Entry
  //   1.9% BinaryOperatorExpression
  //   1.0% Comprehension
  //   6  % all others

  // TODO(adonovan): instead of creating Locations during parsing.
  // record the LineNumberTable and the offsets of key tokens,
  // then create Locations on demand for the node start and end
  // and for key tokens.
  private Lexer.LexerLocation location;

  Node() {}

  final void setLocation(Lexer.LexerLocation location) {
    this.location = location;
  }

  /** @return the same node with its location set, in a slightly more fluent style */
  static <NodeT extends Node> NodeT setLocation(Lexer.LexerLocation location, NodeT node) {
    node.setLocation(location);
    return node;
  }

  /** Returns the location of the start of this node. */
  public final Location getStartLocation() {
    return location;
  }

  /** Returns the char offset of the start of this node within its file. */
  public final int getStartOffset() {
    return location.startOffset;
  }

  /** Returns the char offset of the end of this node within its file. */
  public final int getEndOffset() {
    return location.endOffset;
  }

  /** Returns the Location of the end of this node. */
  public final Location getEndLocation() {
    return location.getEndLocation();
  }

  /**
   * Returns a pretty-printed representation of this syntax tree.
   *
   * <p>This function returns the canonical source code corresponding to a syntax tree. Generally,
   * the output can be round-tripped: pretty-printing a syntax tree then parsing the result should
   * yield an equivalent syntax tree.
   *
   * <p>The pretty-printed form of a syntax tree may be used as a proxy for equality in tests.
   * However, different trees may have the same printed form. In particular, {@link StarlarkFile}
   * includes comments that are not reflected in the string.
   */
  public final String prettyPrint() {
    StringBuilder buf = new StringBuilder();
    new NodePrinter(buf).printNode(this);
    return buf.toString();
  }

  /**
   * Print the syntax node in a form useful for debugging.
   *
   * <p>The output is not precisely specified; use {@link #prettyPrint()} if you need more stable
   * and complete information. For instance, this function may omit child statements of compound
   * statements, or parentheses around some expressions. It may also abbreviate large list literals.
   */
  @Override
  public String toString() {
    return prettyPrint(); // default behavior, overridden in several subclasses
  }

  /**
   * Implements the double dispatch by calling into the node specific <code>visit</code> method of
   * the {@link NodeVisitor}
   *
   * @param visitor the {@link NodeVisitor} instance to dispatch to.
   */
  public abstract void accept(NodeVisitor visitor);
}
