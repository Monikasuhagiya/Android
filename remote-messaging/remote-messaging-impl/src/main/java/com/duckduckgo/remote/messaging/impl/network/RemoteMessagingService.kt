/*
 * Copyright (c) 2021 DuckDuckGo
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

package com.duckduckgo.remote.messaging.impl.network

import com.duckduckgo.anvil.annotations.ContributesServiceApi
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.remote.messaging.impl.models.JsonRemoteMessagingConfig
import retrofit2.http.GET

@ContributesServiceApi(AppScope::class)
interface RemoteMessagingService {
    // @GET("https://staticcdn.duckduckgo.com/remotemessaging/config/v1/android-config.json")
    @GET("http://www.jsonblob.com/api/1235947624862703616")
    suspend fun config(): JsonRemoteMessagingConfig
}
