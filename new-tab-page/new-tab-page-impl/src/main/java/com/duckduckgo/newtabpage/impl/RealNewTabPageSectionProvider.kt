/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.newtabpage.impl

import com.duckduckgo.anvil.annotations.ContributesActivePluginPoint
import com.duckduckgo.common.utils.plugins.ActivePluginPoint
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.newtabpage.api.NewTabPageSectionPlugin
import com.duckduckgo.newtabpage.api.NewTabPageSectionProvider
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@ContributesBinding(
    scope = AppScope::class,
)
class RealNewTabPageSectionProvider @Inject constructor(
    private val newTabPageSections: ActivePluginPoint<@JvmSuppressWildcards NewTabPageSectionPlugin>,
) : NewTabPageSectionProvider {
    override fun provideSections(): Flow<List<NewTabPageSectionPlugin>> = flow {
        emit(newTabPageSections.getPlugins().map { it })
    }
}

@ContributesActivePluginPoint(
    scope = AppScope::class,
    boundType = NewTabPageSectionPlugin::class,
)
private interface NewTabPageSectionPluginPointTrigger
