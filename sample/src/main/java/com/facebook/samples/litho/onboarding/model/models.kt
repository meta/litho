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

package com.facebook.samples.litho.onboarding.model

import androidx.annotation.DrawableRes
import com.facebook.samples.litho.R

// start_example
class User(val username: String, @param:DrawableRes val avatarRes: Int)

class Post(
    val id: String,
    val user: User,
    @param:DrawableRes val imageRes: Int,
    val text: String? = null,
)

// end_example

val Ben: User = User(username = "Ben", avatarRes = R.drawable.ic_launcher)
val Lucas = User(username = "Lucas", avatarRes = R.drawable.ic_launcher)
val Lily = User(username = "Lily", avatarRes = R.drawable.ic_launcher)
val Lyra = User(username = "Lyra", avatarRes = R.drawable.ic_launcher)
val Nebula = User(username = "Nebula", avatarRes = R.drawable.ic_launcher)
val TotoLoki = User(username = "Toto & Loki", avatarRes = R.drawable.ic_launcher)

val NebulasPost =
    Post(id = "post1", user = Nebula, imageRes = R.drawable.nebula4, text = "Meow you doin?")

val Feed = listOf(
    Post(
        id = "post1",
        user = Ben,
        imageRes = R.drawable.ben,
        text = "Did someone say tuna?",
    ),
    Post(
        id = "post2",
        user = Lily,
        imageRes = R.drawable.lily,
        text = "Whisker-y Business",
    ),
    Post(
        id = "post3",
        user = Lily,
        imageRes = R.drawable.lily2,
        text = "I cat even right now",
    ),
    Post(
        id = "post4",
        user = Lily,
        imageRes = R.drawable.lily3,
        text = "It ain’t easy being puff-fect",
    ),
    Post(
        id = "post5",
        user = Lyra,
        imageRes = R.drawable.lyra,
        text = "Purr-fect morning!",
    ),
    Post(
        id = "post6",
        user = Lyra,
        imageRes = R.drawable.lyra2,
        text = "Napping is the thing I love meowst",
    ),
    Post(
        id = "post7",
        user = Lyra,
        imageRes = R.drawable.lyra3,
        text = "Looking good, feline good",
    ),
    Post(
        id = "post8",
        user = Lyra,
        imageRes = R.drawable.lyra4,
        text = "It’s the purrfect time for a nap",
    ),
    Post(
        id = "post9",
        user = Lyra,
        imageRes = R.drawable.lyra5,
        text = "How claw-some is that?",
    ),
    Post(
        id = "post10",
        user = Lucas,
        imageRes = R.drawable.lucascat,
        text = "Stay pawsitive!",
    ),
    NebulasPost,
    Post(
        id = "post11",
        user = Nebula,
        imageRes = R.drawable.nebula,
        text = "I’ll have a meow-tini",
    ),
    Post(
        id = "post12",
        user = Nebula,
        imageRes = R.drawable.nebula2,
        text = "Meow-nificent",
    ),
    Post(
        id = "post13",
        user = Nebula,
        imageRes = R.drawable.nebula3,
        text = "Every day is a cat day for me",
    ),
    Post(
        id = "post15",
        user = Nebula,
        imageRes = R.drawable.nebula5,
        text = "We’re purrfect for each other",
    ),
    Post(
        id = "post16",
        user = Nebula,
        imageRes = R.drawable.nebula6,
        text = "So fur so good!",
    ),
    Post(
        id = "post17",
        user = Nebula,
        imageRes = R.drawable.nebula7,
        text = "Catitude is everything ",
    ),
    Post(
        id = "post18",
        user = TotoLoki,
        imageRes = R.drawable.toto_loki,
        text = "Do you believe in furry tails",
    ),
    Post(
        id = "post19",
        user = TotoLoki,
        imageRes = R.drawable.toto_loki2,
        text = "Best fur-ends",
    ),
    Post(
        id = "post20",
        user = TotoLoki,
        imageRes = R.drawable.toto_loki3,
        text = "Tabby or not tabby? That is the question",
    ),
    Post(
        id = "post21",
        user = TotoLoki,
        imageRes = R.drawable.toto_loki4,
        text = "Claw-less",
    ),
    Post(
        id = "post22",
        user = TotoLoki,
        imageRes = R.drawable.toto_loki5,
        text = "You had me at meow",
    ),
    Post(
        id = "post23",
        user = TotoLoki,
        imageRes = R.drawable.toto_loki6,
        text = "Don’t worry. Be tabby",
    ),
)
    .shuffled()

val UserStories = List(10) { User("story$it", R.drawable.ic_launcher) }
