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
package com.google.devtools.build.lib.collect;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CollectionUtils}. */
@RunWith(JUnit4.class)
public final class CollectionUtilsTest {

  @Test
  public void testDuplicatedElementsOf() {
    assertDups(ImmutableList.of(), ImmutableSet.of());
    assertDups(ImmutableList.of(0), ImmutableSet.of());
    assertDups(ImmutableList.of(0, 0, 0), ImmutableSet.of(0));
    assertDups(ImmutableList.of(1, 2, 3, 1, 2, 3), ImmutableSet.of(1, 2, 3));
    assertDups(ImmutableList.of(1, 2, 3, 1, 2, 3, 4), ImmutableSet.of(1, 2, 3));
    assertDups(ImmutableList.of(1, 2, 3, 4), ImmutableSet.of());
  }

  private static void assertDups(List<Integer> collection, Set<Integer> dups) {
    assertThat(CollectionUtils.duplicatedElementsOf(collection)).isEqualTo(dups);
  }

  @Test
  public void testIsImmutable() {
    assertThat(CollectionUtils.isImmutable(ImmutableList.of(1, 2, 3))).isTrue();
    assertThat(CollectionUtils.isImmutable(ImmutableSet.of(1, 2, 3))).isTrue();

    Iterable<Integer> chain = IterablesChain.<Integer>builder().addElement(1).build();

    assertThat(CollectionUtils.isImmutable(chain)).isTrue();

    assertThat(CollectionUtils.isImmutable(Lists.newArrayList())).isFalse();
    assertThat(CollectionUtils.isImmutable(Lists.newLinkedList())).isFalse();
    assertThat(CollectionUtils.isImmutable(Sets.newHashSet())).isFalse();
    assertThat(CollectionUtils.isImmutable(Sets.newLinkedHashSet())).isFalse();
  }

  @Test
  public void testCheckImmutable() {
    CollectionUtils.checkImmutable(ImmutableList.of(1, 2, 3));
    CollectionUtils.checkImmutable(ImmutableSet.of(1, 2, 3));

    List<Integer> mutableList = Lists.newArrayList(1, 2, 3);
    assertThrows(IllegalStateException.class, () -> CollectionUtils.checkImmutable(mutableList));
  }

  @Test
  public void testMakeImmutable() {
    Iterable<Integer> immutableList = ImmutableList.of(1, 2, 3);
    assertThat(CollectionUtils.makeImmutable(immutableList)).isSameInstanceAs(immutableList);

    List<Integer> mutableList = Lists.newArrayList(1, 2, 3);
    Iterable<Integer> converted = CollectionUtils.makeImmutable(mutableList);
    assertThat(converted).isNotSameInstanceAs(mutableList);
    assertThat(ImmutableList.copyOf(converted)).isEqualTo(mutableList);
  }

  @Test
  public void isNullOrEmpty_null() {
    assertThat(CollectionUtils.isNullOrEmpty(null)).isTrue();
  }

  @Test
  public void isNullOrEmpty_empty() {
    assertThat(CollectionUtils.isNullOrEmpty(ImmutableList.of())).isTrue();
  }

  @Test
  public void isNullOrEmpty_nonEmpty() {
    assertThat(CollectionUtils.isNullOrEmpty(ImmutableList.of(1))).isFalse();
  }
}
