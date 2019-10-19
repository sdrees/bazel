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

import java.io.IOException;

/**
 * Base class for all expression nodes in the AST.
 *
 * <p>The only expressions permitted on the left-hand side of an assignment (such as 'lhs=rhs' or
 * 'for lhs in expr') are identifiers, dot expressions (x.y), list expressions ([expr, ...]), tuple
 * expressions ((expr, ...)), or parenthesized variants of those. In particular and unlike Python,
 * slice expressions and starred expressions cannot appear on the LHS. TODO(bazel-team): Add support
 * for assigning to slices (e.g. a[2:6] = [3]).
 */
public abstract class Expression extends Node {

  /**
   * Kind of the expression. This is similar to using instanceof, except that it's more efficient
   * and can be used in a switch/case.
   */
  public enum Kind {
    BINARY_OPERATOR,
    COMPREHENSION,
    CONDITIONAL,
    DICT_EXPR,
    DOT,
    FUNCALL,
    IDENTIFIER,
    INDEX,
    INTEGER_LITERAL,
    LIST_EXPR,
    SLICE,
    STRING_LITERAL,
    UNARY_OPERATOR,
  }

  @Override
  public final void prettyPrint(Appendable buffer, int indentLevel) throws IOException {
    prettyPrint(buffer);
  }

  /**
   * Expressions should implement this method instead of {@link #prettyPrint(Appendable, int)},
   * since the {@code indentLevel} argument is not needed.
   */
  @Override
  public abstract void prettyPrint(Appendable buffer) throws IOException;

  /**
   * Kind of the expression. This is similar to using instanceof, except that it's more efficient
   * and can be used in a switch/case.
   */
  public abstract Kind kind();

  /** Parses an expression. */
  public static Expression parse(ParserInput input) throws SyntaxError {
    return Parser.parseExpression(input);
  }

}
