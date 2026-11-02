/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.litho.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.facebook.litho.testing.testrunner.LithoTestRunner;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

/** Test for {@link RecyclerRangeTraverser} */
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RecyclerRangeTraverserTest implements RecyclerRangeTraverser.Processor {
  private ArrayList<Integer> mCollectedIndices;

  @Before
  public void setUp() {
    mCollectedIndices = new ArrayList<>();
  }

  @Test
  public void testForwardTraverserWithEmptyRange() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.FORWARD_TRAVERSER;
    traverser.traverse(0, 0, 0, 0, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList());
  }

  @Test
  public void testForwardTraverserWithSingleItemRange() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.FORWARD_TRAVERSER;
    traverser.traverse(0, 1, 0, 0, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(0));
  }

  @Test
  public void testForwardTraverserWithEntireRangeVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.FORWARD_TRAVERSER;
    traverser.traverse(0, 2, 0, 1, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(0, 1));
  }

  @Test
  public void testBackwardTraverserWithEntireRangeVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BACKWARD_TRAVERSER;
    traverser.traverse(0, 2, 0, 1, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(1, 0));
  }

  @Test
  public void testForwardTraverserWithSimpleRange() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.FORWARD_TRAVERSER;
    traverser.traverse(0, 10, 2, 4, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
  }

  @Test
  public void testBackwardTraverserWithSimpleRange() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BACKWARD_TRAVERSER;
    traverser.traverse(0, 10, 2, 4, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(9, 8, 7, 6, 5, 4, 3, 2, 1, 0));
  }

  @Test
  public void testBidirectionalTraverserWithEmptyRange() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 0, 0, 0, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList());
  }

  @Test
  public void testBidirectionalTraverserWithSingleItemRange() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 1, 0, 0, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(0));
  }

  @Test
  public void testBidirectionalTraverserWithEntireRangeVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, 0, 9, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(4, 3, 5, 2, 6, 1, 7, 0, 8, 9));
  }

  @Test
  public void testBidirectionalTraverserRangeStartVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, 0, 5, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(2, 1, 3, 0, 4, 5, 6, 7, 8, 9));
  }

  @Test
  public void testBidirectionalTraverserRangeMiddleVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, 4, 8, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(6, 5, 7, 4, 8, 3, 9, 2, 1, 0));
  }

  @Test
  public void testBidirectionalTraverserSingleItemVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, 3, 3, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(3, 2, 4, 1, 5, 0, 6, 7, 8, 9));
  }

  @Test
  public void testBidirectionalTraverserInvalidFirstVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, -1, 7, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(7, 6, 8, 5, 9, 4, 3, 2, 1, 0));
  }

  @Test
  public void testBidirectionalTraverserInvalidLastVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, 0, -1, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
  }

  @Test
  public void testBidirectionalTraverserInvalidFirstAndLastVisible() {
    RecyclerRangeTraverser traverser = RecyclerRangeTraverser.BIDIRECTIONAL_TRAVERSER;
    traverser.traverse(0, 10, -1, -1, this);
    assertThat(mCollectedIndices).isEqualTo(Arrays.asList(4, 3, 5, 2, 6, 1, 7, 0, 8, 9));
  }

  @Override
  public boolean process(int index) {
    mCollectedIndices.add(index);
    return true;
  }
}
