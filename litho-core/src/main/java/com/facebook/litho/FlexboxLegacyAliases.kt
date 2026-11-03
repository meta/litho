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

@file:Suppress("DEPRECATION")

package com.facebook.litho

/**
 * Migration scaffolding: a typealias for the legacy SpecGen [Column] class under a non-colliding
 * name. See [RowBuilder] for rationale.
 */
@Deprecated(
    "Migration scaffold. Use com.facebook.litho.layout.flexbox.Column(...) for new construction. " +
        "This alias only exists so files that also import the new Column function can keep " +
        "calling Column.create(c).build() under the disambiguated name ColumnBuilder.create(c).build().",
)
typealias ColumnBuilder = Column
