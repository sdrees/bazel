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
package com.google.devtools.build.lib.query2.engine;

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import java.util.Optional;

/**
 */
public class QueryException extends Exception {
  private final Optional<FailureDetail> failureDetail;

  /**
   * Returns a better error message for the query.
   */
  static String describeFailedQuery(QueryException e, QueryExpression toplevel) {
    QueryExpression badQuery = e.getFailedExpression();
    if (badQuery == null) {
      return "Evaluation failed: " + e.getMessage();
    }
    return badQuery == toplevel
        ? "Evaluation of query \"" + toplevel + "\" failed: " + e.getMessage()
        : "Evaluation of subquery \"" + badQuery
            + "\" failed (did you want to use --keep_going?): " + e.getMessage();
  }

  private final QueryExpression expression;

  public QueryException(QueryException e, QueryExpression toplevel) {
    super(describeFailedQuery(e, toplevel), e);
    this.expression = null;
    this.failureDetail = e.getFailureDetail();
  }

  public QueryException(QueryExpression expression, String message) {
    super(message);
    this.expression = expression;
    this.failureDetail = Optional.empty();
  }

  public QueryException(QueryExpression expression, String message, Query.Code queryCode) {
    super(message);
    this.expression = expression;
    this.failureDetail =
        Optional.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setQuery(Query.newBuilder().setCode(queryCode).build())
                .build());
  }

  public QueryException(String message) {
    this(null, message);
  }

  public QueryException(String message, Query.Code queryCode) {
    this(null, message, queryCode);
  }

  /**
   * Returns the subexpression for which evaluation failed, or null if
   * the failure occurred during lexing/parsing.
   */
  public QueryExpression getFailedExpression() {
    return expression;
  }

  /** Returns an optional {@link FailureDetail} containing fine grained detail code. */
  public Optional<FailureDetail> getFailureDetail() {
    return failureDetail;
  }
}
