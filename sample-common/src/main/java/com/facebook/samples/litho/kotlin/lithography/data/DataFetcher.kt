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

package com.facebook.samples.litho.kotlin.lithography.data

import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData

class DataFetcher(val model: MutableLiveData<Model>) : (Int) -> Unit {
  private var fetching = -1

  override fun invoke(lastFetchedDecade: Int) {
    if (fetching == lastFetchedDecade + 1) {
      return
    }

    fetching = lastFetchedDecade + 1

    model.value = model.value?.let { Model(it.decades, true) }

    FetchTask(model, lastFetchedDecade).execute()
  }
}

class FetchTask(val model: MutableLiveData<Model>, private val lastFetchedDecade: Int) :
    AsyncTask<Void, Void, List<Decade>>() {
  override fun doInBackground(vararg params: Void?): List<Decade> {
    // Let's simulate a network call here.
    Thread.sleep(2000)
    return DataCreator.createPageOfData(lastFetchedDecade + 1)
  }

  override fun onPostExecute(result: List<Decade>?) {
    result?.let { fetchedDecades ->
      val decades = model.value?.decades ?: emptyList()
      model.value = Model(decades + fetchedDecades, false)
    }
  }
}
